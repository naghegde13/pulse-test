package com.pulse.deploy.projection.model;

import com.pulse.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "runtime_projections")
public class RuntimeProjection extends BaseEntity {

    @Column(name = "package_id", nullable = false)
    private String packageId;

    @Column(name = "target_id", nullable = false)
    private String targetId;

    @Column(name = "environment", nullable = false)
    private String environment;

    @Column(name = "runtime_persona", nullable = false)
    private String runtimePersona;

    @Column(name = "runtime_authority_version")
    private String runtimeAuthorityVersion;

    @Column(name = "projection_hash", nullable = false)
    private String projectionHash;

    @Column(name = "status", nullable = false)
    private String status = "active";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "readiness_blockers", columnDefinition = "jsonb")
    private List<Map<String, Object>> readinessBlockers;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "resolved_storage_roots", columnDefinition = "jsonb")
    private Map<String, Object> resolvedStorageRoots;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "resolved_catalogs", columnDefinition = "jsonb")
    private Map<String, Object> resolvedCatalogs;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "resolved_entrypoints", columnDefinition = "jsonb")
    private Map<String, Object> resolvedEntrypoints;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "adapter_plan", columnDefinition = "jsonb")
    private Map<String, Object> adapterPlan;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "orchestration_block", columnDefinition = "jsonb")
    private Map<String, Object> orchestrationBlock;

    @Column(name = "projected_at")
    private Instant projectedAt;

    public String getPackageId() { return packageId; }
    public void setPackageId(String packageId) { this.packageId = packageId; }

    public String getTargetId() { return targetId; }
    public void setTargetId(String targetId) { this.targetId = targetId; }

    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }

    public String getRuntimePersona() { return runtimePersona; }
    public void setRuntimePersona(String runtimePersona) { this.runtimePersona = runtimePersona; }

    public String getRuntimeAuthorityVersion() { return runtimeAuthorityVersion; }
    public void setRuntimeAuthorityVersion(String runtimeAuthorityVersion) { this.runtimeAuthorityVersion = runtimeAuthorityVersion; }

    public String getProjectionHash() { return projectionHash; }
    public void setProjectionHash(String projectionHash) { this.projectionHash = projectionHash; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public List<Map<String, Object>> getReadinessBlockers() { return readinessBlockers; }
    public void setReadinessBlockers(List<Map<String, Object>> readinessBlockers) { this.readinessBlockers = readinessBlockers; }

    public Map<String, Object> getResolvedStorageRoots() { return resolvedStorageRoots; }
    public void setResolvedStorageRoots(Map<String, Object> resolvedStorageRoots) { this.resolvedStorageRoots = resolvedStorageRoots; }

    public Map<String, Object> getResolvedCatalogs() { return resolvedCatalogs; }
    public void setResolvedCatalogs(Map<String, Object> resolvedCatalogs) { this.resolvedCatalogs = resolvedCatalogs; }

    public Map<String, Object> getResolvedEntrypoints() { return resolvedEntrypoints; }
    public void setResolvedEntrypoints(Map<String, Object> resolvedEntrypoints) { this.resolvedEntrypoints = resolvedEntrypoints; }

    public Map<String, Object> getAdapterPlan() { return adapterPlan; }
    public void setAdapterPlan(Map<String, Object> adapterPlan) { this.adapterPlan = adapterPlan; }

    public Map<String, Object> getOrchestrationBlock() { return orchestrationBlock; }
    public void setOrchestrationBlock(Map<String, Object> orchestrationBlock) { this.orchestrationBlock = orchestrationBlock; }

    public Instant getProjectedAt() { return projectedAt; }
    public void setProjectedAt(Instant projectedAt) { this.projectedAt = projectedAt; }

    public boolean isActive() { return "active".equals(status); }
    public boolean isStale() { return "stale".equals(status); }
    public boolean isSuperseded() { return "superseded".equals(status); }
}
