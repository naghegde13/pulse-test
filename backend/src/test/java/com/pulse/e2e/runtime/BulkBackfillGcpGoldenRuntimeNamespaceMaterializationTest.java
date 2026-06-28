package com.pulse.e2e.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BulkBackfillGcpGoldenRuntimeNamespaceMaterializationTest {

    private static final String RUN_ID = "run-20260506-0555";
    private static final String NAMESPACE = "tenants/home-lending/pipelines/loan-master";
    private static final String INPUT_ROOT = "gs://pulse-home-lending-dev-files/semantic/bulk-backfill/input";
    private static final String CONFIG_URI = INPUT_ROOT + "/expectations/backfill-config.json";
    private static final String OUTPUT_PREFIX = "gs://pulse-home-lending-dev-lake/semantic/bulk-backfill/" + RUN_ID;
    private static final String STAGING_BUCKET = "gs://pulse-proof-04261847-dataproc-staging";
    private static final String DATAPROC_RUNTIME_SERVICE_ACCOUNT = "pulse-proof-dataproc-runtime@pulse-proof-04261847.iam.gserviceaccount.com";
    private static final Path RUNTIME_ROOT = Path.of("build/e2e-airflow-runtime");
    private static final Path EVIDENCE_ROOT = Path.of("build/e2e-semantic-hardening/gcp-golden")
            .resolve("bulk-backfill")
            .resolve(RUN_ID)
            .resolve("deploy");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void materializeBulkBackfillGcpGoldenRuntimeNamespaceTree() throws Exception {
        Path namespacedRoot = RUNTIME_ROOT.resolve(NAMESPACE);
        Path mainPython = namespacedRoot.resolve("jobs/backfill/bulk_backfill.py");
        Path secretResolver = namespacedRoot.resolve("runtime/pulse_secret_resolver.py");
        Path dag = namespacedRoot.resolve("dags/bulk_backfill_gcp_dag.py");

        Files.createDirectories(mainPython.getParent());
        Files.writeString(mainPython, bulkBackfillJob());
        Files.createDirectories(secretResolver.getParent());
        Files.writeString(secretResolver, secretResolverModule());
        Files.createDirectories(dag.getParent());

        GcpComposerDataprocBridgeAdapter adapter = new GcpComposerDataprocBridgeAdapter(objectMapper);
        GcpComposerDataprocBridgeAdapter.WrittenAdapterPlan written = adapter.writeEvidence(
                request(),
                EVIDENCE_ROOT
        );

        assertTrue(Files.exists(mainPython), "main BulkBackfill PySpark job must exist");
        assertTrue(Files.exists(secretResolver), "runtime secret resolver helper must exist");
        assertTrue(Files.exists(dag), "Composer DAG must exist in the runtime namespace");

        String job = Files.readString(mainPython);
        assertTrue(job.contains("duplicateMonthRejected"));
        assertTrue(job.contains("missingMonths"));
        assertTrue(job.contains("partitioned-readings"));
        assertTrue(job.contains("backfill-report"));

        String renderedDag = Files.readString(dag);
        assertTrue(renderedDag.contains("DataprocCreateBatchOperator"));
        assertTrue(renderedDag.contains("pulse_semantic_gcp_bulk_backfill"));
        assertTrue(renderedDag.contains("semantic-bulk-backfill-batch-20260506-0555"));
        assertFalse(renderedDag.contains("BashOperator"));

        assertTrue(Files.exists(namespacedRoot.resolve("runtime/dataproc-submit-request.json")));
        assertTrue(Files.exists(written.adapterPlan()));
        assertTrue(Files.exists(written.composerEvidence()));
        assertTrue(Files.exists(written.dataprocEvidence()));
        assertTrue(Files.exists(written.dataprocSubmitRequest()));
        assertTrue(Files.exists(written.renderedDag()));

        JsonNode submit = objectMapper.readTree(namespacedRoot.resolve("runtime/dataproc-submit-request.json").toFile());
        assertEquals("bulk-backfill-gcp-golden", submit.get("scenarioId").asText());
        assertEquals(RUN_ID, submit.get("generationRunId").asText());
        assertEquals("pulse-proof-04261847", submit.get("projectId").asText());
        assertEquals("semantic-bulk-backfill-batch-20260506-0555", submit.get("batchId").asText());
        assertEquals(
                STAGING_BUCKET + "/pulse-runtime/bulk-backfill-gcp-golden/" + RUN_ID + "/"
                        + NAMESPACE + "/jobs/backfill/bulk_backfill.py",
                submit.at("/batch/pyspark_batch/main_python_file_uri").asText()
        );
    }

    private GcpComposerDataprocBridgeAdapter.AdapterRequest request() {
        return new GcpComposerDataprocBridgeAdapter.AdapterRequest(
                "bulk-backfill-gcp-golden",
                RUN_ID,
                "pulse-proof-04261847",
                "us-central1",
                "pulse-proof-composer",
                "gs://us-central1-pulse-proof-com-a7066110-bucket/dags",
                "us-central1",
                DATAPROC_RUNTIME_SERVICE_ACCOUNT,
                STAGING_BUCKET,
                OUTPUT_PREFIX,
                NAMESPACE,
                "pulse_semantic_gcp_bulk_backfill",
                "bulk_backfill_gcp_dag.py",
                "pulse_semantic__bulk_backfill_run_20260506_0555",
                "2026-05-06T05:55:00+00:00",
                "semantic-bulk-backfill-batch-20260506-0555",
                "jobs/backfill/bulk_backfill.py",
                List.of("runtime/pulse_secret_resolver.py"),
                List.of(
                        "--jan-input", INPUT_ROOT + "/positive/meter_readings_2024_01.csv",
                        "--feb-input", INPUT_ROOT + "/positive/meter_readings_2024_02.csv",
                        "--mar-input", INPUT_ROOT + "/positive/meter_readings_2024_03.csv",
                        "--duplicate-feb-input", INPUT_ROOT + "/negative/meter_readings_2024_02_duplicate.csv",
                        "--missing-mar-input", INPUT_ROOT + "/edge/meter_readings_missing_2024_03.csv",
                        "--config", CONFIG_URI,
                        "--output", OUTPUT_PREFIX
                ),
                "GCLOUD_SUBPROCESS",
                "GCLOUD_COMPOSER_RUN",
                "aamer@aamer.net",
                RUNTIME_ROOT
        );
    }

    private String bulkBackfillJob() {
        return """
                from __future__ import annotations

                import argparse
                import json
                from functools import reduce
                from pyspark.sql import SparkSession
                from pyspark.sql import functions as F


                def parse_args():
                    parser = argparse.ArgumentParser(description="BulkBackfill semantic GCP golden proof job")
                    parser.add_argument("--jan-input", required=True)
                    parser.add_argument("--feb-input", required=True)
                    parser.add_argument("--mar-input", required=True)
                    parser.add_argument("--duplicate-feb-input", required=True)
                    parser.add_argument("--missing-mar-input", required=True)
                    parser.add_argument("--config", required=True)
                    parser.add_argument("--output", required=True)
                    return parser.parse_args()


                def read_json_config(spark, uri):
                    return json.loads("\\n".join(row["value"] for row in spark.read.text(uri).collect()))


                def main():
                    args = parse_args()
                    spark = SparkSession.builder.appName("pulse-bulk-backfill-gcp-golden").getOrCreate()
                    try:
                        config = read_json_config(spark, args.config)
                        required_columns = config["requiredColumns"]
                        expected_months = config["expectedMonths"]
                        monthly_frames = [
                            spark.read.option("header", "true").csv(args.jan_input),
                            spark.read.option("header", "true").csv(args.feb_input),
                            spark.read.option("header", "true").csv(args.mar_input),
                        ]
                        readings = reduce(lambda left, right: left.unionByName(right), monthly_frames)
                        readings = readings.select(*required_columns).orderBy("batch_month", "meter_id")
                        readings.coalesce(1).write.mode("overwrite").option("header", "true").csv(
                            args.output + "/partitioned-readings"
                        )

                        duplicate_feb = spark.read.option("header", "true").csv(args.duplicate_feb_input)
                        duplicate_count = duplicate_feb.count() - duplicate_feb.dropDuplicates(
                            ["meter_id", "read_date", "batch_month"]
                        ).count()
                        missing_mar = spark.read.option("header", "true").csv(args.missing_mar_input)
                        present_edge_months = {
                            row["batch_month"] for row in missing_mar.select("batch_month").distinct().collect()
                        }
                        missing_months = [month for month in expected_months if month not in present_edge_months]
                        partition_months = [
                            row["batch_month"]
                            for row in readings.select("batch_month").distinct().orderBy("batch_month").collect()
                        ]

                        report = {
                            "blueprintKey": "BulkBackfill",
                            "rowCount": readings.count(),
                            "partitionMonths": partition_months,
                            "duplicateMonthRejected": duplicate_count > 0,
                            "missingMonths": missing_months,
                            "verdict": "draft-pass",
                        }
                        metrics = {
                            "positiveRowCount": readings.count(),
                            "partitionCount": len(partition_months),
                            "duplicateRowCount": duplicate_count,
                            "missingMonthCount": len(missing_months),
                        }
                        spark.sparkContext.parallelize([json.dumps(report, sort_keys=True)]).saveAsTextFile(
                            args.output + "/backfill-report"
                        )
                        spark.sparkContext.parallelize([json.dumps(metrics, sort_keys=True)]).saveAsTextFile(
                            args.output + "/backfill-metrics"
                        )
                    finally:
                        spark.stop()


                if __name__ == "__main__":
                    main()
                """;
    }

    private String secretResolverModule() {
        return """
                from __future__ import annotations


                def resolve_runtime_secret_env(secret_bindings=None):
                    return {}, []


                def cleanup_runtime_secret_files(runtime_files=None):
                    return None
                """;
    }
}
