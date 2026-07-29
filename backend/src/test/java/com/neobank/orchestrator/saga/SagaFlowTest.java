package com.neobank.orchestrator.saga;

import static org.assertj.core.api.Assertions.assertThat;

import com.neobank.orchestrator.domain.Application;
import com.neobank.orchestrator.generator.GeneratorService;
import com.neobank.orchestrator.saga.SagaDtos.ApplicationDetail;
import com.neobank.orchestrator.saga.SagaDtos.ApplicationRequest;
import com.neobank.orchestrator.saga.SagaDtos.ApplicationRow;
import com.neobank.orchestrator.saga.SagaDtos.ApplicationStatusUpdate;
import com.neobank.orchestrator.saga.SagaDtos.EventView;
import com.neobank.orchestrator.saga.SagaDtos.ServiceSummary;
import com.neobank.orchestrator.saga.SagaDtos.StepView;
import com.neobank.orchestrator.saga.SagaStore.DispatchTarget;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;

/**
 * The state machine, end to end against H2 with the HTTP call stubbed out.
 *
 * <p>The {@link RestClient} is a deep-stub mock, so a dispatch "succeeds" instantly without
 * a service listening: what is under test is the sequencing — who gets dispatched, when the
 * journey advances, and above all when it must <em>not</em>.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
// Its own in-memory database. The default H2 URL is shared by every test context in the
// module, and this class sweeps *all* in-progress applications — including other classes'.
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:sagaflow;MODE=MySQL;DB_CLOSE_DELAY=-1")
class SagaFlowTest {

    @MockBean(answer = Answers.RETURNS_DEEP_STUBS)
    RestClient restClient;

    @Autowired
    GeneratorService generator;

    @Autowired
    SagaEngine engine;

    @Autowired
    SagaStore store;

    // ---- the happy path ----

    @Test
    void acceptedCallbackAdvancesToTheNextService() {
        String id = startAndAwaitDispatch();

        engine.handleApplicationStatusUpdate(id,
                new ApplicationStatusUpdate("neo01", "ACCEPTED", "ok"));
        awaitDispatchOf(id, 2);

        ApplicationDetail detail = detail(id);
        assertThat(detail.overallStatus()).isEqualTo(Application.IN_PROGRESS);
        assertThat(detail.currentStep()).isEqualTo(2);
        assertThat(eventTypes(detail)).containsSubsequence(
                "JOURNEY_STARTED", "REQUEST_SENT", "ACK_RECEIVED", "CALLBACK", "REQUEST_SENT");
    }

    @Test
    void acceptancesFromEveryStepCompleteTheJourney() {
        String id = startAndAwaitDispatch();

        int last = store.serviceSummaries().size();
        for (int step = 1; step <= last; step++) {
            engine.handleApplicationStatusUpdate(id,
                    new ApplicationStatusUpdate(serviceIdOf(step), "ACCEPTED", "ok"));
            if (step < last) {
                awaitDispatchOf(id, step + 1);
            }
        }

        ApplicationDetail detail = detail(id);
        assertThat(detail.overallStatus()).isEqualTo(Application.COMPLETED);
        assertThat(eventTypes(detail)).endsWith("JOURNEY_ENDED");
        assertThat(store.board(50).stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow()
                .steps()).allSatisfy(s -> assertThat(s.status()).isEqualTo("ACCEPTED"));
    }

    @Test
    void aModulesOwnDomainWordAdvancesTheJourney() {
        // The APP-0011 incident as a test: neo01 reported PASSED, its brief's word for a pass,
        // and the orchestrator ignored it — so the journey stalled at step 1 and died on the
        // 30-second sweep with nothing said about why.
        String id = startAndAwaitDispatch();

        engine.handleApplicationStatusUpdate(id,
                new ApplicationStatusUpdate("neo01", "PASSED", "all three rules passed"));
        awaitDispatchOf(id, 2);

        assertThat(detail(id).currentStep()).isEqualTo(2);
        // Stored canonically, so the board dot and the service tallies still recognise it.
        assertThat(stepsOf(id).get(0).status()).isEqualTo(StatusVocabulary.ACCEPTED);
        assertThat(summaryFor("neo01").accepted()).isPositive();
    }

    // ---- stopping ----

    @Test
    void failedFromTheAccountModuleParksTheJourneyRatherThanRejectingIt() {
        // FAILED means something different depending on who says it. For neo07 the core banking
        // system was unreachable — nobody was refused — so the journey must park for a person
        // instead of ending REJECTED. This is the case that justifies keying the table per module.
        String id = startAndAwaitDispatch();
        for (int step = 1; step <= 6; step++) {
            engine.handleApplicationStatusUpdate(id,
                    new ApplicationStatusUpdate(serviceIdOf(step), "ACCEPTED", "ok"));
            awaitDispatchOf(id, step + 1);
        }

        engine.handleApplicationStatusUpdate(id,
                new ApplicationStatusUpdate("neo07", "FAILED", "core banking unreachable"));

        assertThat(detail(id).overallStatus()).isEqualTo(Application.REFERRED);
        assertThat(stepsOf(id).get(6).status()).isEqualTo(StatusVocabulary.REFERRED);
    }

    @Test
    void rejectedCallbackEndsTheJourneyWhereItHappened() {
        String id = startAndAwaitDispatch();
        engine.handleApplicationStatusUpdate(id,
                new ApplicationStatusUpdate("neo01", "ACCEPTED", "ok"));
        awaitDispatchOf(id, 2);

        engine.handleApplicationStatusUpdate(id,
                new ApplicationStatusUpdate("neo02", "REJECTED", "dice"));

        assertThat(detail(id).overallStatus()).isEqualTo(Application.REJECTED);
        // Step 3 must never be dispatched.
        sleep(200);
        assertThat(stepsOf(id)).satisfies(steps -> {
            assertThat(steps.get(0).status()).isEqualTo("ACCEPTED");
            assertThat(steps.get(1).status()).isEqualTo("REJECTED");
            assertThat(steps.get(2).status()).isEqualTo(StepView.PENDING);
        });
    }

    @Test
    void referredCallbackAlsoEndsTheJourney() {
        String id = startAndAwaitDispatch();

        engine.handleApplicationStatusUpdate(id,
                new ApplicationStatusUpdate("neo01", "REFERRED", "a human should look"));

        assertThat(detail(id).overallStatus()).isEqualTo(Application.REFERRED);
        assertThat(stepsOf(id).get(1).status()).isEqualTo(StepView.PENDING);
    }

    // ---- the things that must not happen ----

    @Test
    void timeoutFailsTheJourneyAndALateCallbackCannotResurrectIt() {
        String id = startAndAwaitDispatch();

        // Everything so far is older than "now", so a zero-length timeout sweeps it.
        store.sweepTimeouts(Duration.ZERO);
        assertThat(detail(id).overallStatus()).isEqualTo(Application.FAILED);

        // The service finally answers. It is recorded — but the journey stays dead and
        // step 2 is never dispatched.
        engine.handleApplicationStatusUpdate(id,
                new ApplicationStatusUpdate("neo01", "ACCEPTED", "sorry I'm late"));
        sleep(300);

        ApplicationDetail after = detail(id);
        assertThat(after.overallStatus()).isEqualTo(Application.FAILED);
        assertThat(after.currentStep()).isEqualTo(1);
        assertThat(eventTypes(after)).contains("TIMEOUT", "CALLBACK");
        assertThat(dispatchCount(after, 2)).isZero();
    }

    @Test
    void callbackFromAServiceThatIsNotTheCurrentStepIsRecordedButIgnored() {
        String id = startAndAwaitDispatch();

        engine.handleApplicationStatusUpdate(id,
                new ApplicationStatusUpdate("neo07", "REJECTED", "not your turn"));
        sleep(200);

        ApplicationDetail detail = detail(id);
        assertThat(detail.overallStatus()).isEqualTo(Application.IN_PROGRESS);
        assertThat(detail.events()).anyMatch(
                e -> "CALLBACK".equals(e.eventType()) && "neo07".equals(e.serviceId()));
    }

    @Test
    void unknownStatusIsRecordedAndChangesNothing() {
        String id = startAndAwaitDispatch();

        engine.handleApplicationStatusUpdate(id,
                new ApplicationStatusUpdate("neo01", "MAYBE", "?"));
        sleep(200);

        assertThat(detail(id).overallStatus()).isEqualTo(Application.IN_PROGRESS);
        assertThat(detail(id).currentStep()).isEqualTo(1);
    }

    @Test
    void aProgressReportKeepsTheJourneyWaitingWithoutTouchingItsDotOrItsCount() {
        String id = startAndAwaitDispatch();

        engine.handleApplicationStatusUpdate(id,
                new ApplicationStatusUpdate("neo01", "PENDING", "awaiting the signature"));
        sleep(200);

        ApplicationDetail detail = detail(id);
        assertThat(detail.events()).anyMatch(e -> "PROGRESS_REPORTED".equals(e.eventType()));
        assertThat(detail.overallStatus()).isEqualTo(Application.IN_PROGRESS);
        assertThat(detail.currentStep()).isEqualTo(1);

        // The two that pin the choice of a separate event type. Recorded as a CALLBACK, this
        // report would overwrite the step's in-flight dot and clear neo01's running count — a
        // module honestly saying "still working" would erase itself from both screens.
        assertThat(stepsOf(id).get(0).status()).isEqualTo(StepView.IN_FLIGHT);
        assertThat(summaryFor("neo01").inProgress()).isPositive();
    }

    @Test
    void aStatusTooLongForTheColumnIsRecordedRatherThanFailingThePut() {
        // An unrecognised word is stored as the module sent it, so this column now carries
        // arbitrary module input. Over-length input must not turn the report into a 500 and
        // lose the one clue an operator has.
        String id = startAndAwaitDispatch();

        engine.handleApplicationStatusUpdate(id,
                new ApplicationStatusUpdate("neo01", "X".repeat(40), "a very long opinion"));
        sleep(200);

        assertThat(detail(id).events()).anyMatch(e -> "X".repeat(24).equals(e.status()));
        assertThat(detail(id).overallStatus()).isEqualTo(Application.IN_PROGRESS);
    }

    @Test
    void callbackForAnUnknownApplicationIsIgnoredNotFatal() {
        engine.handleApplicationStatusUpdate("APP-NOPE",
                new ApplicationStatusUpdate("neo01", "ACCEPTED", "?"));
        // Reaching here without an exception is the assertion: a stray callback must not
        // break the endpoint that every service depends on.
    }

    // ---- demo stepping ----

    /**
     * The toggle lives on a shared bean, so a test that left it on would silently park every
     * test after it. Turning it off also releases anything this test parked.
     */
    @AfterEach
    void demoSteppingOff() {
        engine.setDemoStepping(false);
    }

    @Test
    void demoSteppingParksTheJourneyBeforeTheVeryFirstDispatch() {
        engine.setDemoStepping(true);

        String id = generator.createAndStart().getId();

        ApplicationDetail detail = detail(id);
        assertThat(detail.pendingStep()).isEqualTo(1);
        assertThat(detail.overallStatus()).isEqualTo(Application.IN_PROGRESS);
        assertThat(eventTypes(detail)).containsExactly("JOURNEY_STARTED", "AWAITING_OPERATOR");
        // No special case for the first step: eight steps means eight clicks.
        sleep(200);
        assertThat(dispatchCount(detail(id), 1)).isZero();
    }

    @Test
    void proceedSendsTheParkedStepAndTheJourneyThenParksAgain() {
        engine.setDemoStepping(true);
        String id = generator.createAndStart().getId();

        assertThat(engine.proceed(id)).contains(1);
        awaitDispatchOf(id, 1);
        assertThat(detail(id).pendingStep()).isNull();

        engine.handleApplicationStatusUpdate(id,
                new ApplicationStatusUpdate("neo01", "ACCEPTED", "ok"));

        // Parked again — and step 2 stays unsent until the next click.
        assertThat(detail(id).pendingStep()).isEqualTo(2);
        sleep(200);
        assertThat(dispatchCount(detail(id), 2)).isZero();
    }

    @Test
    void proceedOnAJourneyThatIsNotParkedDoesNothing() {
        String id = startAndAwaitDispatch();

        assertThat(engine.proceed(id)).isEmpty();
        assertThat(engine.proceed("APP-NOPE")).isEmpty();
    }

    /**
     * The one that would kill a demo. A parked journey is silent because nothing was asked of
     * anybody — the sweeper must not read that as a module gone quiet and fail it thirty
     * seconds into the first pause.
     */
    @Test
    void aParkedJourneyIsNotSweptByTheTimeout() {
        engine.setDemoStepping(true);
        String id = generator.createAndStart().getId();
        assertThat(detail(id).pendingStep()).isEqualTo(1);

        engine.sweepTimeouts(Duration.ZERO);

        assertThat(detail(id).overallStatus()).isEqualTo(Application.IN_PROGRESS);
        assertThat(eventTypes(detail(id))).doesNotContain("TIMEOUT");
        // Still held: with stepping on, the sweep must not release it either.
        assertThat(detail(id).pendingStep()).isEqualTo(1);
    }

    /**
     * The restart hole. {@code demoStepping} is in memory and comes back off; {@code pending_step}
     * is in the database and comes back set — so a journey parked when the process died would
     * return held with nothing holding it, and the timeout sweep skips parked rows, so nothing
     * would ever pick it up again.
     */
    @Test
    void aJourneyLeftParkedWhileSteppingIsOffIsReleasedByTheNextSweep() {
        String id = startAndAwaitDispatch();     // stepping is off
        store.park(id, 2);                       // exactly what a restart leaves behind
        assertThat(detail(id).pendingStep()).isEqualTo(2);

        // A generous timeout, so nothing here is failing for being quiet — the release is the
        // only thing under test.
        engine.sweepTimeouts(Duration.ofMinutes(5));

        awaitDispatchOf(id, 2);
        assertThat(detail(id).pendingStep()).isNull();
        assertThat(detail(id).overallStatus()).isEqualTo(Application.IN_PROGRESS);
    }

    /** Only ACCEPTED parks, so a refusal still ends where it happened and offers no button. */
    @Test
    void aRejectionInDemoModeEndsTheJourneyRatherThanParkingIt() {
        engine.setDemoStepping(true);
        String id = generator.createAndStart().getId();
        engine.proceed(id);
        awaitDispatchOf(id, 1);

        engine.handleApplicationStatusUpdate(id,
                new ApplicationStatusUpdate("neo01", "REJECTED", "no"));

        assertThat(detail(id).overallStatus()).isEqualTo(Application.REJECTED);
        assertThat(detail(id).pendingStep()).isNull();
    }

    /** The way out of an abandoned demo: everything already parked goes on its way. */
    @Test
    void turningDemoSteppingOffReleasesEveryParkedJourney() {
        engine.setDemoStepping(true);
        String first = generator.createAndStart().getId();
        String second = generator.createAndStart().getId();

        engine.setDemoStepping(false);

        awaitDispatchOf(first, 1);
        awaitDispatchOf(second, 1);
        assertThat(detail(first).pendingStep()).isNull();
        assertThat(detail(second).pendingStep()).isNull();
    }

    // ---- views ----

    @Test
    void serviceSummaryCountsWhatIsInFlightAndWhatWasDecided() {
        String id = startAndAwaitDispatch();

        ServiceSummary first = summaryFor("neo01");
        assertThat(first.inProgress()).isPositive();

        engine.handleApplicationStatusUpdate(id,
                new ApplicationStatusUpdate("neo01", "ACCEPTED", "ok"));
        awaitDispatchOf(id, 2);

        assertThat(summaryFor("neo01").accepted()).isPositive();
        assertThat(summaryFor("neo02").inProgress()).isPositive();
    }

    @Test
    void eventsCanBeFilteredToOneService() {
        String id = startAndAwaitDispatch();
        engine.handleApplicationStatusUpdate(id,
                new ApplicationStatusUpdate("neo01", "ACCEPTED", "ok"));
        awaitDispatchOf(id, 2);

        List<EventView> forService = store.events("neo02", 100).stream()
                .map(EventView::from).toList();

        assertThat(forService).isNotEmpty();
        assertThat(forService).allSatisfy(e -> assertThat(e.serviceId()).isEqualTo("neo02"));
    }

    // ---- outputs ----

    @Test
    void whatAServiceProducesIsVisibleOnTheJourney() {
        String id = startAndAwaitDispatch();

        engine.handleApplicationStatusUpdate(id, new ApplicationStatusUpdate(
                "neo01", "ACCEPTED", "ok", Map.of("approvedLimit", 3000)));

        assertThat(detail(id).outputs()).containsEntry("approvedLimit", 3000);
    }

    @Test
    void laterServicesAddTheirOwnKeysRatherThanReplacingTheMap() {
        String id = startAndAwaitDispatch();

        engine.handleApplicationStatusUpdate(id, new ApplicationStatusUpdate(
                "neo01", "ACCEPTED", "ok", Map.of("approvedLimit", 3000)));
        awaitDispatchOf(id, 2);
        engine.handleApplicationStatusUpdate(id, new ApplicationStatusUpdate(
                "neo02", "ACCEPTED", "ok", Map.of("accountId", "CC-0058291")));

        assertThat(detail(id).outputs())
                .containsEntry("approvedLimit", 3000)
                .containsEntry("accountId", "CC-0058291");
    }

    /** Absent means unchanged — the whole reason the modules that ignore outputs are unaffected. */
    @Test
    void aStatusUpdateWithNoOutputsLeavesWhatWasAlreadyReported() {
        String id = startAndAwaitDispatch();

        engine.handleApplicationStatusUpdate(id, new ApplicationStatusUpdate(
                "neo01", "ACCEPTED", "ok", Map.of("approvedLimit", 3000)));
        awaitDispatchOf(id, 2);
        engine.handleApplicationStatusUpdate(id,
                new ApplicationStatusUpdate("neo02", "ACCEPTED", "nothing to add"));

        assertThat(detail(id).outputs()).containsEntry("approvedLimit", 3000);
    }

    /**
     * Dropped whole, not truncated: half a JSON document would reach the next service looking
     * like data, which is worse than the absence it replaced.
     */
    @Test
    void anOversizedMapIsDroppedWholeAndTheEarlierOneSurvives() {
        String id = startAndAwaitDispatch();

        engine.handleApplicationStatusUpdate(id, new ApplicationStatusUpdate(
                "neo01", "ACCEPTED", "ok", Map.of("approvedLimit", 3000)));
        awaitDispatchOf(id, 2);
        engine.handleApplicationStatusUpdate(id, new ApplicationStatusUpdate(
                "neo02", "ACCEPTED", "ok", Map.of("essay", "x".repeat(Application.OUTPUTS_MAX))));

        assertThat(detail(id).outputs())
                .containsEntry("approvedLimit", 3000)
                .doesNotContainKey("essay");
    }

    /** Recorded on the log, but a service that is not the current step may not move the journey
     *  — and that includes moving what the journey has accumulated. */
    @Test
    void outputsFromAServiceThatIsNotTheCurrentStepNeverReachTheJourney() {
        String id = startAndAwaitDispatch();

        engine.handleApplicationStatusUpdate(id, new ApplicationStatusUpdate(
                "neo05", "ACCEPTED", "not my turn", Map.of("approvedLimit", 9999)));

        assertThat(detail(id).outputs()).isEmpty();
    }

    /**
     * The one assertion that proves the feature: what step 1 produced is in the envelope step 2
     * receives. Goes through the same {@code beginDispatch} → {@code toRequest} pair
     * {@link SagaEngine#dispatch} uses, rather than the stubbed HTTP client.
     */
    @Test
    void theEnvelopeForTheNextStepCarriesWhatEarlierServicesReported() {
        String id = startAndAwaitDispatch();

        engine.handleApplicationStatusUpdate(id, new ApplicationStatusUpdate(
                "neo01", "ACCEPTED", "ok", Map.of("approvedLimit", 3000, "apr", 24.9)));
        awaitDispatchOf(id, 2);

        DispatchTarget target = store.beginDispatch(id, 2).orElseThrow();
        ApplicationRequest envelope = store.toRequest(target, "process-application");

        assertThat(envelope.outputs())
                .containsEntry("approvedLimit", 3000)
                .containsEntry("apr", 24.9);
        // A sibling of the application, never merged into it: the application is what the
        // customer submitted and must read the same whether pushed here or pulled from /{id}.
        assertThat(envelope.application()).doesNotContainKey("approvedLimit");
    }

    @Test
    void theFirstStepIsDispatchedWithAnEmptyOutputsMapRatherThanNull() {
        String id = startAndAwaitDispatch();

        DispatchTarget target = store.beginDispatch(id, 1).orElseThrow();

        assertThat(store.toRequest(target, "process-application").outputs()).isEmpty();
    }

    // ---- helpers ----

    private String startAndAwaitDispatch() {
        String id = generator.createAndStart().getId();
        awaitDispatchOf(id, 1);
        return id;
    }

    /**
     * Wait for the dispatch to be <em>finished</em>, i.e. for the ack — not merely for
     * REQUEST_SENT. The scheduler writes REQUEST_SENT, makes the call, then writes
     * ACK_RECEIVED; returning at the first of those leaves the second racing whatever the
     * test does next, which shows up as an out-of-order event log or an application the
     * timeout sweep declines to touch because its newest event landed after the sweep's
     * cutoff. Both were seen on a slow CI runner.
     */
    private void awaitDispatchOf(String applicationId, int step) {
        await(() -> eventCount(detail(applicationId), "ACK_RECEIVED", step) > 0,
                "step " + step + " to be dispatched and acknowledged for " + applicationId);
    }

    private long dispatchCount(ApplicationDetail detail, int step) {
        return eventCount(detail, "REQUEST_SENT", step);
    }

    private long eventCount(ApplicationDetail detail, String eventType, int step) {
        return detail.events().stream()
                .filter(e -> eventType.equals(e.eventType()) && e.stepIndex() == step)
                .count();
    }

    private ApplicationDetail detail(String id) {
        return store.detail(id).orElseThrow();
    }

    private List<StepView> stepsOf(String id) {
        return store.board(50).stream()
                .filter(r -> r.id().equals(id))
                .findFirst()
                .map(ApplicationRow::steps)
                .orElseThrow();
    }

    private ServiceSummary summaryFor(String serviceId) {
        return store.serviceSummaries().stream()
                .filter(s -> s.serviceId().equals(serviceId))
                .findFirst()
                .orElseThrow();
    }

    private static String[] eventTypes(ApplicationDetail detail) {
        return detail.events().stream().map(EventView::eventType).toArray(String[]::new);
    }

    private static String serviceIdOf(int step) {
        return "neo" + String.format("%02d", step);
    }

    /** Dispatches happen on the scheduler, so the assertions have to wait for them. */
    private static void await(BooleanSupplier condition, String what) {
        Instant deadline = Instant.now().plusSeconds(5);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(25);
        }
        throw new AssertionError("Timed out waiting for " + what);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
