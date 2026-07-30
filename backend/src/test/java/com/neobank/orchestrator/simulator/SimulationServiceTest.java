package com.neobank.orchestrator.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.neobank.orchestrator.saga.SagaDtos.ApplicationStatusUpdate;
import com.neobank.orchestrator.saga.StatusVocabulary;
import com.neobank.orchestrator.simulator.SimulationService.DispatchCommand;
import com.neobank.orchestrator.simulator.SimulationService.DispatchView;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * The dispatch half of the simulator, against a stubbed module.
 *
 * <p><b>A real {@link RestClient} bound to {@link MockRestServiceServer}, not a mocked one.</b>
 * A deep-stubbed {@code RestClient} cannot express this chain: Mockito has to fabricate the
 * {@code ResponseEntity} that {@code toEntity} returns, and deep-stubbing that means deep-stubbing
 * {@code HttpStatusCode}, which is sealed — so the stub silently fails to match, every dispatch
 * falls into the unreachable branch, and the test reads as "the module answered 0". Binding a mock
 * server instead exercises the URL, the method and the body that actually leave the process, which
 * is the half of the contract a Mockito argument check never sees.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(SimulationServiceTest.StubModule.class)
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:simulation;MODE=MySQL;DB_CLOSE_DELAY=-1")
class SimulationServiceTest {

    /**
     * The mock server has to be bound to the builder <em>before</em> the client is built, which is
     * what the bean dependency below enforces. {@code @Primary} so the simulator gets this client
     * rather than {@code AppConfig}'s.
     */
    @TestConfiguration
    static class StubModule {

        @Bean
        RestClient.Builder stubModuleBuilder() {
            return RestClient.builder();
        }

        @Bean
        MockRestServiceServer stubModuleServer(RestClient.Builder stubModuleBuilder) {
            return MockRestServiceServer.bindTo(stubModuleBuilder).ignoreExpectOrder(true).build();
        }

        @Bean
        @Primary
        RestClient stubModuleClient(RestClient.Builder stubModuleBuilder,
                                    MockRestServiceServer stubModuleServer) {
            return stubModuleBuilder.build();
        }
    }

    private static final String NEO05 = "http://localhost:9005/api/v1/applications";

    @Autowired
    MockRestServiceServer server;

    @Autowired
    SimulationService service;

    @Autowired
    SimulationRepository repository;

    @BeforeEach
    void clear() {
        repository.deleteAll();
        server.reset();
    }

    private void moduleAccepts() {
        server.expect(ExpectedCount.manyTimes(), requestTo(NEO05))
                .andExpect(method(HttpMethod.POST))
                // The id the module is told to work on must be the rewritten one, not SIM-26.
                .andExpect(jsonPath("$.applicationId").exists())
                .andRespond(withStatus(HttpStatus.ACCEPTED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":\"in-progress\",\"serviceId\":\"neo05\"}"));
    }

    @Test
    void freshIdMovesBothCopiesAndAnAbsentNestedIdStaysAbsent() {
        moduleAccepts();

        DispatchView first = service.dispatch(new DispatchCommand(
                null, envelope("SIM-26", true), "neo05"));
        DispatchView second = service.dispatch(new DispatchCommand(
                null, envelope("SIM-26", true), "neo05"));

        assertThat(first.applicationId()).matches("SIM-26-neo05-\\d+");
        assertThat(second.applicationId()).isNotEqualTo(first.applicationId());
        assertThat(first.ackHttpStatus()).isEqualTo(202);
        assertThat(first.application().get("applicationId")).isEqualTo(first.applicationId());

        // SIM-26 carries no nested id on purpose. Freshening must not invent one.
        DispatchView absent = service.dispatch(new DispatchCommand(
                null, envelope("SIM-26", false), "neo05"));
        assertThat(absent.application()).doesNotContainKey("applicationId");
    }

    @Test
    void unreachableTargetIsRecordedAsStatusZeroRatherThanThrown() {
        server.expect(ExpectedCount.manyTimes(), requestTo(
                        "http://localhost:9001/api/v1/applications"))
                .andRespond(withException(new IOException("connection refused")));

        DispatchView result = service.dispatch(new DispatchCommand(
                null, envelope("SIM-01", true), "neo01"));

        assertThat(result.ackHttpStatus()).isZero();
        assertThat(String.valueOf(result.ackBody())).isNotBlank();
        assertThat(repository.findById(result.id()).orElseThrow().getAckHttpStatus()).isZero();
    }

    @Test
    void reportsPairOldestUnansweredFirstAndKeepRawBesideCanonical() {
        Simulation first = row("SAME-ID");
        Simulation second = row("SAME-ID");

        assertThat(service.report("SAME-ID",
                new ApplicationStatusUpdate("neo04", "CLEAR", "first"))).isTrue();
        assertThat(repository.findById(first.getId()).orElseThrow().getReportedStatus())
                .isEqualTo("CLEAR");
        assertThat(repository.findById(first.getId()).orElseThrow().getCanonicalStatus())
                .isEqualTo(StatusVocabulary.ACCEPTED);
        assertThat(repository.findById(second.getId()).orElseThrow().getReportedAt()).isNull();

        assertThat(service.report("SAME-ID",
                new ApplicationStatusUpdate("neo04", "MYSTERY", "second"))).isTrue();
        assertThat(repository.findById(second.getId()).orElseThrow().getReportedStatus())
                .isEqualTo("MYSTERY");
        assertThat(repository.findById(second.getId()).orElseThrow().getCanonicalStatus()).isNull();
        assertThat(service.report("SAME-ID",
                new ApplicationStatusUpdate("neo04", "CLEAR", "duplicate"))).isFalse();
    }

    @Test
    void anIdOwnedByNeitherJourneyNorSimulationWritesNoSimulationRow() {
        long before = repository.count();
        assertThat(service.report("APP-STRAY",
                new ApplicationStatusUpdate("neo01", "PASSED", "lost"))).isFalse();
        assertThat(repository.count()).isEqualTo(before);
    }

    private Simulation row(String applicationId) {
        Simulation row = repository.saveAndFlush(
                new Simulation("pending", "corr", "SIM-01", "neo04", "http://localhost"));
        row.prepare(applicationId, "corr", "{\"applicationId\":\"" + applicationId + "\"}");
        return repository.saveAndFlush(row);
    }

    private static Map<String, Object> envelope(String id, boolean nestedId) {
        Map<String, Object> application = new LinkedHashMap<>();
        if (nestedId) application.put("applicationId", id);
        application.put("applicant", Map.of("fullName", "Maria Nowak"));
        application.put("product", Map.of("productCode", "CREDIT_CARD_REWARDS"));

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("applicationId", id);
        envelope.put("correlationId", "sim-correlation");
        envelope.put("command", "process-application");
        envelope.put("application", application);
        envelope.put("outputs", null);
        return envelope;
    }
}
