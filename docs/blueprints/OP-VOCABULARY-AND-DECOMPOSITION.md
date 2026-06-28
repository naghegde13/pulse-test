# Builder Op Vocabulary & Blueprint Decomposition — Branch 2 lock-basis

> `[report]` — code-verified by the re-decomposition agent (file:line below); transcribed
> here by the orchestrator (sub-agents are write-blocked in this env). **Pending operator
> sign-off → becomes the Branch-2 ADR.** Re-verify a specific item at file:line before
> building on it.

## The model (locked in the grill)
- A blueprint's data behavior = ONE ordered list of **self-describing ops**; each op declares
  its `schema-effect` / `row-effect` / `side-output`. **No op equals a blueprint name.**
- **Intent-is-canonical:** decompose to what the blueprint is *meant* to do; current
  pass-through / `SELECT *` / LLM-fallback / wrong-column behaviors are **bugs** (the fix-list).
- **Zero LLM for schema** (ADR 0011). Emission is blueprint-declared, never LLM-chosen.
- Airflow DAG is the universal substrate (one per pipeline; every blueprint contributes a task).

## Op table (plain descriptions)
| Op | Meaning | Notes |
|---|---|---|
| read-source | pull rows from a source connector | data-movement in |
| add-audit-columns | append PULSE's standard audit columns | |
| add-column | add a NEW column from an expression (incl. window functions) | window = add-column, no separate op |
| transform-values | replace an existing column's values via an expression (trim/fill-nulls/standardize) | schema & rows unchanged |
| drop-columns / keep-columns | remove named cols / keep a subset | |
| rename-columns / change-types | rename / cast | |
| mask-columns | hash/redact column values | |
| flatten-json / build-struct | expand nested→flat / pack cols→struct | |
| join | combine two inputs on keys (collision → `right_` prefix) | |
| group-and-aggregate | collapse rows → group cols + agg cols | |
| union-all / distinct-union / sort / sample-limit | combine/reshape | currently unused by catalog (see coverage) |
| filter-rows / deduplicate / route-rows / merge-rows | row ops (route = N outputs; merge = upsert) | |
| track-history-scd2 | dbt `{% snapshot %}`; cols `dbt_valid_from/dbt_valid_to/dbt_scd_id/dbt_updated_at` | emission dbt-snapshot |
| take-periodic-snapshot | dbt INCREMENTAL model; cols source + `ds` + `_pulse_processing_ts/_pulse_run_id/_pulse_snapshot_model` | emission dbt-SQL |
| check-data | DQ checks; raises→fails run; quarantine side-output = `filter-rows`+auto-materialize a managed table | emission GX |
| emit-report | append-only report table | emission GX |
| write-sink | write rows to a destination | |
| sql-model | a SQL-chaining chain element carrying user-authored dbt SQL (power-user / DE) | schema via Calcite-validate-against-input OR declared; emission **dbt-SQL** |
| sense / schedule-and-triggers / rollback / advance-time / invoke-remote | portless control behaviors | DAG-only |

> **Op count = 32** (closed vocabulary). `sql-model` is the power-user SQL-chaining op added when ADR 0013 locked (the LLM is out of codegen; a DE may write the SQL by hand or ask Chat to draft it, then it is Calcite/handler-validated). Its build prereq is the Calcite "Phase-2" validator (Expression Builder gap) or the declare-schema path.

## Decomposition (43 blueprints; 4 DEPRECATING; lock-basis = 39)

**INGESTION (6, PySpark)** — all = `read-source → add-audit-columns → write-sink(bronze)`.
(CDCIngestion: codegen is a plain JDBC read, no change-column — intent gap, fix-list.)

