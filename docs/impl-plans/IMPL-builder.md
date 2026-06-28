# IMPL-builder — Implementation Plan: the deterministic op-composition Builder (specs #1 + #2)

> **What this is:** the BUILD PLAN that turns the two gated specs
> [`docs/build-specs/SPEC-schema-op-engine.md`](../build-specs/SPEC-schema-op-engine.md) (#1, design-time op-engine
> + the 32 ops' schema-effect rules + propagation/conflict/enforcement) and
> [`docs/build-specs/SPEC-codegen-compiler.md`](../build-specs/SPEC-codegen-compiler.md) (#2, the 32 ops'
> per-engine emission handlers, Mode-aware; V153; the byte-exact anchor oracle) into code. **This is the plan,
> NOT the code.** Both specs are GATE-CLEAN (`SPEC-INDEX.md:41`); this plan sequences their build worklists.
>
> **Vocabulary (enforced, per both specs):** Customer (never "user"), op / op-list (never "recipe"/"steps"),
> params (never "settings"), Blueprint, Designer/Builder (design-time vs build-time).
>
> **Evidence tags:** `[read]` = confirmed at the cited file:line in this repo; `[spec]` = the owning spec
> section; `[report]` = transcribed from a locked doc/ADR.
>
> **Two files this plan extends (verified, paths corrected vs the spec's older citation):**
> - **Design-time engine:** `[read]` `backend/src/main/java/com/pulse/pipeline/service/SchemaPropagationService.java`
>   (1361 lines; the spec cites it under the old `com/pulse/` root — the real package is `pipeline.service`).
> - **Build-time emitter:** `[read]` `backend/src/main/java/com/pulse/codegen/service/CodeGenerationService.java`
>   (4896 lines; spec cites under `com/pulse/` — real package is `codegen.service`).
> - Supporting source of truth: `[read]` `backend/src/main/java/com/pulse/codegen/audit/IngestionAuditColumns.java`
>   (7-column NAMES at `:44-46`); `[read]` `backend/src/main/java/com/pulse/expression/service/ExpressionValidationService.java`
>   (parse-only Calcite Babel validator; Phase-2 named at `:36-42`); `[read]`
>   `backend/src/main/java/com/pulse/blueprint/model/Blueprint.java` (`schema_behavior` field `:90-92`,
>   `params_schema` `:35-37`, `add_surface` `:115-116`).

---

## 1. SCOPE — what gets built

The deterministic op-composition Builder replaces the **hardcoded per-Blueprint switch** (design-time) and the
**per-Blueprint hand-written codegen branches** (build-time) with a **single op-engine** that reads each Blueprint's
`schema_behavior` op-list and applies/emits the 32 closed ops. Concretely, ten capability blocks:

1. **`schema_behavior` op-list reader + param-tiering** — parse the pinned JSON shape (`version`/`ops`/
   `blueprint_params`/`emission`; op-entry `op`/`ui_label`/`config`; config = literal | `{"param":"<name>"}`)
   `[spec]` #1 §A.1; resolve param-refs at design + build time `[spec]` #1 §A.1; derive the param surface as the
   union of op-referenced params + `blueprint_params` `[spec]` #1 §A.4; read `tier`/`derivedFrom` with
   absent-tier ⇒ `user` `[spec]` #1 §A.3.
2. **The 32 ops' schema-effect rules** (columns in → out) `[spec]` #1 §B.1 — replacing the `switch (key)` in
   `deriveBaseOutputSchema` `[read]` `SchemaPropagationService.java:814-854`; includes the recursive nested-type
   column model `[spec]` #1 §B.0.
3. **Propagation + conflict + enforcement** — walk `schema_behavior.ops` in order per instance during the existing
   topological pass `[read]` `:287-321`; the **3-tier conflict classification** (breaking / partial / non-breaking)
   + impact-radius `[spec]` #1 §B.2; three-part enforcement (design authority / codegen subordinate / runtime
   loud-fail, NO repair loop) `[spec]` #1 §B.2; **delete the LLM fallback** `[read]` `:843-851` `[spec]` #1 §B.3.
4. **32 op emission handlers × 5 engines** (dbt-SQL / PySpark / GX / dbt-snapshot / DAG-only), **Mode-aware**
   (GCP_PULSE vs DPC_PULSE) `[spec]` #2 §C.1, §C.2 — replacing the per-Blueprint codegen branches in
   `CodeGenerationService` (e.g. the cleaning `SELECT *` `[read]` `:2345-2348`).
5. **The DAG emitter + config-externalization** — the 5 control-ops' Airflow elements (each its own handler)
   `[spec]` #2 §C.1; data-aware edges (Airflow Datasets, `pulse://<tenant>/<domain>/<dataset>` URI) `[spec]` #2 §C.7;
   the env-var-selected `config/<env>.yaml` slice (`PULSE_ENV`) `[spec]` #2 §C.3.
6. **The V153 migration** — 39 op-lists into `schema_behavior` + `tier`/`derivedFrom` into `params_schema` + 4
   deprecations + the SnapshotModel `artifact_types` correction `[spec]` #2 §D.
7. **The `IngestionAuditColumns` 7→8 change** — add `_pulse_dag_id`, make `_pulse_task` live, drop the phantom
   `created_as_timestamp` `[spec]` #1 §B rule 25 + #2 §C.1.
8. **`sql-model` via the declare-schema path** (interim), gated on the `CALCITE-PHASE-2` prerequisite for the
   Calcite-validate-against-input branch `[spec]` #1 §B rule 27, #2 §C.4.
9. **The byte-exact anchor oracle harness** — bronze (500×86) + silver (290×78) reproducible from the op-lists,
   2–3-run byte-identical diff `[spec]` #2 §E.
10. **The 12 fix-items folded into the above** (router N-outputs, transposed SCD2/Snapshot rules, cleaning
    `SELECT *`, join collision, aggregate types, DQ overwrite→append+raise, zero-LLM modeling gap, etc.)
    `[report]` OP-VOCAB doc:73-86.

**Explicitly OUT of scope here** (owned by other specs, this plan only consumes/forward-refs them):
- The config UI / panels / conflict-overlay rendering → #3 (`SPEC-ui-composition.md`) + #4 (`SPEC-construct-library.md`).
- The 39 per-Blueprint op-list JSON *content* + tiering decisions → #5 (`SPEC-blueprint-catalog.md`, GATE-CLEAN,
  41 Blueprints) is the authoritative source the V153 author transcribes; this plan builds the *migration mechanism*.
