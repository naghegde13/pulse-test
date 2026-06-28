-- PULSE codegen example: AggregateMaterialization — gold-layer mart that
-- collapses a fact/silver model into a reduced grain (one row per group)
-- for executive/operational reporting.
--
-- What this blueprint does (and what it does NOT):
--   - GROUP BY-based reduction: the output grain is coarser than the
--     input. Time bucket + dimension(s) on the LHS, measure aggregates
--     on the RHS.
--   - Time bucket is mnemonic-aware via the V92 audit fields:
--     bucket_kind ('day'|'week'|'month'|'quarter'|'year'|'fiscal_*')
--     drives DATE_TRUNC OR a fiscal calendar lookup.
--   - Idempotent on bucket: re-running the same bucket overwrites
--     only that period's rows.
--   - Different from GenericAggregate: that one preserves grain (window
--     aggregates). AggregateMaterialization reduces grain (GROUP BY).
--   - Different from ReferenceDataPublish: this is for time-bucketed
--     facts; ReferenceDataPublish is for slowly-changing reference
--     dimensions (lookups, code tables).

{{
    config(
        materialized='incremental',
        file_format='__LAKE_FORMAT__',
        partition_by=['period_bucket'],
        unique_key=__UNIQUE_KEY_LIST__,
        on_schema_change='fail',
        incremental_strategy='merge',
        tags=['pulse', 'marts', 'aggregate']
    )
}}

{# V92 AggregateMaterialization fields:                                     #}
{#   bucket_column        → 'event_ts'  (the temporal column)                #}
{#   bucket_kind          → 'day'|'week'|'month'|'quarter'|'fiscal_quarter' #}
{#   group_dimensions     → ['region', 'product_category']                  #}
{#   measures             → [{col: 'amount', aggs: ['sum','avg','count']}]  #}
{#   distinct_dimensions  → ['customer_id']  (for COUNT DISTINCT)            #}
{%- set bucket_col = '__BUCKET_COLUMN__' -%}
{%- set bucket_kind = '__BUCKET_KIND__' -%}
{%- set group_dims = __GROUP_DIMENSIONS__ -%}
{%- set measures = __MEASURES__ -%}
{%- set distinct_dims = __DISTINCT_DIMENSIONS__ -%}

WITH upstream AS (
    SELECT *
    FROM {{ ref('__UPSTREAM_MODEL__') }}
    -- Bound by the CURRENT bucket. For monthly aggregates we still
    -- only re-aggregate THIS month; prior months are already final.
    WHERE ds >= '{{ var("pulse_bucket_start") }}'
      AND ds <= '{{ var("pulse_bucket_end") }}'
        {% if is_incremental() %}
            AND period_bucket NOT IN (SELECT DISTINCT period_bucket FROM {{ this }})
        {% endif %}
),

bucketed AS (
    SELECT
        {%- if bucket_kind in ['day', 'week', 'month', 'quarter', 'year'] %}
        DATE_TRUNC('{{ bucket_kind }}', {{ bucket_col }})    AS period_bucket,
        {%- elif bucket_kind == 'fiscal_quarter' %}
        -- Fiscal calendar lookup against the platform's date_dim, which
        -- already encodes the tenant's fiscal_offset_months.
        date_dim.fiscal_quarter_start                         AS period_bucket,
        {%- elif bucket_kind == 'fiscal_year' %}
        date_dim.fiscal_year_start                            AS period_bucket,
        {%- endif %}
        {%- for d in group_dims %},
        {{ d }}
        {%- endfor %},
        *
    FROM upstream
    {%- if bucket_kind in ['fiscal_quarter', 'fiscal_year'] %}
    LEFT JOIN {{ ref('date_dim') }} date_dim
      ON date_dim.date = CAST({{ bucket_col }} AS DATE)
    {%- endif %}
)

SELECT
    period_bucket
    {%- for d in group_dims %},
    {{ d }}
    {%- endfor %}
    {%- for m in measures %}
    {%- for agg in m.aggs %},
    {{ agg | upper }}({{ m.col }})           AS {{ m.col }}_{{ agg }}
    {%- endfor %}
    {%- endfor %}
    {%- for dd in distinct_dims %},
    COUNT(DISTINCT {{ dd }})                 AS {{ dd }}_distinct_count
    {%- endfor %},
    COUNT(*)                                  AS row_count,
    CURRENT_TIMESTAMP()                        AS _pulse_processing_ts,
    '{{ var("pulse_run_id") }}'                AS _pulse_run_id,
    '{{ this.identifier }}'                    AS _pulse_gold_model
FROM bucketed
GROUP BY period_bucket
    {%- for d in group_dims %},
    {{ d }}
    {%- endfor %}
