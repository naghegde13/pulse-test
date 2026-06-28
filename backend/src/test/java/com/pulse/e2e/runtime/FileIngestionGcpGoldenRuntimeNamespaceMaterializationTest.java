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

class FileIngestionGcpGoldenRuntimeNamespaceMaterializationTest {

    private static final String RUN_ID = "run-20260506-0540";
    private static final String NAMESPACE = "tenants/home-lending/pipelines/loan-master";
    private static final String VALID_FIXTURE_URI = "gs://pulse-home-lending-dev-files/semantic/file-ingestion/input/positive/orders_valid.csv";
    private static final String EMPTY_FIXTURE_URI = "gs://pulse-home-lending-dev-files/semantic/file-ingestion/input/edge/orders_empty.csv";
    private static final String BAD_HEADER_FIXTURE_URI = "gs://pulse-home-lending-dev-files/semantic/file-ingestion/input/negative/orders_bad_header.csv";
    private static final String CORRUPT_FIXTURE_URI = "gs://pulse-home-lending-dev-files/semantic/file-ingestion/input/negative/orders_corrupt.csv";
    private static final String CONFIG_URI = "gs://pulse-home-lending-dev-files/semantic/file-ingestion/input/expectations/ingestion-config.json";
    private static final String OUTPUT_PREFIX = "gs://pulse-home-lending-dev-lake/semantic/file-ingestion/" + RUN_ID;
    private static final String STAGING_BUCKET = "gs://pulse-proof-04261847-dataproc-staging";
    private static final String DATAPROC_RUNTIME_SERVICE_ACCOUNT = "pulse-proof-dataproc-runtime@pulse-proof-04261847.iam.gserviceaccount.com";
    private static final Path RUNTIME_ROOT = Path.of("build/e2e-airflow-runtime");
    private static final Path EVIDENCE_ROOT = Path.of("build/e2e-semantic-hardening/gcp-golden")
            .resolve("file-ingestion")
            .resolve(RUN_ID)
            .resolve("deploy");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void materializeFileIngestionGcpGoldenRuntimeNamespaceTree() throws Exception {
        Path namespacedRoot = RUNTIME_ROOT.resolve(NAMESPACE);
        Path mainPython = namespacedRoot.resolve("jobs/ingestion/file_ingestion.py");
        Path secretResolver = namespacedRoot.resolve("runtime/pulse_secret_resolver.py");
        Path dag = namespacedRoot.resolve("dags/file_ingestion_gcp_dag.py");

        Files.createDirectories(mainPython.getParent());
        Files.writeString(mainPython, fileIngestionJob());
        Files.createDirectories(secretResolver.getParent());
        Files.writeString(secretResolver, secretResolverModule());
        Files.createDirectories(dag.getParent());

        GcpComposerDataprocBridgeAdapter adapter = new GcpComposerDataprocBridgeAdapter(objectMapper);
        GcpComposerDataprocBridgeAdapter.WrittenAdapterPlan written = adapter.writeEvidence(
                request(),
                EVIDENCE_ROOT
        );

        assertTrue(Files.exists(mainPython), "main FileIngestion PySpark job must exist");
        assertTrue(Files.exists(secretResolver), "runtime secret resolver helper must exist");
        assertTrue(Files.exists(dag), "Composer DAG must exist in the runtime namespace");

        String job = Files.readString(mainPython);
        assertTrue(job.contains("badHeaderRejected"));
        assertTrue(job.contains("corruptRowIds"));
        assertTrue(job.contains("emptyFileRowCount"));
        assertTrue(job.contains("bronze-orders"));

        String renderedDag = Files.readString(dag);
        assertTrue(renderedDag.contains("DataprocCreateBatchOperator"));
        assertTrue(renderedDag.contains("pulse_semantic_gcp_file_ingestion"));
        assertTrue(renderedDag.contains("semantic-file-ingestion-batch-20260506-0540"));
        assertFalse(renderedDag.contains("BashOperator"));

        assertTrue(Files.exists(namespacedRoot.resolve("runtime/dataproc-submit-request.json")));
        assertTrue(Files.exists(written.adapterPlan()));
        assertTrue(Files.exists(written.composerEvidence()));
        assertTrue(Files.exists(written.dataprocEvidence()));
        assertTrue(Files.exists(written.dataprocSubmitRequest()));
        assertTrue(Files.exists(written.renderedDag()));

        JsonNode submit = objectMapper.readTree(namespacedRoot.resolve("runtime/dataproc-submit-request.json").toFile());
        assertEquals("file-ingestion-gcp-golden", submit.get("scenarioId").asText());
        assertEquals(RUN_ID, submit.get("generationRunId").asText());
        assertEquals("pulse-proof-04261847", submit.get("projectId").asText());
        assertEquals("semantic-file-ingestion-batch-20260506-0540", submit.get("batchId").asText());
        assertEquals(
                STAGING_BUCKET + "/pulse-runtime/file-ingestion-gcp-golden/" + RUN_ID + "/"
                        + NAMESPACE + "/jobs/ingestion/file_ingestion.py",
                submit.at("/batch/pyspark_batch/main_python_file_uri").asText()
        );
    }

