-- V81: Blueprint catalog rationalization (Phase 0 of redesign-v2).
--
-- Completes the metadata columns introduced by V74/V75 for every active
-- blueprint, normalizes the artifact_types vocabulary, deprecates blueprints
-- that are absorbed or superseded, and aligns remaining rows with the locked
-- architectural decisions:
--   * All compute backends are 'spark' (no 'bigquery').
--   * Data quality is Great Expectations only (no dbt tests).
--   * Sensors are control-plane constructs, not layer-bearing steps.
--
-- All statements are idempotent / no-op safe. QuarantineBadRecords was removed
-- by V9 and is already absent; the UPDATE that deprecates it is kept for
-- defensive purposes.

-- =============================================================================
-- 7a. Deprecate blueprints that are absorbed, superseded, or out of scope.
-- =============================================================================

UPDATE blueprints SET status = 'deprecated', deferred = true, replacement_blueprint_key = 'JsonFlatten'
    WHERE blueprint_key = 'FlattenNestedStructures';
UPDATE blueprints SET status = 'deprecated', deferred = true, replacement_blueprint_key = 'GenericJoin'
    WHERE blueprint_key = 'EnrichmentJoin';
UPDATE blueprints SET status = 'deprecated', deferred = true, replacement_blueprint_key = 'SchemaNormalization'
    WHERE blueprint_key = 'ConformanceToEnterpriseModel';
UPDATE blueprints SET status = 'deprecated', deferred = true, replacement_blueprint_key = 'GenericAggregate'
    WHERE blueprint_key = 'DerivedMetricsComputation';
UPDATE blueprints SET status = 'deprecated', deferred = true, replacement_blueprint_key = NULL
    WHERE blueprint_key = 'DataVaultHubLinkSat';
UPDATE blueprints SET status = 'deprecated', deferred = true, replacement_blueprint_key = 'FactBuild'
    WHERE blueprint_key = 'TimeSeriesOptimization';
UPDATE blueprints SET status = 'deprecated', deferred = true, replacement_blueprint_key = 'DQValidator'
    WHERE blueprint_key = 'GXExpectationSuite';
UPDATE blueprints SET status = 'deprecated', deferred = true, replacement_blueprint_key = NULL
    WHERE blueprint_key = 'ReferentialIntegrityCheck';
UPDATE blueprints SET status = 'deprecated', deferred = true, replacement_blueprint_key = NULL
    WHERE blueprint_key = 'DQScorecardPublish';
UPDATE blueprints SET status = 'deprecated', deferred = true, replacement_blueprint_key = 'DQValidator'
    WHERE blueprint_key = 'Reconciliation';
UPDATE blueprints SET status = 'deprecated', deferred = true, replacement_blueprint_key = 'DQValidator'
    WHERE blueprint_key = 'QuarantineBadRecords';

-- =============================================================================
-- 7d. CDCIngestion: dual-mode parameter schema (set BEFORE §7e appends).
-- =============================================================================

UPDATE blueprints SET params_schema = '[
  {"name":"source_type","type":"enum","required":true,"options":["postgres","mysql","oracle","sqlserver"]},
  {"name":"tables","type":"string[]","required":true},
  {"name":"primary_key","type":"string[]","required":true},
  {"name":"cdc_mode","type":"enum","required":false,"options":["debezium","incremental_poll"],"default":"debezium"},
  {"name":"incremental_column","type":"string","required":false,"description":"Required when cdc_mode=incremental_poll"},
  {"name":"watermark_column","type":"string","required":false},
  {"name":"initial_snapshot","type":"boolean","required":false,"default":true},
  {"name":"delete_handling","type":"enum","required":false,"options":["soft_delete","hard_delete","ignore"],"default":"soft_delete"},
  {"name":"ordering_field","type":"string","required":false},
  {"name":"partition_by","type":"string[]","required":false}
]'::jsonb WHERE blueprint_key = 'CDCIngestion';

-- =============================================================================
-- Appendix A. Base params_schema for every remaining active blueprint.
-- (Applied before §7e appends so the appended fields are additive.)
-- =============================================================================

-- INGESTION --------------------------------------------------------------------
UPDATE blueprints SET params_schema = '[
  {"name":"api_url","type":"string","required":false,"description":"Inherited from connector"},
  {"name":"auth_type","type":"enum","required":false,"options":["oauth2","api_key","basic"],"description":"Inherited from connector"},
  {"name":"rate_limit_rpm","type":"integer","required":false,"default":60},
  {"name":"pagination_type","type":"string","required":false},
  {"name":"incremental_field","type":"string","required":false},
  {"name":"response_json_path","type":"string","required":false}
]'::jsonb WHERE blueprint_key = 'ApiIngestion';

