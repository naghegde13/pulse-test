# SPEC #1 — Schema / Op Engine (DESIGN-time; the column authority)

> Status: **DRAFT — split from the bundled `SPEC-builder-compiler-CONTRACTS.md` per the
> operator-confirmed 5-spec map (`SPEC-INDEX.md`).** This file carries **§A metadata model**
> + **§B the 32 ops' schema-effect rules** (propagation / conflict / enforcement). The
> **build-time** halves (emission §C, V153 migration §D, byte-exact oracle §E) live in the
> sibling spec **`docs/build-specs/SPEC-codegen-compiler.md`** (#2).
>
> **Decision record (cited, not re-decided here):** `docs/build-specs/SPEC-builder-compiler.md`
> (the Grill log + RESUME-HERE — the locked design) and ADRs 0011 (deterministic schema =
> enforced contract) · 0012 (behavior = composable primitive ops; 32-op closed vocabulary) ·
> 0023 (param-tiering). This spec **applies** those decisions; it does not re-decide them.
>
> **Coverage basis:** the **32 ops' schema-effect rules** + the **op-list metadata model**
> (`schema_behavior` shape + param-tiering) + **ADRs 0011 / 0012 / 0023**. The SPEC-GATE's
> completeness pass checks this spec against exactly that basis.
>
> Evidence tags: `[read]` = confirmed at the cited file:line in this repo; `[report]` =
> transcribed from a locked doc/ADR, not re-verified here.
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
- `emission` — the emission declaration (see A.5). **The emission HANDLERS that consume this
  declaration are specified in #2 (`SPEC-codegen-compiler.md` §C); this spec defines only the
  declaration's shape.**

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

> **RESOLVED (G-1 — locked 2026-06-15): this JSON shape IS the contract.** The top-level keys
> (`version`, `ops`, `blueprint_params`, `emission`), each op-entry's keys (`op`, `ui_label`,
> `config`), and the param-ref token `{"param":"<name>"}` are pinned here as THE `schema_behavior`
> shape that V153 writes and the engine reads — not a proposal. `config` values are **literals**
> (string/number/bool/array/object — used as-is) or the exact **param-ref** object
> `{"param":"<name>"}` (no other config-value form is legal). This replaces the throwaway
> `{effect_type,conflict_policy}` content `[read]` V74:93-97; it does NOT add a column.
> (The 39 per-Blueprint op-list JSONs that V153 seeds are mechanical drafting deferred to #2 §D /
> G-D2; the SHAPE they are drafted into is fixed here.)

### A.2 Worked example — BronzeToSilverCleaning (the anchor's silver op)

Applying the locked Cleaning decomposition `transform-values(trim/fill-nulls) + rename-columns +
change-types + drop-columns + filter-rows + deduplicate`, every param optional with a do-nothing
default (unconfigured = passthrough). The drop-row behavior is carried by the **`drop_null_columns`**
param → the **`filter-rows`** op (drop rows where the named column(s) are null, via `drop_when_null`
in the op-list below), NOT a `null_handling` enum value. This decomposed key set **REPLACES** the old
single `null_handling` enum `[read]` V7:115 (options `drop`/`fill_default`/`flag` — seeded but unused);
V153 deliberately supersedes it. `[report]` SPEC-builder-compiler.md:57-58,170-174 + OP-VOCAB doc:47:

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

> **RESOLVED (G-A2 — 2026-06-15):** the Cleaning param keys introduced here (`trim_columns`,
> `fill_null_map`, `rename_map`, `type_coercions`, `drop_columns`, `drop_null_columns`, `dedup_key`)
> are **pinned** as the contract for V153's Cleaning param surface (each optional, do-nothing
> default = passthrough). Mechanical fill of the already-locked decomposition — not new design.

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
> (back-compat default — pre-V153 rows have no tier). **RESOLVED (G-A3 — 2026-06-15): absent-tier
> defaults to `user`** (pinned; not a loud-fail — pre-V153 params are genuine Customer choices).

> WHEN `tier=="derived"` THE SYSTEM SHALL resolve the param's value from `derivedFrom` at
> build/package time AND render it **read-only and always visible** in the config panel (never
> hidden). `[report]` ADR 0023 §Consequences + SPEC-builder-compiler.md:55.

