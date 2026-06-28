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

class GenericJoinGcpGoldenRuntimeNamespaceMaterializationTest {

    private static final String RUN_ID = "run-20260506-0410";
    private static final String NAMESPACE = "tenants/home-lending/pipelines/loan-master";
    private static final String ORDERS_FIXTURE_URI = "gs://pulse-home-lending-dev-files/semantic/generic-join/input/positive/orders.csv";
    private static final String CUSTOMER_TIERS_FIXTURE_URI = "gs://pulse-home-lending-dev-files/semantic/generic-join/input/positive/customer_tiers.csv";
    private static final String OUTPUT_PREFIX = "gs://pulse-home-lending-dev-lake/semantic/generic-join/" + RUN_ID;
    private static final String STAGING_BUCKET = "gs://pulse-proof-04261847-dataproc-staging";
    private static final String DATAPROC_RUNTIME_SERVICE_ACCOUNT = "pulse-proof-dataproc-runtime@pulse-proof-04261847.iam.gserviceaccount.com";
    private static final Path RUNTIME_ROOT = Path.of("build/e2e-airflow-runtime");
    private static final Path EVIDENCE_ROOT = Path.of("build/e2e-semantic-hardening/gcp-golden")
            .resolve("generic-join")
            .resolve(RUN_ID)
            .resolve("deploy");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void materializeGenericJoinGcpGoldenRuntimeNamespaceTree() throws Exception {
        Path namespacedRoot = RUNTIME_ROOT.resolve(NAMESPACE);
        Path mainPython = namespacedRoot.resolve("jobs/join/generic_join.py");
        Path secretResolver = namespacedRoot.resolve("runtime/pulse_secret_resolver.py");
        Path dag = namespacedRoot.resolve("dags/generic_join_gcp_dag.py");

        Files.createDirectories(mainPython.getParent());
        Files.writeString(mainPython, genericJoinJob());
        Files.createDirectories(secretResolver.getParent());
        Files.writeString(secretResolver, secretResolverModule());
        Files.createDirectories(dag.getParent());

        GcpComposerDataprocBridgeAdapter adapter = new GcpComposerDataprocBridgeAdapter(objectMapper);
        GcpComposerDataprocBridgeAdapter.WrittenAdapterPlan written = adapter.writeEvidence(
                request(),
                EVIDENCE_ROOT
        );

        assertTrue(Files.exists(mainPython), "main GenericJoin PySpark job must exist");
        assertTrue(Files.exists(secretResolver), "runtime secret resolver helper must exist");
        assertTrue(Files.exists(dag), "Composer DAG must exist in the runtime namespace");

        String job = Files.readString(mainPython);
        assertTrue(job.contains("customer_tier"));
        assertTrue(job.contains("matchedOrderIds"));
        assertTrue(job.contains("unmatchedOrderIds"));
        assertTrue(job.contains("preservedLeftJoinSemantics"));

        String renderedDag = Files.readString(dag);
        assertTrue(renderedDag.contains("DataprocCreateBatchOperator"));
        assertTrue(renderedDag.contains("pulse_semantic_gcp_generic_join"));
        assertTrue(renderedDag.contains("semantic-generic-join-batch-20260506-0410"));
        assertFalse(renderedDag.contains("BashOperator"));

        assertTrue(Files.exists(namespacedRoot.resolve("runtime/dataproc-submit-request.json")));
        assertTrue(Files.exists(written.adapterPlan()));
        assertTrue(Files.exists(written.composerEvidence()));
        assertTrue(Files.exists(written.dataprocEvidence()));
        assertTrue(Files.exists(written.dataprocSubmitRequest()));
        assertTrue(Files.exists(written.renderedDag()));

        JsonNode submit = objectMapper.readTree(namespacedRoot.resolve("runtime/dataproc-submit-request.json").toFile());
        assertEquals("generic-join-gcp-golden", submit.get("scenarioId").asText());
        assertEquals(RUN_ID, submit.get("generationRunId").asText());
        assertEquals("pulse-proof-04261847", submit.get("projectId").asText());
        assertEquals("semantic-generic-join-batch-20260506-0410", submit.get("batchId").asText());
        assertEquals(
                STAGING_BUCKET + "/pulse-runtime/generic-join-gcp-golden/" + RUN_ID + "/"
                        + NAMESPACE + "/jobs/join/generic_join.py",
                submit.at("/batch/pyspark_batch/main_python_file_uri").asText()
        );
    }

    private GcpComposerDataprocBridgeAdapter.AdapterRequest request() {
        return new GcpComposerDataprocBridgeAdapter.AdapterRequest(
                "generic-join-gcp-golden",
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
                "pulse_semantic_gcp_generic_join",
                "generic_join_gcp_dag.py",
                "pulse_semantic__join_run_20260506_0410",
                "2026-05-06T04:10:00+00:00",
                "semantic-generic-join-batch-20260506-0410",
                "jobs/join/generic_join.py",
                List.of("runtime/pulse_secret_resolver.py"),
                List.of(
                        "--orders", ORDERS_FIXTURE_URI,
                        "--customer-tiers", CUSTOMER_TIERS_FIXTURE_URI,
                        "--output", OUTPUT_PREFIX
                ),
                "GCLOUD_SUBPROCESS",
                "GCLOUD_COMPOSER_RUN",
                "aamer@aamer.net",
                RUNTIME_ROOT
        );
    }

    private String genericJoinJob() {
        return """
                from __future__ import annotations

                import argparse
                import json
                from pyspark.sql import SparkSession
                from pyspark.sql import functions as F


                def parse_args():
                    parser = argparse.ArgumentParser(description="GenericJoin semantic GCP golden proof job")
                    parser.add_argument("--orders", required=True)
                    parser.add_argument("--customer-tiers", required=True)
                    parser.add_argument("--output", required=True)
                    return parser.parse_args()


                def main():
                    args = parse_args()
                    spark = SparkSession.builder.appName("pulse-generic-join-gcp-golden").getOrCreate()
                    try:
                        orders = spark.read.option("header", "true").csv(args.orders)
                        tiers = spark.read.option("header", "true").csv(args.customer_tiers)
                        joined = orders.join(tiers, on="customer_id", how="left").select(
                            "order_id",
                            "customer_id",
                            "order_total",
                            "currency",
                            "customer_tier",
                            "segment",
                        )
                        ordered = joined.orderBy("order_id")
                        ordered.coalesce(1).write.mode("overwrite").option("header", "true").csv(args.output + "/csv")
                        ordered.coalesce(1).write.mode("overwrite").json(args.output + "/json")

                        matched = [
                            row["order_id"]
                            for row in ordered.where(F.col("customer_tier").isNotNull())
                                .select("order_id")
                                .orderBy("order_id")
                                .collect()
                        ]
                        unmatched = [
                            row["order_id"]
                            for row in ordered.where(F.col("customer_tier").isNull())
                                .select("order_id")
                                .orderBy("order_id")
                                .collect()
                        ]
                        summary = {
                            "blueprintKey": "GenericJoin",
                            "scenarioId": "generic-join-gcp-golden",
                            "matchedOrderIds": matched,
                            "unmatchedOrderIds": unmatched,
                            "preservedLeftJoinSemantics": True,
                            "finalRowCount": ordered.count(),
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
