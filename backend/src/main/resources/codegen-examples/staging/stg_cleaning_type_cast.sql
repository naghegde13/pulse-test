-- PULSE codegen example: BronzeToSilverCleaning — secondary example
-- showing the type-casting flavor of cleaning (the partner to
-- stg_cleaning_basic.sql which focuses on trim/rename/drop).
--
-- What this blueprint does (and what it does NOT):
--   - Applies a typed projection: each bronze column is explicitly cast
--     to the silver contract's type. The cast list is generated from
--     the schema_propagation registry at codegen time so this file's
--     CASE expressions stay in lock-step with the contract.
--   - SAFE_CAST equivalents: TRY_CAST in Spark SQL fails-soft (NULL on
--     parse failure). For cleaning we WANT this — bad rows fall to the
--     _pulse_corrupt_record column instead of failing the whole job.
--     Strict casting belongs in DQValidator's bronze→silver gate.
--   - No dedup, no business filter — that's DedupeAndMerge / GenericFilter.

{{
    config(
        materialized='incremental',
        file_format='__LAKE_FORMAT__',
        partition_by=['ds'],
        unique_key=['_silver_pk'],
        on_schema_change='fail',
        incremental_strategy='merge',
        tags=['pulse', 'staging', 'bronze_to_silver', 'type_cast']
    )
}}

{# Type cast list, JSON-substituted from the schema_propagation registry.    #}
{# Each entry: {column: 'amount', from: 'string', to: 'decimal(18,2)'}        #}
{%- set casts = __TYPE_CAST_LIST__ -%}

WITH bronze AS (
    SELECT *
    FROM {{ source('__SOURCE_SYSTEM__', '__BRONZE_TABLE__') }}
    WHERE ds = '{{ var("pulse_business_date") }}'
        {% if is_incremental() %}
            AND ds NOT IN (SELECT DISTINCT ds FROM {{ this }})
        {% endif %}
),

cast_attempts AS (
    SELECT
        -- PK column: cast to its silver type but enforce NOT NULL
        -- downstream — bronze should NEVER have a null PK; if it does,
        -- the bronze→silver DQValidator should have caught it. This
        -- model only applies the casts.
        TRY_CAST(__PK_COLUMN__ AS __PK_SILVER_TYPE__) AS _silver_pk,
        {%- for c in casts %}
        TRY_CAST({{ c.column }} AS {{ c.to }}) AS {{ c.column }}
            {%- if not loop.last %},{% endif %}
        {%- endfor %},
        -- Diagnostic columns: count how many casts produced NULL when
        -- the source value was non-null. If this is high, the source's
        -- type contract has drifted.
        ARRAY_REMOVE(ARRAY(
            {%- for c in casts %}
            CASE
                WHEN {{ c.column }} IS NOT NULL
                  AND TRY_CAST({{ c.column }} AS {{ c.to }}) IS NULL
                THEN '{{ c.column }}'
            END
            {%- if not loop.last %},{% endif %}
            {%- endfor %}
        ), NULL) AS _pulse_cast_failures,
        ds
    FROM bronze
)

SELECT
    *,
    '{{ var("pulse_business_date") }}'           AS _pulse_business_date,
    CURRENT_TIMESTAMP()                           AS _pulse_processing_ts,
    '{{ var("pulse_run_id") }}'                   AS _pulse_run_id,
    '{{ this.identifier }}'                       AS _pulse_silver_model,
    SIZE(_pulse_cast_failures) > 0                AS _pulse_has_cast_failure
FROM cast_attempts
