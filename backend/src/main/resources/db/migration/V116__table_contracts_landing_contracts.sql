-- =============================================================
-- V113: Table Contracts and Dataset Landing Contracts
-- ARCH-005 / ARCH-015 — Physical table-contract authority
-- =============================================================

-- -----------------------------------------------
-- table_contracts
-- -----------------------------------------------
CREATE TABLE IF NOT EXISTS table_contracts (
    id                     VARCHAR(26)  NOT NULL PRIMARY KEY,
    pipeline_id            VARCHAR(26)  NOT NULL,
    version_id             VARCHAR(26)  NOT NULL,
    producing_instance_id  VARCHAR(26)  NOT NULL,
    output_port_name       VARCHAR(100) NOT NULL DEFAULT 'main_output',
    domain_id              VARCHAR(26)  NOT NULL,
    domain_slug            VARCHAR(100) NOT NULL,
    layer                  VARCHAR(20)  NOT NULL,
    table_role             VARCHAR(40)  NOT NULL,
    table_name             VARCHAR(200) NOT NULL,
    table_slug             VARCHAR(200) NOT NULL,
    logical_table_id       VARCHAR(300) NOT NULL,
    source_sor_id          VARCHAR(26),
    source_sor_slug        VARCHAR(100),
    source_dataset_id      VARCHAR(26),
    source_dataset_slug    VARCHAR(100),
    table_format           VARCHAR(40)  NOT NULL DEFAULT 'PARQUET',
    catalog_kind           VARCHAR(40)  NOT NULL DEFAULT 'NONE',
    schema_name            VARCHAR(200) NOT NULL,
    catalog_table_name     VARCHAR(200) NOT NULL,
    relative_storage_path  VARCHAR(500) NOT NULL,
    partition_spec         TEXT,
    layout_spec            TEXT,
    primary_key_columns    TEXT,
    business_date_columns  TEXT,
    write_mode             VARCHAR(40)  NOT NULL DEFAULT 'append',
    ddl_strategy           VARCHAR(40)  NOT NULL DEFAULT 'create_if_not_exists',
    writer_owner           VARCHAR(26)  NOT NULL,
    contract_version       INT          NOT NULL DEFAULT 1,
    status                 VARCHAR(20)  NOT NULL DEFAULT 'proposed',
    provenance             TEXT,
    created_at             TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_tc_version_status ON table_contracts (version_id, status);
CREATE INDEX idx_tc_version_instance ON table_contracts (version_id, producing_instance_id, status);
CREATE INDEX idx_tc_pipeline ON table_contracts (pipeline_id);

-- -----------------------------------------------
-- dataset_landing_contracts
-- -----------------------------------------------
CREATE TABLE IF NOT EXISTS dataset_landing_contracts (
    id                          VARCHAR(26)  NOT NULL PRIMARY KEY,
    tenant_id                   VARCHAR(26)  NOT NULL,
    domain_id                   VARCHAR(26)  NOT NULL,
    domain_slug                 VARCHAR(100) NOT NULL,
    sor_id                      VARCHAR(26)  NOT NULL,
    sor_slug                    VARCHAR(100) NOT NULL,
    dataset_id                  VARCHAR(26)  NOT NULL,
    dataset_slug                VARCHAR(100) NOT NULL,
    contract_version            INT          NOT NULL DEFAULT 1,
    status                      VARCHAR(20)  NOT NULL DEFAULT 'active',
    root_kind                   VARCHAR(20)  NOT NULL DEFAULT 'files',
    relative_landing_path       VARCHAR(500) NOT NULL,
    arrival_partition_template  VARCHAR(200),
    rejected_relative_path      VARCHAR(500),
    archive_relative_path       VARCHAR(500),
    outgoing_relative_path      VARCHAR(500),
    first_arrival_at            TIMESTAMP,
    first_arrival_event_id      VARCHAR(26),
    provenance                  TEXT,
    created_at                  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_dlc_dataset_status ON dataset_landing_contracts (dataset_id, status);
CREATE INDEX idx_dlc_tenant ON dataset_landing_contracts (tenant_id, status);
CREATE INDEX idx_dlc_domain ON dataset_landing_contracts (domain_id, status);
