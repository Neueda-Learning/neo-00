package com.neobank.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.neobank.orchestrator.domain.Application;
import com.neobank.orchestrator.domain.ApplicationEvent;
import com.neobank.orchestrator.domain.ApplicationEventRepository;
import com.neobank.orchestrator.domain.ApplicationRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * INTEGRATION TEST (name ends in {@code IT} → runs on {@code ./mvnw verify}, needs Docker).
 *
 * <p>Liquibase creates the schema on a real MySQL 8 and Hibernate validates the entities
 * against that DDL. This is what catches what H2 cannot — notably that {@code payload_json}
 * is a {@code TEXT} column, because the application payload is well over a {@code VARCHAR}'s
 * worth and would only fail on {@code docker compose up}.</p>
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class OrchestratorSchemaIT {

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("neo_00");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("orchestrator.generator.enabled", () -> false);
    }

    @MockBean(answer = Answers.RETURNS_DEEP_STUBS)
    RestClient restClient;

    @Autowired
    ApplicationRepository applications;

    @Autowired
    ApplicationEventRepository events;

    @Test
    void schemaValidatesAndStartsEmpty() {
        assertThat(applications.findAll()).isEmpty();
    }

    @Test
    void applicationAndItsLogRoundTripThroughRealMysql() {
        String bigPayload = "{\"filler\":\"" + "x".repeat(6000) + "\"}";
        Application saved = applications.save(new Application(
                "APP-9001", "COR-1", "Maria Nowak", "CREDIT_CARD_REWARDS", 3000,
                "MOBILE_APP", bigPayload));

        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(applications.findById("APP-9001").orElseThrow().getPayloadJson())
                .hasSize(bigPayload.length());   // TEXT, not VARCHAR — no silent truncation

        events.save(new ApplicationEvent("APP-9001", 0, null,
                ApplicationEvent.JOURNEY_STARTED, null, "created"));
        events.save(new ApplicationEvent("APP-9001", 1, "neo01",
                ApplicationEvent.REQUEST_SENT, null, "sent"));
        events.save(new ApplicationEvent("APP-9001", 1, "neo01",
                ApplicationEvent.CALLBACK, "ACCEPTED", "ok"));

        assertThat(events.findByApplicationIdOrderByIdAsc("APP-9001"))
                .extracting(ApplicationEvent::getEventType)
                .containsExactly("JOURNEY_STARTED", "REQUEST_SENT", "CALLBACK");
        assertThat(events.countCallbacksByServiceAndStatus()).isNotEmpty();
    }

    @Test
    void overlongCommentsAreTruncatedRatherThanFailingTheInsert() {
        events.save(new ApplicationEvent("APP-9002", 1, "neo01",
                ApplicationEvent.DISPATCH_FAILED, null, "e".repeat(4000)));

        assertThat(events.findByApplicationIdOrderByIdAsc("APP-9002").get(0).getComment())
                .hasSize(500);
    }
}
