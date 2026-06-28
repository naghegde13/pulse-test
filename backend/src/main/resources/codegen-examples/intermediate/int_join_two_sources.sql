-- PULSE codegen example: GenericJoin — intermediate model that joins two
-- upstream silver models on a declared key.
--
-- What this blueprint does (and what it does NOT):
--   - Single canonical join: LEFT, INNER, RIGHT, or FULL OUTER. The
--     join_kind param chooses; LEFT is the safe default (preserves the
--     left grain, surfaces missing right matches as NULL).
--   - Column-level conflict resolution: when both sides have a column
--     named foo, the projection prefixes the right side as r_foo. The
--     codegen layer expands the SELECT * EXCEPT (...) clause from
--     the resolved upstream schemas.
--   - Pre-filters BEFORE the join — pushdown_left/pushdown_right —
--     reduce the join's input size. The agent SHOULD propose explicit
--     pushdown filters; "filter then join" is faster than "join then
--     filter" by 1-2 orders of magnitude on moderate data.
--   - Bound by ds on each side; cross-day joins are a separate
--     blueprint (TemporalJoin, planned).

{{
    config(
        materialized='incremental',
        file_format='__LAKE_FORMAT__',
        partition_by=['ds'],
        unique_key=__UNIQUE_KEY_LIST__,
        on_schema_change='fail',
        incremental_strategy='merge',
        tags=['pulse', 'intermediate', 'join']
    )
}}

{# V92 GenericJoin fields:                                                 #}
{#   left_model           → ref name                                        #}
{#   right_model          → ref name                                        #}
{#   join_keys            → ['customer_id'] | [{left:'cid', right:'id'}]    #}
{#   join_kind            → 'LEFT' | 'INNER' | 'RIGHT' | 'FULL_OUTER'       #}
{#   left_pushdown        → optional pre-filter expression                  #}
{#   right_pushdown       → optional pre-filter expression                  #}
{#   right_columns_drop   → list of right-side columns to drop              #}
{%- set left_model = '__LEFT_MODEL__' -%}
{%- set right_model = '__RIGHT_MODEL__' -%}
{%- set join_keys = __JOIN_KEYS__ -%}
{%- set join_kind = '__JOIN_KIND__' -%}
{%- set left_pushdown = '__LEFT_PUSHDOWN__' -%}
{%- set right_pushdown = '__RIGHT_PUSHDOWN__' -%}
{%- set right_drops = __RIGHT_COLUMNS_DROP__ -%}

WITH left_src AS (
    SELECT *
    FROM {{ ref(left_model) }}
    WHERE ds = '{{ var("pulse_business_date") }}'
    {% if left_pushdown and left_pushdown != '__LEFT_PUSHDOWN__' -%}
      AND ({{ left_pushdown }})
    {%- endif %}
),

right_src AS (
    SELECT *
    FROM {{ ref(right_model) }}
    WHERE ds = '{{ var("pulse_business_date") }}'
    {% if right_pushdown and right_pushdown != '__RIGHT_PUSHDOWN__' -%}
      AND ({{ right_pushdown }})
    {%- endif %}
)

SELECT
    l.*,
    r.* EXCEPT (
        {%- set keys_for_drop = [] -%}
        {%- for key in join_keys -%}
            {%- if key is mapping -%}
                {{ keys_for_drop.append(key.right) or '' }}
            {%- else -%}
                {{ keys_for_drop.append(key) or '' }}
            {%- endif -%}
        {%- endfor -%}
        {{ (keys_for_drop + right_drops) | join(', ') }}
    ),
    '{{ var("pulse_business_date") }}'        AS _pulse_business_date,
    CURRENT_TIMESTAMP()                        AS _pulse_processing_ts,
    '{{ var("pulse_run_id") }}'                AS _pulse_run_id
FROM left_src l
{%- if join_kind == 'INNER' %}
INNER JOIN right_src r
{%- elif join_kind == 'RIGHT' %}
RIGHT JOIN right_src r
{%- elif join_kind == 'FULL_OUTER' %}
FULL OUTER JOIN right_src r
{%- else %}
LEFT JOIN right_src r
{%- endif %}
    ON
    {%- for key in join_keys %}
    {%- if not loop.first %} AND {% endif -%}
    {%- if key is mapping -%}
    l.{{ key.left }} = r.{{ key.right }}
    {%- else -%}
    l.{{ key }} = r.{{ key }}
    {%- endif -%}
    {%- endfor %}
