# IMPL PLAN — CALCITE-PHASE-2 + SqlModel / SourceSQL blueprints (spec #6)

> **Build plan, not code.** Implements `docs/build-specs/SPEC-calcite-sql-model.md` (spec #6) and
> `docs/adr/0024-sql-authoring-sourcesql-sqlmodel-calcite-mnemonics.md`. Cites ADR 0011 (deterministic
> schema, zero LLM — unknown → loud fail), ADR 0012 (closed 32-op vocab), ADR 0013 (deterministic Builder),
> ADR 0009 (byte-exact determinism).
>
> **Grounding convention:** `[read]` = read from the live codebase / a LOCKED ADR/spec at a cited path.
> Everything not so tagged is a build decision this plan makes (and flags as a PREREQUISITE if it needs
> an operator/cross-spec answer first).
>
> **One-line shape:** extend the existing **parse-only** Calcite Babel validator
> (`ExpressionValidationService.java`) into a **schema-deriving** one for `sql-model` ONLY; add a JDBC
> **source-prepare** schema branch for `SourceSQL` (NOT Calcite); lower the inline `[[ … ]]` date mnemonic
> per engine in codegen; supply the two blueprints' op-list content for the catalog-seed plan's single V153
> migration (this plan authors no migration). The schema-effect rules are op-keyed (op-walker, Builder plan),
> not blueprint-key-keyed. **No new op** (ADR 0012 stays at 32).

---

## SCOPE

What this build delivers, concretely:

1. **A schema-deriving Calcite validator (`CALCITE-PHASE-2`), `sql-model`-ONLY.** Grow the parse-only
   `ExpressionValidationService` (`[read]` `backend/.../expression/service/ExpressionValidationService.java:99-113`
   = the Babel parser config to COPY; `[read]` `:36-42` = the javadoc that names exactly this "Phase 2"
   work — `Frameworks.getPlanner(...)` + virtual tables per port + rowtype derivation) into a service that,
   given `(orderedSql, catalog)`, returns **either** a PULSE column list (the derived output schema) **or**
   a structured `BuildFailure`. Adds: a `SqlValidator` / `Frameworks.getPlanner` setup, virtual tables built
   from the catalog via the **PULSE↔Calcite type map** (§A.3, 8 simple + 2 nested forms), output-schema
   derivation from the **terminal `SELECT`**'s validated rowtype (§A.4, with the reverse map), a **registered
   Spark function set** (§A.2), and `BuildFailure{cause, sqlPosition, message}` failure semantics (§A.5). It
   carries **ONE catalog context** (the `sql-model` `input` + accumulated prior-step schemas) — never a
   discovered-source catalog. Pure function of `(orderedSql, catalog, registry, typeMap)` — no LLM, no
   network, no clock (§A.7).

2. **The `sql-model` discrete-statement chain validator (§B).** Validate `steps: [{name, sql, materialize}]`
   in order against an accumulating catalog (`input` + every prior step's derived schema), register each
   step's derived schema under its `name`, return the **last** step's schema. Unique `name`s; any step's
   failure is a `BuildFailure`. `materialize` is **schema-irrelevant** (same schema either way) — it is a
   codegen emission concern only (§B.3).

3. **The `SourceSQL = JDBC prepare/metadata` schema path (§C.2) — NOT Calcite.** A NEW `source_query`
   derivation branch inside rule-24 / `read-source` in `SchemaPropagationService`. When `source_query` is
   present: substitute each `[[ … ]]` to a dummy `DATE` literal, **prepare** the query against the bound JDBC
   source (`PreparedStatement.getMetaData()` on a `SELECT … WHERE 1=0` / `LIMIT 0` round-trip), let the
   **source** validate its own dialect + functions, and map the returned `ResultSetMetaData` (`java.sql.Types`
   → PULSE types, §C.2 table; nullability = `isNullable`) onto the output port. The §A Calcite validator is
   **not** invoked here (LOCKED 2026-06-16). This is a **CREATE** task — the `ingestionSchema()` path today
   (`[read]` `SchemaPropagationService.java:861-883`) derives OUT from the **discovered dataset schema**; the
   `source_query` branch does not exist yet.

4. **The declare-schema EITHER/OR fallback (§A.6) for BOTH paths.** Derivation is primary; on a Calcite
   parse/validation **error** (`sql-model`) **or** a source-unreachable / source-prepare error (`SourceSQL`),
   fall back to an OPTIONAL `declared_output_schema` op-config field (shaped as the #1 §B.0 column-descriptor
   list) and log a warning; if absent → loud build-fail. **No cross-check, no fail-on-mismatch.**

5. **The `[[ … ]]` inline-mnemonic lowering (§D).** Reuse the existing closed vocabulary + validator
   (`[read]` `DateMnemonic.java` — `isValid:57` / `isMnemonic:77` / `validateOrThrow:105`) and runtime
   resolver (`[read]` `pulse_dates/__init__.py:22-30` `resolve_mnemonic(token, as_of, calendar_id,
   fiscal_offset_months, previous_run_date, calendar_bundle_uri, calendar_bundle_hash)`). This build adds
   **only** the embedded-token scan + per-engine lowering — it does **not** fork the 3-way-synced vocabulary.
   - **Config time:** extract each `[[ m ]]`, `DateMnemonic.validateOrThrow(m)`, loud-fail a typo.
   - **Calcite typing:** treat each token as a `DATE`-typed placeholder for parse/type/derivation only —
     in any position (predicate operand or function arg), and DATE for overload resolution (§D.3).
   - **dbt lowering:** `[[ m ]]` → `{{ var('pulse_<slug>') }}` in the model; runtime step resolves `m` and
     passes `--vars '{"pulse_<slug>":"<resolved>"}'` on the `dbt build --select …` invocation
     (`[read]` `CodeGenerationService.java:597` — the invocation today carries **no** `--vars`; this build
     adds it).
   - **PySpark / SourceSQL lowering:** runtime step resolves `m` and string-substitutes the date into the SQL.
   - After lowering, **no `[[ … ]]` survives** → `ForbiddenTokenScanner` unaffected
     (`[read]` `ForbiddenTokenScanner.java:52-67` PLACEHOLDER_TOKENS, `:71-84` INTERNAL_TOKENS).

6. **The two blueprint rows' op-list CONTENT** (the catalog-seed plan owns the migration). Supply the
   `schema_behavior` op-lists (#1 §A.1 shape) for `SqlModel` (TRANSFORM, single `sql-model` op, required
   `input` port, `sql_output` port) and `SourceSQL` (INGESTION,
   `read-source(source_query, connector_instance_id) → add-audit-columns → write-sink(bronze)`,
   `source_output` port). These land **inside the single V153 migration owned by the catalog-seed plan**
   (`IMPL-catalog-seed.md` Phase C / #2 §D) — this plan does not author its own migration (§Phase 5).
   Shapes already drafted: `[read]` `SPEC-blueprint-catalog.md:1020-1043` (SqlModel), `:1045-1069` (SourceSQL).

**Out of scope (do not build):** any new op (ADR 0012); a PULSE-side per-dialect source parser (the source
DB is the authority for `SourceSQL`); any change to the mnemonic vocabulary, validator, or resolver; the
byte-exact **anchor** (it does not use `sql-model` — §TESTS); the #4 expression-builder UI constructs
(`sql-chain-editor`, `simple-sql-builder`, `date-mnemonic-picker`) — they **inherit** §D but are spec #4's
build.

---

## PREREQUISITES (resolve BEFORE writing build code)

The spec's OPEN WORKLIST has 4 residual items that gate the build. Each must be answered (operator or
cross-spec) before the corresponding phase starts; defaults are carried so the work is unblocked, but they
are NOT operator-agreed.

- **P1 — G-1: the initial registered-function list (§A.2).** The parser **config is NOT a prerequisite** —
  it is the existing `ExpressionValidationService.java:99-113` config to COPY verbatim (`SqlBabelParserImpl.FACTORY`,
  `withConformance(SqlConformanceEnum.BABEL)`, `withQuoting(Quoting.BACK_TICK)`, `withCaseSensitive(false)`,
  `withUnquotedCasing/withQuotedCasing(UNCHANGED)`). The residual is the **Spark function registry seed**.
  *Default:* seed from the functions the live dbt/Spark codegen-examples actually emit — `[read]` from the
  templates: `SHA2`, `CONCAT_WS`, `TRY_CAST`, `DATEDIFF`, `CURRENT_DATE`/`CURRENT_TIMESTAMP`, `COALESCE`,
  `CAST`, `DATE_FORMAT`, `ROW_NUMBER`/`RANK`/`DENSE_RANK` + `OVER`/`PARTITION BY` window funcs,
  `REGEXP_REPLACE`, `SUBSTRING`, `LENGTH`, `REPEAT`, `TRIM`, `LOWER`, `UPPER`, `MAX`, `CASE/WHEN`.
  Extensible; an unknown function is a loud build-fail, not a silent pass. **Gate:** confirm the seed list is
  complete enough that no current codegen-example SQL trips it (or accept that the registry grows as authors
  hit gaps).

- **P2 — G-4: op-JSON key names + reserved `input` name, reconciled with #1 §A.1 (§B.1).** The op config
  keys `steps` / `name` / `sql` / `materialize` and the reserved relation name `input` must validate against
  #1 `SPEC-schema-op-engine.md` §A.1's op-entry `config` shape, so the `sql-model` op-entry round-trips.
  *Default:* keys as written above; reserved relation `input`. **Gate:** read #1 §A.1, confirm the
  `schema_behavior` / op-config shape accepts these keys (this also unblocks the `declared_output_schema`
  fallback field placement, §A.6).

- **P3 — G-6: blueprint keys + param spellings (`SqlModel`, `SourceSQL`; §B/§C).** Reconcile against the
  catalog spec, which has **already drafted** them: `[read]` `SPEC-blueprint-catalog.md:1030` (SqlModel param
  `steps`, port out `sql_output`, in `input`), `:1057-1058` (SourceSQL params `source_query` + the connector
  field, port out `source_output`). **Connector field name = `connector_instance_id`** (6-G06; the SINK
  convention the catalog spec flags at `:1062`) — NOT `connector_id`. Residual `> GUESS:`es to lock: whether
  `declared_output_schema` is a V153-seeded param or panel-only (`:1032-1034`, `:1063`); the `lake_layer`
  default for SqlModel (silver vs gold, `:1035`). **Gate:** one reconciliation pass with the catalog-seed plan
  owner so the single V153 seed and this build agree on the exact strings (a mismatch breaks the round-trip
  between the op and the blueprint row).

- **P4 — G-7: the mnemonic→`<slug>` rule + dedup (§D.4).** The deterministic function from a mnemonic token
  to the dbt var name `pulse_<slug>`, and dedup when the same mnemonic appears twice.
  *Default:* lowercase, non-alphanumerics → `_`, dedup identical tokens to one var (`PBD-1` → `pbd_1` →
  `pulse_pbd_1`). **Gate:** confirm the slug is collision-free across the offset/parameterized vocab (e.g.
  `NBDOM(3)` and `BOM-1` must produce distinct, valid identifier slugs) — this is a pure determinism check,
  cheap to settle.

**Already-resolved (NOT prerequisites — noted so the build doesn't re-litigate them):**
G-2 (type map, §A.3 — reconciled verbatim to #1 §B.0: 8 simple types + `struct`/`list`, no `int`/`bigint`/`map`,
generic `decimal`); G-3 (fallback is EITHER/OR with the testable trigger "Calcite raises a parse/validation
error", §A.6); G-5 (per-engine `materialize` realization pinned in §B.3); G-8 (the SourceSQL branch is a
CREATE inside rule-24, NOT a Calcite call).

**Already-present infra (copy / reuse, do NOT add):**
- **Gradle:** `[read]` `backend/build.gradle.kts:99-100` — `org.apache.calcite:calcite-core:1.39.0` +
  `org.apache.calcite:calcite-babel:1.39.0` are **already declared**. No new dep. Keep the build green.
- **Parser config:** already in `ExpressionValidationService.java:99-113` — copy it, don't pick a new
  dialect/`Lex`.

---

## BUILD PHASES (ordered)

> Files to create / modify, in dependency order. Each phase is independently testable.

### Phase 1 — Calcite schema-deriving validator (`CALCITE-PHASE-2`)
**Create** the schema-deriving validator `com.pulse.expression.service.CalciteSqlModelValidator` (co-located with
`ExpressionValidationService`, whose named "Phase 2" CALCITE-PHASE-2 extends — 6-G08/G-8 RESOLVED in spec #6 §A; the
rule-27 call site remains an explicit build-locate task, not an open decision).
*(Depends on: P1, P2.)*
- **Copy** the Babel parser config from `ExpressionValidationService.java:99-113`.
- Add the **PULSE↔Calcite type map** (§A.3): forward (PULSE schema → Calcite catalog `RelDataType`, incl.
  `struct→ROW`, `list→ARRAY`, recursive; `nullable` round-trips) and reverse (Calcite rowtype → PULSE
  column-descriptor list, recursive nested per §A.4). Unmappable type → loud build-fail.
- Add a `SqlValidator` / `Frameworks.getPlanner(...)` setup with **virtual tables per catalog relation**
  built from the type map.
- Add the **registered Spark function set** (§A.2, seeded per P1). Unknown function → loud build-fail.
- Output-schema derivation (§A.4): terminal `SELECT` rowtype → PULSE columns; name = alias or bare-column
  name; **expression column with no explicit alias → loud build-fail** (no `EXPR$n`); duplicate output name →
  loud build-fail.
- Failure semantics (§A.5): `BuildFailure{cause, sqlPosition(line/col), message}`; never emit a schema on
  failure; no AI fallback.
- Single-method contract: `(orderedSql, catalog) → PULSE column list | BuildFailure`. **SHALL NOT** execute
  SQL or resolve any date value (§A.1).
- **Decide** whether to refactor the shared Babel parser config out of `ExpressionValidationService` into a
  small shared helper, or duplicate it. (Lean: extract a tiny shared parser-config factory to keep the two
  services in lockstep — but do not change `ExpressionValidationService`'s existing parse-only behavior.)

### Phase 2 — `sql-model` chain validator + the rule-27 op-walker rule
**Create** the chain-walker (may live inside the Phase-1 service): validate `steps[]` in order against the
accumulating catalog (`input` + prior-step schemas), register each step under its `name`, return the last
step's schema (§B.2). Unique names; any failure → `BuildFailure`. *(Depends on: Phase 1, P2.)*

> **OWNERSHIP — the integration surface is the op-walker, NOT the live blueprint-key switch.** The
> `SchemaPropagationService:814` `switch (key)` is the *old* per-Blueprint design being **replaced** by the
> deterministic **op-list-walker** (keyed on OP name, not blueprint key) — that walker is **built in the
> Builder plan (`IMPL-builder.md`, #1 §B)**, not here. This plan does **not** add a `case "SqlModel"` to that
> switch. Instead, this plan supplies (a) the Calcite **validator** the op-walker's rules call, and (b) the
> two blueprints' op-list **content** (§Phase 5). The schema-effect for `sql-model` is authored as an
> **op-list-walker rule (#1 §B.1 rule 27)** keyed on the `sql-model` **op**: when the walker reaches a
> `sql-model` op-entry, it invokes the Phase-1 chain validator with the op's `steps` + the accumulated
> `input`-port schema and applies the §A.6 declare-schema fallback on Calcite error.

- **Deliver to the op-walker (Builder plan owns the walker):** the rule-27 body — "on a `sql-model` op-entry,
  call the chain validator; on Calcite error apply the §A.6 declared-schema fallback; never AI" — plus the
  Calcite validator it calls. The Builder plan wires this rule into the walker that supersedes the `:814`
  switch.
- **Important — ADR-0011 cleanup (op-keyed, not key-keyed):** the *old* switch `default` branch falls through
  to **LLM inference** (`[read]` `SchemaPropagationService.java:843-851` `schemaInferenceService.inferOutputSchema(...)`).
  Under the op-walker the `sql-model` op resolves via Calcite / the declared schema / a loud fail and **never**
  reaches an LLM path (ADR 0011). Assert (R3/§TESTS) that `sql-model` never invokes `schemaInferenceService`.
- **If an interim against the live switch is unavoidable** (i.e. the op-walker is not yet landed when this
  rule ships): scope it **explicitly as an interim** — a temporary `case "SqlModel"` in the `:814` switch that
  calls the chain validator — and **note the migration**: this interim case is deleted the moment the Builder
  plan's op-walker is in place, because the schema-effect then lives in the op-keyed rule-27, not the
  blueprint-key switch.
- Output of this phase = #1 rule-27's OUT schema (§B.4), replacing rule 27's current "declare-schema only
  (Calcite unavailable)" text once shipped.

### Phase 3 — `SourceSQL` JDBC source-prepare branch in rule-24 / `read-source`
**Modify** `SchemaPropagationService.java` (`[read]` `ingestionSchema()` `:861-883`): add a **NEW**
`source_query` derivation branch (CREATE, per G-8). When the `read-source` op config carries `source_query`:
*(Depends on: P3; independent of Phases 1–2 — can run in parallel.)*

> **ROUTING — `SourceSQL` must reach its schema path explicitly (today it leaks to default→LLM).** The
> live `:814` switch routes ingestion only via `key.endsWith("Ingestion")` (`[read]`
> `SchemaPropagationService.java:840-841`). `SourceSQL` does **NOT** end in `"Ingestion"`, so on the live
> switch it falls through to the `default → schemaInferenceService.inferOutputSchema(...)` LLM branch
> (`[read]` `:843-851`) — an ADR-0011 violation. The fix is **op-keyed**: under the op-walker (Builder plan,
> #1 §B), `SourceSQL`'s first op is `read-source` with a `source_query` in its config, so it resolves through
> the **rule-24 / `read-source` `source_query` branch** this phase builds — never through any blueprint-key
> fallback. If an interim against the live switch is unavoidable before the walker lands, add an explicit
> `SourceSQL` route (NOT relying on the `endsWith("Ingestion")` test) into this `source_query` branch, scoped
> as interim + migration-noted (mirrors Phase 2). Assert (R3/§TESTS) that `SourceSQL` never hits
> `default → schemaInferenceService` — the symmetric assertion to the `sql-model` one.
- Substitute each `[[ … ]]` to a dummy `DATE` literal **before** the prepare (§C.2 — so the source parses a
  well-formed query; value never resolved at design time).
- Prepare against the bound JDBC source (`PreparedStatement.getMetaData()` / `SELECT … WHERE 1=0` /
  `LIMIT 0`), via the connector + credential profile (SecretRefs) from the Producer Registry.
- Map `ResultSetMetaData` (`java.sql.Types` → PULSE type per §C.2 table; `TIME` → `string`; unmapped →
  loud fail; nullability = `isNullable`) onto the output port, then append the canonical audit columns
  (reuse the existing `IngestionAuditColumns` path the current `ingestionSchema()` uses, `[read]` `:881`).
- On source-unreachable / prepare-error: §A.6 declare-schema fallback, else loud-fail.
- This branch does **NOT** call the Phase-1 Calcite validator (LOCKED).

### Phase 4 — `[[ … ]]` lowering in codegen
**Create** a small embedded-token scanner+lowerer (suggested under `com.pulse.codegen` near the scanner) and
**modify** `CodeGenerationService.java`. *(Depends on: P4; reuses `DateMnemonic`/`pulse_dates` unchanged.)*
- **Config-time scan** (§D.2): extract each `[[ m ]]` from every SQL surface, `DateMnemonic.validateOrThrow(m)`,
  loud-fail typos.
- **dbt path** (§D.4): rewrite `[[ m ]]` → `{{ var('pulse_<slug>') }}` (slug per P4); emit a runtime resolve
  step; **add `--vars '{"pulse_<slug>":"<resolved>"}'`** to the `dbt build --select …` invocation
  (`[read]` `CodeGenerationService.java:597` — currently `bash_command='cd /opt/dbt && dbt build --select
  tag:…,tag:…'` with **no `--vars`**; this is the wiring to add). Note the existing `pulse_business_date` var
  convention (`[read]` `:2174-2177`) — reuse that resolve/inject mechanism; this adds the per-mnemonic vars.
- **PySpark / SourceSQL path** (§D.4): emit a runtime resolve step that string-substitutes the resolved date
  into the SQL.
- **Runtime resolve call** (§D.5): `pulse_dates.resolve_mnemonic(m, as_of=PULSE_BUSINESS_DATE (= `{{ ds }}`
  env, `[read]` `:591` Spark `env_vars` / `:599` dbt `env` / `:607` GX), calendar_bundle_uri/hash, calendar_id,
  previous_run_date=<Airflow prev run>, fiscal_offset_months=<domain.business_date_config>)` — Airflow context
  + pinned calendar bundle only, **never** the PULSE DB. Pass `previous_run_date` + `fiscal_offset_months`
  (resolves N-4 — needed for `PREVIOUS_RUN_DATE` + fiscal mnemonics).
- **Materialize emission** (§B.3, Mode-aware — #2 owns the bytes): dbt `true` → separate
  `materialized='table'` model, `false` → CTE; PySpark `true` → `.cache()`/`.checkpoint()` or temp
  write+read, `false` → DataFrame / `createOrReplaceTempView`. `materialize` never affects schema.
- **Verify** no `[[ … ]]` survives into any emitted artifact (so `ForbiddenTokenScanner` stays green).

### Phase 5 — Blueprint op-list CONTENT for the catalog-seed plan's single V153 migration
**Supply the op-list content** for the two SQL-authoring blueprints. *(Depends on: P3; runs last so
keys/params are locked.)*

> **OWNERSHIP — the single V153 migration belongs to the catalog-seed plan (#2 §D / #5), NOT this plan.**
> This plan does **not** author its own migration and does **not** pick a V-number. The two blueprint seeds
> land **inside the catalog-seed plan's one `V153__builder_op_lists_and_param_tiering.sql`**
> (`IMPL-catalog-seed.md`, Phase C — the SqlModel + SourceSQL INSERTs), authored as **`schema_behavior`
> op-lists in the #1 §A.1 shape** (`{"version","ops","blueprint_params","emission"}`) — the same shape every
> other V153 row uses (`[read]` `SPEC-codegen-compiler.md:258-287`). The old "V7 legacy
> `input_ports`/`output_ports` columns" framing is dropped: V153 writes the op-list into `schema_behavior`,
> not the throwaway pre-op-engine columns.

- **Deliver to the catalog-seed plan (`IMPL-catalog-seed.md` Phase C):** the op-list CONTENT for the two rows
  in #1 §A.1 shape —
  - **SqlModel** (TRANSFORM): op-list `[{ "op": "sql-model", … }]`; param `steps`; required `input` port +
    `sql_output` port.
  - **SourceSQL** (INGESTION): op-list `read-source(source_query, connector_instance_id) → add-audit-columns →
    write-sink(bronze)`; param `source_query` + `connector_instance_id`; `source_output` port.
- Use the connector field name **`connector_instance_id`** (per the SINK convention, 6-G06) for the SourceSQL
  seed — not `connector_id`.
- The blueprint shapes are already drafted at `SPEC-blueprint-catalog.md:1020-1069`; serialize those into the
  V153 op-list/params per #1 §A.1. **This plan supplies the CONTENT; the catalog-seed plan owns the single
  V153 migration that writes it.** (Cross-reference: `IMPL-catalog-seed.md` Phase C.)

---

## TESTS (§F unit suite — ADR 0009/0004)

Ship a unit suite proving (mirrors §F 1–5):

1. **`(input schema + SQL) → expected output schema`** for the common Spark-SQL patterns: rename, cast, join,
   aggregate, CTE, window, struct/list (nested round-trip through §A.3 both directions), and the multi-step
   `sql-model` chain (`input` + prior-step refs).
2. **Invalid SQL → `BuildFailure`**: parse error, unknown column, unknown relation, unresolved type,
   unregistered function — each yields `BuildFailure{cause, sqlPosition, message}` and **no** schema. Plus
   the §A.4 author-error cases: unaliased expression column → fail; duplicate output name → fail.
3. **Declare-schema EITHER/OR fallback (§A.6)**: Calcite-valid SQL → Calcite schema wins; Calcite **error** +
   `declared_output_schema` present → declared schema used + warning logged; Calcite error + no declared
   schema → loud build-fail. (Same three-way test for the SourceSQL source-prepare path: prepare succeeds →
   source schema; source unreachable + declared → declared + warning; unreachable + none → fail.)
4. **`[[ … ]]` lowering**: dbt (→ `{{ var('pulse_<slug>') }}` in model + `--vars` on the `dbt build`
   invocation) and PySpark (→ substituted date); Calcite types the token as `DATE` (predicate operand AND
   function arg); config-time rejection of a bad/typo mnemonic via `validateOrThrow`; assert **no `[[ … ]]`
   survives** the emitted artifact.
5. **`materialize`**: `true` emits a persisted intermediate (dbt separate model / PySpark temp), `false`
   inlines (CTE / temp view) — **both with identical derived schema**.

**JDBC source-prepare tests (§C.2):** `java.sql.Types` → PULSE map (use an H2/in-memory JDBC source or a
mocked `ResultSetMetaData`); unmapped JDBC type → loud fail; `[[ … ]]` substituted to dummy `DATE` before
prepare.

**ADR-0011 never-LLM assertions (both paths):** assert `sql-model` resolves via Calcite / declared schema /
loud-fail and **never** invokes `schemaInferenceService`; and assert `SourceSQL` resolves via the
`read-source`/`source_query` branch and **never** falls through to `default → schemaInferenceService` (the
symmetric assertion — `SourceSQL` does not end in `"Ingestion"`, so without explicit routing it would leak to
the LLM fallback at `:843-851`). Both assertions hold under the op-walker; if an interim live-switch route is
used, the same two assertions gate it.

**No anchor change.** The byte-exact anchor does **not** use `sql-model` (its silver is closed-vocab Cleaning
+ Current filter, §F). **Do NOT regress** existing `ExpressionValidationService` parse-only tests or the
`SchemaPropagationService` existing-key derivations.

---

## MILESTONES

- **M1 — Calcite derives schema** (Phases 1–2): `(input + sql-model chain) → output schema | BuildFailure`,
  wired into rule-27, LLM fallback bypassed for `sql-model`. Tests §F-1/2/3/5 green. *Largest, highest-risk.*
- **M2 — SourceSQL reads schema from the source** (Phase 3): rule-24 `read-source`/`source_query` branch
  prepares against a JDBC source, maps types, falls back on unreachable, and **never leaks to default→LLM**
  (explicit routing — `SourceSQL` does not end in `"Ingestion"`). §C.2 tests green.
- **M3 — Mnemonics lower per engine** (Phase 4): `[[ … ]]` config-validated, lowered to dbt `--vars` /
  PySpark substitution; nothing survives into artifacts. §F-4 green.
- **M4 — Blueprints in catalog** (Phase 5): `SqlModel` + `SourceSQL` op-list content (#1 §A.1 shape) delivered
  to the catalog-seed plan's **single V153 migration**; once seeded, instantiate end-to-end → schema derives →
  codegen emits. Keys/ports reconciled (P3, `connector_instance_id`).

M1 and M2 are independent (different services / catalog contexts) and can run in parallel; M3 depends on
neither's internals; M4 depends on P3 being locked.

---

## FAN-OUT (parallelizable work, dependencies noted)

- **Track A (Calcite, M1):** Phases 1+2. Owner needs P1 + P2. Self-contained Java service + the op-keyed
  rule-27 body the **Builder plan's op-walker** consumes (NOT a `SchemaPropagationService` blueprint-key case).
- **Track B (SourceSQL, M2):** Phase 3. Owner needs P3. Independent of Track A (no shared catalog context).
  Touches the JDBC connector / Producer Registry boundary — coordinate with whoever owns
  credential/connector resolution.
- **Track C (mnemonic lowering, M3):** Phase 4. Owner needs P4. Independent of A/B at the type level;
  touches `CodeGenerationService` — coordinate with #2 codegen owner (who owns the Mode-aware bytes for
  `materialize`).
- **Track D (op-list content for V153, M4):** Phase 5. Owner needs P3. **The single V153 migration is owned by
  the catalog-seed plan (`IMPL-catalog-seed.md` / #2 §D / #5)** — this track delivers the SqlModel + SourceSQL
  op-list CONTENT (#1 §A.1 shape) for that one migration to write; it does NOT author its own migration or pick
  a V-number.

**Cross-spec coordination:** #1 (rule-27 op-keyed rule body + #1 §A.1 op-config shape for P2 — the op-walker
itself is built in the Builder plan, #1 §B); #2 (codegen emission §C.4 + Mode-aware materialize); #4 (inherits
§D — the expression-builder UI constructs); the catalog-seed plan / #5 (the single V153 migration that seeds
the two new rows from this plan's op-list content).

---

## RISKS

- **R1 — Calcite type-map / rowtype fidelity (HIGH).** Calcite's inferred nullability and nested
  (`ROW`/`ARRAY`) rowtypes must reverse-map exactly to the #1 §B.0 encoding, byte-for-byte (ADR 0009).
  Window/aggregate/join nullability is where Calcite and Spark can disagree. *Mitigation:* §F-1 covers
  exactly these patterns; treat any reverse-map gap as a loud build-fail, never a guess.
- **R2 — Function-registry coverage (MEDIUM).** Too small → false build-fails on legit Spark SQL; too loose →
  silently accepts functions Spark rejects at runtime. *Mitigation:* P1 seeds from real codegen-example
  usage; registry is extensible; unknown → loud fail (correct per ADR 0011) but noisy until seeded well.
- **R3 — LLM-fallback leak (MEDIUM, ADR-0011-critical).** The `SchemaPropagationService` switch `default`
  routes to LLM inference (`:843-851`). Both SQL blueprints can leak there: `sql-model` if it ever falls
  through to `default`, and **`SourceSQL` by default today** — it does NOT end in `"Ingestion"`, so the live
  `endsWith("Ingestion")` route (`:840-841`) misses it and it lands on the LLM fallback. *Mitigation:* under
  the op-walker (Builder plan) both resolve op-keyed (`sql-model` op → rule-27; `read-source`+`source_query` →
  rule-24) and never reach `default`; tests assert **both** `sql-model` **and** `SourceSQL` never invoke
  `schemaInferenceService`. If an interim live-switch route is used, it routes both explicitly (and the two
  assertions gate it).
- **R4 — JDBC source reachability at design time (MEDIUM).** Source-prepare needs live connectivity +
  credentials in dev-builder; flaky/unreachable sources are expected. *Mitigation:* the §A.6 fallback is the
  designed escape; tests cover unreachable→declared and unreachable→fail. Ensure the prepare uses a real
  no-rows round-trip (`WHERE 1=0` / `LIMIT 0`), never executes the query for data.
- **R5 — Mnemonic slug collisions / dedup (LOW-MEDIUM).** Offset/parameterized tokens (`BOM-1`, `NBDOM(3)`)
  must produce distinct, valid SQL-identifier slugs. *Mitigation:* P4 settles the rule; a determinism test
  over the full vocab.
- **R6 — Two specs authoring V153 (LOW) — resolved by single ownership.** The risk is not a *numbering*
  collision but **two plans both trying to author the V153 migration** (this plan and the catalog-seed plan).
  *Resolution:* single ownership — the catalog-seed plan (`IMPL-catalog-seed.md` / #2 §D / #5) owns the one
  `V153__builder_op_lists_and_param_tiering.sql`; this plan supplies only the SqlModel + SourceSQL op-list
  CONTENT (#1 §A.1 shape) for it to write. This plan authors no migration, so there is no number to clash.
- **R7 — Parser-config drift (LOW).** Two services sharing the Babel config can drift. *Mitigation:* extract
  one shared parser-config factory (Phase 1) rather than duplicating; do not alter the existing parse-only
  behavior.

---

## Cross-references
- Spec: `docs/build-specs/SPEC-calcite-sql-model.md` (#6) · ADR `docs/adr/0024-…md`.
- #1 `SPEC-schema-op-engine.md` §B rule 27 / 24, §B.0 (types), §A.1 (op-entry). #2
  `SPEC-codegen-compiler.md` §C.4 / §C / §D / §E. #5 `SPEC-blueprint-catalog.md:1018-1069`.
- Live code reused: `ExpressionValidationService.java:99-113,36-42` · `SchemaPropagationService.java:814` (the
  *old* blueprint-key switch the op-walker replaces — referenced only to show the LLM-leak the op-keyed rules
  avoid), `:840-841` (the `endsWith("Ingestion")` route SourceSQL misses), `:843-851` (the default→LLM
  fallback), `:861-883` (`ingestionSchema()`) · `CodeGenerationService.java:591,597,599,607,2174-2177` ·
  `DateMnemonic.java:57,77,105` · `pulse_dates/__init__.py:22-30` · `ForbiddenTokenScanner.java:52-67,71-84` ·
  `build.gradle.kts:99-100`.
- **Single V153 migration owned by the catalog-seed plan** (`IMPL-catalog-seed.md` / #2 §D §C — citing
  `SPEC-codegen-compiler.md:258-287`); this plan supplies the two blueprints' op-list content only. The
  op-walker that consumes the op-keyed rule-27/rule-24 rules is built in the **Builder plan** (`IMPL-builder.md`
  / #1 §B).
