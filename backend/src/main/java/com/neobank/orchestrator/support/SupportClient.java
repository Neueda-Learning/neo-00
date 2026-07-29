package com.neobank.orchestrator.support;

import com.neobank.orchestrator.config.UpstreamModuleException;
import com.neobank.orchestrator.domain.Application;
import com.neobank.orchestrator.domain.ApplicationRepository;
import com.neobank.orchestrator.saga.SagaStore;
import java.util.LinkedHashMap;
import java.util.Map;
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
 * a customer pressing the button twice should not open two tickets — but it means the answer
 * carries no new case reference, which is why the page confirms receipt rather than quoting a
 * number.</p>
 */
@Service
public class SupportClient {

    private static final Logger log = LoggerFactory.getLogger(SupportClient.class);

    /** The module's command word for "open a case". Its contract, not ours. */
    private static final String OPEN_CASE = "open-case";

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
}