    private GcpComposerDataprocBridgeAdapter.AdapterRequest request() {
        return new GcpComposerDataprocBridgeAdapter.AdapterRequest(
                "file-ingestion-gcp-golden",
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
                "pulse_semantic_gcp_file_ingestion",
                "file_ingestion_gcp_dag.py",
                "pulse_semantic__file_ingestion_run_20260506_0540",
                "2026-05-06T05:40:00+00:00",
                "semantic-file-ingestion-batch-20260506-0540",
                "jobs/ingestion/file_ingestion.py",
                List.of("runtime/pulse_secret_resolver.py"),
                List.of(
                        "--valid-input", VALID_FIXTURE_URI,
                        "--empty-input", EMPTY_FIXTURE_URI,
                        "--bad-header-input", BAD_HEADER_FIXTURE_URI,
                        "--corrupt-input", CORRUPT_FIXTURE_URI,
                        "--config", CONFIG_URI,
                        "--output", OUTPUT_PREFIX
                ),
                "GCLOUD_SUBPROCESS",
                "GCLOUD_COMPOSER_RUN",
                "aamer@aamer.net",
                RUNTIME_ROOT
        );
    }

    private String fileIngestionJob() {
        return """
                from __future__ import annotations

                import argparse
                import json
                from pyspark.sql import SparkSession
                from pyspark.sql import functions as F


                def parse_args():
                    parser = argparse.ArgumentParser(description="FileIngestion semantic GCP golden proof job")
                    parser.add_argument("--valid-input", required=True)
                    parser.add_argument("--empty-input", required=True)
                    parser.add_argument("--bad-header-input", required=True)
                    parser.add_argument("--corrupt-input", required=True)
                    parser.add_argument("--config", required=True)
                    parser.add_argument("--output", required=True)
                    return parser.parse_args()


                def read_json_config(spark, uri):
                    return json.loads("\\n".join(row["value"] for row in spark.read.text(uri).collect()))


                def main():
                    args = parse_args()
                    spark = SparkSession.builder.appName("pulse-file-ingestion-gcp-golden").getOrCreate()
                    try:
                        config = read_json_config(spark, args.config)
                        required_columns = config["requiredColumns"]
                        valid = spark.read.option("header", "true").csv(args.valid_input)
                        empty = spark.read.option("header", "true").csv(args.empty_input)
                        bad_header = spark.read.option("header", "true").csv(args.bad_header_input)
                        corrupt = spark.read.option("header", "true").csv(args.corrupt_input)

                        bronze = valid.select(*required_columns).orderBy("order_id")
                        bronze.coalesce(1).write.mode("overwrite").option("header", "true").csv(
                            args.output + "/bronze-orders"
                        )

                        corrupt_rows = corrupt.where(
                            F.to_timestamp("order_ts").isNull()
                            | F.col("quantity").cast("int").isNull()
                            | F.col("unit_price").cast("double").isNull()
                        )
                        corrupt_row_ids = [
                            row["order_id"]
                            for row in corrupt_rows.select("order_id").orderBy("order_id").collect()
                        ]
                        bad_header_rejected = bad_header.columns != required_columns
                        report = {
                            "blueprintKey": "FileIngestion",
                            "schemaColumns": bronze.columns,
                            "rowCount": bronze.count(),
                            "emptyFileRowCount": empty.count(),
                            "badHeaderRejected": bad_header_rejected,
                            "corruptRowIds": corrupt_row_ids,
                            "verdict": "draft-pass",
                        }
                        metrics = {
                            "validRowCount": bronze.count(),
                            "emptyFileRowCount": empty.count(),
                            "badHeaderRejected": bad_header_rejected,
                            "corruptRowCount": len(corrupt_row_ids),
                        }
                        spark.sparkContext.parallelize([json.dumps(report, sort_keys=True)]).saveAsTextFile(
                            args.output + "/ingestion-report"
                        )
                        spark.sparkContext.parallelize([json.dumps(metrics, sort_keys=True)]).saveAsTextFile(
                            args.output + "/ingestion-metrics"
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
