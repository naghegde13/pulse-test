CREATE TABLE cobol_discovery_sessions (
    id          VARCHAR(26) PRIMARY KEY,
    tenant_id   VARCHAR(26) NOT NULL,
    user_id     VARCHAR(26) NOT NULL,
    title       VARCHAR(255) NOT NULL,
    status      VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_cobol_discovery_sessions_tenant
    ON cobol_discovery_sessions(tenant_id, updated_at DESC);

CREATE TABLE cobol_discovery_messages (
    id                VARCHAR(26) PRIMARY KEY,
    session_id        VARCHAR(26) NOT NULL REFERENCES cobol_discovery_sessions(id) ON DELETE CASCADE,
    role              VARCHAR(20) NOT NULL,
    content           TEXT NOT NULL,
    safe_payload_only BOOLEAN NOT NULL DEFAULT TRUE,
    expires_at        TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_cobol_discovery_messages_session
    ON cobol_discovery_messages(session_id, created_at ASC);

CREATE TABLE cobol_discovery_artifacts (
    id                VARCHAR(26) PRIMARY KEY,
    session_id        VARCHAR(26) NOT NULL REFERENCES cobol_discovery_sessions(id) ON DELETE CASCADE,
    tenant_id         VARCHAR(26) NOT NULL,
    artifact_type     VARCHAR(20) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    storage_uri       VARCHAR(1000) NOT NULL,
    sha256            VARCHAR(128) NOT NULL,
    size_bytes        BIGINT NOT NULL,
    content_type      VARCHAR(255),
    cleanup_status    VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    expires_at        TIMESTAMPTZ NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_cobol_discovery_artifacts_session
    ON cobol_discovery_artifacts(session_id, artifact_type);
CREATE INDEX idx_cobol_discovery_artifacts_expiry
    ON cobol_discovery_artifacts(expires_at);

CREATE TABLE cobol_discovery_runs (
    id                     VARCHAR(26) PRIMARY KEY,
    session_id             VARCHAR(26) NOT NULL REFERENCES cobol_discovery_sessions(id) ON DELETE CASCADE,
    tenant_id              VARCHAR(26) NOT NULL,
    run_type               VARCHAR(20) NOT NULL,
    status                 VARCHAR(32) NOT NULL DEFAULT 'QUEUED',
    config_snapshot        JSONB NOT NULL DEFAULT '{}'::jsonb,
    profiling_summary      JSONB NOT NULL DEFAULT '{}'::jsonb,
    confidence_score       DOUBLE PRECISION,
    anomaly_summary        JSONB NOT NULL DEFAULT '{}'::jsonb,
    sample_policy          JSONB NOT NULL DEFAULT '{}'::jsonb,
    result_schema_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    preview_rows           JSONB NOT NULL DEFAULT '[]'::jsonb,
    mapping_spec           JSONB NOT NULL DEFAULT '[]'::jsonb,
    event_log              JSONB NOT NULL DEFAULT '[]'::jsonb,
    cleanup_status         VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    expires_at             TIMESTAMPTZ,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_cobol_discovery_runs_session
    ON cobol_discovery_runs(session_id, created_at DESC);
CREATE INDEX idx_cobol_discovery_runs_tenant
    ON cobol_discovery_runs(tenant_id, created_at DESC);

CREATE TABLE cobol_parsing_profiles (
    id                      VARCHAR(26) PRIMARY KEY,
    tenant_id               VARCHAR(26) NOT NULL,
    name                    VARCHAR(255) NOT NULL,
    description             TEXT,
    copybook_content        TEXT NOT NULL,
    cobrix_options          JSONB NOT NULL DEFAULT '{}'::jsonb,
    flatten_spec            JSONB NOT NULL DEFAULT '{}'::jsonb,
    output_schema_snapshot  JSONB NOT NULL DEFAULT '{}'::jsonb,
    profile_quality_summary JSONB NOT NULL DEFAULT '{}'::jsonb,
    metadata                JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_by              VARCHAR(26),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uq_cobol_parsing_profiles_tenant_name
    ON cobol_parsing_profiles(tenant_id, name);
CREATE INDEX idx_cobol_parsing_profiles_tenant
    ON cobol_parsing_profiles(tenant_id, updated_at DESC);