- The schema-deriving Calcite validator itself → #6 (`SPEC-calcite-sql-model.md`, the `CALCITE-PHASE-2` prerequisite).

---

## 2. PREREQUISITES — resolve before / alongside the build

These are the specs' acknowledged open-worklist items and the cross-spec dependencies. **Count: 8.**

| # | Prerequisite | Owner / source | Blocking for | Status |
|---|---|---|---|---|
| P1 | **`CALCITE-PHASE-2`** — schema-deriving extension of the parse-only validator (`SqlValidator` / `Frameworks.getPlanner` + `RelDataType` derivation) `[read]` `ExpressionValidationService.java:36-42,99-113` | #6 spec | the `sql-model` Calcite-validate-against-input branch ONLY (#1 §B rule 27, #2 §C.4). **NOT blocking** the rest — `sql-model` uses the declare-schema path until P1 ships | NOT in repo `[spec]` #2 §C.4 (G-7) |
| P2 | **DPC / Livy build** — Apache Livy submit + Hive+Parquet write + plain-Airflow orchestration | #2 §C.2 (G-13) | the **DPC_PULSE** half of every Mode-aware handler. Only the GCP/Dataproc path exists today (`SparkSubmitOperator` only `[read]` `CodeGenerationService.java:585`) | NOT in `src/main` `[spec]` #2 §C.2 (G-13-flagged) |
| P3 | **ADR-0024 forward-updates to #2** — when CALCITE-PHASE-2 ships: (a) **flip the `sql-model` rule** to Calcite-primary (declare-schema becomes the fallback, not the only path), (b) **add `SqlModel` + `SourceSQL` Blueprints to V153** `[spec]` `SPEC-INDEX.md:42-43`, #6 spec `SPEC-calcite-sql-model.md:23-24` | #2 / #6 | the SQL-authoring Blueprints in V153 + the rule-flip | tracked forward-update; deferred until P1 |
| P4 | **#5 Blueprint catalog (V153 content)** — the 39 op-list JSONs + tiered params (GATE-CLEAN, 41 Blueprints inc. SqlModel/SourceSQL) `[spec]` `SPEC-INDEX.md:21-22,48-49` | #5 spec | V153 authoring (Phase 6). The decomposition is locked (OP-VOCAB doc); the SHAPE is pinned (#1 §A.1); this is mechanical transcription `[spec]` #2 §D (G-D2) | authored + GATE-CLEAN |
| P5 | **#6 Calcite spec (SqlModel/SourceSQL/mnemonics)** `[spec]` `SPEC-INDEX.md:23-24,50-52` | #6 spec | P1 + P3 | GATE-CLEAN; 4 open items (function list, op-JSON reconcile, spellings, slug rule) |
| P6 | **ADR-0007 "Considered" text correction** (Delta is the format for *neither* Mode; DPC = Hive+Parquet) `[spec]` #2 §C.2 (C-2) | ADR | the Mode-aware format choice in handlers (kills the hardcoded `USING DELTA`) | spec says corrected in `docs/adr/0007-*.md` — **verify the ADR edit landed** before Phase 3 |
| P7 | **ADR-0011 "bounded repair regeneration" annotation** (SUPERSEDED-by-ADR-0013; no repair loop) `[spec]` #1 §B intro (C-3/G-14) | ADR | the "no repair loop" enforcement contract | spec says annotated in `docs/adr/0011-*.md` — **verify the annotation landed** |
| P8 | **Cross-spec consistency check** (#1 ↔ #2 ↔ #3/#5 agree on the op-list model, schema output, conflict model) `[spec]` `SPEC-INDEX.md:26-29` | all specs | nothing technically, but a drift here = boundary slop; run before Phase 1 lands | required check |

> **Dependency notes the plan honors:** the V153 op-list content depends on **#5** (P4); the `sql-model` Calcite
> branch + the SQL-authoring Blueprints depend on **#6 / CALCITE-PHASE-2** (P1, P3, P5). The DPC half of all
> handlers depends on **P2**. Neither P1 nor P2 blocks the *anchor* (the anchor is GCP-mode, dbt/PySpark, no
> `sql-model`), so the anchor oracle (Phase 7) is reachable without them.

---

## 3. BUILD PHASES (ordered, dependency-first)

> **Ordering principle:** the op-engine is foundational (everything reads the op-list); the per-op schema rules
> come before the per-op emission handlers (codegen is *subordinate* to the design-time columns `[spec]` #1 §B.2);
> the audit-column change is a shared dependency of both the bronze schema rule and the bronze emit; the DAG
> emitter + config-externalization sit on top of the data-op handlers; V153 + the oracle close the loop.
>
> **Producer-before-consumer constraint (binds Risk #1 into the phase dependencies, not just the prose).**
> Phase 1 *deletes the LLM fallback* and *makes the bronze audit rule depend on the 8th audit column*. Two
> producers MUST therefore land **before or in lockstep with** Phase 1, or Phase 1 mass-loud-fails / under-counts:
> - **Phase 2 (the 8th audit column) lands BEFORE Phase 1's bronze audit rule** — the `read-source`/
>   `add-audit-columns` rule consumes the 8-column `IngestionAuditColumns`, so Phase 2 is renumbered ahead of
>   the bronze rule's "done" claim. (Phase 2 is tiny + serial; running it first costs nothing.)
> - **Phase 6 (V153 op-lists) lands in LOCKSTEP with Phase 1's fallback deletion** for the in-use Blueprints —
>   the chosen Risk-#1 mitigation. Concretely, the cutover obeys ONE of: **(a)** land V153 + the switch-removal
>   in the same change for the in-use catalog, OR **(b)** keep the old `switch` as a **flagged transitional
>   shim** until V153 covers the catalog, then flip the flag. This is a **phase dependency** (Phase 1's
>   fallback-deletion milestone is gated on the V153-lockstep or the shim flag), not merely a RISKS note.
>
> Consequence for the "done" bars: **Phase 1's "done" proves the per-op rule UNITS** (each `SchemaOp.apply`
> against synthetic / inline op-lists), **NOT** the anchor's end-to-end silver column count — that
> whole-pipeline propagation claim is a **post-V153 gate** (it needs the real op-lists from Phase 6 + the
> 8-column Phase 2), recorded under Phase 6/§MILESTONES.

### Phase 0 — Foundations: the op-list reader + the column model (FOUNDATIONAL, serial)

**Goal:** a typed, tested reader of the `schema_behavior` op-list and the recursive column model, with NO behavior
change yet (the old switch still runs). Everything downstream depends on this.

- **CREATE** `backend/src/main/java/com/pulse/pipeline/opengine/OpList.java` (+ `OpEntry`, `EmissionDecl` records) —
  parse the pinned shape `[spec]` #1 §A.1; reject `op` outside the 32 vocabulary (loud-fail) `[spec]` #1 §A.1/§B.3.
- **CREATE** `.../opengine/ParamRef.java` + `ParamResolver.java` — resolve `{"param":"<name>"}` against an
  instance's `params` (the same `inst.getParams()` already read `[read]` `SchemaPropagationService.java:800`);
  loud-fail on a param-ref absent from the derived surface `[spec]` #1 §A.1, §A.4.
- **CREATE** `.../opengine/ColumnModel.java` — the recursive `{name,type,nullable,...}` encoding (`fields` iff
  struct, `element` iff list) `[spec]` #1 §B.0; supersedes today's flat `{name,type}` helper `[read]`
  `SchemaPropagationService.java:1211-1216` (`column(name,type)`) and closes fix-item #12 (JsonStruct nested types).
- **CREATE** `.../opengine/ParamSurface.java` — derive the union (op-referenced + `blueprint_params`), each name
  matched to a `params_schema` descriptor or loud-fail `[spec]` #1 §A.4; read `tier`/`derivedFrom`, absent ⇒ `user`
  `[spec]` #1 §A.3; resolve `derived` params from `derivedFrom` at build/package time (loud-fail unless a provably
  harmless platform default) `[spec]` #1 §A.3.
- **DELIVERABLE:** a `SchemaBehaviorReader` service that, given a `Blueprint`, returns a typed `OpList` +
  `ParamSurface`. **No call sites changed yet.**

### Phase 1 — The op-engine: 32 schema-effect rules + propagation/conflict/enforcement (FOUNDATIONAL, serial spine + parallelizable rule bodies)

**Goal:** Schema Propagation walks `schema_behavior.ops` instead of the `switch (key)`, applies each op's rule, and
classifies conflicts in 3 tiers. This is the design-time column authority.

> **Dependencies (producer-before-consumer, per the ordering principle):** the bronze audit rule here consumes
> **Phase 2's 8-column** `IngestionAuditColumns` (Phase 2 lands first). The **LLM-fallback deletion** (below)
> is gated on the Risk-#1 mitigation: **either** land it in lockstep with **Phase 6 (V153)** for the in-use
> Blueprints, **or** keep the old `switch` as a flagged transitional shim until V153 covers the catalog. Until
> that cutover, the deletion is staged behind the flag — it is NOT done independently of V153.

- **CREATE** `.../opengine/SchemaOpEngine.java` — `applyOpList(OpList, inputs) → outputSchema`, walking ops in order,
  applying each rule to the running column set `[spec]` #1 §B intro ("walks `schema_behavior.ops` in order").
- **CREATE** `.../opengine/ops/` — **one class per op** implementing a `SchemaOp` interface
  (`Schema apply(Schema in, Schema in2, ResolvedConfig cfg)`). **This is the Phase-1 fan-out** (32 small classes,
  each grounded in #1 §B.1's numbered rule). Reuse the existing resolvers where they already encode the rule:
  - `MaskColumnsOp` ← reuse `maskSchema`/`maskedTypeFor` `[read]` `:896-954` (rule 7, already correct).
  - `JoinOp` ← extend `mergeJoin` `[read]` `:1022-1041` — **FIX #5**: matching-type same-name keeps BOTH under
    `right_<name>` (today drops the right `:1038`) `[spec]` #1 §B.1 rule 10.
  - `GroupAndAggregateOp` ← fix `aggregateSchema` `[read]` `:1043-1072` — **FIX #6**: COUNT/COUNT_DISTINCT→`long`
    (today `integer` `:1064`), SUM int→`long`/SUM dec→`double`, AVG→`double` (today `decimal` `:1065`), MIN/MAX→source
    `[spec]` #1 §B.1 rule 11.
  - `TrackHistoryScd2Op` — **FIX #2**: emit the dbt-snapshot set `dbt_valid_from/dbt_valid_to/dbt_scd_id/dbt_updated_at`
    (today's rule is transposed `[read]` `:823-828`, emits `valid_from/valid_to/is_current`) `[spec]` #1 §B.1 rule 20.
  - `TakePeriodicSnapshotOp` — **FIX #3**: emit `ds/_pulse_processing_ts/_pulse_run_id/_pulse_snapshot_model`
    (today transposed `[read]` `:829-834`, emits `dbt_valid_*`) `[spec]` #1 §B.1 rule 21.
  - `ReadSourceOp` + `AddAuditColumnsOp` ← reuse `ingestionSchema` `[read]` `:861-883` (rule 24/25), now consuming
    the **8-column** `IngestionAuditColumns` (Phase 2 dependency).
  - `RouteRowsOp` — **FIX #1**: input schema to EACH dynamic output port (today one passThrough port `[read]`
    `:815-817`) `[spec]` #1 §B.1 rule 18.
  - Passthrough/row ops (`transform-values`, `filter-rows`, `deduplicate`, `merge-rows`, `write-sink`, `union-all`,
    `distinct-union`, `sort`, `sample-limit`, `check-data`, `emit-report`) — schema-unchanged rules `[spec]` #1 §B.1.
  - Column ops (`add-column`, `drop-columns`, `keep-columns`, `rename-columns`, `change-types`, `flatten-json`,
    `build-struct`) `[spec]` #1 §B.1 rules 1-9.
  - Control ops (`sense`, `schedule-and-triggers`, `rollback`, `advance-time`, `invoke-remote`) — portless, no schema
    effect `[spec]` #1 §B.1 rules 28-32.
- **MODIFY** `SchemaPropagationService.deriveBaseOutputSchema` `[read]` `:810-854` — replace the `switch (key)` body
  with: read `bp.getSchemaBehavior()` → `SchemaOpEngine.applyOpList(...)`. **DELETE the LLM fallback** `[read]`
  `:843-851` (the `schemaInferenceService.inferOutputSchema` call) `[spec]` #1 §B.3; **remove** the
  `schemaInferenceService` field/constructor-arg dependency `[read]` `:72,81,89` once no caller remains. This closes
  **FIX #4** (cleaning passThrough), **FIX #8** (the 5 modeling Blueprints falling to LLM `[read]` `:835`).
- **KEEP** the universal `derived_columns`/`dropped_columns` addenda `[read]` `:802-807,964-1009`
  (`applyDerivedColumns` begins at `:964`) — they run AFTER the op-list `[spec]` #1 §B.1 (G-B1, back-compat
  escape hatch).
- **CREATE** `.../opengine/ConflictClassifier.java` — classify each surfaced downstream conflict as **breaking /
  partial / non-breaking** + compute impact-radius `[spec]` #1 §B.2. Feed the existing `emitConflict` path `[read]`
  `:1294-1304` with a `tier` detail; the existing conflict types (`MISSING_COLUMN` `[read]` `:511`,
  `TYPE_MISMATCH` `[read]` `:743`) map onto the 3 tiers. The overlay *rendering* is #3's; the classification is
  this phase's.
- **MODIFY** unknown-op / no-op-list / no-rule → loud-fail (blocking conflict), never silent passthrough, never LLM
  `[spec]` #1 §B.3, replacing the `default →` LLM branch.

### Phase 2 — The shared audit-column change (`IngestionAuditColumns` 7→8) (small, serial; lands BEFORE Phase 1's bronze audit rule)

> **Ordering note:** numbered 2 but it **precedes** Phase 1's bronze `add-audit-columns` rule as a producer —
> that rule (and any "bronze = 86" claim) consumes the 8-column set. It is tiny + serial, so run it first; the
> Phase-1 rule bodies that touch audit columns build against the already-8-column `IngestionAuditColumns`.

- **MODIFY** `IngestionAuditColumns.java` `[read]` `:36-46,87-138` — **ADD** `DAG_ID = "_pulse_dag_id"` (string) to
  `NAMES` (now 8) + `DEFS`; **make `_pulse_task` live** `{{ task.task_id }}` via `PULSE_TASK_ID` env-var fallback
  (today baked `taskSlug` `[read]` `:107`); keep `_pulse_run_id` live `[read]` `:108-110`; emit `_pulse_dag_id` live
  `{{ dag.dag_id }}` via `PULSE_DAG_ID` (mirror the run_id pattern); **drop** the phantom `created_as_timestamp`
  (never in NAMES; lives only in the anchor key) `[spec]` #2 §C.1 (C-1). `asColumnDescriptors` `[read]` `:59-71`
  auto-picks the 8th from `DEFS` — design-time + runtime stay aligned. Net bronze = 78 + 8 = **86**.

### Phase 3 — Per-op emission handlers × 5 engines, Mode-aware (PARALLELIZABLE — the big fan-out)

**Goal:** each op has a tested codegen handler per engine; a Blueprint's code = the deterministic composition of its
op-list handlers (no LLM) `[spec]` #2 §C. **Depends on Phase 0 (reader) + Phase 1 (each op's authoritative output
schema).**

