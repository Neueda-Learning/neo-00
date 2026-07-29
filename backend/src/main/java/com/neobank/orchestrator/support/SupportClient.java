package com.neobank.orchestrator.support;

import com.neobank.orchestrator.config.UpstreamModuleException;
import com.neobank.orchestrator.domain.Application;
import com.neobank.orchestrator.domain.ApplicationRepository;
import com.neobank.orchestrator.saga.SagaStore;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Opening a support case for a customer, on the module that runs the support desk.
 *
 * <h2>Why this one is configured by URL and not by step</h2>
 *
 * <p>The support module is <b>not part of the journey</b> and must not be added to
 * {@code orchestrator.services[]} — it is an analytical module that observes applications rather
 * than deciding them, and an entry there would make the saga dispatch to it and wait for an
 * answer it has no business giving. So it gets its own address, {@code orchestrator.support
 * .base-url}, and is reachable only through this one deliberate call.</p>
 *
 * <h2>One case per application</h2>
 *
 * <p>The module derives its case id from the correlation id, so a second request for the same
 * application returns the first case rather than opening another. That is the behaviour we want —
 * a customer pressing the button twice should not open two tickets — and it is why
 * {@link #findCase} searches for the case afterwards rather than reading an id off the reply: the
 * {@code 202} carries none, because on the second call there is nothing new to report.</p>
 */
@Service
public class SupportClient {

    private static final Logger log = LoggerFactory.getLogger(SupportClient.class);

    /** The module's command word for "open a case". Its contract, not ours. */
    private static final String OPEN_CASE = "open-case";

    /**
     * A customer's own case, as they should see it: what they asked, where it has got to, and
     * anything the bank has said back.
     *
     * <p>Narrower than the module's case view, which also carries the SLA deadline, whether it has
     * been breached, the priority, the assignee and the config version. Those are how the support
     * desk runs itself — a customer being shown that their question is a P3 and that the bank is
     * four hours from missing its own target is worse than showing them nothing.</p>
     */
    public record CaseView(String reference, String status, String category, String description,
                           Instant openedAt, Instant resolvedAt, String resolutionNote,
                           List<CaseUpdate> updates) {
    }

    /** One thing that happened on the case and was worth saying out loud. */
    public record CaseUpdate(String status, String actor, String note, Instant at) {
    }

    /** The module's search row. Only the id is used — the detail call carries the rest. */
    private record CaseSummary(String caseId) {
    }

    /** The module's case detail. Fields we do not pass on are read here and stop here. */
    private record CaseDetail(String caseId, String status, String category, String description,
                              String resolutionNote, Instant openedAt, Instant resolvedAt,
                              List<CaseEvent> events) {
    }

    private record CaseEvent(String type, String toStatus, String actor, String note, Instant at) {
    }

    private final RestClient http;
    private final ApplicationRepository applications;
    private final SagaStore store;
    private final String baseUrl;

    public SupportClient(RestClient http, ApplicationRepository applications, SagaStore store,
                         @Value("${orchestrator.support.base-url:http://localhost:9009}") String baseUrl) {
        this.http = http;
        this.applications = applications;
        this.store = store;
        this.baseUrl = baseUrl;
    }

    /**
     * Open a case about one application.
     *
     * <p>The customer supplies only what they know — what it is about, and what happened. The
     * application id, its correlation id and the application itself come from here, because a
     * page must not be trusted to say which application it is talking about.</p>
     */
    public Map<String, Object> openCase(String applicationId, String category, String description) {
        Application app = applications.findById(applicationId).orElseThrow(
                () -> new UpstreamModuleException(HttpStatus.NOT_FOUND,
                        "no application " + applicationId));

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("applicationId", app.getId());
        envelope.put("correlationId", app.getCorrelationId());
        envelope.put("command", OPEN_CASE);
        envelope.put("application", store.application(app.getId()).orElse(Map.of()));
        envelope.put("request", Map.of(
                "category", category,
                "description", description,
                "channel", app.getChannel() == null ? "WEB" : app.getChannel()));

        try {
            Map<String, Object> answer = http.post()
                    .uri(baseUrl + "/api/v1/support/execute")
                    .body(envelope)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});
            log.info("Support case opened for {} ({})", applicationId, category);
            return answer == null ? Map.of("status", "in-progress") : answer;
        } catch (RestClientResponseException e) {
            log.warn("The support module refused a case for {}: {}", applicationId, e.getStatusText());
            throw UpstreamModuleException.from(e,
                    "we could not open a support case just now — please try again in a moment");
        } catch (Exception e) {
            log.warn("Could not reach the support module about {}: {}", applicationId, e.toString());
            throw UpstreamModuleException.unreachable(
                    "the support desk is not responding — please try again in a moment", e);
        }
    }

    /**
     * The case a customer already has about this application, or empty if they have none.
     *
     * <p>Found by searching the module rather than by computing the id. The case id is
     * {@code UUID.nameUUIDFromBytes(correlationId)} on the module's side, and reimplementing that
     * here would be a second copy of a rule only it should own — one hash change and every
     * customer would be shown "no case" while holding one. The module's own search takes an
     * application id, so it can answer the question itself.</p>
     *
     * <p><b>Read-only.</b> There is no way for a customer to reply: every transition on the module
     * is an operator action with an actor, and a customer is not one. Adding a reply is a change
     * to that module's API, not something to fake from here.</p>
     */
    public Optional<CaseView> findCase(String applicationId) {
        try {
            List<CaseSummary> found = http.get()
                    .uri(baseUrl + "/api/v1/support/cases?q=" + applicationId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<CaseSummary>>() {});
            if (found == null || found.isEmpty()) {
                return Optional.empty();
            }
            // One case per application — the module dedupes on the correlation id — so the first
            // row is the only row. A name search could match several; an application id cannot.
            CaseDetail detail = http.get()
                    .uri(baseUrl + "/api/v1/support/cases/" + found.get(0).caseId())
                    .retrieve()
                    .body(CaseDetail.class);
            return detail == null ? Optional.empty() : Optional.of(toView(detail));
        } catch (Exception e) {
            // A customer who cannot be shown their case should be offered the form again, not an
            // error: the module dedupes, so submitting twice costs nothing and returns the case
            // they already had.
            log.warn("Could not read the support case for {}: {}", applicationId, e.toString());
            return Optional.empty();
        }
    }

    private static CaseView toView(CaseDetail c) {
        List<CaseUpdate> updates = (c.events() == null ? List.<CaseEvent>of() : c.events()).stream()
                // Only entries that actually say something. A bare status change with no note is
                // internal bookkeeping, and a timeline of "OPEN. OPEN. OPEN." tells a customer
                // nothing they did not already know.
                .filter(e -> e.note() != null && !e.note().isBlank())
                // SYSTEM entries are the module's own audit trail, and they DUPLICATE the
                // operator's words with a reason code stapled on ("SUP_RESOLVED: ..."). Resolving
                // a case writes three rows carrying one sentence; a customer should be shown it
                // once, in the words a person actually typed.
                .filter(e -> !"SYSTEM".equalsIgnoreCase(e.actor()))
                .map(e -> new CaseUpdate(e.toStatus(), e.actor(), e.note(), e.at()))
                .toList();
        return new CaseView(reference(c.caseId()), c.status(), c.category(), c.description(),
                c.openedAt(), c.resolvedAt(), c.resolutionNote(), updates);
    }

    /**
     * The short form the support desk's own board shows, so a customer quoting their reference and
     * an operator searching for it are looking at the same string.
     */
    private static String reference(String caseId) {
        if (caseId == null) {
            return null;
        }
        String withoutPrefix = caseId.startsWith("case-") ? caseId.substring(5) : caseId;
        int dash = withoutPrefix.indexOf('-');
        return dash < 0 ? withoutPrefix : withoutPrefix.substring(0, dash);
    }
}