UPDATE blueprints SET params_schema = '[
  {"name":"stream_type","type":"enum","required":false,"options":["kafka","kinesis","eventhub","pubsub"],"description":"Inherited from connector"},
  {"name":"topic","type":"string","required":false,"description":"Inherited from connector"},
  {"name":"consumer_group","type":"string","required":false,"description":"Inherited from connector"},
  {"name":"batch_window_seconds","type":"integer","required":false,"default":60},
  {"name":"deserialization_format","type":"enum","required":false,"options":["json","avro","protobuf"],"default":"json"},
  {"name":"starting_offsets","type":"string","required":false}
]'::jsonb WHERE blueprint_key = 'StreamIngestion';

UPDATE blueprints SET params_schema = '[
  {"name":"source_table","type":"string","required":false,"description":"Inherited from connector"},
  {"name":"snapshot_frequency","type":"enum","required":false,"options":["daily","weekly","monthly"],"default":"daily"},
  {"name":"compare_key","type":"string","required":false}
]'::jsonb WHERE blueprint_key = 'SnapshotIngestion';

UPDATE blueprints SET params_schema = '[
  {"name":"source_query","type":"string","required":true},
  {"name":"date_range_start","type":"string","required":true,"description":"Date YYYY-MM-DD"},
  {"name":"date_range_end","type":"string","required":true,"description":"Date YYYY-MM-DD"},
  {"name":"chunk_size","type":"integer","required":false,"default":100000},
  {"name":"parallelism","type":"integer","required":false,"default":4}
]'::jsonb WHERE blueprint_key = 'BulkBackfill';

-- TRANSFORM --------------------------------------------------------------------
UPDATE blueprints SET params_schema = '[
  {"name":"filter_mode","type":"enum","required":false,"options":["visual","sql"],"default":"visual"},
  {"name":"conditions","type":"object[]","required":false,"description":"Visual filter conditions: [{column, operator, value}]"},
  {"name":"raw_sql","type":"string","required":false,"description":"Raw SQL WHERE clause when filter_mode=sql","default":"1 = 1"}
]'::jsonb WHERE blueprint_key = 'GenericFilter';

UPDATE blueprints SET params_schema = '[
  {"name":"group_by_columns","type":"string[]","required":false},
  {"name":"aggregations","type":"object[]","required":false,"description":"[{column, function, alias}] where function is COUNT/SUM/AVG/MAX/MIN"},
  {"name":"having_clause","type":"string","required":false}
]'::jsonb WHERE blueprint_key = 'GenericAggregate';

UPDATE blueprints SET params_schema = '[
  {"name":"join_type","type":"enum","required":true,"options":["inner","left","right","full_outer","cross"],"default":"left"},
  {"name":"join_keys","type":"object[]","required":true,"description":"[{left_column, right_column}]"},
  {"name":"select_columns","type":"string[]","required":false},
  {"name":"alias_left","type":"string","required":false},
  {"name":"alias_right","type":"string","required":false}
]'::jsonb WHERE blueprint_key = 'GenericJoin';

UPDATE blueprints SET params_schema = '[
  {"name":"routes","type":"object[]","required":false,"description":"[{name, condition, description}]"},
  {"name":"include_default","type":"boolean","required":false,"default":true}
]'::jsonb WHERE blueprint_key = 'GenericRouter';

UPDATE blueprints SET params_schema = '[
  {"name":"source_columns","type":"string[]","required":false,"default":["*"]},
  {"name":"separator","type":"string","required":false,"default":"_"},
  {"name":"max_depth","type":"integer","required":false,"default":3},
  {"name":"explode_arrays","type":"boolean","required":false,"default":false},
  {"name":"keep_original","type":"boolean","required":false,"default":false},
  {"name":"prefix","type":"string","required":false,"default":""}
]'::jsonb WHERE blueprint_key = 'JsonFlatten';

UPDATE blueprints SET params_schema = '[
  {"name":"output_format","type":"enum","required":false,"options":["struct","json_string"],"default":"struct"},
  {"name":"mappings","type":"object[]","required":false,"description":"[{struct_name, fields: [{source_column, as}]}]"},
  {"name":"drop_source_columns","type":"boolean","required":false,"default":false},
  {"name":"passthrough_columns","type":"string[]","required":false}
]'::jsonb WHERE blueprint_key = 'JsonStruct';