- **CREATE** `.../codegen/opengine/EmissionEngine.java` (interface) + 5 implementations:
  `DbtSqlEmitter`, `PySparkEmitter`, `GxEmitter`, `DbtSnapshotEmitter`, `DagOnlyEmitter` `[spec]` #2 §C.1.
- **CREATE** `.../codegen/opengine/handlers/` — **one handler per (op × applicable engine)** per the §C.1 table
  `[spec]` #2 §C.1. **This is the Phase-3 fan-out**; each handler is grounded in the §C.1 row + an existing codegen
  branch to lift/fix:
  - dbt-SQL column ops (`add-column`/`transform-values`/`drop`/`keep`/`rename`/`change-types`/`mask`/`flatten`/
    `build-struct`/`filter-rows`/`deduplicate`/`group-and-aggregate`/`join`) — replace the cleaning `SELECT *`
    `[read]` `CodeGenerationService.java:2345-2348` (FIX #4) + the per-Blueprint SQL bodies (join `:2254`, agg
    `:2330`, pii `:2351`) with op-driven SELECT-fragment composition; fuse consecutive same-engine/same-layer ops
    into `ephemeral` CTEs, materialize tier-3 only at medallion/DQ/engine-crossing boundaries `[spec]` #2 §C intro.
  - **PySpark** (`read-source`/`add-audit-columns`/`write-sink`) — `add-audit-columns` ← `IngestionAuditColumns.emitPyspark`
    `[read]` `:87-138` (now 8-col, Phase 2).
  - **GX** (`check-data`/`emit-report`) — **FIX #7**: `emit-report` default `append` (today `overwrite` `[read]`
    `CodeGenerationService.java:4281,4284`); `check-data` with `on_failure=block` raises and fails the Airflow task;
    quarantine → managed `filter-rows`-derived side-table `[spec]` #2 §C.5.
  - **dbt-snapshot** (`track-history-scd2`) — `{% snapshot %}` ← `generateSnapshotSql` `[read]` `:2104-2167`;
    **FIX #10**: drop the redundant custom `effective_from/to` cols `[read]` `:2162-2164` `[spec]` #2 §C.1.
  - **dbt-incremental** (`take-periodic-snapshot`) ← `generateSnapshotModelSql` `[read]` `:2170-2239` (the `ds` +
    `_pulse_*` SELECT `:2230-2236` is already the FIX #3 column set — keep it).
  - **`sql-model`** — emit user dbt SQL; output schema from the **declare-schema path** (interim) `[spec]` #2 §C.4
    (P1-gated for the Calcite branch).
  - **`sample-limit` / `union-all` / `distinct-union` / `sort`** — GAP2 handlers (`sample-limit` MUST work — own
    atomic Blueprint) `[spec]` #2 §C.1 (GAP2).
- **CREATE** `.../codegen/opengine/ModeResolver.java` — resolve the active Mode via `RuntimeAuthorityService`
  (`getActivePersona()` `[read]` `RuntimeAuthorityService.java:47-49`); each handler branches GCP_PULSE vs DPC_PULSE
  per the §C.2 Mode×engine table `[spec]` #2 §C.2. **GCP half is the only built path today** (kills the hardcoded
  `USING DELTA` + the `SparkSubmitOperator` `[read]` `CodeGenerationService.java:585`, replacing it with
  `DataprocCreateBatchOperator`). **The DPC half is P2-gated** (Livy + Hive+Parquet) — stub + flag, do not block.
