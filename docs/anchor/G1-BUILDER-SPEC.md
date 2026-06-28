# G1 — Make File Ingestion GCP-correct (Builder) — DRAFT blind-implementer spec

> Status: **WRITTEN + PARKED (branch `g1-builder-gcp-correct`, `36d8c5b`) — NOT a fresh dispatch.** Evaluate per ADR 0008 + the integrated GCP-run scenario before merge (see PULSE-MAP "Branch & Merge State"). Decisions locked (Iceberg-on-GCS interim / real-GCP target).
> When handing to an external implementer (Codex), paste/point at only the
> *Self-contained context + Task + Constraints* sections — **never** the
> *Behavioral test* section (ADR 0004: the implementer must not see the oracle).

## Self-contained context (the implementer has no PULSE background)

PULSE's Builder = `backend/src/main/java/com/pulse/codegen/service/CodeGenerationService.java`.
It generates a PySpark job + an Airflow DAG from a pipeline composition. For a
**File Ingestion** step today (all `[report]` from a survey, verify before editing):

- **Reads** the source file (CSV/Parquet/JSON) from object storage via
  `PathConventionService` — branch at `CodeGenerationService.java:995-1061`. Source
  format resolves from a `file_format`/`source_format` config value, **default
  `parquet`** (`:1047-1048`, normalizer `:4651-4656`).
- **Adds 8 audit columns** (`IngestionAuditColumns.java:36-46` + `:1117`):
  `_pulse_ingested_at`, `_pulse_processing_ts`, `_pulse_pipeline`, `_pulse_task`,
  `_pulse_run_id`, `_pulse_source_uri`, `_pulse_business_date`,
  `created_as_timestamp`.
- **Writes bronze HARDCODED as Delta** — `LakeFormat.DELTA` literal at `:1128`,
  `.format('delta')` at `:1141`, then `CREATE TABLE … USING DELTA LOCATION …`
  (`:1146`). Path write + external table.
- **DAG runs the job via `SparkSubmitOperator`/`PythonOperator`** (`:573-593`) —
  **never** `DataprocCreateBatchOperator`.
- **Mode-blind**: `generatePySparkJobs` does not branch on GCP vs DPC. But the
  Mode-aware rulebook EXISTS: `RuntimeAuthorityService` GCP_PULSE preset maps
  bronze→Iceberg (`:134-150`); `PathConventionService` already emits `gs://` for
  GCP vs `s3a://`/`hdfs://` for DPC (so paths need no change).

## Task — make File Ingestion emit GCP-correct bronze

1. **Mode-aware bronze format.** Replace the hardcoded `LakeFormat.DELTA`
   (`:1128`/`:1141`) with a value chosen from the **active runtime persona**
   (`RuntimeAuthorityService`):
   - `GCP_PULSE` → **`ICEBERG_ON_GCS`** (interim, ADR 0007; implement the format as a *selection* off the persona, not a new hardcode, so `ICEBERG_BQ_MANAGED` can slot in later)
   - `DPC_PULSE` → `DELTA` (unchanged — must not regress).
   Emit the matching Spark write + table-registration syntax for the chosen format.
2. **Mode-aware execution operator.** In the DAG generator (`~:573-593`), when the
   active persona is `GCP_PULSE`, emit a **`DataprocCreateBatchOperator`**
   (serverless) instead of `SparkSubmitOperator`: import it; pass `project_id`
   (from `storage_backend.gcpProject`), `region`, a `batch_id`, and a `batch`
   with `pyspark_batch.main_python_file_uri` pointing at the job staged in GCS,
   plus the runtime service account. DPC path stays `SparkSubmitOperator`.
3. **Source format must be CSV for the anchor.** The anchor reads
   `loan_master.csv`, but the read defaults to `parquet`. Ensure `source_format=csv`
   reaches `mergedConfig` for this step (via connector or instance config) so the
   CSV read path (`appendCsvRead`) is taken — confirm and wire it.

## Constraints
- Do **not** regress the DPC path (still Delta + `SparkSubmitOperator`).
- The post-generation forbidden-token scan (`ForbiddenTokenScanner`) must still pass.
- No secrets in generated code (SecretRefs only).

## Behavioral test (evaluator-only — DO NOT include when dispatching the implementer)
Given `loan_master.csv` at the connector's resolved GCS path + a File Ingestion
composition in **GCP mode**, the **real Builder** generates a job whose run
produces a bronze table with **500 rows and 86 columns** (78 source columns with
unchanged data + the 8 audit columns); the table format is the Decision-1 choice
(no `delta` literal in GCP output); the generated DAG contains
`DataprocCreateBatchOperator`. **Run target = `REAL_GCP`** (G1's proof is the real GCP run; the separate local-Spark milestone needs no G1 change).
Evaluator runs the generated code on sample data and diffs the output table
against this; implementer never sees this section.
