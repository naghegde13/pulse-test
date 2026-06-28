# SPEC — CALCITE-PHASE-2: the deterministic SQL validator + the SqlModel / SourceSQL blueprints + inline date mnemonics

> **Status:** contract-level draft for the **SPEC-GATE** (ADR 0010 zero-fuzziness bar). Implements **ADR 0024**
> (SQL authoring). Cites ADR 0011 (deterministic schema, zero LLM), ADR 0012 (closed 32-op vocab), ADR 0013
> (deterministic Builder; LLM Chat-side only), ADR 0009 (byte-exact). **Consumed by:** #1
> `SPEC-schema-op-engine.md` §B rule 27 (this spec defines how rule 27's OUT columns are derived) and #2
> `SPEC-codegen-compiler.md` §C.4 (this spec defines the emission of the SQL path). EARS phrasing
> (`WHEN <trigger> THE SYSTEM SHALL <response>`) is used for binding contract sentences.
>
> **`> GUESS:` lines are the SPEC-GATE's findings to resolve** — each marks a name/shape/value this author had to
> invent rather than read from a LOCKED source or the live code. They carry a concrete default so a building agent
> is unblocked, but are NOT operator-agreed and must be resolved before build.
>
> **Coverage basis (the gate yardstick):** (1) the Calcite validator's full contract — input, dialect, the COMPLETE
> PULSE↔Calcite type map incl. nested types, output derivation, failure semantics, declare-schema fallback,
> determinism. **The Calcite validator is `sql-model`-ONLY** (LOCKED 2026-06-16): `SourceSQL` validates via the
> **source** (JDBC prepare/metadata, §C.2), **not** Calcite — so the validator carries ONE catalog context, not two;
> (2) the `sql-model` discrete-statement chain incl. `materialize`; (3) the `SourceSQL` blueprint (source-validated);
> (4) the `SqlModel` blueprint (Calcite-validated); (5) inline date mnemonics across **every** place SQL or a SQL
> expression appears, with per-engine lowering; (6) integration points + tests. A dropped item in any of these =
> an omission finding.
>
> **Defined vocab (used verbatim):** op · op-list · Blueprint · dataset · port · Customer · input schema · catalog
> · step (one statement in a `sql-model` chain) · lowering (build-time rewrite of an author token to an engine-native
> slot) · calendar bundle (the hash-pinned holiday-calendar artifact).

## 0. Grounding ledger (READ vs GUESSED)

`[read]` = read from a LOCKED ADR/spec or the live codebase at a cited path; everything else is `> GUESS:`.

- `[read]` The date-mnemonic facility exists and is mature: validator `com.pulse.common.text.DateMnemonic`
  (`isValid`/`isMnemonic`/`validateOrThrow`); runtime resolver `pulse_dates.resolve_mnemonic(token, as_of, calendar_id,
  calendar_bundle_uri, calendar_bundle_hash, …)`; the closed vocabulary is **3-way synced** (`DateMnemonic.java` ↔
  `pulse_dates/__init__.py` ↔ `PulseSystemPrompt`) — the `DateMnemonic.java` header mandates all three change together.
- `[read]` The vocabulary heads: offset-capable `T, BOW, EOW, BOM, EOM, BOQ, EOQ, BOH, EOH, BOY, EOY, BOFY, EOFY, BOFQ,
  EOFQ, BOFM, EOFM, PBD, NBD` (±N); offset-forbidden `TODAY, RUN_DATE, PREVIOUS_RUN_DATE, SAME_DAY_LAST_{WEEK,MONTH,
  QUARTER,YEAR}, FBOM, LBOM`; aliases `WTD_START, MTD_START, QTD_START, YTD_START, FYTD_START, LAST_COMPLETED_{MONTH,
  QUARTER}_{START,END}`; parameterized `NBDOM(N)`; plus ISO `YYYY-MM-DD`; case-insensitive
  (`DateMnemonic.java:31-55`, `pulse_dates/__init__.py:53-137,160-162`). **There is no `CBD` token** — "current
  business day" maps to `RUN_DATE`/`TODAY`.
- `[read]` The business date enters every task as the Airflow run context `{{ ds }}` injected as
  `PULSE_BUSINESS_DATE`, but the **injection keyword differs by operator** (`CodeGenerationService.java`): `:591`
  SparkSubmitOperator uses **`env_vars={'PULSE_BUSINESS_DATE':'{{ ds }}', …}`**; `:599` the dbt BashOperator uses
  **`env={'PULSE_BUSINESS_DATE':'{{ ds }}', …}`**; `:607` the GX PythonOperator uses
  **`env={**os.environ, 'PULSE_BUSINESS_DATE': ctx['ds'], …}`** (templated via `ctx['ds']`, not the literal `{{ ds }}`).
  Tasks read `os.environ.get('PULSE_BUSINESS_DATE','{{ ds }}')` at `:843,1198,4255`. Airflow **Variables** carry the
  time-dimension state (`time_state.py:511 Variable.get / :517 Variable.set`) and may carry the calendar-bundle path.
- `[read]` The holiday calendar is a **hash-pinned bundle** (`resolve_mnemonic(… calendar_bundle_uri,
  calendar_bundle_hash …)`; `time_state.py:266-291`); deployed resolution never queries the PULSE DB. The
  two distinct things, not to be conflated: **`__HOLIDAY_CALENDAR_ID__`** is the **build-time sentinel** (codegen
  fill), while **`calendar_id`** (default **`US-FED`**, `time_state.py:61`) is the **resolver param** passed at runtime.
- `[read]` dbt models are parameterized two ways: build-time `__UPPER__` sentinels (`__LAKE_FORMAT__`,
  `__TYPE_CAST_LIST__`) and **`--vars`** on the `dbt build` invocation for runtime values (`stg_cleaning_basic.sql:21` "dbt run --vars;
  codegen always passes it"). dbt is invoked via `bash_command='cd /opt/dbt && dbt build --select …'`
  (`CodeGenerationService.java:597`).
- `[read]` `ForbiddenTokenScanner` flags unresolved placeholders in generated artifacts — `${VARIABLE}` and a
  `PLACEHOLDER_TOKENS` set (`ForbiddenTokenScanner.java:50-172`, called from `CodeGenerationService.java:221-232`).
- `[read]` `source_query` already exists as an ingestion param on `BulkBackfill` ("JDBC SELECT to source rows… for
  predicate pushdown", `V7:70`, `V81:90`, `V92:60-72`).
- `[read]` `sql-model` is op 27 in the closed vocabulary; its schema rule today resolves via the declare-schema path
  until `CALCITE-PHASE-2` is built (`SPEC-schema-op-engine.md:362-370`, `SPEC-codegen-compiler.md:186-196`).
- `[read]` A **parse-only** Calcite Babel validator already exists in-repo —
  `ExpressionValidationService.java:99-113` (`SqlBabelParserImpl.FACTORY`, `SqlConformanceEnum.BABEL`,
  `Quoting.BACK_TICK`, `withCaseSensitive(false)`); its javadoc (`:36-42`) explicitly names schema-derivation via
  `Frameworks.getPlanner(...)` + virtual tables per port as its deferred **"Phase 2"** — i.e. EXACTLY this work. The
  schema-deriving validator this spec builds is that named **Phase-2**: it **extends** the parse-only validator to a
  schema-deriving one (adds `SqlValidator` / `Frameworks.getPlanner` + `RelDataType` derivation).

---

## A. The Calcite validator (`CALCITE-PHASE-2`) — `sql-model`-ONLY

A deterministic Java service. **No LLM** (ADR 0011/0013). **It validates `sql-model` ONLY** (LOCKED 2026-06-16):
`SourceSQL`'s `source_query` is **NOT** Calcite-validated — it round-trips to the bound JDBC **source**, which
validates its own dialect/functions and returns result-set metadata (§C.2). The Calcite validator therefore carries
a **single** catalog context (the `sql-model` `input` + earlier-step schemas, §A.1), not two. It
**extends/parallels** the existing parse-only `ExpressionValidationService` (`ExpressionValidationService.java:99-113`)
— this spec builds that service's named **"Phase 2"** (`:36-42`): the parse-only Babel validator grown into a
schema-deriving one (adds `SqlValidator` / `Frameworks.getPlanner` + `RelDataType` derivation per port). Type
**`CalciteSqlModelValidator`**, co-located with `ExpressionValidationService` in package
**`com.pulse.expression.service`** (resolved below).

> **RESOLVED (operator 2026-06-16):** the validator service is **`CalciteSqlModelValidator`**, named/placed
> **alongside the existing `ExpressionValidationService`** whose named "Phase 2" it builds (§A, `:36-42`,`:99-113`) —
> i.e. in that service's package **`com.pulse.expression.service`**
> (`backend/src/main/java/com/pulse/expression/service/ExpressionValidationService.java`), since CALCITE-PHASE-2
> *extends/parallels* it (reuses its `:99-113` Babel parser config and grows it into the schema-deriving validator).
> The earlier `com.pulse.codegen.sql.…` placement is superseded: the validator is grounded in, and co-located with,
> `ExpressionValidationService`, not codegen. The class name `CalciteSqlModelValidator` is used verbatim throughout
> this spec (§A, §E). (Resolves 6-G08, the validator-name residual.)

### A.1 Service contract

> **WHEN** the schema engine resolves the OUT schema of a `sql-model` chain **THE SYSTEM SHALL** call the validator
> with `(orderedSql, catalog)` and receive **either** a PULSE column list (the derived output schema) **or** a
> structured `BuildFailure`. The validator **SHALL NOT** execute SQL and **SHALL NOT** resolve any date value.
> (`SourceSQL`'s `source_query` does **not** call this validator — it resolves its schema via the source, §C.2.)

- `orderedSql` = the `sql-model` ordered `steps[]` (see §B).
- `catalog` = the named relations the SQL may reference, each a PULSE column list. **One context** (the validator is
  `sql-model`-only): `input` (the upstream schema) + every earlier step's derived schema. There is **no** SourceSQL /
  discovered-source-catalog context — that path validates via the source (§C.2).

### A.2 Parser / dialect / function registry

> **WHEN** the validator parses SQL **THE SYSTEM SHALL** reuse the **existing** Babel parser config already proven in
> `ExpressionValidationService.java:99-113` — `SqlBabelParserImpl.FACTORY`, `withConformance(SqlConformanceEnum.BABEL)`,
> `withQuoting(Quoting.BACK_TICK)`, `withCaseSensitive(false)` (lenient, Spark-SQL-leaning; accepts backtick
> identifiers and dbt/Spark idioms) — **not** a new dialect/`Lex` choice. On top of that parser it adds a
> **registered function set** covering the Spark SQL functions PULSE blueprints emit. This registry is **Spark-only**
> (it serves `sql-model`, the Spark/dbt-SQL transform); **`SourceSQL` needs no PULSE function registry** — its
> `source_query` is validated by the **source DB**, which is the authority on its own dialect + functions (§C.2).
> **WHEN** the SQL references a function not in the registry **THE SYSTEM SHALL** fail the build loudly (ADR 0011 —
> unknown → loud fail, never a guess).

> **RESOLVED (operator 2026-06-16):** the parser config is COPIED verbatim from
> `ExpressionValidationService.java:99-113` (no new dialect/`Lex` choice). The **initial registered-function seed
> list** for the Calcite validator's operator table is:
>
> - **Calcite built-ins (free):** the standard ANSI/SQL operators Calcite's `SqlStdOperatorTable` already supplies —
>   arithmetic / comparison / logical operators, `CASE`/`COALESCE`/`NULLIF`/`CAST`, the standard aggregates
>   (`COUNT`, `SUM`, `AVG`, `MIN`, `MAX`), the window machinery (`OVER`, `ROW_NUMBER`, `RANK`, `DENSE_RANK`,
>   `LAG`, `LEAD`), and the standard scalar string/date functions Calcite recognizes (`SUBSTRING`, `TRIM`, `UPPER`,
>   `LOWER`, `CURRENT_DATE`, `CURRENT_TIMESTAMP`). These need no extra registration.
> - **Spark-SQL functions PULSE codegen emits, registered on top:** `SHA2`, `CONCAT_WS`, `CONCAT`, `TRY_CAST`,
>   `DATEDIFF`, `DATE_ADD`, `DATE_SUB`, `DATE_FORMAT`, `TO_DATE`, `TO_TIMESTAMP`, `NVL`, `COALESCE`, `CAST`,
>   `REGEXP_REPLACE`, `SPLIT`, `LPAD`, `RPAD`, `MD5`, `CURRENT_DATE`, `CURRENT_TIMESTAMP` (Spark spellings/overloads
>   that Calcite's std table does not already cover).
>
> **Source of truth + extensibility:** the seed is the union of (a) Calcite's `SqlStdOperatorTable` built-ins and
> (b) the Spark-SQL functions the live dbt/PySpark codegen actually emits (the codegen-example bodies — e.g.
> `stg_cleaning_basic.sql` and the `CodeGenerationService` dbt/Spark templates referenced in §0). The registry is an
> **extensible** operator table: a Spark function added to codegen later is registered here in the same pass. Per ADR
> 0011, **an unknown / unregistered function is a loud build-fail (§A.5), never a silent pass**. (`SourceSQL` needs no
> PULSE function registry — its `source_query` is validated by the source DB, §C.2.) (Resolves G-1 residual.)

### A.3 PULSE ↔ Calcite type mapping (the complete map)

> **WHEN** the validator builds a Calcite catalog from a PULSE schema, or maps a derived Calcite row type back to
> PULSE columns, **THE SYSTEM SHALL** use this total, deterministic map; an unmappable type is a loud build-fail.

| PULSE type (per #1 §B.0, `SPEC-schema-op-engine.md:251,257-259`) | Calcite `SqlTypeName` |
|---|---|
| `string` | `VARCHAR` |
| `integer` | `INTEGER` |
| `long` | `BIGINT` |
| `double` | `DOUBLE` |
| `decimal` | `DECIMAL` (generic; #1 §B.0 does **not** encode precision/scale) |
| `boolean` | `BOOLEAN` |
| `date` | `DATE` |
| `timestamp` | `TIMESTAMP` |
| `struct` (carries `fields`) | `ROW(field…)` — recurse per field |
| `list` (carries `element`) | `ARRAY(element)` — recurse on element |

> **RESOLVED (G-2 — reconciled verbatim to #1 §B.0, `SPEC-schema-op-engine.md:251` + nested at `:257-259`):** the
> simple set is **exactly these 8** — `string, integer, long, double, decimal, boolean, date, timestamp` — plus the
> two nested forms `struct` (key `fields`) and `list` (key `element`), recursive. The `int`/`bigint` spellings are
> **removed** (#1 uses only `integer`/`long`); `decimal` maps generically (#1 encodes no precision/scale); the `map`
> row is **removed** (#1 §B.0 has **no** `map` type). `nullable` round-trips (PULSE `nullable` ↔ Calcite nullability).

### A.4 Output-schema derivation

> **WHEN** validation succeeds **THE SYSTEM SHALL** derive the output schema from the **final/terminal `SELECT`**'s
> validated row type: each output column's **name** = the `SELECT` alias (or the source column name when the column
> is a bare column reference), **type** = the §A.3 reverse-map of Calcite's inferred type, **nullable** = Calcite's
> inferred nullability.
> **WHEN** two output columns resolve to the same name **THE SYSTEM SHALL** fail the build loudly.

> **WHEN** a `SELECT` output column is an **expression** (not a bare column reference) with **no explicit alias**
> **THE SYSTEM SHALL** fail the build loudly, requiring the author to supply an explicit alias — rather than accept
> Calcite's synthesized `EXPR$n` name (resolves N-2; deterministic, author-controlled output naming).

> **WHEN** the reverse-map (§A.3) maps a derived Calcite type back to a PULSE column **THE SYSTEM SHALL** apply the
> **nested** reverse map recursively (resolves omission-b): Calcite `ROW(field…)` → `{type:"struct", fields:[…]}`
> (each field reverse-mapped recursively); Calcite `ARRAY(element)` → `{type:"list", element:…}` (element
> reverse-mapped recursively) — so §A.4 can emit nested PULSE columns in the #1 §B.0 encoding.

### A.5 Failure semantics

> **WHEN** the SQL fails to parse, references an unknown column or relation, has an unresolved type, or calls an
> unregistered function **THE SYSTEM SHALL** raise a `BuildFailure` carrying `{ cause, sqlPosition (line/col),
> message }` and **SHALL NOT** emit a schema. There is **no silent pass and no AI fallback** (ADR 0011).

### A.6 Declare-schema fallback + precedence

The derived schema is **primary**; the declared schema is an **EITHER/OR fallback**, not a cross-check (aligned to ADR
0024:22, #1 rule 27 `SPEC-schema-op-engine.md:365-367`, and #2 §C.4 `SPEC-codegen-compiler.md:187-189` — none of
which describe a declared-vs-derived cross-check). The declared schema is **ADR-0011-legal**: it is an explicit
Customer/DE declaration, not a guess or an LLM fallback. This fallback covers **BOTH** schema-derivation paths
(LOCKED 2026-06-16): the **Calcite** path (`sql-model`, §A.4) and the **source-prepare** path (`SourceSQL`, §C.2).

> **WHEN** Calcite validates a `sql-model` chain **THE SYSTEM SHALL** use Calcite's derived schema (§A.4); **WHEN**
> the source-prepare of a `SourceSQL` `source_query` succeeds **THE SYSTEM SHALL** use the source-returned schema
> (§C.2). **WHEN** Calcite raises a parse/validation error (`sql-model`) **OR** the source-prepare errors / the
> source is unreachable at design time (`SourceSQL`) **AND** a declared schema is present **THE SYSTEM SHALL** use
> the declared schema and log a warning. **WHEN** the same derivation error occurs **AND** no declared schema is
> present **THE SYSTEM SHALL** fail the build loudly. There is **no cross-check** of declared-vs-derived and **no
> fail-on-mismatch** — derivation either succeeds (and wins) or errors (and the declared schema, if any, takes over).

> **RESOLVED (G-3 — 2026-06-15; resolves 2a/2b/2c/N-3 and the §A.6 EARS-testability nit):** the fallback is
> EITHER/OR with a **concrete, testable trigger** = "Calcite raises a parse/validation error" (NOT the fuzzy
> "unsupported construct"). This matches ADR 0024:22 + #1 rule 27 + #2 §C.4; the earlier "cross-check + fail on
> mismatch" semantic contradicted all three and is **removed**.

**Declared-schema shape + source (resolves omission-d).** The declared schema is an **OPTIONAL** op-config field
**`declared_output_schema`**, shaped as the **#1 §B.0 column-descriptor list** (`SPEC-schema-op-engine.md:251,257-259`):
an ordered list of `{name, type, nullable}` descriptors, where `type` is one of the 8 simple strings or a nested
`{type:"struct", fields:[…]}` / `{type:"list", element:…}` (recursive — `fields` present iff `type=="struct"`,
`element` present iff `type=="list"`). For a `sql-model` op it sits on the op config (alongside `steps`); for
`SourceSQL` it sits on the `read-source` op config. When present it is the §A.6 fallback target; when absent, a
Calcite error is a loud build-fail. (Without this field pinned, the §A.6 fallback and the §F test (3) are not
buildable.)

### A.7 Determinism

> **WHILE** validating, **THE SYSTEM SHALL** be a pure function of `(orderedSql, catalog, registry, typeMap)` —
> same inputs → same schema, byte-for-byte (ADR 0009) — with **no LLM, no network, no clock** dependency.

---

## B. `sql-model` op + the `SqlModel` blueprint (in-pipeline transform)

### B.1 The discrete-statement chain

> **WHEN** a `sql-model` op is configured **THE SYSTEM SHALL** carry `steps: [{ name, sql, materialize }]` — an
> ordered list of discrete SQL statements. **THE** op's input is referenced by the reserved relation name **`input`**;
> each step is referenced by its **`name`**; a step's `sql` MAY reference `input` and any **earlier** step. Step
> `name`s **SHALL** be unique within the chain. The **last step's `SELECT` is the op's output schema** (§A.4).

```
{ "op": "sql-model",
  "config": {
    "steps": [
      { "name": "stg",  "sql": "SELECT … FROM input WHERE biz_date >= [[ BOM-1 ]]", "materialize": false },
      { "name": "agg",  "sql": "SELECT … FROM stg GROUP BY …",                       "materialize": true  },
      { "name": "out",  "sql": "SELECT … FROM agg",                                  "materialize": false }
    ]
  } }
```

> **RESOLVED (operator 2026-06-16):** the JSON key names are **`steps` / `name` / `sql` / `materialize`** and the
> reserved input relation name is **`input`**, reconciled to #1 §A.1's op-entry `config` shape
> (`SPEC-schema-op-engine.md:66-101`, G-1 LOCKED). The `sql-model` op-entry round-trips there as a normal op-entry:
> the entry is `{ "op": "sql-model", "ui_label": "<label>", "config": { "steps": {"param":"steps"} } }` — `steps` sits
> **inside `config`** as a **param-ref** (`{"param":"steps"}`, the only legal non-literal config-value form per §A.1),
> resolving to the Blueprint's Customer-authored `steps:[{name,sql,materialize}]` param (§B.5); the inner
> `name`/`sql`/`materialize` are the literal shape of each step value. The reserved relation name **`input`** is a
> SQL-internal relation reference (the upstream-bound `input` schema, §A.1), **not** an op-entry config key, so it does
> **not** collide with the §A.1 key set (`op`/`ui_label`/`config`). (Resolves G-4.)

### B.2 Chain validation

> **WHEN** resolving a `sql-model` chain's schema **THE SYSTEM SHALL** validate each step in order against an
> accumulating catalog (`input` + every prior step's derived schema), register each step's derived schema under its
> `name`, and return the **last** step's schema. A failure on **any** step is a `BuildFailure` (§A.5).

### B.3 `materialize` semantics + emission (Mode-aware — #2 owns the bytes)

> **WHEN** a step has `materialize: true` **THE SYSTEM SHALL** emit it as a **persisted intermediate**; **WHEN**
> `materialize: false` (default) **THE SYSTEM SHALL** emit it **inlined** (CTE / temp view). `materialize`
> **SHALL NOT** affect the derived schema.

- **PySpark:** `true` → `.cache()`/`.checkpoint()` **or** a temp-table write+read; `false` → a DataFrame /
  `createOrReplaceTempView`.
- **dbt:** `true` → a **separate materialized model** (`materialized='table'`); `false` → a **CTE**.

> **RESOLVED (G-5 — the `materialize` emission CONTRACT lives here, not deferred):** the per-Mode realization above is
> the contract this spec owns. #2 §C **realizes** it Mode-aware (GCP/DPC), but #2 §C does not carry the contract, so
> it is pinned here (concretely, per engine) rather than deferred.

### B.4 Output to #1 rule 27

> **WHEN** #1 §B rule 27 resolves `sql-model`'s OUT **THE SYSTEM SHALL** use this op's §A.4 derived schema (Calcite),
> with the §A.6 declare-schema fallback. This **replaces** rule 27's current "declare-schema only (Calcite
> unavailable)" text once `CALCITE-PHASE-2` ships (ADR 0024 consequence).

### B.5 The `SqlModel` blueprint (shape)

> **WHEN** a `SqlModel` Blueprint is instantiated **THE SYSTEM SHALL** expose ONE **required input port** (the
> upstream dataset, bound to the op's reserved `input` relation per §B.1) and ONE **output port** (the chain's
> last-step derived schema, §A.4 / §B.4). Its op-list is a single `sql-model` op; the Customer-authored
> `steps:[{name,sql,materialize}]` (§B.1) is the Blueprint's param surface, mapped into the op's `config.steps`.
> **No new op** (ADR 0024).

> **RESOLVED (operator 2026-06-16):** the Blueprint key is **`SqlModel`** and the param carrying the chain is
> **`steps`** (`steps:[{name,sql,materialize}]`), confirmed against #1 §A.1's op-entry `config` shape (the param maps
> into the single `sql-model` op-entry's `config.steps` as a param-ref `{"param":"steps"}` — see §B.1) and seeded by
> V153 (§E). (Resolves G-6 for `SqlModel`; couples to the G-4 op-JSON reconciliation in §B.1.)

---

## C. `SourceSQL` blueprint (relational source via SQL)

### C.1 Shape

> **WHEN** a `SourceSQL` Blueprint is instantiated **THE SYSTEM SHALL** bind a **JDBC connector** (Producer Registry,
> credential profile = SecretRefs) via the **`connector_instance_id`** param and a **`source_query`** param (a SQL
> `SELECT` run at the source). Its op-list is the generic ingestion shape on the **existing `read-source` op**:
> `read-source(source_query, connector_instance_id) → add-audit-columns → write-sink(bronze)`. **No new op** (ADR 0024).

> **RESOLVED (operator 2026-06-16):** the Blueprint key is **`SourceSQL`** and its param names are **`source_query`**
> and **`connector_instance_id`** — the connector reference is `connector_instance_id`, the **SINK convention** that
> names the Producer Registry **ServiceInstance** layer (CLAUDE.md "Producer Registry" three-layer model: Producer →
> ServiceInstance → Bindings) and matches the §0.1 connector-derived field list. (Resolves G-6; supersedes the earlier
> `connector_id` default and the `connector_id`-vs-`connector_instance_id` flag at #5 :1062 / 5-G11.) This is the value
> the **V153** seed writes. Aligns to the existing `BulkBackfill.source_query` shape (`V81:90`).

### C.2 Schema via the source (JDBC prepare/metadata) — NOT Calcite

`SourceSQL` validates via the **SOURCE**, not Calcite (LOCKED 2026-06-16). The source DB validates its **own** dialect
+ functions and returns result-set metadata; PULSE maps JDBC types → PULSE types. There is **no PULSE-side
per-dialect parser** for `source_query`, and §A's Calcite validator is **not** invoked here.

> **WHEN** `SourceSQL`'s output schema is resolved **THE SYSTEM SHALL**, at design time (dev-builder, with source
> connectivity), **prepare** `source_query` against the bound JDBC source — `PreparedStatement.getMetaData()` on a
> `SELECT … WHERE 1=0` / `LIMIT 0` round-trip — let the source validate its own dialect + functions, and **map the
> returned result-set metadata (JDBC types) → PULSE columns** (the §A.3 simple types; JDBC → PULSE via the standard
> `java.sql.Types` mapping), placing the derived schema on the output port. Each `[[ … ]]` mnemonic in
> `source_query` **SHALL** be substituted to a dummy `DATE` literal **before** the prepare (so the source parses a
> well-formed query; the value is never resolved at design time — §D).
>
> **JDBC → PULSE type map** (each result-set column's `java.sql.Types` → the §A.3 PULSE simple type; an unmapped JDBC
> type is a loud build-fail, ADR 0011; nullability = `ResultSetMetaData.isNullable`):
>
> | `java.sql.Types` | PULSE type |
> |---|---|
> | `CHAR`, `VARCHAR`, `LONGVARCHAR`, `NCHAR`, `NVARCHAR`, `CLOB` | `string` |
> | `TINYINT`, `SMALLINT`, `INTEGER` | `integer` |
> | `BIGINT` | `long` |
> | `REAL`, `FLOAT`, `DOUBLE` | `double` |
> | `DECIMAL`, `NUMERIC` | `decimal` |
> | `BIT`, `BOOLEAN` | `boolean` |
> | `DATE` | `date` |
> | `TIMESTAMP`, `TIMESTAMP_WITH_TIMEZONE` | `timestamp` |
> | `ARRAY` | `list` (element recursively mapped) |
> | `STRUCT` | `struct` (fields recursively mapped) |
>
> GUESS: `TIME` (PULSE has no time-of-day type) → default `string`; vendor/source-specific types not in this table →
> loud fail (the DE then uses the declare-schema fallback, §A.6).
> **WHEN** the source is unreachable at design time (cannot connect/prepare) **THE SYSTEM SHALL** use the §A.6
> declare-schema fallback (else loud-fail).

### C.3 Runtime

> **WHEN** the packaged `SourceSQL` task runs **THE SYSTEM SHALL** execute `source_query` against the bound JDBC
> connector (data movement; Airbyte/Spark-JDBC per Mode), land bronze, and stamp the audit columns (#2 §E). The
> chain + `materialize` of §B does **not** apply to `SourceSQL` — the source DB runs one query.

---

## D. Inline date mnemonics in SQL — the `[[ … ]]` token

### D.1 Token + vocabulary (reuse)

> **WHEN** a Customer embeds a date mnemonic in any SQL or SQL expression **THE SYSTEM SHALL** accept the inline token
> `[[ <mnemonic> ]]` (e.g. `[[ PBD-1 ]]`, `[[ RUN_DATE ]]`), where `<mnemonic>` is drawn from the existing closed
> vocabulary (§0). The vocabulary, validator, and resolver are **reused unchanged** (`DateMnemonic`, `pulse_dates`);
> this spec adds only the **embedded-token scan + lowering**, not new mnemonic logic, and **SHALL NOT** fork the
> 3-way-synced vocabulary.

### D.2 Config-time validation

> **WHEN** SQL containing `[[ … ]]` tokens is saved/compiled **THE SYSTEM SHALL** extract each token and validate it
> via `DateMnemonic.validateOrThrow`, failing loudly on an unknown/typo'd mnemonic.

### D.3 Calcite typing (build time)

> **WHEN** the validator (§A) parses SQL containing `[[ … ]]` **THE SYSTEM SHALL** treat each token as a **`DATE`-typed
> placeholder** purely for parsing/typing/schema-derivation; it **SHALL NOT** resolve the date value. The token
> resolves to a `DATE`-typed placeholder in **any position** — a bare predicate operand (e.g. `WHERE biz_date >= [[ BOM-1 ]]`)
> **or** a function argument (e.g. `datediff([[ RUN_DATE ]], biz_date)`) — and **function-overload resolution treats it
> as `DATE`** when selecting the matching function signature (resolves N-1).

### D.4 Per-engine lowering (build time)

> **WHEN** the Builder emits SQL containing `[[ m ]]` **THE SYSTEM SHALL** lower the token to the engine-native slot:
> - **dbt:** rewrite `[[ m ]]` → `{{ var('pulse_<slug>') }}` in the model; emit a runtime step that resolves `m` and
>   passes it to the dbt step (`dbt build --select …`, `CodeGenerationService.java:597`) via `--vars '{"pulse_<slug>":"<resolved>"}'`.
> - **PySpark / `SourceSQL`:** emit a runtime step that resolves `m` and string-substitutes the date into the SQL.
>
> After lowering, **no `[[ … ]]` survives** into the artifact, so `ForbiddenTokenScanner` is unaffected.

> **RESOLVED (operator 2026-06-16):** the `<slug>` derivation is a **deterministic** rule (not a product choice),
> grounded in ADR 0024:26's lowering contract (`[[ m ]]` → `{{ var('pulse_<slug>') }}`):
>
> 1. **Slug rule:** take the mnemonic token's inner text, **lowercase** it, and **replace every non-alphanumeric
>    character with `_`** — so `PBD-1` → `pbd_1`, `RUN_DATE` → `run_date`, `NBDOM(2)` → `nbdom_2_` (the `(` and `)`
>    each become `_`), `SAME_DAY_LAST_WEEK` → `same_day_last_week`. The dbt var name is the **`pulse_`-prefixed** slug:
>    `pulse_pbd_1`, `pulse_run_date`, etc. (`{{ var('pulse_<slug>') }}`, per ADR 0024:26).
> 2. **Dedup:** the slug is derived **per distinct mnemonic token** (after the lowercase/normalize step above), so two
>    occurrences of the **same** token (e.g. `[[ PBD-1 ]]` appearing twice in one model) map to the **same** slug and
>    emit **one** `pulse_<slug>` var (resolved once, passed once via `--vars`). Distinct tokens (`[[ PBD-1 ]]` vs
>    `[[ PBD-2 ]]`) yield distinct slugs/vars. Because the normalize step is lossy at the character level, the dedup key
>    is the **normalized slug**, so any two tokens that normalize to the same slug collapse to one var.
>
> (Resolves G-7. PySpark / `SourceSQL` lowering string-substitutes the resolved date directly, so no var name is needed
> there — the slug rule governs the dbt `--vars` path only.)

### D.5 Runtime resolution (Airflow only — no phone-home)

> **WHEN** a lowered SQL task runs **THE SYSTEM SHALL** resolve each mnemonic via `pulse_dates.resolve_mnemonic(m,
> as_of=PULSE_BUSINESS_DATE (= Airflow `{{ ds }}`), calendar_bundle_uri/hash, calendar_id,
> previous_run_date=…, fiscal_offset_months=…, …)` — depending **only** on Airflow run context + the pinned calendar
> bundle (+ Airflow Variables for state), **never** the PULSE DB.

> **WHEN** the lowered task calls `resolve_mnemonic` **THE SYSTEM SHALL** also pass (resolves N-4): **`previous_run_date`**
> — wired from the Airflow **previous** run — so `[[ PREVIOUS_RUN_DATE ]]` (and the offset-forbidden
> `PREVIOUS_RUN_DATE` family) resolves; and **`fiscal_offset_months`** — from `domain.business_date_config` — so the
> fiscal mnemonics (`BOFY/EOFY/BOFQ/EOFQ/BOFM/EOFM/FYTD_START`) resolve. These are **in addition to** `as_of` and the
> `calendar_*` params, not a replacement for them.

### D.6 Scope ("anywhere SQL or a SQL expression appears")

> **THE** `[[ … ]]` facility **SHALL** apply uniformly to: `SourceSQL.source_query`, every `sql-model` step's `sql`,
> and every SQL-expression param (e.g. the DERIVE blueprint's `derived_columns[].expression` `[read]` `V102:40`,
> `having_clause` `[read]` `V32:24`). #4 (construct library / expression builder) inherits this rule.

---

## E. Integration points

- **Schema engine (#1 rule 27 / `SchemaPropagationService`):** call the §A **Calcite** validator where the
  **`sql-model`** OUT schema is resolved at design/build time. The **`SourceSQL`** OUT schema is resolved via the
  **source** (§C.2 JDBC prepare/metadata), **not** Calcite.
  > GUESS (G-8): exact call site in `SchemaPropagationService` — the builder locates rule-27 (`sql-model`) resolution.
  > **NOTE (resolves omission-c):** the `SourceSQL` output-port derivation is a **CREATE** task, not a "locate" one.
  > Today rule 24 `read-source` derives OUT from the **discovered dataset schema**
  > (`SchemaPropagationService.java:861-883`); the `source_query` path does **not** exist. So **author a NEW
  > `source_query` derivation branch inside rule 24 / `read-source`** (when `source_query` is present, prepare it
  > against the bound JDBC source per §C.2 and map the returned result-set metadata → PULSE columns), rather than
  > locating an existing site. This branch does **not** call the §A Calcite validator (LOCKED 2026-06-16 — Calcite is
  > `sql-model`-only).
- **Codegen (#2 §C.4):** emit the SQL path (the chain, `materialize`, the §D lowering, `dbt run --vars` wiring),
  Mode-aware (GCP/DPC).
- **New service:** `CalciteSqlModelValidator` (§A) — **extends/parallels** the existing
  `ExpressionValidationService` (reuses its `:99-113` Babel parser config; builds its named `:36-42` "Phase 2"
  schema-derivation). **Gradle:** the `org.apache.calcite:calcite-core` dep is **already present** (it backs
  `ExpressionValidationService`) — no new dep to add; keep the build green.
- **Catalog/seed (#5 + V153, #2 §D):** add the `SqlModel` and `SourceSQL` Blueprint rows.

## F. Tests / oracle (ADR 0009/0004)

> **WHEN** this spec is built **THE SYSTEM SHALL** ship a unit suite proving: (1) `(input schema + SQL) → expected
> output schema` for the common Spark-SQL patterns (rename, cast, join, aggregate, CTE, window, struct/list, the
> multi-step chain); (2) invalid SQL (parse / unknown column / type / unknown function) → `BuildFailure`; (3) the
> declare-schema EITHER/OR fallback (§A.6): Calcite-valid SQL → Calcite schema wins; Calcite **error** + `declared_output_schema`
present → declared schema used (warning logged); Calcite error + no declared schema → loud build-fail; (4) `[[ … ]]` lowering for dbt (→ `{{ var }}` +
> `--vars`) and PySpark (→ substituted date), and config-time rejection of a bad mnemonic; (5) `materialize: true`
> emits a persisted intermediate, `false` inlines, both with identical schema.

- The byte-exact **anchor** does **not** use `sql-model` (verified — its silver is closed-vocab Cleaning + Current
  filter), so **no anchor change** is required. Do **not** regress existing tests.

## OPEN WORKLIST (residual GUESSes — resolve before build)

- **G-1 — RESOLVED** (§A.2): parser config reused verbatim from `ExpressionValidationService.java:99-113`; the
  initial registered-function seed list is pinned = Calcite `SqlStdOperatorTable` built-ins + the Spark-SQL functions
  the live dbt/PySpark codegen emits (extensible operator table; unknown function → loud build-fail per §A.5).
- **G-2 — RESOLVED** (§A.3): type map reconciled **verbatim** to #1 §B.0 (`SPEC-schema-op-engine.md:251,257-259`) —
  8 simple types, two nested forms, no `int`/`bigint`/`map`, generic `decimal`.
- **G-3 — RESOLVED** (§A.6): EITHER/OR fallback (Calcite primary; on Calcite **error**, use declared schema if
  present else loud-fail), aligned to ADR 0024:22 + #1 rule 27 + #2 §C.4; cross-check semantic removed.
- **G-4 — RESOLVED** (§B.1): op JSON keys `steps`/`name`/`sql`/`materialize` + reserved relation name `input`,
  reconciled to #1 §A.1's op-entry `config` shape — `steps` is a param-ref inside `config`; `input` is a SQL-internal
  relation reference, not an op-entry config key, so no key collision.
- **G-5 — RESOLVED** (§B.3): per-engine `materialize` realization pinned here as the contract (#2 §C realizes it
  Mode-aware but does not own it).
- **G-6 — RESOLVED** (§B.5/§C.1): Blueprint keys `SqlModel` / `SourceSQL`; `SqlModel` chain param `steps`;
  `SourceSQL` params `source_query` + **`connector_instance_id`** (the SINK/ServiceInstance connector convention,
  matching the §0.1 connector-derived field list — supersedes the earlier `connector_id` default). This is the value
  V153 seeds.
- **G-7 — RESOLVED** (§D.4): slug = lowercase the mnemonic + non-alphanumerics → `_` (`PBD-1` → `pbd_1`), dbt var =
  `pulse_<slug>` (ADR 0024:26); dedup by normalized slug — one var per distinct mnemonic.
- **G-8 — RESOLVED** (§A, §E): validator service name/package pinned = **`CalciteSqlModelValidator`** in
  **`com.pulse.expression.service`** (co-located with `ExpressionValidationService`, whose "Phase 2" it builds). The #1
  rule-27 (`sql-model`) call site remains a build-locate task (§E `> GUESS (G-8)`), not an operator decision;
  the `SourceSQL` output-port derivation is clarified as a **CREATE** task — author a NEW **source-prepare** branch
  inside rule 24 / `read-source` (`SchemaPropagationService.java:861-883`) that prepares `source_query` against the
  bound JDBC source (§C.2) and maps JDBC types → PULSE columns; it does **NOT** call Calcite (LOCKED 2026-06-16 —
  Calcite is `sql-model`-only).

## Cross-references

- ADR 0024 (this spec implements it) · ADR 0011/0012/0013/0009/0010.
- #1 `SPEC-schema-op-engine.md` §B rule 27 (OUT derivation), §B.0 (nested types), §A.1 (op-entry shape).
- #2 `SPEC-codegen-compiler.md` §C.4 (sql-model emission), §C (Mode-aware), §D (V153), §E (audit columns).
- #5 catalog (the new `SqlModel`/`SourceSQL` rows) · #4 construct library (inherits §D).
- Live facilities reused: `DateMnemonic.java`, `pulse_dates`, `ForbiddenTokenScanner.java`, `read-source`,
  `CodeGenerationService` dbt `--vars` + `{{ ds }}` env wiring.
