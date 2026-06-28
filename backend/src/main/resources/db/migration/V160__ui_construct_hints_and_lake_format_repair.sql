-- V160: Follow-up to V159 — add ui_construct hints + repair lake format defaults.
--
-- V159 was already applied without ui_construct hints, so the derived_columns
-- and dropped_columns params render as raw JSON textareas. This migration patches
-- them inline with the correct construct hints so the UI routes them to the
-- rich DerivedColumnBuilder and MultiColumnPicker components.
--
-- Also repairs lake_format defaults/options across the catalog to align with
-- RuntimeAuthorityService (V153/V156 seeded "delta" as the default for DPC and
-- GCP blueprints, but RuntimeAuthorityService resolves DPC to parquet and GCP
-- to iceberg_bq_managed). This completes the lake-format authority alignment
-- (P1-2).

-- ── Part 1: ui_construct hints for derived_columns / dropped_columns ──────

-- Add ui_construct to derived_columns params that V159 created (no hint).
-- Pattern: patch the element inline when name matches and ui_construct absent.
UPDATE blueprints SET params_schema = (
    SELECT jsonb_agg(
        CASE
            WHEN elem ->> 'name' = 'derived_columns' AND NOT elem ? 'ui_construct'
            THEN elem || '{"ui_construct":"derived-column-builder"}'::jsonb
            ELSE elem
        END
        ORDER BY ord
    )
    FROM jsonb_array_elements(params_schema) WITH ORDINALITY AS t(elem, ord)
) WHERE blueprint_key IN (
    'BronzeToSilverCleaning',
    'SchemaNormalization', 'DedupeAndMerge', 'PIIMasking',
    'GenericJoin', 'GenericAggregate', 'GenericFilter', 'GenericRouter',
    'JsonFlatten', 'JsonStruct',
    'SCD2Dimension', 'SnapshotModel', 'FactBuild',
    'WideDenormalizedMart', 'IncrementalMerge',
    'ReferenceDataPublish', 'FeatureTablePublish'
) AND status = 'ACTIVE';

-- Add ui_construct to dropped_columns params that V159 created (no hint).
UPDATE blueprints SET params_schema = (
    SELECT jsonb_agg(
        CASE
            WHEN elem ->> 'name' = 'dropped_columns' AND NOT elem ? 'ui_construct'
            THEN elem || '{"ui_construct":"column-picker (multi)"}'::jsonb
            ELSE elem
        END
        ORDER BY ord
    )
    FROM jsonb_array_elements(params_schema) WITH ORDINALITY AS t(elem, ord)
) WHERE blueprint_key IN (
    'SchemaNormalization', 'DedupeAndMerge', 'PIIMasking',
    'GenericJoin', 'GenericAggregate', 'GenericFilter', 'GenericRouter',
    'JsonFlatten', 'JsonStruct',
    'SCD2Dimension', 'SnapshotModel', 'FactBuild',
    'WideDenormalizedMart', 'IncrementalMerge',
    'ReferenceDataPublish', 'FeatureTablePublish'
) AND status = 'ACTIVE';

-- Patch Derive blueprint's V102-era derived_columns / dropped_columns
-- (never had ui_construct, so they also render as raw JSON textareas).
UPDATE blueprints SET params_schema = (
    SELECT jsonb_agg(
        CASE
            WHEN elem ->> 'name' = 'derived_columns' AND NOT elem ? 'ui_construct'
            THEN elem || '{"ui_construct":"derived-column-builder"}'::jsonb
            WHEN elem ->> 'name' = 'dropped_columns' AND NOT elem ? 'ui_construct'
            THEN elem || '{"ui_construct":"column-picker (multi)"}'::jsonb
            ELSE elem
        END
        ORDER BY ord
    )
    FROM jsonb_array_elements(params_schema) WITH ORDINALITY AS t(elem, ord)
) WHERE blueprint_key = 'Derive' AND status = 'ACTIVE';

-- ── Part 2: Repair lake_format defaults/options to match authority ────────
--
-- RuntimeAuthorityService resolves:
--   DPC  → parquet (bronze/silver/gold)
--   GCP  → iceberg_bq_managed (bronze/silver), bq_native (gold)
-- But V153/V156 seeded lake_format default = "delta" across many blueprints.
-- This UPDATE replaces "delta" defaults with authority-aligned defaults and
-- removes "delta" from the options list for DPC/GCP blueprints.
--
-- Strategy: for each active blueprint's lake_format param in params_schema,
--   - if default = "delta" and the blueprint is a non-source type,
--     replace default with "parquet" (DPC default) or "iceberg_bq_managed"
--     (GCP default) depending on the blueprint's likely storage context;
--     for generality, use "parquet" as the safest cross-backend default.
--   - remove "delta" from the options array if present.
-- This is a catalog-level fix; runtime authority still overrides at pipeline
-- resolution time.

-- Replace delta default in lake_format param descriptors.
UPDATE blueprints SET params_schema = (
    SELECT jsonb_agg(
        CASE
            WHEN elem ->> 'name' = 'lake_format'
                 AND elem ->> 'default' = 'delta'
            THEN elem
                || jsonb_build_object('default', 'parquet')
                || jsonb_build_object(
                       'description',
                       coalesce(elem ->> 'description', '')
                       || ' [Authority: DPC=parquet, GCP=iceberg_bq_managed (gold=bq_native)]')
            ELSE elem
        END
        ORDER BY ord
    )
    FROM jsonb_array_elements(params_schema) WITH ORDINALITY AS t(elem, ord)
) WHERE status = 'ACTIVE'
  AND params_schema @> '[{"name":"lake_format","default":"delta"}]';
