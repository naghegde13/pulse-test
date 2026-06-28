-- PULSE codegen example: ReferenceDataPublish — gold-layer publish of a
-- slowly-changing reference dimension (lookup tables, code values,
-- standardized hierarchies).
--
-- What this blueprint does (and what it does NOT):
--   - Materialize a CURRENT-state reference table from one or more
--     silver sources. The output is small (10s to 100k rows) and
--     mostly-static. Distinct from fact tables (high-volume, event
--     grain).
--   - Different from AggregateMaterialization: that one is for
--     time-bucketed metric rollups. ReferenceDataPublish is for
--     entity catalogs (country codes, product categories, account
--     types, GL codes).
--   - Different from SCD2Dimension: SCD2 keeps every historical
--     version of every row (for time-travel joins from facts).
--     ReferenceDataPublish keeps only the CURRENT version.
--   - Optional dual-write: when publish_to_postgres=TRUE, a paired
--     post-hook copies this gold Delta table to a Postgres reference
--     schema for low-latency app/operational reads.
--
-- Architectural rules:
--   - materialization='table' (full overwrite). Reference data is
--     small enough that incremental adds no value and full overwrite
--     guarantees the published state matches the silver source exactly.
--   - Surrogate key is REQUIRED (reference_id). Natural keys can
--     change; surrogate keys can't.

{{
    config(
        materialized='table',
        file_format='__LAKE_FORMAT__',
        on_schema_change='fail',
        tags=['pulse', 'marts', 'reference']
    )
}}

{# reference_kind     → 'static_lookup' | 'hierarchy' | 'standardized_codes' #}
{# natural_key        → 'country_code'                                       #}
{# columns            → ordered list of columns to publish                   #}
{# parent_key         → for hierarchies: 'parent_country_code' (optional)    #}
{# active_predicate   → "is_active = TRUE"                                   #}
{%- set natural_key = '__NATURAL_KEY__' -%}
{%- set columns = __REFERENCE_COLUMNS__ -%}
{%- set parent_key = '__PARENT_KEY__' -%}
{%- set active_predicate = '__ACTIVE_PREDICATE__' -%}

WITH source AS (
    SELECT *
    FROM {{ ref('__UPSTREAM_MODEL__') }}
    WHERE ds = '{{ var("pulse_business_date") }}'
    {%- if active_predicate and active_predicate != '__ACTIVE_PREDICATE__' %}
      AND ({{ active_predicate }})
    {%- endif %}
),

deduped AS (
    -- Reference data should have one row per natural key per ds, but
    -- guard in case the silver source has duplicates (e.g., during a
    -- backfill of historical reference revisions). Take the row with
    -- the latest event_ts as the winner.
    SELECT *
    FROM (
        SELECT
            *,
            ROW_NUMBER() OVER (
                PARTITION BY {{ natural_key }}
                ORDER BY event_ts DESC, {{ natural_key }} DESC
            ) AS _rank
        FROM source
    )
    WHERE _rank = 1
)

SELECT
    -- reference_id: deterministic surrogate from the natural key. Stable
    -- across re-publishes — never use a sequence/identity here, or
    -- downstream FK joins break on every full overwrite.
    SHA2({{ natural_key }}, 256)                AS reference_id,
    {{ natural_key }},
    {%- for c in columns %}
    {{ c }},
    {%- endfor %}
    {%- if parent_key and parent_key != '__PARENT_KEY__' %}
    {{ parent_key }},
    -- Hierarchy depth: BFS from the roots. Codegen substitutes a
    -- recursive CTE here when reference_kind='hierarchy'; we render
    -- a placeholder note for the simpler flat case.
    NULL                                         AS hierarchy_depth,
    {%- endif %}
    DATE('{{ var("pulse_business_date") }}')    AS published_as_of,
    CURRENT_TIMESTAMP()                          AS _pulse_processing_ts,
    '{{ var("pulse_run_id") }}'                  AS _pulse_run_id,
    '{{ this.identifier }}'                      AS _pulse_gold_model
FROM deduped
