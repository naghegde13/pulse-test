package com.pulse.storage.model;

import com.pulse.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Tenant-scoped storage backend — one row per (tenant, environment, backend).
 *
 * <p>Distinct from {@link com.pulse.sor.model.ConnectorInstance}: connector
 * instances are SoR-scoped technical endpoints (a Postgres JDBC connector
 * for a Source SoR; a Kafka cluster connector for a Target SoR). Storage
 * backends are tenant-scoped pipeline-WORKING-STORAGE — where
 * bronze/silver/gold tables and the file-flow lifecycle folders
 * physically live.
 *
 * <p>Backed by V96's {@code storage_backends} table.
 */
@Entity
@Table(name = "storage_backends")
public class StorageBackend extends BaseEntity {

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    /** 'dev' | 'integration' | 'uat' | 'prod' — DB CHECK enforced. */
    @Column(name = "environment", nullable = false)
    private String environment;

    /** 'DPC' | 'GCP' — DB CHECK enforced. */
    @Column(name = "backend", nullable = false)
    private String backend;

    @Column(name = "storage_root_files", nullable = false)
    private String storageRootFiles;

    @Column(name = "storage_root_lake", nullable = false)
    private String storageRootLake;

    /** Set when backend='GCP'. Null otherwise (DB CHECK enforced). */
    @Column(name = "gcp_project")
    private String gcpProject;

    /** 's3a' | 'hdfs' — set when backend='DPC'. Null otherwise. */
    @Column(name = "dpc_scheme")
    private String dpcScheme;

    /** Set when backend='DPC'. Null otherwise. */
    @Column(name = "dpc_cluster")
    private String dpcCluster;

    @Column(name = "provisioning_status", nullable = false)
    private String provisioningStatus = "pending";

    @Column(name = "provisioning_validated_at")
    private Instant provisioningValidatedAt;

    @Column(name = "provisioning_error")
    private String provisioningError;

    @Column(name = "disabled", nullable = false)
    private boolean disabled = false;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }

    public String getBackend() { return backend; }
    public void setBackend(String backend) { this.backend = backend; }
    public StorageBackendType getBackendType() { return StorageBackendType.from(backend); }

    public String getStorageRootFiles() { return storageRootFiles; }
    public void setStorageRootFiles(String storageRootFiles) { this.storageRootFiles = storageRootFiles; }

    public String getStorageRootLake() { return storageRootLake; }
    public void setStorageRootLake(String storageRootLake) { this.storageRootLake = storageRootLake; }

    public String getGcpProject() { return gcpProject; }
    public void setGcpProject(String gcpProject) { this.gcpProject = gcpProject; }

    public String getDpcScheme() { return dpcScheme; }
    public void setDpcScheme(String dpcScheme) { this.dpcScheme = dpcScheme; }

    public String getDpcCluster() { return dpcCluster; }
    public void setDpcCluster(String dpcCluster) { this.dpcCluster = dpcCluster; }

    public String getProvisioningStatus() { return provisioningStatus; }
    public void setProvisioningStatus(String provisioningStatus) { this.provisioningStatus = provisioningStatus; }
    public ProvisioningStatus getProvisioningStatusEnum() {
        return ProvisioningStatus.from(provisioningStatus);
    }

    public Instant getProvisioningValidatedAt() { return provisioningValidatedAt; }
    public void setProvisioningValidatedAt(Instant provisioningValidatedAt) {
        this.provisioningValidatedAt = provisioningValidatedAt;
    }

    public String getProvisioningError() { return provisioningError; }
    public void setProvisioningError(String provisioningError) { this.provisioningError = provisioningError; }

    public boolean isDisabled() { return disabled; }
    public void setDisabled(boolean disabled) { this.disabled = disabled; }
}
