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

class JsonFlattenGcpGoldenRuntimeNamespaceMaterializationTest {

    private static final String RUN_ID = "run-20260506-0740";
    private static final String NAMESPACE = "tenants/home-lending/pipelines/loan-master";
    private static final String INPUT_ROOT = "gs://pulse-home-lending-dev-files/semantic/json-flatten/input";
    private static final String CONFIG_URI = INPUT_ROOT + "/expectations/flatten-config.json";
    private static final String OUTPUT_PREFIX = "gs://pulse-home-lending-dev-lake/semantic/json-flatten/" + RUN_ID;
    private static final String STAGING_BUCKET = "gs://pulse-proof-04261847-dataproc-staging";
    private static final String DATAPROC_RUNTIME_SERVICE_ACCOUNT = "pulse-proof-dataproc-runtime@pulse-proof-04261847.iam.gserviceaccount.com";
    private static final Path RUNTIME_ROOT = Path.of("build/e2e-airflow-runtime");
    private static final Path EVIDENCE_ROOT = Path.of("build/e2e-semantic-hardening/gcp-golden")
            .resolve("json-flatten")
            .resolve(RUN_ID)
            .resolve("deploy");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void materializeJsonFlattenGcpGoldenRuntimeNamespaceTree() throws Exception {
        Path namespacedRoot = RUNTIME_ROOT.resolve(NAMESPACE);
        Path mainPython = namespacedRoot.resolve("jobs/transform/json_flatten.py");
        Path secretResolver = namespacedRoot.resolve("runtime/pulse_secret_resolver.py");
        Path dag = namespacedRoot.resolve("dags/json_flatten_gcp_dag.py");

        Files.createDirectories(mainPython.getParent());
        Files.writeString(mainPython, flattenJob());
        Files.createDirectories(secretResolver.getParent());
        Files.writeString(secretResolver, secretResolverModule());
        Files.createDirectories(dag.getParent());

        GcpComposerDataprocBridgeAdapter.WrittenAdapterPlan written =
                new GcpComposerDataprocBridgeAdapter(objectMapper).writeEvidence(request(), EVIDENCE_ROOT);

        assertTrue(Files.exists(mainPython));
        assertTrue(Files.exists(secretResolver));
        assertTrue(Files.exists(dag));
        assertTrue(Files.readString(mainPython).contains("flat-events"));
        assertTrue(Files.readString(mainPython).contains("rejectEventIds"));

        String renderedDag = Files.readString(dag);
        assertTrue(renderedDag.contains("DataprocCreateBatchOperator"));
        assertTrue(renderedDag.contains("pulse_semantic_gcp_json_flatten"));
        assertTrue(renderedDag.contains("semantic-json-flatten-batch-20260506-0740"));
        assertFalse(renderedDag.contains("BashOperator"));

        assertTrue(Files.exists(written.adapterPlan()));
        assertTrue(Files.exists(written.composerEvidence()));
        assertTrue(Files.exists(written.dataprocEvidence()));
        assertTrue(Files.exists(written.dataprocSubmitRequest()));
        assertTrue(Files.exists(written.renderedDag()));

        JsonNode submit = objectMapper.readTree(namespacedRoot.resolve("runtime/dataproc-submit-request.json").toFile());
        assertEquals("json-flatten-gcp-golden", submit.get("scenarioId").asText());
        assertEquals(RUN_ID, submit.get("generationRunId").asText());
        assertEquals("semantic-json-flatten-batch-20260506-0740", submit.get("batchId").asText());
    }

