package com.neobank.orchestrator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One application walking the ten services.
 *
 * <p>{@code currentStep} is the 1-based index of the service currently working
 * (0 = not dispatched yet, 10 = the last one). {@code overallStatus} is the journey
 * outcome: only {@code ACCEPTED} at every step gets you to {@code COMPLETED}.</p>
 *
 * <p>A few fields from the application payload are denormalised onto the row so the board
 * can render without parsing JSON for every one; the payload itself is kept whole in
 * {@code payloadJson} because that is what was actually sent.</p>
 */
@Entity
@Table(name = "application")
public class Application {

    /** The journey is running; some service is working or about to be dispatched. */
    public static final String IN_PROGRESS = "IN_PROGRESS";
    /** All ten services accepted. */
    public static final String COMPLETED = "COMPLETED";
    /** A service said REJECTED — the journey stopped there. */
    public static final String REJECTED = "REJECTED";
    /** A service said REFERRED — a person would have to look; the journey stopped. */
    public static final String REFERRED = "REFERRED";
    /** A service never called back, or could not be reached at all. */
    public static final String FAILED = "FAILED";

    @Id
    @Column(length = 40)
    private String id;

    /** Sent on every dispatch so a service can tie its records back to this journey. */
    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "applicant_name", length = 120)
    private String applicantName;

    @Column(name = "product_code", length = 40)
    private String productCode;

    @Column(name = "requested_limit")
    private Integer requestedLimit;

    @Column(length = 24)
    private String channel;

    /**
     * The whole application object as it was sent, ~1.2 KB of JSON.
     *
     * <p>An explicit {@code VARCHAR} rather than {@code @Lob}/{@code TEXT}: H2 and MySQL
     * disagree about what those map to ({@code CLOB} vs {@code tinytext}), and
     * {@code ddl-auto=validate} then refuses to start on one of them. A plain length both
     * dialects render identically is the only thing that validates in H2 tests <em>and</em>
     * against real MySQL.</p>
     */
    @Column(name = "payload_json", length = 8000)
    private String payloadJson;

    @Column(name = "current_step", nullable = false)
    private int currentStep;

    /**
     * Demo stepping: {@code null} means the journey runs itself, a number means it is parked
     * and this is the step that will be dispatched when an operator releases it.
     *
     * <p>One nullable column rather than a boolean plus a target, because the target is not
     * derivable from {@code currentStep}: parked before the first dispatch it is 1 while
     * {@code currentStep} is still 0, and parked mid-journey it equals {@code currentStep},
     * which {@link com.neobank.orchestrator.saga.SagaStore} advances when the callback lands.</p>
     */
    @Column(name = "pending_step")
    private Integer pendingStep;

    /**
     * The agreement step is waiting for the customer to sign: {@code null} means nobody is being
     * waited on, a timestamp is when the wait started.
     *
     * <p><b>Not the same hold as {@link #pendingStep}, and deliberately a second column.</b> An
     * operator hold is released by a click and turning demo stepping off releases every one of
     * them; a customer hold is released by the module reporting what the customer did, and must
     * survive that. Sharing a column would make "release everything parked" cancel a signature
     * the customer is still reading.</p>
     *
     * <p>The step is not stored because it does not need to be: unlike an operator hold, nothing
     * here will be dispatched on release. {@code currentStep} still points at the signature
     * service, which is still the one that will answer.</p>
     */
    @Column(name = "awaiting_signature_at")
    private Instant awaitingSignatureAt;

    /**
     * What the services have reported so far, accumulated — the journey's own scratchpad.
     *
     * <p>Each module may attach an {@code outputs} map to its status update; they are merged
     * here, last writer wins, and the whole accumulated map rides every later dispatch envelope.
     * That is how neo-05's approved limit reaches neo-06, neo-07 and neo-08. {@code null} means
     * nothing has been reported yet, which is not the same as {@code {}}.</p>
     *
     * <p>Which module owns which key is fixed in {@code api-contract.md} §3, not decided per
     * team: a merged map is last-writer-wins, so two modules writing {@code approvedLimit} would
     * silently overwrite each other and the later step would emboss the wrong number.</p>
     *
     * <p>Sized like {@code payloadJson} and for the same reason — an explicit {@code VARCHAR}
     * both dialects render identically. 2000 characters is ~20× the handful of scalars the
     * registry allows, and costs 8000 of the 65535 bytes MySQL gives the whole row.</p>
     */
    public static final int OUTPUTS_MAX = 2000;

    @Column(name = "outputs_json", length = OUTPUTS_MAX)
    private String outputsJson;

    @Column(name = "overall_status", nullable = false, length = 24)
    private String overallStatus;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Application() {
        // JPA
    }

    public Application(String id, String correlationId, String applicantName, String productCode,
                       Integer requestedLimit, String channel, String payloadJson) {
        this.id = id;
        this.correlationId = correlationId;
        this.applicantName = applicantName;
        this.productCode = productCode;
        this.requestedLimit = requestedLimit;
        this.channel = channel;
        this.payloadJson = payloadJson;
        this.currentStep = 0;
        this.overallStatus = IN_PROGRESS;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    /** True once the journey can no longer move — nothing may restart it. */
    public boolean isTerminal() {
        return !IN_PROGRESS.equals(overallStatus);
    }

    public String getId() {
        return id;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getApplicantName() {
        return applicantName;
    }

    public String getProductCode() {
        return productCode;
    }

    public Integer getRequestedLimit() {
        return requestedLimit;
    }

    public String getChannel() {
        return channel;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public int getCurrentStep() {
        return currentStep;
    }

    public void setCurrentStep(int currentStep) {
        this.currentStep = currentStep;
        this.updatedAt = Instant.now();
    }

    public Integer getPendingStep() {
        return pendingStep;
    }

    public void setPendingStep(Integer pendingStep) {
        this.pendingStep = pendingStep;
        this.updatedAt = Instant.now();
    }

    /** Parked in demo mode, waiting for someone to press Proceed. */
    public boolean isAwaitingOperator() {
        return pendingStep != null;
    }

    public Instant getAwaitingSignatureAt() {
        return awaitingSignatureAt;
    }

    public void setAwaitingSignatureAt(Instant awaitingSignatureAt) {
        this.awaitingSignatureAt = awaitingSignatureAt;
        this.updatedAt = Instant.now();
    }

    /** Held at the agreement step, waiting for the customer to sign or decline. */
    public boolean isAwaitingSignature() {
        return awaitingSignatureAt != null;
    }

    public String getOutputsJson() {
        return outputsJson;
    }

    public void setOutputsJson(String outputsJson) {
        this.outputsJson = outputsJson;
        this.updatedAt = Instant.now();
    }

    public String getOverallStatus() {
        return overallStatus;
    }

    public void setOverallStatus(String overallStatus) {
        this.overallStatus = overallStatus;
        this.updatedAt = Instant.now();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
