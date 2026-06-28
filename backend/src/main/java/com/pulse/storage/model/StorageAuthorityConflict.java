package com.pulse.storage.model;

import com.pulse.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Conflict row recorded when a {@code SubPipelineInstance}'s storage authority
 * cannot be resolved deterministically (ARCH-010). Drives the deployability
 * gate: unresolved conflicts block deploy and surface in static readiness
 * blockers.
 */
@Entity
@Table(name = "storage_authority_conflicts")
public class StorageAuthorityConflict extends BaseEntity {

    @Column(name = "instance_id", nullable = false)
    private String instanceId;

    @Column(name = "pipeline_id", nullable = false)
    private String pipelineId;

    @Column(name = "conflicting_storage_backend", length = 32)
    private String conflictingStorageBackend;

    @Column(name = "conflicting_lake_layer", length = 32)
    private String conflictingLakeLayer;

    @Column(name = "conflicting_lake_format", length = 32)
    private String conflictingLakeFormat;

    /** Stable machine-readable reason code (e.g. invalid_combination). */
    @Column(name = "reason", nullable = false, length = 64)
    private String reason;

    @Column(name = "detail", length = 1000)
    private String detail;

    @Column(name = "resolved", nullable = false)
    private boolean resolved;

    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
    public String getPipelineId() { return pipelineId; }
    public void setPipelineId(String pipelineId) { this.pipelineId = pipelineId; }
    public String getConflictingStorageBackend() { return conflictingStorageBackend; }
    public void setConflictingStorageBackend(String v) { this.conflictingStorageBackend = v; }
    public String getConflictingLakeLayer() { return conflictingLakeLayer; }
    public void setConflictingLakeLayer(String v) { this.conflictingLakeLayer = v; }
    public String getConflictingLakeFormat() { return conflictingLakeFormat; }
    public void setConflictingLakeFormat(String v) { this.conflictingLakeFormat = v; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public boolean isResolved() { return resolved; }
    public void setResolved(boolean resolved) { this.resolved = resolved; }
}
