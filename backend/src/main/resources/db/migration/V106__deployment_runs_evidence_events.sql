-- V104: Phase 4 of the deployment productization plan — preflight,
-- run, and evidence split. Replaces the metadata-only deploy contract
-- with explicit run state, idempotency, evidence records, and audit
-- events.
--
-- Three new tables, all child of deployments:
--   * deployment_runs       — one execution attempt for a deployment;
--                             carries idempotency key + body hash and
--                             a non-terminal starting state. Owns the
--                             state machine (PENDING / PREFLIGHT_FAILED
--                             / PREFLIGHT_PASSED / SUCCEEDED / FAILED
--                             / CANCEL_REQUESTED / CANCELLED / TIMED_OUT).
--   * deployment_evidence   — schema-versioned evidence artifacts
--                             produced by preflight, run, materialization,
--                             and adapter calls.
--   * deployment_events     — append-only audit log of state transitions
--                             and operator actions. Carries actor +
--                             correlation id + body hash for replay.
--
-- These tables are introduced now so Phase 4 can persist preflight
-- results and audit events; Phase 5+ will add adapter-driven
-- materialization rows that reuse the same envelopes.

CREATE TABLE deployment_runs (
    id                          VARCHAR(26) PRIMARY KEY,
    deployment_id               VARCHAR(26) NOT NULL REFERENCES deployments(id),
    tenant_id                   VARCHAR(26) NOT NULL,
    status                      VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    initiated_by                VARCHAR(26) NOT NULL,
    correlation_id              VARCHAR(64),
    -- Idempotency contract from the plan: same key + same request body
    -- hash returns the existing run; same key + different body hash
    -- returns 409 idempotency_body_mismatch.
    idempotency_key             VARCHAR(128),
    request_body_sha256         VARCHAR(64),
    started_at                  TIMESTAMPTZ,
    finished_at                 TIMESTAMPTZ,
    cancel_requested_at         TIMESTAMPTZ,
    timeout_at                  TIMESTAMPTZ,
    -- TEXT (not VARCHAR(N)) so a multi-blocker preflight reason like
    -- "preflight_failed: STORAGE_BACKEND_VALIDATED,CREDENTIAL_READINESS,
    -- TARGET_SCHEMA_VALID,GIT_BRANCH_ALLOWED,..." can be persisted
    -- without truncation.
    failure_reason              TEXT,
    metadata                    JSONB DEFAULT '{}',
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_deployment_run_status CHECK (status IN (
        'PENDING','PREFLIGHT_RUNNING','PREFLIGHT_FAILED','PREFLIGHT_PASSED',
        'MATERIALIZING','MATERIALIZED','SUBMITTING','RUNNING',
        'SUCCEEDED','FAILED','CANCEL_REQUESTED','CANCELLED','TIMED_OUT'))
);

-- Same idempotency_key used twice with the same body hash for the
-- same deployment must point at the same run row.
CREATE UNIQUE INDEX uq_deployment_runs_idem
    ON deployment_runs(deployment_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
CREATE INDEX idx_deployment_runs_deployment ON deployment_runs(deployment_id);
CREATE INDEX idx_deployment_runs_tenant ON deployment_runs(tenant_id);
CREATE INDEX idx_deployment_runs_status ON deployment_runs(status);

COMMENT ON COLUMN deployment_runs.status IS
    'Run state machine: PENDING (initial) -> PREFLIGHT_RUNNING -> '
    'PREFLIGHT_FAILED|PREFLIGHT_PASSED -> ... -> terminal SUCCEEDED|FAILED|'
    'CANCELLED|TIMED_OUT. Phase 4 lands at PENDING/PREFLIGHT_*; later '
    'phases drive deeper transitions.';

CREATE TABLE deployment_evidence (
    id                          VARCHAR(26) PRIMARY KEY,
    deployment_id               VARCHAR(26) REFERENCES deployments(id),
    deployment_run_id           VARCHAR(26) REFERENCES deployment_runs(id),
    package_id                  VARCHAR(26) REFERENCES packages(id),
    -- Stable evidence envelope:
    --   schemaVersion: deployment-evidence.v1
    --   type: PREFLIGHT_RESULT, RUN_STATUS, MATERIALIZATION_MANIFEST, ...
    --   path: filesystem-style path the evidence body would land at in
    --         a future package zip (Phase 5).
    schema_version              VARCHAR(64) NOT NULL DEFAULT 'deployment-evidence.v1',
    artifact_id                 VARCHAR(64) NOT NULL,
    type                        VARCHAR(64) NOT NULL,
    path                        VARCHAR(255),
    sha256                      VARCHAR(64) NOT NULL,
    produced_by                 VARCHAR(64) NOT NULL,
    correlation_id              VARCHAR(64),
    summary                     JSONB DEFAULT '{}',
    body                        JSONB DEFAULT '{}',
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_deployment_evidence_run ON deployment_evidence(deployment_run_id);
CREATE INDEX idx_deployment_evidence_deployment ON deployment_evidence(deployment_id);
CREATE INDEX idx_deployment_evidence_type ON deployment_evidence(type);

CREATE TABLE deployment_events (
    id                          VARCHAR(26) PRIMARY KEY,
    deployment_id               VARCHAR(26) REFERENCES deployments(id),
    deployment_run_id           VARCHAR(26) REFERENCES deployment_runs(id),
    schema_version              VARCHAR(64) NOT NULL DEFAULT 'deployment-event.v1',
    event_type                  VARCHAR(64) NOT NULL,
    from_status                 VARCHAR(32),
    to_status                   VARCHAR(32),
    actor_type                  VARCHAR(32) NOT NULL,
    actor_id                    VARCHAR(64) NOT NULL,
    surface                     VARCHAR(32),
    correlation_id              VARCHAR(64),
    request_body_sha256         VARCHAR(64),
    details                     JSONB DEFAULT '{}',
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_deployment_events_run ON deployment_events(deployment_run_id);
CREATE INDEX idx_deployment_events_deployment ON deployment_events(deployment_id);
CREATE INDEX idx_deployment_events_type ON deployment_events(event_type);
