-- V159: Surface common schema-shaping params on editable blueprints (LCT-055).
--
-- The universal addenda in SchemaPropagationService already process
-- derived_columns and dropped_columns for EVERY blueprint. The gap is that
-- these params are not declared in params_schema, so the metadata-driven UI
-- never surfaces them. This migration adds them as optional user-tier params.
--
-- Rules:
--   Source-root INGESTION blueprints (7): SKIP — read-only, no user ops.
--   Derive: SKIP — already has native derived_columns + dropped_columns.
--   BronzeToSilverCleaning: ADD ONLY derived_columns — has native drop_columns.
--   16 other TRANSFORM/MODELING blueprints: ADD BOTH derived_columns + dropped_columns.
--
-- The params are appended to each blueprint's params_schema so they appear at
-- the bottom of the configure dialog. They are tier=user, required=false.

-- Helper: append a param descriptor to a blueprint's params_schema JSON array.
-- Uses jsonb_agg WITH ORDINALITY to preserve existing element order, then
-- appends the new element. Idempotent: skips if a param with the same name
-- already exists (checked via @> containment).

-- 1. Add derived_columns to BronzeToSilverCleaning (NEEDS-ADD only).
UPDATE blueprints SET params_schema = (
    SELECT jsonb_agg(elem ORDER BY ord)
    FROM (
        SELECT elem, ord FROM jsonb_array_elements(params_schema) WITH ORDINALITY AS t(elem, ord)
        UNION ALL
        SELECT
            '{"name":"derived_columns","type":"object[]","required":false,"tier":"user","description":"List of {name, type, expression, nullable?, description?} entries. Expression is SQL evaluated per row against the upstream input schema. Applied after the blueprint category-specific resolver."}'::jsonb,
            9999
        WHERE NOT params_schema @> '[{"name":"derived_columns"}]'
    ) sub
) WHERE blueprint_key = 'BronzeToSilverCleaning' AND status = 'ACTIVE';

-- 2. Add both derived_columns + dropped_columns to the 16 NEEDS-BOTH blueprints.
-- Batch update: for each qualifying blueprint, append both params if not already present.
UPDATE blueprints SET params_schema = (
    SELECT jsonb_agg(elem ORDER BY ord)
    FROM (
        SELECT elem, ord FROM jsonb_array_elements(params_schema) WITH ORDINALITY AS t(elem, ord)
        UNION ALL
        SELECT
            CASE WHEN NOT params_schema @> '[{"name":"derived_columns"}]'
            THEN '{"name":"derived_columns","type":"object[]","required":false,"tier":"user","description":"List of {name, type, expression, nullable?, description?} entries. Expression is SQL evaluated per row against the upstream input schema. Applied after the blueprint category-specific resolver."}'::jsonb
            ELSE NULL END,
            9998
        UNION ALL
        SELECT
            CASE WHEN NOT params_schema @> '[{"name":"dropped_columns"}]'
            THEN '{"name":"dropped_columns","type":"string[]","required":false,"tier":"user","description":"Names of columns to remove from the output. Applied after derived_columns are added, so a derive+drop combination can replace a column in place."}'::jsonb
            ELSE NULL END,
            9999
    ) sub
    WHERE elem IS NOT NULL
) WHERE blueprint_key IN (
    'SchemaNormalization', 'DedupeAndMerge', 'PIIMasking',
    'GenericJoin', 'GenericAggregate', 'GenericFilter', 'GenericRouter',
    'JsonFlatten', 'JsonStruct',
    'SCD2Dimension', 'SnapshotModel', 'FactBuild',
    'WideDenormalizedMart', 'IncrementalMerge',
    'ReferenceDataPublish', 'FeatureTablePublish'
) AND status = 'ACTIVE';
