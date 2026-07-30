package com.neobank.orchestrator.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neobank.orchestrator.domain.Application;
import com.neobank.orchestrator.products.ProductRouter;
import com.neobank.orchestrator.saga.SagaDtos.GeneratorState;
import com.neobank.orchestrator.saga.SagaEngine;
import com.neobank.orchestrator.saga.SagaStore;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The start/stop toggle behind the front end's switch: while it is on, a new application
 * is created every {@code intervalMs} and sent on its way.
 *
 * <p><b>It starts off.</b> The orchestrator dispatches to services which call back to the
 * orchestrator, so a stack coming up all at once would otherwise fire at containers that
 * are not listening yet. Turning it on is a deliberate act.</p>
 */
@Service
public class GeneratorService {

    private static final Logger log = LoggerFactory.getLogger(GeneratorService.class);

    private final SagaStore store;
    private final SagaEngine engine;
    private final ApplicationFactory factory;
    private final ProductRouter router;
    private final ObjectMapper json;

    private final AtomicBoolean enabled = new AtomicBoolean(false);
    private final AtomicLong intervalMs;
    private final AtomicLong created = new AtomicLong();
    private volatile Instant lastRun = Instant.EPOCH;

    public GeneratorService(SagaStore store, SagaEngine engine, ApplicationFactory factory,
                            ProductRouter router, ObjectMapper json,
                            @Value("${orchestrator.generator.interval-ms:5000}") long intervalMs,
                            @Value("${orchestrator.generator.enabled:false}") boolean enabledAtBoot) {
        this.store = store;
        this.engine = engine;
        this.factory = factory;
        this.router = router;
        this.json = json;
        this.intervalMs = new AtomicLong(intervalMs);
        this.enabled.set(enabledAtBoot);
    }

    /**
     * Ticks once a second and creates an application when the configured interval has
     * elapsed — a fixed tick with an interval check, rather than a reschedulable timer, so
     * changing the interval from the UI takes effect on the next tick with no restart.
     */
    @Scheduled(fixedDelayString = "${orchestrator.generator.tick-ms:1000}")
    void tick() {
        if (!enabled.get()) {
            return;
        }
        if (Duration.between(lastRun, Instant.now()).toMillis() < intervalMs.get()) {
            return;
        }
        lastRun = Instant.now();
        try {
            // Generated fixtures belong to nobody: they fill the operator's board and
            // must never appear on a customer's own screen.
            createAndStart((String) null);
        } catch (Exception e) {
            // A failure here must never kill the scheduled task.
            log.error("Generator tick failed: {}", e.toString());
        }
    }

    /**
     * Create one application from the seeded fixture set and dispatch it to the first service.
     *
     * <p>{@code customerId} may be null, and is for every fixture the generator makes and for the
     * backoffice's "+ one" — those belong to nobody and must stay that way, or they would appear
     * on somebody's own screen. It is a parameter here rather than only on the submitted path so
     * that "the code names the customer, whatever the body" is one rule with no exception, and so
     * a customer's history can be seeded without filling the form eight times.</p>
     */
    public Application createAndStart(String customerId) {
        String id = nextId();
        return persistRouteAndStart(id, factory.next(id), customerId);
    }

    /**
     * Create one application from a supplied (customer-filled) payload and dispatch it.
     *
     * <p>The orchestrator owns the id and the submission time, so those are stamped here whatever
     * the caller sent; every other field is taken as given. The caller's map is copied, never
     * mutated.</p>
     *
     * <p><b>{@code customerId} is deliberately not put into the payload.</b> The payload is the
     * api-contract §4 application object and is kept exactly as it was sent; who signed in is
     * this orchestrator's own bookkeeping and goes on the row.</p>
     */
    public Application createAndStart(Map<String, Object> supplied, String customerId) {
        String id = nextId();
        Map<String, Object> payload = new LinkedHashMap<>(supplied);
        payload.put("applicationId", id);
        payload.put("submittedAt", Instant.now().toString());
        return persistRouteAndStart(id, payload, customerId);
    }

    private String nextId() {
        return "APP-" + String.format("%04d", created.incrementAndGet());
    }

    /** The shared tail: denormalise the board columns, store, route by product, then dispatch. */
    private Application persistRouteAndStart(String id, Map<String, Object> payload,
                                             String customerId) {
        String productCode = nested(payload, "product", "productCode");
        Application application = store.create(new Application(
                id,
                UUID.randomUUID().toString(),
                nested(payload, "applicant", "fullName"),
                productCode,
                intOf(nested(payload, "product", "requestedCreditLimit")),
                String.valueOf(payload.get("channel")),
                writeJson(payload),
                customerId));
        log.info("Created {} for {}", id, application.getApplicantName());
        router.route(productCode, id, payload);
        engine.startJourney(id);
        return application;
    }

    public GeneratorState state() {
        return new GeneratorState(enabled.get(), intervalMs.get(), created.get());
    }

    public GeneratorState update(Boolean newEnabled, Long newIntervalMs) {
        if (newIntervalMs != null) {
            // Below ~500ms the board becomes unreadable and the services queue up.
            intervalMs.set(Math.max(500, newIntervalMs));
        }
        if (newEnabled != null && enabled.getAndSet(newEnabled) != newEnabled) {
            log.info("Generator {} (every {}ms)", newEnabled ? "STARTED" : "STOPPED", intervalMs.get());
            if (newEnabled) {
                lastRun = Instant.EPOCH;   // fire on the very next tick, don't wait an interval
            }
        }
        return state();
    }

    /**
     * Continue numbering after a restart so ids stay unique against rows already stored.
     * Called once at startup by {@link GeneratorSeed}.
     */
    void resumeCountFrom(long highest) {
        created.set(highest);
    }

    @SuppressWarnings("unchecked")
    private static String nested(Map<String, Object> map, String outer, String inner) {
        Object child = map.get(outer);
        if (child instanceof Map<?, ?> m) {
            Object value = ((Map<String, Object>) m).get(inner);
            return value == null ? null : String.valueOf(value);
        }
        return null;
    }

    private static Integer intOf(String value) {
        try {
            return value == null ? null : Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String writeJson(Map<String, Object> payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (Exception e) {
            return "{}";
        }
    }
}
