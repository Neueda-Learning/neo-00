package com.neobank.orchestrator.saga;

import com.neobank.orchestrator.domain.Application;
import com.neobank.orchestrator.saga.SagaDtos.ApplicationStatusUpdate;
import com.neobank.orchestrator.saga.SagaStore.CallbackOutcome;
import com.neobank.orchestrator.saga.SagaStore.DispatchTarget;
import com.neobank.orchestrator.saga.ServiceRegistry.ServiceDef;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * The sequencer. One application visits the ten services strictly in order:
 *
 * <pre>
 *   dispatch step N  →  202 ack  →  (service works)  →  status update  →  wait 1s  →  dispatch N+1
 * </pre>
 *
 * <p>Nothing here blocks waiting for a service. A dispatch returns as soon as the service
 * acknowledges; what advances the journey is the service's <em>status update</em> arriving as a
 * {@code PUT /api/v1/applications/{id}} on
 * {@link com.neobank.orchestrator.web.ApplicationController}. Journeys therefore overlap
 * freely — several are in flight at different steps at any moment — while each individual one stays
 * sequential.</p>
 *
 * <p>Only {@code ACCEPTED} advances. {@code REJECTED} and {@code REFERRED} end the journey
 * where they happen, so the remaining steps are never dispatched.</p>
 */
@Service
public class SagaEngine {

    private static final Logger log = LoggerFactory.getLogger(SagaEngine.class);

    private final SagaStore store;
    private final ServiceRegistry registry;
    private final RestClient restClient;
    private final TaskScheduler scheduler;
    private final Duration stepDelay;
    private final String command;

    public SagaEngine(SagaStore store,
                      ServiceRegistry registry,
                      RestClient restClient,
                      TaskScheduler scheduler,
                      @Value("${orchestrator.step-delay:1s}") Duration stepDelay,
                      @Value("${orchestrator.command:process-application}") String command) {
        this.store = store;
        this.registry = registry;
        this.restClient = restClient;
        this.scheduler = scheduler;
        this.stepDelay = stepDelay;
        this.command = command;
    }

    /** Send the application to the first service. */
    public void startJourney(String applicationId) {
        scheduleDispatch(applicationId, 1, Duration.ZERO);
    }

    /**
     * A service has answered. Record it, then either move on or stop.
     *
     * <p>The application id arrives as a parameter rather than on the body: it comes from the URL of
     * the {@code PUT} that carried the update.</p>
     */
    public void handleApplicationStatusUpdate(String applicationId, ApplicationStatusUpdate update) {
        CallbackOutcome outcome = store.recordApplicationStatusUpdate(applicationId, update);
        if (outcome instanceof CallbackOutcome.Advance advance) {
            // The 1s pause between services — the visible rhythm of the board.
            scheduleDispatch(applicationId, advance.nextStep(), stepDelay);
        }
    }

    /** Fail anything that has gone quiet. Called by {@link TimeoutSweeper}. */
    public int sweepTimeouts(Duration timeout) {
        return store.sweepTimeouts(timeout);
    }

    private void scheduleDispatch(String applicationId, int step, Duration delay) {
        scheduler.schedule(() -> dispatch(applicationId, step), Instant.now().plus(delay));
    }

    /**
     * POST one step. The store decides whether this is still worth doing — an application
     * that ended while this task sat on the queue returns empty and nothing is sent.
     */
    void dispatch(String applicationId, int step) {
        ServiceDef service = registry.byStep(step);
        if (service == null) {
            log.warn("No service configured for step {} — cannot dispatch {}", step, applicationId);
            return;
        }
        DispatchTarget target = store.beginDispatch(applicationId, step).orElse(null);
        if (target == null) {
            log.debug("Skipping dispatch of {} step {} — journey no longer running",
                    applicationId, step);
            return;
        }

        String url = service.baseUrl() + "/api/v1/applications";
        try {
            restClient.post()
                    .uri(url)
                    .body(store.toRequest(target, command))
                    .retrieve()
                    .toBodilessEntity();
            log.info("Dispatched {} to {} (step {})", applicationId, service.serviceId(), step);
            store.recordAck(applicationId, step, service.serviceId(), "202 from " + service.serviceId());
        } catch (Exception e) {
            log.warn("Dispatch of {} to {} failed: {}", applicationId, url, e.toString());
            store.recordDispatchFailed(applicationId, step, service.serviceId(), e.toString());
        }
    }

    /** How many services a full journey visits. */
    public int stepCount() {
        return registry.size();
    }

    /** Exposed for the ops/info endpoint. */
    public Duration stepDelay() {
        return stepDelay;
    }

    /** The terminal states a journey can reach — for documentation and the info endpoint. */
    public static String[] terminalStatuses() {
        return new String[]{Application.COMPLETED, Application.REJECTED,
                Application.REFERRED, Application.FAILED};
    }
}
