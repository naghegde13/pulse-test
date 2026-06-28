-- PULSE codegen example: GenericFilter — intermediate model that applies
-- a composite predicate to an upstream silver model and emits the
-- matching rows.
--
-- What this blueprint does (and what it does NOT):
--   - Single-output filter. The matching rows go to ONE output port;
--     non-matching rows are dropped. For multi-output routing (some
--     rows to A, others to B based on a predicate), use GenericRouter.
--   - The predicate is JSON-declarative: a list of clause objects,
--     each with an operator and operand columns/values. The codegen
--     layer expands them into a SQL WHERE clause so the user's chat
--     description ("active orders over $100 from last 90 days") maps
--     to a structured spec, not free-form SQL.
--   - Idempotent / deterministic: no ROW_NUMBER, no LIMIT, no random
--     sampling — same input always produces same output.
--   - Bound by ds for incremental builds; full silver scans are a
--     codegen anti-pattern.

{{
    config(
        materialized='incremental',
        file_format='__LAKE_FORMAT__',
        partition_by=['ds'],
        unique_key=__UNIQUE_KEY_LIST__,
        on_schema_change='fail',
        incremental_strategy='merge',
        tags=['pulse', 'intermediate', 'filter']
    )
}}

{# filter_clauses: [                                                       #}
{#   {column: 'status',     op: 'IN',  values: ['ACTIVE', 'PENDING']},    #}
{#   {column: 'amount',     op: '>',   value: 0},                          #}
{#   {column: 'region',     op: 'NOT_IN', values: ['TEST', 'STAGING'],     #}
{#                                       allow_null: true},                #}
{#   {column: 'created_at', op: '>=',  date_relative: 'PBD-90'},          #}
{# ]                                                                        #}
{# join_kind: 'AND' | 'OR' — top-level conjunction.                          #}
{%- set filter_clauses = __FILTER_CLAUSES__ -%}
{%- set join_kind = '__JOIN_KIND__' -%}

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
    '{{ var("pulse_business_date") }}'        AS _pulse_business_date,
    CURRENT_TIMESTAMP()                        AS _pulse_processing_ts,
    '{{ var("pulse_run_id") }}'                AS _pulse_run_id
FROM upstream
WHERE
    {%- for clause in filter_clauses %}
    {%- if not loop.first %} {{ join_kind }} {% endif -%}
    (
    {%- if clause.op == 'IN' -%}
        {{ clause.column }} IN ({{ clause['values'] | map('tojson') | join(', ') }})
    {%- elif clause.op == 'NOT_IN' -%}
        ({{ clause.column }} NOT IN ({{ clause['values'] | map('tojson') | join(', ') }})
        {%- if clause.allow_null %} OR {{ clause.column }} IS NULL{% endif -%})
    {%- elif clause.op in ['=', '<>', '>', '>=', '<', '<='] -%}
        {%- if clause.date_relative is defined -%}
        -- date_relative resolves at codegen time to an explicit ISO date.
        {{ clause.column }} {{ clause.op }} DATE '{{ clause.date_relative_resolved }}'
        {%- else -%}
        {{ clause.column }} {{ clause.op }} {{ clause.value | tojson }}
        {%- endif -%}
    {%- elif clause.op == 'LIKE' -%}
        {{ clause.column }} LIKE {{ clause.value | tojson }}
    {%- elif clause.op == 'IS_NULL' -%}
        {{ clause.column }} IS NULL
    {%- elif clause.op == 'IS_NOT_NULL' -%}
        {{ clause.column }} IS NOT NULL
    {%- else -%}
        {# unsupported op — codegen-time validation should reject before reaching here #}
        FALSE
    {%- endif -%}
    )
    {%- endfor %}
