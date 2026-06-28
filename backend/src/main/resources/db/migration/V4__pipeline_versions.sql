-- V4: Split pipeline into container + versioned entity.
-- Pipeline becomes a lightweight container; all lifecycle/config moves to pipeline_versions.

-- 1. Create pipeline_versions table
CREATE TABLE pipeline_versions (
    id              VARCHAR(26) PRIMARY KEY,
    pipeline_id     VARCHAR(26) NOT NULL REFERENCES pipelines(id) ON DELETE CASCADE,
    version         VARCHAR(20) NOT NULL,
    lifecycle_stage VARCHAR(50) NOT NULL DEFAULT 'DRAFT',
    created_by      VARCHAR(26) NOT NULL,
    sla_config      JSONB DEFAULT '{}',
    metadata        JSONB DEFAULT '{}',
    change_summary  TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (pipeline_id, version)
);

CREATE INDEX idx_pv_pipeline ON pipeline_versions(pipeline_id);
CREATE INDEX idx_pv_stage ON pipeline_versions(lifecycle_stage);

-- 2. Migrate existing pipelines into pipeline_versions
INSERT INTO pipeline_versions (id, pipeline_id, version, lifecycle_stage, created_by, sla_config, metadata, created_at, updated_at)
SELECT
    SUBSTRING(md5(id || '-v') FROM 1 FOR 26),
    id,
    COALESCE(current_version, '0.1.0'),
    lifecycle_stage,
    created_by,
    COALESCE(sla_config, '{}'),
    COALESCE(metadata, '{}'),
    created_at,
    updated_at
FROM pipelines;

-- 3. Add active_version_id to pipelines, backfill from migrated versions
ALTER TABLE pipelines ADD COLUMN active_version_id VARCHAR(26);

UPDATE pipelines SET active_version_id = (
    SELECT pv.id FROM pipeline_versions pv
    WHERE pv.pipeline_id = pipelines.id
    ORDER BY pv.created_at DESC LIMIT 1
);

-- 4. Point sub_pipeline_instances to version instead of pipeline
ALTER TABLE sub_pipeline_instances ADD COLUMN version_id VARCHAR(26);
UPDATE sub_pipeline_instances SET version_id = (
    SELECT pv.id FROM pipeline_versions pv
    WHERE pv.pipeline_id = sub_pipeline_instances.pipeline_id
    ORDER BY pv.created_at DESC LIMIT 1
);

-- 5. Drop columns that moved to pipeline_versions
ALTER TABLE pipelines DROP COLUMN IF EXISTS lifecycle_stage;
ALTER TABLE pipelines DROP COLUMN IF EXISTS current_version;
ALTER TABLE pipelines DROP COLUMN IF EXISTS sla_config;
ALTER TABLE pipelines DROP COLUMN IF EXISTS metadata;
