package com.pulse.pipeline.model;

import com.pulse.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "pipelines")
public class Pipeline extends BaseEntity {

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "domain_name", nullable = false)
    private String domainName;

    @Column(name = "domain_id")
    private String domainId;

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "active_version_id")
    private String activeVersionId;

    @Column(name = "pipeline_slug")
    private String pipelineSlug;

    @Column(name = "exposed_as_remote_target", nullable = false)
    private boolean exposedAsRemoteTarget = false;

    /**
     * Default storage backend for new instances added to this pipeline
     * (ARCH-010). One of {@code DPC}, {@code GCP}. Existing instances are not
     * rebased when this changes.
     */
    @Column(name = "default_storage_backend", nullable = false, length = 16)
    private String defaultStorageBackend = "DPC";

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getDomainName() { return domainName; }
    public void setDomainName(String domainName) { this.domainName = domainName; }
    public String getDomainId() { return domainId; }
    public void setDomainId(String domainId) { this.domainId = domainId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public String getActiveVersionId() { return activeVersionId; }
    public void setActiveVersionId(String activeVersionId) { this.activeVersionId = activeVersionId; }
    public String getPipelineSlug() { return pipelineSlug; }
    public void setPipelineSlug(String pipelineSlug) { this.pipelineSlug = pipelineSlug; }
    public boolean isExposedAsRemoteTarget() { return exposedAsRemoteTarget; }
    public void setExposedAsRemoteTarget(boolean exposedAsRemoteTarget) {
        this.exposedAsRemoteTarget = exposedAsRemoteTarget;
    }
    public String getDefaultStorageBackend() { return defaultStorageBackend; }
    public void setDefaultStorageBackend(String defaultStorageBackend) { this.defaultStorageBackend = defaultStorageBackend; }
}
