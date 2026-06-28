package com.pulse.auth.model;

import com.pulse.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Per-tenant GCP <em>control-plane</em> project configuration. Each tenant
 * binds to exactly one control-plane project at a time via a unique constraint
 * on {@code tenant_id}.
 * <p>
 * The control-plane project is where PULSE's tenant service account lives,
 * where Secret Manager references resolve, and (in IMPERSONATION credential
 * mode) where the short-lived impersonation tokens are minted. This is
 * conceptually distinct from <em>data-plane</em> projects — per-environment
 * GCP projects that host buckets, Composer envs, Dataproc clusters, and
 * BigQuery datasets. Data-plane projects are tracked in
 * {@code storage_backends.gcp_project} and
 * {@code tenant_gcp_runtime_topology.*_project_id} and are NOT the same as
 * this field, even when they happen to coincide in single-project deployments.
 * <p>
 * Configured through {@code PUT /api/v1/tenants/{tenantId}/gcp-config}.
 * The credential resolver and identity probe derive the control-plane project
 * and principal from this record rather than from ambient gcloud auth or
 * hardcoded constants.
 */
@Entity
@Table(name = "tenant_gcp_configs")
public class TenantGcpConfig extends BaseEntity {

    @Column(name = "tenant_id", nullable = false, unique = true)
    private String tenantId;

    @Column(name = "control_plane_project_id", nullable = false)
    private String controlPlaneProjectId;

    @Column(name = "gcp_region", nullable = false)
    private String gcpRegion = "us-central1";

    @Column(nullable = false)
    private String status = "active";

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getControlPlaneProjectId() { return controlPlaneProjectId; }
    public void setControlPlaneProjectId(String controlPlaneProjectId) {
        this.controlPlaneProjectId = controlPlaneProjectId;
    }

    public String getGcpRegion() { return gcpRegion; }
    public void setGcpRegion(String gcpRegion) { this.gcpRegion = gcpRegion; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
