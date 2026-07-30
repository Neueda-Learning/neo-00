package com.neobank.orchestrator.agreement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.neobank.orchestrator.agreement.AgreementClient.AgreementView;
import com.neobank.orchestrator.agreement.AgreementClient.SignedDocument;
import com.neobank.orchestrator.config.UpstreamModuleException;
import com.neobank.orchestrator.saga.ServiceRegistry;
import com.neobank.orchestrator.saga.ServiceRegistry.ServiceDef;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * What actually goes over the wire to the agreement module, and what comes back to the customer.
 *
 * <p>{@link MockRestServiceServer} rather than a mocked client on purpose: every module in this
 * project asserts its outbound calls with Mockito on Java arguments, which passes whether or not
 * anything reachable is ever sent. The half that carries the customer's own decision is exactly
 * the half worth pinning at the socket.</p>
 */
class AgreementClientTest {

    private static final String BASE = "http://neo-06:8080";

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private AgreementClient client;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        ServiceRegistry registry = new ServiceRegistry(List.of(
                new ServiceDef(1, "neo01", "Application Verification", "http://neo-01:8080"),
                new ServiceDef(6, "neo06", "Agreement Management", BASE)));
        client = new AgreementClient(builder.build(), registry, "neo06");
    }

    private static final String PENDING_CASE = """
            {"status":"PENDING","reference":"agr-000001","termsVersion":"2026-06-01",
             "approvedLimit":3000,"apr":24.9,"minPaymentGbp":90,
             "envelopeId":"env-abc123","sentAt":"2026-07-29T10:00:00Z",
             "expiresAt":"2026-08-03T10:00:00Z","signedAt":null,
             "timeline":[],"documentAvailable":true}""";

    @Test
    void theCustomerViewCarriesTheTermsAndNotTheModulesInternals() {
        server.expect(requestTo(BASE + "/cases/APP-0001"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(PENDING_CASE, MediaType.APPLICATION_JSON));

        AgreementView view = client.view("APP-0001");

        assertThat(view.status()).isEqualTo("PENDING");
        assertThat(view.approvedLimit()).isEqualTo(3000);
        assertThat(view.minPaymentGbp()).isEqualTo(90);
        assertThat(view.documentAvailable()).isTrue();
        assertThat(view.signable()).isTrue();
        // The envelope id and the operator timeline are read and stopped here: a customer's page
        // has no business knowing either, and passing the module's view through whole would tie
        // this surface to its shape.
        assertThat(AgreementView.class.getRecordComponents())
                .noneMatch(c -> c.getName().equals("envelopeId") || c.getName().equals("timeline"));
        server.verify();
    }

    @Test
    void anAlreadySignedAgreementIsNotOfferedForSigningAgain() {
        server.expect(requestTo(BASE + "/cases/APP-0001"))
                .andRespond(withSuccess(PENDING_CASE.replace("\"PENDING\"", "\"SIGNED\""),
                        MediaType.APPLICATION_JSON));

        assertThat(client.view("APP-0001").signable()).isFalse();
    }

    @Test
    void signingSendsTheCasesCurrentEnvelopeAndTheCustomersWord() {
        server.expect(requestTo(BASE + "/cases/APP-0001"))
                .andRespond(withSuccess(PENDING_CASE, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/cases/APP-0001/signature-events"))
                .andExpect(method(HttpMethod.POST))
                // The envelope comes from the case, never from the browser: a page open for ten
                // minutes holds exactly the stale envelope the module is right to refuse.
                .andExpect(jsonPath("$.envelopeId").value("env-abc123"))
                .andExpect(jsonPath("$.event").value("SIGNED"))
                .andExpect(jsonPath("$.occurredAt").exists())
                .andRespond(withSuccess("{\"applicationId\":\"APP-0001\",\"status\":\"SIGNED\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.decide("APP-0001", "SIGNED")).containsEntry("status", "SIGNED");
        server.verify();
    }

    @Test
    void decliningSendsTheSameShapeWithTheOtherWord() {
        server.expect(requestTo(BASE + "/cases/APP-0001"))
                .andRespond(withSuccess(PENDING_CASE, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/cases/APP-0001/signature-events"))
                .andExpect(jsonPath("$.event").value("DECLINED"))
                .andRespond(withSuccess("{\"status\":\"DECLINED\"}", MediaType.APPLICATION_JSON));

        client.decide("APP-0001", "DECLINED");
        server.verify();
    }

    /**
     * The module's provider-unavailable path leaves a PENDING case with no envelope. Signing it
     * would be a {@code 400} from the module for a missing field, which reads to a customer as
     * "your request was wrong" when in fact nothing was ever sent to them.
     */
    @Test
    void anAgreementWithNoEnvelopeIsAReadable409AndIsNeverSent() {
        server.expect(requestTo(BASE + "/cases/APP-0001"))
                .andRespond(withSuccess(PENDING_CASE.replace("\"env-abc123\"", "null"),
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.decide("APP-0001", "SIGNED"))
                .isInstanceOf(UpstreamModuleException.class)
                .hasMessageContaining("nothing to sign")
                .extracting(e -> ((UpstreamModuleException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
        // No signature-event call was made — the expectation list held exactly one request.
        server.verify();
    }

    @Test
    void theModulesOwnRefusalKeepsItsStatusAndItsSentence() {
        server.expect(requestTo(BASE + "/cases/APP-0001"))
                .andRespond(withSuccess(PENDING_CASE, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/cases/APP-0001/signature-events"))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"case APP-0001 already EXPIRED\"}"));

        assertThatThrownBy(() -> client.decide("APP-0001", "SIGNED"))
                .isInstanceOf(UpstreamModuleException.class)
                .hasMessageContaining("already EXPIRED")
                .extracting(e -> ((UpstreamModuleException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void anUnknownCaseIs404NotAServerError() {
        server.expect(requestTo(BASE + "/cases/APP-NOPE"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.view("APP-NOPE"))
                .isInstanceOf(UpstreamModuleException.class)
                .extracting(e -> ((UpstreamModuleException) e).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** A module falling over is our problem to describe, not a 500 the customer has to read. */
    @Test
    void aModuleFailureBecomesABadGatewayWithCustomerReadableWords() {
        server.expect(requestTo(BASE + "/cases/APP-0001"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.view("APP-0001"))
                .isInstanceOf(UpstreamModuleException.class)
                .hasMessageNotContaining("500")
                .extracting(e -> ((UpstreamModuleException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @Test
    void theDocumentComesBackAsBytesWithItsTypeAndName() {
        server.expect(requestTo(BASE + "/cases/APP-0001/document"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("%PDF-1.4 pretend".getBytes(StandardCharsets.UTF_8),
                        MediaType.APPLICATION_PDF));

        SignedDocument document = client.document("APP-0001");

        assertThat(new String(document.content(), StandardCharsets.UTF_8)).startsWith("%PDF");
        assertThat(document.contentType()).startsWith(MediaType.APPLICATION_PDF_VALUE);
        assertThat(document.fileName()).isEqualTo("agreement-APP-0001.pdf");
    }

    @Test
    void aServiceMissingFromTheJourneyIsSaidPlainlyRatherThanNullPointing() {
        AgreementClient orphan = new AgreementClient(builder.build(),
                new ServiceRegistry(List.of()), "neo06");

        assertThatThrownBy(() -> orphan.view("APP-0001"))
                .isInstanceOf(UpstreamModuleException.class)
                .hasMessageContaining("neo06");
    }

    @Test
    void theSignatureEventIsSentAsJson() {
        server.expect(requestTo(BASE + "/cases/APP-0001"))
                .andRespond(withSuccess(PENDING_CASE, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/cases/APP-0001/signature-events"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.decide("APP-0001", "SIGNED");
        server.verify();
    }
}