UPDATE blueprints SET params_schema = '[
  {"name":"columns_to_mask","type":"string[]","required":true,"description":"Columns containing PII"},
  {"name":"masking_strategy","type":"enum","required":true,"options":["hash","redact","tokenize","encrypt"],"default":"hash"},
  {"name":"preserve_format","type":"boolean","required":false,"default":false},
  {"name":"hash_algorithm","type":"enum","required":false,"options":["sha256","sha512","md5"],"default":"sha256"}
]'::jsonb WHERE blueprint_key = 'PIIMasking';

UPDATE blueprints SET params_schema = '[
  {"name":"match_keys","type":"string[]","required":true},
  {"name":"match_strategy","type":"enum","required":false,"options":["exact","fuzzy","composite"],"default":"exact"},
  {"name":"merge_priority","type":"string","required":false}
]'::jsonb WHERE blueprint_key = 'DedupeAndMerge';

-- MODELING ---------------------------------------------------------------------
UPDATE blueprints SET params_schema = '[
  {"name":"grain","type":"string","required":true,"description":"What one row represents"},
  {"name":"measures","type":"string[]","required":true},
  {"name":"dimension_keys","type":"string[]","required":true},
  {"name":"incremental","type":"boolean","required":false,"default":true},
  {"name":"time_column","type":"string","required":false}
]'::jsonb WHERE blueprint_key = 'FactBuild';

UPDATE blueprints SET params_schema = '[
  {"name":"merge_key","type":"string[]","required":true},
  {"name":"merge_strategy","type":"enum","required":false,"options":["upsert","delete_insert","append"],"default":"upsert"},
  {"name":"soft_delete","type":"boolean","required":false,"default":false},
  {"name":"late_data_policy","type":"string","required":false},
  {"name":"late_threshold_hours","type":"integer","required":false}
]'::jsonb WHERE blueprint_key = 'IncrementalMerge';

UPDATE blueprints SET params_schema = '[
  {"name":"snapshot_frequency","type":"enum","required":false,"options":["daily","weekly","monthly"],"default":"daily"},
  {"name":"retention_days","type":"integer","required":false,"default":365},
  {"name":"unique_key","type":"string[]","required":false},
  {"name":"strategy","type":"enum","required":false,"options":["timestamp","check"],"default":"timestamp"}
]'::jsonb WHERE blueprint_key = 'SnapshotModel';

UPDATE blueprints SET params_schema = '[
  {"name":"group_by","type":"string[]","required":true},
  {"name":"aggregations","type":"object[]","required":true,"description":"[{measure, aggregation_function}]"},
  {"name":"refresh_strategy","type":"enum","required":false,"options":["full","incremental"],"default":"incremental"}
]'::jsonb WHERE blueprint_key = 'AggregateMaterialization';

UPDATE blueprints SET params_schema = '[
  {"name":"entity_key","type":"string","required":true},
  {"name":"features","type":"object[]","required":true,"description":"Feature definitions"},
  {"name":"point_in_time_column","type":"string","required":true},
  {"name":"output_format","type":"enum","required":false,"options":["delta","parquet","feature_store"],"default":"delta"}
]'::jsonb WHERE blueprint_key = 'FeatureTablePublish';

UPDATE blueprints SET params_schema = '[
  {"name":"reference_type","type":"string","required":true},
  {"name":"publish_frequency","type":"enum","required":false,"options":["on_change","daily","weekly"],"default":"on_change"},
  {"name":"versioned","type":"boolean","required":false,"default":true}
]'::jsonb WHERE blueprint_key = 'ReferenceDataPublish';

-- DATA_QUALITY -----------------------------------------------------------------
UPDATE blueprints SET params_schema = '[
  {"name":"timestamp_column","type":"string","required":true},
  {"name":"max_age_hours","type":"integer","required":false,"default":24},
  {"name":"max_age_minutes","type":"integer","required":false}
]'::jsonb WHERE blueprint_key = 'FreshnessChecks';

UPDATE blueprints SET params_schema = '[
  {"name":"expected_columns","type":"object[]","required":true,"description":"[{name, type}]"},
  {"name":"strict_order","type":"boolean","required":false,"default":false},
  {"name":"allow_extra_columns","type":"boolean","required":false,"default":true},
  {"name":"drift_policy","type":"enum","required":false,"options":["block","warn","auto_adapt"],"default":"warn"}
]'::jsonb WHERE blueprint_key = 'SchemaDriftDetection';