**TRANSFORM (10, dbt-SQL)**
- BronzeToSilverCleaning = `transform-values`(trim/fill-nulls) + `rename-columns` + `change-types` + `drop-columns` + `deduplicate` *(intent; code does `SELECT *` today — fix #4)*
- SchemaNormalization = `rename-columns` (+`change-types` via target_schema, +`drop-columns` if strict_mode)
- DedupeAndMerge = `deduplicate` · PIIMasking = `mask-columns` · GenericJoin = `join` · GenericAggregate = `group-and-aggregate` · GenericFilter = `filter-rows`
- GenericRouter = `route-rows` (N outputs) *(breaks-model #1)*
- JsonFlatten = `flatten-json` · JsonStruct = `build-struct` (+`drop-columns`)

**MODELING (8, dbt-SQL; SCD2 = dbt-snapshot)**
- SCD2Dimension = `track-history-scd2` · SnapshotModel = `take-periodic-snapshot`
- FactBuild = `join` + `keep-columns` · WideDenormalizedMart = `join`(N) + `group-and-aggregate` + `keep-columns`
- AggregateMaterialization = `group-and-aggregate` (≡ GenericAggregate — merge candidate)
- IncrementalMerge = `merge-rows`(upsert)
- ReferenceDataPublish = `keep-columns` + `deduplicate` + `filter-rows` + `write-sink`
- FeatureTablePublish = `join` + `group-and-aggregate` + `keep-columns` + `write-sink`

**DATA_QUALITY (4, GX)** — DQValidator = `check-data` (quarantine→managed table). FreshnessChecks / SchemaDriftDetection / AnomalyDetection = `check-data` + `emit-report`.

**SINK (4, PySpark)** — all = `write-sink(target, mode)` (Warehouse / Lake / Stream / Database).

**CONTROL (11, DAG-only)** — `sense`: FileArrivalSensor, DatabaseReadinessSensor, ExternalEventSensor. `schedule-and-triggers`: ScheduleAndTriggers. `rollback`: RollbackOnFailure. `advance-time`: AdvanceTimeDimension. `invoke-remote`: RemotePipelineInvocation.
DEPRECATING (4): ObjectStoreKeySensor→FileArrivalSensor · DatasetDependencySensor→ScheduleAndTriggers triggers · BackfillAndReplay→BulkBackfill+ScheduleAndTriggers · CostMonitoringHook→none(platform).

## Coverage
- **All 39 survivors decompose cleanly — NO op==blueprint-name, NO undeclared effect** (verified). The cryptic modeling blueprints all dissolve into primitives (above).
- **Unused ops:** `union-all`, `distinct-union`, `sort`, `sample-limit` (+ `pivot/unpivot`) — no current blueprint uses them. Kept by operator decision for vocabulary completeness; `sample-limit` gets its own atomic blueprint. **Not a gap.**
- **Atomic blueprints** already exist (GenericFilter=`filter-rows`, GenericAggregate=`group-and-aggregate`, GenericJoin=`join`); `sample-limit` joins them.

## Fix-items (the real "fix-S1 / Builder" worklist — NOT lock blockers)
1. **GenericRouter N-outputs** (breaks-model): codegen emits N+1 models; schema-prop gives one passThrough port (`:815` vs `:2914-3023`).
2. **SCD2 schema rule transposed** (`:823-828`): declares `valid_from/valid_to/is_current`; codegen emits `dbt_valid_*` + custom effective cols (`:2158-2164`); no `is_current` exists.
3. **SnapshotModel schema rule transposed** (`:829-834`): declares `dbt_valid_*`; codegen emits `ds`+`_pulse_*` (`:2230-2236`). #2 & #3 are swapped.
4. **BronzeToSilverCleaning (anchor)**: schema=passThrough (`:816`) AND body=`SELECT *` (`:2345-2348`) — ignores every declared param. Strongest intent-vs-code bug.
5. **GenericJoin** same-type collision drops the right col instead of `right_`-prefix (`:1038`).
6. **GenericAggregate** output types: COUNT→integer (→ long), SUM/AVG→decimal (→ double) (`:1063-1067`).
7. **DQ reports** (Freshness/Drift/Anomaly) write `mode('overwrite')` (`:4281`) + report-only (don't raise on FAIL); intent = append-only + can fail the job.
8. **Zero-LLM gap:** FactBuild / WideDenormalizedMart / AggregateMaterialization / FeatureTablePublish / ReferenceDataPublish still fall to LLM (`:835`) despite deterministic decompositions.
9. SnapshotModel metadata mis-tag (`artifact_types=dbt_snapshot`, `:2072-2076`) → blueprint backlog.
10. SCD2 redundant custom cols (`:2162-2164`).
11. 2 orphaned codegen-example files (V94 wiring leftovers).
12. JsonStruct nested-struct types not carried by the flat `{name,type}` schema model.

**Catalog/merge candidates (operator decision, not mechanical):** AggregateMaterialization ≡ GenericAggregate; BackfillAndReplay vs BulkBackfill; ObjectStoreKeySensor ⊂ FileArrivalSensor; DatasetDependencySensor name/param mismatch.

## Evidence
`SchemaPropagationService.deriveBaseOutputSchema:810-854` (+ resolvers `mergeJoin:1022`, `aggregateSchema:1044`, `normalizeSchema:1075`, `maskSchema:897`, `ingestionSchema:861`, addenda `:964/:997`); `CodeGenerationService` (cleaning `:2345`, dedupe `:2459`, router `:2914-3023`, SCD2 `:2104-2168`, SnapshotModel `:2170-2239`, DQ `:4232-4351`); `GxCodeGenerator:32-172`; `IngestionAuditColumns:44-46,87-139`; `docs/blueprints/BLUEPRINT-RATIONALIZATION.md:134-183`.
