package com.pulse.tenant.model;

import com.pulse.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * PKT-0025: Per-tenant GCP runtime topology record.
 *
 * <p>Stores the full topology for Composer, Dataproc Serverless, BigQuery
 * (native + managed Iceberg), GCS, Secret Manager, logging, and evidence
 * sinks. One record per tenant via unique constraint on {@code tenant_id}.
 *
 * <p>Three split service accounts are modeled:
 * <ul>
 *   <li>{@code controlPlaneSaEmail} — PULSE control plane (Composer/SM/logging)</li>
 *   <li>{@code dataprocWorkloadSaEmail} — Dataproc runtime workload identity</li>
 *   <li>{@code bqConnectionSaEmail} — BigQuery connection service account for Iceberg bucket access</li>
 * </ul>
 */
@Entity
@Table(name = "tenant_gcp_runtime_topology")
public class TenantGcpRuntimeTopology extends BaseEntity {

    @Column(name = "tenant_id", nullable = false, unique = true)
    private String tenantId;

    // ---- Composer ----
    @Column(name = "composer_project_id") private String composerProjectId;
    @Column(name = "composer_environment") private String composerEnvironment;
    @Column(name = "composer_region") private String composerRegion;
    @Column(name = "composer_environment_bucket") private String composerEnvironmentBucket;
    @Column(name = "composer_dag_prefix") private String composerDagPrefix;
    @Column(name = "composer_plugins_prefix") private String composerPluginsPrefix;
    @Column(name = "composer_data_prefix") private String composerDataPrefix;
    @Column(name = "composer_log_prefix") private String composerLogPrefix;

    // ---- Dataproc Serverless ----
    @Column(name = "dataproc_project_id") private String dataprocProjectId;
    @Column(name = "dataproc_region") private String dataprocRegion;
    @Column(name = "dataproc_workload_sa_email") private String dataprocWorkloadSaEmail;
    @Column(name = "dataproc_network") private String dataprocNetwork;
    @Column(name = "dataproc_subnet") private String dataprocSubnet;
    @Column(name = "dataproc_staging_bucket") private String dataprocStagingBucket;

    // ---- BigQuery native ----
    @Column(name = "bq_project_id") private String bqProjectId;
    @Column(name = "bq_location") private String bqLocation;
    @Column(name = "bq_dataset_bronze") private String bqDatasetBronze;
    @Column(name = "bq_dataset_silver") private String bqDatasetSilver;
    @Column(name = "bq_dataset_gold") private String bqDatasetGold;

    // ---- BigQuery connection ----
    @Column(name = "bq_connection_id") private String bqConnectionId;
    @Column(name = "bq_connection_region") private String bqConnectionRegion;
    @Column(name = "bq_connection_sa_email") private String bqConnectionSaEmail;

    // ---- Iceberg storage ----
    @Column(name = "iceberg_storage_bucket") private String icebergStorageBucket;

    // ---- Evidence sink ----
    @Column(name = "evidence_sink_bucket") private String evidenceSinkBucket;
    @Column(name = "evidence_sink_dataset") private String evidenceSinkDataset;

    // ---- Secret Manager ----
    @Column(name = "secret_manager_project_id") private String secretManagerProjectId;

    /**
     * PKT-FINAL-5 / BUG-54: per-tenant secret authority mode.
     * Values: {@code LOCAL_STUB}, {@code GCP_SECRET_MANAGER}, {@code BLOCKED}.
     * Defaults to {@code LOCAL_STUB} on first row (V148 default).
     */
    @Column(name = "secret_authority_mode", nullable = false) private String secretAuthorityMode = "LOCAL_STUB";

    /**
     * PKT-FINAL-5 / BUG-54: optional namespace prefix applied to all
     * tenant-scoped secret IDs in GSM. NULL = no prefix.
     */
    @Column(name = "secret_name_prefix") private String secretNamePrefix;

    // ---- Logging ----
    @Column(name = "logging_project_id") private String loggingProjectId;
    @Column(name = "logging_log_bucket") private String loggingLogBucket;

    // ---- Control plane SA ----
    @Column(name = "control_plane_sa_email") private String controlPlaneSaEmail;

    // ---- Getters/setters ----

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getComposerProjectId() { return composerProjectId; }
    public void setComposerProjectId(String v) { this.composerProjectId = v; }

    public String getComposerEnvironment() { return composerEnvironment; }
    public void setComposerEnvironment(String v) { this.composerEnvironment = v; }

