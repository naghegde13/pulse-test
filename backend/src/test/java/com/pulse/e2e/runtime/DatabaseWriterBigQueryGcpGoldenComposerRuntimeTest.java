package com.pulse.e2e.runtime;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseWriterBigQueryGcpGoldenComposerRuntimeTest {

    private static final String RUN_ID = "run-20260506-1225";
    private static final String NAMESPACE = "tenants/home-lending/pipelines/loan-master";
    private static final Path RUNTIME_ROOT = Path.of("build/e2e-airflow-runtime");
    private static final Path EVIDENCE_ROOT = Path.of("build/e2e-semantic-hardening/gcp-golden")
            .resolve("database-writer")
            .resolve(RUN_ID)
            .resolve("deploy");

    @Test
    void materializeDatabaseWriterBigQueryGcpGoldenComposerDag() throws Exception {
        Path namespacedRoot = RUNTIME_ROOT.resolve(NAMESPACE);
        Path dag = namespacedRoot.resolve("dags/database_writer_bigquery_gcp_dag.py");
        Files.createDirectories(dag.getParent());
        Files.writeString(dag, dagSource());

        Files.createDirectories(EVIDENCE_ROOT.resolve("runtime/composer-bigquery"));
        Files.writeString(EVIDENCE_ROOT.resolve("runtime/composer-bigquery/database_writer_bigquery_gcp_dag.py"), dagSource());
        Files.writeString(EVIDENCE_ROOT.resolve("gcp-composer-bigquery-writer-plan.json"), """
                {
                  "scenarioId": "database-writer-gcp-golden-bigquery",
                  "generationRunId": "run-20260506-1225",
                  "composerDagId": "pulse_semantic_gcp_database_writer_bigquery",
                  "composerDagRunId": "pulse_semantic__database_writer_bigquery_run_20260506_1225",
                  "canonicalGcpDestination": "bigquery",
                  "dataset": "pulse_semantic_database_writer",
                  "table": "billing_invoices_run_20260506_1225",
                  "probeTable": "billing_invoices_probe_run_20260506_1225"
                }
                """);

        String renderedDag = Files.readString(dag);
        assertTrue(renderedDag.contains("BigQueryInsertJobOperator"));
        assertTrue(renderedDag.contains("pulse_semantic_gcp_database_writer_bigquery"));
        assertTrue(renderedDag.contains("billing_invoices_run_20260506_1225"));
        assertTrue(renderedDag.contains("billing_invoices_probe_run_20260506_1225"));
        assertFalse(renderedDag.contains("DataprocCreateBatchOperator"));
        assertFalse(renderedDag.contains("BashOperator"));
    }

    private String dagSource() {
        return """
                from __future__ import annotations

                import pendulum
                from airflow import DAG
                from airflow.operators.empty import EmptyOperator
                from airflow.providers.google.cloud.operators.bigquery import BigQueryInsertJobOperator

                DAG_ID = "pulse_semantic_gcp_database_writer_bigquery"
                PROJECT_ID = "pulse-proof-04261847"
                LOCATION = "US"
                DATASET = "pulse_semantic_database_writer"
                TABLE = "billing_invoices_run_20260506_1225"
                PROBE_TABLE = "billing_invoices_probe_run_20260506_1225"

                SQL = '''
                CREATE OR REPLACE TABLE `pulse-proof-04261847.pulse_semantic_database_writer.billing_invoices_run_20260506_1225` AS
                WITH source AS (
                  SELECT 'INV1' AS invoice_id, 'ACC1' AS account_id, DATE '2026-05-01' AS invoice_date, NUMERIC '100.00' AS amount, 'open' AS status, TIMESTAMP '2026-05-01 10:00:00+00' AS updated_at, 1 AS op_seq UNION ALL
                  SELECT 'INV2', 'ACC2', DATE '2026-05-02', NUMERIC '80.00', 'open', TIMESTAMP '2026-05-02 11:00:00+00', 2 UNION ALL
                  SELECT 'INV1', 'ACC1', DATE '2026-05-03', NUMERIC '125.00', 'paid', TIMESTAMP '2026-05-03 12:00:00+00', 3 UNION ALL
                  SELECT NULL, 'ACC3', DATE '2026-05-04', NUMERIC '25.00', 'open', TIMESTAMP '2026-05-04 09:00:00+00', 4 UNION ALL
                  SELECT 'INV2', 'ACC2', DATE '2026-05-02', NUMERIC '80.00', 'open', TIMESTAMP '2026-05-02 11:00:00+00', 5
                ),
                valid AS (
                  SELECT * FROM source WHERE invoice_id IS NOT NULL
                ),
                ranked AS (
                  SELECT *,
                         ROW_NUMBER() OVER (PARTITION BY invoice_id ORDER BY updated_at DESC, op_seq DESC) AS rn
                  FROM valid
                )
                SELECT invoice_id, account_id, invoice_date, amount, status, updated_at
                FROM ranked
                WHERE rn = 1;

                CREATE OR REPLACE TABLE `pulse-proof-04261847.pulse_semantic_database_writer.billing_invoices_probe_run_20260506_1225` AS
                WITH source AS (
                  SELECT 'INV1' AS invoice_id, 'ACC1' AS account_id, DATE '2026-05-01' AS invoice_date, NUMERIC '100.00' AS amount, 'open' AS status, TIMESTAMP '2026-05-01 10:00:00+00' AS updated_at, 1 AS op_seq UNION ALL
                  SELECT 'INV2', 'ACC2', DATE '2026-05-02', NUMERIC '80.00', 'open', TIMESTAMP '2026-05-02 11:00:00+00', 2 UNION ALL
                  SELECT 'INV1', 'ACC1', DATE '2026-05-03', NUMERIC '125.00', 'paid', TIMESTAMP '2026-05-03 12:00:00+00', 3 UNION ALL
                  SELECT NULL, 'ACC3', DATE '2026-05-04', NUMERIC '25.00', 'open', TIMESTAMP '2026-05-04 09:00:00+00', 4 UNION ALL
                  SELECT 'INV2', 'ACC2', DATE '2026-05-02', NUMERIC '80.00', 'open', TIMESTAMP '2026-05-02 11:00:00+00', 5
                ),
                grouped AS (
                  SELECT invoice_id, COUNT(*) AS row_count, COUNT(DISTINCT TO_JSON_STRING(STRUCT(account_id, invoice_date, amount, status, updated_at))) AS distinct_versions
                  FROM source
                  WHERE invoice_id IS NOT NULL
                  GROUP BY invoice_id
                )
                SELECT
                  'DatabaseWriter' AS blueprintKey,
                  'bigquery' AS canonicalGcpDestination,
                  (SELECT COUNT(*) FROM source) AS sourceRows,
                  (SELECT COUNT(*) FROM source WHERE invoice_id IS NOT NULL) AS acceptedRows,
                  (SELECT COUNT(*) FROM `pulse-proof-04261847.pulse_semantic_database_writer.billing_invoices_run_20260506_1225`) AS finalRows,
                  (SELECT COUNT(*) FROM source WHERE invoice_id IS NULL) AS nullRejectedRows,
                  (SELECT ARRAY_AGG(invoice_id ORDER BY invoice_id) FROM grouped WHERE row_count > 1) AS duplicateInvoiceIds,
                  (SELECT ARRAY_AGG(invoice_id ORDER BY invoice_id) FROM grouped WHERE distinct_versions > 1) AS upsertedInvoiceIds,
                  'atomic_create_or_replace' AS transactionBehavior,
                  'PASS' AS verdict;
                '''

                with DAG(
                    dag_id=DAG_ID,
                    start_date=pendulum.datetime(2026, 1, 1, tz="UTC"),
                    schedule=None,
                    catchup=False,
                    max_active_runs=1,
                    tags=["pulse", "semantic-proof", "gcp-composer-bigquery"],
                ) as dag:
                    write_billing_invoices = BigQueryInsertJobOperator(
                        task_id="write_billing_invoices",
                        project_id=PROJECT_ID,
                        location=LOCATION,
                        configuration={
                            "query": {
                                "query": SQL,
                                "useLegacySql": False,
                            },
                            "labels": {
                                "scenario": "database-writer-gcp-golden-bigquery",
                                "generation_run": "run-20260506-1225",
                            },
                        },
                    )

                    done = EmptyOperator(task_id="done")

                    write_billing_invoices >> done
                """;
    }
}
