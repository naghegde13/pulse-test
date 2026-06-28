-- PULSE codegen example: GenericAggregate — window-aggregate intermediate
-- that adds rolling/cumulative metrics ONTO an upstream silver model
-- without collapsing rows.
--
-- What this blueprint does (and what it does NOT):
--   - Window-aggregate (NOT GROUP BY): the row count is preserved.
--     Each input row gets one or more aggregate columns derived from
--     a ROWS-BETWEEN frame.
--   - Different from AggregateMaterialization: that one materializes a
--     reduced gold-layer table (one row per group). GenericAggregate
--     enriches the existing grain.
--   - Frame is RANGE-BETWEEN aware: the agent SHOULD propose a
--     timestamp-based RANGE BETWEEN INTERVAL '7' DAYS PRECEDING for
--     temporal windows (handles missing days correctly), and
--     ROWS-BETWEEN for sequence-based windows.
--   - Bound by ds — full-history rolling windows are expensive on
--     large silvers; codegen splits into a "history" snapshot CTE
--     plus a "today" delta when the lookback exceeds the partition.

{{
    config(
        materialized='incremental',
        file_format='__LAKE_FORMAT__',
        partition_by=['ds'],
        unique_key=__UNIQUE_KEY_LIST__,
        on_schema_change='fail',
        incremental_strategy='merge',
        tags=['pulse', 'intermediate', 'aggregate']
    )
}}

{# V92 GenericAggregate fields:                                              #}
{#   partition_columns       → ['account_id']                                #}
{#   order_column            → 'event_ts'                                    #}
{#   measure_column          → 'amount'                                      #}
{#   window_kind             → 'ROWS' | 'RANGE'                              #}
{#   window_size             → integer (rows) or interval (days)             #}
{#   aggregations            → ['avg', 'sum', 'min', 'max', 'count']         #}
{%- set partition_cols = __PARTITION_COLUMNS_LIST__ -%}
{%- set order_col = '__ORDER_COLUMN__' -%}
{%- set measure_col = '__MEASURE_COLUMN__' -%}
{%- set window_kind = '__WINDOW_KIND__' -%}
{%- set window_size = '__WINDOW_SIZE__' -%}
{%- set aggs = __AGGREGATIONS_LIST__ -%}

WITH upstream AS (
    SELECT *
    FROM {{ ref('__UPSTREAM_MODEL__') }}
    -- Lookback: window_size of history is needed for the frame to be
    -- correct. We pull (lookback) days of context plus today.
    WHERE ds >= DATE_SUB('{{ var("pulse_business_date") }}', __LOOKBACK_DAYS__)
      AND ds <= '{{ var("pulse_business_date") }}'
)

SELECT
    *
    {%- for agg in aggs %},
    {{ agg | upper }}({{ measure_col }}) OVER (
        PARTITION BY {{ partition_cols | join(', ') }}
        ORDER BY {{ order_col }}
        {% if window_kind == 'RANGE' -%}
        RANGE BETWEEN INTERVAL '{{ window_size }}' DAY PRECEDING AND CURRENT ROW
        {%- else -%}
        ROWS BETWEEN {{ window_size }} PRECEDING AND CURRENT ROW
        {%- endif %}
    ) AS {{ measure_col }}_{{ agg }}_{{ window_size }}
    {%- endfor %}
    -- Cumulative-from-start variant. Common ask in financial pipelines
    -- ("running balance from account open"); always include if measure
    -- is a numeric flow.
    ,
    SUM({{ measure_col }}) OVER (
        PARTITION BY {{ partition_cols | join(', ') }}
        ORDER BY {{ order_col }}
        ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
    ) AS {{ measure_col }}_cumulative_sum,
    '{{ var("pulse_business_date") }}'        AS _pulse_business_date,
    CURRENT_TIMESTAMP()                        AS _pulse_processing_ts,
    '{{ var("pulse_run_id") }}'                AS _pulse_run_id
FROM upstream
-- Final filter: only emit rows for today's ds. The lookback rows above
-- were context for the window; we don't re-write them.
WHERE ds = '{{ var("pulse_business_date") }}'
