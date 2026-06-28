-- ARCH-008 corrected: design/deploy-time remote Airflow target authority.
-- Pulse does not run in higher/runtime environments; these rows validate
-- non-secret Airflow target metadata and secret references before packaging.

CREATE TABLE IF NOT EXISTS remote_airflow_trust_bindings (
    id                          VARCHAR(26) PRIMARY KEY,
    local_tenant_id             VARCHAR(26) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    environment                 VARCHAR(32) NOT NULL,
    invoker_persona             VARCHAR(32) NOT NULL,
    target_owner_persona        VARCHAR(32) NOT NULL,
    federated_tenant_key        VARCHAR(255) NOT NULL,
    airflow_base_url            VARCHAR(1024) NOT NULL,
    issuer                      VARCHAR(255) NOT NULL,
    audience                    VARCHAR(255) NOT NULL,
    jwks_uri                    VARCHAR(1024),
    inbound_shared_secret_ref   VARCHAR(1024),
    outbound_secret_ref         VARCHAR(1024),
    status                      VARCHAR(32) NOT NULL DEFAULT 'UNVALIDATED',
    capability_snapshot         JSONB DEFAULT '{}',
    validated_at                TIMESTAMPTZ,
    validation_error            TEXT,
    metadata                    JSONB DEFAULT '{}',
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_peer_trust_status CHECK (status IN ('UNVALIDATED', 'VALIDATED', 'DISABLED', 'ERROR')),
    CONSTRAINT chk_peer_trust_environment CHECK (environment IN ('dev', 'integration', 'uat', 'prod'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_airflow_trust_local_env_target
    ON remote_airflow_trust_bindings(local_tenant_id, environment, target_owner_persona);

CREATE UNIQUE INDEX IF NOT EXISTS uq_airflow_trust_federated_env_personas
    ON remote_airflow_trust_bindings(federated_tenant_key, environment, invoker_persona, target_owner_persona);

CREATE INDEX IF NOT EXISTS idx_airflow_trust_local_status
    ON remote_airflow_trust_bindings(local_tenant_id, status);
