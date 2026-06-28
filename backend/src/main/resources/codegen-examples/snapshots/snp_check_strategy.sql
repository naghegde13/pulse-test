-- PULSE codegen example: SCD2Dimension — secondary example using the
-- CHECK strategy. Use when the source has no reliable updated_at
-- column but you can name the columns whose changes should trigger
-- a new SCD2 version.
--
-- What this blueprint does (and what it does NOT):
--   - dbt's check strategy hashes the named tracked columns on each
--     run; when the hash differs from the prior version, a new
--     dbt_valid_from/_to row is emitted. Slower than timestamp (full
--     row hash on every run) but correct when no usable update column
--     exists.
--   - tracked_columns is the explicit declaration of which attribute
--     changes constitute a new "version" — the agent SHOULD think
--     hard about this list. Including audit columns
--     (_pulse_processing_ts) would create a new version on EVERY run,
--     defeating SCD2's purpose. Excluding business-meaningful columns
--     (status, tier) silently misses real changes.
--   - invalidate_hard_deletes=True: same rationale as the timestamp
--     strategy.
--   - When you have a usable updated_at column, prefer the timestamp
--     variant (snp_timestamp_strategy.sql) — it scales to wider rows
--     because it skips the row-hash on the fast path.

{% snapshot snp___ENTITY___check %}

{{
    config(
        target_schema='snapshots',
        file_format='__LAKE_FORMAT__',
        unique_key='__NATURAL_KEY__',
        strategy='check',
        check_cols=__TRACKED_COLUMNS_LIST__,
        invalidate_hard_deletes=True,
        tags=['pulse', 'snapshots', 'scd2', 'check']
    )
}}

WITH source_snapshot AS (
    SELECT
        __NATURAL_KEY__,
        SHA2(CAST(__NATURAL_KEY__ AS STRING), 256) AS __ENTITY___sk,
        *
    FROM {{ ref('__SILVER_DIMENSION_MODEL__') }}
    WHERE ds = '{{ var("pulse_business_date") }}'
)

SELECT
    *,
    '{{ var("pulse_business_date") }}'        AS _source_business_date,
    '{{ var("pulse_run_id") }}'                AS _source_run_id
FROM source_snapshot

{% endsnapshot %}