- **CDCIngestion** — NO new handler; the generic `read-source → add-audit-columns → write-sink(bronze)` shape, with
  the connector supporting **both JDBC and Kafka** transport `[spec]` #2 §C.8 (GAP4).

### Phase 4 — The DAG emitter + config-externalization (serial, on top of Phase 3)

- **CREATE/MODIFY** the DAG assembly in `CodeGenerationService` (the operator-emit region around `[read]`
  `:580-600`) — one DAG per pipeline; data-Blueprint → a task, control-Blueprint → its sensor/trigger/schedule
  `[spec]` #1 §A.5, #2 §C.1. **Each of the 5 control ops gets its own DAG handler** (not one lumped row):
  `sense` → Airflow sensor; `schedule-and-triggers` → schedule / `TriggerDagRunOperator`; `rollback` → failure
  callback / rollback task (ADR 0020); `advance-time` → time-advance task (ADR 0023); `invoke-remote` →
  `RemotePipelineInvocation` (ADR 0021) `[spec]` #2 §C.1 (control-ops-split).
- **MODIFY** to emit **data-aware edges**: wire the existing-but-unwired `DatasetScheduleService` `[read]`
  `backend/src/main/java/com/pulse/pipeline/service/DatasetScheduleService.java`; producer `outlets=[Dataset(uri)]`,
  consumer `schedule=[Dataset(uri)]`, URI = `pulse://<tenant>/<domain>/<dataset>` `[spec]` #2 §C.7 (G-C4); cross-Airflow
  ⇒ `invoke-remote`. Fix the `event`/`dataset_event` mismatch `[spec]` #2 §C.7 (verify before building).
