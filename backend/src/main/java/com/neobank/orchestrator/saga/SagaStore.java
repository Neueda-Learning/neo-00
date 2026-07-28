package com.neobank.orchestrator.saga;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neobank.orchestrator.domain.Application;
import com.neobank.orchestrator.domain.ApplicationEvent;
import com.neobank.orchestrator.domain.ApplicationEventRepository;
import com.neobank.orchestrator.domain.ApplicationRepository;
import com.neobank.orchestrator.saga.SagaDtos.ApplicationRequest;
import com.neobank.orchestrator.saga.SagaDtos.ApplicationStatusUpdate;
import com.neobank.orchestrator.saga.SagaDtos.ApplicationRow;
import com.neobank.orchestrator.saga.SagaDtos.StepView;
import com.neobank.orchestrator.saga.ServiceRegistry.ServiceDef;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every database write the journey makes, in one place, each in its own transaction.
 *
 * <p>Split out from {@link SagaEngine} deliberately: the engine does HTTP and scheduling,
 * which must happen <em>outside</em> a transaction, and calling a {@code @Transactional}
 * method from a sibling method of the same bean would silently bypass the proxy.</p>
 *
 * <p>Writes to {@code application_event} are inserts only — this is the append-only log.</p>
 */
@Service
public class SagaStore {

    private static final Logger log = LoggerFactory.getLogger(SagaStore.class);

    /**
     * What the engine should do after a status update.
     *
     * <p>Still named {@code CallbackOutcome}, along with the {@code CALLBACK} event type and the
     * per-service tally query: those are internal vocabulary, and the event type in particular is a
     * string already stored in {@code application_event} rows. Renaming it would leave the board
     * showing two different words for the same thing.</p>
     */
    public sealed interface CallbackOutcome {
        /** Dispatch this step next, after the inter-step delay. */
        record Advance(int nextStep) implements CallbackOutcome {
        }

        /** The journey ended; nothing more to send. */
        record Finished(String overallStatus) implements CallbackOutcome {
        }

        /** Recorded, but it changes nothing (late, duplicate, or from the wrong service). */
        record Ignored(String why) implements CallbackOutcome {
        }
    }

    /** What the engine needs in order to POST one step. */
    public record DispatchTarget(String applicationId, String correlationId, int step,
                                 Map<String, Object> application) {
    }

    private final ApplicationRepository applications;
    private final ApplicationEventRepository events;
    private final ServiceRegistry registry;
    private final ObjectMapper json;

    public SagaStore(ApplicationRepository applications, ApplicationEventRepository events,
                     ServiceRegistry registry, ObjectMapper json) {
        this.applications = applications;
        this.events = events;
        this.registry = registry;
        this.json = json;
    }

    // ---- writes ----

    /** Create an application and open its log. */
    @Transactional
    public Application create(Application application) {
        Application saved = applications.save(application);
        events.save(new ApplicationEvent(saved.getId(), 0, null,
                ApplicationEvent.JOURNEY_STARTED, null,
                "application created for " + saved.getApplicantName()));
        return saved;
    }

    /**
     * Move the application onto {@code step} and log that a command is going out. Returns
     * empty if the application is gone or already terminal — which is what stops a
     * scheduled dispatch from firing at a journey that ended while it was queued.
     */
    @Transactional
    public Optional<DispatchTarget> beginDispatch(String applicationId, int step) {
        Application app = applications.findById(applicationId).orElse(null);
        if (app == null || app.isTerminal()) {
            return Optional.empty();
        }
        ServiceDef service = registry.byStep(step);
        if (service == null) {
            return Optional.empty();
        }
        app.setCurrentStep(step);
        applications.save(app);
        events.save(new ApplicationEvent(applicationId, step, service.serviceId(),
                ApplicationEvent.REQUEST_SENT, null, "sent to " + service.name()));
        return Optional.of(new DispatchTarget(applicationId, app.getCorrelationId(), step,
                readPayload(app.getPayloadJson())));
    }

    @Transactional
    public void recordAck(String applicationId, int step, String serviceId, String comment) {
        events.save(new ApplicationEvent(applicationId, step, serviceId,
                ApplicationEvent.ACK_RECEIVED, null, comment));
    }

    /** The service could not be reached at all — the journey cannot continue. */
    @Transactional
    public void recordDispatchFailed(String applicationId, int step, String serviceId, String error) {
        events.save(new ApplicationEvent(applicationId, step, serviceId,
                ApplicationEvent.DISPATCH_FAILED, null, error));
        endJourney(applicationId, Application.FAILED, "could not reach " + serviceId);
    }

