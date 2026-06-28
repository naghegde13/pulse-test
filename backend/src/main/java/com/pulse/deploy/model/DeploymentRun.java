package com.pulse.deploy.model;

import com.pulse.common.model.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/**
 * Phase 4 — single execution attempt for a parent {@link Deployment}.
 *
 * <p>Owns the deployment run state machine. Created in
 * {@code PENDING} (preflight not yet run) or
 * {@code PREFLIGHT_FAILED}/{@code PREFLIGHT_PASSED} immediately after
 * preflight, depending on the call site. Phase 5+ adds materialization
 * / runtime states.
 */
@Entity
@Table(name = "deployment_runs")
public class DeploymentRun extends BaseEntity {

    @Column(name = "deployment_id", nullable = false)
    private String deploymentId;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(nullable = false)
    private String status = "PENDING";

    @Column(name = "initiated_by", nullable = false)
    private String initiatedBy;

    @Column(name = "correlation_id")
    private String correlationId;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(name = "request_body_sha256")
    private String requestBodySha256;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "cancel_requested_at")
    private Instant cancelRequestedAt;

    @Column(name = "timeout_at")
    private Instant timeoutAt;

    /**
     * Phase 4 closeout: TEXT (not VARCHAR(64)) so a multi-blocker
     * preflight failure reason cannot truncate or violate the column
     * constraint at persist time.
     */
    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    public String getDeploymentId() { return deploymentId; }
    public void setDeploymentId(String deploymentId) { this.deploymentId = deploymentId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getInitiatedBy() { return initiatedBy; }
    public void setInitiatedBy(String initiatedBy) { this.initiatedBy = initiatedBy; }
    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getRequestBodySha256() { return requestBodySha256; }
    public void setRequestBodySha256(String requestBodySha256) { this.requestBodySha256 = requestBodySha256; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public Instant getCancelRequestedAt() { return cancelRequestedAt; }
    public void setCancelRequestedAt(Instant cancelRequestedAt) { this.cancelRequestedAt = cancelRequestedAt; }
    public Instant getTimeoutAt() { return timeoutAt; }
    public void setTimeoutAt(Instant timeoutAt) { this.timeoutAt = timeoutAt; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
}
