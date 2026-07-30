package com.neobank.orchestrator.saga;

import com.neobank.orchestrator.domain.Application;
import com.neobank.orchestrator.saga.SagaDtos.ApplicationStatusUpdate;
import com.neobank.orchestrator.saga.SagaStore.CallbackOutcome;
import com.neobank.orchestrator.saga.SagaStore.DispatchTarget;
import com.neobank.orchestrator.saga.ServiceRegistry.ServiceDef;
import com.neobank.orchestrator.simulator.SimulationService;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * <p>Only a status that resolves to {@code ACCEPTED} advances — each module may say so in its own
 * word ({@code PASSED}, {@code CLEAR}, {@code SIGNED}, {@code OPENED}…), which
 * {@link StatusVocabulary} translates. {@code REJECTED} ends the journey. {@code REFERRED} stops
 * automatic processing for human review; the same service may later resolve it with
 * {@code ACCEPTED} or {@code REJECTED}. {@code IN_PROGRESS} does neither: the journey keeps
 * waiting.</p>
 *
 * <h2>Demo stepping</h2>
 *
 * <p>With {@code orchestrator.demo-stepping} on, every arrow above that says "dispatch" waits for
 * a person instead: the journey is parked and only {@code POST /api/v1/applications/{id}/proceed}
 * sends the next step. It is one rule with no special case — the first dispatch waits too, so a
 * demo of an eight-step journey is eight clicks.</p>
 *
 * <p><b>The button gates the dispatch; it never speaks for a module.</b> Each service still does
 * its own work and reports its own status exactly as in automatic mode, so what an audience sees
 * is the real journey slowed down, not a puppet of one.</p>
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
    private final SimulationService simulations;

    /**
     * In memory on purpose, exactly like {@link com.neobank.orchestrator.generator.GeneratorService}
     * — it has to be flippable mid-demo without restarting a stack.
     *
     * <p><b>This is only correct while one orchestrator task runs</b> ({@code DesiredCount} is 1 in
     * {@code infra/service.yaml}). The park decision is taken by whichever task receives a module's
     * status update, so with two tasks half the steps would auto-advance and the demo would
     * silently half-work. If the orchestrator is ever scaled out, this flag has to become a row.</p>
     */
    private final AtomicBoolean demoStepping;

    public SagaEngine(SagaStore store,
                      ServiceRegistry registry,
                      RestClient restClient,
                      TaskScheduler scheduler,
                      SimulationService simulations,
                      @Value("${orchestrator.step-delay:1s}") Duration stepDelay,
                      @Value("${orchestrator.command:process-application}") String command,
                      @Value("${orchestrator.demo-stepping:false}") boolean demoSteppingAtBoot) {
        this.store = store;
        this.registry = registry;
        this.restClient = restClient;
        this.scheduler = scheduler;
        this.simulations = simulations;
        this.stepDelay = stepDelay;
        this.command = command;
        this.demoStepping = new AtomicBoolean(demoSteppingAtBoot);
    }

    /** Send the application to the first service — or park it there, in demo mode. */
    public void startJourney(String applicationId) {
        if (demoStepping.get()) {
            store.park(applicationId, 1);
            return;
        }
        scheduleDispatch(applicationId, 1, Duration.ZERO);
    }

    /**
     * A service has answered. Record it, then either move on or stop.
     *
     * <p>The application id arrives as a parameter rather than on the body: it comes from the URL of
     * the {@code PUT} that carried the update.</p>
     *
     * <p>{@code Advance} schedules the next journey step. {@code Unknown} falls through to the
     * simulator, which may own the id without ever writing a saga event. {@code Finished},
     * {@code Waiting} and {@code Ignored} are deliberate no-ops.</p>
     */
    public void handleApplicationStatusUpdate(String applicationId, ApplicationStatusUpdate update) {
        CallbackOutcome outcome = store.recordApplicationStatusUpdate(applicationId, update);
        if (outcome instanceof CallbackOutcome.Unknown) {
            simulations.report(applicationId, update);
            return;
        }
        if (outcome instanceof CallbackOutcome.Advance advance) {
            if (demoStepping.get()) {
                // Only Advance parks, and only a canonical ACCEPTED produces an Advance — so a
                // journey that was rejected, referred or answered with a word we do not know
                // still ends or stalls where it did, and no Proceed button appears for it.
                store.park(applicationId, advance.nextStep());
                return;
            }
            // The 1s pause between services — the visible rhythm of the board.
            scheduleDispatch(applicationId, advance.nextStep(), stepDelay);
        }
    }

    /**
     * Send the step a parked journey is waiting on. Empty if it is not parked — unknown, already
     * running, or finished — which the controller answers as a {@code 409}.
     */
    public Optional<Integer> proceed(String applicationId) {
        Optional<Integer> step = store.release(applicationId);
        step.ifPresent(s -> {
            log.info("Operator released {} — dispatching step {}", applicationId, s);
            scheduleDispatch(applicationId, s, Duration.ZERO);
        });
        return step;
    }

    public SagaDtos.DemoState demoState() {
        return new SagaDtos.DemoState(demoStepping.get(), store.parkedApplicationIds().size());
    }

    /**
     * Turn demo stepping on or off.
     *
     * <p><b>Turning it off releases everything already parked</b> — the way out if a demo is
     * abandoned half-way, or if the generator was left running and filled the board with journeys
     * waiting on a click that is never coming.</p>
     */
    public SagaDtos.DemoState setDemoStepping(boolean enabled) {
        if (demoStepping.getAndSet(enabled) != enabled) {
            log.info("Demo stepping {}", enabled ? "ON — every step now waits for an operator"
                    : "OFF — journeys advance on their own again");
        }
        if (!enabled) {
            store.parkedApplicationIds().forEach(this::proceed);
        }
        return demoState();
    }

    public boolean isDemoStepping() {
        return demoStepping.get();
    }

    /**
     * Fail anything that has gone quiet, and first release anything left parked by mistake.
     * Called by {@link TimeoutSweeper}.
     *
     * <p><b>The reconciliation matters more than it looks.</b> The toggle lives in memory and
     * comes back <em>off</em> after a restart; {@code pending_step} lives in the database and
     * comes back exactly as it was. So a journey parked when the process died would return
     * held with nothing holding it — and because {@link SagaStore#sweepTimeouts} deliberately
     * skips parked rows, the one mechanism that stops a journey wedging forever would never
     * touch it. It would sit {@code IN_PROGRESS} for the life of the database.</p>
     *
     * <p>The rule is simply that the two must agree: <b>stepping off means nothing is
     * parked.</b> Running it here rather than only at boot also closes a race — a status update
     * can land between {@link #setDemoStepping} reading the parked list and the flag going
     * off, which would park an application a moment after the release-all had passed it.</p>
     */
    public int sweepTimeouts(Duration timeout) {
        if (!demoStepping.get()) {
            store.parkedApplicationIds().forEach(this::proceed);
        }
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