UPDATE blueprints SET params_schema = '[
  {"name":"monitored_columns","type":"string[]","required":true},
  {"name":"sensitivity_percent","type":"number","required":false,"default":2.0},
  {"name":"detection_method","type":"enum","required":false,"options":["z_score","iqr","mean_deviation"],"default":"z_score"},
  {"name":"lookback_runs","type":"integer","required":false,"default":10},
  {"name":"volume_monitoring","type":"boolean","required":false,"default":false}
]'::jsonb WHERE blueprint_key = 'AnomalyDetection';

-- DESTINATION ------------------------------------------------------------------
UPDATE blueprints SET params_schema = '[
  {"name":"target_table","type":"string","required":true},
  {"name":"write_mode","type":"enum","required":false,"options":["overwrite","append","merge"],"default":"append"},
  {"name":"merge_keys","type":"string[]","required":false},
  {"name":"batch_size","type":"integer","required":false,"default":10000}
]'::jsonb WHERE blueprint_key = 'WarehouseWriter';

UPDATE blueprints SET params_schema = '[
  {"name":"output_path","type":"string","required":true},
  {"name":"write_mode","type":"enum","required":false,"options":["overwrite","append","merge"],"default":"append"},
  {"name":"merge_keys","type":"string[]","required":false},
  {"name":"schema_evolution","type":"enum","required":false,"options":["strict","add_columns","overwrite_schema"],"default":"add_columns"},
  {"name":"compact_files","type":"boolean","required":false,"default":false}
]'::jsonb WHERE blueprint_key = 'LakeWriter';

UPDATE blueprints SET params_schema = '[
  {"name":"topic","type":"string","required":true},
  {"name":"serialization_format","type":"enum","required":false,"options":["json","avro","protobuf"],"default":"json"},
  {"name":"key_column","type":"string","required":false},
  {"name":"delivery_guarantee","type":"enum","required":false,"options":["at_least_once","exactly_once"],"default":"at_least_once"},
  {"name":"checkpoint_location","type":"string","required":false}
]'::jsonb WHERE blueprint_key = 'StreamWriter';

UPDATE blueprints SET params_schema = '[
  {"name":"target_table","type":"string","required":true},
  {"name":"write_mode","type":"enum","required":false,"options":["overwrite","append","upsert"],"default":"append"},
  {"name":"upsert_keys","type":"string[]","required":false},
  {"name":"batch_size","type":"integer","required":false,"default":5000},
  {"name":"connection_pool_size","type":"integer","required":false,"default":5}
]'::jsonb WHERE blueprint_key = 'DatabaseWriter';

-- ORCHESTRATION policies -------------------------------------------------------
UPDATE blueprints SET params_schema = '[
  {"name":"schedule_type","type":"enum","required":true,"options":["cron","event","manual"]},
  {"name":"cron_expression","type":"string","required":false},
  {"name":"trigger_dataset","type":"string","required":false},
  {"name":"timezone","type":"string","required":false,"default":"UTC"},
  {"name":"retry_count","type":"integer","required":false,"default":3}
]'::jsonb WHERE blueprint_key = 'ScheduleAndTriggers';

UPDATE blueprints SET params_schema = '[
  {"name":"start_date","type":"string","required":true,"description":"Date YYYY-MM-DD"},
  {"name":"end_date","type":"string","required":true,"description":"Date YYYY-MM-DD"},
  {"name":"parallelism","type":"integer","required":false,"default":1},
  {"name":"clear_existing","type":"boolean","required":false,"default":false}
]'::jsonb WHERE blueprint_key = 'BackfillAndReplay';

UPDATE blueprints SET params_schema = '[
  {"name":"rollback_trigger","type":"enum","required":false,"options":["deploy_failure","health_check_failure","manual"],"default":"deploy_failure"},
  {"name":"keep_failed_artifacts","type":"boolean","required":false,"default":true}
]'::jsonb WHERE blueprint_key = 'RollbackOnFailure';

UPDATE blueprints SET params_schema = '[
  {"name":"budget_limit_daily","type":"number","required":false},
  {"name":"alert_threshold_percent","type":"number","required":false,"default":80},
  {"name":"track_compute","type":"boolean","required":false,"default":true},
  {"name":"track_storage","type":"boolean","required":false,"default":true}
]'::jsonb WHERE blueprint_key = 'CostMonitoringHook';

UPDATE blueprints SET params_schema = '[
  {"name":"dataset_name","type":"string","required":true},
  {"name":"advance_domain","type":"boolean","required":false,"default":false},
  {"name":"notes","type":"string","required":false}
]'::jsonb WHERE blueprint_key = 'AdvanceTimeDimension';

