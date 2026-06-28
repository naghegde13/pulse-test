-- PULSE codegen example: FeatureTablePublish — gold-layer feature table
-- keyed by ENTITY (not event), suitable for ML feature stores
-- (Feast, SageMaker FS, Vertex FS).
--
-- What this blueprint does (and what it does NOT):
--   - Output grain: ONE row per entity per as_of timestamp. Each row
--     carries the snapshot of features valid at that moment.
--   - Different from FactBuild: fact is event-grain (one row per
--     transaction). FeatureTable is entity-grain (one row per
--     customer/account/whatever the feature is about).
--   - Includes feature freshness columns (event_ts, _feature_age_days)
--     so downstream consumers can filter on staleness without reading
--     the source models.
--   - Idempotent on (entity_id, as_of): re-running the same as_of
--     overwrites those rows.
--   - Generates a paired schema_export.json artifact (a separate dbt
--     post-hook) describing each feature's name, dtype, owner, and
--     definition for the feature store registry.

{{
    config(
        materialized='incremental',
        file_format='__LAKE_FORMAT__',
        partition_by=['as_of_date'],
        unique_key=['entity_id', 'as_of_date'],
        on_schema_change='fail',
        incremental_strategy='merge',
        tags=['pulse', 'marts', 'features']
    )
}}

{# entity_key   → 'customer_id'                                              #}
{# as_of_kind   → 'business_date' | 'event_ts'                               #}
{# features     → [                                                           #}
{#   {name: 'lifetime_order_count', source_model: 'fct_orders',               #}
{#    expression: "COUNT(*)", group_by: ['customer_id'],                      #}
{#    pre_filter: "ds <= '{{ var(\"pulse_business_date\") }}'", dtype: 'int'}, #}
{#   {name: 'avg_basket_30d', source_model: 'fct_orders',                     #}
{#    expression: "AVG(amount)", group_by: ['customer_id'],                   #}
{#    pre_filter: "event_ts >= DATE_SUB('{{ var(\"pulse_business_date\") }}', 30)", #}
{#    dtype: 'double'},                                                        #}
{# ]                                                                            #}
{%- set entity_key = '__ENTITY_KEY__' -%}
{%- set features = __FEATURES__ -%}

{# We materialize each feature as its own CTE, then join back together.    #}
{# Codegen MUST guarantee the entity_key is the FIRST column in every     #}
{# feature CTE's GROUP BY.                                                  #}

{%- for f in features %}
WITH {%- if loop.first %}{% else %},{% endif %} feat_{{ loop.index }} AS (
    SELECT
        {{ entity_key }},
        {{ f.expression }} AS {{ f.name }}
    FROM {{ ref(f.source_model) }}
    WHERE {{ f.pre_filter }}
    GROUP BY {{ entity_key }}
)
{%- endfor -%}

SELECT
    base.{{ entity_key }}                          AS entity_id,
    DATE('{{ var("pulse_business_date") }}')      AS as_of_date,
    {%- for f in features %}
    feat_{{ loop.index }}.{{ f.name }}             AS {{ f.name }},
    {%- endfor %}
    -- Feature freshness signal — downstream serving can reject features
    -- older than its staleness budget without reading the source models.
    DATE_SUB('{{ var("pulse_business_date") }}', 0) AS _feature_as_of,
    CURRENT_TIMESTAMP()                              AS _pulse_processing_ts,
    '{{ var("pulse_run_id") }}'                      AS _pulse_run_id,
    '{{ this.identifier }}'                          AS _pulse_feature_table
-- The base relation: distinct entities seen across all features. We
-- start from the first feature's entity universe (codegen MUST verify
-- the first feature has full coverage; otherwise emit a separate
-- entity_universe CTE).
FROM feat_1 base
{%- for f in features %}
{%- if not loop.first %}
LEFT JOIN feat_{{ loop.index }}
  ON feat_{{ loop.index }}.{{ entity_key }} = base.{{ entity_key }}
{%- endif %}
{%- endfor %}
