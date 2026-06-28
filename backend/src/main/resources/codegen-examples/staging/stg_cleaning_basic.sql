-- PULSE codegen example: BronzeToSilverCleaning — bronze → silver staging
-- model that applies the cleaning operations declared in V93 params:
-- columns_to_trim (whitespace strip), rename_map (canonical naming),
-- drop_columns (drop source-of-record cruft), partition_by (silver layout).
--
-- What this blueprint does (and what it does NOT):
--   - dbt-native staging model with INLINE dbt tests (per #27 boundary).
--     The tests in the model's schema.yml are part of THIS blueprint;
--     PULSE does not delegate "trim/cast/rename" tests to GX.
--   - Type-casting and complex value scrubbing belong in
--     stg_cleaning_type_cast.sql — same blueprint, second example —
--     so this file stays focused on the trim/rename/drop/partition core.
--   - Does NOT enforce business invariants (e.g., balance ≥ 0). That's
--     DQValidator (silver_gold_gate.py).
--   - Does NOT apply dedup logic. That's DedupeAndMerge.
--
-- Convention notes:
--   - {{ source(...) }} resolves to the bronze Delta table; never
--     hard-code a path or a fully-qualified table name.
--   - {{ var('pulse_business_date') }} pulls the daily ds bound from
--     dbt run --vars; codegen always passes it.
--   - V93 fields: COLUMNS_TO_TRIM_LIST, RENAME_PAIRS, DROP_COLUMNS_LIST
--     are JSON-encoded at codegen time and templated into Jinja loops.

{{
    config(
        materialized='incremental',
        file_format='__LAKE_FORMAT__',
        partition_by=['ds'],
        unique_key=['_silver_pk'],
        on_schema_change='fail',
        incremental_strategy='merge',
        tags=['pulse', 'staging', 'bronze_to_silver']
    )
}}

{# -- V93 substitutions ----------------------------------------------- #}
{# columns_to_trim: ['name', 'email', 'address_line_1']                  #}
{%- set columns_to_trim = __COLUMNS_TO_TRIM_LIST__ -%}
{# rename_map: [{'from': 'cust_id', 'to': 'customer_id'}, ...]           #}
{%- set rename_pairs = __RENAME_PAIRS__ -%}
{# drop_columns: ['_pulse_corrupt_record', 'sor_internal_seq']           #}
{%- set drop_columns = __DROP_COLUMNS_LIST__ -%}
{# partition_by: ['ds']  (always at least 'ds' for medallion)            #}
{%- set partition_columns = __PARTITION_BY_LIST__ -%}

WITH bronze AS (
    SELECT *
    FROM {{ source('__SOURCE_SYSTEM__', '__BRONZE_TABLE__') }}
    -- Daily-incremental bound. Without this, a re-run would scan the
    -- full bronze history every night.
    WHERE ds = '{{ var("pulse_business_date") }}'
        {% if is_incremental() %}
            -- Defensive: if dbt re-runs this model after the silver
            -- partition exists, exclude it so we don't double-count
            -- audit columns.
            AND ds NOT IN (SELECT DISTINCT ds FROM {{ this }})
        {% endif %}
),

trimmed AS (
    SELECT
        *
        {%- for col in columns_to_trim -%}
        ,
        TRIM({{ col }}) AS {{ col }}
        {%- endfor %}
    FROM bronze
),

renamed AS (
    SELECT
        *
        {%- for pair in rename_pairs %}
        ,
        {{ pair.from }} AS {{ pair.to }}
        {%- endfor %}
    FROM trimmed
),

projected AS (
    SELECT
        *
        EXCEPT (
            {%- set drop_with_renames = drop_columns + (rename_pairs | map(attribute='from') | list) -%}
            {{ drop_with_renames | join(', ') }}
        )
    FROM renamed
)

SELECT
    -- _silver_pk is the de-aliased PK that downstream silver/gold rely on.
    -- The codegen layer fills in the real PK column name from blueprint
    -- params, OR uses the renamed counterpart if the PK is in rename_map.
    __PK_COLUMN_AFTER_RENAME__                  AS _silver_pk,
    *,
    -- Silver-layer audit columns. Bronze audit cols are dropped by the
    -- EXCEPT clause above; silver gets its own.
    '{{ var("pulse_business_date") }}'          AS _pulse_business_date,
    CURRENT_TIMESTAMP()                          AS _pulse_processing_ts,
    '{{ var("pulse_run_id") }}'                  AS _pulse_run_id,
    '{{ this.identifier }}'                      AS _pulse_silver_model
FROM projected
