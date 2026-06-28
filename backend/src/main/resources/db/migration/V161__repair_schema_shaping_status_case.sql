-- V161: Repair V159/V160 status-case mismatch for common schema shaping.
--
-- V159/V160 were applied successfully, but their WHERE clauses used
-- status = 'ACTIVE'. The blueprint catalog stores active rows as lowercase
-- status = 'active', so those UPDATEs no-opped on live/local catalogs.
--
-- This forward migration intentionally does not edit the already-applied
-- migrations. It replays the schema-shaping repair with case-insensitive
-- active-row matching and includes the UI construct hints directly.

-- BronzeToSilverCleaning has native drop_columns, so only add derived_columns.
UPDATE blueprints SET params_schema = (
    SELECT jsonb_agg(elem ORDER BY ord)
    FROM (
        SELECT elem, ord
        FROM jsonb_array_elements(params_schema) WITH ORDINALITY AS t(elem, ord)
        UNION ALL
        SELECT
            '{
              "name": "derived_columns",
              "type": "object[]",
              "required": false,
              "tier": "user",
              "ui_construct": "derived-column-builder",
              "description": "List of {name, type, expression, nullable?, description?} entries. Expression is SQL evaluated per row against the upstream input schema. Applied after the blueprint category-specific resolver."
            }'::jsonb,
            9999
        WHERE NOT params_schema @> '[{"name":"derived_columns"}]'
    ) sub
) WHERE blueprint_key = 'BronzeToSilverCleaning'
  AND lower(status) = 'active';

-- Add both derived_columns and dropped_columns to editable transform/modeling
-- blueprints that do not already have native schema-shaping params.
UPDATE blueprints SET params_schema = (
    SELECT jsonb_agg(elem ORDER BY ord)
    FROM (
        SELECT elem, ord
        FROM jsonb_array_elements(params_schema) WITH ORDINALITY AS t(elem, ord)
        UNION ALL
        SELECT
            CASE WHEN NOT params_schema @> '[{"name":"derived_columns"}]'
            THEN '{
              "name": "derived_columns",
              "type": "object[]",
              "required": false,
              "tier": "user",
              "ui_construct": "derived-column-builder",
              "description": "List of {name, type, expression, nullable?, description?} entries. Expression is SQL evaluated per row against the upstream input schema. Applied after the blueprint category-specific resolver."
            }'::jsonb
            ELSE NULL END,
            9998
        UNION ALL
        SELECT
            CASE WHEN NOT params_schema @> '[{"name":"dropped_columns"}]'
            THEN '{
              "name": "dropped_columns",
              "type": "string[]",
              "required": false,
              "tier": "user",
              "ui_construct": "column-picker (multi)",
              "description": "Names of columns to remove from the output. Applied after derived_columns are added, so a derive+drop combination can replace a column in place."
            }'::jsonb
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
) AND lower(status) = 'active';

-- Patch construct hints on any existing derived/drop params, including Derive's
-- native V102 params and any rows partially repaired by hand.
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
) WHERE lower(status) = 'active'
  AND (
      params_schema @> '[{"name":"derived_columns"}]'
      OR params_schema @> '[{"name":"dropped_columns"}]'
  );
