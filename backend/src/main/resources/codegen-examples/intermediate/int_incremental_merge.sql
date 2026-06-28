-- PULSE codegen example: IncrementalMerge — silver/intermediate model
-- that MERGES new/changed rows from upstream into the materialized
-- table by business PK.
--
-- What this blueprint does (and what it does NOT):
--   - Two-phase incremental:
--       (a) On first run (full-refresh), reads all upstream and
--           writes the table.
--       (b) On subsequent runs, reads only rows where the
--           watermark column has advanced since the prior max,
--           then MERGE-merges them into {{ this }}.
--   - Update vs. insert: matched rows are UPDATED (the upstream
--     value wins), unmatched rows are INSERTED. No deletes — the
--     blueprint is for additive/upsert flows. Hard-delete handling
--     belongs in SCD2Dimension (invalidate_hard_deletes).
--   - Different from DedupeAndMerge: DedupeAndMerge takes one day's
--     bronze and emits one canonical row per business key for
--     today's silver. IncrementalMerge takes today's delta of
--     upstream and merges it into a long-running materialized table.
--   - Different from SCD2Dimension: SCD2 keeps history. This blueprint
--     keeps only the LATEST version per PK.
--
-- Architectural rules:
--   - on_schema_change='fail' — silent column adds in incremental
--     models cause silent NULLs. The agent MUST update the
--     contract registry first (Agent E), then this model picks up
--     the new column.
--   - The watermark column MUST be monotonic. Wall-clock event_ts
--     is fine; "last modified" with a 30-second clock skew is fine
--     for daily; sub-second-skew watermarks need a tiebreaker.

{{
    config(
        materialized='incremental',
        file_format='__LAKE_FORMAT__',
        unique_key='__PK_COLUMN__',
        on_schema_change='fail',
        incremental_strategy='merge',
        tags=['pulse', 'intermediate', 'merge']
    )
}}

{# watermark_column   → 'updated_at' | 'event_ts'                           #}
{# watermark_default  → '1970-01-01 00:00:00' (initial run lower bound)     #}
{%- set watermark_col = '__WATERMARK_COLUMN__' -%}
{%- set watermark_default = '__WATERMARK_DEFAULT__' -%}

WITH delta AS (
    SELECT *
    FROM {{ ref('__UPSTREAM_MODEL__') }}
    {% if is_incremental() %}
    -- Watermark advance: only rows newer than the prior max.
    -- COALESCE on watermark_default guards the empty-table case.
    WHERE {{ watermark_col }} > (
        SELECT COALESCE(MAX({{ watermark_col }}),
                        TIMESTAMP '{{ watermark_default }}')
        FROM {{ this }}
    )
    {% endif %}
),

-- Final-write tiebreaker: if the same PK appears more than once in
-- the delta (e.g., source emitted multiple updates within one window),
-- keep the version with the newest watermark. Without this, MERGE
-- would non-deterministically pick a winner.
deduped_delta AS (
    SELECT *
    FROM (
        SELECT
            *,
            ROW_NUMBER() OVER (
                PARTITION BY __PK_COLUMN__
                ORDER BY {{ watermark_col }} DESC, __PK_COLUMN__ DESC
            ) AS _delta_rank
        FROM delta
    )
    WHERE _delta_rank = 1
)

SELECT
    * EXCEPT (_delta_rank),
    '{{ var("pulse_business_date") }}'        AS _pulse_business_date,
    CURRENT_TIMESTAMP()                        AS _pulse_processing_ts,
    '{{ var("pulse_run_id") }}'                AS _pulse_run_id,
    '{{ this.identifier }}'                    AS _pulse_silver_model
FROM deduped_delta
