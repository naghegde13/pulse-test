# Builder architecture — grill findings (layered old→new)

> ## ⚠️ SUPERSEDED-IN-PART — read this first
> The **early sections below are pre-ADR-0013 history** (they REFINE the now-superseded ADR 0002 and treat the LLM as writing the per-step body / having "form freedom"). **Current truth:** the Builder is a **DETERMINISTIC op-composition compiler — the LLM is OUT of codegen** (ADRs 0011 schema-contract / 0012 op-vocabulary / 0013 deterministic-compiler, which SUPERSEDES ADR 0002). The LLM lives **only in Chat** (compose blueprints + author expressions/SQL/filters, all Calcite/handler-validated). For what HOLDS, read the LOCKED sections lower down: **Branch 1 → ADR 0011**, **Branch 2 → ADR 0012**, **"(A) LOCKED — deterministic op-composition compiler → ADR 0013"**, **config-externalization CLOSED**, and **DESIGN PHASE COMPLETE**. The pre-0013 "What the grill refined / LLM implementation freedom / open questions about who picks the form & form-freedom" sections are kept for history, not as current decisions.

Status: **findings from a grill-with-docs session (2026-06-13) that REFINE ADR 0002 — SUPERSEDED IN PART, see the banner above.**
Not a final decision. The operator wants the **broad Builder ("compiler") refactor**
done as its **own dedicated grill-with-docs session**, where the **orchestrator
brings strong recommendations and the operator relies on them**. The operator also
noted *"this is not the only thing in my head regarding the Builder"* — so more will
surface in that session. **This doc seeds that session.**

## What the grill refined

ADR 0002 said "deterministic skeleton + LLM body." The operator agreed structure
shouldn't be hallucinated — but sharpened **what "structure" actually is** and gave
the LLM more freedom than "fill in a SQL blank":

**Deterministic structure (PULSE computes; the LLM never decides):**
- The **contract**: input/output ports + the **inferred** column schemas in/out
  (← depends on schema inference, **S1**).
- **Paths + table format** — generated as **per-environment config** that ships in
  the deployment package, **read from config, NOT hardcoded** literals (12-factor /
  dev-int-uat-prod). PULSE generates the config; the code reads it.
- **DDL** — **derived from the inferred output schema** (not a fixed template; ←
  depends on S1).
- **Materialization** boundaries; **Mode-specific shape** (Dataproc-Serverless vs
  Apache Livy, ADR 0006); **audit/lineage columns**; upstream/downstream **wiring/refs**.

**LLM implementation freedom (constrained by the contract):**
- The **implementation form** — dbt **SQL** *or* **procedural PySpark** *or* a
  **UDF** — whatever best fits the blueprint. **Not locked to a SQL SELECT body.**
