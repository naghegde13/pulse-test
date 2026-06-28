-- PULSE codegen example: SnapshotModel — daily IMMUTABLE snapshot of a
-- dimension's CURRENT state. NOT an SCD2 (no version history).
--
-- What this blueprint does (and what it does NOT):
--   - Each ds partition holds a complete copy of the dimension's
--     current state at that point in time. Querying by ds gives a
--     point-in-time snapshot WITHOUT requiring an SCD2 valid_window
--     join.
--   - Different from SCD2Dimension: SCD2 stores ONE row per
--     (natural_key, valid_window) — efficient for "what was the value
--     at event_ts" queries on the fact side. SnapshotModel stores
--     N rows per natural_key (one per day), efficient for "give me
--     the entire state on 2026-04-22" queries.
--   - Use SnapshotModel when:
--       (a) the consumer wants the full state at a point in time
--           without joining to fact event_ts (e.g., regulatory
--           snapshots, daily reconciliation files);
--       (b) the dimension is small enough that daily-replication
--           cost is acceptable;
--       (c) downstream queries are AS-OF the END of a day, not
--           AS-OF a specific event_ts mid-day.
--   - Don't use SnapshotModel when:
--       - the dimension is large (>100M rows) — daily-replication is
--         prohibitively expensive;
--       - facts need AS-OF-event_ts dim values (use SCD2 instead);
--       - storage budget cannot absorb O(daily * full_dim_size).
--
-- Why a dedicated example (not snp_timestamp_strategy.sql):
--   - SnapshotModel is NOT a dbt {% snapshot %} block — that
--     materialization is reserved for SCD2. SnapshotModel is a normal
--     incremental {{ config }} model with ds partitioning. They share
--     no logic.

{{
    config(
        materialized='incremental',
        file_format='__LAKE_FORMAT__',
        partition_by=['ds'],
        unique_key=['__NATURAL_KEY__', 'ds'],
        on_schema_change='fail',
        incremental_strategy='merge',
        tags=['pulse', 'snapshots', 'point_in_time']
    )
}}

{# columns_to_capture: ordered list — codegen substitutes the silver       #}
{# dimension's projection.                                                 #}
{%- set columns_to_capture = __COLUMNS_TO_CAPTURE__ -%}

WITH today AS (
    SELECT *
    FROM {{ ref('__SILVER_DIMENSION_MODEL__') }}
    WHERE ds = '{{ var("pulse_business_date") }}'
)

SELECT
    __NATURAL_KEY__,
    -- Surrogate key — deterministic, stable across re-snapshots.
    SHA2(CAST(__NATURAL_KEY__ AS STRING), 256)   AS __ENTITY___sk,
    {%- for c in columns_to_capture %}
    {{ c }},
    {%- endfor %}
    DATE('{{ var("pulse_business_date") }}')     AS ds,
    CURRENT_TIMESTAMP()                           AS _pulse_processing_ts,
    '{{ var("pulse_run_id") }}'                   AS _pulse_run_id,
    '{{ this.identifier }}'                       AS _pulse_snapshot_model
FROM today