    /**
     * Record a service's status update and decide what happens next.
     *
     * <p>The event is appended <em>whatever</em> it says — a late or misdirected report is still
     * something that happened. What it does <em>not</em> do is change a terminal application: an
     * update arriving a second after the sweeper gave up must not resurrect a journey.</p>
     *
     * <p>{@code applicationId} is a parameter because it comes from the {@code PUT} URL, not from
     * the body.</p>
     */
    @Transactional
    public CallbackOutcome recordApplicationStatusUpdate(String applicationId, ApplicationStatusUpdate cb) {
        Application app = applications.findById(applicationId).orElse(null);
        if (app == null) {
            log.warn("Status update for unknown application {} from {}", applicationId, cb.serviceId());
            return new CallbackOutcome.Ignored("unknown application " + applicationId);
        }

        String status = normalize(cb.status());
        int step = stepOf(cb.serviceId(), app.getCurrentStep());
        events.save(new ApplicationEvent(app.getId(), step, cb.serviceId(),
                ApplicationEvent.CALLBACK, status, cb.comment()));

        if (app.isTerminal()) {
            log.info("Late status update for {} ({} already {}) — recorded, ignored",
                    app.getId(), cb.serviceId(), app.getOverallStatus());
            return new CallbackOutcome.Ignored("application already " + app.getOverallStatus());
        }

        ServiceDef expected = registry.byStep(app.getCurrentStep());
        if (expected == null || !expected.serviceId().equals(cb.serviceId())) {
            log.warn("Status update for {} from {} but step {} belongs to {} — recorded, ignored",
                    app.getId(), cb.serviceId(), app.getCurrentStep(),
                    expected == null ? "nobody" : expected.serviceId());
            return new CallbackOutcome.Ignored("not the current step");
        }

        return switch (status) {
            case "ACCEPTED" -> {
                if (app.getCurrentStep() >= registry.size()) {
                    yield finish(app, Application.COMPLETED, "all " + registry.size() + " services accepted");
                }
                int next = app.getCurrentStep() + 1;
                // Advance now, not at dispatch time: a duplicate callback from this service
                // then no longer matches the current step and cannot double-dispatch.
                app.setCurrentStep(next);
                applications.save(app);
                yield new CallbackOutcome.Advance(next);
            }
            case "REJECTED" -> finish(app, Application.REJECTED, cb.serviceId() + " rejected");
            case "REFERRED" -> finish(app, Application.REFERRED, cb.serviceId() + " referred");
            default -> {
                log.warn("Status update for {} carried unknown status '{}' — recorded, ignored",
                        app.getId(), cb.status());
                yield new CallbackOutcome.Ignored("unknown status " + cb.status());
            }
        };
    }

    /**
     * Fail any in-progress application that has gone quiet for longer than {@code timeout}.
     *
     * <p>Keyed on the last event of <em>any</em> kind rather than on an outstanding request,
     * so it also catches a journey whose scheduled dispatch was lost (a restart, say) and
     * would otherwise sit IN_PROGRESS forever.</p>
     */
    @Transactional
    public int sweepTimeouts(Duration timeout) {
        Instant cutoff = Instant.now().minus(timeout);
        List<Application> inFlight = applications.findByOverallStatus(Application.IN_PROGRESS);
        if (inFlight.isEmpty()) {
            return 0;
        }
        Map<String, Instant> lastSeen = new HashMap<>();
        Map<String, String> lastService = new HashMap<>();
        for (ApplicationEvent e : events.findByApplicationIdInOrderByIdAsc(
                inFlight.stream().map(Application::getId).toList())) {
            lastSeen.put(e.getApplicationId(), e.getCreatedAt());
            if (e.getServiceId() != null) {
                lastService.put(e.getApplicationId(), e.getServiceId());
            }
        }

        int swept = 0;
        for (Application app : inFlight) {
            Instant seen = lastSeen.getOrDefault(app.getId(), app.getCreatedAt());
            if (seen != null && seen.isBefore(cutoff)) {
                String serviceId = lastService.get(app.getId());
                events.save(new ApplicationEvent(app.getId(), app.getCurrentStep(), serviceId,
                        ApplicationEvent.TIMEOUT, "TIMEOUT",
                        "no callback within " + timeout));
                finish(app, Application.FAILED, "timed out waiting for " + serviceId);
                log.warn("Application {} timed out waiting for {}", app.getId(), serviceId);
                swept++;
            }
        }
        return swept;
    }

    // ---- reads ----

