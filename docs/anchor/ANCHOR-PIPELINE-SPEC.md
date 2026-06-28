# Anchor Pipeline & Behavioral-Test Format

> Status: **LOCKED — the 3 §5 decisions are resolved** (bronze = Iceberg-on-GCS interim → BQ-managed target per ADR 0007; silver = Bronze-to-Silver Cleaning; run target = true GCP, not local-only — see PULSE-MAP Done log 2026-06-13). Anchor tenant = `tenant-home-lending` (`CanonicalLoanMasterAirflowRuntimeIT:58`). This is the one
> concrete pipeline + sample data that BOTH tracks build and test against
> (ADR 0004). Track-1's win is Leg 1; Track-2 extends it to Leg 2.

## 1. Verified facts (checked first-hand this session)

| Fact | Evidence |
|---|---|
| Sample data: `data/loan_master.csv`, **500 rows × 78 cols** | `[read]` + counted |
| Its answer key is correct — **242/242** checks vs the real CSV | `[run]` python verify |
| **File Ingestion's real params (V93)**: no `source_path`/`file_format` (removed V62); location **inherits from a connector**; the file is found by **`filename_pattern`** (e.g. `loan_master_{date}.csv` template, or glob `loan_master.csv`); plus date-mnemonic/partition/size-guard knobs | `[read]` `V93:135-212` |
| **Source connector already exists**: "S3-compatible Object Storage (Source)" — resolves bucket/path/auth **from the `storage_backend` row** (= our GCS bucket via G0). GCS is handled by this S3-compatible row. | `[read]` `V99:115-124` |
| **Paths**: files at `gs://{files_root}/{domain}/{sor}/{pipeline}/{lifecycle}/`; bronze table at `gs://{lake_root}/{domain}/{sor}/{pipeline}/bronze/{table}/` | `[read]` `PathConventionService` |
| **Deploy walls (G2)**: ALL THREE production GCP clients **throw** — GCS upload (`DefaultGcsPackageDeliveryClient:27`), Composer sync (`DefaultComposerDagSyncClient:18,29`), Dataproc submit (`DefaultDataprocSubmitClient:18,28`) | `[read]` |
| **Builder today**: not LLM-wired (ADR 0002); hardcodes `USING DELTA`; emits **no** Dataproc submit | `[read]` + ADR 0002 |

## 2. The anchor pipeline

```
Leg 1 (Track 1, today):
   S3-compatible Object Storage connector (→ our GCS bucket via storage_backend)
        → File Ingestion (filename_pattern = loan_master.csv)
        → bronze table  loan_master_bronze   (500 rows × 86 cols = 78 source + 8 PULSE audit cols)

Leg 2 (Track 2, next):
   bronze  → Bronze-to-Silver Cleaning → filter-rows(loan_status="Current")
        → silver table  (290 rows × 78 business cols + 8 bronze audit cols carried through)
```

> Silver op-list (anchor): the Cleaning ops (`transform-values` → `rename-columns` →
> `change-types` → `drop-columns` → `deduplicate`, all passthrough when unconfigured)
> **followed by** `filter-rows(loan_status="Current")`. The filter is what reduces 500 → 290
> (Cleaning alone is row-preserving). Pinned in SPEC-codegen-compiler.md §E.

## 3. Behavioral test (fresh harness — ADR 0004, NOT the quarantined suite)

- **Input:** `loan_master.csv` placed at the connector's resolved GCS files-path.
- **Process under test:** the **real Builder** (`CodeGenerationService`) generates the
  File Ingestion PySpark job + the Airflow DAG from this composition; the generated
  job is **actually run** (where = Decision 3).
