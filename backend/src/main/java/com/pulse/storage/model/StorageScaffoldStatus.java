package com.pulse.storage.model;

import com.pulse.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Tracks the scaffold status for a (tenant, domain) tuple.
 * One row per (tenant_id, domain_slug) — upserted on preview or execute.
 *
 * <p>PKT-0012: The scaffold lifecycle is:
 * <ol>
 *   <li>{@code previewed} — manifest generated but not written to GCS</li>
 *   <li>{@code executed} — folders written to GCS (future; currently gated)</li>
 *   <li>{@code operator_blocked} — execution requested but gated</li>
 * </ol>
 */
@Entity
@Table(name = "storage_scaffold_status")
public class StorageScaffoldStatus extends BaseEntity {

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "domain_slug", nullable = false)
    private String domainSlug;

    /** 'previewed' | 'executed' | 'operator_blocked' */
    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "gcp_project_id")
    private String gcpProjectId;

    @Column(name = "service_account_email")
    private String serviceAccountEmail;

    @Column(name = "credential_source")
    private String credentialSource;

    @Column(name = "entry_count")
    private int entryCount;

    @Column(name = "last_previewed_at")
    private Instant lastPreviewedAt;

    @Column(name = "last_executed_at")
    private Instant lastExecutedAt;

    @Column(name = "execution_error")
    private String executionError;

    /** JSON serialized manifest snapshot for readback. */
    @Column(name = "manifest_snapshot", columnDefinition = "TEXT")
    private String manifestSnapshot;

    // -- Getters and setters --

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getDomainSlug() { return domainSlug; }
    public void setDomainSlug(String domainSlug) { this.domainSlug = domainSlug; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getGcpProjectId() { return gcpProjectId; }
    public void setGcpProjectId(String gcpProjectId) { this.gcpProjectId = gcpProjectId; }

    public String getServiceAccountEmail() { return serviceAccountEmail; }
    public void setServiceAccountEmail(String serviceAccountEmail) { this.serviceAccountEmail = serviceAccountEmail; }

    public String getCredentialSource() { return credentialSource; }
    public void setCredentialSource(String credentialSource) { this.credentialSource = credentialSource; }

    public int getEntryCount() { return entryCount; }
    public void setEntryCount(int entryCount) { this.entryCount = entryCount; }

    public Instant getLastPreviewedAt() { return lastPreviewedAt; }
    public void setLastPreviewedAt(Instant lastPreviewedAt) { this.lastPreviewedAt = lastPreviewedAt; }

    public Instant getLastExecutedAt() { return lastExecutedAt; }
    public void setLastExecutedAt(Instant lastExecutedAt) { this.lastExecutedAt = lastExecutedAt; }

    public String getExecutionError() { return executionError; }
    public void setExecutionError(String executionError) { this.executionError = executionError; }

    public String getManifestSnapshot() { return manifestSnapshot; }
    public void setManifestSnapshot(String manifestSnapshot) { this.manifestSnapshot = manifestSnapshot; }
}
