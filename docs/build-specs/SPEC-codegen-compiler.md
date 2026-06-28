# SPEC #2 — Codegen / Compiler (BUILD-time)

> Status: **DRAFT — split from the bundled `SPEC-builder-compiler-CONTRACTS.md` per the
> operator-confirmed 5-spec map (`SPEC-INDEX.md`).** This file carries **§C emission handlers**
> (+ config-externalization + `sql-model`/Calcite) + **§D the V153 migration** + **§E the
> byte-exact anchor oracle**. The **design-time** halves (§A metadata model, §B the 32 ops'
> schema-effect rules + propagation/conflict/enforcement) live in the sibling spec
> **`docs/build-specs/SPEC-schema-op-engine.md`** (#1). **#2 must satisfy #1's schema output**
> (`SPEC-INDEX.md` §Cross-reference): codegen is subordinate to the design-time columns and never
> decides them.
>
> **Decision record (cited, not re-decided here):** `docs/build-specs/SPEC-builder-compiler.md`
> (the Grill log + RESUME-HERE — the locked design) and ADRs 0013 (LLM is OUT of codegen) ·
> 0003 (materialization tiers) · 0009 (byte-exact output) · 0006/0007 (Spark-per-Mode / GCP
> format) · 0001 (Mode exclusivity) · 0011 (deterministic schema = enforced contract) · 0012
> (32-op closed vocabulary) · 0020/0021/0022 (control-ops / cross-pipeline / sensor transparency)
> · 0023 (param-tiering). This spec **applies** those decisions; it does not re-decide them.
>
> **Coverage basis:** **all 32 ops × 5 emission types (dbt-SQL / PySpark / GX / dbt-snapshot /
> DAG-only), Mode-aware** (GCP Composer/Dataproc/Iceberg-on-GCS vs DPC plain-Airflow/Livy/
> Hive-Parquet) + the **12 fix-items** (OP-VOCAB doc) + the **V153 migration** + the **byte-exact
> anchor oracle** (ADR 0009). The SPEC-GATE's completeness pass checks this spec against exactly
> that basis.
>
> Evidence tags: `[read]` = confirmed at the cited file:line in this repo; `[report]` =
> transcribed from a locked doc/ADR, not re-verified here.
>
> Vocabulary (enforced): **Customer** (never "user"), **op / op-list** (never "recipe"/"steps"),
> **params** (never "settings"), **Blueprint**, **Designer/Builder** (design time vs build time).

---

## §C — EMISSION (per-op handler per engine)

Each op has a tested codegen handler emitting its fragment per emission engine. A Blueprint's code
= the deterministic composition of its op-list handlers (no LLM). `[report]` ADR 0013 §1.
The emission DECLARATION (`schema_behavior.emission`) is defined in **#1 §A.5**; this section
defines the HANDLERS that consume it.
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
| union-all | `UNION ALL` between models | `.unionByName()` | n/a | model |
| distinct-union | `UNION` (or `UNION ALL` + `DISTINCT`) | `.unionByName().dropDuplicates()` | n/a | model |
| sort | `ORDER BY` | `.orderBy()` | n/a | model |
| sample-limit | `LIMIT`/`TABLESAMPLE` | `.limit()/.sample()` | n/a | model |
| track-history-scd2 | n/a | n/a | n/a | **snapshot** (`{% snapshot %}`) |
| take-periodic-snapshot | `{{config(materialized='incremental')}}` | n/a | n/a | **incremental** |
| check-data / emit-report | n/a | GX checkpoint reads delta, writes side-table | GX suite | n/a |
| read-source / add-audit-columns / write-sink | n/a | PySpark read / `IngestionAuditColumns.emitPyspark` / write | n/a | n/a |
| sql-model | user dbt SQL (Calcite-validated) | n/a | n/a | per-config |
| sense | n/a | n/a | n/a | **DAG-only** — Airflow sensor |
| schedule-and-triggers | n/a | n/a | n/a | **DAG-only** — Airflow schedule/`TriggerDagRunOperator` |
| rollback | n/a | n/a | n/a | **DAG-only** — Airflow failure callback / rollback task (ADR 0020) |
| advance-time | n/a | n/a | n/a | **DAG-only** — Airflow time-advance task (ADR 0023) |
| invoke-remote | n/a | n/a | n/a | **DAG-only** — `RemotePipelineInvocation` operator (ADR 0021) |

> **NOTE — control-ops are per-op, NOT one row.** The five control ops
> (`sense`/`schedule-and-triggers`/`rollback`/`advance-time`/`invoke-remote`) each emit a DISTINCT
> Airflow element and each needs its own tested handler — they are split per-op above rather than
> lumped into one "DAG-only" row, because each is a different operator/callback shape. (Closes the
> control-ops-lumped-into-one-row finding; see OPEN WORKLIST.)
>
> **NOTE — union-all/distinct-union/sort/sample-limit handlers added (GAP2 RESOLVED).**
> These four ops are kept-but-currently-unused by the catalog (OP-VOCAB doc:70), but `sample-limit`
> gets its own atomic Blueprint and therefore **MUST** have a working emission handler (dbt-SQL
> `LIMIT`/`TABLESAMPLE`, PySpark `.limit()/.sample()`); the other three are kept for vocabulary
> completeness with the fragments listed in the table (union-all → `UNION ALL`/`.unionByName()`;
> distinct-union → `UNION`/`.unionByName().dropDuplicates()`; sort → `ORDER BY`/`.orderBy()`).
> Emission coverage is now complete across all 32 ops.

Grounding references for handlers:
- **SnapshotModel** dbt-incremental emission `[read]` CodeGenerationService.java:2170-2239 (config
  block :2206-2221; the `ds` + `_pulse_processing_ts/_pulse_run_id/_pulse_snapshot_model` SELECT
  :2230-2236 — the FIX #3 column set).
- **SCD2** dbt-snapshot emission `[read]` CodeGenerationService.java:2104-2167 (the `dbt_valid_*`
  set is managed by the snapshot strategy; FIX #10 = drop the redundant custom `effective_from/to`
  cols at :2162-2164).
- **Audit columns** PySpark emit `[read]` IngestionAuditColumns.emitPyspark:87-138 (single source
  of truth, matches the design-time set).
  > **C-1 RESOLVED (locked 2026-06-15) — `IngestionAuditColumns` change (emission side):** the
  > single-source-of-truth set goes from **7 → 8** columns and three become **live Airflow
  > templates**. Concretely, in `IngestionAuditColumns.java`:
  > - **ADD** constant `DAG_ID = "_pulse_dag_id"` (string) and append it to `NAMES` (now 8) and to
  >   `DEFS` (type `string`, description "Airflow DAG id (`{{ dag.dag_id }}`); supplied via
  >   PULSE_DAG_ID env var").
  > - **DROP** any `created_as_timestamp` notion (it was never in `NAMES`; the anchor key listed it —
  >   it is removed there, see ANCHOR-PIPELINE-SPEC.md). It equalled `_pulse_ingested_at`.
  > - **`_pulse_task` becomes LIVE:** `emitPyspark` must emit `_pulse_task` from the live Airflow
  >   template `{{ task.task_id }}` (via a `PULSE_TASK_ID` env-var fallback, the same pattern as
  >   `_pulse_run_id`'s `PULSE_RUN_ID`/`{{ run_id }}` at :108-110), **not** the baked `taskSlug`
  >   literal currently emitted at :107. (`_pulse_pipeline` stays the baked pipeline slug.)
  > - **`_pulse_run_id`** stays live `{{ run_id }}` (:108-110); **`_pulse_dag_id`** emits live
  >   `{{ dag.dag_id }}` via a `PULSE_DAG_ID` env-var fallback (mirror the run_id pattern).
  > - `asColumnDescriptors()` automatically picks up the 8th column from `DEFS` — design-time and
  >   runtime stay aligned (the class's own invariant).
  > Net: bronze = 78 source + **8** audit = **86** (anchor oracle §E). The three live columns
  > (`_pulse_task`, `_pulse_run_id`, `_pulse_dag_id`) plus the timestamps are normalized out of the
  > byte-exact business-data diff (ADR 0009). Matches #1 §B rule 25 (schema-effect side).
- **GX/DQ** checkpoint emit `[read]` CodeGenerationService.java:4232-4293; the FIX #7 target is
  here — today `mode('overwrite')` at :4281,4284 must become **append** for report tables, and
  semantic reports must **raise on FAIL** when `on_failure=block` (today report-only).
- **Emission examples corpus** (reference for handler authors, NOT runtime LLM grounding per
  ADR 0013 §4) `[read]` `backend/src/main/resources/codegen-examples/` (8 dirs: ingestion,
  staging, snapshots, marts, intermediate, sinks, gx, orchestration). FIX #11 = remove the 2
  orphaned example files (V94 leftovers). `[report]` OP-VOCAB doc:84.

### C.2 Mode-aware emission (ADR 0006/0007)
> WHEN the active Mode is `GCP_PULSE` THE SYSTEM SHALL emit Spark execution as a
> `DataprocCreateBatchOperator` (Dataproc Serverless), orchestrate on **Composer**, and write
> bronze/silver as **Iceberg-on-GCS** (Hadoop/GCS catalog) for the interim, with **BigQuery-managed
> Iceberg** as the tracked target;
> WHEN the active Mode is `DPC_PULSE` THE SYSTEM SHALL emit Spark via **Apache Livy**, orchestrate
> on **plain Airflow** (not Composer), and write **Hive + Parquet**. Plain `SparkSubmitOperator` is
> the target for neither Mode. `[report]` ADR 0006 §Decision + ADR 0007 §Decision + ADR 0001.
> Mode resolved via `RuntimeAuthorityService`.
>
> **C-2 RESOLVED (locked 2026-06-15): DPC bronze/silver = Hive + Parquet** (ADR 0001 + CONTEXT.md).
> Delta is the format for **neither** Mode — it was only the Builder's old Mode-blind hardcoded
> bronze default (`USING DELTA`). The contradiction came from ADR 0007's "Considered" text calling
> Delta "the DPC format"; that text is now **corrected** in `docs/adr/0007-*.md` (~line 34) to say
> Delta is the format for neither Mode and DPC = Hive+Parquet. Summary table:
>
> | Mode | Orchestration | Spark submit | bronze/silver format | gold format |
> |---|---|---|---|---|
> | `GCP_PULSE` | Composer | `DataprocCreateBatchOperator` (Dataproc Serverless) | Iceberg-on-GCS (interim) → BigQuery-managed Iceberg (target) | **BigQuery-native** (not Iceberg) |
> | `DPC_PULSE` | plain Airflow | Apache Livy (batch submit) | **Hive + Parquet** | **Hive + Parquet** |
>
> **Gold-layer format (locked, ADR 0007 + ADR 0001):** GCP gold tables are **BigQuery-native** (NOT managed-Iceberg —
> only bronze/silver are Iceberg); DPC gold stays **Hive + Parquet** like the other layers. This per-layer split is
> already encoded in the live runtime resolver (`RuntimeAuthorityService.java:134-176`: GCP gold → `bq_native`); the
> emission handlers must honor it, not blanket-Iceberg the GCP path.
>
> **G-13 RESOLVED-AS-FLAGGED: the DPC/Livy/Hive-Parquet emission path "remains to be built."**
> Per ADR 0006 §"Scope today vs tracked", only the GCP/Dataproc path is built today; only a plain
> `SparkSubmitOperator` exists in `src/main` (`CodeGenerationService.java:585`). This spec contracts
> the DPC emission **target** (Livy submit + Hive+Parquet write + plain-Airflow orchestration), but
> flags that it is **NOT yet present in `src/main`** — it is a build task, tracked in
> `docs/PULSE-MAP.md`, not a ready-to-apply capability. The format question (C-2) is now settled;
> the build-existence question (G-13) is the open work.

> **GAP3 RESOLVED (Mode-aware emission across ALL five engines, locked 2026-06-15).** The Mode
> swap is specified per-engine below, not just for PySpark. All resolve Mode via
> `RuntimeAuthorityService`; the GCP column is the only built path today (the DPC column is the
> tracked target, gated on G-13).
>
> | Engine | `GCP_PULSE` | `DPC_PULSE` |
> |---|---|---|
> | **PySpark** | Dataproc Serverless (`DataprocCreateBatchOperator`); write Iceberg-on-GCS → BQ-managed Iceberg | Apache Livy submit; write **Hive + Parquet** |
> | **dbt** | dbt adapter target = the GCP Spark/Dataproc (or BQ) profile; catalog/relations as Iceberg-on-GCS/BQ; `file_format='iceberg'` | dbt adapter target = the Cloudera Spark profile (via Livy/Thrift); catalog = Hive metastore; **`file_format='parquet'`** |
> | **GX** | checkpoint store + DataContext on GCS; quarantine/report **side-tables written as Iceberg-on-GCS** | checkpoint store on S3-compatible storage; side-tables written as **Hive + Parquet** |
> | **dbt-snapshot** | snapshot store = the dbt target above (Iceberg/BQ); `{% snapshot %}` materializes Iceberg | snapshot store = Hive metastore; `{% snapshot %}` materializes **Parquet** |
> | **DAG / Airflow** | DAG runs on **Composer**; Composer operator flavors (Dataproc/GCS/Iceberg operators) | DAG runs on **plain Airflow**; plain-Airflow operator flavors (Livy/HDFS/S3-compatible operators) |
>
> Each row's DPC half inherits G-13's "remains-to-be-built" flag where it depends on the Livy/Hive
> path. The format/target choices are settled here; the DPC build is the open work.

### C.3 Config-externalization (ADR 0013)
> WHEN the Builder emits any artifact THE SYSTEM SHALL make generated code read its per-env config
> from an **env-var-selected per-env config slice**, baking NO literal connection strings / paths /
> project IDs into the code. `[report]` SPEC-builder-compiler.md:21-22 + ADR 0013. Today the audit
> emit already reads env vars (`PULSE_RUN_ID`/`PULSE_BUSINESS_DATE`/`PULSE_SOURCE_URI`)
> `[read]` IngestionAuditColumns.java:108-121.
> **G-C2 — RESOLVED (operator-decided 2026-06-15):** the per-env config slice is selected by the
> env var **`PULSE_ENV` ∈ `{dev | integration | uat | prod}`**, which names the slice file
> **`config/<env>.yaml`** (`config/dev.yaml`, `config/integration.yaml`, `config/uat.yaml`,
> `config/prod.yaml`), loaded at job start. Generated code reads its per-env values (connection
> strings, paths, project IDs) from that slice — never baked into the code. This pins the env-var
> name (`PULSE_ENV`) and the file layout (`config/<env>.yaml`) as THE selection mechanism; it
> consistently extends the existing env-var reads (`PULSE_RUN_ID` etc.) noted above.

### C.4 sql-model + Calcite Phase-2 (PREREQUISITE, not an apply)
> WHEN a `sql-model` op carries user dbt SQL THE SYSTEM SHALL derive its output schema by
> Calcite-validating the SQL against the input schema (or use the declared schema), and SHALL fail
> the build if Calcite cannot validate it. `[report]` ADR 0013 §3 + OP-VOCAB doc:36,39.
>
> **G-7 RESOLVED-AS-PREREQUISITE (locked 2026-06-15): the Calcite "Phase-2" validator is a named
> hard BUILD PREREQUISITE `CALCITE-PHASE-2`, not an apply.** A **parse-only** Calcite Babel validator DOES
> exist today (`ExpressionValidationService.java:99-113`); `CALCITE-PHASE-2` is its named **"Phase 2"** (`:36-42`)
> — the **schema-deriving extension** of that parse-only validator (adds `SqlValidator`/`Frameworks.getPlanner` +
> `RelDataType` derivation), which does NOT exist yet. The `sql-model` schema rule in #1 §B (rule 27) and this
> emission handler both DEPEND on that `CALCITE-PHASE-2` extension. **Until it is
> built, `sql-model` uses the declare-schema path only** (the DE supplies a declared output schema);
> the Calcite-validate-against-input branch is unavailable. This is the same named prerequisite #1
> §B rule 27 references. It is flagged as a prerequisite the build must satisfy first — NOT
> ready-to-apply.

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
> `[read]` SchemaPropagationService.java:815-817. The schema-prop side is fixed in #1 §B rule 18;
> this is the emission side.)

### C.7 Data-aware edges (C1)
> WHEN two pipelines on the SAME Airflow have a cross-pipeline dependency THE SYSTEM SHALL emit
> native Airflow Datasets: the producer gets `outlets=[Dataset(uri)]`, the consumer gets
> `schedule=[Dataset(uri)]`, where PULSE **generates the canonical URI** from the registered
> dataset the Customer selected (the Customer never sees URIs or Airflow syntax); the dependency
> edge SHALL be surfaced on the canvas. WHEN the dependency is CROSS-Airflow (separate instance/
> Mode) THE SYSTEM SHALL emit a `RemotePipelineInvocation` (invoke-remote op) instead.
> `[report]` SPEC-builder-compiler.md:115-122 (C1) + ADR 0021 + ADR 0022. Build = wire the existing
> unwired `DatasetScheduleService` + fix the `event`/`dataset_event` mismatch `[report]`
> SPEC-builder-compiler.md:121-122 (untagged in source — verify before building).
> **G-C4 — RESOLVED (operator-decided 2026-06-15):** the canonical Airflow-Dataset URI format is
> **`pulse://<tenant>/<domain>/<dataset>`**. PULSE generates this URI from the registered dataset
> the Customer selected (tenant + domain + dataset identifiers); the producer's `outlets=[Dataset(
> "pulse://<tenant>/<domain>/<dataset>")]` and the consumer's `schedule=[Dataset("pulse://<tenant>/
> <domain>/<dataset>")]` reference the same string. The Customer never sees or authors this URI.
> Pinned as THE canonical URI scheme.

### C.8 CDCIngestion emission (RESOLVED — same generic Ingestion handler)
> The Ingestion decomposition is `read-source → add-audit-columns → write-sink(bronze)`, and
> **CDCIngestion emits the SAME shape with NO new op** (emission side of #1 §B.4 GAP4). **GAP4 —
> RESOLVED (operator-decided 2026-06-15):** CDCIngestion reads a source that is **already a CDC log**
> (change data captured upstream), so the existing `read-source` handler is correct — it reads the
> CDC log's columns (including the source's insert/update/delete change-type/op columns) through
> as-is; PULSE does **not** generate change-capture columns or logic, and there is no CDC-variant
> handler, no `merge-rows`-into-bronze tail. The "plain JDBC read with no change-column" observation
> was a **MISREAD** — a plain read of a CDC log is the intended emission. `[report]` OP-VOCAB doc:44.
> Two refinements bind the handler:
> - **Transport:** the `read-source` connector for CDCIngestion MUST support **both JDBC and Kafka**
>   (the CDC log may be served over either); Mode-aware emission (§C.2) still applies to the write.
> - **Source contract:** CDCIngestion's `read-source` **expects a CDC-log source** (documented
>   precondition); the change-type/op columns are SOURCE columns, not PULSE-emitted.
> (Op-coverage side in #1 §B.4.)

---

## §D — V153 MIGRATION

**Version:** `V153` (G1's V152 is integrated; current head on this branch is **V151**
`[read]` `V151__widen_user_git_identities_scopes.sql` is the highest migration present; no V152
exists on this branch). File name SHALL be
`backend/src/main/resources/db/migration/V153__builder_op_lists_and_param_tiering.sql`.

> WHEN V153 runs THE SYSTEM SHALL, for each of the **39 surviving Blueprints**, write its op-list
> into `schema_behavior` (replacing the throwaway `{effect_type,conflict_policy}`) and write
> `tier`/`derivedFrom` into each `params_schema` element. `[report]` SPEC-builder-compiler.md:24-25,
> 33 + OP-VOCAB doc:41 (39 survivors). (The op-list shape it writes is defined in #1 §A.1; the
> param-tiering shape in #1 §A.3.)

The 39 op-lists to seed are the decompositions in OP-VOCAB doc:43-66 (e.g. Ingestion ×6 =
`read-source→add-audit-columns→write-sink(bronze)`; BronzeToSilverCleaning per #1 §A.2; SCD2Dimension=
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

> WHEN V153 seeds SnapshotModel THE SYSTEM SHALL also correct its mis-tagged metadata: set
> SnapshotModel's `artifact_types` to **`incremental`** (NOT `dbt_snapshot`) so the metadata
> matches its actual `take-periodic-snapshot` → dbt-**incremental** emission. The live mis-tag is
> seed data at `[read]` V81__blueprint_catalog_rationalization.sql:426
> (`WHEN 'SnapshotModel' THEN '["dbt_snapshot"]'`) — uncorrected since (V94:110-118 touches only
> `codegen_hints.example_keys`), so V153 MUST override it. (SCD2Dimension's `["dbt_snapshot"]` at
> V81:425 is correct and stays.) **GAP1 / FIX #9 RESOLVED
> (locked 2026-06-15):** the corrected value is `incremental`; this is a migration/metadata
> correction slotted to V153. (Note the contrast: `track-history-scd2`/SCD2Dimension is the op that
> truly emits a dbt **snapshot**; SnapshotModel's `take-periodic-snapshot` op emits dbt
> **incremental** — the old `dbt_snapshot` tag was on the wrong Blueprint.)

> **G-D1 — RESOLVED (verified 2026-06-15): all 4 deprecation-target keys currently exist as ACTIVE
> rows, so V153 deprecating them is a sound contract.** V153 MUST still guard each of the 4
> deprecation UPDATEs idempotently (no-op if the key is absent), per V81's convention `[read]`
> V81:11-13 — that idempotent-guard contract is pinned. The pre-V153 existence check is now done at
> file:line:
> - `BackfillAndReplay` — **active**; inserted `[read]` V7:375 (`'BackfillAndReplay'`, default
>   `status='active'`), still treated as active at `[read]` V81:559 (`... AND status='active'`); also
>   `pipeline_config=TRUE` at `[read]` V9:79. Not in V81's deprecation list (V81:19-40).
> - `CostMonitoringHook` — **active**; inserted `[read]` V7:439, still active per `[read]` V81:559;
>   `pipeline_config=TRUE` at `[read]` V9:79. Not in V81:19-40.
> - `ObjectStoreKeySensor` — **active**; inserted with explicit `'active'` status `[read]` V75:60-84
>   (status at V75:84); control-plane re-tagged (not deprecated) at `[read]` V81:566-573.
> - `DatasetDependencySensor` — **active**; inserted with explicit `'active'` status `[read]`
>   V75:113-128 (control-plane re-tagged at V81:566-573, not deprecated).
> No migration (incl. V81's deprecation block V81:19-40 and V112's `add_surface='none'` overrides
> V112:39-41) deprecates any of the 4. Therefore the §D deprecation UPDATEs above are the FIRST to
> deprecate them — the contract is sound, no key is missing or already-deprecated.
> **G-D2 — DEFERRED-MECHANICAL (now unblocked):** the 39 per-Blueprint op-list JSONs are mechanical
> V153 drafting. The DECOMPOSITION is locked (OP-VOCAB doc); the SHAPE is pinned (#1 §A.1, G-1) and
> the Cleaning param keys pinned (#1 §A.2, G-A2) — so this is unblocked mechanical drafting, not a
> design gap.

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
  ANCHOR-PIPELINE-SPEC.md:25,38-42. **C-1 RESOLVED:** the 8 audit columns are `_pulse_ingested_at`,
  `_pulse_processing_ts`, `_pulse_pipeline`, `_pulse_task`, `_pulse_run_id`, `_pulse_source_uri`,
  `_pulse_business_date`, **`_pulse_dag_id`** (the phantom `created_as_timestamp` is dropped; the new
  `_pulse_dag_id` is added — count stays 86). See the `IngestionAuditColumns` change in §C.
- **Silver** (anchor) = **290 rows × 78 business columns + 8 carried-through bronze audit columns**
  (the audit columns flow through unchanged; silver does NOT re-add or drop them). `[read]`
  data-oracle.json:428,527 (`"Current":290`) + loan-master-scenario-families.json:221,34
  (`"row_count":290`, `"column_count":78`, derivative `current_loans`).
  - **G-11 RESOLVED:** the silver **business**-column count is **78**, not 87 — the task framing's
    "290×87" is **ungrounded** (no read artifact asserts 87; the `current_loans` derivative tracks
    `column_count:78`). Silver's full physical column count = 78 business + 8 carried audit = **86**
    (same shape as bronze, fewer rows); the byte-exact diff is on the 78 business columns.
  - **G-12 RESOLVED:** the **anchor silver op-list = Bronze-to-Silver Cleaning's ops** (all
    passthrough when unconfigured — `transform-values` → `rename-columns` → `change-types` →
    `drop-columns` → `deduplicate`) **followed by a `filter-rows(loan_status="Current")` op**. The
    **`filter-rows` op** is what reduces 500 → 290 (Cleaning alone is row-preserving). So the 290-row
    silver = design (b): a `current_loans` derivative that additionally filters to `Current`.

> WHEN the evaluator runs the anchor THE SYSTEM SHALL assert: bronze EXISTS with 500 rows and 86
> columns (78 source matching the CSV by row-count + schema + content hash, excluding the audit
> columns from the byte-diff); silver EXISTS with **290 rows and 78 business columns** (the
> `filter-rows(loan_status="Current")` output of Cleaning), audit columns carried through and
> normalized out of the byte-diff. `[report]` ANCHOR-PIPELINE-SPEC.md:31-43.

**Deterministic re-run / diff method:**
> THE evaluator SHALL run the Builder-generated job 2–3 times against the same input, normalize the
> audit columns out, and diff the business data byte-for-byte vs the reference oracle; identical
> across runs AND vs reference ⇒ PASS, any byte divergence ⇒ FAIL. `[report]` ADR 0009.

---

## OPEN WORKLIST (gate findings — RESOLVED / flagged-residual as of 2026-06-15)

> These are the SPEC-GATE findings that belong to **#2 (codegen / compiler)**. As of the
> 2026-06-15 locked-decision pass, each is a **RESOLVED** contract (in-spec) or a **flagged
> residual** (`> GUESS:` / PREREQUISITE / DESIGN-OPEN — for the re-gate). Findings carried by #1 are
> in that file's OPEN WORKLIST; findings that span both are noted in BOTH.

### Contradictions
- **C-1 — RESOLVED (locked: bronze = 78 source + 8 audit = 86).** The 8 audit columns are
  `_pulse_ingested_at, _pulse_processing_ts, _pulse_pipeline, _pulse_task, _pulse_run_id,
  _pulse_source_uri, _pulse_business_date, _pulse_dag_id`. The phantom `created_as_timestamp` is
  **dropped** (= `_pulse_ingested_at`); the NEW `_pulse_dag_id` (live `{{ dag.dag_id }}`) is added;
  `_pulse_task` becomes live `{{ task.task_id }}`. The `IngestionAuditColumns` change is specified in
  §C (NAMES 7→8, `_pulse_task` live, new `_pulse_dag_id`, `emitPyspark` updated); the schema-effect
  side in #1 §B rule 25; the anchor key fixed in ANCHOR-PIPELINE-SPEC.md.
- **C-2 — RESOLVED (locked: DPC = Hive + Parquet).** Delta is the format for **neither** Mode (it was
  the Builder's old Mode-blind default). The ADR 0007 "Considered" text that called Delta "the DPC
  format" is **corrected** in `docs/adr/0007-*.md`. §C.2 carries the per-Mode format table.
- **C-3 / G-14 — RESOLVED (ADR-text fix applied).** ADR 0011 §Decision item 2's "bounded repair
  regeneration" wording is annotated **SUPERSEDED-by-ADR-0013** in `docs/adr/0011-*.md`. No LLM in
  codegen ⇒ no body to repair ⇒ no repair loop; a mismatch fails loudly with no repair step. §C
  here and #1 §B are now consistent with the ADRs.

### Guesses / prerequisites
- **G-7 — RESOLVED-AS-PREREQUISITE (§C.4).** Named build prerequisite `CALCITE-PHASE-2` (does not
  exist in repo today). `sql-model` uses the **declare-schema path** until it is built; the
  Calcite-validate-against-input branch is gated on it. Flagged as a hard prerequisite, not an apply.
- **G-13 — RESOLVED-AS-FLAGGED (§C.2).** The DPC/Livy/Hive-Parquet emission path "remains to be
  built" (ADR 0006 §Scope); only `SparkSubmitOperator` exists in `src/main`
  (`CodeGenerationService.java:585`). The format (C-2) is settled; the DPC build is the open work —
  this spec contracts the target and flags it as not-yet-in-`src/main`. Distinct from C-2.
- **G-C2 — RESOLVED (operator-decided 2026-06-15; §C.3).** The per-env config slice is selected by
  the env var **`PULSE_ENV` ∈ `{dev | integration | uat | prod}`** naming the file
  **`config/<env>.yaml`**, loaded at job start; generated code reads per-env values from that slice,
  none baked in. Env-var name and file layout are pinned as the selection mechanism.
- **G-C4 — RESOLVED (operator-decided 2026-06-15; §C.7).** The canonical Airflow-Dataset URI format
  is **`pulse://<tenant>/<domain>/<dataset>`**, generated by PULSE from the registered dataset the
  Customer selected (never Customer-authored); producer `outlets` and consumer `schedule` reference
  the same string. Pinned as THE URI scheme.
- **G-11 — RESOLVED (§E): silver business-column count = 78** (NOT 87 — the task framing's "87" is
  ungrounded; `current_loans` derivative tracks `column_count:78`). Silver carries the 8 bronze
  audit columns through (full physical = 86); the byte-exact diff is on the 78 business columns.
- **G-12 — RESOLVED (§E): the anchor silver op-list = Cleaning's ops + a `filter-rows(loan_status=
  "Current")` op** (design (b)). The `filter-rows` op reduces 500 → 290; Cleaning alone is
  row-preserving. Pinned in §E and ANCHOR-PIPELINE-SPEC.md.
- **G-D1 — RESOLVED (verified 2026-06-15; §D).** All 4 deprecation-target keys currently exist as
  **ACTIVE** rows, so V153 deprecating them is a sound contract: `BackfillAndReplay` `[read]` V7:375
  (active; pipeline_config V9:79; treated active V81:559), `CostMonitoringHook` `[read]` V7:439
  (active; V9:79; V81:559), `ObjectStoreKeySensor` `[read]` V75:60-84 (explicit `'active'` V75:84),
  `DatasetDependencySensor` `[read]` V75:113-128 (explicit `'active'`). None appears in V81's
  deprecation block (V81:19-40) or V112's `add_surface='none'` overrides (V112:39-41) — V153 is the
  first to deprecate them. V153 still guards each UPDATE idempotently per V81's convention `[read]`
  V81:11-13. No key is missing or already-deprecated, so nothing needed re-flagging.
- **G-D2 — DEFERRED-MECHANICAL (§D).** The 39 per-Blueprint op-list JSONs (config param-refs +
  `ui_label`s) are mechanical drafting deferred to V153 authoring (producer ≠ verifier). The
  DECOMPOSITION is locked (OP-VOCAB doc); the SHAPE is now pinned (#1 §A.1 G-1) and the Cleaning
  param keys pinned (#1 §A.2 G-A2), so this is now unblocked mechanical drafting, not a design gap.

### Completeness gaps
- **GAP1 — RESOLVED (§D).** SnapshotModel's `artifact_types` is corrected to **`incremental`** (NOT
  `dbt_snapshot`) in V153, matching its `take-periodic-snapshot` → dbt-incremental emission.
- **GAP2 — RESOLVED (§C.1).** Emission handler rows added for `sample-limit` (MUST emit — its own
  atomic Blueprint) and `union-all` / `distinct-union` / `sort` (kept for vocabulary completeness).
  The handlers themselves are build tasks; the rows + their dbt-SQL/PySpark fragments are pinned.
- **GAP3 — RESOLVED (§C.2).** Mode-aware emission is now specified per-engine for **all five**
  engines (PySpark / dbt / GX / dbt-snapshot / DAG-Airflow) in the §C.2 Mode×engine table, not just
  PySpark. (The DPC halves inherit G-13's "remains-to-be-built" flag.)
- **GAP4 — RESOLVED (operator-decided 2026-06-15; spans #1 + #2; §C.8).** CDCIngestion reads a source
  that is **already a CDC log** (change data captured upstream), so the existing `read-source`
  handler is correct and emits the **same generic Ingestion shape with NO new op** — it reads the
  CDC log's columns (including the source's insert/update/delete change-type/op columns) through
  as-is; PULSE does not emit change-capture columns/logic and there is no CDC-variant handler. The
  "plain JDBC read with no change-column" observation was a **MISREAD**. Two refinements: the
  `read-source` connector MUST support **both JDBC and Kafka** transport, and CDCIngestion's
  `read-source` expects a CDC-log source (documented precondition; change columns are SOURCE
  columns). Pinned in §C.8; op-coverage side in #1 §B.4.
- **Control-ops emission split per-op — RESOLVED (§C.1).** The five control ops
  (`sense`/`schedule-and-triggers`/`rollback`/`advance-time`/`invoke-remote`) are split per-op in
  §C.1, each mapped to its own distinct Airflow element/operator/sensor needing its own tested
  handler (sense → Airflow sensor; schedule-and-triggers → schedule/`TriggerDagRunOperator`;
  rollback → failure callback / rollback task per ADR 0020; advance-time → time-advance task per
  ADR 0023; invoke-remote → `RemotePipelineInvocation` operator per ADR 0021). The per-op handlers
  themselves are build tasks.
