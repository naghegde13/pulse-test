# SPEC — The Builder Compiler: BUILD CONTRACTS (§A–E) — ⛔ SUPERSEDED

> **SUPERSEDED (2026-06-15).** This bundled §A–E draft was SPLIT into two specs per the
> operator-confirmed 5-spec map (`docs/build-specs/SPEC-INDEX.md`): **§A metadata + §B schema
> rules → `docs/build-specs/SPEC-schema-op-engine.md` (#1, DESIGN-time)**; **§C emission + §D
> V153 migration + §E oracle → `docs/build-specs/SPEC-codegen-compiler.md` (#2, BUILD-time)**.
> The 15 GUESSES / 3 CONTRADICTIONS / completeness gaps below are now attached, unresolved, to
> the "OPEN WORKLIST" section of whichever new spec owns them. **Author against the two new
> specs, not this file.** Content kept below verbatim for history only.

> Status (historical): **DRAFT for orchestrator review + independent SPEC-GATE.** Authored by a PRODUCER
> applying the LOCKED design in `docs/build-specs/SPEC-builder-compiler.md` (the Grill log +
> RESUME-HERE) and ADRs 0011/0012/0013/0023/0003/0009/0004/0006/0007/0020/0021/0022.
> This file does NOT re-decide anything; it APPLIES the locked decisions to zero-fuzziness
> build contracts. **It is NOT certified done.** Every `GUESS:` block is a gap to grill, not
> an invented fact. Evidence tags: `[read]` = confirmed at the cited file:line in this repo;
> `[report]` = transcribed from a locked doc/ADR, not re-verified here.
>
> Vocabulary (enforced): **Customer** (never "user"), **op / op-list** (never "recipe"/"steps"),
> **params** (never "settings"), **Blueprint**, **Designer/Builder** (design time vs build time).

---

## §A — METADATA MODEL

### A.0 Where the metadata lives (verified)
- The op-list is the **redefined `schema_behavior` JSONB column** on `blueprints`.
  `[read]` Field exists: `Blueprint.java:90-92` (`@Column(name="schema_behavior")
  Map<String,Object> schemaBehavior`); column added `V74:17`
  (`schema_behavior JSONB NOT NULL DEFAULT '{}'`).
- Its **current** content is throwaway `{effect_type, conflict_policy}`
  `[read]` `V74:93-97` (e.g. `SCD2Dimension → {"effect_type":"history_emitter","conflict_policy":"block"}`).
  This spec **replaces** that content; it does NOT add a new column.
- The param surface is the **`params_schema` JSONB column**, a JSON **array** of param descriptors.
  `[read]` `Blueprint.java:35-37` (`List<Map<String,Object>> paramsSchema`); array shape e.g.
  `V7:115` BronzeToSilverCleaning = `[{"name":"null_handling","type":"enum",...}, ...]`;
  upgraded arrays at `V93:16-57` (FreshnessChecks), `V92:13` etc.

> **DECISION APPLIED (locked Q1/Q3, A3):** op-list lives in `schema_behavior`; ops reference
> params by name; the param surface DERIVES from the op-list (union of ops' params +
> Blueprint-level params); emission is two-layer (Airflow universal + one compute engine per
> data-Blueprint). `[report]` SPEC-builder-compiler.md:46-77.

### A.1 `schema_behavior` shape (the op-list)

`schema_behavior` is a JSON **object** with this exact top-level shape:

```json
{
  "version": 1,
  "ops": [ <op-entry>, <op-entry>, ... ],
  "blueprint_params": [ "<param-name>", ... ],
  "emission": { "orchestration": "airflow", "compute": "<engine>|null" }
}
```

- `ops` — the **ordered** op-list. Order is the schema-propagation / emission order.
- `blueprint_params` — names of params NOT bound to any single op (e.g. `partition_by`,
  output table name); declared at Blueprint level (see A.4).
- `emission` — the emission declaration (see A.5).

**Each `<op-entry>` has this exact shape:**

```json
{
  "op": "<op-name-from-the-32-closed-vocabulary>",
  "ui_label": "<friendly section label shown op-by-op>",
  "config": { "<config-key>": <literal | param-ref>, ... }
}
```

- `op` — one of the **32 closed ops** (ADR 0012 §2). `[report]` ADR 0012 enumerates them.
  An `op` value outside the 32 = loud-fail at design time (§B, B2).
- `ui_label` — REQUIRED. The Customer-facing label for this op's config section. The config
  panel renders **op-by-op**: one friendly labeled section per op-entry, in op-list order; the
  Customer cannot add/remove/reorder ops. `[report]` SPEC-builder-compiler.md:69 ("each op-list
  entry needs a friendly UI label").
- `config` — the op's static configuration. **Each value is EITHER:**
  - a **literal** (string / number / bool / array / object — used as-is), OR
  - a **param-ref**: the exact object `{"param": "<param-name>"}`, resolved at design time
    AND build time by substituting the instance's value for that param from `params_schema`.

> WHEN the engine reads an op-entry's `config` value of the form `{"param":"<name>"}`
> THE SYSTEM SHALL substitute the running instance's value for param `<name>` before applying
> the op's schema-effect rule (design time) and before emitting the op's code fragment (build time).
> `[report]` "One op-list, two readers" — SPEC-builder-compiler.md:50,159-160.

> WHEN an op-entry's `config` references a param name absent from the Blueprint's derived param
> surface (A.4) THE SYSTEM SHALL loud-fail at design time, naming the op and the missing param.

**GUESS A-1 (FLAG — shape names invented):** the key names `version`, `ops`, `blueprint_params`,
`emission`, `op`, `ui_label`, `config`, and the param-ref token `{"param":...}` are NOT present
in any locked doc or migration — `schema_behavior` today holds only `{effect_type,conflict_policy}`
(`[read]` V74:93-97). The locked docs fix the *semantics* (op-list in `schema_behavior`, ops
reference params by name, op-by-op UI, two-layer emission) but never specify the JSON key spelling.
These names are a coherent proposal to satisfy the locked semantics; the exact spelling is a gap
to ratify before V153 is authored.

### A.2 Worked example — BronzeToSilverCleaning (the anchor's silver op)

Applying the locked Cleaning decomposition `transform-values(trim/fill-nulls) + rename-columns +
change-types + drop-columns + deduplicate`, every param optional with a do-nothing default
(unconfigured = passthrough), `null_handling="drop_row" → filter-rows`
`[report]` SPEC-builder-compiler.md:57-58,170-174 + OP-VOCAB doc:47:

```json
{
  "version": 1,
  "ops": [
    {"op":"transform-values","ui_label":"Trim & fill nulls",
     "config":{"trim_columns":{"param":"trim_columns"},
               "fill_null_map":{"param":"fill_null_map"}}},
    {"op":"rename-columns","ui_label":"Rename columns",
     "config":{"rename_map":{"param":"rename_map"}}},
    {"op":"change-types","ui_label":"Cast types",
     "config":{"type_coercions":{"param":"type_coercions"}}},
    {"op":"drop-columns","ui_label":"Drop columns",
     "config":{"drop_columns":{"param":"drop_columns"}}},
    {"op":"filter-rows","ui_label":"Drop null rows",
     "config":{"drop_when_null":{"param":"drop_null_columns"}}},
    {"op":"deduplicate","ui_label":"Remove duplicates",
     "config":{"dedup_key":{"param":"dedup_key"}}}
  ],
  "blueprint_params": [],
  "emission": {"orchestration":"airflow","compute":"dbt"}
}
```

> WHEN a Cleaning param is unset THE SYSTEM SHALL skip that op's effect (do-nothing default ⇒
> the op is a passthrough), so an unconfigured Cleaning Blueprint passes every column and row
> through unchanged. `[report]` SPEC-builder-compiler.md:57,170-174 (fixes fix-item #4).

**GUESS A-2 (FLAG — Cleaning param names invented):** today's Cleaning `params_schema` is only
`null_handling`/`dedup_key`/`type_coercions` `[read]` V7:115. The op-list above introduces
`trim_columns`, `fill_null_map`, `rename_map`, `drop_columns`, `drop_null_columns`. The locked
decomposition names the *ops* but not these param keys. V153 must (re)author the Cleaning
`params_schema` to match — the exact param names/types are a gap to ratify.

### A.3 Param-tiering shape in `params_schema` (ADR 0023)

Each element of the `params_schema` array gains TWO fields (ADR 0023 §Mechanism):

```json
{
  "name": "...", "type": "...", "required": true|false, "default": ...,
  "description": "...", "options": [...],
  "tier": "user" | "derived",
  "derivedFrom": "<resolution-source>"     // present iff tier=="derived"
}
```

- `tier` — `user` (the genuine Customer choice) or `derived` (system-resolved).
  `[report]` ADR 0023 §Mechanism: "a per-param `tier: user|derived` flag in `params_schema`
  (the only param field backend code actually reads), plus a `derivedFrom`".
- `derivedFrom` — the resolution source for a `derived` param (e.g. `"domain.calendar"`,
  `"target_dataset.state_binding"`, `"platform_default"`). `[report]` ADR 0023.

> WHEN `params_schema` omits `tier` on a param THE SYSTEM SHALL treat that param as `tier:"user"`
> (back-compat default — pre-V153 rows have no tier). **GUESS A-3 (FLAG):** the default-when-absent
> rule is not stated in ADR 0023; it is a safe-default proposal. Confirm whether absent-tier should
> instead loud-fail at design time.

> WHEN `tier=="derived"` THE SYSTEM SHALL resolve the param's value from `derivedFrom` at
> build/package time AND render it **read-only and always visible** in the config panel (never
> hidden). `[report]` ADR 0023 §Consequences + SPEC-builder-compiler.md:55.

> WHEN a `derived` param cannot resolve (e.g. domain has no calendar) THE SYSTEM SHALL loud-fail,
> except where a platform default is provably harmless (concurrency policy, evidence prefixes)
> in which case it applies that default. `[report]` SPEC-builder-compiler.md:60-62 + ADR 0011.

> Nuance — package-stamped `derived` params (e.g. calendar bundle hash) SHALL show their source
> at design time and their final value at packaging. `[report]` SPEC-builder-compiler.md:55.

### A.4 How the param surface DERIVES from the op-list

> WHEN the engine computes a Blueprint's param surface THE SYSTEM SHALL take the **union** of:
> (1) every param referenced by a `{"param":"<name>"}` in any op-entry's `config`, plus
> (2) every name in `schema_behavior.blueprint_params` (params not tied to one op, e.g.
> `partition_by`, output table name). Each name in that union MUST have a matching descriptor in
> `params_schema` carrying its type / tier / presentation metadata.
> `[report]` SPEC-builder-compiler.md:64-69 (Q3 LOCKED).

> WHEN a name in the derived union has no descriptor in `params_schema` THE SYSTEM SHALL loud-fail
> at design time (the Blueprint's metadata is incomplete — B2). `[report]` SPEC-builder-compiler.md:90-92.

The config UI stays **per-Blueprint and friendly**, driven by `params_schema`, grouped op-by-op
by the op-list; it is NEVER a generic op-list editor (op-list is backend-only).
`[report]` SPEC-builder-compiler.md:51,69,161-163.

### A.5 Emission declaration (`schema_behavior.emission`)

> WHEN the Builder emits a Blueprint THE SYSTEM SHALL emit **two layers**: (1) ALWAYS an Airflow
> DAG element (`emission.orchestration:"airflow"`) — a data-Blueprint emits a **task**, a control-
> Blueprint emits a **sensor/trigger/schedule**; one DAG per pipeline; and (2) for a data-Blueprint
> only, the **compute artifact** named by `emission.compute` ∈ `{pyspark, dbt, gx}`; a control-
> Blueprint has `emission.compute:null`. `[report]` SPEC-builder-compiler.md:73-77 (A3 LOCKED) + ADR 0012 §5.

> WHEN `emission.compute=="dbt"` THE SYSTEM SHALL choose the dbt **kind per-op**: a
> `track-history-scd2` op ⇒ dbt `snapshot`; a `take-periodic-snapshot` op ⇒ dbt `incremental`
> model; every other dbt op ⇒ a standard dbt model. `[report]` SPEC-builder-compiler.md:74-77.

Emission-by-category (applying OP-VOCAB doc:43-66):
- **Ingestion (6)** → `compute:"pyspark"`.  **Sink (4)** → `compute:"pyspark"`.
- **Transform (10)** → `compute:"dbt"`.  **Modeling (8)** → `compute:"dbt"` (SCD2's op makes its
  kind `snapshot`; SnapshotModel's op makes its kind `incremental`).
- **Data Quality (4)** → `compute:"gx"`.  **Control (11)** → `compute:null` (DAG-only).
`[report]` OP-VOCABULARY-AND-DECOMPOSITION.md:43-66.

---

## §B — SCHEMA ENGINE (the 32 ops' schema-effect rules)

**Authority (B1, locked):** the **design-time** schema is the column authority — Schema
Propagation deterministically computes the full schema (every column, every op) live as the
Customer designs; re-propagates on every edit; surfaces downstream conflicts. Build-time codegen
is **subordinate** (it produces exactly these columns, never decides them). Runtime validates the
produced table matches the contract → loud-fail. **No repair loop** (no LLM in codegen).
`[report]` SPEC-builder-compiler.md:79-88 + ADR 0011 §Decision.

This REPLACES the hardcoded switch `SchemaPropagationService.deriveBaseOutputSchema:814-854`
`[read]` (the `switch (key)` over blueprint key) — the engine instead walks `schema_behavior.ops`
in order and applies each op's rule below to the input column set(s).

### B.0 The column model (B5 — nested types)
A column descriptor is `{name, type, nullable, ...tags}` where `type` is one of:
- **simple** — `string | integer | long | double | decimal | boolean | date | timestamp`
- **struct** — `{"kind":"struct","fields":[<column-descriptor>, ...]}` (recursive named sub-fields)
- **list** — `{"kind":"list","element":<type>}`

> WHEN an op needs a nested type THE SYSTEM SHALL source it from schema discovery (sampling),
> never from an LLM. `[report]` SPEC-builder-compiler.md:110-112 (B5) + ADR 0011.

**GUESS B-0 (FLAG — nested type JSON spelling invented):** ADR 0011 + the grill lock the *model*
(simple|struct|list, from discovery) but not the JSON encoding. The `{"kind":"struct","fields":[]}`
/ `{"kind":"list","element":...}` spelling is a proposal. Today the schema descriptors are flat
`{name,type}` `[read]` IngestionAuditColumns.java:59-71. Ratify the encoding before §B is built.

### B.1 The 32 schema-effect rules (columns in → out)

Notation: `IN` = input column set (ordered); `IN2` = secondary input (for `join`); `OUT` = output.

**Column ops**
1. **add-column** — OUT = IN + one new column `{name, type(declared), expr}`. Derived columns
   REQUIRE a declared output type (validation-enforced). Window functions are this op (a window
   expression), NOT a separate op. `[report]` ADR 0011 §Consequences ("derived_columns require a
   declared output type") + OP-VOCAB doc:18,22. Today implemented as `applyDerivedColumns`
   `[read]` SchemaPropagationService.java:963-988.
2. **transform-values** — OUT = IN, columns & rows UNCHANGED (replaces a column's *values* via an
   expression: trim/fill-nulls/standardize). `[report]` OP-VOCAB doc:22.
3. **drop-columns** — OUT = IN minus the named columns. `[read]` `applyDroppedColumns`
   SchemaPropagationService.java:996-1009.
4. **keep-columns** — OUT = only the named columns, in named order. `[report]` OP-VOCAB doc:24.
5. **rename-columns** — OUT = IN with names remapped per `rename_map`; types/order preserved.
6. **change-types** — OUT = IN with named columns' `type` replaced per `type_coercions`.
7. **mask-columns** — OUT = IN; masked columns gain `lineage="masked:<strategy>"`, tags
   `["masked","pii"]`, a `transform{kind:mask,strategy}`; **type rule:** `hash → string`, every
   other strategy preserves source type. `[read]` `maskSchema` + `maskedTypeFor`
   SchemaPropagationService.java:896-954.
8. **flatten-json** — OUT = IN with each `struct` column expanded into its flat sub-fields
   (nested struct → flat). `[report]` SPEC-builder-compiler.md:112 + OP-VOCAB doc:26.
9. **build-struct** — OUT = IN with named columns packed into one `struct` column (+ `drop-columns`
   of the packed sources when configured). `[report]` SPEC-builder-compiler.md:112 + OP-VOCAB doc:26,52.

**Combine/reshape ops**
10. **join** — OUT = all IN columns + IN2 columns; on a same-name collision: if types DIFFER keep
    BOTH sides, prefixing the right side `right_<name>`; if types MATCH **keep both sides under the
    `right_` prefix** (FIX #5 — keep both, do not drop the right col). `[read]` current `mergeJoin`
    SchemaPropagationService.java:1022-1041 — **today on matching type it KEEPS PRIMARY ONLY**
    (:1038 "matching type on same name: keep the primary column, no duplicate"); FIX #5 changes this
    to emit `right_<name>` so both sides survive. `[report]` OP-VOCAB doc:78 + SPEC-builder-compiler.md:96.
11. **group-and-aggregate** — OUT = group-by columns (types from IN) + one column per aggregation
    `{alias, type}`. **Aggregate output types (FIX #6):** `COUNT/COUNT_DISTINCT → long`;
    `SUM` on integer → `long`, `SUM` on decimal → `double`; `AVG → double`; `MIN/MAX → source type`.
    `[read]` current `aggregateSchema` SchemaPropagationService.java:1043-1072 — **today wrong**:
    COUNT→`integer` (:1064), SUM/AVG→`decimal` (:1065). `[report]` OP-VOCAB doc:79 + SPEC-builder-compiler.md:96.
12. **union-all** — OUT = IN (all inputs share schema; rows concatenated incl. duplicates). *Unused
    by catalog; kept for completeness.* `[report]` OP-VOCAB doc:70.
13. **distinct-union** — OUT = IN (union then dedupe). *Unused; kept.* `[report]` OP-VOCAB doc:70.
14. **sort** — OUT = IN (order only; schema unchanged). *Unused; kept.* `[report]` OP-VOCAB doc:70.
15. **sample-limit** — OUT = IN (row subset; schema unchanged). *Gets its own atomic Blueprint.*
    `[report]` OP-VOCAB doc:70 + ADR 0012 §6.

**Row ops** (schema unchanged; rows change)
16. **filter-rows** — OUT = IN (schema unchanged; rows filtered). `[report]` OP-VOCAB doc:28.
17. **deduplicate** — OUT = IN (schema unchanged; duplicate rows removed by `dedup_key`).
    Requires a deterministic tiebreaker for byte-exactness. `[report]` OP-VOCAB doc:28 + ADR 0009.
18. **route-rows** — OUT = the INPUT schema carried to **each** dynamic output port (B4: one port
    per Customer-defined branch; routing splits ROWS, not columns). `[report]` SPEC-builder-compiler.md:106-108
    (B4) + OP-VOCAB doc:74 (FIX #1).
19. **merge-rows** — OUT = IN (upsert/merge by key; schema unchanged). `[report]` OP-VOCAB doc:28.

**History ops**
20. **track-history-scd2** — OUT = IN + dbt-snapshot system columns
    **`dbt_valid_from` (timestamp), `dbt_valid_to` (timestamp), `dbt_scd_id` (string),
    `dbt_updated_at` (timestamp)`** (FIX #2). `[read]` current rule at
    SchemaPropagationService.java:823-828 is **WRONG/TRANSPOSED** — emits `valid_from/valid_to/
    is_current`; the codegen actually emits `dbt_valid_*` `[read]` CodeGenerationService.java:2158-2164.
    FIX #2 makes the rule match the dbt-snapshot column set. `[report]` OP-VOCAB doc:31,75.
21. **take-periodic-snapshot** — OUT = IN (source) + **`ds` (date partition) +
    `_pulse_processing_ts` (timestamp) + `_pulse_run_id` (string) + `_pulse_snapshot_model`
    (string)`** (FIX #3). `[read]` current rule at SchemaPropagationService.java:829-834 is
    **WRONG/TRANSPOSED** — emits `dbt_valid_*`; codegen actually emits `ds`+`_pulse_*`
    `[read]` CodeGenerationService.java:2230-2236. #2 and #3 are swapped today. `[report]` OP-VOCAB doc:32,76.

**Quality ops (GX)**
22. **check-data** — OUT = IN (schema unchanged on the main path); a FAILING check raises and
    **fails the run** when `on_failure=block`; quarantine = a `filter-rows`-derived **managed
    side-table** of bad rows (auto-materialized). Quarantine, `on_failure`, `report_mode` are
    Customer params (B3). `[report]` SPEC-builder-compiler.md:100-104 (B3) + OP-VOCAB doc:33,61 + ADR 0012.
23. **emit-report** — side-output: an **append-only** report table (NOT the main data). Main path
    OUT = IN. `report_mode` (append=history / overwrite) is a Customer param, default `append`
    (FIX #7 — today overwrites). `[report]` SPEC-builder-compiler.md:100-104 + OP-VOCAB doc:34,80.

**Movement ops**
24. **read-source** — OUT = the source dataset's discovered schema (columns from schema discovery
    / declared dataset schema). `[read]` `ingestionSchema` sources the dataset schema +
    audit columns SchemaPropagationService.java:861-883.
25. **add-audit-columns** — OUT = IN + the **PULSE audit column set**: `_pulse_ingested_at`(ts),
    `_pulse_processing_ts`(ts), `_pulse_pipeline`(string), `_pulse_task`(string), `_pulse_run_id`
    (string), `_pulse_source_uri`(string), `_pulse_business_date`(date). `[read]`
    IngestionAuditColumns.java:36-46 (NAMES = exactly **7** columns) + asColumnDescriptors:59-71.
    **⚠ SEE GUESS E-1 — the anchor answer key expects 8 audit columns, not 7.**
26. **write-sink** — OUT = IN (writes rows to a destination; schema unchanged). `[report]` OP-VOCAB doc:63.

**History/power-user**
27. **sql-model** — OUT = the SQL's output columns, derived by **Calcite-validating the
    user-authored dbt SQL against its input schema** OR a **declared** output schema. dbt-SQL
    emission. `[report]` ADR 0013 §3 + OP-VOCAB doc:36,39 + SPEC-builder-compiler.md:23.

**Control ops (portless — no schema effect)**
28. **sense** — DAG-only; no columns. `[report]` OP-VOCAB doc:38,65.
29. **schedule-and-triggers** — DAG-only; no columns. `[report]` OP-VOCAB doc:38,65.
30. **rollback** — DAG-only; no columns. `[report]` OP-VOCAB doc:38,65 + ADR 0020.
31. **advance-time** — DAG-only; no columns. `[report]` OP-VOCAB doc:38,65 + ADR 0023.
32. **invoke-remote** — DAG-only; no columns. `[report]` OP-VOCAB doc:38,65 + ADR 0021.

> **Universal addenda (KEPT):** after the op-list's rules run, the engine applies
> `derived_columns` and `dropped_columns` addenda (available on every TRANSFORM/MODELING
> Blueprint). `[read]` SchemaPropagationService.java:802-807, 963-1009. **GUESS B-1 (FLAG):**
> with `add-column`/`drop-columns` now first-class ops, whether the universal addenda are kept
> as-is, folded into the ops, or removed is not settled in the locked docs — flag for the
> orchestrator (it affects whether derived columns are declared per-op or globally).

### B.2 Three-part enforcement (B1)
> WHEN the Customer edits a composition THE SYSTEM SHALL re-run Schema Propagation over the
> op-lists, recompute every column at every op, and surface any downstream conflict. (Design-time
> column authority.) `[report]` SPEC-builder-compiler.md:79-84.

> WHEN the Builder emits an op's code THE SYSTEM SHALL produce exactly the design-time columns and
> never decide columns itself (codegen subordinate). `[report]` SPEC-builder-compiler.md:84-86.

> WHEN a generated table is produced at runtime THE SYSTEM SHALL validate its columns against the
> contract and loud-fail on mismatch; explicit DDL SHALL be hand-generated ONLY where the engine
> does not make the table (PySpark bronze / external tables). THE SYSTEM SHALL NOT run any LLM
> repair loop. `[report]` SPEC-builder-compiler.md:86-88 + ADR 0011 §3.

### B.3 Unknown op → loud-fail; remove the LLM fallback
> WHEN an op is outside the 32-op vocabulary, OR a Blueprint has no op-list, OR an op has no
> schema rule THE SYSTEM SHALL loud-fail at design time, surfaced clearly, blocking that
> Blueprint's use until its metadata is complete — never silent passthrough, never an LLM guess.
> `[report]` SPEC-builder-compiler.md:90-92 (B2) + ADR 0011 §Consequences.

> THE SYSTEM SHALL DELETE the LLM fallback at `SchemaPropagationService.deriveBaseOutputSchema`
> `[read]` :843-851 (`schemaInferenceService.inferOutputSchema(...)`) and SHALL rewrite the test
> `SchemaPropagationServiceTest.propagate_inferenceFallbackUsedWhenNoRule` `[read]` :579
> (which today stubs `schemaInferenceService.inferOutputSchema` :598 and `verify(...).inferOutputSchema`
> :603) to assert a **loud-fail / conflict** on an unknown Blueprint instead. `[report]` ADR 0011
> §Consequences ("`propagate_inferenceFallbackUsedWhenNoRule` is rewritten to assert this").

This also closes FIX #8 — the 5 modeling Blueprints (FactBuild / WideDenormalizedMart /
AggregateMaterialization / FeatureTablePublish / ReferenceDataPublish) that today fall to the LLM
default `[read]` :835 now resolve through their op-lists (`join`/`group-and-aggregate`/`keep-columns`/
`deduplicate`/`filter-rows`/`write-sink`). `[report]` OP-VOCAB doc:55-59,81.

---

## §C — EMISSION (per-op handler per engine)

Each op has a tested codegen handler emitting its fragment per emission engine. A Blueprint's code
= the deterministic composition of its op-list handlers (no LLM). `[report]` ADR 0013 §1.
Engines: **dbt-SQL · PySpark · GX · dbt-snapshot (dbt kind) · DAG-only (Airflow).**

> WHEN the Builder composes a data-Blueprint's compute artifact THE SYSTEM SHALL fuse consecutive
> same-engine, same-layer ops (dbt `ephemeral` CTEs / chained Spark DataFrames) and materialize a
> **tier-3 real (contract) table** ONLY at medallion boundaries (bronze/silver/gold), DQ gates,
> and engine crossings (PySpark→dbt); tier-2 temp tables are a per-chain-element performance choice
> for the SQL-chaining path. `[report]` ADR 0003 §Refinement.

### C.1 Per-op × per-engine handler table (abbreviated; full handler = build task)
| Op | dbt-SQL | PySpark | GX | dbt-kind |
|---|---|---|---|---|
| add-column | `<expr> AS <name>` in SELECT | `.withColumn(name, expr)` | n/a | model |
| transform-values | wrap col in SELECT (trim/coalesce) | `.withColumn(col, expr)` | n/a | model |
| drop/keep-columns | SELECT projection | `.drop()/.select()` | n/a | model |
| rename-columns | `col AS new` | `.withColumnRenamed` | n/a | model |
| change-types | `CAST(col AS t)` | `.cast(t)` | n/a | model |
| mask-columns | hash/redact expr | `.withColumn(sha2/...)` | n/a | model |
| flatten-json/build-struct | `.` / `struct()` SQL | `.select(col.*)` / `struct()` | n/a | model |
| join | `JOIN ... ON`, `right_`-prefix collisions | `.join()` | n/a | model |
| group-and-aggregate | `GROUP BY` + agg fns | `.groupBy().agg()` | n/a | model |
| filter-rows/deduplicate | `WHERE` / `QUALIFY row_number()` w/ ORDER BY | `.filter()/.dropDuplicates` | n/a | model |
| route-rows | one model per branch (dynamic outputs) | n/a | n/a | model |
| merge-rows | `incremental_strategy='merge'` | n/a | n/a | incremental |
| track-history-scd2 | n/a | n/a | n/a | **snapshot** (`{% snapshot %}`) |
| take-periodic-snapshot | `{{config(materialized='incremental')}}` | n/a | n/a | **incremental** |
| check-data / emit-report | n/a | GX checkpoint reads delta, writes side-table | GX suite | n/a |
| read-source / add-audit-columns / write-sink | n/a | PySpark read / `IngestionAuditColumns.emitPyspark` / write | n/a | n/a |
| sql-model | user dbt SQL (Calcite-validated) | n/a | n/a | per-config |
| sense/schedule/rollback/advance-time/invoke-remote | n/a | n/a | n/a | **DAG-only** (Airflow operator) |

Grounding references for handlers:
- **SnapshotModel** dbt-incremental emission `[read]` CodeGenerationService.java:2170-2239 (config
  block :2206-2221; the `ds` + `_pulse_processing_ts/_pulse_run_id/_pulse_snapshot_model` SELECT
  :2230-2236 — the FIX #3 column set).
- **SCD2** dbt-snapshot emission `[read]` CodeGenerationService.java:2104-2167 (the `dbt_valid_*`
  set is managed by the snapshot strategy; FIX #10 = drop the redundant custom `effective_from/to`
  cols at :2162-2164).
- **Audit columns** PySpark emit `[read]` IngestionAuditColumns.emitPyspark:87-138 (single source
  of truth, matches the design-time set).
- **GX/DQ** checkpoint emit `[read]` CodeGenerationService.java:4232-4293; the FIX #7 target is
  here — today `mode('overwrite')` at :4281,4284 must become **append** for report tables, and
  semantic reports must **raise on FAIL** when `on_failure=block` (today report-only).
- **Emission examples corpus** (reference for handler authors, NOT runtime LLM grounding per
  ADR 0013 §4) `[read]` `backend/src/main/resources/codegen-examples/` (8 dirs: ingestion,
  staging, snapshots, marts, intermediate, sinks, gx, orchestration). FIX #11 = remove the 2
  orphaned example files (V94 leftovers). `[report]` OP-VOCAB doc:84.

### C.2 Mode-aware emission (ADR 0006/0007)
> WHEN the active Mode is `GCP_PULSE` THE SYSTEM SHALL emit Spark execution as a
> `DataprocCreateBatchOperator` (Dataproc Serverless) and write bronze/silver as **Iceberg-on-GCS**
> (Hadoop/GCS catalog) for the interim, with **BigQuery-managed Iceberg** as the tracked target;
> WHEN the active Mode is `DPC_PULSE` THE SYSTEM SHALL emit Spark via **Apache Livy** and write
> **Hive/Parquet** (Delta is DPC's format per ADR 0001). Plain `SparkSubmitOperator` is the target
> for neither Mode. `[report]` ADR 0006 §Decision + ADR 0007 §Decision. Mode resolved via
> `RuntimeAuthorityService`. **GUESS C-1 (FLAG):** ADR 0001 says Delta=DPC; the task prompt says
> "DPC Livy + Hive/Parquet". "Hive/Parquet" vs "Delta" for DPC silver is unsettled across sources
> — flag the DPC silver/bronze format.

### C.3 Config-externalization (ADR 0013)
> WHEN the Builder emits any artifact THE SYSTEM SHALL make generated code read its per-env config
> from an **env-var-selected per-env config slice**, baking NO literal connection strings / paths /
> project IDs into the code. `[report]` SPEC-builder-compiler.md:21-22 + ADR 0013. Today the audit
> emit already reads env vars (`PULSE_RUN_ID`/`PULSE_BUSINESS_DATE`/`PULSE_SOURCE_URI`)
> `[read]` IngestionAuditColumns.java:108-121. **GUESS C-2 (FLAG):** the concrete per-env-slice
> selection mechanism (env var name, file layout) is not specified in the locked docs — gap to
> design.

### C.4 sql-model + Calcite Phase-2
> WHEN a `sql-model` op carries user dbt SQL THE SYSTEM SHALL derive its output schema by
> Calcite-validating the SQL against the input schema (or use the declared schema), and SHALL fail
> the build if Calcite cannot validate it. `[report]` ADR 0013 §3 + OP-VOCAB doc:36,39. The Calcite
> "Phase-2" validator is a **build prerequisite**. **GUESS C-3 (FLAG):** no Calcite validator exists
> in the repo today (the "Expression Builder Phase-2 gap"); this contract assumes it is built —
> flag as a hard prerequisite, not an existing capability.

### C.5 DQ ops (check-data + quarantine; emit-report)
> WHEN a `check-data` op runs with `on_failure=block` and a check FAILS THE SYSTEM SHALL fail the
> Airflow task; WHEN quarantine is configured THE SYSTEM SHALL route failing rows to a managed
> side-table (a `filter-rows`-derived, auto-materialized table). WHEN an `emit-report` op runs with
> `report_mode=append` THE SYSTEM SHALL append to the report table (history), and with
> `report_mode=overwrite` SHALL replace it. `[report]` SPEC-builder-compiler.md:100-104 (B3) +
> ADR 0012 §Quarantine. (FIX #7 — today overwrite + report-only `[read]` :4281,4284.)

### C.6 Router dynamic outputs (B4)
> WHEN a `route-rows` op declares N branches THE SYSTEM SHALL emit N output ports (one per branch:
> label + condition; optional catch-all default), each carrying the input schema, and grow the
> canvas a port per branch. `[report]` SPEC-builder-compiler.md:106-108 (B4) + OP-VOCAB doc:74.
> (FIX #1 — today codegen emits N+1 models but schema-prop gives one passThrough port
> `[read]` SchemaPropagationService.java:815-817.)

### C.7 Data-aware edges (C1)
> WHEN two pipelines on the SAME Airflow have a cross-pipeline dependency THE SYSTEM SHALL emit
> native Airflow Datasets: the producer gets `outlets=[Dataset(uri)]`, the consumer gets
> `schedule=[Dataset(uri)]`, where PULSE **generates the canonical URI** from the registered
> dataset the Customer selected (the Customer never sees URIs or Airflow syntax); the dependency
> edge SHALL be surfaced on the canvas. WHEN the dependency is CROSS-Airflow (separate instance/
> Mode) THE SYSTEM SHALL emit a `RemotePipelineInvocation` (invoke-remote op) instead.
> `[report]` SPEC-builder-compiler.md:115-122 (C1) + ADR 0021 + ADR 0022. Build = wire the existing
> unwired `DatasetScheduleService` + fix the `event`/`dataset_event` mismatch `[report]`
> SPEC-builder-compiler.md:121-122 (untagged in source — verify before building). **GUESS C-4 (FLAG):**
> the canonical-URI format (e.g. `pulse://<tenant>/<domain>/<dataset>`) is not specified in any
> locked doc — gap to ratify.

---

## §D — V153 MIGRATION

**Version:** `V153` (G1's V152 is integrated; current head on this branch is **V151**
`[read]` `V151__widen_user_git_identities_scopes.sql` is the highest migration present; no V152
exists on this branch). File name SHALL be
`backend/src/main/resources/db/migration/V153__builder_op_lists_and_param_tiering.sql`.

> WHEN V153 runs THE SYSTEM SHALL, for each of the **39 surviving Blueprints**, write its op-list
> into `schema_behavior` (replacing the throwaway `{effect_type,conflict_policy}`) and write
> `tier`/`derivedFrom` into each `params_schema` element. `[report]` SPEC-builder-compiler.md:24-25,
> 33 + OP-VOCAB doc:41 (39 survivors).

The 39 op-lists to seed are the decompositions in OP-VOCAB doc:43-66 (e.g. Ingestion ×6 =
`read-source→add-audit-columns→write-sink(bronze)`; BronzeToSilverCleaning per A.2; SCD2Dimension=
`track-history-scd2`; SnapshotModel=`take-periodic-snapshot`; FactBuild=`join+keep-columns`; etc.).
`[report]` OP-VOCABULARY-AND-DECOMPOSITION.md:43-66.

> WHEN V153 runs THE SYSTEM SHALL deprecate the **4 dead Blueprints** following the V81 deprecation
> shape `[read]` V81:19-40 (`UPDATE blueprints SET status='deprecated', deferred=true,
> replacement_blueprint_key=<repl-or-NULL>`):
> - `ObjectStoreKeySensor` → `replacement_blueprint_key='FileArrivalSensor'` (strict subset; ADR 0022).
> - `DatasetDependencySensor` → `replacement_blueprint_key='ScheduleAndTriggers'` (trigger, not
>   pull-sensor; ADR 0021/0022).
> - `BackfillAndReplay` → `replacement_blueprint_key='BulkBackfill'` (replay half parked as a
>   future domain-level op, not a Blueprint; ADR 0020).
> - `CostMonitoringHook` → `replacement_blueprint_key=NULL` (FinOps concern, no emittable behavior;
>   ADR 0020).
> `[report]` SPEC-builder-compiler.md:129-130 + OP-VOCAB doc:66 + ADRs 0020/0021/0022.

> WHEN V153 deprecates a Blueprint THE SYSTEM SHALL also pin its `add_surface='none'`
> (deprecated rows cannot be instantiated). `[read]` Blueprint.java:110-116 (`add_surface`,
> "deprecated/deferred rows are pinned to `none`").

**GUESS D-1 (FLAG):** the 4 deprecations' `status/deferred/replacement_blueprint_key` UPDATEs follow
V81's shape exactly, but I did not verify that all 4 keys (`ObjectStoreKeySensor`,
`DatasetDependencySensor`, `BackfillAndReplay`, `CostMonitoringHook`) still exist as `active` rows
to deprecate (some may have been removed by a prior migration, like QuarantineBadRecords was by V9).
V153 must guard each UPDATE idempotently (no-op if absent), per V81's own convention `[read]` V81:11-13.

**GUESS D-2 (FLAG):** the exact per-Blueprint op-list JSON for all 39 (config param-refs +
`ui_label`s) is NOT authored here — it is the mechanical drafting the spec defers to agents
(producer ≠ verifier; SPEC-builder-compiler.md:136). The DECOMPOSITION (which ops, in what order)
is locked; the param-ref wiring per op depends on ratifying GUESS A-1/A-2 first.

---

## §E — ORACLE (the anchor byte-exact test)

**Anchor:** dataset `loan_master`, tenant **`tenant-home-lending`** `[read]`
CanonicalLoanMasterAirflowRuntimeIT.java:58 (`TENANT_ID = "tenant-home-lending"`). Pipeline:
GCS file → bronze → silver. `[report]` ADR 0004 + ANCHOR-PIPELINE-SPEC.md:19-29.

**The deterministic oracle (ADR 0009 method):**
> THE behavioral test SHALL author a deterministic reference output (the exact expected table),
> independent of any LLM, with deterministic tiebreakers (explicit ORDER BY for any dedup/ranking);
> the evaluator SHALL generate the Builder's code **2–3 times**, run each, and assert every output
> is byte-identical to each other AND to the reference; any divergence = FAIL = a real bug;
> runtime-only audit columns (ingest timestamps, run ids) are excluded/normalized from the diff
> (the byte-exact check is on the business data). `[report]` ADR 0009 §Decision.

**Verified answer key facts:**
- Source CSV `data/loan_master.csv` = **500 rows × 78 source columns**. `[read]`
  data-oracle.json:9-10 (`"row_count":500, "column_count":78`); file SHA256
  `e3e56e4d...75b67a`, canonical-csv SHA256 `c8ec1c0d...59bc8f` `[read]` data-oracle.json:5-7.
- Answer key verified **242/242** checks vs the real CSV (2026-06-13). `[report]`
  PULSE-MAP.md:167,240 + ANCHOR-PIPELINE-SPEC.md:12.
- **Bronze** `loan_master_bronze` = **500 rows × 86 columns = 78 source + 8 PULSE audit**. `[report]`
  ANCHOR-PIPELINE-SPEC.md:25,38-42.
- **Silver** (current-loans, `loan_status="Current"`) = **290 rows**. `[read]` data-oracle.json:428,527
  (`"Current":290`) + loan-master-scenario-families.json:221 (`"row_count":290`, derivative
  `current_loans`).

> WHEN the evaluator runs the anchor THE SYSTEM SHALL assert: bronze EXISTS with 500 rows and 86
> columns (78 source matching the CSV by row-count + schema + content hash, excluding the audit
> columns from the byte-diff); silver EXISTS with 290 rows (current loans). `[report]`
> ANCHOR-PIPELINE-SPEC.md:31-43.

**Deterministic re-run / diff method:**
> THE evaluator SHALL run the Builder-generated job 2–3 times against the same input, normalize the
> audit columns out, and diff the business data byte-for-byte vs the reference oracle; identical
> across runs AND vs reference ⇒ PASS, any byte divergence ⇒ FAIL. `[report]` ADR 0009.

**GUESS E-1 (FLAG — a real CONTRADICTION between two trusted sources, must grill):**
The anchor answer key requires **8** audit columns: the ANCHOR-PIPELINE-SPEC lists
`_pulse_ingested_at, _pulse_processing_ts, _pulse_pipeline, _pulse_task, _pulse_run_id,
_pulse_source_uri, _pulse_business_date, created_as_timestamp` `[read]` ANCHOR-PIPELINE-SPEC.md:38-42
(78 source + **8** audit = 86). But `IngestionAuditColumns` — described in-code as the **single
source of truth, "must not be a second copy anywhere else"** — defines exactly **7** columns
(`NAMES`, no `created_as_timestamp`) `[read]` IngestionAuditColumns.java:44-46. The `add-audit-columns`
op rule in §B.25 therefore yields 78+7 = **85**, not 86. **This must be resolved before the anchor
oracle can pass:** either (a) `created_as_timestamp` is added to `IngestionAuditColumns` (making the
op emit 8 and bronze 86), or (b) the anchor answer key drops it (bronze 85). I did NOT resolve it —
it is a genuine contradiction, not a producer choice.

**GUESS E-2 (FLAG — silver 87-column count unverified):** the task prompt states "silver 290×87".
The `current_loans` oracle derivative tracks **78 business columns** (`column_count:78`) `[read]`
loan-master-scenario-families.json:34,221 — it does NOT assert 87. 87 would be 78 source + 8 audit
+ 1, or 78 + 8 audit + 1 cleaning-added column. The derivation of "87" is not grounded in any read
artifact; the oracle derivative I found asserts 78 business cols + 290 rows for silver. Flag the
exact silver column count (and whether silver carries the bronze audit columns through) as a gap.

**GUESS E-3 (FLAG — Leg-2 silver = Cleaning, not a row-filter):** ANCHOR-PIPELINE-SPEC.md:28 defines
Leg 2 as `bronze → Bronze-to-Silver Cleaning → silver`. The "290 current loans" figure comes from a
`loan_status="Current"` filter, which Cleaning does NOT inherently do (Cleaning = trim/cast/rename/
dedup). Whether the anchor silver is (a) Cleaning output of all 500 rows, or (b) a `current_loans`
derivative that additionally filters to 290, is ambiguous: the oracle's `current_loans` derivative
is row-filtered to 290, but the anchor pipeline op is Cleaning. Flag which op produces the 290-row
silver before authoring its op-list answer key.

---

## Open gaps summary (every GUESS, for the grill)
- **A-1** — `schema_behavior`/op-entry JSON key spelling (version/ops/op/ui_label/config/`{"param"}`) invented; ratify before V153.
- **A-2** — Cleaning param names (trim_columns/fill_null_map/rename_map/drop_columns/drop_null_columns) invented; V153 must (re)author Cleaning `params_schema`.
- **A-3** — default behavior when `tier` is absent on a param (proposed `user`) not in ADR 0023.
- **B-0** — nested-type JSON encoding (`{"kind":"struct"...}`) invented; ratify.
- **B-1** — fate of the universal `derived_columns`/`dropped_columns` addenda now that add-column/drop-columns are ops.
- **C-1** — DPC bronze/silver format (Hive/Parquet vs Delta) conflicts across ADR 0001 vs the task prompt.
- **C-2** — concrete per-env config-slice selection mechanism unspecified.
- **C-3** — Calcite Phase-2 validator does not exist yet (hard build prerequisite for sql-model).
- **C-4** — canonical Airflow-Dataset URI format unspecified.
- **D-1** — verify all 4 deprecation targets still exist as active rows; guard idempotently.
- **D-2** — the 39 per-Blueprint op-list JSONs (config param-refs) are deferred mechanical drafting; depend on A-1/A-2.
- **E-1** — **CONTRADICTION:** anchor expects 8 audit cols (incl. `created_as_timestamp`), `IngestionAuditColumns` defines 7 → bronze 86 vs 85.
- **E-2** — silver "87 columns" not grounded; oracle derivative asserts 78 business cols.
- **E-3** — which op produces the 290-row silver (Cleaning vs a current-loans filter) is ambiguous.
