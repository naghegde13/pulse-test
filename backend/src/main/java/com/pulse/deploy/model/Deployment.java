package com.pulse.deploy.model;

import com.pulse.common.model.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "deployments")
public class Deployment extends BaseEntity {

    @Column(name = "package_id", nullable = false)
    private String packageId;

    @Column(name = "target_id", nullable = false)
    private String targetId;

    @Column(name = "pipeline_id", nullable = false)
    private String pipelineId;

    @Column(name = "version_id", nullable = false)
    private String versionId;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "deployed_by", nullable = false)
    private String deployedBy;

    @Column(nullable = false)
    private String status = "PENDING";

    @Column(name = "deploy_log", columnDefinition = "TEXT")
    private String deployLog;

    @Column(name = "deployed_at")
    private Instant deployedAt;

    @Column(name = "rolled_back_at")
    private Instant rolledBackAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    public String getPackageId() { return packageId; }
    public void setPackageId(String packageId) { this.packageId = packageId; }
    public String getTargetId() { return targetId; }
    public void setTargetId(String targetId) { this.targetId = targetId; }
    public String getPipelineId() { return pipelineId; }
    public void setPipelineId(String pipelineId) { this.pipelineId = pipelineId; }
    public String getVersionId() { return versionId; }
    public void setVersionId(String versionId) { this.versionId = versionId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getDeployedBy() { return deployedBy; }
    public void setDeployedBy(String deployedBy) { this.deployedBy = deployedBy; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getDeployLog() { return deployLog; }
    public void setDeployLog(String deployLog) { this.deployLog = deployLog; }
    public Instant getDeployedAt() { return deployedAt; }
    public void setDeployedAt(Instant deployedAt) { this.deployedAt = deployedAt; }
    public Instant getRolledBackAt() { return rolledBackAt; }
    public void setRolledBackAt(Instant rolledBackAt) { this.rolledBackAt = rolledBackAt; }
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
}
