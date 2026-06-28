-- V94: Remap example_keys for blueprints whose existing keys point at
-- examples now dedicated to a different blueprint (#44 Phase D).
--
-- After Phase C of #44, eight example files were authored as dedicated
-- production-grade examples for blueprints that previously shared a
-- file with semantically unrelated blueprints (the trust-eroding case
-- the user flagged). This migration points each affected blueprint at
-- its now-dedicated file. After V94, NO example file should be
-- referenced by more than one blueprint.
--
-- The old shared files (stg_cleaning_basic.sql, fct_basic.sql,
-- agg_summary.sql, snp_timestamp_strategy.sql, int_filter_complex.sql,
-- bronze_silver_gate.py) remain — each is now exclusively the
-- canonical example for ONE blueprint after this remap.

-- 1. GenericRouter: was sharing int_filter_complex with GenericFilter.
--    GenericRouter is a multi-output tag transform; GenericFilter is
--    single-output drop-non-matching. Different concerns.
UPDATE blueprints
SET codegen_hints = jsonb_set(
        codegen_hints,
        '{example_keys}',
        '["int_router_by_predicate"]'::jsonb,
        true
    ),
    updated_at = NOW()
WHERE blueprint_key = 'GenericRouter';

-- 2. JsonFlatten: was sharing stg_cleaning_basic with BronzeToSilverCleaning,
--    JsonStruct, SchemaNormalization. Flatten is the OPPOSITE of struct
--    composition; cleaning is unrelated. Now uses dedicated POSEXPLODE_OUTER
--    + struct-hoist example.
UPDATE blueprints
SET codegen_hints = jsonb_set(
        codegen_hints,
        '{example_keys}',
        '["stg_json_flatten"]'::jsonb,
        true
    ),
    updated_at = NOW()
WHERE blueprint_key = 'JsonFlatten';

-- 3. JsonStruct: was sharing stg_cleaning_basic. Struct composition is
--    the OPPOSITE concern from flatten and unrelated to cleaning.
UPDATE blueprints
SET codegen_hints = jsonb_set(
        codegen_hints,
        '{example_keys}',
        '["stg_json_struct"]'::jsonb,
        true
    ),
    updated_at = NOW()
WHERE blueprint_key = 'JsonStruct';

-- 4. SchemaNormalization: was sharing stg_cleaning_basic. Cleaning
--    operates on column VALUES; normalization conforms NAMES/TYPES/ORDER.
UPDATE blueprints
SET codegen_hints = jsonb_set(
        codegen_hints,
        '{example_keys}',
        '["stg_schema_normalization"]'::jsonb,
        true
    ),
    updated_at = NOW()
WHERE blueprint_key = 'SchemaNormalization';

-- 5. FreshnessChecks: was sharing bronze_silver_gate with DQValidator.
--    DQValidator runs GX checkpoints with structural invariants;
--    FreshnessChecks does a max-partition probe with business-day
--    SLA arithmetic.
UPDATE blueprints
SET codegen_hints = jsonb_set(
        codegen_hints,
        '{example_keys}',
        '["freshness_checks"]'::jsonb,
        true
    ),
    updated_at = NOW()
WHERE blueprint_key = 'FreshnessChecks';

-- 6. FeatureTablePublish: was sharing fct_basic with FactBuild. Fact is
--    event-grain for analytical aggregation; feature table is
--    entity-grain for ML feature stores.
UPDATE blueprints
SET codegen_hints = jsonb_set(
        codegen_hints,
        '{example_keys}',
        '["feature_table_publish"]'::jsonb,
        true
    ),
    updated_at = NOW()
WHERE blueprint_key = 'FeatureTablePublish';

-- 7. ReferenceDataPublish: was sharing agg_summary with
--    AggregateMaterialization. Aggregate is time-bucketed metric
--    rollups; reference data is slowly-changing lookup tables.
UPDATE blueprints
SET codegen_hints = jsonb_set(
        codegen_hints,
        '{example_keys}',
        '["reference_data_publish"]'::jsonb,
        true
    ),
    updated_at = NOW()
WHERE blueprint_key = 'ReferenceDataPublish';

-- 8. SnapshotModel: was sharing snp_timestamp_strategy with SCD2Dimension.
--    SCD2 is dbt {% snapshot %} block with version history;
--    SnapshotModel is a normal incremental ds-partitioned model.
UPDATE blueprints
SET codegen_hints = jsonb_set(
        codegen_hints,
        '{example_keys}',
        '["snp_snapshot_model"]'::jsonb,
        true
    ),
    updated_at = NOW()
WHERE blueprint_key = 'SnapshotModel';
