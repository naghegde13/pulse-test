package com.pulse.deploy.model;

import com.pulse.common.model.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

/**
 * Phase 4 — append-only audit / state-transition log entry. One row
 * per state change, approval decision, cancellation, etc. Carries the
 * resolved actor + caller surface + correlation id + request body
 * hash so downstream replay/audit can reconstruct who did what.
 */
@Entity
@Table(name = "deployment_events")
public class DeploymentEvent extends BaseEntity {

    @Column(name = "deployment_id")
    private String deploymentId;

    @Column(name = "deployment_run_id")
    private String deploymentRunId;

    @Column(name = "schema_version", nullable = false)
    private String schemaVersion = "deployment-event.v1";

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "from_status")
    private String fromStatus;

    @Column(name = "to_status")
    private String toStatus;

    @Column(name = "actor_type", nullable = false)
    private String actorType;

    @Column(name = "actor_id", nullable = false)
    private String actorId;

    @Column
    private String surface;

    @Column(name = "correlation_id")
    private String correlationId;

    @Column(name = "request_body_sha256")
    private String requestBodySha256;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> details;

    public String getDeploymentId() { return deploymentId; }
    public void setDeploymentId(String deploymentId) { this.deploymentId = deploymentId; }
    public String getDeploymentRunId() { return deploymentRunId; }
    public void setDeploymentRunId(String deploymentRunId) { this.deploymentRunId = deploymentRunId; }
    public String getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(String schemaVersion) { this.schemaVersion = schemaVersion; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getFromStatus() { return fromStatus; }
    public void setFromStatus(String fromStatus) { this.fromStatus = fromStatus; }
    public String getToStatus() { return toStatus; }
    public void setToStatus(String toStatus) { this.toStatus = toStatus; }
    public String getActorType() { return actorType; }
    public void setActorType(String actorType) { this.actorType = actorType; }
    public String getActorId() { return actorId; }
    public void setActorId(String actorId) { this.actorId = actorId; }
    public String getSurface() { return surface; }
    public void setSurface(String surface) { this.surface = surface; }
    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }
    public String getRequestBodySha256() { return requestBodySha256; }
    public void setRequestBodySha256(String requestBodySha256) { this.requestBodySha256 = requestBodySha256; }
    public Map<String, Object> getDetails() { return details; }
    public void setDetails(Map<String, Object> details) { this.details = details; }
}