-- =============================================================================
-- 7e. Additive params (window_functions, partition_by, cluster_by).
-- =============================================================================

UPDATE blueprints SET params_schema = COALESCE(params_schema, '[]'::jsonb) || '[
  {"name":"window_functions","type":"object[]","required":false,"description":"Window function definitions","schema":{"column":"string","function":"string","partition_by":"string[]","order_by":"string[]","frame":"string"}}
]'::jsonb WHERE blueprint_key = 'GenericAggregate';

UPDATE blueprints SET params_schema = COALESCE(params_schema, '[]'::jsonb) || '[
  {"name":"partition_by","type":"string[]","required":false,"description":"Columns to partition by"},
  {"name":"cluster_by","type":"string[]","required":false,"description":"Columns to cluster by (BigQuery)"}
]'::jsonb WHERE category = 'MODELING' AND status = 'active';

UPDATE blueprints SET params_schema = COALESCE(params_schema, '[]'::jsonb) || '[
  {"name":"partition_by","type":"string[]","required":false,"description":"Columns to partition by"}
]'::jsonb WHERE category = 'DESTINATION' AND status = 'active';

-- =============================================================================
-- 7b + 7h. Normalize artifact_types vocabulary and align with locked decisions.
--   * pyspark_job replaces legacy spark_job (INGESTION, DESTINATION).
--   * publish_manifest / quarantine_manifest / dbt_test retired.
--   * DQValidator is GX-only.
--   * Compute backend is Spark (no bigquery).
-- =============================================================================

UPDATE blueprints SET artifact_types = '["gx_checkpoint"]'::jsonb
    WHERE blueprint_key = 'DQValidator';

UPDATE blueprints SET compute_backend = 'spark'
    WHERE blueprint_key IN ('SCD2Dimension', 'WideDenormalizedMart');

-- =============================================================================
-- 7f. Category-level metadata for every active blueprint.
-- =============================================================================

-- INGESTION --------------------------------------------------------------------
UPDATE blueprints SET
    subcategory = CASE blueprint_key
        WHEN 'FileIngestion'    THEN 'file_ingest'
        WHEN 'ApiIngestion'     THEN 'api_ingest'
        WHEN 'StreamIngestion'  THEN 'stream_ingest'
        WHEN 'SnapshotIngestion' THEN 'snapshot_ingest'
        WHEN 'CDCIngestion'     THEN 'cdc_ingest'
        WHEN 'BulkBackfill'     THEN 'bulk_backfill'
        ELSE subcategory
    END,
    artifact_types = '["pyspark_job"]'::jsonb,
    compute_backend = 'spark',
    composition_role = 'ingestion',
    valid_layers = '["bronze"]'::jsonb,
    emit_strategy = 'generate',
    usage_guidance = jsonb_build_object(
        'when_to_use', CASE blueprint_key
            WHEN 'FileIngestion'     THEN 'Ingest files from S3, GCS, SFTP, or local filesystem'
            WHEN 'ApiIngestion'      THEN 'Pull data from REST APIs with pagination and auth'
            WHEN 'StreamIngestion'   THEN 'Consume from Kafka, Kinesis, or PubSub streams'
            WHEN 'SnapshotIngestion' THEN 'Full-table snapshot from JDBC sources without CDC'
            WHEN 'CDCIngestion'      THEN 'True CDC via Debezium or incremental polling fallback'
            WHEN 'BulkBackfill'      THEN 'Historical backfill over a date range'
        END
    ),
    codegen_hints = jsonb_build_object('example_keys', CASE blueprint_key
        WHEN 'FileIngestion'     THEN '["file_ingestion_s3_csv", "file_ingestion_sftp"]'::jsonb
        WHEN 'ApiIngestion'      THEN '["api_rest_paginated"]'::jsonb
        WHEN 'StreamIngestion'   THEN '["stream_kafka_json"]'::jsonb
        WHEN 'SnapshotIngestion' THEN '["jdbc_snapshot_oracle"]'::jsonb
        WHEN 'CDCIngestion'      THEN '["cdc_debezium_postgres", "cdc_incremental_poll"]'::jsonb
        WHEN 'BulkBackfill'      THEN '["jdbc_snapshot_oracle"]'::jsonb
    END)
WHERE category = 'INGESTION' AND status = 'active';

