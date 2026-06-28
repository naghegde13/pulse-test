-- PKT-0025: Tenant GCP Runtime Topology.
-- Stores per-tenant topology configuration for Composer, Dataproc Serverless,
-- BigQuery (native + managed Iceberg), GCS, Secret Manager, logging, and evidence.
-- One topology record per tenant; updated via upsert.

CREATE TABLE IF NOT EXISTS tenant_gcp_runtime_topology (
    id                              VARCHAR(26)   NOT NULL PRIMARY KEY,
    tenant_id                       VARCHAR(26)   NOT NULL,

    -- Composer topology
    composer_project_id             VARCHAR(255),
    composer_environment            VARCHAR(255),
    composer_region                 VARCHAR(100),
    composer_environment_bucket     VARCHAR(512),
    composer_dag_prefix             VARCHAR(512),
    composer_plugins_prefix         VARCHAR(512),
    composer_data_prefix            VARCHAR(512),
    composer_log_prefix             VARCHAR(512),

    -- Dataproc Serverless topology
    dataproc_project_id             VARCHAR(255),
    dataproc_region                 VARCHAR(100),
    dataproc_workload_sa_email      VARCHAR(320),
    dataproc_network                VARCHAR(255),
    dataproc_subnet                 VARCHAR(255),
    dataproc_staging_bucket         VARCHAR(512),

    -- BigQuery native topology
    bq_project_id                   VARCHAR(255),
    bq_location                     VARCHAR(100),
    bq_dataset_bronze               VARCHAR(255),
    bq_dataset_silver               VARCHAR(255),
    bq_dataset_gold                 VARCHAR(255),

    -- BigQuery connection + service account
    bq_connection_id                VARCHAR(255),
    bq_connection_region            VARCHAR(100),
    bq_connection_sa_email          VARCHAR(320),

    -- Iceberg storage
    iceberg_storage_bucket          VARCHAR(512),

    -- Evidence sink
    evidence_sink_bucket            VARCHAR(512),
    evidence_sink_dataset           VARCHAR(255),

    -- Secret Manager
    secret_manager_project_id       VARCHAR(255),

    -- Logging
    logging_project_id              VARCHAR(255),
    logging_log_bucket              VARCHAR(255),

    -- Control plane service account (PULSE itself)
    control_plane_sa_email          VARCHAR(320),

    created_at                      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_tgrt_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT uq_tgrt_tenant UNIQUE (tenant_id)
);

COMMENT ON TABLE tenant_gcp_runtime_topology IS
    'PKT-0025: Per-tenant GCP runtime topology covering Composer, Dataproc, BigQuery, Iceberg, GCS, Secret Manager, logging, and evidence.';
