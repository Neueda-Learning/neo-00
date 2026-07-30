package com.neobank.orchestrator.simulator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neobank.orchestrator.config.UpstreamModuleException;
import com.neobank.orchestrator.saga.SagaDtos.ApplicationRequest;
import com.neobank.orchestrator.saga.SagaDtos.ApplicationStatusUpdate;
import com.neobank.orchestrator.saga.StatusVocabulary;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * One configured, synchronous call to one module.
 *
 * <p>Like {@code SupportClient}, this is addressed independently and builds its own envelope. It
 * must never use {@code SagaEngine}'s dispatch path: that path advances a journey and writes
 * board/tally events, while a simulation is deliberately invisible to both.</p>
 */
@Service
public class SimulationService {

    public record DispatchCommand(String scenarioId, Map<String, Object> envelope,
                                  String targetServiceId) {
    }

    public record DispatchView(Long id, String applicationId, String correlationId,
                               String scenarioId, String targetServiceId, String targetUrl,
                               Instant sentAt, Integer ackHttpStatus, Object ackBody,
                               String reportedServiceId, String reportedStatus,
                               String canonicalStatus, String reportedComment, Instant reportedAt,
                               Map<String, Object> application, String statusWarning) {
    }

    private static final Logger log = LoggerFactory.getLogger(SimulationService.class);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final RestClient http;
    private final SimulationRepository simulations;
    private final SimulatorProperties properties;
    private final ScenarioLibrary scenarios;
    private final ObjectMapper json;

    public SimulationService(RestClient http, SimulationRepository simulations,
                             SimulatorProperties properties, ScenarioLibrary scenarios,
                             ObjectMapper json) {
        this.http = http;
        this.simulations = simulations;
        this.properties = properties;
        this.scenarios = scenarios;
        this.json = json;
    }

    public DispatchView dispatch(DispatchCommand command) {
        if (command == null || command.targetServiceId() == null
                || command.targetServiceId().isBlank()) {
            throw new IllegalArgumentException("targetServiceId is required");
        }
        boolean hasScenario = command.scenarioId() != null && !command.scenarioId().isBlank();
        boolean hasEnvelope = command.envelope() != null && !command.envelope().isEmpty();
        if (hasScenario == hasEnvelope) {
            throw new IllegalArgumentException("send exactly one of scenarioId or envelope");
        }

        SimulatorProperties.Target target = properties.target(command.targetServiceId());
        if (target == null) {
            throw new UpstreamModuleException(HttpStatus.NOT_FOUND,
                    "unknown simulator target " + command.targetServiceId());
        }

        Map<String, Object> source = deepCopy(hasScenario
                ? scenarios.envelopeOf(command.scenarioId())
                        .orElseThrow(() -> new IllegalArgumentException(
                                "unknown scenario " + command.scenarioId()))
                : command.envelope());
        String correlationId = string(source.get("correlationId"));
        Simulation row = simulations.saveAndFlush(new Simulation(
                "pending", correlationId, hasScenario ? command.scenarioId() : null,
                target.serviceId(), target.baseUrl()));

        String suppliedId = string(source.get("applicationId"));
        String prefix = hasScenario ? command.scenarioId()
                : suppliedId != null && suppliedId.matches("SIM-\\d+") ? suppliedId : "SIM-CUSTOM";
        String applicationId = prefix + "-" + target.serviceId() + "-" + row.getId();
        source.put("applicationId", applicationId);
        Object nested = source.get("application");
        if (nested instanceof Map<?, ?> application && application.containsKey("applicationId")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> writable = (Map<String, Object>) application;
            writable.put("applicationId", applicationId);
        }

        ApplicationRequest envelope = toRequest(source);
        String applicationJson = write(envelope.application());
        row.prepare(applicationId, envelope.correlationId(), applicationJson);
        simulations.saveAndFlush(row);

        int status;
        String body;
        try {
            ResponseEntity<String> response = http.post()
                    .uri(target.baseUrl() + "/api/v1/applications")
                    .body(envelope)
                    .retrieve()
                    .toEntity(String.class);
            status = response.getStatusCode().value();
            body = response.getBody();
        } catch (RestClientResponseException e) {
            status = e.getStatusCode().value();
            body = e.getResponseBodyAsString();
        } catch (Exception e) {
            status = 0;
            body = e.toString();
            log.warn("Could not reach simulator target {} for {}: {}",
                    target.serviceId(), applicationId, e.toString());
        }

        // Reload rather than saving the pre-call entity: a very fast callback may already have
        // filled the report columns while the POST was in flight, and those fields must survive.
        Simulation acknowledged = simulations.findById(row.getId()).orElseThrow();
        acknowledged.acknowledge(status, body);
        return view(simulations.saveAndFlush(acknowledged));
    }