-- TRANSFORM --------------------------------------------------------------------
UPDATE blueprints SET
    artifact_types = '["dbt_model"]'::jsonb,
    compute_backend = 'spark',
    emit_strategy = 'generate',
    composition_role = 'transform',
    valid_layers = CASE blueprint_key
        WHEN 'GenericFilter'    THEN '["silver","gold"]'
        WHEN 'GenericAggregate' THEN '["silver","gold"]'
        WHEN 'GenericJoin'      THEN '["silver","gold"]'
        ELSE '["silver"]'
    END::jsonb,
    subcategory = CASE blueprint_key
        WHEN 'BronzeToSilverCleaning' THEN 'cleaning'
        WHEN 'SchemaNormalization'    THEN 'conformance'
        WHEN 'GenericFilter'          THEN 'filter'
        WHEN 'GenericAggregate'       THEN 'aggregate'
        WHEN 'GenericJoin'            THEN 'join'
        WHEN 'GenericRouter'          THEN 'router'
        WHEN 'JsonFlatten'            THEN 'flatten'
        WHEN 'JsonStruct'             THEN 'struct'
        WHEN 'PIIMasking'             THEN 'masking'
        WHEN 'DedupeAndMerge'         THEN 'dedup'
    END,
    usage_guidance = jsonb_build_object(
        'when_to_use', CASE blueprint_key
            WHEN 'BronzeToSilverCleaning' THEN 'Clean raw data: type casts, trims, null handling, dedup'
            WHEN 'SchemaNormalization'    THEN 'Conform data to a target enterprise schema with mapping rules'
            WHEN 'GenericFilter'          THEN 'Filter rows with visual conditions or raw SQL WHERE clause'
            WHEN 'GenericAggregate'       THEN 'Aggregate data with GROUP BY, aggregations, and optional window functions'
            WHEN 'GenericJoin'            THEN 'Join two or more datasets on specified keys'
            WHEN 'GenericRouter'          THEN 'Route data to multiple outputs based on conditions'
            WHEN 'JsonFlatten'            THEN 'Flatten nested JSON/struct fields into tabular columns'
            WHEN 'JsonStruct'             THEN 'Build nested struct or JSON output from flat columns'
            WHEN 'PIIMasking'             THEN 'Mask PII columns via hash, redact, tokenize, or encrypt'
            WHEN 'DedupeAndMerge'         THEN 'Deduplicate records by match key with survivorship rules'
        END,
        'dbt_layer', CASE blueprint_key
            WHEN 'BronzeToSilverCleaning' THEN 'staging'
            WHEN 'SchemaNormalization'    THEN 'staging'
            WHEN 'JsonFlatten'            THEN 'staging'
            WHEN 'PIIMasking'             THEN 'staging'
            WHEN 'DedupeAndMerge'         THEN 'staging'
            ELSE 'intermediate'
        END
    ),
    codegen_hints = jsonb_build_object('example_keys', CASE blueprint_key
        WHEN 'BronzeToSilverCleaning' THEN '["stg_cleaning_basic", "stg_cleaning_type_cast"]'::jsonb
        WHEN 'SchemaNormalization'    THEN '["stg_cleaning_basic"]'::jsonb
        WHEN 'GenericFilter'          THEN '["int_filter_complex"]'::jsonb
        WHEN 'GenericAggregate'       THEN '["int_aggregate_window"]'::jsonb
        WHEN 'GenericJoin'            THEN '["int_join_two_sources"]'::jsonb
        WHEN 'GenericRouter'          THEN '["int_filter_complex"]'::jsonb
        WHEN 'JsonFlatten'            THEN '["stg_cleaning_basic"]'::jsonb
        WHEN 'JsonStruct'             THEN '["stg_cleaning_basic"]'::jsonb
        WHEN 'PIIMasking'             THEN '["stg_pii_masking"]'::jsonb
        WHEN 'DedupeAndMerge'         THEN '["stg_cleaning_basic"]'::jsonb
    END)
WHERE category = 'TRANSFORM' AND status = 'active'
AND blueprint_key IN ('BronzeToSilverCleaning','SchemaNormalization','GenericFilter','GenericAggregate','GenericJoin','GenericRouter','JsonFlatten','JsonStruct','PIIMasking','DedupeAndMerge');

