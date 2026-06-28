-- PULSE codegen example: GenericRouter — intermediate model that splits
-- one upstream model into multiple labeled output streams based on
-- predicate-to-route mappings.
--
-- What this blueprint does (and what it does NOT):
--   - Multi-output: each input row is tagged with exactly one route
--     label, then downstream models filter by that label. PULSE
--     codegen emits this single base model PLUS one passthrough view
--     per route (e.g., int_orders_routed_high_value, _routed_pending,
--     _routed_default). The agent SHOULD reference the route-specific
--     view downstream, not this base model directly.
--   - Different from GenericFilter: filter is one-output; rows that
--     don't match are dropped. Router preserves all rows, tagged.
--   - Routes are evaluated in DECLARED ORDER; first match wins. The
--     agent MUST end the route list with a 'default' that has no
--     predicate so every row gets a label.
--   - Idempotent: no ROW_NUMBER tiebreakers; predicates evaluate
--     deterministically.

{{
    config(
        materialized='incremental',
        file_format='__LAKE_FORMAT__',
        partition_by=['ds'],
        unique_key=__UNIQUE_KEY_LIST__,
        on_schema_change='fail',
        incremental_strategy='merge',
        tags=['pulse', 'intermediate', 'router']
    )
}}

{# routes: ordered list, last entry MUST be the default                    #}
{# [                                                                        #}
{#   {label: 'high_value', predicate: "amount >= 10000"},                   #}
{#   {label: 'pending',    predicate: "status = 'PENDING'"},                #}
{#   {label: 'default'},                                                    #}
{# ]                                                                         #}
{%- set routes = __ROUTE_RULES__ -%}

WITH upstream AS (
    SELECT *
    FROM {{ ref('__UPSTREAM_MODEL__') }}
    WHERE ds = '{{ var("pulse_business_date") }}'
        {% if is_incremental() %}
            AND ds NOT IN (SELECT DISTINCT ds FROM {{ this }})
        {% endif %}
)

SELECT
    *,
    CASE
        {%- for route in routes %}
        {%- if route.predicate is defined %}
        WHEN ({{ route.predicate }}) THEN '{{ route.label }}'
        {%- endif %}
        {%- endfor %}
        {%- for route in routes %}
        {%- if route.predicate is not defined %}
        ELSE '{{ route.label }}'
        {%- endif %}
        {%- endfor %}
    END                                          AS _pulse_route,
    '{{ var("pulse_business_date") }}'           AS _pulse_business_date,
    CURRENT_TIMESTAMP()                           AS _pulse_processing_ts,
    '{{ var("pulse_run_id") }}'                   AS _pulse_run_id
FROM upstream
