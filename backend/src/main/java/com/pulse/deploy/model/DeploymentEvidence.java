package com.pulse.deploy.model;

import com.pulse.common.model.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

/**
 * Phase 4 — schema-versioned evidence artifact produced by preflight,
 * run, materialization, or adapter calls. Carries a stable
 * {@code deployment-evidence.v1} envelope so downstream surfaces can
 * read every kind of evidence with one parser.
 */
@Entity
@Table(name = "deployment_evidence")
public class DeploymentEvidence extends BaseEntity {

    @Column(name = "deployment_id")
    private String deploymentId;

    @Column(name = "deployment_run_id")
    private String deploymentRunId;

    @Column(name = "package_id")
    private String packageId;

    @Column(name = "schema_version", nullable = false)
    private String schemaVersion = "deployment-evidence.v1";

    @Column(name = "artifact_id", nullable = false)
    private String artifactId;

    @Column(nullable = false)
    private String type;

    @Column
    private String path;

    @Column(nullable = false)
    private String sha256;

    @Column(name = "produced_by", nullable = false)
    private String producedBy;

    @Column(name = "correlation_id")
    private String correlationId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> summary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> body;

    public String getDeploymentId() { return deploymentId; }
    public void setDeploymentId(String deploymentId) { this.deploymentId = deploymentId; }
    public String getDeploymentRunId() { return deploymentRunId; }
    public void setDeploymentRunId(String deploymentRunId) { this.deploymentRunId = deploymentRunId; }
    public String getPackageId() { return packageId; }
    public void setPackageId(String packageId) { this.packageId = packageId; }
    public String getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(String schemaVersion) { this.schemaVersion = schemaVersion; }
    public String getArtifactId() { return artifactId; }
    public void setArtifactId(String artifactId) { this.artifactId = artifactId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }
    public String getProducedBy() { return producedBy; }
    public void setProducedBy(String producedBy) { this.producedBy = producedBy; }
    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }
    public Map<String, Object> getSummary() { return summary; }
    public void setSummary(Map<String, Object> summary) { this.summary = summary; }
    public Map<String, Object> getBody() { return body; }
    public void setBody(Map<String, Object> body) { this.body = body; }
}