- The transformation **logic**.
- Hard constraints: read config (don't hardcode), produce **exactly the contracted
  output schema**, read from the contracted input, and emit **byte-exact
  deterministic data output** (the LLM's *code* may vary; its *data* must not).

## Implications surfaced (today)
- **S3 as built by Codex (parked on `s3-llm-builder`)** is the **SQL-form special
  case** (deterministic dbt model + LLM SELECT body). Valid for cleaning, but **not
  the general architecture** (no form-freedom; no S1 schema-contract; config-
  externalization unverified). **It does not merge as-is** — it informed the
  architecture.
- **S1 (deterministic schema inference) is foundational** — both the DDL and the
  LLM's output-schema contract depend on it — and is **not built yet**. A correct
  Builder rests on S1.
- The "smart Builder" done right is a **broad refactor**, not a one-day fan-out.

## Open questions for the dedicated session (orchestrator brings recommendations)
1. **Who picks the implementation form** — the **blueprint declares** it (it changes
   the scaffolding, the DAG task, and how we test it) or the **LLM chooses**
   per-instance (more agentic)?
2. **Build S1 before S3?**
3. How config-externalization manifests per form (dbt profiles/refs already
   externalize; procedural code needs an explicit config-read pattern).
4. *Reserved:* the rest of "what's in the operator's head re the Builder."

## Decisions refined (2026-06-14, operator + orchestrator)

- **Form = exactly two: dbt-SQL and PySpark.** A **UDF is a *technique* within either, NOT a third form** — runtimes differ (Spark Python UDF vs warehouse/engine-native UDF), which confirms it's host-dependent, not standalone. UDFs allowed where appropriate, but (i) must stay **deterministic** (ADR 0009 applies *inside* UDFs) and (ii) **prefer native functions** (Python UDFs break Spark's optimizer). **The blueprint declares its form; the LLM does NOT choose the form** — LLM freedom is in the *logic*, not the form. *(Orchestrator pushed back on "give the LLM form freedom"; operator agreed — see [[be-a-genuine-technical-check]].)*
- **The Builder grills are MERGED into ONE deep session** (S1 + smart Builder + form + examples). The contracts *between* them are the hard part and the source of PULSE's struggle — do not fragment the thing whose fragmentation is the problem. *(Orchestrator pushed back on splitting; operator agreed.)*
- **Spec-authoring = the lightweight discipline (`docs/SPEC-DISCIPLINE.md`), TRIAL next session:** ADRs-as-constitution + a one-page spec template + grill-with-docs + EARS phrasing + the **guess-detector gate** (a fresh agent lists what it'd have to guess; empty or it doesn't ship). GitHub Spec Kit optional. Judge by: *did an agent have to guess?*
- **Blueprints are METADATA, not code (operator, 2026-06-14).** A blueprint's "implementation" is its definition/params/ports/description — **converting metadata → code is the COMPILER's job.** The rationalization (ADRs 0020–0023) created **no code dependency** in the blueprints; it clarified what survives / what each does / what's deprecated. So the compiler's central contract is: **read the updated blueprint metadata and emit code matching 0020–0023** — data-aware-scheduling edges, implicit + explicit sensors from one emitter, deriving system-derived params. That metadata→code contract is a core question for THIS session. *(Reconciles the "is the model built?" flag: the model is metadata; building it in code is exactly this session's compiler work.)*
- **Concrete inputs for THIS session (2026-06-14 metadata discovery, spot-verified):** the **param-tiering metadata-model gap** (no `derived`/`readonly` flag exists to mark user-vs-derived params — ADR 0023's "inspectable, not editable" needs one — decide how the catalog expresses it); the **`codegen-examples/` corpus (~40 templates** across gx/ingestion/intermediate/marts/orchestration/sinks/snapshots/staging) — the open *"is codegen-examples any good / feed the LLM best examples?"* question now has a concrete target, and the **2 codegen-template bugs** (`bulk_backfill_date_range.py`, `stg_dedupe_merge.sql`) are codegen-quality, not isolated fixes; **calendar-at-domain-creation** as a compiler-input prerequisite (derived calendar params need the domain to have a calendar); **Chat's input contract** (what the compiler needs elicited: `target_scope`, sensing strategy, calendar/grain). Full worklist: `docs/blueprints/METADATA-SYNC-TASK.md` + PULSE-MAP backlog (K).

## Standing instruction (operator, 2026-06-13)
Broad Builder refactor = its **own grill-with-docs session**; orchestrator brings
strong recommendations; operator relies on them.

## Pre-grill research findings (2026-06-14) — FACTS TO GRILL, not decisions

> These are verified inputs that ground the (dynamic) grill — NOT a script and NOT
> locked. Decisions land only when reached with the operator, then as ADRs.

**S1 (schema inference) is largely BUILT, not greenfield — the reframe.**
- `SchemaPropagationService.deriveBaseOutputSchema` (`:810-854`) is a deterministic
  `switch(blueprintKey)`: passThrough (GenericFilter/GenericRouter/**BronzeToSilverCleaning**/
  DedupeAndMerge/IncrementalMerge/Derive), `maskSchema` (PIIMasking), `mergeJoin`
  (GenericJoin/EnrichmentJoin), `aggregateSchema` (GenericAggregate), `normalizeSchema`,
  appendColumns (SCD2/Snapshot), `ingestionSchema` (Ingestion = dataset + audit cols);
  `*Writer`/`*Publish`/`*DQ*` → passThrough; **default → LLM fallback** (`SchemaInferenceService`,
  returns EMPTY list when no API key). Runs on every composition mutation; persisted to
  `instance_port_schemas` + mirrored to `sub_pipeline_instances.output_schema` `[read]`.
- **GAPS (verified):** (a) `BronzeToSilverCleaning` is passThrough (`:816`) — ignores
  `rename_map`/`drop_columns`/`type_coercions` ⇒ WRONG schema for the anchor silver step;
  (b) `aggregateSchema` type bug (COUNT→integer not long; SUM/AVG→decimal not double, no
  int/double split); (c) `mergeJoin` drops a same-type name-collision right column instead
  of `right_`-prefixing; (d) FactBuild/WideDenormalizedMart/AggregateMaterialization/
  FeatureTablePublish still fall to LLM; (e) JsonFlatten/JsonStruct need nested-struct field
  types the `{name,type}` model can't carry.
- **The core missing piece = the S1→codegen CONTRACT.** The generator does NOT consume
  S1's column list: dbt bodies `SELECT *` or project from params; no DDL is derived from
  S1 (`materialized='table'` lets the warehouse infer physical schema at run time). Only
  consumers of `output_schema` in codegen: partitioning + bronze DDL `[read]`.

**Examples corpus (`codegen-examples/`, 38 files): good content, wrong FORM for grounding.**
- Well-documented, modern (GX 1.x, idiomatic dbt/PySpark), good coverage — BUT every file is
  a **marker-substitution template** (`__LAKE_FORMAT__`, `__COLUMNS_TO_TRIM_LIST__` + Jinja
  over injected literals — `stg_cleaning_basic.sql` `[read]`), the deterministic-emitter form
  ADR 0002 rejects, not concrete LLM-grounding examples. NOT wired into the generator.
- 3 orphans; the 2 "known bugs" are **mis-wirings** (BulkBackfill→`jdbc_snapshot_oracle`,
  DedupeAndMerge→`stg_cleaning_basic` — both grounded on semantically-wrong files;
  `bulk_backfill_date_range.py`/`stg_dedupe_merge.sql` referenced by nothing). Plus 1 real
  runtime bug (`stream_kafka.py:164` `source_path` undefined) + determinism defects
  (`fct_incremental_late_arriving.sql:98` current_timestamp in business logic; 2 ineffective
  `ORDER BY k, k` tiebreakers in `reference_data_publish`/`int_incremental_merge`).

**Other verified inputs.** Param-tiering: NO `derived`/`readonly` marker in `Blueprint`/the
catalog (`Blueprint.java` `[read]`) ⇒ ADR 0023 tiering has nowhere to live. Config-
externalization: `file_format`/`location_root` baked as LITERALS into the generated dbt
config (`CodeGenerationService:1765-1769` `[read]`); transform/dbt path still Delta-centric
(Mode-awareness only half-done — G1 did ingestion only). Metadata→code: sensor/outlet/schedule
emission EXISTS but as TWO sensor paths (explicit `switch` + implicit `resolveSourceDataset`,
`:374-411`), not ADR 0022's one emitter; still branches on to-be-deprecated
`ObjectStoreKeySensor`/`DatasetDependencySensor`.

**Open questions for the DYNAMIC grill (order = dependency, not a script):**
1. Is S1's inferred column list the **ENFORCED contract** (generated body MUST produce exactly
   it; DDL derived from it; checked in the schema/repair loop) or advisory? ← root decision.
2. If enforced: which S1 gaps to fix first (BronzeToSilverCleaning is the anchor's, so first)?
3. Examples: render to concrete + fix defects / rebuild / keep as templates (contradicts 0002)?
4. Config-externalization: per-env config read at runtime vs baked literals — and how per form?
5. Param-tiering: how does the catalog express user-vs-derived (per-param flag in paramsSchema)?
6. Metadata→code: unify the two sensor paths into one emitter; drop deprecated branches.
(Form is already settled: blueprint declares dbt-SQL vs PySpark; LLM doesn't choose form.)

**BRANCH 1 CLOSED (2026-06-14) → ADR 0011.** Resolved: schema inference = 100%
deterministic, ZERO LLM (rule / blueprint-declared behavior / discovery); S1's column
list is the ENFORCED contract (body must match → bounded repair → loud fail); validate
the produced columns EVERYWHERE; hand-generate explicit DDL only where the engine
doesn't make the table (PySpark bronze, external tables) — don't override dbt's table
creation; blueprints DECLARE schema behavior as metadata (replaces the hardcoded Java
switch; fixes "adding a blueprint is easy"). Verified gaps to fix as build work:
BronzeToSilverCleaning passThrough ignores rename/drop/type_coercions; aggregate output
types; same-type join collision; existing tests don't cover these (green ≠ correct);
`propagate_inferenceFallbackUsedWhenNoRule` must be rewritten (unknown → error, not LLM).
**Remaining open Builder branches:** examples (form/role/authoring), config-externalization,
param-tiering metadata model (user-vs-derived flag), metadata→code one-emitter (sensors/edges).

## Pre-grill research for Branches 2–5 (2026-06-15) — FACTS + OPTIONS, `[report]`, NO decisions

> Re-verify file:line before acting. Many points corroborate earlier first-hand `[read]`
> findings (dead `schema_behavior`, the `deriveBaseOutputSchema` switch, delta-keyed dbt
> path, two sensor emitters). **⚠️ BASELINE: G1 is NOT merged on the current branch** —
> the live Builder is still Mode-blind (`SparkSubmitOperator` + `.format('delta')`; ZERO
> `DataprocCreateBatchOperator` in `src/main`). "G1 made ingestion Mode-aware" is true only
> on parked `g1-builder-gcp-correct`. The Mode-awareness branches below assume G1 merges
> first (its persona-branch pattern is the template).

**Branch 2 — blueprint metadata model (declare schema behavior + param tiering).**
- Of blueprint metadata, only `params_schema`, `codegen_hints` (example_keys only),
  `emit_strategy`, `valid_layers`, `add_surface` are READ by backend code;
  `required_params_schema`/`optional_params_schema`/`ui_schema`/`schema_behavior`/
  `compute_backend`/`runtime_requirements` are **write-only/dead** (seeded, unread).
- `schema_behavior` is dead + carries an UNRELATED vocabulary (`effect_type`/`conflict_policy`)
  → ADR-0011 can claim it, but "reuse" = redefine its contents.
- The switch (`deriveBaseOutputSchema:810-854`) + addenda (`applyDerivedColumns:964`/
  `applyDroppedColumns:997`) already implement ~9 "kinds": passthrough / ingestion+audit /
  rename-drop-retype (SchemaNormalization) / aggregate / mask / join-merge / scd2-append /
  declared-columns / discover. **B2S is mis-routed to passthrough** despite declaring
  type_coercions/rename_map/drop_columns (V93:363-409) = the worked example for rename-drop-retype.
- **Schema-behavior declaration OPTIONS:** (A) fixed enumerable vocabulary of ~9 kinds
  (`{kind,config}`; current resolvers become per-kind impls selected by declared kind, not
  `switch(blueprintKey)`) — ~1:1 with existing code, closed/testable, clean fallback removal;
  cost = new kind+resolver+tests per genuinely-new shape. (B) general column-ops spec (one
  interpreter) — flexible, subsumes addenda; cost = bigger surface, some resolvers aren't pure
  column-ops (ingestion resolves dataset schema; join collision; aggregate type-inference) →
  escape hatches erode benefit; reintroduces DSL fuzziness. Hybrid: A for closed shapes, B-style
  config only for the user-declared-columns family.
- **Param-tiering OPTIONS (no `tier`/`derived` flag exists ANYWHERE — confirmed):** (i) per-param
  flag in each params_schema entry — single source, on the only-read field; touches every row +
  needs a default. (ii) ui_schema read-only hints — keeps params_schema clean but ui_schema is
  dead + tiering is a build-time contract concern not UI. (iii) separate derived-params list (can
  carry the resolution source per param) — clean separation but two lists to sync. Examples:
  AdvanceTimeDimension 2-user/18-derived; RemotePipelineInvocation selectors-user/broker-refs-derived.

**Branch 3 — config-externalization.** Structural values baked as LITERALS: dbt
`file_format`/`location_root` (`:1765-1770`, repeated router `:2968`, snapshot `:2134/:2208`);
PySpark bronze `output_path`/`.format('delta')`/`USING DELTA` (`:1136-1146`), source read
(`:1035-1042`), sinks (`:1227-1313`). An `{{ env_var() }}` pattern EXISTS but only at the
scaffold/connection layer (`RepoScaffoldService:471-498`); SQL `{{ var() }}` is used only for
business-date/run-id. **OPTIONS** — dbt: (A) dbt vars, (B) `{{ env_var() }}` (matches scaffold),
(C) PULSE-emitted per-env config read via macro, (D) per-env-stamped literal in the immutable
package. PySpark: (E) os.environ (mirrors secrets), (F) packaged config file, (G) Spark
--conf/args from the DAG. **Key open Q: "externalized" = true runtime-read (B/C/E/F) OR
per-env-stamped-literal-in-immutable-package (D)? These conflict** (D leans on the existing
Lifecycle immutable-package model but may not satisfy "read from config, not literal").
Granularity: lake-root-per-layer (macro appends identifier — already does) vs full per-table path.

**Branch 4 — Mode-awareness in the transform/dbt path.** `RuntimeAuthorityService` already maps
per-layer format per persona (GCP bronze/silver→iceberg_bq_managed, gold→bq_native; DPC→parquet,
`:134-160`) but the transform path NEVER consults it (codegen uses it only for
`resolveDefaultBackend`). dbt keys on `"delta".equalsIgnoreCase(fileFormat)` (default delta),
`fileFormat` from `compileNode.artifactHints` not persona. Custom materializations
(`pulse_delta_table`/`_incremental_merge`) are Delta/Spark-only + scaffolded; scaffold profiles.yml
is DeltaSparkSessionExtension+S3A only → **GCP silver/gold dbt does NOT exist** (no Iceberg-on-GCS
materialization, no BQ catalog). Extend = format off persona (or off `RuntimeAuthority.
materializations[layer]`, already per-layer) at every delta-keyed site + new GCP materializations/
profiles + reconcile ADR-0007 interim-vs-target. **Open Q: dbt-spark+Iceberg-catalog vs
dbt-bigquery for gold; interim only (parallel to G1) or stand up BQ catalog now?**

**Branch 5 — metadata→code sensor/edge emitter (ADRs 0021/0022).** TWO separate sensor emitters
(no shared code): explicit (`switch` imports `:378-395` + `appendExplicitSensorTask:4138-4230`) and
implicit (`resolveSourceDataset` inline `:483-565`); duplicate S3KeySensor blocks `:4162`/`:540`.
Deprecated branches present: `ObjectStoreKeySensor` (`:387,:4002,:4174-4188`),
`DatasetDependencySensor` (`:391,:4004,:4201-4214`). Data-aware CONSUMER side NOT emitted: producer
`outlet=Dataset(...)` is (`:436-439,:729-747`) but `schedule=` is only cron (`:464`), never
`schedule=[Dataset(X)]`; resolver `DatasetScheduleService.resolveEventRefs` EXISTS but is
unwired (not injected/called by codegen). **Vocabulary mismatch to confirm:** PipelineService
writes `scheduleType="event"` (`:229`) but DatasetScheduleService checks `"dataset_event"` (`:63`).
Surfacing implicit elements on the canvas = a FRONTEND/data-model task (cross-component). Prereqs
(ADR 0021): cross-pipeline "dataset X produced by pipeline A" resolution; DPC Airflow ≥2.4/2.9.

## Branch 2 — DECISIONS + primitive op vocabulary (DRAFT 2026-06-15 — finalizing → ADR at lock)

**Locked so far (operator-agreed):**
- **Self-describing ops:** a data blueprint = ONE ordered list of ops; each op declares its `schema-effect` / `row-effect` / `side-output`. We do NOT bucket ops by rows-vs-columns. **No op may equal a blueprint name** (an op==blueprint is a rename, not a decomposition — operator's key catch).
- **Intent-is-canonical:** the current pass-through / `SELECT *` / LLM-fallback / wrong-column behaviors are **bugs to fix**, not the spec (operator's standing rule — judge a blueprint on intent, not today's Builder output). The decomposition's intent op-lists are canonical.
- **Zero LLM for schema** (ADR 0011) — "falls to LLM today" = the bug being removed, not an ongoing role.
- `route-rows` may declare **N data-dependent outputs** (GenericRouter); the port/schema model supports multi-output for it.
- **DQ-observation** (Freshness/SchemaDrift/Anomaly) = `check-data` (CAN fail the job) **+** `emit-report` (append-only, configurable table name, pipeline-level) — not just a report.
- Blueprint **declares its emission** (`PySpark`/`dbt-SQL`/`dbt-snapshot`/`GX`/`DAG-only`); the LLM never chooses it. Airflow DAG is the universal substrate (one per pipeline; every blueprint contributes a task/config).

**Primitive op vocabulary (DRAFT — plain names; each op self-declares its effects):**
- **Column:** `add-column` (any Expression-Builder expression — incl. **window functions** — producing a NEW column) · `transform-values` (same expression machinery but REPLACES an existing column — operator-confirmed) · `drop-columns` · `keep-columns` · `rename-columns` · `change-types` · `mask-columns` · `flatten-json` · `build-struct`
  - **`window-function` is NOT its own op** (operator + orchestrator, 2026-06-15): it adds a column without collapsing rows, so it's `add-column` with a window expression — NOT `group-and-aggregate` (which collapses). **CLOSED `[read]`:** the Expression Builder uses Calcite's permissive **Babel parser**, parse-level only (`ExpressionValidationService:99-107`; full semantic validation is a Phase-2 gap for ALL expressions, `:39`), so `OVER(...)` parses + column-refs extract → no separate op. Impl note (not window-specific): bare exprs are rewritten to parse (`:58`), so validate the window expr in a query context at build time.
- **Combine/reshape:** `join` · `group-and-aggregate` (COLLAPSES rows) · `union-all` · `distinct-union` · `sort` · `sample/limit` (operator-confirmed + gets a corresponding atomic blueprint)
- **Row:** `filter-rows` · `deduplicate` · `route-rows`(N outputs) · `merge-rows`(upsert)
- **History (dbt-native) — SPLIT, CONFIRMED by study `[report]`:** (1) **`track-history-scd2`** — emission = dbt `{% snapshot %}` block; columns = dbt-managed `dbt_valid_from/dbt_valid_to/dbt_scd_id/dbt_updated_at` (codegen also adds custom `effective_from/effective_to/_pulse_processed_at` — redundant w/ `dbt_valid_*`, cleanup candidate). (2) **`take-periodic-snapshot`** — emission = dbt **incremental** model (NOT a snapshot block); columns = source + business-as-of partition `ds` + audit `_pulse_processing_ts/_pulse_run_id/_pulse_snapshot_model`. **BUG (intent-canonical fix):** schema rules `SchemaPropagationService:823-834` are wrong + appear TRANSPOSED (SCD2 rule says `valid_from/valid_to/is_current` — none emitted, no is_current; SnapshotModel rule says `dbt_valid_*` — the snapshot cols, on the non-snapshot blueprint). Fix both to codegen reality. **Metadata bug:** V81:425/457 tag BOTH `artifact_types=dbt_snapshot`/`dbt_layer=snapshots` — wrong for SnapshotModel (a `DBT_MODEL` in `models/marts/`); pre-V94 leftover → blueprint backlog. dbt runs as `BashOperator: dbt build` on the worker (GCP gap: needs Dataproc on Composer — flagged, not Branch-2).
- **Quality (GX):** `check-data` (raises on failure → Airflow task fails → run stops; confirmed `[report]`) · `emit-report` (intent = append-only, configurable, pipeline-level; today overwrites — to fix)
- **Movement:** `read-source` · `add-audit-columns` · `write-sink`
- **Control (portless):** `sense` · `schedule-and-triggers` · `rollback` · `advance-time` · `invoke-remote`
- Known-but-unused (add only if a blueprint needs): `pivot/unpivot`.
- **Atomic-blueprint concept (operator, 2026-06-15):** a primitive op can be exposed as a single-op blueprint. Several `Generic*` blueprints already ARE this — `GenericFilter`=`filter-rows`, `GenericAggregate`=`group-and-aggregate`, `GenericJoin`=`join`. `sample/limit` joins that family. Open: which other primitives also get atomic blueprints.

## Branch 2 — adjacent decisions (2026-06-15)
- **Quarantine (#4) — DECIDED (operator):** quarantine output = a **managed table**, auto-generated (DDL + materialization done FOR the developer — no manual path→table step, no loose DQ/GX files on GCS). Implemented by **composing atomic ops**: `filter-rows`(failing) → auto-materialize a managed quarantine table. **No new op needed** — it's a `check-data` side-output the skeleton materializes like the medallion tables. (GX doesn't natively split DataFrames; PULSE generates the filter+write — per the GX study `[report]`.)
- **GX + dbt execution on GCP — DECIDED (operator):** GX runs as a **Dataproc job** (scales; not inside the Composer worker). Same gap + fix applies to **dbt-on-Spark** (also targets Dataproc). GCP-track items (parallel to G1's Dataproc-Serverless), NOT Branch-2 blockers.
- **UI SCOPE — DECIDED (operator, 2026-06-15):** UI is a **committed, separate lane** (not an afterthought). Three parts: a **dedicated UI grill** against the locked backend model (the contract the UI renders → lock first, design UI against a stable target); a **running UI-obligations list** (below, maintained as backend decisions land); and a background **UI-capability audit** (have / missing / needs-change per op + model) to run **once the Builder contracts in this grill are stable** (not now). PULSE "working" = backend + frontend both. Tracked as its own lane.
- **UI obligations (running — feeds the UI grill):** surface implicit sensors + data-aware-scheduling edges on the canvas (ADR 0022); param-tiering "inspectable not editable" → read-only render of system-derived params (ADR 0023); `route-rows` N-output visualization; `transform-values` vs `add-column` affordance (replace vs new); dataset GUI shows sensing next to file-naming; Chat elicits `target_scope`/sensing-strategy/calendar+grain; show each step's op-list so the user sees what a step does.

## BRANCH 2 — LOCKED (operator sign-off 2026-06-15) → ADR 0012
Op vocabulary (32 primitives) + self-describing-ops model + intent-is-canonical + emission-is-declared + atomic-blueprints — all locked. **Lock-basis:** `docs/blueprints/OP-VOCABULARY-AND-DECOMPOSITION.md` (all 39 survivors decompose cleanly, no op==blueprint). **Build worklist = its 12 fix-items.** **Atomic blueprints to ADD (so no op is unused):** `union-all`, `distinct-union`, `sample-limit`, `sort` (operator-confirmed: `sort` gets a standalone atomic blueprint too). Tooling: sub-agents can now write files directly (`worktree.bgIsolation: none` set in `.claude/settings.local.json`) — may need a session restart to take effect.
**Remaining Builder branches (next):** config-externalization · metadata→code one-emitter (sensors/edges) · param-tiering metadata flag (ADR 0023) · per-op codegen handlers.

## (A) LOCKED — Builder = deterministic op-composition compiler → ADR 0013 (SUPERSEDES ADR 0002)
Operator-confirmed 2026-06-15. **No LLM in codegen.** Each op has a deterministic handler (per emission type); blueprint code = composed handlers; byte-exact by construction. **LLM = Chat assistant only:** compose/arrange blueprints on the design surface; author expressions, filter clauses, and SQL (user writes by hand OR asks Chat to draft — all Calcite/handler-validated downstream). **codegen-examples corpus** → reference for op-handler authors, not runtime grounding. This resolves the bulk of the deferred "merged S1/smart-Builder/examples" session.
- **SQL-chaining blueprint (power-user/DE) — confirmed:** a chain of **`sql-model`** ops (raw user dbt SQL; new closed op in the vocab). Schema via Calcite-validate-against-input (or declare). **Build prereq:** Calcite validator/planner (Expression Builder Phase-2 gap) or declare path.
- **UI obligation:** a dedicated SQL-chaining panel — write the SQL chain + specify **per-element materialization** (the user must specify; dbt doesn't auto-optimize).
- **Materialization = THREE tiers (refines ADR 0003):** ephemeral (CTE) / **temp** (perf, internal, NOT a contract) / **real** (contract, forced at medallion/DQ/engine-crossing). "Real forced at boundaries" = tier 3; temp = optimization. Power-user picks ephemeral-vs-temp per chain element; real stays forced.

## config-externalization — CLOSED (2026-06-15)
Settled by `CONTEXT.md`'s Packager model + operator correction: the bundle is **env-agnostic carrying ALL env config slices**; the Deployer ships the **whole** bundle; the **runtime selects the active env's slice via a configured env var**. So generated code **reads the env-var-selected config slice** (paths/format/project/region) — it does NOT bake literals, and the Deployer does NOT pick a slice. Current literal-baking (dbt config block `:1765-1770`; PySpark paths `:1136-1146`) is a **fix-item**. Per-form mechanism (build detail): dbt target/profile + `{{ var() }}`/`{{ env_var() }}`; PySpark reads the env-var-selected config. Not a new ADR — it honors the (now-corrected) Packager model in `CONTEXT.md`. **Package-completeness preflight (operator, 2026-06-15):** the package must carry config slices for ALL target envs; a deployment **preflight check** verifies every required config value exists for every higher env (PULSE knows the required keys from the `storage_backend`/topology rows) and **FAILS early** if a dev supplied dev but not UAT — so an incomplete package never reaches a higher-env deploy. Slots into the existing `DeploymentPreflightService`.

## GRILL CONVERGENCE (2026-06-15)
BIG architectural decisions are LOCKED: ADR 0011 (schema-contract), 0012 (op-vocabulary), 0013 (deterministic-compiler — supersedes 0002), 0003-refinement (3 materialization tiers), config-externalization (above). **Remaining design items are SMALL — mostly fix-items honoring existing ADRs:**
- **metadata→code emitter** (ADRs 0021/0022): one shared sensor emitter + emit data-aware-scheduling edges + drop deprecated sensor branches + surface implicit elements on the canvas (UI). One genuine open PREREQ (ADR 0021 flagged it): cross-pipeline "dataset X is produced by pipeline A" resolution. **RESOLVED (operator 2026-06-15):** auto-derive **within one Airflow** (same Mode/install — GCP-only or DPC-only datasets): consumer references the dataset, PULSE finds the producer via a `dataset→producer` index; **0 or >1 producers → preflight error**. **Cross-Airflow (GCP↔DPC, across installs) is NOT auto-resolvable** (Airflow Datasets don't span instances) → explicit **`invoke-remote`** (RemotePipelineInvocation, ADR 0021-kept): user names the remote DAG + async (fire-and-forget) or sync (poll-to-completion), then the local portion runs. Future option: a broker federated dataset-event bridge (big lift, deferred). The current explicit-`pipelineId` path is the gap; the `event` vs `dataset_event` string mismatch is a bug to fix.
- **param-tiering flag** (ADR 0023): **LOCKED (operator 2026-06-15) — option (i)-enriched:** a per-param `tier: user|derived` flag in `params_schema` (the only param field code reads) + each *derived* param carries a `derivedFrom` (resolution source). Serves the build (packager resolves derived) AND the UI (render read-only). Rejected `ui_schema` (a build contract, not UI) + separate-list (drift). [Mechanism note still to be added to ADR 0023 in the next-session sweep.]

## DESIGN PHASE COMPLETE — but NOT yet autonomy-ready (2026-06-15)
All Builder branches locked: ADRs 0011/0012/0013 + 0003-refinement + config-externalization + metadata→emitter + param-tiering. **This is the architecture, NOT build-ready specs.** The guess-detector gate (FAIL, no hidden rot) confirmed an implementer would still GUESS: per-op handler contracts (32 ops × emission), the redefined `schema_behavior` metadata shape, `route-rows` N-output port model, full aggregate type rules, the nested-struct schema-model change (flatten-json/build-struct), Calcite-Phase-2-vs-declare for expressions/sql-model, `emit-report` row schema. → These are the **BUILD-SPEC phase (next):** author each to ZERO fuzziness via the spec-discipline (template → EARS → guess-detector empty → dispatch, ADR 0008). **The gate's Q2 guess-list IS the build-spec checklist. First spec: the metadata-driven schema/op engine (S1 done right) — everything depends on it.** Build phase is generation-heavy → recommend fast mode (operating rule).
Then: **DESIGN phase done → BUILD specs** (op engine + 32 op handlers + sql-model/Calcite-Phase-2 + the 12 fix-items + atomic blueprints), each gated by the spec-discipline + ADR 0008.

**Cryptic blueprints dissolve into primitives (proof the model works, no op==blueprint):**
- FactBuild = `join` + `keep-columns`(keys+measures)
- WideDenormalizedMart = `join`(N) + `keep-columns` (+ `group-and-aggregate` for measures)
- ReferenceDataPublish = `keep-columns` + `deduplicate` + `filter-rows` (a distinct lookup list)
- FeatureTablePublish = `keep-columns`(entity + features + as-of)
- AggregateMaterialization = `group-and-aggregate` (≡ GenericAggregate — merge candidate)

**Open before lock:** dbt-snapshot scrutiny (study running); GX/DQ runtime + quarantine-table + append-only report (study running); confirm `window-function` + `transform-values`; then the **full re-decomposition** (all 43 → these primitives, dbt-snapshot columns pinned, no op==blueprint) → review → lock → **ADR**.
