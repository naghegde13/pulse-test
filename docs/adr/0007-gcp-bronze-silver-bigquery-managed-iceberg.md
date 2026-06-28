# GCP bronze/silver tables are BigQuery-managed Iceberg (target); Iceberg-on-GCS is a tracked interim

Status: accepted

## Context

PULSE GCP mode is a **lakehouse**: bronze + silver are meant to be
**BigQuery-managed Iceberg** tables, gold is native BigQuery (see `CONTEXT.md`;
`RuntimeAuthorityService` GCP_PULSE preset maps bronze/silver → `iceberg_bq_managed`).
The Builder today hardcodes **Delta** for bronze regardless of Mode
(`CodeGenerationService.java:1128`/`:1141`). Standing up BigQuery-managed Iceberg
(BigLake metastore / BigQuery Iceberg catalog wiring on Dataproc) is meaningfully
more setup than a plain Spark-written Iceberg table on GCS.

## Decision

The documented **TARGET** for GCP bronze/silver is **BigQuery-managed Iceberg**.
For the first end-to-end GCP run we ship an **INTERIM**: a Spark-written
**Iceberg table on GCS** (Hadoop/GCS catalog) — honest to the Iceberg format and
demoable, but explicitly **not** the final target.

The interim is a stepping stone, not a destination. The
**BigQuery-managed-Iceberg work is tracked as an open lane** in
`docs/PULSE-MAP.md` and **must be completed** — it does not get silently parked.
(Operator-mandated 2026-06-13: "in GCP we are supposed to use BigQuery-managed
Iceberg tables… make sure this work doesn't just pile up away somewhere.")

## Considered

- **Ship BigQuery-managed Iceberg directly** — rejected *for the first run only*:
  heavier BigLake/BQ catalog wiring on Dataproc, higher risk of not landing the
  first real GCP run today. It remains the required end-state.
- **Parquet on GCS** — rejected: not even Iceberg; drifts from the lakehouse story.
- **Delta** — rejected for GCP: Delta is wrong for GCP's Iceberg lakehouse. (Note: Delta
  is NOT the DPC format either — DPC = **Hive + Parquet** per ADR 0001. Delta was only the
  Builder's old Mode-blind hardcoded bronze default; it is the format for *neither* Mode.
  Corrected 2026-06-15 per locked operator decision; see SPEC-codegen-compiler.md §C.2.)

## Trade-off

We incur a known, deliberate follow-up (Iceberg-on-GCS → BigQuery-managed Iceberg)
and must not forget it. Accepted to get a real GCP run sooner, **with the target
preserved in writing and tracked so it is finished, not abandoned.**
