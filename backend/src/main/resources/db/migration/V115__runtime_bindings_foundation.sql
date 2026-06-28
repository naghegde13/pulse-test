-- V112: ARCH-017 Runtime Bindings Foundation
--
-- Introduces runtime_bindings as the single authoritative
-- tenant/environment runtime-binding table, with kind-specific
-- detail tables, validation evidence, and deployment target
-- classification columns.
--
-- storage_backends remains as legacy mirror/diagnostic evidence.

-- ---------------------------------------------------------------------
-- 1. runtime_bindings — authoritative binding table
-- ---------------------------------------------------------------------
CREATE TABLE runtime_bindings (
    id                      VARCHAR(26)  PRIMARY KEY,
    tenant_id               VARCHAR(26)  NOT NULL
                            REFERENCES tenants(id) ON DELETE CASCADE,
    environment             VARCHAR(16)  NOT NULL,
    binding_kind            VARCHAR(16)  NOT NULL,
    settings_role           VARCHAR(16)  NOT NULL DEFAULT 'PRIMARY',
    record_state            VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    validation_status       VARCHAR(16)  NOT NULL DEFAULT 'PENDING',

    storage_root_files      VARCHAR(512),
    storage_root_lake       VARCHAR(512),
    storage_root_ops        VARCHAR(512),

    diagnostic_reason       VARCHAR(128),
    diagnostic_details      JSONB,

    validated_at            TIMESTAMPTZ,
    validation_error        TEXT,

    source_evidence         JSONB,

    created_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_rb_environment
        CHECK (environment IN ('local','dev','integration','uat','prod')),
    CONSTRAINT chk_rb_binding_kind
        CHECK (binding_kind IN ('GCP','DPC','LOCAL')),
    CONSTRAINT chk_rb_settings_role
        CHECK (settings_role IN ('PRIMARY','DIAGNOSTIC')),
    CONSTRAINT chk_rb_record_state
        CHECK (record_state IN ('ACTIVE','INACTIVE')),
    CONSTRAINT chk_rb_validation_status
        CHECK (validation_status IN ('PENDING','VALIDATED','FAILED','DISABLED')),
    CONSTRAINT chk_rb_local_env
        CHECK (binding_kind != 'LOCAL' OR environment = 'local'),
    CONSTRAINT chk_rb_diagnostic_reason
        CHECK (settings_role != 'DIAGNOSTIC' OR diagnostic_reason IS NOT NULL)
);

-- Partial unique index: at most one active PRIMARY binding per (tenant_id, environment).
-- H2 does not support WHERE on indexes; application-layer uniqueness is
-- enforced by RuntimeBindingService. On Postgres, uncomment the filtered index:
-- CREATE UNIQUE INDEX uq_rb_active_primary
--     ON runtime_bindings (tenant_id, environment)
--     WHERE settings_role = 'PRIMARY' AND record_state = 'ACTIVE';
CREATE INDEX idx_rb_tenant_env ON runtime_bindings (tenant_id, environment);

-- ---------------------------------------------------------------------
-- 2. GCP runtime binding details
-- ---------------------------------------------------------------------
CREATE TABLE gcp_runtime_binding_details (
    id                      VARCHAR(26)  PRIMARY KEY
                            REFERENCES runtime_bindings(id) ON DELETE CASCADE,
    gcp_project             VARCHAR(128) NOT NULL,
    gcp_region              VARCHAR(64),
    composer_environment    VARCHAR(128),
    dataproc_cluster        VARCHAR(128),
    bigquery_dataset_prefix VARCHAR(128),
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- ---------------------------------------------------------------------
-- 3. DPC runtime binding details
-- ---------------------------------------------------------------------
CREATE TABLE dpc_runtime_binding_details (
    id                      VARCHAR(26)  PRIMARY KEY
                            REFERENCES runtime_bindings(id) ON DELETE CASCADE,
    dpc_scheme              VARCHAR(16)  NOT NULL,
    dpc_cluster             VARCHAR(128) NOT NULL,
    airflow_url             VARCHAR(512),
    spark_submit_url        VARCHAR(512),
    hive_metastore_uri      VARCHAR(512),
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- ---------------------------------------------------------------------
-- 4. Local runtime binding details
-- ---------------------------------------------------------------------
CREATE TABLE local_runtime_binding_details (
    id                      VARCHAR(26)  PRIMARY KEY
                            REFERENCES runtime_bindings(id) ON DELETE CASCADE,
    local_spark_master      VARCHAR(128) DEFAULT 'local[*]',
    local_warehouse_path    VARCHAR(512),
    minio_endpoint          VARCHAR(512),
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- ---------------------------------------------------------------------
-- 5. Runtime binding validation evidence
-- ---------------------------------------------------------------------
CREATE TABLE runtime_binding_validation_evidence (
    id                      VARCHAR(26)  PRIMARY KEY,
    binding_id              VARCHAR(26)  NOT NULL
                            REFERENCES runtime_bindings(id) ON DELETE CASCADE,
    probe_type              VARCHAR(64)  NOT NULL,
    probe_result            VARCHAR(16)  NOT NULL,
    probe_details           JSONB,
    probed_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_rbve_binding ON runtime_binding_validation_evidence(binding_id);

-- ---------------------------------------------------------------------
-- 6. Deployment target classification columns
-- ---------------------------------------------------------------------
ALTER TABLE deployment_targets ADD COLUMN IF NOT EXISTS settings_role      VARCHAR(16) DEFAULT 'PRIMARY';
ALTER TABLE deployment_targets ADD COLUMN IF NOT EXISTS diagnostic_reason  VARCHAR(128);
ALTER TABLE deployment_targets ADD COLUMN IF NOT EXISTS diagnostic_details JSONB;
ALTER TABLE deployment_targets ADD COLUMN IF NOT EXISTS runtime_binding_id VARCHAR(26);

-- Application-layer enforcement handles settings_role validation
-- (H2 does not support ALTER TABLE ADD CONSTRAINT on existing tables
-- the same way as Postgres).

-- Filtered unique on Postgres only; application-layer enforcement in
-- RuntimeBindingService handles H2-based tests.
-- CREATE UNIQUE INDEX uq_dt_active_primary
--     ON deployment_targets (tenant_id, environment)
--     WHERE settings_role = 'PRIMARY' AND enabled = TRUE;

-- ---------------------------------------------------------------------
-- 7. storage_backends: add storage_root_ops column
-- ---------------------------------------------------------------------
ALTER TABLE storage_backends
    ADD COLUMN IF NOT EXISTS storage_root_ops VARCHAR(255);
