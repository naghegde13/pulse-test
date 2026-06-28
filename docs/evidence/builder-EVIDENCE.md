# Builder Lane — EVIDENCE

> The deterministic op-composition compiler (specs #1 SPEC-schema-op-engine + #2 SPEC-codegen-compiler;
> impl plan IMPL-builder). Built on branch `build/builder`, worktree `/Users/aameradam/projects/dev/pulse-wt/builder`,
> forked from BASE_SHA `e54751cff1e8a82327fd7549b7f1ea6d5e658a92`.
>
> **SCOPE = Phases 0–5.** Phase 6 (V153 consumption — owned by the Catalog lane) and Phase 7 (the full
> `@Tag("runtime")` byte-exact anchor — needs Docker/Spark + V153) are DEFERRED to post-merge orchestrator
> integration. This worktree has NO V153, so the real runtime anchor cannot run here.
>
> Every claim below is independently re-runnable. All test counts are REAL gradle output (H2 fast lane),
> captured via sub-agents (the lead has Bash for `git -C` + delegates gradle). Anything compiled-but-not-runtime-tested
> or deferred is marked explicitly.

## How to re-run everything (H2 fast lane)

```
cd /Users/aameradam/projects/dev/pulse-wt/builder/backend
./gradlew compileJava compileTestJava            # clean compile (main + test)
./gradlew test --tests "com.pulse.pipeline.opengine.*"        # design-time engine (both spine + 32 rules)
./gradlew test --tests "com.pulse.pipeline.opengine.ops.*"    # 32 schema-rule units
./gradlew test --tests "com.pulse.codegen.opengine.*"         # codegen op-engine + 32 handlers + Phase 4
./gradlew test --tests "com.pulse.codegen.opengine.handlers.*" # 32 emission handlers
./gradlew test --tests "*SchemaPropagationServiceTest"        # rewritten fallback test + engine path
./gradlew test --tests "com.pulse.codegen.audit.IngestionAuditColumnsTest"  # Phase 2 audit 7->8
./gradlew test                                                # full suite (H2)
```

NOT runnable here (deferred): the `@Tag("runtime")` anchor IT
`com.pulse.e2e.runtime.CanonicalLoanMasterAirflowRuntimeIT` (needs Docker/Spark + V153).

---

## SUMMARY

| Phase | What | Status | Tests (real) |
|---|---|---|---|
| 0 | op-list reader + recursive column model + param surface | DONE | reader 8 + column-model 6 = 14 |
| 2 | `IngestionAuditColumns` 7→8 (`_pulse_dag_id`, live `_pulse_task`) | DONE | 4 |
| 1 (rules) | 32 `SchemaOp` schema-effect classes | DONE | 130 |
| 1 (spine) | `SchemaOpEngine` + `OpRegistry` + `ConflictClassifier` + propagation rewrite + LLM-fallback deletion + fallback-test rewrite | DONE | engine 5 + conflict 7 + propagation 23 = 35 |
| 3 | 32 emission handlers × 5 engines (Mode-aware) + `ModeResolver` + 5 emitters + `CodegenOpEngine` + CodeGenerationService seam | DONE | handlers 101 + engine 4 = 105 |
| 4 | `DagAssembler` + data-aware `pulse://` URIs + `ConfigExternalizer` (`PULSE_ENV`/`config/<env>.yaml`) | DONE | 8 |
| 5 | `sql-model` declare-schema path + inert CALCITE-PHASE-2 seam | DONE | 3 |
| 6 | V153 consumption | DEFERRED (Catalog lane owns V153; not in this worktree) | — |
| 7 | byte-exact runtime anchor | DEFERRED (needs Docker/Spark + V153) | — |

**Full backend suite (final): `./gradlew test` → 3043 tests, 12 failed, 2 skipped. ZERO new regressions** — all 12
failures are pre-existing-at-BASE or infra-only (see §FULL-SUITE below). Baseline at BASE_SHA was 2926 tests; the
+117 net are this lane's new tests.

---

## PHASE 0 — op-list reader + recursive column model