-- MODELING ---------------------------------------------------------------------
UPDATE blueprints SET
    compute_backend = 'spark',
    emit_strategy = 'generate',
    composition_role = 'modeling',
    valid_layers = CASE blueprint_key
        WHEN 'IncrementalMerge' THEN '["silver","gold"]'
        ELSE '["gold"]'
    END::jsonb,
    artifact_types = CASE blueprint_key
        WHEN 'SCD2Dimension' THEN '["dbt_snapshot"]'
        WHEN 'SnapshotModel' THEN '["dbt_snapshot"]'
        ELSE '["dbt_model"]'
    END::jsonb,
    subcategory = CASE blueprint_key
        WHEN 'SCD2Dimension'            THEN 'history'
        WHEN 'IncrementalMerge'         THEN 'incremental'
        WHEN 'FactBuild'                THEN 'fact'
        WHEN 'SnapshotModel'            THEN 'snapshot'
        WHEN 'WideDenormalizedMart'     THEN 'serving_mart'
        WHEN 'AggregateMaterialization' THEN 'aggregate'
        WHEN 'FeatureTablePublish'      THEN 'feature'
        WHEN 'ReferenceDataPublish'     THEN 'reference'
    END,
    supports_reuse = CASE blueprint_key
        WHEN 'SCD2Dimension'        THEN true
        WHEN 'WideDenormalizedMart' THEN true
        WHEN 'FactBuild'            THEN true
        ELSE false
    END,
    usage_guidance = jsonb_build_object(
        'when_to_use', CASE blueprint_key
            WHEN 'SCD2Dimension'            THEN 'Track full history for mutable master data (employees, customers)'
            WHEN 'IncrementalMerge'         THEN 'Process only new/changed records since last run'
            WHEN 'FactBuild'                THEN 'Build a fact table with measures and dimension keys'
            WHEN 'SnapshotModel'            THEN 'Point-in-time snapshot for periodic state capture'
            WHEN 'WideDenormalizedMart'     THEN 'Wide denormalized table joining facts with dimensions for BI'
            WHEN 'AggregateMaterialization' THEN 'Pre-aggregated summary table for dashboards'
            WHEN 'FeatureTablePublish'      THEN 'ML feature table with entity key and point-in-time features'
            WHEN 'ReferenceDataPublish'     THEN 'Publish versioned reference/lookup data'
        END,
        'dbt_layer', CASE blueprint_key
            WHEN 'SCD2Dimension' THEN 'snapshots'
            WHEN 'SnapshotModel' THEN 'snapshots'
            ELSE 'marts'
        END
    ),
    codegen_hints = jsonb_build_object('example_keys', CASE blueprint_key
        WHEN 'SCD2Dimension'            THEN '["snp_timestamp_strategy", "snp_check_strategy"]'::jsonb
        WHEN 'IncrementalMerge'         THEN '["int_incremental_merge"]'::jsonb
        WHEN 'FactBuild'                THEN '["fct_basic", "fct_incremental_late_arriving"]'::jsonb
        WHEN 'SnapshotModel'            THEN '["snp_timestamp_strategy"]'::jsonb
        WHEN 'WideDenormalizedMart'     THEN '["mart_wide_denormalized"]'::jsonb
        WHEN 'AggregateMaterialization' THEN '["agg_summary"]'::jsonb
        WHEN 'FeatureTablePublish'      THEN '["fct_basic"]'::jsonb
        WHEN 'ReferenceDataPublish'     THEN '["agg_summary"]'::jsonb
    END)
WHERE category = 'MODELING' AND status = 'active'
AND blueprint_key IN ('SCD2Dimension','IncrementalMerge','FactBuild','SnapshotModel','WideDenormalizedMart','AggregateMaterialization','FeatureTablePublish','ReferenceDataPublish');

-- DATA_QUALITY -----------------------------------------------------------------
UPDATE blueprints SET
    artifact_types = '["gx_checkpoint"]'::jsonb,
    compute_backend = 'spark',
    emit_strategy = 'generate',
    composition_role = 'data_quality',
    valid_layers = CASE blueprint_key
        WHEN 'AnomalyDetection' THEN '["silver","gold"]'
        ELSE '["silver"]'
    END::jsonb,
    subcategory = CASE blueprint_key
        WHEN 'DQValidator'          THEN 'validation'
        WHEN 'FreshnessChecks'      THEN 'freshness'
        WHEN 'SchemaDriftDetection' THEN 'drift'
        WHEN 'AnomalyDetection'     THEN 'anomaly'
    END,
    usage_guidance = jsonb_build_object(
        'when_to_use', CASE blueprint_key
            WHEN 'DQValidator'          THEN 'Validate data quality with per-rule thresholds, quarantine support'
            WHEN 'FreshnessChecks'      THEN 'Check source data freshness by timestamp column age'
            WHEN 'SchemaDriftDetection' THEN 'Detect schema changes between runs'
            WHEN 'AnomalyDetection'     THEN 'Statistical anomaly detection on column values and volumes'
        END
    ),
    codegen_hints = jsonb_build_object('example_keys', CASE blueprint_key
        WHEN 'DQValidator'          THEN '["bronze_silver_gate", "silver_gold_gate"]'::jsonb
        WHEN 'FreshnessChecks'      THEN '["bronze_silver_gate"]'::jsonb
        WHEN 'SchemaDriftDetection' THEN '["schema_drift_check"]'::jsonb
        WHEN 'AnomalyDetection'     THEN '["anomaly_detection"]'::jsonb
    END)
