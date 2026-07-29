package com.neobank.orchestrator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One line in the append-only log — the system of record for the whole journey.
 *
 * <p><b>Rows are inserted and never updated or deleted.</b> Everything the board and the
 * service summary display is derived from this table, so "what actually happened" is always
 * reconstructible: a step was dispatched, a service acknowledged, a service answered, a
 * service went silent, a journey ended.</p>
 *
 * <p>There is deliberately no setter on this class. If a fact turns out to be wrong, the
 * fix is another row, not an edit.</p>
 */
@Entity
@Table(name = "application_event")
public class ApplicationEvent {

    /** The application was created and the journey began. */
    public static final String JOURNEY_STARTED = "JOURNEY_STARTED";
    /** A command was POSTed to a service. */
    public static final String REQUEST_SENT = "REQUEST_SENT";
    /** The service answered 202. */
    public static final String ACK_RECEIVED = "ACK_RECEIVED";
    /** The service could not be reached at all. */
    public static final String DISPATCH_FAILED = "DISPATCH_FAILED";
    /** The service reported its decision. */
    public static final String CALLBACK = "CALLBACK";
    /**
     * The service reported that it is still working — the journey neither advances nor ends.
     *
     * <p>Deliberately not a {@link #CALLBACK}: the board and the service summary both treat a
     * callback as the end of a wait, so a progress report recorded as one would overwrite the
     * step's in-flight dot and drop the application out of the running count. A module honestly
     * reporting progress would erase itself from the screen that exists to show it.</p>
     */
    public static final String PROGRESS_REPORTED = "PROGRESS_REPORTED";
    /**
     * Demo stepping is on and the journey is parked: the next service will not be sent
     * anything until an operator presses Proceed.
     *
     * <p>Carries no {@code status}, so the step's dot stays {@code pending} — nothing has
     * been asked of that service yet.</p>
     */
    public static final String AWAITING_OPERATOR = "AWAITING_OPERATOR";
    /** An operator released a parked journey; the step is dispatched immediately after. */
    public static final String RELEASED_BY_OPERATOR = "RELEASED_BY_OPERATOR";
    /** No callback arrived within the timeout. */
    public static final String TIMEOUT = "TIMEOUT";
    /** The journey reached a terminal state. */
    public static final String JOURNEY_ENDED = "JOURNEY_ENDED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "application_id", nullable = false, length = 40)
    private String applicationId;

    @Column(name = "step_index", nullable = false)
    private int stepIndex;

    @Column(name = "service_id", length = 24)
    private String serviceId;

    @Column(name = "event_type", nullable = false, length = 24)
    private String eventType;

    private static final int STATUS_MAX = 24;

    @Column(length = STATUS_MAX)
    private String status;

    @Column(length = 500)
    private String comment;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ApplicationEvent() {
        // JPA
    }

    public ApplicationEvent(String applicationId, int stepIndex, String serviceId,
                            String eventType, String status, String comment) {
        this.applicationId = applicationId;
        this.stepIndex = stepIndex;
        this.serviceId = serviceId;
        this.eventType = eventType;
        this.status = cap(status);
        this.comment = truncate(comment);
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /**
     * A status the orchestrator does not recognise is stored exactly as the module sent it, so
     * this column now carries arbitrary module input. A word longer than the column would throw
     * on insert and turn the module's report into a 500 — losing the one clue an operator has.
     * No ellipsis, unlike a comment: a status is a token, and three of twenty-four characters is
     * too much to spend on punctuation.
     */
    private static String cap(String status) {
        return status == null || status.length() <= STATUS_MAX
                ? status
                : status.substring(0, STATUS_MAX);
    }

    /** Comments carry exception messages, which can be far longer than the column. */
    private static String truncate(String comment) {
        if (comment == null || comment.length() <= 500) {
            return comment;
        }
        return comment.substring(0, 497) + "...";
    }

    public Long getId() {
        return id;
    }

    public String getApplicationId() {
        return applicationId;
    }

    public int getStepIndex() {
        return stepIndex;
    }

    public String getServiceId() {
        return serviceId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getStatus() {
        return status;
    }

    public String getComment() {
        return comment;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
