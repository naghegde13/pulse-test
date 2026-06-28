-- V86: Pipeline state table for runtime watermarks, high-water marks, backfill progress.
--
-- Owned by Agent A (redesign-plan-v2, WP2). Generated PySpark code reads/writes state
-- via the REST endpoints on PipelineStateController; no other module writes to this
-- table in Phase 1.

CREATE TABLE IF NOT EXISTS pipeline_state (
    id          VARCHAR(26) PRIMARY KEY,
    pipeline_id VARCHAR(26) NOT NULL REFERENCES pipelines(id) ON DELETE CASCADE,
    instance_id VARCHAR(26) NOT NULL REFERENCES sub_pipeline_instances(id) ON DELETE CASCADE,
    state_key   VARCHAR(255) NOT NULL,
    state_value JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_pipeline_state_key UNIQUE (pipeline_id, instance_id, state_key)
);

CREATE INDEX IF NOT EXISTS idx_pipeline_state_lookup
    ON pipeline_state(pipeline_id, instance_id);
