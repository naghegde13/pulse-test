-- PULSE codegen example: JsonStruct — silver staging model that COMPOSES
-- nested struct columns from flat upstream columns. The OPPOSITE
-- transform from JsonFlatten.
--
-- What this blueprint does (and what it does NOT):
--   - Build named_struct(...) columns from a declared field map. Common
--     use case: gold-feeding silvers where downstream marts expect a
--     struct shape per the published schema (REST APIs, JSON exports,
--     downstream services that hydrate from Iceberg).
--   - Fields can come from any upstream column or a literal expression.
--   - Optionally TO_JSON the resulting struct column for sinks that
--     want a JSON string (e.g., Postgres jsonb, BigQuery JSON).
--   - Does NOT roll up across rows. For "build struct from a group of
--     rows", use AggregateMaterialization with collect_list inside a
--     transform_keys.
--
-- Boundary note:
--   - Struct columns are an anti-pattern for analytical exploration
--     (BI tools struggle with them). Prefer JsonStruct only on the
--     boundary models that feed an external consumer; keep internal
--     silver/gold flat.

{{
    config(
        materialized='incremental',
        file_format='__LAKE_FORMAT__',
        partition_by=['ds'],
        unique_key=__UNIQUE_KEY_LIST__,
        on_schema_change='fail',
        incremental_strategy='merge',
        tags=['pulse', 'staging', 'json_struct']
    )
}}

{# struct_specs: [                                                          #}
{#   {alias: 'address', as_json: false, fields: [                            #}
{#     {key: 'street', expr: 'address_line_1'},                              #}
{#     {key: 'city',   expr: 'city'},                                        #}
{#     {key: 'zip',    expr: 'postal_code'},                                 #}
{#   ]},                                                                      #}
{#   {alias: 'customer_payload', as_json: true, fields: [...]},               #}
{# ]                                                                          #}
{%- set struct_specs = __STRUCT_SPECS__ -%}
{%- set drop_inputs = __DROP_INPUT_COLUMNS__ -%}

WITH upstream AS (
    SELECT *
    FROM {{ ref('__UPSTREAM_MODEL__') }}
    WHERE ds = '{{ var("pulse_business_date") }}'
        {% if is_incremental() %}
            AND ds NOT IN (SELECT DISTINCT ds FROM {{ this }})
        {% endif %}
),

structured AS (
    SELECT
        *
        {%- for spec in struct_specs %},
        {%- if spec.as_json %}
        TO_JSON(NAMED_STRUCT(
            {%- for f in spec.fields %}
            '{{ f.key }}', {{ f.expr }}
            {%- if not loop.last %},{% endif %}
            {%- endfor %}
        ))                                       AS {{ spec.alias }}
        {%- else %}
        NAMED_STRUCT(
            {%- for f in spec.fields %}
            '{{ f.key }}', {{ f.expr }}
            {%- if not loop.last %},{% endif %}
            {%- endfor %}
        )                                        AS {{ spec.alias }}
        {%- endif %}
        {%- endfor %}
    FROM upstream
)

SELECT
    *
    EXCEPT ({{ drop_inputs | join(', ') }}),
    '{{ var("pulse_business_date") }}'           AS _pulse_business_date,
    CURRENT_TIMESTAMP()                           AS _pulse_processing_ts,
    '{{ var("pulse_run_id") }}'                   AS _pulse_run_id,
    '{{ this.identifier }}'                       AS _pulse_silver_model
FROM structured
