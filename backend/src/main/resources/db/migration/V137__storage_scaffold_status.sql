-- PKT-0012: Storage scaffold status tracking
-- Tracks preview/execution state per (tenant, domain) tuple.

CREATE TABLE storage_scaffold_status (
    id                    VARCHAR(26)   PRIMARY KEY,
    tenant_id             VARCHAR(255)  NOT NULL,
    domain_slug           VARCHAR(255)  NOT NULL,
    status                VARCHAR(50)   NOT NULL DEFAULT 'previewed'
                          CHECK (status IN ('previewed', 'executed', 'operator_blocked')),
    gcp_project_id        VARCHAR(255),
    service_account_email VARCHAR(255),
    credential_source     VARCHAR(100),
    entry_count           INTEGER       NOT NULL DEFAULT 0,
    last_previewed_at     TIMESTAMP WITH TIME ZONE,
    last_executed_at      TIMESTAMP WITH TIME ZONE,
    execution_error       TEXT,
    manifest_snapshot     TEXT,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_scaffold_tenant_domain UNIQUE (tenant_id, domain_slug)
);

CREATE INDEX idx_scaffold_status_tenant ON storage_scaffold_status (tenant_id);