WHERE blueprint_key IN ('DQValidator','FreshnessChecks','SchemaDriftDetection','AnomalyDetection')
AND status = 'active';

-- DESTINATION ------------------------------------------------------------------
UPDATE blueprints SET
    artifact_types = '["pyspark_job"]'::jsonb,
    compute_backend = 'spark',
    emit_strategy = 'generate',
    composition_role = 'destination',
    valid_layers = CASE blueprint_key
        WHEN 'LakeWriter'   THEN '["bronze","silver"]'
        WHEN 'StreamWriter' THEN '["silver","gold"]'
        ELSE '["gold"]'
    END::jsonb,
    subcategory = CASE blueprint_key
        WHEN 'WarehouseWriter' THEN 'warehouse'
        WHEN 'LakeWriter'      THEN 'lake'
        WHEN 'StreamWriter'    THEN 'stream'
        WHEN 'DatabaseWriter'  THEN 'database'
    END,
    usage_guidance = jsonb_build_object(
        'when_to_use', CASE blueprint_key
            WHEN 'WarehouseWriter' THEN 'Write to BigQuery, Snowflake, or Redshift'
            WHEN 'LakeWriter'      THEN 'Write to object storage in Delta, Iceberg, or Parquet format'
            WHEN 'StreamWriter'    THEN 'Publish to Kafka topic'
            WHEN 'DatabaseWriter'  THEN 'Write to PostgreSQL or Databricks'
        END
    ),
    codegen_hints = jsonb_build_object('example_keys', CASE blueprint_key
        WHEN 'WarehouseWriter' THEN '["warehouse_bigquery"]'::jsonb
        WHEN 'LakeWriter'      THEN '["lake_delta", "lake_iceberg"]'::jsonb
        WHEN 'StreamWriter'    THEN '["stream_kafka"]'::jsonb
        WHEN 'DatabaseWriter'  THEN '["database_postgres"]'::jsonb
    END)
WHERE category = 'DESTINATION' AND status = 'active';

-- ORCHESTRATION policy blueprints ---------------------------------------------
UPDATE blueprints SET
    artifact_types = '["airflow_policy"]'::jsonb,
    compute_backend = 'airflow',
    emit_strategy = 'config_only',
    composition_role = 'orchestration_policy',
    valid_layers = '["control_plane"]'::jsonb,
    subcategory = 'policy',
    usage_guidance = jsonb_build_object(
        'when_to_use', CASE blueprint_key
            WHEN 'ScheduleAndTriggers'   THEN 'Define pipeline schedule: cron, event-triggered, or manual'
            WHEN 'BackfillAndReplay'     THEN 'Configure historical backfill with date range and parallelism'
            WHEN 'RollbackOnFailure'     THEN 'Define rollback behavior on deploy or health-check failure'
            WHEN 'CostMonitoringHook'    THEN 'Set daily budget limits and compute/storage tracking'
            WHEN 'AdvanceTimeDimension'  THEN 'Advance dataset or domain business date after successful run'
        END
    ),
    codegen_hints = jsonb_build_object('example_keys', '[]'::jsonb)
WHERE blueprint_key IN ('ScheduleAndTriggers','BackfillAndReplay','RollbackOnFailure','CostMonitoringHook','AdvanceTimeDimension')
AND status = 'active';

-- =============================================================================
-- 7b (cont.) + 7i. Sensors are control-plane. Also add empty example_keys.
-- =============================================================================

UPDATE blueprints SET valid_layers = '["control_plane"]'::jsonb
WHERE blueprint_key IN (
    'FileArrivalSensor',
    'ObjectStoreKeySensor',
    'DatabaseReadinessSensor',
    'DatasetDependencySensor',
    'ExternalEventSensor'
);

UPDATE blueprints SET codegen_hints = jsonb_build_object('example_keys', '[]'::jsonb)
WHERE composition_role = 'orchestration_sensor'
AND status = 'active'
AND (codegen_hints IS NULL OR codegen_hints = '{}'::jsonb OR NOT codegen_hints ? 'example_keys');