NEW package `com.pulse.pipeline.opengine`:
- `ColumnModel.java` — recursive `{name,type,nullable, fields|element}` (SPEC #1 §B.0); round-trips to/from the legacy
  `Map` column shape; closes fix-item #12 (nested types not silently dropped).
- `Schema.java` — ordered `ColumnModel` set with `{"columns":[...]}` round-trip.
- `ParamRef.java` — the `{"param":"<name>"}` token (SPEC #1 §A.1).
- `ParamResolver.java` — substitutes param-refs; loud-fails on a ref absent from the surface.
- `OpList.java` (+ `OpEntry`, `EmissionDecl` records) — parses the pinned shape; loud-fails on malformed shape /
  missing `op`|`ui_label` / op outside the 32 vocabulary (SPEC #1 §A.1).
- `OpVocabulary.java` — the closed 32-op set (invariant-checked size==32).
- `ParamSurface.java` — union derivation (op-refs + `blueprint_params`); `tier`/`derivedFrom`; absent-tier ⇒ `user`
  (G-A3); loud-fail on a union name with no `params_schema` descriptor (SPEC #1 §A.4).
- `SchemaBehaviorReader.java` (`@Service`) — the Phase-0 deliverable: given a `Blueprint`, returns typed
  `OpList` + `ParamSurface`; `hasOpList()` discriminates new-shape vs legacy.
- support: `OpEngineException`, `ResolvedConfig`, `SchemaOp` (interface).

Tests (REAL):
- `./gradlew test --tests "com.pulse.pipeline.opengine.SchemaBehaviorReaderTest"` → **8/8 pass**
  (`SchemaBehaviorReaderTest.java`): parses pinned shape; derives surface as union; absent-tier⇒user; unknown-op
  loud-fail; missing-ui_label loud-fail; param-ref-without-descriptor loud-fail; no-op-list detected; control compute=null.
- `./gradlew test --tests "com.pulse.pipeline.opengine.ColumnModelTest"` → **6/6 pass** (`ColumnModelTest.java`):
  simple/struct/list round-trips; nested struct preserved (fix-item #12); list-of-struct; extras preserved; Schema wrapper.

## PHASE 2 — IngestionAuditColumns 7→8

`backend/src/main/java/com/pulse/codegen/audit/IngestionAuditColumns.java`:
- ADDED `DAG_ID = "_pulse_dag_id"` to `NAMES` (`:44-46`, now 8) and `DEFS`; net bronze = 78 source + 8 = **86**.
- `_pulse_task` is now LIVE: `emitPyspark` emits `os.environ.get('PULSE_TASK_ID', '{{ task.task_id }}')` (was baked slug).
- `_pulse_dag_id` LIVE via `os.environ.get('PULSE_DAG_ID', '{{ dag.dag_id }}')`; `_pulse_run_id` stays live.
- phantom `created_as_timestamp` confirmed absent (it was never in NAMES). `asColumnDescriptors()` auto-picks the 8th.

Tests (REAL): `./gradlew test --tests "com.pulse.codegen.audit.IngestionAuditColumnsTest"` → **4/4 pass**
(`IngestionAuditColumnsTest.java`): NAMES size==8 incl `_pulse_dag_id`; phantom absent; descriptors==8;
emitPyspark emits the 3 live templates + keeps `_pulse_pipeline` baked.

## PHASE 1 — schema engine: 32 rules + spine

### 32 schema-effect rule classes — `com.pulse.pipeline.opengine.ops/` (one `SchemaOp` per op)
All 32 present (verified by independent file-count + name check). Load-bearing fix-items, each asserted in its unit test:
- `JoinOp` — FIX #5: same-name collision keeps BOTH under `right_<name>` (legacy dropped the right col).
- `GroupAndAggregateOp` — FIX #6: COUNT/COUNT_DISTINCT→`long`; SUM(int)→`long`/SUM(dec)→`double`; AVG→`double`; MIN/MAX→source.
- `TrackHistoryScd2Op` — FIX #2: emits `dbt_valid_from/dbt_valid_to/dbt_scd_id/dbt_updated_at` (was transposed to is_current).
- `TakePeriodicSnapshotOp` — FIX #3: emits `ds/_pulse_processing_ts/_pulse_run_id/_pulse_snapshot_model` (was transposed).
- `RouteRowsOp` — FIX #1: `applyMulti` carries the input schema to EACH of N output ports.

Tests (REAL): `./gradlew test --tests "com.pulse.pipeline.opengine.ops.*"` → **130 tests, 0 fail, 0 error**
(verified in one clean `--rerun-tasks` run).

### Spine — engine + conflict + propagation rewrite
- `SchemaOpEngine.java` (`@Service`) — `applyOpList(...)` walks ops in order, resolves config, applies each rule;
  `applyOpListAsMap(...)` round-trips the legacy wrapper for the propagation call site. `OpRegistry.java` —
  closed registry, invariant size==32, one rule per op.
- `ConflictClassifier.java` (`@Service`) — 3-tier (breaking/partial/non-breaking) + impact radius (SPEC #1 §B.2);
  MISSING_COLUMN/TYPE_MISMATCH map onto tiers; widening⇒partial.
- `SchemaPropagationService.deriveBaseOutputSchema` REWRITTEN (`SchemaPropagationService.java`):
  - op-engine path when `schemaBehaviorReader.hasOpList(bp)` (the primary path post-V153);
  - **LLM fallback DELETED** — `schemaInferenceService` field/ctor-arg/`inferOutputSchema` call removed
    (the `SchemaInferenceService` class itself is KEPT — it has a second consumer `SchemaInferenceController`);
  - **Risk-#1 cutover flag** `pulse.builder.loud-fail-on-missing-op-list` (default `false`): flag OFF ⇒ deterministic
    PASSTHROUGH fallback (NOT LLM) so the pre-V153 catalog keeps working; flag ON ⇒ LOUD-FAIL (the post-V153 target,
    SPEC #1 §B.3). No LLM in either mode.
  - conflict emit sites now carry a `tier` detail via `ConflictClassifier`.
  - legacy shim also folds FIX #5 (mergeJoin) + FIX #6 (aggregateSchema) + FIX #2/#3 (SCD2/Snapshot) for parity.
- Fallback test REWRITTEN (`SchemaPropagationServiceTest.java`): old `propagate_inferenceFallbackUsedWhenNoRule`
  (which stubbed `inferOutputSchema` + `verify(...)`) replaced by
  `propagate_unknownBlueprintLoudFailsNoLlmFallback_whenCutoverFlagOn` (asserts `OpEngineException`) +
  `propagate_unknownBlueprintPassthroughNoLlm_whenCutoverFlagOff` (asserts deterministic passthrough). The SCD2 test
  updated to assert the corrected `dbt_valid_*` columns (FIX #2). `SchemaPropagationControllerTest` constructor updated.

Tests (REAL):
- `./gradlew test --tests "com.pulse.pipeline.opengine.SchemaOpEngineTest"` → **5/5 pass**.
- `./gradlew test --tests "com.pulse.pipeline.opengine.ConflictClassifierTest"` → **7/7 pass**.
- `./gradlew test --tests "*SchemaPropagationServiceTest"` → **23/23 pass** (incl. loud-fail flag-ON, passthrough
  flag-OFF, engine-path KeepCols, corrected SCD2).
- `./gradlew test --tests "*SchemaPropagationControllerTest"` → **7/7 pass**.

## PHASE 3 — 32 emission handlers × 5 engines (Mode-aware)

NEW package `com.pulse.codegen.opengine`:
- `Mode.java` / `ModeResolver.java` (`@Service`) — resolves Mode via `RuntimeAuthorityService`; `fileFormatFor` →
  GCP bronze/silver `iceberg`, GCP gold `bq_native`, DPC `parquet` (NEVER `delta`, C-2). `sparkSubmitOperatorFor` →
  GCP `DataprocCreateBatchOperator` (not plain `SparkSubmitOperator`). `assertDpcBuilt(...)` loud-fails for the
  P2-gated DPC/Livy path (stub+flag; GCP is the only built path).
- `EmissionEngine` (5), `EmitContext`, `OpEmitHandler`, `HandlerRegistry`, `EmissionException`.
- 5 emitters: `DbtSqlEmitter` (CTE-chain fusion), `PySparkEmitter`, `GxEmitter`, `DbtSnapshotEmitter`, `DagOnlyEmitter`.
- `handlers/` — **32 handlers, one per op** (engine breakdown: 22 DBT_SQL, 3 PYSPARK, 2 GX, 1 DBT_SNAPSHOT, 5 DAG_ONLY;
  full op→handler→engine coverage independently verified — no op uncovered). Fix-items folded at emission:
  FIX #5 join `right_<name>`; FIX #6 agg types; FIX #2/#10 SCD2 dbt-snapshot no redundant effective_from/to;
  FIX #3 snapshot ds/_pulse_*; FIX #4 cleaning op-driven SELECT (no blanket `SELECT *`); FIX #7 emit-report append
  default + check-data raise on `on_failure=block`; FIX #1 route-rows one model per branch; deterministic dedup
  ORDER BY (ADR 0009); CDCIngestion JDBC|Kafka transport (GAP4); zero-LLM modeling (sql-model emits user dbt SQL).
- `CodegenOpEngine.java` (`@Service`) — assembles the 32 handlers into the registry (invariant 32) + exposes the 5
  emitters. The two-package split (`pipeline.opengine` design-time vs `codegen.opengine` build-time) is intentional & kept.
- `CodeGenerationService.java` — **localized, churn-free seam**: `CodegenOpEngine` injected (ctor-arg + field +
  accessor `codegenOpEngine()`). The per-blueprint codegen branches are NOT yet ripped out (same producer-before-consumer
  cutover as the schema side; gated on V153). **DOWNSTREAM PLUG-IN POINT** for the Calcite lane (sql-model rule-27
  emission, SourceSQL source-prepare, `[[ ]]` lowering) and the post-V153 cutover.

Tests (REAL):
- `./gradlew test --tests "com.pulse.codegen.opengine.handlers.*"` → **101 tests, 0 fail, 0 error**.
- `./gradlew test --tests "com.pulse.codegen.opengine.CodegenOpEngineTest"` → **4/4 pass** (registry==32; every op
  covered; dbt-SQL CTE chain composes; PySpark ingestion chain emits `_pulse_dag_id` + iceberg, never delta).
- CodeGenerationService seam: `./gradlew test --tests "com.pulse.codegen.service.*"` → existing
  `CodeGenerationServiceTest` **69/69 pass** + CompilePlan 7 + DbtAssetRegistry 5 — ZERO new failures from the seam.

## PHASE 4 — DAG emitter + config-externalization

`com.pulse.codegen.opengine`:
- `DataAwareUri.java` — canonical `pulse://<tenant>/<domain>/<dataset>` (SPEC #2 §C.7, G-C4); producer `outlets=` /
  consumer `schedule=` reference the same string; blank segment loud-fails.
- `ConfigExternalizer.java` — `PULSE_ENV ∈ {dev|integration|uat|prod}` selects `config/<env>.yaml` (SPEC #2 §C.3,
  G-C2); `pythonLoader()` reads the slice at job start; `renderSlice()` emits per-env YAML (no baked literals in code).
- `DagAssembler.java` — one DAG per pipeline; data-Blueprint→task, each of the 5 control ops→its own Airflow element
  via `DagOnlyEmitter`; data-aware edges via `pulse://` URIs. Wired into `CodegenOpEngine` (`dagAssembler()`).

Tests (REAL): `./gradlew test --tests "com.pulse.codegen.opengine.Phase4DagAndConfigTest"` → **8/8 pass**.

## PHASE 5 — sql-model declare-schema path + CALCITE-PHASE-2 seam

`com.pulse.pipeline.opengine.SqlModelSchemaService.java` (`@Service`):
- `resolveByDeclaredSchema(cfg)` — builds output `Schema` from the op's `declared_schema` config (the interim path);
  loud-fails if absent (message names CALCITE-PHASE-2).
- `resolveByCalcite(userSql, inputSchema)` — the typed **inert seam**: throws `CalcitePhase2NotBuiltException` until
  the prerequisite ships. NO Calcite dependency added; NO `case "SqlModel"/"SourceSQL"` (the Calcite lane owns those
  and plugs into this seam post-merge). (The `SqlModelOp` schema rule + `SqlModelDbtSqlHandler` emission also use the
  declare-schema path.)

Tests (REAL): `./gradlew test --tests "com.pulse.pipeline.opengine.SqlModelSchemaServiceTest"` → **3/3 pass**.

---

## FULL SUITE — `./gradlew test` (final)

**3043 tests, 12 failed, 2 skipped. ZERO new regressions.** The 12 failures (all independently classified):

PRE-EXISTING (confirmed FAILING at BASE_SHA via a throwaway base worktree run):
- `contract.EndpointReferenceContractTest` — unresolved `/api/v1/*` endpoint string (BUG-67-shape).
- `contract.AdapterConfigVsFormFieldContractTest` — adapter config keys not collected by the form (BUG-67-shape).
- `e2e.builder.ApiScenarioBuilderAdvanceTimeDimensionIT` — legacy DAG operator-vs-function-form drift.

INFRA-ONLY (need infra absent in this worktree — NOT code failures):
- `config.PostgresFlywayMigrationSmokeIT` + `pipeline.SchemaPropagationE2EIntegrationIT` (5 methods) — profile
  `postgres-it`, no Postgres (context fails to load; propagation logic never runs).
- `suitea.EmptyRepoInitSuiteAIT` + `suitea.OnboardingHappyPathSuiteAIT` — no JDBC/Postgres.
- `blueprint.CodegenExampleSharingRegressionTest` — H2 has no seeded `blueprints` table ("Table BLUEPRINTS not found").

None of these are in builder code (op-engine / schema-propagation / codegen-opengine / IngestionAuditColumns / DAG /
config). The schema-propagation E2E IT failures are context-load failures under the no-Postgres profile, NOT logic
failures.

> Risk-#1 verification note: deleting the LLM fallback initially mass-loud-failed the not-yet-op-listed catalog
> (`LoanMasterScenarioCatalogExecutionIT`, 8 transform/modeling/DQ keys). RESOLVED with the cutover flag (default OFF
> ⇒ deterministic passthrough) — that IT now PASSES (1/1). This is exactly the IMPL-builder §3 / §7 Risk-#1 mitigation
> ("flagged transitional shim until V153, then flip the flag").

---

## DEFERRED / NOT DONE (explicit)

- **Phase 6 (V153 consumption)** — DEFERRED. V153 is owned by the Catalog lane (`IMPL-catalog-seed`); this worktree
  has NO V153. The op-engine (Phase 1) + emission (Phase 3) CONSUME the op-lists V153 writes; they are built and unit-
  tested against synthetic/inline op-lists. The post-V153 cutover = flip `pulse.builder.loud-fail-on-missing-op-list`
  to `true` once V153 covers the catalog.
- **Phase 7 (byte-exact runtime anchor)** — DEFERRED. `CanonicalLoanMasterAirflowRuntimeIT` is `@Tag("runtime")` and
  needs Docker/Spark + V153 — not runnable in the H2 fast lane / this worktree. NOT attempted (per scope).
- **FIX #11 (remove 2 orphaned codegen-example files)** — DEFERRED, NOT done. Identifying the exact 2 orphans requires
  the seeded `blueprints.codegen_hints.example_keys` cross-reference (V94/V81 seed data + the `CodegenExampleSharing
  RegressionTest` validator, which can't run on empty H2). Guessing which 2 of the 40 `codegen-examples/` files to
  delete risks breaking live `example_keys` references — left for the Catalog/seed lane which has the authoritative map.
- **DPC half of Mode-aware handlers** — STUBBED + FLAGGED (P2, G-13), per spec. `ModeResolver.assertDpcBuilt(...)`
  loud-fails the unbuilt DPC/Livy/Hive-Parquet path. GCP is the only built path (as the spec mandates today).
- **CodeGenerationService branch rip-out** — the `CodegenOpEngine` is wired in as a clean seam but the per-blueprint
  codegen branches are NOT yet replaced (gated on V153, same cutover constraint as the schema side).

## DOWNSTREAM PLUG-IN POINTS (single-writer files, ready for the Calcite lane post-merge)

- `SchemaPropagationService.deriveBaseOutputSchema` — the op-engine path is the call site for the `sql-model` rule-27
  resolution (the `SqlModelOp` + `SqlModelSchemaService` declare-schema path is wired; the Calcite-validate branch is
  the inert seam `SqlModelSchemaService.resolveByCalcite`).
- `SqlModelSchemaService.resolveByCalcite(userSql, inputSchema)` — the typed inert CALCITE-PHASE-2 seam.
- `CodeGenerationService.codegenOpEngine()` — the build-time composition seam (sql-model emission, SourceSQL
  source-prepare branch, `[[ ]]` mnemonic lowering slot in here without churn).
