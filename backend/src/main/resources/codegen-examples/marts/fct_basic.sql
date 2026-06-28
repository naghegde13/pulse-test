-- PULSE codegen example: FactBuild — gold-layer fact table built from a
-- silver event stream. Per-event grain (NOT pre-aggregated).
--
-- What this blueprint does (and what it does NOT):
--   - Materialize one row per business event with foreign keys to the
--     dimensional model (customer_sk, product_sk, date_sk). The FKs
--     are SURROGATE keys looked up against the SCD2 dimensions, NOT
--     the natural keys carried in silver.
--   - Idempotent on ds: re-running today's slice overwrites only
--     today's partition (Delta replaceWhere via merge unique_key).
--   - Different from AggregateMaterialization: fact preserves the
--     event grain. AggregateMaterialization collapses it.
--   - Different from FeatureTablePublish: feature table is keyed by
--     entity (customer_id) for ML feature stores; fact is keyed by
--     event for analytical aggregation.
--   - Different from fct_incremental_late_arriving: this base example
--     assumes dimensions resolved at fact-build time. Late-arriving
--     dim handling has its own model.

{{
    config(
        materialized='incremental',
        file_format='__LAKE_FORMAT__',
        partition_by=['ds'],
        unique_key=['fact_pk'],
        on_schema_change='fail',
        incremental_strategy='merge',
        tags=['pulse', 'marts', 'fact']
    )
}}

{# Fact build params:                                                       #}
{#   event_source_model   → silver event model                              #}
{#   event_pk             → composite or single PK from silver              #}
{#   measures             → ['amount', 'quantity']                          #}
{#   dimension_lookups    → [                                                #}
{#     {dim_model: 'dim_customer', natural_key: 'customer_id',               #}
{#      surrogate_alias: 'customer_sk'},                                     #}
{#     {dim_model: 'dim_product',  natural_key: 'product_id',                #}
{#      surrogate_alias: 'product_sk'},                                      #}
{#   ]                                                                        #}
{%- set measures = __FACT_MEASURES__ -%}
{%- set dim_lookups = __DIMENSION_LOOKUPS__ -%}

WITH events AS (
    SELECT *
    FROM {{ ref('__EVENT_SOURCE_MODEL__') }}
    WHERE ds = '{{ var("pulse_business_date") }}'
        {% if is_incremental() %}
            AND ds NOT IN (SELECT DISTINCT ds FROM {{ this }})
        {% endif %}
),

resolved AS (
    SELECT
        e.*
        {%- for dl in dim_lookups %},
        {{ loop.index }}_dim.{{ dl.surrogate_alias }} AS {{ dl.surrogate_alias }}
        {%- endfor %}
    FROM events e
    {%- for dl in dim_lookups %}
    LEFT JOIN {{ ref(dl.dim_model) }} {{ loop.index }}_dim
      ON {{ loop.index }}_dim.{{ dl.natural_key }} = e.{{ dl.natural_key }}
      -- SCD2 dim lookup: only the row valid at the event_ts.
      AND e.event_ts >= {{ loop.index }}_dim.dbt_valid_from
      AND (e.event_ts <  {{ loop.index }}_dim.dbt_valid_to
           OR {{ loop.index }}_dim.dbt_valid_to IS NULL)
    {%- endfor %}
),

unmatched AS (
    -- Surface fact rows whose dim lookups didn't resolve. Per the
    -- pipeline's late-arriving policy, the agent SHOULD propose
    -- routing these rows to fct_incremental_late_arriving for
    -- retry-on-next-run, NOT silently emitting NULLs into prod gold.
    SELECT *
    FROM resolved
    WHERE
    {%- for dl in dim_lookups %}
    {%- if not loop.first %} OR {% endif -%}
    {{ dl.surrogate_alias }} IS NULL
    {%- endfor %}
)

SELECT
    -- fact_pk is a deterministic surrogate built from the event_pk +
    -- ds, suitable for incremental MERGE. Avoid GUIDs — they break
    -- idempotent re-runs.
    SHA2(CONCAT_WS('|', __EVENT_PK_EXPRESSION__,
                       '{{ var("pulse_business_date") }}'), 256)
                                                  AS fact_pk,
    -- Dimension surrogate keys.
    {%- for dl in dim_lookups %}
    {{ dl.surrogate_alias }},
    {%- endfor %}
    -- Date surrogate from the platform date_dim.
    DATE_FORMAT(event_ts, 'yyyyMMdd')             AS date_sk,
    -- Measures.
    {%- for m in measures %}
    {{ m }},
    {%- endfor %}
    event_ts,
    ds,
    -- Audit columns.
    '{{ var("pulse_business_date") }}'           AS _pulse_business_date,
    CURRENT_TIMESTAMP()                           AS _pulse_processing_ts,
    '{{ var("pulse_run_id") }}'                   AS _pulse_run_id,
    '{{ this.identifier }}'                       AS _pulse_gold_model
FROM resolved
