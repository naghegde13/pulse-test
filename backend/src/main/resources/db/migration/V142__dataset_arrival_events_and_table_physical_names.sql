-- V142: dataset_arrival_events and table_physical_names
-- PKT-0023: Physical Design Authority Completion
--
-- dataset_arrival_events: first-arrival ledger proving data has landed.
-- Once a landing contract records its first arrival, the contract path
-- becomes immutable (display-name changes cannot mutate landed paths).
--
-- table_physical_names: authoritative physical table name registry with
-- uniqueness enforcement per (tenant, catalog_kind, schema_name, physical_table_name).
-- Prevents two contracts from claiming the same physical table name.

CREATE TABLE IF NOT EXISTS dataset_arrival_events (
    id               VARCHAR(26)  PRIMARY KEY,
    tenant_id        VARCHAR(26)  NOT NULL,
    dataset_id       VARCHAR(26)  NOT NULL,
    landing_contract_id VARCHAR(26) NOT NULL,
    contract_version INT          NOT NULL,
    arrival_path     VARCHAR(1000) NOT NULL,
    ingest_date      VARCHAR(20)  NOT NULL,
    arrival_id       VARCHAR(60)  NOT NULL,
    file_count       INT          NOT NULL DEFAULT 0,
    total_bytes      BIGINT       NOT NULL DEFAULT 0,
    source_system    VARCHAR(100),
    status           VARCHAR(40)  NOT NULL DEFAULT 'recorded',
    provenance       JSONB        DEFAULT '{}',
    created_at       TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX idx_dae_dataset ON dataset_arrival_events (dataset_id, created_at DESC);
CREATE INDEX idx_dae_contract ON dataset_arrival_events (landing_contract_id);
CREATE UNIQUE INDEX idx_dae_arrival_unique ON dataset_arrival_events (landing_contract_id, ingest_date, arrival_id);

CREATE TABLE IF NOT EXISTS table_physical_names (
    id                    VARCHAR(26)  PRIMARY KEY,
    tenant_id             VARCHAR(26)  NOT NULL,
    table_contract_id     VARCHAR(26)  NOT NULL,
    catalog_kind          VARCHAR(60)  NOT NULL,
    schema_name           VARCHAR(200) NOT NULL,
    physical_table_name   VARCHAR(200) NOT NULL,
    layer                 VARCHAR(20)  NOT NULL,
    domain_slug           VARCHAR(200),
    pipeline_id           VARCHAR(26),
    version_id            VARCHAR(26),
    shared_group          VARCHAR(100),
    status                VARCHAR(40)  NOT NULL DEFAULT 'active',
    created_at            TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at            TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_tpn_unique_name ON table_physical_names (tenant_id, catalog_kind, schema_name, physical_table_name)
    WHERE status = 'active';
CREATE INDEX idx_tpn_contract ON table_physical_names (table_contract_id);
CREATE INDEX idx_tpn_pipeline ON table_physical_names (pipeline_id, version_id);