    public String getComposerRegion() { return composerRegion; }
    public void setComposerRegion(String v) { this.composerRegion = v; }

    public String getComposerEnvironmentBucket() { return composerEnvironmentBucket; }
    public void setComposerEnvironmentBucket(String v) { this.composerEnvironmentBucket = v; }

    public String getComposerDagPrefix() { return composerDagPrefix; }
    public void setComposerDagPrefix(String v) { this.composerDagPrefix = v; }

    public String getComposerPluginsPrefix() { return composerPluginsPrefix; }
    public void setComposerPluginsPrefix(String v) { this.composerPluginsPrefix = v; }

    public String getComposerDataPrefix() { return composerDataPrefix; }
    public void setComposerDataPrefix(String v) { this.composerDataPrefix = v; }

    public String getComposerLogPrefix() { return composerLogPrefix; }
    public void setComposerLogPrefix(String v) { this.composerLogPrefix = v; }

    public String getDataprocProjectId() { return dataprocProjectId; }
    public void setDataprocProjectId(String v) { this.dataprocProjectId = v; }

    public String getDataprocRegion() { return dataprocRegion; }
    public void setDataprocRegion(String v) { this.dataprocRegion = v; }

    public String getDataprocWorkloadSaEmail() { return dataprocWorkloadSaEmail; }
    public void setDataprocWorkloadSaEmail(String v) { this.dataprocWorkloadSaEmail = v; }

    public String getDataprocNetwork() { return dataprocNetwork; }
    public void setDataprocNetwork(String v) { this.dataprocNetwork = v; }

    public String getDataprocSubnet() { return dataprocSubnet; }
    public void setDataprocSubnet(String v) { this.dataprocSubnet = v; }

    public String getDataprocStagingBucket() { return dataprocStagingBucket; }
    public void setDataprocStagingBucket(String v) { this.dataprocStagingBucket = v; }

    public String getBqProjectId() { return bqProjectId; }
    public void setBqProjectId(String v) { this.bqProjectId = v; }

    public String getBqLocation() { return bqLocation; }
    public void setBqLocation(String v) { this.bqLocation = v; }

    public String getBqDatasetBronze() { return bqDatasetBronze; }
    public void setBqDatasetBronze(String v) { this.bqDatasetBronze = v; }

    public String getBqDatasetSilver() { return bqDatasetSilver; }
    public void setBqDatasetSilver(String v) { this.bqDatasetSilver = v; }

    public String getBqDatasetGold() { return bqDatasetGold; }
    public void setBqDatasetGold(String v) { this.bqDatasetGold = v; }

    public String getBqConnectionId() { return bqConnectionId; }
    public void setBqConnectionId(String v) { this.bqConnectionId = v; }

    public String getBqConnectionRegion() { return bqConnectionRegion; }
    public void setBqConnectionRegion(String v) { this.bqConnectionRegion = v; }

    public String getBqConnectionSaEmail() { return bqConnectionSaEmail; }
    public void setBqConnectionSaEmail(String v) { this.bqConnectionSaEmail = v; }

    public String getIcebergStorageBucket() { return icebergStorageBucket; }
    public void setIcebergStorageBucket(String v) { this.icebergStorageBucket = v; }

    public String getEvidenceSinkBucket() { return evidenceSinkBucket; }
    public void setEvidenceSinkBucket(String v) { this.evidenceSinkBucket = v; }

    public String getEvidenceSinkDataset() { return evidenceSinkDataset; }
    public void setEvidenceSinkDataset(String v) { this.evidenceSinkDataset = v; }

    public String getSecretManagerProjectId() { return secretManagerProjectId; }
    public void setSecretManagerProjectId(String v) { this.secretManagerProjectId = v; }

    public String getSecretAuthorityMode() { return secretAuthorityMode; }
    public void setSecretAuthorityMode(String v) { this.secretAuthorityMode = v; }

    public String getSecretNamePrefix() { return secretNamePrefix; }
    public void setSecretNamePrefix(String v) { this.secretNamePrefix = v; }

    public String getLoggingProjectId() { return loggingProjectId; }
    public void setLoggingProjectId(String v) { this.loggingProjectId = v; }

    public String getLoggingLogBucket() { return loggingLogBucket; }
    public void setLoggingLogBucket(String v) { this.loggingLogBucket = v; }

    public String getControlPlaneSaEmail() { return controlPlaneSaEmail; }
    public void setControlPlaneSaEmail(String v) { this.controlPlaneSaEmail = v; }
}