    /**
     * Pair a callback with the oldest unanswered dispatch for this application id.
     *
     * @return true if a simulation owned and accepted it; false for litter or a duplicate
     */
    @Transactional
    public boolean report(String applicationId, ApplicationStatusUpdate update) {
        Optional<Simulation> match =
                simulations.findFirstByApplicationIdAndReportedAtIsNullOrderByIdAsc(applicationId);
        if (match.isEmpty()) {
            if (simulations.existsByApplicationId(applicationId)) {
                log.warn("Duplicate simulation report for {} from {} — dropped",
                        applicationId, update.serviceId());
            }
            return false;
        }
        String canonical = StatusVocabulary.canonical(update.serviceId(), update.status())
                .orElse(null);
        match.get().report(update.serviceId(), update.status(), canonical, update.comment());
        simulations.save(match.get());
        return true;
    }

    public List<DispatchView> dispatches(String target) {
        List<Simulation> rows = target == null || target.isBlank()
                ? simulations.findAllByOrderByIdDesc()
                : simulations.findByTargetServiceIdOrderByIdDesc(target);
        return rows.stream().map(this::view).toList();
    }

    @Transactional
    public long clear(String target) {
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("target is required");
        }
        if (properties.target(target) == null) {
            throw new UpstreamModuleException(HttpStatus.NOT_FOUND,
                    "unknown simulator target " + target);
        }
        return simulations.deleteByTargetServiceId(target);
    }

    /** Application read-back only. Simulations never enter board or name-search queries. */
    public Optional<Map<String, Object>> application(String applicationId) {
        return simulations.findFirstByApplicationIdOrderByIdDesc(applicationId)
                .flatMap(this::application);
    }

    private DispatchView view(Simulation row) {
        String warning = row.getReportedStatus() != null && row.getCanonicalStatus() == null
                ? "unknown word; " + row.getReportedServiceId() + " may send: "
                    + StatusVocabulary.acceptedWords(row.getReportedServiceId())
                    + " (case-insensitive; '-' and '_' are the same)."
                : null;
        return new DispatchView(
                row.getId(), row.getApplicationId(), row.getCorrelationId(), row.getScenarioId(),
                row.getTargetServiceId(), row.getTargetUrl(), row.getSentAt(),
                row.getAckHttpStatus(), parseBody(row.getAckBody()), row.getReportedServiceId(),
                row.getReportedStatus(), row.getCanonicalStatus(), row.getReportedComment(),
                row.getReportedAt(), application(row).orElse(null), warning);
    }

    private Optional<Map<String, Object>> application(Simulation row) {
        if (row.getApplicationJson() == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(json.readValue(row.getApplicationJson(), MAP));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored simulation application is not JSON", e);
        }
    }

    private Object parseBody(String body) {
        if (body == null || body.isBlank()) {
            return body;
        }
        try {
            return json.readValue(body, Object.class);
        } catch (JsonProcessingException ignored) {
            return body;
        }
    }

    private ApplicationRequest toRequest(Map<String, Object> source) {
        try {
            return json.convertValue(source, ApplicationRequest.class);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("envelope does not match the application contract", e);
        }
    }

    private Map<String, Object> deepCopy(Map<String, Object> source) {
        return json.convertValue(source, MAP);
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("envelope cannot be encoded as JSON", e);
        }
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
