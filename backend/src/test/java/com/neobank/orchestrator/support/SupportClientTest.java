package com.neobank.orchestrator.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.neobank.orchestrator.config.UpstreamModuleException;
import com.neobank.orchestrator.domain.Application;
import com.neobank.orchestrator.domain.ApplicationRepository;
import com.neobank.orchestrator.saga.SagaStore;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** The one call the orchestrator makes to a module that is not part of the journey. */
class SupportClientTest {

    private static final String BASE = "http://neo-09:8080";

    private MockRestServiceServer server;
    private SupportClient client;
    private ApplicationRepository applications;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        applications = mock(ApplicationRepository.class);
        SagaStore store = mock(SagaStore.class);
        when(store.application(anyString()))
                .thenReturn(Optional.of(Map.of("channel", "WEB",
                        "applicant", Map.of("fullName", "Ada Lovelace"))));
        client = new SupportClient(builder.build(), applications, store, BASE);
    }

    private void haveApplication(String id) {
        Application app = new Application(id, "corr-" + id, "Ada Lovelace",
                "CREDIT_CARD_STANDARD", 3000, "WEB", "{}");
        when(applications.findById(id)).thenReturn(Optional.of(app));
    }

    @Test
    void theEnvelopeIsBuiltHereAndNotTrustedToThePage() {
        haveApplication("APP-0001");
        server.expect(requestTo(BASE + "/api/v1/support/execute"))
                .andExpect(method(HttpMethod.POST))
                // The customer supplies only what they know. Which application it is, and which
                // journey it belongs to, come from our own row — a page must not be able to open
                // a case against somebody else's application by editing a field.
                .andExpect(jsonPath("$.applicationId").value("APP-0001"))
                .andExpect(jsonPath("$.correlationId").value("corr-APP-0001"))
                .andExpect(jsonPath("$.command").value("open-case"))
                .andExpect(jsonPath("$.request.category").value("CARD_NOT_ARRIVED"))
                .andExpect(jsonPath("$.request.description").value("still waiting"))
                .andExpect(jsonPath("$.request.channel").value("WEB"))
                .andExpect(jsonPath("$.application.applicant.fullName").value("Ada Lovelace"))
                .andRespond(withStatus(HttpStatus.ACCEPTED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":\"in-progress\",\"applicationId\":\"APP-0001\"}"));

        assertThat(client.openCase("APP-0001", "CARD_NOT_ARRIVED", "still waiting"))
                .containsEntry("status", "in-progress");
        server.verify();
    }

    @Test
    void anUnknownApplicationIsRefusedBeforeAnythingIsSent() {
        when(applications.findById("APP-NOPE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> client.openCase("APP-NOPE", "COMPLAINT", "hello"))
                .isInstanceOf(UpstreamModuleException.class)
                .extracting(e -> ((UpstreamModuleException) e).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
        server.verify();   // no call was made
    }

    /** The module owns its taxonomy; a word it does not know is its refusal to pass on, not ours. */
    @Test
    void aCategoryTheModuleRejectsComesBackAsTheModulesOwnStatus() {
        haveApplication("APP-0001");
        server.expect(requestTo(BASE + "/api/v1/support/execute"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> client.openCase("APP-0001", "NOT_A_CATEGORY", "hello"))
                .isInstanceOf(UpstreamModuleException.class)
                .extracting(e -> ((UpstreamModuleException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---- reading the case back, so the customer can follow it ----

    private static final String CASE_DETAIL = """
            {"caseId":"case-d697da68-f15c-39e3-aa9e-65aeeb3e38f1","status":"RESOLVED",
             "category":"APPLICATION_STATUS","channel":"WEB","priority":"P3",
             "slaDeadline":"2026-08-01T15:03:25Z","breached":false,
             "applicationId":"APP-0001","correlationId":"corr-APP-0001","configVersion":1,
             "description":"when will my card arrive?","assignee":"dana","pausedMinutes":0,
             "resolutionNote":"Posted on Tuesday, with you in 3 working days.",
             "openedAt":"2026-07-29T15:03:25Z","resolvedAt":"2026-07-29T16:00:00Z",
             "events":[
               {"type":"CASE_OPENED","fromStatus":null,"toStatus":"NEW",
                "actor":"customer via orchestrator","note":"when will my card arrive?",
                "at":"2026-07-29T15:03:25Z"},
               {"type":"TRANSITION","fromStatus":"NEW","toStatus":"OPEN",
                "actor":"dana","note":null,"at":"2026-07-29T15:30:00Z"},
               {"type":"TRANSITION","fromStatus":"OPEN","toStatus":"RESOLVED",
                "actor":"dana","note":"Posted on Tuesday, with you in 3 working days.",
                "at":"2026-07-29T16:00:00Z"}]}""";

    private void expectCaseLookup(String applicationId, String searchBody, String detailBody) {
        server.expect(requestTo(BASE + "/api/v1/support/cases?q=" + applicationId))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(searchBody, MediaType.APPLICATION_JSON));
        if (detailBody != null) {
            server.expect(requestTo(
                            BASE + "/api/v1/support/cases/case-d697da68-f15c-39e3-aa9e-65aeeb3e38f1"))
                    .andRespond(withSuccess(detailBody, MediaType.APPLICATION_JSON));
        }
    }

    @Test
    void theCaseComesBackWithWhatTheBankSaidAndNoneOfHowItRunsItself() {
        expectCaseLookup("APP-0001",
                "[{\"caseId\":\"case-d697da68-f15c-39e3-aa9e-65aeeb3e38f1\"}]", CASE_DETAIL);

        var view = client.findCase("APP-0001").orElseThrow();

        assertThat(view.status()).isEqualTo("RESOLVED");
        assertThat(view.resolutionNote()).contains("Posted on Tuesday");
        // The short form the support desk's own board shows, so a customer quoting their
        // reference and an operator searching for it are looking at the same string.
        assertThat(view.reference()).isEqualTo("d697da68");
        // The SLA deadline, the breach flag, the priority and the assignee are how the desk runs
        // itself. Telling a customer their question is a P3 four hours from a missed target is
        // worse than telling them nothing.
        assertThat(view.getClass().getRecordComponents())
                .noneMatch(c -> java.util.List.of("priority", "slaDeadline", "breached", "assignee")
                        .contains(c.getName()));
        server.verify();
    }

    /** A status change nobody wrote a sentence about tells a customer nothing they did not know. */
    @Test
    void onlyUpdatesThatActuallySaySomethingAreShown() {
        expectCaseLookup("APP-0001",
                "[{\"caseId\":\"case-d697da68-f15c-39e3-aa9e-65aeeb3e38f1\"}]", CASE_DETAIL);

        var view = client.findCase("APP-0001").orElseThrow();

        // Three events, one of which is a bare NEW -> OPEN with no note.
        assertThat(view.updates()).hasSize(2);
        assertThat(view.updates()).extracting("note").doesNotContainNull();
        assertThat(view.updates().get(0).note()).isEqualTo("when will my card arrive?");
    }

    @Test
    void noCaseIsEmptyRatherThanAnError() {
        expectCaseLookup("APP-0001", "[]", null);

        assertThat(client.findCase("APP-0001")).isEmpty();
        server.verify();
    }

    /**
     * A customer who cannot be shown their case is offered the form again, not an error page.
     * The module dedupes on the correlation id, so sending twice costs nothing and returns the
     * case they already had.
     */
    @Test
    void aSupportDeskThatCannotBeAskedIsEmptyRatherThanAnError() {
        server.expect(requestTo(BASE + "/api/v1/support/cases?q=APP-0001"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThat(client.findCase("APP-0001")).isEmpty();
    }

    /**
     * A module falling over is our problem to describe, not a status code for a customer to
     * read. Note this is the module ANSWERING badly, not failing to answer — both end as a 502,
     * but only the second one is the desk being "down".
     */
    @Test
    void aModuleFailureBecomesABadGatewayInWordsACustomerCanRead() {
        haveApplication("APP-0001");
        server.expect(requestTo(BASE + "/api/v1/support/execute"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.openCase("APP-0001", "COMPLAINT", "hello"))
                .isInstanceOf(UpstreamModuleException.class)
                .hasMessageContaining("try again")
                .hasMessageNotContaining("500")
                .extracting(e -> ((UpstreamModuleException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_GATEWAY);
    }
}
