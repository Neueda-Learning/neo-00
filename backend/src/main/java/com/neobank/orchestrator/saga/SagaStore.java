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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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

        /**
         * The service is still working. Recorded as a
         * {@link ApplicationEvent#PROGRESS_REPORTED} event, which restarts the timeout clock;
         * the journey neither advances nor ends.
         *
         * <p>Deliberately not {@link Ignored}: that means late, duplicate, or from the wrong
         * service, and a module doing exactly what its brief tells it to is none of the three.</p>
         */
        record Waiting(String reportedStatus) implements CallbackOutcome {
        }

        /** Recorded, but it changes nothing (late, duplicate, or from the wrong service). */
        record Ignored(String why) implements CallbackOutcome {
        }
    }

    /** What the engine needs in order to POST one step. */
    public record DispatchTarget(String applicationId, String correlationId, int step,
                                 Map<String, Object> application,
                                 Map<String, Object> outputs) {
    }

    private final ApplicationRepository applications;
    private final ApplicationEventRepository events;
    private final ServiceRegistry registry;
    private final ObjectMapper json;

    /**
     * The one service whose "still working" means "a human is reading something", not "a
     * computer is thinking". Its wait gets {@link #signatureTimeout} instead of the ordinary
     * one — see {@link #sweepTimeouts}.
     */
    private final String signatureServiceId;
    private final Duration signatureTimeout;

    public SagaStore(ApplicationRepository applications, ApplicationEventRepository events,
                     ServiceRegistry registry, ObjectMapper json,
                     @Value("${orchestrator.signature.service-id:neo06}") String signatureServiceId,
                     @Value("${orchestrator.signature.timeout:10m}") Duration signatureTimeout) {
        this.applications = applications;
        this.events = events;
        this.registry = registry;
        this.json = json;
        this.signatureServiceId = signatureServiceId;
        this.signatureTimeout = signatureTimeout;
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
                readPayload(app.getPayloadJson()), readPayload(app.getOutputsJson())));
    }

    /**
     * Demo stepping: hold the journey here instead of dispatching {@code step}, and say so in
     * the log. A no-op on an unknown or terminal application — a journey that ended has nothing
     * left to park.
     *
     * <p>The step is <em>stored</em>, not inferred, because {@link #release} has to know what to
     * dispatch and {@code currentStep} does not answer that before the first dispatch.</p>
     */
    @Transactional
    public void park(String applicationId, int step) {
        Application app = applications.findById(applicationId).orElse(null);
        if (app == null || app.isTerminal()) {
            return;
        }
        ServiceDef service = registry.byStep(step);
        app.setPendingStep(step);
        applications.save(app);
        events.save(new ApplicationEvent(applicationId, step,
                service == null ? null : service.serviceId(),
                ApplicationEvent.AWAITING_OPERATOR, null,
                "demo mode — waiting for an operator to send step " + step
                        + (service == null ? "" : " to " + service.name())));
        log.info("Application {} parked before step {} — demo stepping is on", applicationId, step);
    }

    /**
     * Let a parked journey go: clear the hold and hand back the step to dispatch. Empty unless
     * the application is parked and still running.
     *
     * <p><b>The clear and the read are one transaction, and both happen before anything is
     * dispatched.</b> A row left parked forever is also un-sweepable forever — the timeout
     * deliberately skips parked journeys — whereas a cleared row whose dispatch is then lost
     * degrades to the ordinary 30-second timeout, which is the failure we already handle.</p>
     */
    @Transactional
    public Optional<Integer> release(String applicationId) {
        Application app = applications.findById(applicationId).orElse(null);
        if (app == null || app.isTerminal() || !app.isAwaitingOperator()) {
            return Optional.empty();
        }
        int step = app.getPendingStep();
        ServiceDef service = registry.byStep(step);
        app.setPendingStep(null);
        applications.save(app);
        events.save(new ApplicationEvent(applicationId, step,
                service == null ? null : service.serviceId(),
                ApplicationEvent.RELEASED_BY_OPERATOR, null,
                "released by an operator — sending step " + step));
        return Optional.of(step);
    }

    /** Every journey currently waiting on a click, oldest first. Feeds the release-all path. */
    @Transactional(readOnly = true)
    public List<String> parkedApplicationIds() {
        return applications.findByOverallStatus(Application.IN_PROGRESS).stream()
                .filter(Application::isAwaitingOperator)
                .sorted(java.util.Comparator.comparing(Application::getCreatedAt))
                .map(Application::getId)
                .toList();
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
     *
     * <p>The word itself is resolved through {@link StatusVocabulary} <em>before</em> the row is
     * written, so what lands in {@code application_event.status} is one of the four canonical
     * values. Both derived views depend on that: {@code toRow} paints the board dot straight from
     * this column, and {@code serviceSummaries} buckets on it. A module's own word stored here
     * would show up as a grey dot and vanish out of the service tallies.</p>
     */
    @Transactional
    public CallbackOutcome recordApplicationStatusUpdate(String applicationId, ApplicationStatusUpdate cb) {
        Application app = applications.findById(applicationId).orElse(null);
        if (app == null) {
            log.warn("Status update for unknown application {} from {}", applicationId, cb.serviceId());
            return new CallbackOutcome.Ignored("unknown application " + applicationId);
        }

        String reported = StatusVocabulary.normalize(cb.status());
        String status = StatusVocabulary.canonical(cb.serviceId(), reported).orElse(null);
        boolean progress = StatusVocabulary.IN_PROGRESS.equals(status);
        int step = stepOf(cb.serviceId(), app.getCurrentStep());
        // A progress report gets its own event type so it is not mistaken for the end of a wait.
        // An unrecognised word is stored exactly as the module said it — there is no canonical
        // value to store, and that word is the only clue the operator gets.
        events.save(new ApplicationEvent(app.getId(), step, cb.serviceId(),
                progress ? ApplicationEvent.PROGRESS_REPORTED : ApplicationEvent.CALLBACK,
                status == null ? reported : status, cb.comment()));

        if (status != null && !status.equals(reported)) {
            // The fault this vocabulary fixes was invisible: nothing said a module spoke a
            // different dialect. INFO rather than DEBUG — it fires only for modules worth knowing
            // about, and stops on its own as teams converge on the shipped three.
            log.info("{} said '{}' on {} — recorded as {}", cb.serviceId(), cb.status(),
                    app.getId(), status);
        }

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

        if (status == null) {
            // Everything an operator needs to act, in one line: which module, the word exactly as
            // it was sent, what it costs, what would have worked, and both ways to fix it. The
            // message this replaced said only "unknown status — recorded, ignored", which left a
            // 30-second silence and no way to attribute it.
            log.warn("Unknown status '{}' from {} on {} — recorded, but the journey does NOT advance "
                            + "and the sweeper will fail it. {} may send: {} (case-insensitive; "
                            + "'-' and '_' are the same). Fix the module, or teach the word to "
                            + "StatusVocabulary — see api-contract.md §3.",
                    cb.status(), cb.serviceId(), app.getId(), cb.serviceId(),
                    StatusVocabulary.acceptedWords(cb.serviceId()));
            return new CallbackOutcome.Ignored("unknown status " + cb.status());
        }

        // Only now, past every guard: a late, duplicate or wrong-step update is recorded in the
        // log but must not mutate journey state, and an unrecognised word is not an outcome at
        // all. Note this sits BEFORE the switch, so an IN_PROGRESS report merges too — that is
        // deliberate, because the one step that legitimately calls back twice (neo-06's
        // PENDING-then-terminal) may have something to hand on already at the first call.
        mergeOutputs(app, cb);
        applySignatureHold(app, cb.serviceId(), status, step);

        return switch (status) {
            case StatusVocabulary.ACCEPTED -> {
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
            case StatusVocabulary.REJECTED -> finish(app, Application.REJECTED, cb.serviceId() + " rejected");
            case StatusVocabulary.REFERRED -> finish(app, Application.REFERRED, cb.serviceId() + " referred");
            case StatusVocabulary.IN_PROGRESS -> {
                log.info("{} is still working on {} ('{}') — recorded, the journey waits",
                        cb.serviceId(), app.getId(), cb.status());
                yield new CallbackOutcome.Waiting(reported);
            }
            default -> {
                // Unreachable while the vocabulary only produces the four words above, which
                // everyMappingLandsOnOneOfTheFourWordsTheSagaKnowsHowToActOn pins. Never throw at
                // a module for a fault of ours.
                log.error("StatusVocabulary produced '{}', which the saga has no rule for", status);
                yield new CallbackOutcome.Ignored("unhandled canonical status " + status);
            }
        };
    }

    /**
     * Start or end a wait on the customer.
     *
     * <p>The agreement service is the one step whose "still working" means a person is reading a
     * contract, and people are slower than the thirty seconds a module is allowed to think. When
     * it reports progress the journey is marked as waiting on a signature, which only changes
     * which clock {@link #sweepTimeouts} measures it against; when it reports anything else — the
     * signature landed, the customer declined, the module's own expiry gave up — the mark comes
     * off, because the wait is over whichever way it ended.</p>
     *
     * <p>Every other service reporting progress is left alone deliberately. A module that is
     * merely slow should still be given up on at the ordinary timeout; the long rope is for the
     * one wait that is not the software's fault.</p>
     */
    private void applySignatureHold(Application app, String serviceId, String status, int step) {
        if (!signatureServiceId.equals(serviceId)) {
            return;
        }
        if (StatusVocabulary.IN_PROGRESS.equals(status)) {
            if (!app.isAwaitingSignature()) {
                app.setAwaitingSignatureAt(Instant.now());
                applications.save(app);
                events.save(new ApplicationEvent(app.getId(), step, serviceId,
                        ApplicationEvent.AWAITING_SIGNATURE, null,
                        "waiting for the customer to sign the agreement"));
                log.info("{} is waiting on the customer's signature — the {} clock now applies",
                        app.getId(), signatureTimeout);
            }
        } else if (app.isAwaitingSignature()) {
            app.setAwaitingSignatureAt(null);
            applications.save(app);
        }
    }

    /**
     * Fail any in-progress application that has gone quiet for longer than {@code timeout}.
     *
     * <p>Keyed on the last event of <em>any</em> kind rather than on an outstanding request,
     * so it also catches a journey whose scheduled dispatch was lost (a restart, say) and
     * would otherwise sit IN_PROGRESS forever.</p>
     *
     * <p><b>A journey waiting on a customer's signature is measured against a longer clock, not
     * exempted from one.</b> Thirty seconds is how long a module may think; it is an absurd
     * amount of time to give somebody to read a credit agreement, so that wait would be failed
     * while the customer was still on the page. But an unbounded exemption would hand back the
     * exact failure this sweeper exists to prevent — a row sitting IN_PROGRESS for the life of
     * the database — so it gets {@code orchestrator.signature.timeout} instead. Only the
     * operator hold is a true skip, because there the journey has asked nothing of anybody.</p>
     */
    @Transactional
    public int sweepTimeouts(Duration timeout) {
        Instant now = Instant.now();
        Instant cutoff = now.minus(timeout);
        Instant signatureCutoff = now.minus(signatureTimeout);
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
            // A parked journey is silent BY DESIGN — nothing has been asked of any service, so
            // there is nobody to give up on. Without this the demo dies thirty seconds into the
            // first pause, and it looks exactly like a broken module.
            if (app.isAwaitingOperator()) {
                continue;
            }
            boolean signing = app.isAwaitingSignature();
            Instant deadline = signing ? signatureCutoff : cutoff;
            Duration allowed = signing ? signatureTimeout : timeout;
            Instant seen = lastSeen.getOrDefault(app.getId(), app.getCreatedAt());
            if (seen != null && seen.isBefore(deadline)) {
                String serviceId = lastService.get(app.getId());
                events.save(new ApplicationEvent(app.getId(), app.getCurrentStep(), serviceId,
                        ApplicationEvent.TIMEOUT, "TIMEOUT",
                        signing ? "the customer did not sign within " + allowed
                                : "no callback within " + allowed));
                finish(app, Application.FAILED, signing
                        ? "the customer did not sign in time"
                        : "timed out waiting for " + serviceId);
                log.warn("Application {} timed out {}", app.getId(),
                        signing ? "waiting for the customer to sign" : "waiting for " + serviceId);
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
        return applications.findById(id).map(a -> {
            List<ApplicationEvent> log = events.findByApplicationIdOrderByIdAsc(id);
            return SagaDtos.ApplicationDetail.of(a, readPayload(a.getPayloadJson()),
                    readPayload(a.getOutputsJson()), stepsOf(log), log);
        });
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
            // The same three constants the vocabulary resolves to, so a bucket cannot drift away
            // from what gets stored.
            long accepted = t.getOrDefault(StatusVocabulary.ACCEPTED, 0L);
            long rejected = t.getOrDefault(StatusVocabulary.REJECTED, 0L);
            long referred = t.getOrDefault(StatusVocabulary.REFERRED, 0L);
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
        return new ApplicationRow(app.getId(), app.getApplicantName(), app.getProductCode(),
                app.getRequestedLimit(), app.getChannel(), app.getCurrentStep(),
                app.getPendingStep(), app.isAwaitingSignature(), app.getOverallStatus(),
                app.getCreatedAt(), app.getUpdatedAt(), stepsOf(log));
    }

    /**
     * One dot per service, derived from the log. Shared by the operator's board and the
     * customer's own progress rail — extracted rather than written twice so the two surfaces
     * cannot come to different conclusions about the same journey.
     */
    private List<StepView> stepsOf(List<ApplicationEvent> log) {
        Map<Integer, String> statusByStep = new HashMap<>();
        for (ApplicationEvent e : log) {   // ascending id — later events win
            switch (e.getEventType()) {
                case ApplicationEvent.REQUEST_SENT ->
                        statusByStep.putIfAbsent(e.getStepIndex(), StepView.IN_FLIGHT);
                case ApplicationEvent.CALLBACK ->
                        statusByStep.put(e.getStepIndex(), e.getStatus());
                case ApplicationEvent.TIMEOUT, ApplicationEvent.DISPATCH_FAILED ->
                        statusByStep.put(e.getStepIndex(), "TIMEOUT");
                // A PROGRESS_REPORTED row lands here on purpose: the step is still waiting, so
                // its dot must stay in-flight rather than be overwritten. AWAITING_SIGNATURE is
                // the same case — the customer is reading, the step is still out. AWAITING_OPERATOR
                // and RELEASED_BY_OPERATOR land here for the opposite reason: they are about a
                // step that has not been sent yet, which must stay pending.
                default -> { /* journey markers, progress reports and holds carry no status */ }
            }
        }
        return registry.ordered().stream()
                .map(s -> new StepView(s.step(), s.serviceId(),
                        statusByStep.getOrDefault(s.step(), StepView.PENDING)))
                .toList();
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
        // Nothing is waiting on a customer once the journey is over — including when the reason
        // it is over is that they never signed. Belt and braces beside applySignatureHold: the
        // sweeper and a dispatch failure both end journeys without going through it, and a stale
        // flag on a finished row would offer a dead Sign button on the customer's screen.
        app.setAwaitingSignatureAt(null);
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

    /**
     * Fold this service's {@code outputs} into the journey's accumulated map (api-contract §3).
     *
     * <p><b>Absent means unchanged.</b> A null map writes nothing at all — that is what keeps the
     * services which never send one completely unaffected by this feature, and it is why an empty
     * map is treated as a real (if pointless) report rather than as absence. The merge itself is
     * a plain {@code putAll}: last writer wins, and an explicit null value is stored as a null
     * rather than deleting the key. Nothing here defends one service's keys from another's —
     * that is what the ownership table in api-contract §3 is for.</p>
     *
     * <p>An oversized document is <b>dropped whole</b>, leaving the previous map intact. Never
     * truncated: half a JSON document is a parse error served to the next service as if it were
     * data, which is worse than the absence it would be replacing.</p>
     */
    private void mergeOutputs(Application app, ApplicationStatusUpdate cb) {
        if (cb.outputs() == null) {
            return;
        }
        Map<String, Object> merged = new LinkedHashMap<>(readPayload(app.getOutputsJson()));
        merged.putAll(cb.outputs());
        String encoded;
        try {
            encoded = json.writeValueAsString(merged);
        } catch (Exception e) {
            log.warn("{} sent outputs on {} that will not serialize — keeping the previous map: {}",
                    cb.serviceId(), app.getId(), e.toString());
            return;
        }
        if (encoded.length() > Application.OUTPUTS_MAX) {
            log.warn("{} sent outputs on {} that take the accumulated map to {} characters, over "
                            + "the {} the column holds — the whole update is dropped and the "
                            + "previous map kept. Report identifiers, not documents.",
                    cb.serviceId(), app.getId(), encoded.length(), Application.OUTPUTS_MAX);
            return;
        }
        app.setOutputsJson(encoded);
        applications.save(app);
        log.info("{} reported outputs {} on {}", cb.serviceId(), cb.outputs().keySet(), app.getId());
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
                command, target.application(), target.outputs());
    }
}
