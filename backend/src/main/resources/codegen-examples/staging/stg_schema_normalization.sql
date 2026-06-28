-- PULSE codegen example: SchemaNormalization — silver staging model that
-- conforms an upstream's column NAMES, ORDER, and TYPES to a registered
-- target contract. Distinct from BronzeToSilverCleaning's
-- trim/case-clean focus; this is purely about contract conformance.
--
-- What this blueprint does (and what it does NOT):
--   - For each column in the contract, project from the upstream
--     using a candidate-name list (try foo_id, then fooId, then ID),
--     cast to the contract type, and emit columns in the contract's
--     declared order.
--   - Fails CLOSED when a contract column has no matching upstream
--     column (the agent SHOULD propose adding the column to the
--     upstream's renamed projection or to the schema_propagation
--     override BEFORE running this model).
--   - Does NOT apply business rules — DQValidator's job.
--   - Does NOT mask, dedupe, or filter — separate blueprints.
--   - Does NOT rename UPSTREAM models; only this model's projection.
--
-- Why a dedicated example (not stg_cleaning_basic.sql):
--   - "Cleaning" and "schema normalization" share NO logic. Cleaning
--     trims/lowers/strips column VALUES. SchemaNormalization conforms
--     the column NAMES/TYPES/ORDER. Lumping them into one example
--     teaches the agent to mix concerns (#39 trust loss).

{{
    config(
        materialized='incremental',
        file_format='__LAKE_FORMAT__',
        partition_by=['ds'],
        unique_key=['_silver_pk'],
        on_schema_change='fail',
        incremental_strategy='merge',
        tags=['pulse', 'staging', 'schema_normalization']
    )
}}

{# target_contract: ordered list of contract columns                       #}
{# [                                                                        #}
{#   {contract_name: 'customer_id', type: 'bigint',                          #}
{#    candidates: ['cust_id', 'customerId', 'CUSTID']},                      #}
{#   {contract_name: 'event_ts', type: 'timestamp',                          #}
{#    candidates: ['event_ts', 'eventTs', 'event_timestamp']},               #}
{#   ...                                                                     #}
{# ]                                                                          #}
{# Codegen MUST resolve candidates against the upstream schema and          #}
{# substitute target_contract[i].matched_name = the name found.             #}
{%- set target_contract = __TARGET_CONTRACT__ -%}

WITH upstream AS (
    SELECT *
    FROM {{ ref('__UPSTREAM_MODEL__') }}
    WHERE ds = '{{ var("pulse_business_date") }}'
        {% if is_incremental() %}
            AND ds NOT IN (SELECT DISTINCT ds FROM {{ this }})
        {% endif %}
)

SELECT
    -- Ordered, typed projection from the contract.
    {%- for col in target_contract %}
    CAST({{ col.matched_name }} AS {{ col.type }})  AS {{ col.contract_name }}
        {%- if not loop.last %},{% endif %}
    {%- endfor %},
    -- _silver_pk: the contract's declared PK column AFTER renaming.
    -- Codegen substitutes the contract_name of the PK here.
    CAST(__PK_MATCHED_NAME__ AS __PK_TYPE__)         AS _silver_pk,
    '{{ var("pulse_business_date") }}'               AS _pulse_business_date,
    CURRENT_TIMESTAMP()                               AS _pulse_processing_ts,
    '{{ var("pulse_run_id") }}'                       AS _pulse_run_id,
    '{{ this.identifier }}'                           AS _pulse_silver_model
FROM upstream
