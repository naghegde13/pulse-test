-- V162: Remove BronzeToSilverCleaning drop_null_columns from the durable
-- catalog surface.
--
-- Product decision from live testing: users do not need both "Drop Columns"
-- and "Drop Null Columns" on Bronze-to-Silver Cleaning. Keep the first-class
-- drop_columns operation and remove the drop-null-row operation from the
-- metadata-driven UI and op-list so the catalog has no dangling param refs.

UPDATE blueprints
SET params_schema = (
        SELECT coalesce(jsonb_agg(elem ORDER BY ord), '[]'::jsonb)
        FROM jsonb_array_elements(params_schema) WITH ORDINALITY AS t(elem, ord)
        WHERE elem ->> 'name' <> 'drop_null_columns'
    ),
    schema_behavior = jsonb_set(
        schema_behavior,
        '{ops}',
        (
            SELECT coalesce(jsonb_agg(elem ORDER BY ord), '[]'::jsonb)
            FROM jsonb_array_elements(schema_behavior -> 'ops') WITH ORDINALITY AS t(elem, ord)
            WHERE NOT (
                elem ->> 'op' = 'filter-rows'
                AND elem -> 'config' -> 'drop_when_null' ->> 'param' = 'drop_null_columns'
            )
        )
    )
WHERE blueprint_key = 'BronzeToSilverCleaning'
  AND lower(status) = 'active';

-- Remove stale hidden values from existing BronzeToSilverCleaning instances so
-- old test/demo rows cannot keep applying a now-removed behavior.
UPDATE sub_pipeline_instances
SET params = params - 'drop_null_columns'
WHERE blueprint_key = 'BronzeToSilverCleaning'
  AND params ? 'drop_null_columns';
