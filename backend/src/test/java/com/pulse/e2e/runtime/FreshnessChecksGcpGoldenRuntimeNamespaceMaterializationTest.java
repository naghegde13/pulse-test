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

class FreshnessChecksGcpGoldenRuntimeNamespaceMaterializationTest {

    private static final String RUN_ID = "run-20260506-0425";
    private static final String NAMESPACE = "tenants/home-lending/pipelines/loan-master";
    private static final String RECENT_FIXTURE_URI = "gs://pulse-home-lending-dev-files/semantic/freshness-checks/input/positive/recent_batches.csv";
    private static final String BOUNDARY_FIXTURE_URI = "gs://pulse-home-lending-dev-files/semantic/freshness-checks/input/edge/boundary_batches.csv";
    private static final String STALE_FIXTURE_URI = "gs://pulse-home-lending-dev-files/semantic/freshness-checks/input/negative/stale_batches.csv";
    private static final String CONFIG_URI = "gs://pulse-home-lending-dev-files/semantic/freshness-checks/input/expectations/freshness-config.json";
    private static final String OUTPUT_PREFIX = "gs://pulse-home-lending-dev-lake/semantic/freshness-checks/" + RUN_ID;
    private static final String STAGING_BUCKET = "gs://pulse-proof-04261847-dataproc-staging";
    private static final String DATAPROC_RUNTIME_SERVICE_ACCOUNT = "pulse-proof-dataproc-runtime@pulse-proof-04261847.iam.gserviceaccount.com";
    private static final Path RUNTIME_ROOT = Path.of("build/e2e-airflow-runtime");
    private static final Path EVIDENCE_ROOT = Path.of("build/e2e-semantic-hardening/gcp-golden")
            .resolve("freshness-checks")
            .resolve(RUN_ID)
            .resolve("deploy");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void materializeFreshnessChecksGcpGoldenRuntimeNamespaceTree() throws Exception {
        Path namespacedRoot = RUNTIME_ROOT.resolve(NAMESPACE);
        Path mainPython = namespacedRoot.resolve("jobs/freshness/freshness_checks.py");
        Path secretResolver = namespacedRoot.resolve("runtime/pulse_secret_resolver.py");
        Path dag = namespacedRoot.resolve("dags/freshness_checks_gcp_dag.py");

        Files.createDirectories(mainPython.getParent());
        Files.writeString(mainPython, freshnessChecksJob());
        Files.createDirectories(secretResolver.getParent());
        Files.writeString(secretResolver, secretResolverModule());
        Files.createDirectories(dag.getParent());

        GcpComposerDataprocBridgeAdapter adapter = new GcpComposerDataprocBridgeAdapter(objectMapper);
        GcpComposerDataprocBridgeAdapter.WrittenAdapterPlan written = adapter.writeEvidence(
                request(),
                EVIDENCE_ROOT
        );

        assertTrue(Files.exists(mainPython), "main FreshnessChecks PySpark job must exist");
        assertTrue(Files.exists(secretResolver), "runtime secret resolver helper must exist");
        assertTrue(Files.exists(dag), "Composer DAG must exist in the runtime namespace");

        String job = Files.readString(mainPython);
        assertTrue(job.contains("freshBatchIds"));
        assertTrue(job.contains("staleBatchIds"));
        assertTrue(job.contains("boundaryPassCount"));
        assertTrue(job.contains("maxLagMinutes"));

        String renderedDag = Files.readString(dag);
        assertTrue(renderedDag.contains("DataprocCreateBatchOperator"));
        assertTrue(renderedDag.contains("pulse_semantic_gcp_freshness_checks"));
        assertTrue(renderedDag.contains("semantic-freshness-checks-batch-20260506-0425"));
        assertFalse(renderedDag.contains("BashOperator"));

        assertTrue(Files.exists(namespacedRoot.resolve("runtime/dataproc-submit-request.json")));
        assertTrue(Files.exists(written.adapterPlan()));
        assertTrue(Files.exists(written.composerEvidence()));
        assertTrue(Files.exists(written.dataprocEvidence()));
        assertTrue(Files.exists(written.dataprocSubmitRequest()));
        assertTrue(Files.exists(written.renderedDag()));

        JsonNode submit = objectMapper.readTree(namespacedRoot.resolve("runtime/dataproc-submit-request.json").toFile());
        assertEquals("freshness-checks-gcp-golden", submit.get("scenarioId").asText());
        assertEquals(RUN_ID, submit.get("generationRunId").asText());
        assertEquals("pulse-proof-04261847", submit.get("projectId").asText());
        assertEquals("semantic-freshness-checks-batch-20260506-0425", submit.get("batchId").asText());
        assertEquals(
                STAGING_BUCKET + "/pulse-runtime/freshness-checks-gcp-golden/" + RUN_ID + "/"
                        + NAMESPACE + "/jobs/freshness/freshness_checks.py",
                submit.at("/batch/pyspark_batch/main_python_file_uri").asText()
        );
    }

