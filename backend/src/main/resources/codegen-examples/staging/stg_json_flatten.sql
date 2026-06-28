-- PULSE codegen example: JsonFlatten — silver staging model that explodes
-- nested JSON arrays into one row per element, and lifts struct fields
-- to top-level columns.
--
-- What this blueprint does (and what it does NOT):
--   - Two simultaneous transforms:
--       (1) explode an array column → one output row per array element
--       (2) hoist nested struct fields to top level via dot-path
--           (e.g., payload.address.zip → payload_address_zip)
--   - The OPPOSITE concern from JsonStruct (which builds nested structs
--     from flat columns). Despite both being "JSON-shaped", they share
--     no logic — that's why this file is dedicated, NOT a copy of
--     stg_cleaning_basic.sql.
--   - Output grain is the post-explode grain: ONE row per array
--     element. The original PK is no longer unique on its own;
--     codegen adds an index column (_array_index) to the unique_key.
--   - All hoisted columns get parent-name prefixed (parent_field) so
--     name collisions across nested structs don't silently shadow.
--
-- Performance notes:
--   - explode_outer (NOT explode) so rows with empty/null arrays are
--     preserved with NULL in the exploded column. explode would drop
--     them silently — and silent row drops are bugs.
--   - For deeply-nested structs (>3 levels), prefer multiple Flatten
--     stages chained, NOT one mega-flatten. Spark's Catalyst optimizer
--     handles staged flattens better.

{{
    config(
        materialized='incremental',
        file_format='__LAKE_FORMAT__',
        partition_by=['ds'],
        unique_key=__UNIQUE_KEY_LIST__,
        on_schema_change='fail',
        incremental_strategy='merge',
        tags=['pulse', 'staging', 'json_flatten']
    )
}}

{# array_to_explode: the array column whose elements become rows         #}
{# struct_paths: [                                                        #}
{#   {path: 'payload.address.zip',    alias: 'payload_address_zip'},      #}
{#   {path: 'payload.customer.email', alias: 'payload_customer_email'},   #}
{# ]                                                                        #}
{%- set array_col = '__ARRAY_TO_EXPLODE__' -%}
{%- set struct_paths = __STRUCT_PATHS__ -%}

WITH bronze AS (
    SELECT *
    FROM {{ source('__SOURCE_SYSTEM__', '__BRONZE_TABLE__') }}
    WHERE ds = '{{ var("pulse_business_date") }}'
        {% if is_incremental() %}
            AND ds NOT IN (SELECT DISTINCT ds FROM {{ this }})
        {% endif %}
),

exploded AS (
    SELECT
        *
        EXCEPT ({{ array_col }}),
        POSEXPLODE_OUTER({{ array_col }}) AS (_array_index, _array_element)
    FROM bronze
)

SELECT
    *
    {%- for sp in struct_paths %},
    {{ sp.path }}                                AS {{ sp.alias }}
    {%- endfor %},
    '{{ var("pulse_business_date") }}'           AS _pulse_business_date,
    CURRENT_TIMESTAMP()                           AS _pulse_processing_ts,
    '{{ var("pulse_run_id") }}'                   AS _pulse_run_id,
    '{{ this.identifier }}'                       AS _pulse_silver_model
FROM exploded
