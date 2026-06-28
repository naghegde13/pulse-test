-- PULSE codegen example: DedupeAndMerge — silver staging model that
-- collapses bronze rows to one canonical record per business key,
-- choosing the latest by an explicit ORDER BY clause.
--
-- What this blueprint does (and what it does NOT):
--   - Per V93 audit: order_by_columns, partition_by, dedup_method are
--     first-class params. The agent SHOULD propose explicit values for
--     all three. dedup_method='ROW_NUMBER' is the safe default;
--     'RANK' returns ties (NOT what you want for current-state silver);
--     'DENSE_RANK' is rarely the right choice here.
--   - Distinct from BronzeToSilverCleaning (cast/trim/rename of column
--     VALUES within a row): DedupeAndMerge selects which ROW wins when
--     bronze has multiple records for the same business key.
--   - NO data quality rules — DQ is GX (DQValidator). This model
--     transforms only.
--
-- Architectural rules (locked):
--   - Tiebreaker column at the END of order_by_columns is REQUIRED.
--     Without it, ROW_NUMBER output is nondeterministic in Spark and
--     will silently produce different results across runs.
--   - ds-bound incremental against bronze; never full-table scan.
--   - All audit columns are silver-layer; bronze audit cols are dropped.

{{
    config(
        materialized='incremental',
        file_format='__LAKE_FORMAT__',
        partition_by=['ds'],
        unique_key=__PARTITION_BY_LIST__,
        on_schema_change='fail',
        incremental_strategy='merge',
        tags=['pulse', 'staging', 'dedupe']
    )
}}

{# V93 fields:                                                              #}
{#   partition_by      → ['loan_id']  (the business key)                    #}
{#   order_by_columns  → [{col: 'updated_at', dir: 'DESC'}, ...]            #}
{#   dedup_method      → 'ROW_NUMBER' | 'RANK' | 'DENSE_RANK'               #}
{%- set business_key_cols = __PARTITION_BY_LIST__ -%}
{%- set order_by_cols = __ORDER_BY_COLUMNS__ -%}
{%- set dedup_method = '__DEDUP_METHOD__' -%}

WITH bronze AS (
    SELECT
        *,
        _pulse_run_id          AS _bronze_run_id,
        _pulse_processing_ts   AS _bronze_ingested_at
    FROM {{ source('__SOURCE_SYSTEM__', '__BRONZE_TABLE__') }}
    WHERE ds = '{{ var("pulse_business_date") }}'
        {% if is_incremental() %}
            AND ds NOT IN (SELECT DISTINCT ds FROM {{ this }})
        {% endif %}
),

ranked AS (
    SELECT
        bronze.*,
        {{ dedup_method }}() OVER (
            PARTITION BY {{ business_key_cols | join(', ') }}
            ORDER BY
                {%- for ob in order_by_cols %}
                {{ ob.col }} {{ ob.dir }}
                    {%- if not loop.last %},{% endif %}
                {%- endfor %}
        ) AS _dedup_rank
    FROM bronze
),

deduped AS (
    SELECT * FROM ranked WHERE _dedup_rank = 1
)

SELECT
    *
    EXCEPT (_dedup_rank, _bronze_run_id, _bronze_ingested_at,
            _pulse_run_id, _pulse_processing_ts, _pulse_pipeline,
            _pulse_source, _pulse_business_date),
    '{{ var("pulse_business_date") }}'        AS _pulse_business_date,
    CURRENT_TIMESTAMP()                        AS _pulse_processing_ts,
    '{{ var("pulse_run_id") }}'                AS _pulse_run_id,
    '{{ this.identifier }}'                    AS _pulse_silver_model
FROM deduped
