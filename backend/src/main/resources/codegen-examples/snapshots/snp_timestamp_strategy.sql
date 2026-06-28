-- PULSE codegen example: SCD2Dimension — primary example using the
-- TIMESTAMP strategy. The source carries an updated_at column we trust
-- to advance whenever any tracked attribute changes.
--
-- What this blueprint does (and what it does NOT):
--   - Track the FULL history of a dimension entity (customer, product,
--     account). One row per (natural_key, valid_window). Joining a
--     fact to this snapshot at the fact's event_ts gives the
--     dimension state AS-OF that event.
--   - timestamp strategy is the SAFE default — works whenever the
--     source has a reliable updated_at. Falls back to check strategy
--     (snp_check_strategy.sql) when no such column exists or is
--     unreliable.
--   - invalidate_hard_deletes=True: when a row disappears from the
--     source, dbt closes its open valid_window with dbt_valid_to set
--     to the current run timestamp. This is critical for accuracy in
--     downstream "as-of" joins; without it, hard-deleted entities
--     remain "current" in dbt_valid_to=NULL.
--   - Different from SnapshotModel: SCD2 keeps version history. The
--     SnapshotModel blueprint takes a daily IMMUTABLE snapshot of the
--     dimension's CURRENT state — used when the consumer doesn't need
--     time-travel and operational queries want zero-join lookups.
--
-- Architectural rules:
--   - target_schema='snapshots' is the locked dbt convention. NEVER
--     write SCD2 snapshots to the marts schema.
--   - file_format='__LAKE_FORMAT__' to give downstream models efficient
--     time-travel reads.

{% snapshot snp___ENTITY___timestamp %}

{{
    config(
        target_schema='snapshots',
        file_format='__LAKE_FORMAT__',
        unique_key='__NATURAL_KEY__',
        strategy='timestamp',
        updated_at='__UPDATED_AT_COLUMN__',
        invalidate_hard_deletes=True,
        tags=['pulse', 'snapshots', 'scd2', 'timestamp']
    )
}}

WITH source_snapshot AS (
    -- Source MUST be the silver dim model (post-cleaning), not bronze.
    -- Snapshotting bronze locks us into source-system column names and
    -- types, which breaks when the source schema drifts.
    SELECT
        __NATURAL_KEY__,
        __UPDATED_AT_COLUMN__,
        -- Surrogate dimension key — deterministic from natural key.
        -- Stable across snapshot rebuilds; never use a sequence.
        SHA2(CAST(__NATURAL_KEY__ AS STRING), 256) AS __ENTITY___sk,
        *
    FROM {{ ref('__SILVER_DIMENSION_MODEL__') }}
    -- ds-bound: snapshot only sees today's silver state.
    WHERE ds = '{{ var("pulse_business_date") }}'
)

SELECT
    *,
    -- Source-system audit kept on every version row for forensic queries.
    '{{ var("pulse_business_date") }}'        AS _source_business_date,
    '{{ var("pulse_run_id") }}'                AS _source_run_id
FROM source_snapshot

{% endsnapshot %}
