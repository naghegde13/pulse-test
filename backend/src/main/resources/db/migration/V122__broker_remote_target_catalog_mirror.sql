-- ARCH-008 phase 1: invoker-side remote target catalog mirror.

CREATE TABLE IF NOT EXISTS remote_target_catalog_mirror (
    id                          VARCHAR(26) PRIMARY KEY,
    trust_binding_id            VARCHAR(26) NOT NULL REFERENCES remote_airflow_trust_bindings(id) ON DELETE CASCADE,
    local_tenant_id             VARCHAR(26) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    federated_tenant_key        VARCHAR(255) NOT NULL,
    remote_target_ref           VARCHAR(255) NOT NULL,
    slug                        VARCHAR(255) NOT NULL,
    display_name                VARCHAR(255) NOT NULL,
    description                 TEXT,
    status                      VARCHAR(32) NOT NULL,
    last_synced_at              TIMESTAMPTZ NOT NULL,
    signed_response_jws_sha256  VARCHAR(64),
    payload                     JSONB DEFAULT '{}',
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_remote_target_catalog_status CHECK (status IN ('ACTIVE', 'DISABLED', 'DEPRECATED'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_remote_target_catalog_ref
    ON remote_target_catalog_mirror(federated_tenant_key, remote_target_ref);

CREATE INDEX IF NOT EXISTS idx_remote_target_catalog_status
    ON remote_target_catalog_mirror(federated_tenant_key, status);
