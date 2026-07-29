package com.neobank.orchestrator.agreement;

import com.neobank.orchestrator.config.UpstreamModuleException;
import com.neobank.orchestrator.saga.ServiceRegistry;
import com.neobank.orchestrator.saga.ServiceRegistry.ServiceDef;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Talking to the module that owns credit agreements, on the customer's behalf.
 *
 * <p>Three calls: read the case, fetch its PDF, and report what the customer did with it. All
 * three are the module's own {@code /cases/...} family — <b>not</b> the fixed orchestrator
 * contract, which runs the other way ({@code POST /applications} out, {@code PUT
 * /applications/{id}} back). This class is the orchestrator being a client of a module for once,
 * which is why it lives outside {@code saga}.</p>
 */
@Service
public class AgreementClient {

    private static final Logger log = LoggerFactory.getLogger(AgreementClient.class);

    /**
     * The agreement as a customer needs it — what they are agreeing to, and whether it is theirs
     * to sign yet.
     *
     * <p>Deliberately narrower than the module's own case view. That one also carries the
     * envelope id, the operator timeline and the internal reference, which belong on the bank's
     * screens and not on a customer's; handing the whole thing through would put a module's
     * internals on the public surface and tie this page to its shape.</p>
     */
    public record AgreementView(String status, Integer approvedLimit, BigDecimal apr,
                                Integer minPaymentGbp, String termsVersion, Instant expiresAt,
                                Instant signedAt, boolean documentAvailable, boolean signable) {
    }

    /** A PDF on its way to the browser. */
    public record SignedDocument(byte[] content, String contentType, String fileName) {
    }

    /** The module's case view. Fields we do not pass on are read here and stop here. */
    private record CaseDetail(String status, String reference, String termsVersion,
                              Integer approvedLimit, BigDecimal apr, Integer minPaymentGbp,
                              String envelopeId, Instant sentAt, Instant expiresAt,
                              Instant signedAt, boolean documentAvailable) {
    }

    /** The module's word for "sent, and waiting on the customer". */
    private static final String PENDING = "PENDING";

    private final RestClient http;
    private final ServiceRegistry registry;
    private final String serviceId;

    public AgreementClient(RestClient http, ServiceRegistry registry,
                           @Value("${orchestrator.signature.service-id:neo06}") String serviceId) {
        this.http = http;
        this.registry = registry;
        this.serviceId = serviceId;
    }

    public AgreementView view(String applicationId) {
        CaseDetail c = caseDetail(applicationId);
        return new AgreementView(c.status(), c.approvedLimit(), c.apr(), c.minPaymentGbp(),
                c.termsVersion(), c.expiresAt(), c.signedAt(), c.documentAvailable(),
                PENDING.equals(c.status()) && c.envelopeId() != null);
    }

    public SignedDocument document(String applicationId) {
        ResponseEntity<byte[]> response;
        try {
            response = http.get()
                    .uri(baseUrl() + "/cases/" + applicationId + "/document")
                    .retrieve()
                    .toEntity(byte[].class);
        } catch (RestClientResponseException e) {
            throw upstream(e, applicationId, "agreement document");
        } catch (Exception e) {
            throw unreachable(applicationId, e);
        }
        MediaType type = response.getHeaders().getContentType();
        String disposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        return new SignedDocument(
                response.getBody() == null ? new byte[0] : response.getBody(),
                type == null ? MediaType.APPLICATION_PDF_VALUE : type.toString(),
                fileNameFrom(disposition, applicationId));
    }

    /**
     * Tell the module what the customer decided.
     *
     * <p>The module matches the event against the case's <em>current</em> envelope, so the
     * envelope is read here rather than asked of the browser — a customer's page has no business
     * knowing an envelope id, and one held in a page open for ten minutes is exactly the stale
     * one the module is right to refuse.</p>
     *
     * <p>A case with no envelope at all is the module's provider-unavailable path: the agreement
     * exists but was never sent. That is a {@code 409} and not the {@code 400} the module would
     * answer, because nothing about the customer's request is malformed — there is simply
     * nothing to sign yet.</p>
     */
    public Map<String, Object> decide(String applicationId, String event) {
        CaseDetail c = caseDetail(applicationId);
        if (c.envelopeId() == null) {
            throw new UpstreamModuleException(HttpStatus.CONFLICT,
                    "this agreement has not been sent for signature yet — there is nothing to sign");
        }
        Map<String, Object> body = Map.of(
                "envelopeId", c.envelopeId(),
                "event", event,
                "occurredAt", Instant.now().toString());
        try {
            Map<String, Object> answer = http.post()
                    .uri(baseUrl() + "/cases/" + applicationId + "/signature-events")
                    .body(body)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});
            log.info("Customer reported {} on {} — {} decides what that means for the journey",
                    event, applicationId, serviceId);
            return answer == null ? Map.of() : answer;
        } catch (RestClientResponseException e) {
            throw upstream(e, applicationId, "signature event");
        } catch (Exception e) {
            throw unreachable(applicationId, e);
        }
    }

    private CaseDetail caseDetail(String applicationId) {
        try {
            CaseDetail c = http.get()
                    .uri(baseUrl() + "/cases/" + applicationId)
                    .retrieve()
                    .body(CaseDetail.class);
            if (c == null) {
                throw new UpstreamModuleException(HttpStatus.NOT_FOUND,
                        "no agreement for " + applicationId);
            }
            return c;
        } catch (RestClientResponseException e) {
            throw upstream(e, applicationId, "agreement");
        } catch (UpstreamModuleException e) {
            throw e;
        } catch (Exception e) {
            throw unreachable(applicationId, e);
        }
    }

    private String baseUrl() {
        ServiceDef service = registry.byServiceId(serviceId);
        if (service == null) {
            throw new UpstreamModuleException(HttpStatus.SERVICE_UNAVAILABLE,
                    "no service '" + serviceId + "' in the journey — agreements are not available");
        }
        return service.baseUrl();
    }

    private UpstreamModuleException upstream(RestClientResponseException e, String applicationId,
                                             String what) {
        log.warn("{} refused the {} request for {}: {}", serviceId, what, applicationId,
                e.getStatusText());
        return UpstreamModuleException.from(e,
                "we could not fetch your " + what + " just now — please try again in a moment");
    }

    private UpstreamModuleException unreachable(String applicationId, Exception e) {
        log.warn("Could not reach {} about {}: {}", serviceId, applicationId, e.toString());
        return UpstreamModuleException.unreachable(
                "the agreement service is not responding — please try again in a moment", e);
    }

    private static String fileNameFrom(String contentDisposition, String applicationId) {
        String fallback = "agreement-" + applicationId + ".pdf";
        if (contentDisposition == null) {
            return fallback;
        }
        int at = contentDisposition.indexOf("filename=");
        if (at < 0) {
            return fallback;
        }
        String name = contentDisposition.substring(at + "filename=".length()).replace("\"", "").trim();
        return name.isEmpty() ? fallback : name;
    }
}
