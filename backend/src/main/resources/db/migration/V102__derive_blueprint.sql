-- V102: Add the dedicated `Derive` blueprint to the catalog.
--
-- BACKGROUND
-- ----------
-- The schema-propagation engine (SchemaPropagationService) supports a universal
-- `derived_columns` param applied AFTER each category-specific resolver, so any
-- TRANSFORM or MODELING blueprint can inline derivations. This migration adds a
-- standalone `Derive` blueprint for users who prefer derivations to appear as
-- their own DAG node — same param shape, same resolver path, just visualized
-- as a discrete step.
--
-- The universal `dropped_columns` addendum is also exposed here so users can
-- drop columns in one place if they're not folding them into another transform.
--
-- Schema:
--   params_schema:
--     - derived_columns: array of { name, type, expression, nullable?, description? }
--     - dropped_columns: array of column-name strings
--   input_ports:  data_input
--   output_ports: data_output
--
-- The resolver behavior is implemented in SchemaPropagationService.applyDerivedColumns
-- + applyDroppedColumns. No code changes are required to support this blueprint —
-- the universal addenda already handle it.

-- ULID note: 01JBP0TRANSFORM0DERIV0001 was already taken by V7's
-- DerivedMetricsComputation blueprint. We use the suffix DRVCOL001 to keep
-- this row distinct.
INSERT INTO blueprints (
    id, blueprint_key, name, description, category, version,
    params_schema, input_ports, output_ports, deferred
) VALUES (
    '01JBP0TRANSFORM0DRVCOL001',
    'Derive',
    'Derive Columns',
    'Adds new computed columns (and optionally drops existing ones) without changing the rest of the schema. Each derived column is a SQL expression evaluated per row — for example, ''datediff(current_date, origination_date)'' to compute loan age. Use this when you want derivations visible as a distinct step on the DAG; otherwise the same shape can be added inline on any silver or gold transform.',
    'TRANSFORM',
    '1.0.0',
    '[
        {"name":"derived_columns","type":"object[]","required":false,"description":"List of {name, type, expression, nullable?, description?} entries. Expression is SQL evaluated per row against the upstream input schema."},
        {"name":"dropped_columns","type":"string[]","required":false,"description":"Names of columns to remove from the output. Applied after derived_columns are added, so a derive+drop combination can replace a column in place."}
    ]',
    '[{"name":"data_input","description":"Upstream rows whose schema is the basis for derived expressions"}]',
    '[{"name":"data_output","description":"Upstream rows + derived columns minus dropped columns"}]',
    false
)
ON CONFLICT (blueprint_key) DO NOTHING;
