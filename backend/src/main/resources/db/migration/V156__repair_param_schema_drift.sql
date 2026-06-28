-- V156: Repair the param-schema drift introduced when V153 wholesale-rewrote
-- params_schema. V153 (already applied — cannot be edited) dropped enum option
-- lists and mis-tiered user-editable params. This is a forward, additive repair
-- migration for the blueprint-catalog / runtime-authority / param-drift class
-- (live-chat bugfix Wave B/C: LCT-023, LCT-024, LCT-026, LCT-028, LCT-030).
--
-- Every statement is idempotent: each rewrites params_schema element-by-element
-- (preserving order via WITH ORDINALITY + jsonb_agg ... ORDER BY) and is gated
-- by a jsonb containment WHERE so re-runs / absent keys are no-ops. The repairs
-- keep the V153 tier contract intact (tier user|derived, derivedFrom IFF derived).

-- =============================================================================
-- LCT-023 — FileIngestion / FileArrivalSensor pattern_kind dropdown was empty.
-- V153 stripped the enum option list. Restore the original options (V92/V93:
-- ["template","glob","regex"]). The filename date-mnemonic assist param
-- (date_value, accepts_mnemonic=true, tier=user) already survives V153, so no
-- param needs to be re-added here.
-- =============================================================================
UPDATE blueprints b
SET params_schema = (
    SELECT jsonb_agg(
        CASE
            WHEN param.elem ->> 'name' = 'pattern_kind' AND (param.elem -> 'options') IS NULL
                THEN param.elem || '{"options": ["template", "glob", "regex"]}'::jsonb
            ELSE param.elem
        END
        ORDER BY param.ord
    )
    FROM jsonb_array_elements(b.params_schema) WITH ORDINALITY AS param(elem, ord)
)
WHERE b.blueprint_key IN ('FileIngestion', 'FileArrivalSensor')
  AND b.params_schema @> '[{"name": "pattern_kind"}]'::jsonb;

-- LCT-023 (cont.) — restore the holiday_calendar_id enum options V153 stripped
-- (V92/V93: ["US-FED","US-NYSE"]) across every blueprint that declares it, so
-- the calendar control is never an empty dropdown.
UPDATE blueprints b
SET params_schema = (
    SELECT jsonb_agg(
        CASE
            WHEN param.elem ->> 'name' = 'holiday_calendar_id' AND (param.elem -> 'options') IS NULL
                THEN param.elem || '{"options": ["US-FED", "US-NYSE"]}'::jsonb
            ELSE param.elem
        END
        ORDER BY param.ord
    )
    FROM jsonb_array_elements(b.params_schema) WITH ORDINALITY AS param(elem, ord)
)
WHERE b.params_schema @> '[{"name": "holiday_calendar_id"}]'::jsonb;

-- =============================================================================
-- LCT-024 — date_format must be user-overridable, not derived/read-only. V153
-- tiered it derived (derivedFrom=platform_default), which the dialog renders
-- read-only. Promote to tier=user and drop derivedFrom (the V153 contract
-- requires derivedFrom IFF derived) on every affected blueprint.
-- =============================================================================
UPDATE blueprints b
SET params_schema = (
    SELECT jsonb_agg(
        CASE
            WHEN param.elem ->> 'name' = 'date_format' AND param.elem ->> 'tier' = 'derived'
                THEN (param.elem - 'derivedFrom') || '{"tier": "user"}'::jsonb
            ELSE param.elem
        END
        ORDER BY param.ord
    )
    FROM jsonb_array_elements(b.params_schema) WITH ORDINALITY AS param(elem, ord)
)
WHERE b.params_schema @> '[{"name": "date_format", "tier": "derived"}]'::jsonb;

-- =============================================================================
-- LCT-026 — partition_by must be user-configurable. V153 tiered it derived
-- (derivedFrom=pipeline.storage). Partitioning columns are a genuine user
-- choice; promote to tier=user and drop derivedFrom on every affected blueprint.
-- =============================================================================
UPDATE blueprints b
SET params_schema = (
    SELECT jsonb_agg(
        CASE
            WHEN param.elem ->> 'name' = 'partition_by' AND param.elem ->> 'tier' = 'derived'
                THEN (param.elem - 'derivedFrom') || '{"tier": "user"}'::jsonb
            ELSE param.elem
        END
        ORDER BY param.ord
    )
    FROM jsonb_array_elements(b.params_schema) WITH ORDINALITY AS param(elem, ord)
)
WHERE b.params_schema @> '[{"name": "partition_by", "tier": "derived"}]'::jsonb;

-- =============================================================================
-- LCT-028 — lake_format must obey runtime storage authority and never present
-- as an optionless enum. V153 left lake_format with NO options. Seed the legal
-- format union (mirrors StorageBackendValidator + the frontend legalLakeFormats()
-- matrix in configure-transform-dialog.tsx); the per-(backend,layer) narrowing
-- (e.g. gold-on-GCP => bq_native only, delta illegal on GCP gold) is enforced by
-- StorageBackendValidator server-side and legalLakeFormats() client-side. The
-- param stays tier=derived (resolved from pipeline.storage), so this seeds the
-- legal vocabulary without overriding the per-pipeline resolved value.
-- =============================================================================
UPDATE blueprints b
SET params_schema = (
    SELECT jsonb_agg(
        CASE
            WHEN param.elem ->> 'name' = 'lake_format' AND (param.elem -> 'options') IS NULL
                THEN param.elem || '{"options": ["delta", "iceberg_external", "iceberg_bq_managed", "bq_native", "parquet"]}'::jsonb
            ELSE param.elem
        END
        ORDER BY param.ord
    )
    FROM jsonb_array_elements(b.params_schema) WITH ORDINALITY AS param(elem, ord)
)
WHERE b.params_schema @> '[{"name": "lake_format"}]'::jsonb;

-- =============================================================================
-- LCT-030 — FileIngestion had no input port (input_ports=[]), so an upstream
-- source/context step could not be wired into it. Declare a single optional
-- upstream input port. The bronze output schema stays source/dataset-derived
-- (SchemaPropagationService treats read-source-rooted blueprints as ingestion
-- and does not consume this port for schema), so the port is a wiring affordance
-- that gives the node a left handle without changing emitted columns.
-- =============================================================================
UPDATE blueprints
SET input_ports = '[{"name": "upstream", "description": "Optional upstream relation wired into this ingestion step (e.g. a triggering source or prior-stage context). The bronze output schema remains source/dataset-derived."}]'::jsonb
WHERE blueprint_key = 'FileIngestion'
  AND (input_ports IS NULL OR input_ports = '[]'::jsonb);