> WHEN a `derived` param cannot resolve (e.g. domain has no calendar) THE SYSTEM SHALL loud-fail,
> except where a platform default is provably harmless (concurrency policy, evidence prefixes)
> in which case it applies that default. `[report]` SPEC-builder-compiler.md:60-62 + ADR 0011.

> Nuance — package-stamped `derived` params (e.g. calendar bundle hash) SHALL show their source
> at design time and their final value at packaging. `[report]` SPEC-builder-compiler.md:55.
> (The packaging step is a build-time mechanism specified in #2; this spec defines only the
> design-time presentation contract.)

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
`[report]` SPEC-builder-compiler.md:51,69,161-163. (The UI surface itself is specified in #3
`docs/ui/SPEC-ui-composition.md`; this spec defines the derivation it renders.)

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

> **NOTE — declaration vs handler:** this section fixes the **declaration shape** that op-lists
> carry. The **per-op emission handlers** that turn this declaration into code (per engine,
> Mode-aware) are owned by **#2 (`SPEC-codegen-compiler.md` §C)**. A change to one side is a
> cross-spec consistency check (`SPEC-INDEX.md` §Cross-reference).

---

## §B — SCHEMA ENGINE (the 32 ops' schema-effect rules)

**Authority (B1, locked):** the **design-time** schema is the column authority — Schema
Propagation deterministically computes the full schema (every column, every op) live as the
Customer designs; re-propagates on every edit; surfaces downstream conflicts. Build-time codegen
is **subordinate** (it produces exactly these columns, never decides them). Runtime validates the
produced table matches the contract → loud-fail. **No repair loop** (no LLM in codegen).
`[report]` SPEC-builder-compiler.md:79-88 + ADR 0011 §Decision.
> **RESOLVED (C-3/G-14 — 2026-06-15):** ADR 0011 §Decision item 2's "bounded repair regeneration"
> wording is now marked **SUPERSEDED by ADR 0013** in the ADR itself (no LLM in codegen ⇒ no body
> to repair ⇒ no repair loop; a mismatch fails loudly with no repair step). This "no repair loop"
> rule is therefore consistent with the ADRs; the residual conflicting text has been annotated in
> `docs/adr/0011-*.md` §Decision. (The codegen-side ownership of the deleted repair loop is in #2.)

This REPLACES the hardcoded switch `SchemaPropagationService.deriveBaseOutputSchema:814-854`
`[read]` (the `switch (key)` over blueprint key) — the engine instead walks `schema_behavior.ops`
in order and applies each op's rule below to the input column set(s).

### B.0 The column model (B5 — nested types)

**RESOLVED (G-4 — locked 2026-06-15): this recursive encoding IS the contract.** A column /
field descriptor is `{name, type, nullable, ...tags}`. The `type` value is encoded recursively
as one of three forms, distinguished by which key is present (no separate `kind` discriminator
is needed — the presence of `fields` ⇒ struct, `element` ⇒ list, neither ⇒ simple):

- **simple** — `type` is a string ∈ `{string, integer, long, double, decimal, boolean, date, timestamp}`;
  no `fields`/`element`. e.g. `{"name":"loan_id","type":"string","nullable":false}`.
- **struct** — `type:"struct"` **plus** `fields:[<field-descriptor>, ...]` (recursive named
  sub-fields; each sub-field is itself a `{name,type,...}` descriptor).
  e.g. `{"name":"borrower","type":"struct","fields":[{"name":"fico","type":"integer"}, ...]}`.
- **list** — `type:"list"` **plus** `element:<type-encoding>` (the element is itself one of these
  three forms — simple string, or a nested `{type:"struct",fields:[...]}` / `{type:"list",element:...}`).
  e.g. `{"name":"prior_addresses","type":"list","element":"string"}` or
  `{"name":"payments","type":"list","element":{"type":"struct","fields":[...]}}`.

> **Encoding rule:** `fields` is present **iff** `type=="struct"`; `element` is present **iff**
> `type=="list"`; a simple type carries neither. This is the single recursive shape used by
> `flatten-json` (struct → flat sub-fields), `build-struct` (flat → struct), and schema discovery.

> WHEN an op needs a nested type THE SYSTEM SHALL source it from schema discovery (sampling),
> never from an LLM. `[report]` SPEC-builder-compiler.md:110-112 (B5) + ADR 0011.

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
25. **add-audit-columns** — OUT = IN + the **PULSE audit column set of exactly 8 columns**:
    `_pulse_ingested_at`(ts), `_pulse_processing_ts`(ts), `_pulse_pipeline`(string),
    `_pulse_task`(string), `_pulse_run_id`(string), `_pulse_source_uri`(string),
    `_pulse_business_date`(date), **`_pulse_dag_id`(string)**.
    **RESOLVED (C-1 — locked 2026-06-15):** the set is **8 columns**, so the anchor bronze =
    78 source + 8 audit = **86**. The phantom `created_as_timestamp` is **DROPPED** (it equalled
    `_pulse_ingested_at`; a true source-creation time lives in source data, not audit) and the
    NEW **`_pulse_dag_id`** (live Airflow `{{ dag.dag_id }}`) is added. Three of the 8 are **live
    Airflow templates**, not baked literals: `_pulse_task` = `{{ task.task_id }}` (CHANGED from the
    baked task slug), `_pulse_run_id` = `{{ run_id }}`, `_pulse_dag_id` = `{{ dag.dag_id }}`.
    The `IngestionAuditColumns` source-of-truth change (NAMES 7 → 8, `_pulse_task` made live, new
    `_pulse_dag_id`, `emitPyspark` updated) is specified on the **emission** side in #2 §C; this
    rule is the **schema-effect** side. `[read]` today's source IngestionAuditColumns.java:36-46
    (NAMES = 7, pre-change) + asColumnDescriptors:59-71.
26. **write-sink** — OUT = IN (writes rows to a destination; schema unchanged). `[report]` OP-VOCAB doc:63.

**History/power-user**
27. **sql-model** — OUT = the SQL's output columns, derived by **Calcite-validating the
    user-authored dbt SQL against its input schema** OR a **declared** output schema. dbt-SQL
    emission. `[report]` ADR 0013 §3 + OP-VOCAB doc:36,39 + SPEC-builder-compiler.md:23.
    **RESOLVED (G-7 — locked 2026-06-15): the schema-deriving Calcite validator is a named build
    PREREQUISITE `CALCITE-PHASE-2` (the Expression Builder "Phase-2" gap), owned by #2 §C.4. A
    parse-only Calcite Babel validator DOES exist today (`ExpressionValidationService.java:99-113`,
    Phase-2 named at `:36-42`); `CALCITE-PHASE-2` EXTENDS it to schema-deriving — that schema-deriving
    branch does not exist yet.** Until `CALCITE-PHASE-2` is built, `sql-model` resolves its output
    schema **only** via the **declare-schema path** (the Customer/DE supplies a declared output
    schema); the Calcite-validate-against-input branch is unavailable. The op is still in the
    32-op vocabulary; only its Calcite resolution branch is gated on the prerequisite.

**Control ops (portless — no schema effect)**
28. **sense** — DAG-only; no columns. `[report]` OP-VOCAB doc:38,65.
29. **schedule-and-triggers** — DAG-only; no columns. `[report]` OP-VOCAB doc:38,65.
30. **rollback** — DAG-only; no columns. `[report]` OP-VOCAB doc:38,65 + ADR 0020.
31. **advance-time** — DAG-only; no columns. `[report]` OP-VOCAB doc:38,65 + ADR 0023.
32. **invoke-remote** — DAG-only; no columns. `[report]` OP-VOCAB doc:38,65 + ADR 0021.

> **Universal addenda (KEPT):** after the op-list's rules run, the engine applies
> `derived_columns` and `dropped_columns` addenda (available on every TRANSFORM/MODELING
> Blueprint). `[read]` SchemaPropagationService.java:802-807, 963-1009. **RESOLVED (G-B1 —
> 2026-06-15): the addenda are KEPT as-is** (back-compat escape hatch; they run AFTER the op-list).
> A Blueprint needing these as first-class ordered steps uses `add-column`/`drop-columns` ops
> instead. Removing the addenda is a future cleanup, out of scope here.

> **Vocabulary note — `pivot`/`unpivot`:** OP-VOCAB doc:70 lists `pivot`/`unpivot` as reshape
> verbs alongside the unused `union-all`/`distinct-union`/`sort`/`sample-limit`, but they are NOT
> among the 32 closed ops and have no schema-effect rule here. **RESOLVED (GAP5 — 2026-06-15):
> `pivot`/`unpivot` are deliberately OUT of the 32-op closed vocabulary** (not an omission). Unlike
> the kept-but-unused four, they were not elevated into the 32 and have no schema rule; adding them
> later is an op-vocabulary change (new ADR), not a silent gap.

### B.2 Three-part enforcement (B1)
> WHEN the Customer edits a composition THE SYSTEM SHALL re-run Schema Propagation over the
> op-lists, recompute every column at every op, and surface any downstream conflict. (Design-time
> column authority.) `[report]` SPEC-builder-compiler.md:79-84.

> WHEN the Builder emits an op's code THE SYSTEM SHALL produce exactly the design-time columns and
> never decide columns itself (codegen subordinate). `[report]` SPEC-builder-compiler.md:84-86.
> (The emission side of this contract is enforced in #2 §C.)

> WHEN a generated table is produced at runtime THE SYSTEM SHALL validate its columns against the
> contract and loud-fail on mismatch; explicit DDL SHALL be hand-generated ONLY where the engine
> does not make the table (PySpark bronze / external tables). THE SYSTEM SHALL NOT run any LLM
> repair loop. `[report]` SPEC-builder-compiler.md:86-88 + ADR 0011 §3.
> (C-3/G-14 RESOLVED — ADR 0011's "bounded repair regeneration" wording is now annotated
> SUPERSEDED-by-ADR-0013 in `docs/adr/0011-*.md`; see §B intro above.)

**Conflict model (3-tier, design-time):** Schema Propagation classifies each surfaced downstream
conflict into one of three tiers — **breaking** (a referenced column is gone / wrong-typed in a way
that invalidates a downstream op), **partial** (a downstream op still resolves but loses coverage,
e.g. a renamed column it referenced by old name), **non-breaking** (additive / cosmetic). The tier
drives the impact-radius overlay the UI renders. `[report]` SPEC-INDEX.md:11 (#1's scope: "the
3-tier conflict classification (breaking/partial/non-breaking) + impact-radius + enforcement") +
SPEC-builder-compiler.md:82-84 (re-propagation + surface downstream conflicts). The overlay
RENDERING is #3's concern; the **classification** is this spec's.

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

### B.4 Op-list coverage — the op-list model as the coverage yardstick
The op-list model (OP-VOCAB doc) is the coverage yardstick for §B: all 39 surviving Blueprints
decompose cleanly into these 32 ops, NO op equals a Blueprint name, NO undeclared effect.
`[report]` OP-VOCAB doc:69. The one CDCIngestion question (CDCIngestion,
**OPEN WORKLIST GAP4**) is now **RESOLVED** below — it was a MISREAD, not an op-coverage gap.

> **GAP4 (CDCIngestion) — RESOLVED (operator-decided 2026-06-15).** CDCIngestion reads a source
> **that is ALREADY a CDC log** (the change data was captured upstream), via **JDBC OR Kafka**
> transport. Its op-list is therefore the **SAME generic Ingestion shape** —
> `read-source → add-audit-columns → write-sink(bronze)` — with **NO new op**. The change-type / op
> columns (insert / update / delete) are **SOURCE columns already present in the CDC log**; PULSE
> reads them through as-is via `read-source` (they are NOT PULSE-generated). The earlier
> decomposition note — "plain JDBC read, no change-column = intent gap" — was a **MISREAD**: a plain
> read of a CDC log IS the correct behavior, because the change columns live in the source. The only
> real refinement is that the connector MUST support **both JDBC and Kafka** transport, and that
> CDCIngestion's `read-source` **expects a CDC-log source** (documented contract, no schema-effect
> change — `read-source` still yields the source's discovered schema per §B rule 24, which already
> includes the source's change-type/op columns). No distinct op-list, no `change-data-capture` op,
> no `merge-rows`-into-bronze tail. (Emission side mirrored in #2 §C.8.)

---

## OPEN WORKLIST (gate findings — RESOLVED / flagged-residual as of 2026-06-15)

> These are the SPEC-GATE findings that belong to **#1 (schema / op engine)**. As of the
> 2026-06-15 locked-decision pass, each is turned into a **RESOLVED** contract (in-spec) or a
> **flagged residual** (`> GUESS:` / DESIGN-OPEN — for the re-gate). Findings carried by #2 are in
> that file's OPEN WORKLIST; findings that span both are noted in BOTH.

### Contradictions
- **C-3 / G-14 — RESOLVED.** ADR 0011 §Decision item 2's "bounded repair regeneration" wording is
  now annotated **SUPERSEDED-by-ADR-0013** directly in `docs/adr/0011-*.md` (no LLM in codegen ⇒ no
  body to repair ⇒ no repair loop; mismatch fails loudly with no repair step). §B intro + §B.2 here
  updated to cite the superseded annotation. (Codegen-side ownership in #2.)

### Guesses → pinned shapes (RESOLVED)
- **G-1 — RESOLVED (§A.1).** The `schema_behavior` / op-entry JSON shape is **pinned as THE
  contract** (top-level `version`/`ops`/`blueprint_params`/`emission`; op-entry `op`/`ui_label`/
  `config`; config values = literal | `{"param":"<name>"}`). Not a proposal. V153 writes this shape.
- **G-4 — RESOLVED (§B.0).** The recursive nested-type encoding is **pinned as THE shape**:
  `{name, type, nullable, ...}` where `fields` present **iff** `type=="struct"`, `element` present
  **iff** `type=="list"`, simple types carry neither. Single recursive form for flatten/build-struct/
  discovery.
- **G-A2 — RESOLVED (mechanical, §A.2).** The Cleaning op-list param keys are **pinned** as the
  ones already named in the §A.2 worked example: `trim_columns`, `fill_null_map`, `rename_map`,
  `type_coercions`, `drop_columns`, `drop_null_columns`, `dedup_key` (each optional, do-nothing
  default = passthrough). These names ARE the contract for V153's Cleaning param surface. They
  **REPLACE** today's single `null_handling` enum `[read]` V7:115 (options `drop`/`fill_default`/
  `flag`, default `flag` — seeded but unused) with the decomposed explicit keys, while retaining
  `dedup_key`/`type_coercions`. The drop-row behavior is now carried by **`drop_null_columns` → the
  `filter-rows` op** (per §A.2's op-list), NOT by any `null_handling` value (`"drop_row"` was never a
  legal `null_handling` option). V153 deliberately supersedes the old param (operator 2026-06-15:
  "it does invalidate … now we are going to start to care"). No new design — mechanical fill of the
  already-locked decomposition.
- **G-A3 — RESOLVED (mechanical, §A.3).** Absent `tier` **defaults to `user`** (back-compat: pre-V153
  rows carry no tier and are genuine Customer choices). Pinned as the contract; not a loud-fail.
- **G-B1 — RESOLVED (mechanical, §B.1).** The universal `derived_columns`/`dropped_columns` addenda
  are **KEPT as-is** for back-compat (they remain the `add-column`/`drop-columns` escape hatch
  available on every TRANSFORM/MODELING Blueprint) `[read]` SchemaPropagationService.java:802-807,
  963-1009. A Blueprint that needs these effects as first-class, ordered steps puts `add-column`/
  `drop-columns` ops in its op-list instead; the addenda run **after** the op-list. No removal —
  removing them is a future cleanup, not in scope here.

### Completeness gaps
- **GAP4 — RESOLVED (operator-decided 2026-06-15; spans #1 + #2; §B.4).** CDCIngestion reads a
  source that is **already a CDC log** (change data captured upstream) via **JDBC OR Kafka**
  transport, so its op-list is the **same generic Ingestion shape**
  `read-source → add-audit-columns → write-sink(bronze)` — **NO new op**. The insert/update/delete
  change-type columns are **SOURCE columns already in the CDC log** (read through as-is, NOT
  PULSE-generated). The earlier "plain JDBC read, no change-column = intent gap" framing was a
  **MISREAD** — a plain read of a CDC log IS correct. The only refinements: the connector MUST
  support both JDBC and Kafka, and CDCIngestion's `read-source` expects a CDC-log source. Pinned in
  §B.4; emission side in #2 §C.8.
- **GAP5 — RESOLVED (§B.1 vocabulary note).** `pivot`/`unpivot` are **deliberately OUT of the 32-op
  closed vocabulary** — they sit with the kept-but-unused reshape verbs (OP-VOCAB doc:70) but,
  unlike `union-all`/`distinct-union`/`sort`/`sample-limit`, were **not** elevated into the 32 and
  have **no** schema-effect rule. Pinned as out-of-vocabulary (not an omission). If a future
  Blueprint needs pivot/unpivot, that is an op-vocabulary change (new ADR), not a silent gap here.
