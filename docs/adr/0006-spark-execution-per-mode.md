# Spark execution is Mode-specific: Dataproc Serverless on GCP, Apache Livy on DPC

Status: accepted

## Context

PULSE runs in exactly one Mode per installation (ADR 0001). The generated Airflow
DAG must hand the Spark job to the runtime, and the two substrates do that
differently:

- **GCP mode:** the DAG (running on Composer) submits the Spark job as a
  **Dataproc Serverless batch** (`DataprocCreateBatchOperator`) — fully ephemeral,
  no persistent cluster, billed only while running.
- **DPC mode (Cloudera, on-prem):** there is no Dataproc. The Spark connection is
  made via **Apache Livy** (a REST gateway to the Cloudera Spark cluster); the DAG
  submits the job through Livy.

Today the Builder is Mode-blind and emits a plain `SparkSubmitOperator`
(`conn_id='spark_default'`) for both — which matches neither real target
(`CodeGenerationService.java:585`).

## Decision

The Builder generates **Mode-specific** Spark execution in the DAG, branching on
the active runtime persona (`RuntimeAuthorityService`):

- `GCP_PULSE` → **`DataprocCreateBatchOperator`** (Dataproc Serverless).
- `DPC_PULSE` → **Apache Livy** (Livy batch submit against the Cloudera Spark
  cluster).

Plain `SparkSubmitOperator` is the target for neither Mode.

## Scope today vs tracked

Today's session builds and proves the **GCP / Dataproc-Serverless** path only. The
**DPC / Apache-Livy** path is recorded here and **remains to be built** (tracked in
`docs/PULSE-MAP.md`); the Builder's DPC branch must move from `SparkSubmitOperator`
to Livy and must not be left as-is.

## Considered

- Persistent Dataproc cluster on GCP — rejected: cost + lifecycle management vs
  serverless.
- `SparkSubmitOperator` everywhere — rejected: assumes a static `spark_default`
  connection that neither real target provides.

## Trade-off

Two execution code paths in the Builder. Accepted — it is the same Mode split
ADR 0001 already commits to, kept in one place (the DAG generator) rather than
leaking across components.

> Wording note (2026-06-13): the operator assigned Dataproc Serverless → GCP and
> Apache Livy → DPC/Cloudera. A later sentence said "GCP will use Apache Livy,"
> read here as a slip for DPC. If that reading is wrong, this ADR flips.