- **CREATE** the config-externalization mechanism — generated code reads per-env values from `config/<env>.yaml`
  selected by `PULSE_ENV ∈ {dev|integration|uat|prod}`; NO baked connection strings/paths/project IDs `[spec]` #2 §C.3
  (G-C2). Extends the existing env-var reads (`PULSE_RUN_ID`/`PULSE_BUSINESS_DATE`/`PULSE_SOURCE_URI`) `[read]`
  `IngestionAuditColumns.java:108-121`. **FIX #11**: remove the 2 orphaned codegen-example files (V94 leftovers).

### Phase 5 — `sql-model` declare-schema path + CALCITE-PHASE-2 seam (serial; P1-gated branch stubbed)

- **MODIFY** `ExpressionValidationService` `[read]` `:99-113` (or a new `SqlModelSchemaService`) — wire the
  **declare-schema** resolution for `sql-model` now; leave a typed seam for the `CALCITE-PHASE-2` schema-deriving
  branch (`SqlValidator`/`Frameworks.getPlanner` + `RelDataType`) `[spec]` #1 §B rule 27, #2 §C.4 (G-7). **Do not
  build CALCITE-PHASE-2 here** (P1, #6's deliverable); when it ships, apply the **ADR-0024 forward-update** (flip to
  Calcite-primary + add SqlModel/SourceSQL to V153) `[spec]` `SPEC-INDEX.md:42-43` (P3).

