-- PULSE codegen example: WideDenormalizedMart — single-table BI-serving
-- mart that joins a fact with its dimensions and the date dimension
-- into one wide row per fact event.
--
-- What this blueprint does (and what it does NOT):
--   - Pure projection model: every column the BI tool needs lives on
--     one row. Optimized for Tableau/Looker/PowerBI — no nested
--     structs, no lookups, no joins required at query time.
--   - Idempotent on (fact_pk, ds): re-running rebuilds only today's
--     facts plus their dimension joins.
--   - Different from FactBuild: FactBuild stores surrogate keys.
--     WideDenormalizedMart resolves those surrogates back to
--     descriptive attributes for the BI consumer.
--   - Different from feature_table_publish: feature table is keyed
--     by entity for ML; this mart is keyed by event for BI.
--
-- Performance notes:
--   - Broadcast-hint the dimension joins. Dimensions are small enough
--     to fit in driver memory; broadcast joins avoid a shuffle on
--     the fact side and cut runtime by 5-10× on multi-billion-row facts.
--   - Materialize columnar (Delta default). Wide rows + columnar +
--     z-order on the high-cardinality filter columns give BI
--     sub-second response.

{{
    config(
        materialized='incremental',
        file_format='__LAKE_FORMAT__',
        partition_by=['ds'],
        unique_key=['fact_pk'],
        on_schema_change='fail',
        incremental_strategy='merge',
        tags=['pulse', 'marts', 'bi_serving']
    )
}}

{# joined_dimensions: ordered list                                          #}
{#   [{ref_model: 'dim_customer', join_key: 'customer_sk',                 #}
{#     columns: ['customer_name', 'customer_segment', 'customer_country']},#}
{#    {ref_model: 'dim_product',  join_key: 'product_sk',                  #}
{#     columns: ['product_name', 'product_category']}]                       #}
{# date_dim_columns: which date_dim columns to surface                      #}
{# z_order_cols: high-cardinality filter columns for Delta z-ordering       #}
{%- set joined_dims = __JOINED_DIMENSIONS__ -%}
{%- set date_dim_cols = __DATE_DIM_COLUMNS__ -%}

WITH fact AS (
    SELECT *
    FROM {{ ref('__FACT_MODEL__') }}
    WHERE ds = '{{ var("pulse_business_date") }}'
        {% if is_incremental() %}
            AND ds NOT IN (SELECT DISTINCT ds FROM {{ this }})
        {% endif %}
)

SELECT
    f.*
    {%- for jd in joined_dims %}
    {%- for col in jd.columns %},
    {{ loop.index0 }}_d_{{ loop.index0 }}.{{ col }} AS {{ jd.column_prefix }}_{{ col }}
    {%- endfor %}
    {%- endfor %}
    {%- for c in date_dim_cols %},
    dd.{{ c }}                                       AS date_{{ c }}
    {%- endfor %},
    '{{ var("pulse_business_date") }}'              AS _pulse_business_date,
    CURRENT_TIMESTAMP()                              AS _pulse_processing_ts,
    '{{ var("pulse_run_id") }}'                      AS _pulse_run_id,
    '{{ this.identifier }}'                          AS _pulse_gold_model
FROM fact f
{%- for jd in joined_dims %}
LEFT JOIN /*+ BROADCAST({{ loop.index0 }}_d_{{ loop.index0 }}) */
    {{ ref(jd.ref_model) }} {{ loop.index0 }}_d_{{ loop.index0 }}
  ON {{ loop.index0 }}_d_{{ loop.index0 }}.{{ jd.join_key }} = f.{{ jd.join_key }}
{%- endfor %}
LEFT JOIN /*+ BROADCAST(dd) */ {{ ref('date_dim') }} dd
  ON dd.date = CAST(f.event_ts AS DATE)
