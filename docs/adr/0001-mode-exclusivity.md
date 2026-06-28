# PULSE runs in exactly one Mode per installation, never both

Status: accepted

A PULSE installation targets either **GCP mode** (Google's cloud: Composer, Dataproc, GCS, Iceberg tables, BigQuery warehouse — a lakehouse) or **DPC mode** (on-prem Cloudera: plain Airflow, Cloudera Spark, S3-compatible storage, Hive + Parquet tables — a plain lake), and never serves both at once.

The two substrates differ in storage, table format, transaction capability, and orchestrator. Most sharply: DPC has no Iceberg today (and won't for roughly 9–12+ months) and no warehouse, so the Builder must emit genuinely different pipeline code for each Mode — in effect, two builders. Treating Mode as an install-level switch keeps that difference in one place instead of forcing every component to branch at runtime.

Trade-off: a single installation cannot mix GCP and DPC targets. That is accepted, because the substrates are too different (lake vs lakehouse, Hive+Parquet vs Iceberg, plain Airflow vs Composer) for one code path to serve both.