### Phase 6 — V153 consumption + lockstep (V153 is owned by IMPL-catalog-seed; this plan does NOT author it)

- **V153 is authored by `IMPL-catalog-seed.md` — the SINGLE owner.** That plan CREATEs
  `backend/src/main/resources/db/migration/V153__builder_op_lists_and_param_tiering.sql` `[spec]` #2 §D and writes ALL
  of its content: the **39 surviving Blueprints'** `schema_behavior` op-lists (replacing the throwaway
  `{effect_type,conflict_policy}` `[read]` `V74:93-97`) + `tier`/`derivedFrom`; the **2 SQL-blueprint INSERTs**
  (SqlModel/SourceSQL); the **4 deprecations** (`ObjectStoreKeySensor`→`FileArrivalSensor`,
  `DatasetDependencySensor`→`ScheduleAndTriggers`, `BackfillAndReplay`→`BulkBackfill`, `CostMonitoringHook`→`NULL`);
  and the **SnapshotModel `artifact_types`→`incremental`** correction (today mis-tagged `["dbt_snapshot"]` `[read]`
  `V81:426`; SCD2Dimension's `["dbt_snapshot"]` `[read]` `V81:425` stays). **This plan does NOT author V153** — a second
  author would collide (head is **V151** `[read]`; no V152 on this branch).
- **This plan CONSUMES V153:** the op-engine (Phase 1) walks the `schema_behavior` op-lists V153 writes; emission (Phase 3)
  reads them. The op-list CONTENT for the 39 is #5's catalog (P4); shape = #1 §A.1; the SqlModel/SourceSQL op-list content
  is supplied by `IMPL-calcite-sql.md` and seeded by catalog-seed into the same V153 when CALCITE-PHASE-2 ships (P3).
- **Cross-plan lockstep (the producer-before-consumer constraint, §ordering):** Phase 1's LLM-fallback deletion is gated
  on catalog-seed's V153 landing (or the flagged transitional shim), so no in-use Blueprint falls to a deleted fallback
  before its op-list exists. This is a hard cross-plan dependency on `IMPL-catalog-seed.md`.
- **NOTE** the cross-cutting **seed-data regenerate** (existing seeded pipelines are stale under the new model) is a
  separate deliverable `[spec]` `SPEC-INDEX.md:31-38` validated against #3's UI — out of this plan's core scope but
  flagged as a downstream consequence of V153.

### Phase 7 — The byte-exact anchor oracle harness (serial; the closing gate)

- **CREATE/EXTEND** `backend/src/test/java/com/pulse/e2e/runtime/CanonicalLoanMasterAirflowRuntimeIT.java` `[read]`
  (`TENANT_ID = "tenant-home-lending"` `:58`) — the deterministic oracle per ADR 0009 `[spec]` #2 §E.
  - **Bronze:** generate the File Ingestion PySpark + DAG from the composition, run it, assert bronze EXISTS with
    **500 rows × 86 columns** (78 source matching `data/loan_master.csv` by row-count + schema + content hash;
    8 audit columns normalized out of the byte-diff) `[spec]` #2 §E. Oracle facts `[read]`
    `backend/src/test/resources/e2e/oracle/loan_master/data-oracle.json:9-10` (500×78).
  - **Silver:** the Cleaning ops (all passthrough unconfigured) + `filter-rows(loan_status="Current")` reduce
    500 → **290 rows × 78 business columns** (+ 8 carried audit) `[spec]` #2 §E (G-11/G-12). Oracle `[read]`
    `backend/src/test/resources/e2e/scenarios/loan-master-scenario-families.json` (`row_count:290`, `column_count:78`).
  - **Determinism:** generate the Builder's code **2–3 times**, run each, assert every output byte-identical to each
    other AND to the reference (deterministic ORDER BY tiebreakers for any dedup/ranking); any divergence = FAIL
    `[spec]` #2 §E. Audit columns (live timestamps/run/dag ids) excluded from the diff.

---

## 4. TESTS

| Test | What it asserts | Harness | Phase |
|---|---|---|---|
| **Per-op schema-rule unit tests** (32) | Each `SchemaOp.apply` produces the §B.1 columns-in→out (esp. the fix-items: join `right_` collision, aggregate `long`/`double`, SCD2 `dbt_valid_*`, snapshot `ds`+`_pulse_*`, router N-ports, audit 8-col) `[spec]` #1 §B.1 | H2 in-memory (existing backend test DB) | 1 |
| **Per-op emission-handler unit tests** (32 × engines) | Each handler emits the correct fragment per engine + Mode (GCP built; DPC stub-flagged) `[spec]` #2 §C.1 | H2 / golden-string assertions on generated code | 3 |
| **Rewrite `propagate_inferenceFallbackUsedWhenNoRule`** `[read]` `SchemaPropagationServiceTest.java:579` | Assert a **loud-fail / conflict** on an unknown Blueprint (today stubs `inferOutputSchema` `:598` + `verify(...).inferOutputSchema` `:603` — DELETE those) `[spec]` #1 §B.3 | H2 | 1 |
| **Conflict-tier classification tests** | breaking / partial / non-breaking + impact-radius for a renamed/dropped/retyped column `[spec]` #1 §B.2 | H2 | 1 |
| **V153 migration test** | 39 op-lists written; 4 deprecations idempotent; SnapshotModel `artifact_types=incremental`; param-tiering present `[spec]` #2 §D | **real Postgres** (CI service container; H2 cannot validate JSONB-shape migrations faithfully) | 6 |
| **Byte-exact anchor oracle IT** | bronze 500×86 + silver 290×78, reproducible 2–3×, byte-identical, audit normalized `[spec]` #2 §E | **real harness** (PySpark run + Mode = GCP path; the deterministic-rerun diff per ADR 0009) | 7 |

> **H2 vs real:** the per-op rule/handler unit tests run on **H2** (the existing backend test DB — fast, deterministic
> for pure column-math + string emission). The **V153 migration** and the **anchor oracle** need the **real**
> Postgres/run harness — H2's JSONB + the Spark execution + the byte-exact table-content diff cannot be faithfully
> reproduced on H2. CI already runs Java 21 + Postgres 16 `[report]` `CLAUDE.md` CI section.

---

## 5. MILESTONES — "done" per phase

- **Phase 0 done:** `SchemaBehaviorReader` parses every V153-shaped op-list (and rejects malformed/unknown-op lists)
  + derives the param surface + tier; reader unit-tested; no call-site behavior changed.
- **Phase 1 done:** Schema Propagation walks the op-list; all 32 **per-op rule UNITS** pass — each
  `SchemaOp.apply` against **synthetic / inline op-lists** produces the §B.1 columns-in→out; the fallback test
  rewritten to loud-fail; conflict tiers classified. The LLM-fallback DELETION is **gated on the Phase-6
  lockstep / shim flag** (Risk #1), not declared done here independently. **NOT a Phase-1 bar:** the anchor's
  end-to-end silver column count (78 business + 8 audit) — that whole-pipeline propagation needs the real
  Phase-6 op-lists + Phase-2's 8 columns, so it is a **post-V153 gate** (see Phase 6 done).
- **Phase 2 done:** `IngestionAuditColumns.NAMES` = 8; `_pulse_task`/`_pulse_run_id`/`_pulse_dag_id` live; phantom
  dropped; `asColumnDescriptors` returns 8; design-time bronze = 86.
- **Phase 3 done:** every op has a tested handler per applicable engine; GCP-Mode codegen replaces the per-Blueprint
  branches + the `USING DELTA`/`SparkSubmitOperator` defaults; all 12 fix-items closed at the emission layer; DPC
  handlers stubbed + flagged (P2).
- **Phase 4 done:** one DAG per pipeline; 5 control-ops each emit their own Airflow element; data-aware edges +
  `pulse://` URIs wired; generated code reads `config/<env>.yaml` via `PULSE_ENV` with nothing baked in.
- **Phase 5 done:** `sql-model` resolves via declare-schema + emits user dbt SQL; the CALCITE-PHASE-2 seam is typed
  and inert until P1.
- **Phase 6 done:** V153 applies cleanly on real Postgres — 39 op-lists + tiering written, 4 deprecations + the
  SnapshotModel correction landed; migration test green. **Post-V153 gate (the whole-pipeline propagation claim
  moved off Phase 1):** with the real op-lists in place + Phase 2's 8 columns + the Phase-1 fallback cutover
  (lockstep/shim flipped), the **anchor's silver propagates end-to-end to 78 business + 8 audit columns
  deterministically** — and the LLM fallback is now provably unreachable for the in-use catalog.
- **Phase 7 done:** the anchor oracle IT passes — bronze 500×86 + silver 290×78, byte-identical across 2–3 runs and
  vs the reference. **This is the end-to-end "the deterministic Builder works" proof.**

---

## 6. FAN-OUT — what parallelizes

- **Phase 1 — the 32 schema-effect rule classes** (`.../opengine/ops/*Op.java`). Each is an independent,
  small implementation of one numbered §B.1 rule with one input/output contract; they share only the `ColumnModel`
  + `SchemaOp` interface from Phase 0. **The obvious fan-out** — 32 parallel work-items, each its own unit test.
- **Phase 3 — the 32 emission handlers × engines** (`.../codegen/opengine/handlers/*`). The §C.1 table is *literally
  structured as one row per op*; each handler is independent given Phase-1's authoritative schema + Phase-0's reader.
  This is the largest fan-out (a handler per applicable (op, engine) cell). Group the trivially-similar ones
  (passthrough/row ops share a near-identical dbt-SQL + PySpark shape) to one owner; give the load-bearing ones
  (join, group-and-aggregate, track-history-scd2, take-periodic-snapshot, check-data/quarantine, route-rows, the
  5 control ops) one owner each.
- **Within Phase 3, the two Mode columns** (GCP vs DPC) parallelize per handler, but DPC is P2-gated — fan it out
  only once Livy/Hive lands.
- **NOT parallelizable:** Phase 0 (everything depends on the reader/column-model), the Phase-1 propagation spine
  (the engine that *calls* the rule classes), Phase 6 (single migration file), Phase 7 (the closing gate).

---

## 7. RISKS — genuine build risks

1. **Deleting the LLM fallback before all 39 op-lists exist = mass loud-fail.** The moment Phase 1 removes the
   `default → inferOutputSchema` branch `[read]` `:843-851`, any Blueprint lacking a complete `schema_behavior`
   op-list loud-fails `[spec]` #1 §B.3. **Mitigation:** land V153 (Phase 6) and Phase 1's switch-removal in lockstep
   for the in-use Blueprints, OR keep the old switch as a transitional shim behind a flag until V153 covers the
   catalog. Sequencing the cutover is the single biggest correctness risk.
2. **Byte-exactness is fragile.** Any nondeterminism (map iteration order, unstable dedup/sort, locale-dependent
   formatting, timestamp leakage into the business diff) breaks the 2–3-run identity assertion `[spec]` #2 §E.
   **Mitigation:** `LinkedHashMap`/ordered column sets everywhere (the existing helpers already do `[read]`
   `:1205-1216`), explicit ORDER BY tiebreakers on dedup/rank, rigorous audit-column normalization.
3. **Mode-aware emission is half-built (P2).** Only the GCP/Dataproc path exists `[read]` `CodeGenerationService.java:585`;
   the DPC/Livy/Hive-Parquet half "remains to be built" `[spec]` #2 §C.2 (G-13). Every handler ships with a
   stubbed-and-flagged DPC branch — a real risk if a DPC demo is expected before P2 lands.
4. **Cross-spec drift (#1 ↔ #2 ↔ #5).** The op-list SHAPE (#1 §A.1), the catalog CONTENT (#5), and the emission
   handlers (#2) must agree exactly; a mismatch (e.g. a config param-ref name in #5 not in #1's surface) is
   loud-fail-at-design-time slop. **Mitigation:** run the P8 cross-spec consistency check before Phase 1 lands and
   re-run it after V153 authoring.
5. **`right_<name>` join collision + aggregate type changes are behavior-breaking for existing pipelines.** FIX #5/#6
   change output schemas any downstream consumer was built against. **Mitigation:** the seed-data regenerate
   (`SPEC-INDEX.md:31-38`) must run after these land; flag the schema-shape change in the migration notes.
6. **The recursive column model touches schema discovery + flatten/build-struct.** Moving from flat `{name,type}`
   `[read]` `:1211-1216` to the nested encoding `[spec]` #1 §B.0 risks silently dropping nested fields if any
   consumer still assumes flat. **Mitigation:** a model-conversion shim + a flatten/build-struct round-trip test.
7. **CALCITE-PHASE-2 scope creep (P1).** The seam is easy to over-build into the full validator (which is #6's job).
   **Mitigation:** Phase 5 builds only the declare-schema path + the typed inert seam; the validator stays in #6.
8. **`schema_behavior` is reused by old metadata.** V153 *replaces* the column's content `[read]` `V74:93-97`; a
   half-applied V153 (some rows new-shape, some old) makes the reader loud-fail mid-catalog. **Mitigation:** V153 is
   one transactional migration covering all 39; the migration test asserts coverage.

---

## REPORT

- **Build phases: 8** (Phase 0 foundations → Phase 7 anchor oracle).
- **Prerequisites: 8** (P1 CALCITE-PHASE-2, P2 DPC/Livy build, P3 ADR-0024 forward-updates, P4 #5 catalog content,
  P5 #6 Calcite spec, P6 ADR-0007 format correction, P7 ADR-0011 repair-loop annotation, P8 cross-spec consistency check).