    @Transactional(readOnly = true)
    public List<ApplicationRow> board(int limit) {
        List<Application> apps = applications.findAllByOrderByCreatedAtDesc(
                org.springframework.data.domain.Limit.of(limit));
        if (apps.isEmpty()) {
            return List.of();
        }
        Map<String, List<ApplicationEvent>> byApplication = new HashMap<>();
        for (ApplicationEvent e : events.findByApplicationIdInOrderByIdAsc(
                apps.stream().map(Application::getId).toList())) {
            byApplication.computeIfAbsent(e.getApplicationId(), k -> new ArrayList<>()).add(e);
        }
        return apps.stream()
                .map(a -> toRow(a, byApplication.getOrDefault(a.getId(), List.of())))
                .toList();
    }

    /**
     * The application itself, as the api-contract §4 object — byte for byte the same shape the
     * dispatch envelope carries in its {@code application} field.
     *
     * <p>This is what a service gets from {@code GET /api/v1/applications/{id}}, and it is
     * deliberately <em>not</em> {@link SagaDtos.ApplicationDetail}. A service asking for an
     * application wants the application; the board row and the event log are this orchestrator's
     * own view of the journey and live at {@code /{id}/journey}. One object, two ways to get it:
     * pushed in the envelope, or pulled by id.</p>
     */
    @Transactional(readOnly = true)
    public Optional<Map<String, Object>> application(String id) {
        return applications.findById(id).map(a -> readPayload(a.getPayloadJson()));
    }

    /**
     * Applications whose applicant name <em>contains</em> {@code name}, newest first, each as the
     * same §4 object {@link #application(String)} returns.
     *
     * <p>Substring and case-insensitive on purpose: this backs an operator typing a name into a
     * search box, not an exact-match lookup. A blank query matches nothing rather than everything —
     * returning the whole book because someone tabbed through a field is not a search result.</p>
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> applicationsByName(String name, int limit) {
        if (name == null || name.isBlank()) {
            return List.of();
        }
        return applications.findByApplicantNameContainingIgnoreCaseOrderByCreatedAtDesc(
                        name.strip(), org.springframework.data.domain.Limit.of(limit))
                .stream()
                .map(a -> readPayload(a.getPayloadJson()))
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<SagaDtos.ApplicationDetail> detail(String id) {
        return applications.findById(id)
                .map(a -> SagaDtos.ApplicationDetail.of(a, readPayload(a.getPayloadJson()),
                        events.findByApplicationIdOrderByIdAsc(id)));
    }

    @Transactional(readOnly = true)
    public List<ApplicationEvent> events(String serviceId, int limit) {
        org.springframework.data.domain.Limit cap = org.springframework.data.domain.Limit.of(limit);
        return serviceId == null || serviceId.isBlank()
                ? events.findAllByOrderByIdDesc(cap)
                : events.findByServiceIdOrderByIdDesc(serviceId, cap);
    }

    @Transactional(readOnly = true)
    public List<SagaDtos.ServiceSummary> serviceSummaries() {
        // Callback tallies come from the database; in-flight is derived below.
        Map<String, Map<String, Long>> tallies = new HashMap<>();
        for (Object[] row : events.countCallbacksByServiceAndStatus()) {
            tallies.computeIfAbsent((String) row[0], k -> new HashMap<>())
                    .put((String) row[1], ((Number) row[2]).longValue());
        }
        Map<String, Long> inFlight = inFlightByService();
        Map<String, Long> timedOut = timeoutsByService();

        List<SagaDtos.ServiceSummary> out = new ArrayList<>();
        for (ServiceDef s : registry.ordered()) {
            Map<String, Long> t = tallies.getOrDefault(s.serviceId(), Map.of());
            long accepted = t.getOrDefault("ACCEPTED", 0L);
            long rejected = t.getOrDefault("REJECTED", 0L);
            long referred = t.getOrDefault("REFERRED", 0L);
            long timeouts = timedOut.getOrDefault(s.serviceId(), 0L);
            long running = inFlight.getOrDefault(s.serviceId(), 0L);
            out.add(new SagaDtos.ServiceSummary(s.step(), s.serviceId(), s.name(), s.baseUrl(),
                    running, accepted, rejected, referred, timeouts,
                    accepted + rejected + referred + timeouts + running));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public SagaDtos.BoardSummary boardSummary() {
        return new SagaDtos.BoardSummary(
                applications.count(),
                applications.countByOverallStatus(Application.IN_PROGRESS),
                applications.countByOverallStatus(Application.COMPLETED),
                applications.countByOverallStatus(Application.REJECTED),
                applications.countByOverallStatus(Application.REFERRED),
                applications.countByOverallStatus(Application.FAILED));
    }

    // ---- helpers ----

    /**
     * The ten dots on a board row, derived entirely from the append-only log: a step shows
     * its callback status if one arrived, {@code TIMEOUT} if it gave up, {@code in-flight}
     * if a request went out and nothing came back, and {@code pending} if it was never
     * reached.
     */
    private ApplicationRow toRow(Application app, List<ApplicationEvent> log) {
        Map<Integer, String> statusByStep = new HashMap<>();
        for (ApplicationEvent e : log) {   // ascending id — later events win
            switch (e.getEventType()) {
                case ApplicationEvent.REQUEST_SENT ->
                        statusByStep.putIfAbsent(e.getStepIndex(), StepView.IN_FLIGHT);
                case ApplicationEvent.CALLBACK ->
                        statusByStep.put(e.getStepIndex(), e.getStatus());
                case ApplicationEvent.TIMEOUT, ApplicationEvent.DISPATCH_FAILED ->
                        statusByStep.put(e.getStepIndex(), "TIMEOUT");
                default -> { /* journey markers carry no step status */ }
            }
        }
        List<StepView> steps = registry.ordered().stream()
                .map(s -> new StepView(s.step(), s.serviceId(),
                        statusByStep.getOrDefault(s.step(), StepView.PENDING)))
                .toList();
        return new ApplicationRow(app.getId(), app.getApplicantName(), app.getProductCode(),
                app.getRequestedLimit(), app.getChannel(), app.getCurrentStep(),
                app.getOverallStatus(), app.getCreatedAt(), app.getUpdatedAt(), steps);
    }

