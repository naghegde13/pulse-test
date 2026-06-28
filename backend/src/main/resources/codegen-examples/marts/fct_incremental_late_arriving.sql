-- PULSE codegen example: FactBuild (secondary) — handles LATE-ARRIVING
-- dimension rows by holding affected facts in a "pending" state and
-- re-resolving on each subsequent run.
--
-- What this blueprint does (and what it does NOT):
--   - Two-pass design:
--       Pass A: include today's NEW events.
--       Pass B: re-process facts already in {{ this }} where the
--               dimension lookup hadn't resolved (dim_resolved_at IS NULL).
--   - When a fact is re-processed and its dim now resolves, we
--     overwrite the row with the resolved surrogate key + set
--     dim_resolved_at. When it STILL doesn't resolve after N days
--     (configurable), we either send it to a quarantine table or
--     accept the NULL with an explicit "_pulse_late_dim_giveup" flag.
--   - Different from fct_basic.sql: that one assumes dimensions are
--     present at fact-build time. This file is the explicit retry path
--     when the source emits events before the dimension's master
--     record arrives (common with same-day customer onboarding +
--     same-day order data).
--
-- Architectural rules:
--   - max_lookback_days bounds the scan back through {{ this }}; without
--     it, a 5-year-old unresolved row would re-scan every night.
--   - The "giveup" path uses an explicit threshold so unresolved facts
--     never silently accumulate as NULLs.

{{
    config(
        materialized='incremental',
        file_format='__LAKE_FORMAT__',
        partition_by=['ds'],
        unique_key=['fact_pk'],
        on_schema_change='fail',
        incremental_strategy='merge',
        tags=['pulse', 'marts', 'fact', 'late_arriving']
    )
}}

{%- set dim_lookups = __DIMENSION_LOOKUPS__ -%}
{%- set measures = __FACT_MEASURES__ -%}
{%- set max_lookback_days = __MAX_LATE_LOOKBACK_DAYS__ -%}
{%- set giveup_days = __LATE_DIM_GIVEUP_DAYS__ -%}

WITH new_events AS (
    SELECT *, FALSE AS _is_retry
    FROM {{ ref('__EVENT_SOURCE_MODEL__') }}
    WHERE ds = '{{ var("pulse_business_date") }}'
),

retry_candidates AS (
    {% if is_incremental() %}
    -- Pull back facts where dim hadn't resolved, bounded by lookback.
    SELECT
        f.fact_pk,
        f.event_ts,
        f.ds AS original_ds,
        f.* EXCEPT (fact_pk, event_ts, ds, dim_resolved_at,
                    _pulse_business_date, _pulse_processing_ts,
                    _pulse_run_id, _pulse_gold_model),
        TRUE AS _is_retry
    FROM {{ this }} f
    WHERE f.dim_resolved_at IS NULL
      AND f.ds >= DATE_SUB('{{ var("pulse_business_date") }}', {{ max_lookback_days }})
    {% else %}
    SELECT NULL::string AS fact_pk WHERE FALSE
    {% endif %}
),

union_input AS (
    SELECT * FROM new_events
    UNION ALL
    SELECT * FROM retry_candidates
),

resolved AS (
    SELECT
        u.*
        {%- for dl in dim_lookups %},
        {{ loop.index }}_dim.{{ dl.surrogate_alias }} AS {{ dl.surrogate_alias }}
        {%- endfor %}
    FROM union_input u
    {%- for dl in dim_lookups %}
    LEFT JOIN {{ ref(dl.dim_model) }} {{ loop.index }}_dim
      ON {{ loop.index }}_dim.{{ dl.natural_key }} = u.{{ dl.natural_key }}
      AND u.event_ts >= {{ loop.index }}_dim.dbt_valid_from
      AND (u.event_ts <  {{ loop.index }}_dim.dbt_valid_to
           OR {{ loop.index }}_dim.dbt_valid_to IS NULL)
    {%- endfor %}
),

scored AS (
    SELECT
        *,
        CASE
            WHEN {%- for dl in dim_lookups %}
                {%- if not loop.first %} AND {% endif -%}
                {{ dl.surrogate_alias }} IS NOT NULL
                {%- endfor %} THEN CURRENT_TIMESTAMP()
            ELSE NULL
        END AS dim_resolved_at,
        DATEDIFF('{{ var("pulse_business_date") }}', COALESCE(original_ds, ds))
                                                  AS _days_pending
    FROM resolved
)

SELECT
    SHA2(CONCAT_WS('|', __EVENT_PK_EXPRESSION__,
                       CAST(COALESCE(original_ds, ds) AS STRING)), 256)
                                                  AS fact_pk,
    {%- for dl in dim_lookups %}
    {{ dl.surrogate_alias }},
    {%- endfor %}
    DATE_FORMAT(event_ts, 'yyyyMMdd')             AS date_sk,
    {%- for m in measures %}
    {{ m }},
    {%- endfor %}
    event_ts,
    COALESCE(original_ds, ds)                     AS ds,
    dim_resolved_at,
    -- Explicit giveup flag: dim never resolved within giveup_days.
    -- Reporting MUST surface these distinctly from "still resolving".
    CASE
        WHEN dim_resolved_at IS NULL
         AND _days_pending >= {{ giveup_days }}
        THEN TRUE ELSE FALSE
    END                                            AS _pulse_late_dim_giveup,
    '{{ var("pulse_business_date") }}'             AS _pulse_business_date,
    CURRENT_TIMESTAMP()                             AS _pulse_processing_ts,
    '{{ var("pulse_run_id") }}'                     AS _pulse_run_id,
    '{{ this.identifier }}'                         AS _pulse_gold_model
FROM scored
