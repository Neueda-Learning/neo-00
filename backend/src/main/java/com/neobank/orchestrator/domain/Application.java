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
