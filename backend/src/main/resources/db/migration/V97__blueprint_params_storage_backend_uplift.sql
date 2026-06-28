-- V97: Add storage_backend / lake_layer / lake_format to blueprint
-- params_schemas (#30 P3).
--
-- ADD-ONLY migration. Drops of now-redundant bucket/path fields wait
-- until V99 — after P4 (codegen substitution) and P5 (example rewrites)
-- prove the new fields are sufficient. This phased approach keeps each
-- migration small and reversible-in-spirit.
--
-- Categorization (per #30 spec section 7):
--
--   storage_backend ONLY (read tables, no table production):
--     DATA_QUALITY:  AnomalyDetection, DQValidator, FreshnessChecks,
--                    SchemaDriftDetection
--     ORCHESTRATION: AdvanceTimeDimension, BackfillAndReplay,
--                    CostMonitoringHook, DatabaseReadinessSensor,
--                    DatasetDependencySensor, ExternalEventSensor,
--                    FileArrivalSensor, ObjectStoreKeySensor,
--                    RollbackOnFailure, ScheduleAndTriggers
--
--   storage_backend + lake_layer + lake_format (produce a table):
--     INGESTION  → default lake_layer=bronze
--     TRANSFORM  → default lake_layer=silver
--     MODELING   → default lake_layer=gold
--                  + GCP gold MUST be bq_native (DB-enforced via V96
--                    constraint on sub_pipeline_instances).
--
-- DEFAULT lake_format='delta' (most universal). The configure-transform
-- wizard will require an explicit choice on new instances; this default
-- is only a fallback when the agent doesn't set one.
--
-- Sinks (DatabaseWriter / LakeWriter / StreamWriter / WarehouseWriter)
-- already have target_id + connector_instance_id from V95 and DERIVE
-- their storage_backend from the chosen connector_instance — they're
-- not touched by this migration.

-- ---------------------------------------------------------------------
-- 1. storage_backend field — added to ALL affected blueprints below.
-- ---------------------------------------------------------------------
UPDATE blueprints
SET params_schema = params_schema || '[
    {
        "name": "storage_backend",
        "type": "enum",
        "options": ["DPC", "GCP"],
        "required": true,
        "description": "Pipeline-working-storage backend. Resolved at codegen and deploy time via storage_backends (tenant_id, environment, backend) lookup. Default inherits from pipeline.default_storage_backend when set."
    }
]'::jsonb,
updated_at = NOW()
WHERE status = 'active'
  AND category IN ('DATA_QUALITY','ORCHESTRATION','INGESTION','TRANSFORM','MODELING')
  -- Don't double-add if a future re-run is attempted.
  AND NOT (params_schema::text LIKE '%"storage_backend"%');

-- ---------------------------------------------------------------------
-- 2. lake_layer + lake_format — added to TABLE-PRODUCING blueprints only.
-- ---------------------------------------------------------------------

-- INGESTION → default bronze.
UPDATE blueprints
SET params_schema = params_schema || '[
    {
        "name": "lake_layer",
        "type": "enum",
        "options": ["bronze", "silver", "gold"],
        "default": "bronze",
        "required": true,
        "description": "Medallion layer of the table this blueprint produces. Ingestion blueprints typically land at bronze; agent should not change without explicit user direction."
    },
    {
        "name": "lake_format",
        "type": "enum",
        "options": ["delta", "iceberg_external", "iceberg_bq_managed", "bq_native", "parquet"],
        "default": "delta",
        "required": true,
        "description": "Physical table format. Legal options depend on (storage_backend, lake_layer): DPC supports delta|iceberg_external|parquet; GCP non-gold supports delta|iceberg_external|iceberg_bq_managed; GCP gold MUST be bq_native (locked rule, DB-enforced)."
    }
]'::jsonb,
updated_at = NOW()
WHERE status = 'active'
  AND category = 'INGESTION'
  AND NOT (params_schema::text LIKE '%"lake_layer"%');

-- TRANSFORM → default silver.
UPDATE blueprints
SET params_schema = params_schema || '[
    {
        "name": "lake_layer",
        "type": "enum",
        "options": ["bronze", "silver", "gold"],
        "default": "silver",
        "required": true,
        "description": "Medallion layer of the table this blueprint produces. Transform blueprints typically land at silver."
    },
    {
        "name": "lake_format",
        "type": "enum",
        "options": ["delta", "iceberg_external", "iceberg_bq_managed", "bq_native", "parquet"],
        "default": "delta",
        "required": true,
        "description": "Physical table format. See storage compatibility matrix; GCP gold MUST be bq_native."
    }
]'::jsonb,
updated_at = NOW()
WHERE status = 'active'
  AND category = 'TRANSFORM'
  AND NOT (params_schema::text LIKE '%"lake_layer"%');

-- MODELING → default gold.
UPDATE blueprints
SET params_schema = params_schema || '[
    {
        "name": "lake_layer",
        "type": "enum",
        "options": ["bronze", "silver", "gold"],
        "default": "gold",
        "required": true,
        "description": "Medallion layer of the table this blueprint produces. Modeling blueprints typically land at gold."
    },
    {
        "name": "lake_format",
        "type": "enum",
        "options": ["delta", "iceberg_external", "iceberg_bq_managed", "bq_native", "parquet"],
        "default": "delta",
        "required": true,
        "description": "Physical table format. GCP gold MUST be bq_native (locked rule, DB-enforced); DPC gold supports delta|iceberg_external|parquet."
    }
]'::jsonb,
updated_at = NOW()
WHERE status = 'active'
  AND category = 'MODELING'
  AND NOT (params_schema::text LIKE '%"lake_layer"%');
