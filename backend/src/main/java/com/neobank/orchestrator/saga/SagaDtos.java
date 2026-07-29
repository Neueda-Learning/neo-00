package com.neobank.orchestrator.saga;

import com.neobank.orchestrator.domain.Application;
import com.neobank.orchestrator.domain.ApplicationEvent;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** What crosses the orchestrator's own API: outbound to services, inbound from them,
 *  and the views the two front-end screens read. */
public final class SagaDtos {

    private SagaDtos() {
    }

    // ---- outbound: orchestrator → service ----

    /** The envelope POSTed to a service's {@code /api/v1/applications} (api-contract §2). */
    public record ApplicationRequest(
            String applicationId,
            String correlationId,
            String command,
            Map<String, Object> application) {
    }

    // ---- inbound: service → orchestrator ----

    /**
     * What a service PUTs to {@code /api/v1/applications/{applicationId}} when it has an answer
     * (api-contract §3).
     *
     * <p><b>Three fields — no {@code applicationId}, because it is the URL.</b> This is an update to
     * an application the orchestrator already owns, so the id identifies the resource; carrying it
     * in the body as well would only create a way for the two to disagree.
     * {@code serviceId} is {@code neo01}…{@code neo10}.</p>
     */
    public record ApplicationStatusUpdate(
            @NotBlank String serviceId,
            @NotBlank String status,
            String comment) {
    }

    /** Optional body for a manual {@code POST /api/v1/applications} — all fields optional. */
    public record CreateApplicationRequest(String applicantName, String productCode) {
    }

    /** The generator toggle. */
    public record GeneratorState(boolean enabled, long intervalMs, long created) {
    }

    public record GeneratorRequest(Boolean enabled, Long intervalMs) {
    }

    /**
     * The demo-stepping toggle: while it is on, no step is dispatched without an operator
     * pressing Proceed. {@code parked} is how many journeys are waiting on a click right now.
     */
    public record DemoState(boolean enabled, long parked) {
    }

    public record DemoRequest(Boolean enabled) {
    }

    // ---- views: orchestrator → front end ----

    /** One of the ten dots on a board row. */
    public record StepView(int step, String serviceId, String status) {

        /** Nothing has been sent to this service for this application yet. */
        public static final String PENDING = "pending";
        /** Dispatched, no callback yet — this is what "in progress" counts. */
        public static final String IN_FLIGHT = "in-flight";
    }

    /**
     * {@code pendingStep} is non-null only while demo stepping has this journey parked, and is
     * the step the Proceed button will send. The front end uses its presence to decide whether
     * to draw that button at all.
     */
    public record ApplicationRow(
            String id,
            String applicantName,
            String productCode,
            Integer requestedLimit,
            String channel,
            int currentStep,
            Integer pendingStep,
            String overallStatus,
            Instant createdAt,
            Instant updatedAt,
            List<StepView> steps) {
    }

    public record EventView(
            Long id,
            String applicationId,
            int stepIndex,
            String serviceId,
            String eventType,
            String status,
            String comment,
            Instant createdAt) {

        public static EventView from(ApplicationEvent e) {
            return new EventView(e.getId(), e.getApplicationId(), e.getStepIndex(),
                    e.getServiceId(), e.getEventType(), e.getStatus(), e.getComment(),
                    e.getCreatedAt());
        }
    }

    /**
     * The journey view of one application: the board row, the application itself, and the full
     * append-only event log. Served at {@code GET /api/v1/applications/{id}/journey} and read by
     * this orchestrator's own front end.
     *
     * <p><b>Not what a service gets.</b> {@code GET /api/v1/applications/{id}} returns the bare §4
     * application object — see {@link SagaStore#application(String)} for why.</p>
     *
     * <p>{@code application} is a parsed object, not the raw {@code payloadJson} string it used to
     * be. Handing a client JSON-inside-a-JSON-string makes every caller parse twice and is the
     * same cleanup already made on the module side's {@code RequestView}.</p>
     */
    public record ApplicationDetail(
            String id,
            String applicantName,
            String productCode,
            Integer requestedLimit,
            String channel,
            int currentStep,
            Integer pendingStep,
            String overallStatus,
            Map<String, Object> application,
            Instant createdAt,
            Instant updatedAt,
            List<EventView> events) {

        public static ApplicationDetail of(Application a, Map<String, Object> application,
                                           List<ApplicationEvent> events) {
            return new ApplicationDetail(
                    a.getId(), a.getApplicantName(), a.getProductCode(), a.getRequestedLimit(),
                    a.getChannel(), a.getCurrentStep(), a.getPendingStep(), a.getOverallStatus(),
                    application, a.getCreatedAt(), a.getUpdatedAt(),
                    events.stream().map(EventView::from).toList());
        }
    }

    /** One box on the services screen. */
    public record ServiceSummary(
            int step,
            String serviceId,
            String name,
            String baseUrl,
            long inProgress,
            long accepted,
            long rejected,
            long referred,
            long timedOut,
            long total) {
    }

    /** The board totals shown above the two screens. */
    public record BoardSummary(
            long total,
            long inProgress,
            long completed,
            long rejected,
            long referred,
            long failed) {
    }
}
