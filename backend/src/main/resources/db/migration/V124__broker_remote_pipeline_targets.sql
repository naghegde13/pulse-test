-- ARCH-008 phase 2: target-side remote pipeline target catalog.

CREATE TABLE IF NOT EXISTS remote_pipeline_targets (
    id                          VARCHAR(26) PRIMARY KEY,
    local_tenant_id             VARCHAR(26) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    pipeline_id                 VARCHAR(26) NOT NULL REFERENCES pipelines(id) ON DELETE CASCADE,
    slug                        VARCHAR(255) NOT NULL,
    display_name                VARCHAR(255) NOT NULL,
    description                 TEXT,
    status                      VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_remote_pipeline_target_status CHECK (status IN ('ACTIVE', 'DISABLED', 'DEPRECATED'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_remote_pipeline_target_pipeline
    ON remote_pipeline_targets(local_tenant_id, pipeline_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_remote_pipeline_target_slug
    ON remote_pipeline_targets(local_tenant_id, slug);
