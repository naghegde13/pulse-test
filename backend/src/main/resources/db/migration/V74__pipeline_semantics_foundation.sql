-- Phase 1 semantics foundation:
-- 1. richer blueprint metadata
-- 2. canonical pipeline domain identity
-- 3. domain-scoped git repo attachment with legacy pipeline shim support

ALTER TABLE blueprints ADD COLUMN IF NOT EXISTS subcategory VARCHAR(100);
ALTER TABLE blueprints ADD COLUMN IF NOT EXISTS artifact_types JSONB NOT NULL DEFAULT '[]';
ALTER TABLE blueprints ADD COLUMN IF NOT EXISTS compute_backend VARCHAR(50);
ALTER TABLE blueprints ADD COLUMN IF NOT EXISTS composition_role VARCHAR(50);
ALTER TABLE blueprints ADD COLUMN IF NOT EXISTS valid_layers JSONB NOT NULL DEFAULT '[]';
ALTER TABLE blueprints ADD COLUMN IF NOT EXISTS usage_guidance JSONB NOT NULL DEFAULT '{}';
ALTER TABLE blueprints ADD COLUMN IF NOT EXISTS required_params_schema JSONB NOT NULL DEFAULT '{}';
ALTER TABLE blueprints ADD COLUMN IF NOT EXISTS optional_params_schema JSONB NOT NULL DEFAULT '{}';
ALTER TABLE blueprints ADD COLUMN IF NOT EXISTS ui_schema JSONB NOT NULL DEFAULT '{}';
ALTER TABLE blueprints ADD COLUMN IF NOT EXISTS emit_strategy VARCHAR(50);
ALTER TABLE blueprints ADD COLUMN IF NOT EXISTS supports_reuse BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE blueprints ADD COLUMN IF NOT EXISTS schema_behavior JSONB NOT NULL DEFAULT '{}';
ALTER TABLE blueprints ADD COLUMN IF NOT EXISTS codegen_hints JSONB NOT NULL DEFAULT '{}';
ALTER TABLE blueprints ADD COLUMN IF NOT EXISTS status VARCHAR(30) NOT NULL DEFAULT 'active';
ALTER TABLE blueprints ADD COLUMN IF NOT EXISTS replacement_blueprint_key VARCHAR(100);