- **Expected output (answer key):** a bronze table that **exists** and contains
  **500 rows, 86 columns** (78 source columns, unchanged data + **8 PULSE audit
  columns**: `_pulse_ingested_at`, `_pulse_processing_ts`, `_pulse_pipeline`,
  `_pulse_task`, `_pulse_run_id`, `_pulse_source_uri`, `_pulse_business_date`,
  `_pulse_dag_id`); the 78 source columns match the CSV (row count +
  schema + content hash). Count stays **86** (78 + 8).
  > **Audit-column correction (2026-06-15, locked operator decision — resolves SPEC-#2
  > finding C-1):** the answer key previously listed `created_as_timestamp` as the 8th
  > audit column. That is **dropped** — it equalled `_pulse_ingested_at` (a true
  > source-creation time lives in the source data, not in audit) — and replaced by the
  > NEW **`_pulse_dag_id`** (= live Airflow `{{ dag.dag_id }}`). The 8 audit columns are
  > now: `_pulse_ingested_at`, `_pulse_processing_ts`, `_pulse_pipeline`, `_pulse_task`,
  > `_pulse_run_id`, `_pulse_source_uri`, `_pulse_business_date`, `_pulse_dag_id`.
  > `_pulse_task` is now the **live** Airflow `{{ task.task_id }}` (not the baked task
  > slug), and `_pulse_run_id` / `_pulse_dag_id` are likewise live Airflow templates. All
  > runtime-only audit columns are normalized out of the byte-exact business-data diff
  > (ADR 0009). The `IngestionAuditColumns` source-of-truth change is specified in
  > SPEC-codegen-compiler.md §C; the `add-audit-columns` schema-effect rule in
  > SPEC-schema-op-engine.md §B rule 25.

  Leg-2 cleaning uses the verified `loan_master` manifest derivatives as its answer key:
  silver = Bronze-to-Silver Cleaning **+ a `filter-rows(loan_status="Current")` step** =
  **290 rows** (the `current_loans` derivative), **78 business columns** + the 8 bronze
  audit columns carried through (silver inherits bronze's audit columns; it does not
  re-add or drop them). The 290-row / column-count answer key is pinned in
  SPEC-codegen-compiler.md §E.
- **Roles (separated, per ADR 0004):** spec author = us; **blind implementer** =
  the real Builder + an agent that wires the run, never shown the answer key;
  **independent evaluator** = a separate agent that runs the job and diffs the
  output table against the answer key, returns pass/fail + evidence.

## 4. What each track must build (once anchor is locked)

- **G1 (Builder, GCP-correct):** stop hardcoding `USING DELTA`; emit the bronze
  table in the chosen format (Decision 1) + a DAG that submits a **Dataproc
  Serverless batch** (`DataprocCreateBatchOperator`).
- **G2 (Deployer):** implement the 3 throwing GCP clients — GCS package upload,
  Composer DAG-sync + run-poll, (Dataproc submit happens *inside* the generated
  DAG, so the deploy client mainly needs GCS upload + Composer sync).
- **S1/S3 (smart Builder):** Leg 2 — deterministic schema for the cleaning
  transform + LLM-written dbt SQL body.

## 5. Decisions needed from the operator (3)

1. **Bronze table FORMAT.**
   - (a) **Iceberg table on GCS via Spark** — *recommended*: honest to GCP-mode's
     Iceberg story, demoable today, defers standing up BigQuery.
   - (b) **BigQuery-managed Iceberg** — most faithful to the documented end-state,
     but heavier (BigLake/BQ catalog wiring on Dataproc) — higher risk today.
   - (c) **Parquet files on GCS** — simplest, but it's files, not a managed
     table; weakest as a demo of the real architecture.
2. **Silver transform = Bronze-to-Silver Cleaning?** The research draft marks it
   INTENT: CLEAR with a PLAUSIBLE answer key. *Recommended: yes* (and it's the
   tentatively-frozen one Session B was told not to touch).
3. **Where does the FIRST real run happen?**
   - (a) **Local Spark first** (no GCP, fast) to prove the Builder produces a
     correct, runnable job today — *then* layer the GCP deploy. *Recommended.*
   - (b) **Straight to real GCP** — requires building the 3 throwing GCP clients
     first; bigger, slower to a first verified result.

## 6. Honest scope note (so we pick a reachable first win)

A full "deploy to real GCP and run" today is a **real build**, not a config flip:
3 production clients throw + the Builder isn't GCP-correct yet. The **reachable
verified win today** is: *the real Builder generates a File Ingestion job that
actually runs and produces the bronze table (locally first), checked against the
answer key* — then the GCP deploy layers on top. That gives a witnessed win today
and de-risks the GCP work, instead of a half-finished GCP push.
