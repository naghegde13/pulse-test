-- V155: Seed UI-construct hints for the catalog/control params that already
-- have implemented ConfigureTransformDialog construct wiring.
--
-- V153 owns op-lists + tier/derivedFrom. This migration is intentionally
-- additive: it preserves each existing params_schema descriptor and appends
-- ui_construct/filter_types only for matching (blueprint_key, param_name) rows.

WITH hints(blueprint_key, param_name, ui_construct, filter_types) AS (
    VALUES
    -- Ingestion
    ('ApiIngestion', 'incremental_field', 'column-picker', NULL::jsonb),
    ('BulkBackfill', 'source_query', 'simple-sql-builder', NULL::jsonb),
    ('BulkBackfill', 'date_range_start', 'date-mnemonic-picker', NULL::jsonb),
    ('BulkBackfill', 'date_range_end', 'date-mnemonic-picker', NULL::jsonb),
    ('CDCIngestion', 'primary_key', 'column-picker (multi)', NULL::jsonb),
    ('CDCIngestion', 'incremental_column', 'column-picker', NULL::jsonb),
    ('CDCIngestion', 'watermark_column', 'column-picker', NULL::jsonb),
    ('CDCIngestion', 'ordering_field', 'column-picker', NULL::jsonb),
    ('FileIngestion', 'date_value', 'date-mnemonic-picker', NULL::jsonb),
    ('SnapshotIngestion', 'compare_key', 'column-picker', NULL::jsonb),

    -- Transform
    ('BronzeToSilverCleaning', 'trim_columns', 'column-picker (multi)', NULL::jsonb),
    ('BronzeToSilverCleaning', 'fill_null_map', 'key-value-mapper', NULL::jsonb),
    ('BronzeToSilverCleaning', 'rename_map', 'rename-mapper', NULL::jsonb),
    ('BronzeToSilverCleaning', 'type_coercions', 'type-cast-mapper', NULL::jsonb),
    ('BronzeToSilverCleaning', 'drop_columns', 'column-picker (multi)', NULL::jsonb),
    ('BronzeToSilverCleaning', 'drop_null_columns', 'column-picker (multi)', NULL::jsonb),
    ('BronzeToSilverCleaning', 'dedup_key', 'column-picker (multi)', NULL::jsonb),
    ('SchemaNormalization', 'mapping_rules', 'rename-mapper', NULL::jsonb),
    ('DedupeAndMerge', 'match_keys', 'column-picker (multi)', NULL::jsonb),
    ('DedupeAndMerge', 'merge_priority', 'column-picker', NULL::jsonb),
    ('PIIMasking', 'columns_to_mask', 'column-picker (multi)', NULL::jsonb),
    ('GenericJoin', 'select_columns', 'column-picker (multi)', NULL::jsonb),
    ('GenericAggregate', 'group_by_columns', 'column-picker (multi)', NULL::jsonb),
    ('GenericFilter', 'conditions', 'condition-builder', NULL::jsonb),
    ('GenericFilter', 'raw_sql', 'expression-builder', NULL::jsonb),
    ('JsonFlatten', 'source_columns', 'column-picker (multi)', '["struct","json","array","map"]'::jsonb),
    ('JsonStruct', 'passthrough_columns', 'column-picker (multi)', NULL::jsonb),

    -- Modeling
    ('SCD2Dimension', 'business_key', 'column-picker (multi)', NULL::jsonb),
    ('SCD2Dimension', 'tracked_columns', 'column-picker (multi)', NULL::jsonb),
    ('SCD2Dimension', 'effective_date_column', 'column-picker', '["timestamp","date"]'::jsonb),
    ('SnapshotModel', 'unique_key', 'column-picker (multi)', NULL::jsonb),
    ('FactBuild', 'measures', 'column-picker (multi)', NULL::jsonb),
    ('FactBuild', 'dimension_keys', 'column-picker (multi)', NULL::jsonb),
    ('FactBuild', 'time_column', 'column-picker', '["timestamp","date"]'::jsonb),
    ('IncrementalMerge', 'merge_key', 'column-picker (multi)', NULL::jsonb),
    ('FeatureTablePublish', 'entity_key', 'column-picker', NULL::jsonb),
    ('FeatureTablePublish', 'point_in_time_column', 'column-picker', '["timestamp","date"]'::jsonb),

    -- Data quality
    ('DQValidator', 'on_failure', 'dq-outcome-control', NULL::jsonb),
    ('FreshnessChecks', 'timestamp_column', 'column-picker', '["timestamp","date"]'::jsonb),
    ('SchemaDriftDetection', 'drift_policy', 'dq-outcome-control', NULL::jsonb),
    ('AnomalyDetection', 'monitored_columns', 'column-picker (multi)', NULL::jsonb),

    -- Destinations
    ('WarehouseWriter', 'merge_keys', 'column-picker (multi)', NULL::jsonb),
    ('LakeWriter', 'merge_keys', 'column-picker (multi)', NULL::jsonb),
    ('LakeWriter', 'z_order_columns', 'column-picker (multi)', NULL::jsonb),
    ('DatabaseWriter', 'upsert_keys', 'column-picker (multi)', NULL::jsonb),
    ('DatabaseWriter', 'partition_by', 'column-picker (multi)', NULL::jsonb),
    ('StreamWriter', 'key_columns', 'column-picker (multi)', NULL::jsonb),

    -- Orchestration / control
    ('FileArrivalSensor', 'date_value', 'date-mnemonic-picker', NULL::jsonb),
    ('DatabaseReadinessSensor', 'sql', 'expression-builder', NULL::jsonb),
    ('DatabaseReadinessSensor', 'date_value', 'date-mnemonic-picker', NULL::jsonb),
    ('ScheduleAndTriggers', 'cron_expression', 'cron-builder', NULL::jsonb),
    ('AdvanceTimeDimension', 'advance_to', 'date-mnemonic-picker', NULL::jsonb),

    -- SQL blueprints
    ('SqlModel', 'steps', 'sql-chain-editor', NULL::jsonb),
    ('SourceSQL', 'source_query', 'simple-sql-builder', NULL::jsonb)
)
UPDATE blueprints b
SET params_schema = patched.params_schema
FROM (
    SELECT
        b2.blueprint_key,
        jsonb_agg(
            CASE
                WHEN h.param_name IS NULL THEN param.elem
                WHEN h.filter_types IS NULL THEN
                    param.elem || jsonb_build_object('ui_construct', h.ui_construct)
                ELSE
                    param.elem
                        || jsonb_build_object('ui_construct', h.ui_construct)
                        || jsonb_build_object('filter_types', h.filter_types)
            END
            ORDER BY param.ord
        ) AS params_schema
    FROM blueprints b2
    CROSS JOIN LATERAL jsonb_array_elements(b2.params_schema) WITH ORDINALITY AS param(elem, ord)
    LEFT JOIN hints h
        ON h.blueprint_key = b2.blueprint_key
       AND h.param_name = param.elem ->> 'name'
    WHERE EXISTS (
        SELECT 1
        FROM hints hx
        WHERE hx.blueprint_key = b2.blueprint_key
    )
    GROUP BY b2.blueprint_key
) patched
WHERE b.blueprint_key = patched.blueprint_key;