    private GcpComposerDataprocBridgeAdapter.AdapterRequest request() {
        return new GcpComposerDataprocBridgeAdapter.AdapterRequest(
                "freshness-checks-gcp-golden",
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
                "pulse_semantic_gcp_freshness_checks",
                "freshness_checks_gcp_dag.py",
                "pulse_semantic__freshness_checks_run_20260506_0425",
                "2026-05-06T04:25:00+00:00",
                "semantic-freshness-checks-batch-20260506-0425",
                "jobs/freshness/freshness_checks.py",
                List.of("runtime/pulse_secret_resolver.py"),
                List.of(
                        "--recent-input", RECENT_FIXTURE_URI,
                        "--boundary-input", BOUNDARY_FIXTURE_URI,
                        "--stale-input", STALE_FIXTURE_URI,
                        "--config", CONFIG_URI,
                        "--output", OUTPUT_PREFIX
                ),
                "GCLOUD_SUBPROCESS",
                "GCLOUD_COMPOSER_RUN",
                "aamer@aamer.net",
                RUNTIME_ROOT
        );
    }

    private String freshnessChecksJob() {
        return """
                from __future__ import annotations

                import argparse
                import json
                from pyspark.sql import SparkSession
                from pyspark.sql import functions as F


                def parse_args():
                    parser = argparse.ArgumentParser(description="FreshnessChecks semantic GCP golden proof job")
                    parser.add_argument("--recent-input", required=True)
                    parser.add_argument("--boundary-input", required=True)
                    parser.add_argument("--stale-input", required=True)
                    parser.add_argument("--config", required=True)
                    parser.add_argument("--output", required=True)
                    return parser.parse_args()


                def main():
                    args = parse_args()
                    spark = SparkSession.builder.appName("pulse-freshness-checks-gcp-golden").getOrCreate()
                    try:
                        config = spark.read.option("multiLine", "true").json(args.config).first().asDict()
                        as_of = config["asOfTimestamp"]
                        max_lag_minutes = int(config["maxLagMinutes"])
                        recent = spark.read.option("header", "true").csv(args.recent_input)
                        boundary = spark.read.option("header", "true").csv(args.boundary_input)
                        stale = spark.read.option("header", "true").csv(args.stale_input)
                        batches = recent.unionByName(boundary).unionByName(stale)

                        evaluated = batches.withColumn(
                            "lag_minutes",
                            (
                                F.unix_timestamp(F.lit(as_of), "yyyy-MM-dd'T'HH:mm:ss'Z'")
                                - F.unix_timestamp(F.col("loaded_at"), "yyyy-MM-dd'T'HH:mm:ss'Z'")
                            ) / F.lit(60)
                        ).withColumn(
                            "freshness_status",
                            F.when(F.col("lag_minutes") <= F.lit(max_lag_minutes), F.lit("fresh"))
                             .otherwise(F.lit("stale"))
                        )

                        evaluated.orderBy("batch_id").coalesce(1).write.mode("overwrite").option("header", "true").csv(
                            args.output + "/evaluated-batches"
                        )

                        fresh_ids = [
                            row["batch_id"]
                            for row in evaluated.where(F.col("freshness_status") == F.lit("fresh"))
                                .select("batch_id")
                                .orderBy("batch_id")
                                .collect()
                        ]
                        stale_ids = [
                            row["batch_id"]
                            for row in evaluated.where(F.col("freshness_status") == F.lit("stale"))
                                .select("batch_id")
                                .orderBy("batch_id")
                                .collect()
                        ]
                        boundary_pass_count = evaluated.where(
                            (F.col("batch_id") == F.lit("B101"))
                            & (F.col("freshness_status") == F.lit("fresh"))
                        ).count()
                        report = {
                            "freshBatchIds": fresh_ids,
                            "staleBatchIds": stale_ids,
                            "boundaryBatchId": "B101",
                            "verdict": "draft-pass",
                        }
                        metrics = {
                            "freshCount": len(fresh_ids),
                            "staleCount": len(stale_ids),
                            "boundaryPassCount": boundary_pass_count,
                        }
                        spark.sparkContext.parallelize([json.dumps(report, sort_keys=True)]).saveAsTextFile(
                            args.output + "/freshness-report"
                        )
                        spark.sparkContext.parallelize([json.dumps(metrics, sort_keys=True)]).saveAsTextFile(
                            args.output + "/freshness-metrics"
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