UPDATE blueprints
SET subcategory = CASE blueprint_key
        WHEN 'FileIngestion' THEN 'file_ingest'
        WHEN 'BronzeToSilverCleaning' THEN 'cleaning'
        WHEN 'SchemaNormalization' THEN 'conformance'
        WHEN 'DQValidator' THEN 'validation'
        WHEN 'QuarantineBadRecords' THEN 'quarantine'
        WHEN 'WideDenormalizedMart' THEN 'serving_mart'
        WHEN 'SCD2Dimension' THEN 'history'
        ELSE subcategory
    END,
    artifact_types = CASE blueprint_key
        WHEN 'FileIngestion' THEN '["spark_job"]'::jsonb
        WHEN 'BronzeToSilverCleaning' THEN '["dbt_model"]'::jsonb
        WHEN 'SchemaNormalization' THEN '["dbt_model"]'::jsonb
        WHEN 'DQValidator' THEN '["dbt_test","gx_checkpoint"]'::jsonb
        WHEN 'QuarantineBadRecords' THEN '["gx_checkpoint","quarantine_manifest"]'::jsonb
        WHEN 'WideDenormalizedMart' THEN '["dbt_model","publish_manifest"]'::jsonb
        WHEN 'SCD2Dimension' THEN '["dbt_snapshot","publish_manifest"]'::jsonb
        ELSE artifact_types
    END,
    compute_backend = CASE blueprint_key
        WHEN 'FileIngestion' THEN 'spark'
        WHEN 'WideDenormalizedMart' THEN 'bigquery'
        WHEN 'SCD2Dimension' THEN 'bigquery'
        ELSE COALESCE(compute_backend, 'spark')
    END,
    composition_role = CASE blueprint_key
        WHEN 'FileIngestion' THEN 'ingestion'
        WHEN 'DQValidator' THEN 'data_quality'
        WHEN 'QuarantineBadRecords' THEN 'data_quality'
        WHEN 'WideDenormalizedMart' THEN 'modeling'
        WHEN 'SCD2Dimension' THEN 'modeling'
        ELSE COALESCE(composition_role, lower(category))
    END,
    valid_layers = CASE blueprint_key
        WHEN 'FileIngestion' THEN '["bronze"]'::jsonb
        WHEN 'BronzeToSilverCleaning' THEN '["silver"]'::jsonb
        WHEN 'SchemaNormalization' THEN '["silver"]'::jsonb
        WHEN 'DQValidator' THEN '["silver"]'::jsonb
        WHEN 'QuarantineBadRecords' THEN '["silver"]'::jsonb
        WHEN 'WideDenormalizedMart' THEN '["gold"]'::jsonb
        WHEN 'SCD2Dimension' THEN '["gold"]'::jsonb
        ELSE valid_layers
    END,
    emit_strategy = CASE blueprint_key
        WHEN 'FileIngestion' THEN 'generate'
        WHEN 'WideDenormalizedMart' THEN 'generate'
        WHEN 'SCD2Dimension' THEN 'generate'
        ELSE COALESCE(emit_strategy, 'generate')
    END,
    supports_reuse = CASE blueprint_key
        WHEN 'WideDenormalizedMart' THEN TRUE
        WHEN 'SCD2Dimension' THEN TRUE
        ELSE supports_reuse
    END,
    usage_guidance = CASE blueprint_key
        WHEN 'FileIngestion' THEN jsonb_build_object('phase1_supported', true, 'hr_vertical_proof', true)
        WHEN 'BronzeToSilverCleaning' THEN jsonb_build_object('phase1_supported', true, 'hr_vertical_proof', true)
        WHEN 'SchemaNormalization' THEN jsonb_build_object('phase1_supported', true, 'hr_vertical_proof', true)
        WHEN 'DQValidator' THEN jsonb_build_object('phase1_supported', true, 'hr_vertical_proof', true)
        WHEN 'QuarantineBadRecords' THEN jsonb_build_object('phase1_supported', true, 'hr_vertical_proof', true)
        WHEN 'WideDenormalizedMart' THEN jsonb_build_object('phase1_supported', true, 'hr_vertical_proof', true, 'gold_publish_boundary', 'bigquery_input_publish')
        WHEN 'SCD2Dimension' THEN jsonb_build_object('phase1_supported', true, 'hr_vertical_proof', true, 'gold_publish_boundary', 'bigquery_input_publish')
        ELSE usage_guidance
    END,
    ui_schema = CASE blueprint_key
        WHEN 'DQValidator' THEN jsonb_build_object('editor', 'dq_policy', 'supports_thresholds', true)
        WHEN 'QuarantineBadRecords' THEN jsonb_build_object('editor', 'quarantine_policy', 'supports_thresholds', true)
        ELSE ui_schema
    END,
    schema_behavior = CASE blueprint_key
        WHEN 'SCD2Dimension' THEN jsonb_build_object('effect_type', 'history_emitter', 'conflict_policy', 'block')
        WHEN 'WideDenormalizedMart' THEN jsonb_build_object('effect_type', 'gold_publish', 'conflict_policy', 'warn')
        ELSE schema_behavior
    END,
    codegen_hints = CASE blueprint_key
        WHEN 'WideDenormalizedMart' THEN jsonb_build_object('gold_target', 'bigquery', 'publish_boundary', 'bigquery_input_publish')
        WHEN 'SCD2Dimension' THEN jsonb_build_object('gold_target', 'bigquery', 'publish_boundary', 'bigquery_input_publish', 'command', 'dbt_snapshot')
        ELSE codegen_hints
    END
WHERE blueprint_key IN (
    'FileIngestion',
    'BronzeToSilverCleaning',
    'SchemaNormalization',
    'DQValidator',
    'QuarantineBadRecords',
    'WideDenormalizedMart',
    'SCD2Dimension'
);

ALTER TABLE pipelines ADD COLUMN IF NOT EXISTS domain_id VARCHAR(26);
UPDATE pipelines p
SET domain_id = d.id
FROM domains d
WHERE p.domain_id IS NULL
  AND d.tenant_id = p.tenant_id
  AND d.name = p.domain_name;

CREATE INDEX IF NOT EXISTS idx_pipelines_domain_id ON pipelines(domain_id);

ALTER TABLE git_repos ADD COLUMN IF NOT EXISTS domain_id VARCHAR(26);
ALTER TABLE git_repos ALTER COLUMN pipeline_id DROP NOT NULL;

UPDATE git_repos gr
SET domain_id = p.domain_id
FROM pipelines p
WHERE gr.pipeline_id = p.id
  AND gr.domain_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_git_repos_domain ON git_repos(domain_id);
