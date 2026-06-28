-- =============================================================
-- V115: Pipeline Slug for Orchestration Namespace
-- ARCH-007 — Stable slug for DAG ID / namespace resolution
-- =============================================================

ALTER TABLE pipelines ADD COLUMN IF NOT EXISTS pipeline_slug VARCHAR(100);

CREATE INDEX idx_pipelines_slug ON pipelines (tenant_id, pipeline_slug);
