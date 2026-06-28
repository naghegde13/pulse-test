-- ARCH-008 phase 2: target-side runtime rows for exposed remote pipeline targets.

CREATE TABLE IF NOT EXISTS remote_pipeline_target_runtimes (
    id                          VARCHAR(26) PRIMARY KEY,
    target_ref                  VARCHAR(26) NOT NULL REFERENCES remote_pipeline_targets(id) ON DELETE CASCADE,
    environment                 VARCHAR(32) NOT NULL,
    peer_logical_dag_id         VARCHAR(255) NOT NULL,
    payload_schema              JSONB DEFAULT '{}',
    allowed_payload_keys        JSONB DEFAULT '[]',
    completion_event            VARCHAR(255),
    deployment_status           VARCHAR(64) NOT NULL DEFAULT 'UNKNOWN',
    metadata                    JSONB DEFAULT '{}',
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_remote_target_runtime_env CHECK (environment IN ('dev', 'integration', 'uat', 'prod'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_remote_pipeline_target_runtime_env
    ON remote_pipeline_target_runtimes(target_ref, environment);
