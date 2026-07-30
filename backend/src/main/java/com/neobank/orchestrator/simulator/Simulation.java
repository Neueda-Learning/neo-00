package com.neobank.orchestrator.simulator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** One one-shot simulator dispatch and, when it arrives, the module's matching report. */
@Entity
@Table(name = "simulation")
public class Simulation {

    public static final int APPLICATION_JSON_MAX = 4000;
    private static final int ACK_BODY_MAX = 4000;
    private static final int COMMENT_MAX = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "application_id", nullable = false, length = 80)
    private String applicationId;

    @Column(name = "correlation_id", length = 100)
    private String correlationId;

    @Column(name = "scenario_id", length = 24)
    private String scenarioId;

    @Column(name = "target_service_id", nullable = false, length = 24)
    private String targetServiceId;

    @Column(name = "target_url", nullable = false, length = 500)
    private String targetUrl;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "ack_http_status")
    private Integer ackHttpStatus;

    @Column(name = "ack_body", length = ACK_BODY_MAX)
    private String ackBody;

    @Column(name = "reported_service_id", length = 24)
    private String reportedServiceId;

    @Column(name = "reported_status", length = 100)
    private String reportedStatus;

    @Column(name = "canonical_status", length = 24)
    private String canonicalStatus;

    @Column(name = "reported_comment", length = COMMENT_MAX)
    private String reportedComment;

    @Column(name = "reported_at")
    private Instant reportedAt;

    @Column(name = "application_json", length = APPLICATION_JSON_MAX)
    private String applicationJson;

    protected Simulation() {
        // JPA
    }

    public Simulation(String placeholderApplicationId, String correlationId, String scenarioId,
                      String targetServiceId, String targetUrl) {
        this.applicationId = placeholderApplicationId;
        this.correlationId = correlationId;
        this.scenarioId = scenarioId;
        this.targetServiceId = targetServiceId;
        this.targetUrl = targetUrl;
    }

    public void prepare(String applicationId, String correlationId, String applicationJson) {
        this.applicationId = applicationId;
        this.correlationId = correlationId;
        this.applicationJson = applicationJson != null
                && applicationJson.length() <= APPLICATION_JSON_MAX ? applicationJson : null;
        this.sentAt = Instant.now();
    }

    public void acknowledge(int status, String body) {
        this.ackHttpStatus = status;
        this.ackBody = truncate(body, ACK_BODY_MAX);
    }

    public void report(String serviceId, String rawStatus, String canonicalStatus, String comment) {
        this.reportedServiceId = serviceId;
        this.reportedStatus = truncate(rawStatus, 100);
        this.canonicalStatus = canonicalStatus;
        this.reportedComment = truncate(comment, COMMENT_MAX);
        this.reportedAt = Instant.now();
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max - 3) + "...";
    }

    public Long getId() { return id; }
    public String getApplicationId() { return applicationId; }
    public String getCorrelationId() { return correlationId; }
    public String getScenarioId() { return scenarioId; }
    public String getTargetServiceId() { return targetServiceId; }
    public String getTargetUrl() { return targetUrl; }
    public Instant getSentAt() { return sentAt; }
    public Integer getAckHttpStatus() { return ackHttpStatus; }
    public String getAckBody() { return ackBody; }
    public String getReportedServiceId() { return reportedServiceId; }
    public String getReportedStatus() { return reportedStatus; }
    public String getCanonicalStatus() { return canonicalStatus; }
    public String getReportedComment() { return reportedComment; }
    public Instant getReportedAt() { return reportedAt; }
    public String getApplicationJson() { return applicationJson; }
}
