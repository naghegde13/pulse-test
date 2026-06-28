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

class GenericRouterGcpGoldenRuntimeNamespaceMaterializationTest {

    private static final String RUN_ID = "run-20260506-0345";
    private static final String NAMESPACE = "tenants/home-lending/pipelines/loan-master";
    private static final String INPUT_FIXTURE_URI = "gs://pulse-home-lending-dev-files/semantic/generic-router/input/positive/applications.csv";
    private static final String OUTPUT_PREFIX = "gs://pulse-home-lending-dev-lake/semantic/generic-router/" + RUN_ID;
    private static final String STAGING_BUCKET = "gs://pulse-proof-04261847-dataproc-staging";
    private static final String DATAPROC_RUNTIME_SERVICE_ACCOUNT = "pulse-proof-dataproc-runtime@pulse-proof-04261847.iam.gserviceaccount.com";
    private static final Path RUNTIME_ROOT = Path.of("build/e2e-airflow-runtime");
    private static final Path EVIDENCE_ROOT = Path.of("build/e2e-semantic-hardening/gcp-golden")
            .resolve("generic-router")
            .resolve(RUN_ID)
            .resolve("deploy");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void materializeGenericRouterGcpGoldenRuntimeNamespaceTree() throws Exception {
        Path namespacedRoot = RUNTIME_ROOT.resolve(NAMESPACE);
        Path mainPython = namespacedRoot.resolve("jobs/router/generic_router.py");
        Path secretResolver = namespacedRoot.resolve("runtime/pulse_secret_resolver.py");
        Path dag = namespacedRoot.resolve("dags/generic_router_gcp_dag.py");

        Files.createDirectories(mainPython.getParent());
        Files.writeString(mainPython, genericRouterJob());
        Files.createDirectories(secretResolver.getParent());
        Files.writeString(secretResolver, secretResolverModule());
        Files.createDirectories(dag.getParent());

        GcpComposerDataprocBridgeAdapter adapter = new GcpComposerDataprocBridgeAdapter(objectMapper);
        GcpComposerDataprocBridgeAdapter.WrittenAdapterPlan written = adapter.writeEvidence(
                request(),
                EVIDENCE_ROOT
        );

        assertTrue(Files.exists(mainPython), "main GenericRouter PySpark job must exist");
        assertTrue(Files.exists(secretResolver), "runtime secret resolver helper must exist");
        assertTrue(Files.exists(dag), "Composer DAG must exist in the runtime namespace");
        assertEquals(dag.normalize(), RUNTIME_ROOT.resolve(NAMESPACE).resolve("dags/generic_router_gcp_dag.py").normalize());

        String job = Files.readString(mainPython);
        assertTrue(job.contains("risk_score"));
        assertTrue(job.contains("loan_amount"));
        assertTrue(job.contains("manual_review_flag"));
        assertTrue(job.contains("routeCounts"));
        assertTrue(job.contains("manualReviewApplicationIds"));

        String renderedDag = Files.readString(dag);
        assertTrue(renderedDag.contains("DataprocCreateBatchOperator"));
        assertTrue(renderedDag.contains("pulse_semantic_gcp_generic_router"));
        assertTrue(renderedDag.contains("semantic-generic-router-batch-20260506-0345"));
        assertFalse(renderedDag.contains("BashOperator"));

        assertTrue(Files.exists(namespacedRoot.resolve("runtime/dataproc-submit-request.json")));
        assertTrue(Files.exists(written.adapterPlan()));
        assertTrue(Files.exists(written.composerEvidence()));
        assertTrue(Files.exists(written.dataprocEvidence()));
        assertTrue(Files.exists(written.dataprocSubmitRequest()));
        assertTrue(Files.exists(written.renderedDag()));

        JsonNode submit = objectMapper.readTree(namespacedRoot.resolve("runtime/dataproc-submit-request.json").toFile());
        assertEquals("generic-router-gcp-golden", submit.get("scenarioId").asText());
        assertEquals(RUN_ID, submit.get("generationRunId").asText());
        assertEquals("pulse-proof-04261847", submit.get("projectId").asText());
        assertEquals("semantic-generic-router-batch-20260506-0345", submit.get("batchId").asText());
        assertEquals(
                STAGING_BUCKET + "/pulse-runtime/generic-router-gcp-golden/" + RUN_ID + "/"
                        + NAMESPACE + "/jobs/router/generic_router.py",
                submit.at("/batch/pyspark_batch/main_python_file_uri").asText()
        );
    }

    private GcpComposerDataprocBridgeAdapter.AdapterRequest request() {
        return new GcpComposerDataprocBridgeAdapter.AdapterRequest(
                "generic-router-gcp-golden",
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
                "pulse_semantic_gcp_generic_router",
                "generic_router_gcp_dag.py",
                "pulse_semantic__router_run_20260506_0345",
                "2026-05-06T03:45:00+00:00",
                "semantic-generic-router-batch-20260506-0345",
                "jobs/router/generic_router.py",
                List.of("runtime/pulse_secret_resolver.py"),
                List.of(
                        "--input", INPUT_FIXTURE_URI,
                        "--output", OUTPUT_PREFIX
                ),
                "GCLOUD_SUBPROCESS",
                "GCLOUD_COMPOSER_RUN",
                "aamer@aamer.net",
                RUNTIME_ROOT
        );
    }

    private String genericRouterJob() {
        return """
                from __future__ import annotations

                import argparse
                import json
                from pyspark.sql import SparkSession
                from pyspark.sql import functions as F


                def parse_args():
                    parser = argparse.ArgumentParser(description="GenericRouter semantic GCP golden proof job")
                    parser.add_argument("--input", required=True)
                    parser.add_argument("--output", required=True)
                    return parser.parse_args()


                def main():
                    args = parse_args()
                    spark = SparkSession.builder.appName("pulse-generic-router-gcp-golden").getOrCreate()
                    try:
                        source = spark.read.option("header", "true").csv(args.input)
                        routed = source.withColumn(
                            "expected_route",
                            F.when(F.col("risk_score").cast("int") >= F.lit(780), F.lit("prime"))
                             .when(
                                 (F.lower(F.col("manual_review_flag").cast("string")) == F.lit("true"))
                                 | (F.col("loan_amount").cast("double") > F.lit(20000.0)),
                                 F.lit("manual_review")
                             )
                             .when(F.col("risk_score").cast("int") < F.lit(600), F.lit("high_risk"))
                             .otherwise(F.lit("standard"))
                        )
                        selected = routed.select("application_id", "expected_route").orderBy("application_id")
                        selected.coalesce(1).write.mode("overwrite").option("header", "true").csv(args.output + "/csv")
                        selected.coalesce(1).write.mode("overwrite").json(args.output + "/json")

                        route_rows = selected.groupBy("expected_route").count().collect()
                        route_counts = {row["expected_route"]: row["count"] for row in route_rows}
                        manual_review_ids = [
                            row["application_id"]
                            for row in selected.where(F.col("expected_route") == F.lit("manual_review"))
                                .select("application_id")
                                .orderBy("application_id")
                                .collect()
                        ]
                        summary = {
                            "blueprintKey": "GenericRouter",
                            "scenarioId": "generic-router-gcp-golden",
                            "routeCounts": route_counts,
                            "manualReviewApplicationIds": manual_review_ids,
                            "routedApplicationCount": selected.count(),
                        }
                        spark.sparkContext.parallelize([json.dumps(summary, sort_keys=True)]).saveAsTextFile(
                            args.output + "/summary"
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
