-- PULSE codegen example: PIIMasking — silver staging model that applies
-- per-column masking strategies declared in blueprint params. The
-- masked columns shadow the originals; the originals are dropped or
-- preserved into a separate vault table depending on the strategy.
--
-- What this blueprint does (and what it does NOT):
--   - Apply ONE of three masking strategies per PII column:
--       SHA256_HASH   → SHA2(col, 256). Joinable across pipelines via
--                       same hash. NOT reversible. Safe default for
--                       analytical use (deduplication, joins).
--       FORMAT_PRESERVING → first/last char + asterisks; preserves
--                           length so downstream UI can render it.
--                           NOT reversible. Use for display-only
--                           columns (full_name, address line).
--       TOKENIZED     → opaque token via tokenization service; the
--                       reversible mapping lives in a vault. The agent
--                       SHOULD only propose this when the use case
--                       requires unmasking under controlled access.
--   - Apply REGEX masking for free-text columns (email, phone, SSN)
--     before hashing — a SHA of "john.smith@acme.com" and "
--     john.smith@acme.com " differ only in whitespace; normalize first.
--   - Does NOT bypass cleaning. Run BronzeToSilverCleaning UPSTREAM so
--     PIIMasking sees consistent input.
--   - Does NOT apply DLP-class detection. The user must declare which
--     columns are PII; the agent does not infer.
--
-- Vault note:
--   - Reversible columns are written to a parallel vault table in a
--     restricted bucket. The codegen layer expands a second model when
--     vault_columns is non-empty.

{{
    config(
        materialized='incremental',
        file_format='__LAKE_FORMAT__',
        partition_by=['ds'],
        unique_key=['_silver_pk'],
        on_schema_change='fail',
        incremental_strategy='merge',
        tags=['pulse', 'staging', 'pii']
    )
}}

{# masking_rules: [                                                         #}
{#   {col: 'email', strategy: 'SHA256_HASH', normalize: 'LOWER_TRIM'},      #}
{#   {col: 'phone_number', strategy: 'SHA256_HASH', normalize: 'DIGITS_ONLY'},  #}
{#   {col: 'full_name', strategy: 'FORMAT_PRESERVING'},                     #}
{# ]                                                                          #}
{%- set masking_rules = __MASKING_RULES__ -%}
{# columns to drop entirely from the silver projection (PII never        #}
{# masked because we don't need it analytically at all)                  #}
{%- set drop_columns = __PII_DROP_COLUMNS__ -%}

WITH bronze AS (
    SELECT *
    FROM {{ source('__SOURCE_SYSTEM__', '__BRONZE_TABLE__') }}
    WHERE ds = '{{ var("pulse_business_date") }}'
        {% if is_incremental() %}
            AND ds NOT IN (SELECT DISTINCT ds FROM {{ this }})
        {% endif %}
),

normalized AS (
    SELECT
        *
        {%- for rule in masking_rules %}
        ,
        {%- if rule.normalize == 'LOWER_TRIM' %}
        LOWER(TRIM({{ rule.col }}))                AS _norm_{{ rule.col }}
        {%- elif rule.normalize == 'DIGITS_ONLY' %}
        REGEXP_REPLACE({{ rule.col }}, '[^0-9]', '') AS _norm_{{ rule.col }}
        {%- elif rule.normalize == 'UPPER_TRIM' %}
        UPPER(TRIM({{ rule.col }}))                AS _norm_{{ rule.col }}
        {%- else %}
        {{ rule.col }}                              AS _norm_{{ rule.col }}
        {%- endif %}
        {%- endfor %}
    FROM bronze
),

masked AS (
    SELECT
        *
        {%- for rule in masking_rules %}
        ,
        {%- if rule.strategy == 'SHA256_HASH' %}
        CASE
            WHEN _norm_{{ rule.col }} IS NULL OR _norm_{{ rule.col }} = ''
                THEN NULL
            ELSE SHA2(_norm_{{ rule.col }}, 256)
        END                                          AS {{ rule.col }}_hash
        {%- elif rule.strategy == 'FORMAT_PRESERVING' %}
        CASE
            WHEN {{ rule.col }} IS NULL THEN NULL
            WHEN LENGTH({{ rule.col }}) <= 2 THEN REPEAT('*', LENGTH({{ rule.col }}))
            ELSE CONCAT(
                SUBSTRING({{ rule.col }}, 1, 1),
                REPEAT('*', LENGTH({{ rule.col }}) - 2),
                SUBSTRING({{ rule.col }}, LENGTH({{ rule.col }}), 1)
            )
        END                                          AS {{ rule.col }}_masked
        {%- elif rule.strategy == 'TOKENIZED' %}
        -- TOKENIZED strategy uses a UDF wired to the tokenization vault.
        -- The UDF (pulse_tokenize) is registered at session bootstrap.
        pulse_tokenize('{{ rule.col }}', _norm_{{ rule.col }}) AS {{ rule.col }}_token
        {%- endif %}
        {%- endfor %}
    FROM normalized
)

SELECT
    __PK_COLUMN__                                AS _silver_pk,
    *
    EXCEPT (
        {%- set raw_pii = masking_rules | map(attribute='col') | list -%}
        {%- set normalized_pii = ['_norm_' + c for c in raw_pii] -%}
        {%- set all_drops = raw_pii + normalized_pii + drop_columns -%}
        {{ all_drops | join(', ') }}
    ),
    '{{ var("pulse_business_date") }}'           AS _pulse_business_date,
    CURRENT_TIMESTAMP()                           AS _pulse_processing_ts,
    '{{ var("pulse_run_id") }}'                   AS _pulse_run_id,
    '{{ this.identifier }}'                       AS _pulse_silver_model
FROM masked