    /** Requests that went out and have not been answered, per service. */
    private Map<String, Long> inFlightByService() {
        Map<String, Long> counts = new HashMap<>();
        for (Application app : applications.findByOverallStatus(Application.IN_PROGRESS)) {
            List<ApplicationEvent> log = events.findByApplicationIdOrderByIdAsc(app.getId());
            String waitingOn = null;
            for (ApplicationEvent e : log) {
                switch (e.getEventType()) {
                    case ApplicationEvent.REQUEST_SENT -> waitingOn = e.getServiceId();
                    case ApplicationEvent.CALLBACK, ApplicationEvent.TIMEOUT,
                         ApplicationEvent.DISPATCH_FAILED -> waitingOn = null;
                    default -> { /* not a wait boundary */ }
                }
            }
            if (waitingOn != null) {
                counts.merge(waitingOn, 1L, Long::sum);
            }
        }
        return counts;
    }

    private Map<String, Long> timeoutsByService() {
        Map<String, Long> counts = new HashMap<>();
        for (ApplicationEvent e : events.findAllByOrderByIdDesc(
                org.springframework.data.domain.Limit.of(5000))) {
            if ((ApplicationEvent.TIMEOUT.equals(e.getEventType())
                    || ApplicationEvent.DISPATCH_FAILED.equals(e.getEventType()))
                    && e.getServiceId() != null) {
                counts.merge(e.getServiceId(), 1L, Long::sum);
            }
        }
        return counts;
    }

    private CallbackOutcome finish(Application app, String status, String why) {
        app.setOverallStatus(status);
        applications.save(app);
        events.save(new ApplicationEvent(app.getId(), app.getCurrentStep(), null,
                ApplicationEvent.JOURNEY_ENDED, status, why));
        log.info("Application {} ended {} — {}", app.getId(), status, why);
        return new CallbackOutcome.Finished(status);
    }

    private void endJourney(String applicationId, String status, String why) {
        applications.findById(applicationId).ifPresent(app -> {
            if (!app.isTerminal()) {
                finish(app, status, why);
            }
        });
    }

    /** Which step a service occupies, falling back to the application's current step. */
    private int stepOf(String serviceId, int fallback) {
        return registry.ordered().stream()
                .filter(s -> s.serviceId().equals(serviceId))
                .map(ServiceDef::step)
                .findFirst()
                .orElse(fallback);
    }

    private static String normalize(String status) {
        return status == null ? "" : status.trim().toUpperCase();
    }

    private Map<String, Object> readPayload(String payloadJson) {
        try {
            return payloadJson == null ? Map.of()
                    : json.readValue(payloadJson, new TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception e) {
            log.warn("Unreadable stored payload: {}", e.toString());
            return Map.of();
        }
    }

    /** Only used to build the outbound envelope; kept here so the engine stays HTTP-only. */
    public ApplicationRequest toRequest(DispatchTarget target, String command) {
        return new ApplicationRequest(target.applicationId(), target.correlationId(),
                command, target.application());
    }
}
