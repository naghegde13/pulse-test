-- ARCH-008 phase 1: invoker-side environment-specific remote target runtime mirror.

CREATE TABLE IF NOT EXISTS remote_target_runtime_mirror (
    id                          VARCHAR(26) PRIMARY KEY,
    trust_binding_id            VARCHAR(26) NOT NULL REFERENCES remote_airflow_trust_bindings(id) ON DELETE CASCADE,
    local_tenant_id             VARCHAR(26) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    federated_tenant_key        VARCHAR(255) NOT NULL,
    remote_target_ref           VARCHAR(255) NOT NULL,
    environment                 VARCHAR(32) NOT NULL,
    peer_logical_dag_id         VARCHAR(255) NOT NULL,
    payload_schema              JSONB DEFAULT '{}',
    allowed_payload_keys        JSONB DEFAULT '[]',
    completion_event            VARCHAR(255),
    deployment_status           VARCHAR(64),
    last_synced_at              TIMESTAMPTZ NOT NULL,
    signed_response_jws_sha256  VARCHAR(64),
    payload                     JSONB DEFAULT '{}',
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_remote_runtime_environment CHECK (environment IN ('dev', 'integration', 'uat', 'prod'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_remote_target_runtime_ref_env
    ON remote_target_runtime_mirror(federated_tenant_key, remote_target_ref, environment);

CREATE INDEX IF NOT EXISTS idx_remote_target_runtime_lookup
    ON remote_target_runtime_mirror(local_tenant_id, environment, remote_target_ref);