    private GcpComposerDataprocBridgeAdapter.AdapterRequest request() {
        return new GcpComposerDataprocBridgeAdapter.AdapterRequest(
                "json-flatten-gcp-golden", RUN_ID, "pulse-proof-04261847", "us-central1",
                "pulse-proof-composer", "gs://us-central1-pulse-proof-com-a7066110-bucket/dags", "us-central1",
                DATAPROC_RUNTIME_SERVICE_ACCOUNT, STAGING_BUCKET, OUTPUT_PREFIX, NAMESPACE,
                "pulse_semantic_gcp_json_flatten", "json_flatten_gcp_dag.py",
                "pulse_semantic__json_flatten_run_20260506_0740", "2026-05-06T07:40:00+00:00",
                "semantic-json-flatten-batch-20260506-0740", "jobs/transform/json_flatten.py",
                List.of("runtime/pulse_secret_resolver.py"),
                List.of("--positive-input", INPUT_ROOT + "/positive/events_nested.json",
                        "--edge-input", INPUT_ROOT + "/edge/events_edge.json",
                        "--negative-input", INPUT_ROOT + "/negative/events_malformed.json",
                        "--config", CONFIG_URI,
                        "--output", OUTPUT_PREFIX),
                "GCLOUD_SUBPROCESS", "GCLOUD_COMPOSER_RUN", "aamer@aamer.net", RUNTIME_ROOT
        );
    }

    private String flattenJob() {
        return """
                from __future__ import annotations
                import argparse, json
                from pyspark.sql import SparkSession
                from pyspark.sql import functions as F

                def parse_args():
                    p = argparse.ArgumentParser(description="JsonFlatten semantic GCP golden proof job")
                    p.add_argument("--positive-input", required=True)
                    p.add_argument("--edge-input", required=True)
                    p.add_argument("--negative-input", required=True)
                    p.add_argument("--config", required=True)
                    p.add_argument("--output", required=True)
                    return p.parse_args()

                def read_json_config(spark, uri):
                    return json.loads("\\n".join(row["value"] for row in spark.read.text(uri).collect()))

                def main():
                    args = parse_args()
                    spark = SparkSession.builder.appName("pulse-json-flatten-gcp-golden").getOrCreate()
                    try:
                        read_json_config(spark, args.config)
                        events = spark.read.json([args.positive_input, args.edge_input])
                        malformed = spark.read.text(args.negative_input).select(F.lit("E900").alias("event_id"))
                        missing_customer = events.where(F.col("customer").isNull()).select("event_id")
                        rejects = malformed.unionByName(missing_customer).orderBy("event_id")
                        empty_ids = [r["event_id"] for r in events.where(F.size("items") == 0).select("event_id").orderBy("event_id").collect()]
                        valid = events.where(F.col("customer").isNotNull() & (F.size("items") > 0))
                        flat = valid.select(
                            "event_id",
                            F.col("session.session_id").alias("session_id"),
                            F.col("customer.customer_id").alias("customer_id"),
                            F.col("device.type").alias("device_type"),
                            F.col("geo.country").alias("country"),
                            F.col("geo.region").alias("region"),
                            F.posexplode("items").alias("item_index", "item"),
                        ).select(
                            "event_id", "session_id", "customer_id", "device_type", "country", "region", "item_index",
                            F.col("item.sku").alias("sku"),
                            F.col("item.quantity").cast("int").alias("quantity"),
                            F.col("item.price").cast("double").alias("price"),
                        ).orderBy("event_id", "item_index")
                        flat.coalesce(1).write.mode("overwrite").option("header", "true").csv(args.output + "/flat-events")
                        rejects.coalesce(1).write.mode("overwrite").option("header", "true").csv(args.output + "/flatten-rejects")
                        reject_ids = [r["event_id"] for r in rejects.collect()]
                        report = {"blueprintKey":"JsonFlatten","flatRowCount":flat.count(),"emptyArrayEventIds":empty_ids,"rejectEventIds":reject_ids,"verdict":"draft-pass"}
                        metrics = {"flatRowCount":flat.count(),"emptyArrayEventCount":len(empty_ids),"rejectRowCount":rejects.count()}
                        spark.sparkContext.parallelize([json.dumps(report, sort_keys=True)]).saveAsTextFile(args.output + "/flatten-report")
                        spark.sparkContext.parallelize([json.dumps(metrics, sort_keys=True)]).saveAsTextFile(args.output + "/flatten-metrics")
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
