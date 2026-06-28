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

class DedupeAndMergeGcpGoldenRuntimeNamespaceMaterializationTest {

    private static final String RUN_ID = "run-20260506-0850";
    private static final String NAMESPACE = "tenants/home-lending/pipelines/loan-master";
    private static final String INPUT_ROOT = "gs://pulse-home-lending-dev-files/semantic/dedupe-and-merge/input";
    private static final String CONFIG_URI = INPUT_ROOT + "/expectations/dedupe-config.json";
    private static final String OUTPUT_PREFIX = "gs://pulse-home-lending-dev-lake/semantic/dedupe-and-merge/" + RUN_ID;
    private static final String STAGING_BUCKET = "gs://pulse-proof-04261847-dataproc-staging";
    private static final String DATAPROC_RUNTIME_SERVICE_ACCOUNT = "pulse-proof-dataproc-runtime@pulse-proof-04261847.iam.gserviceaccount.com";
    private static final Path RUNTIME_ROOT = Path.of("build/e2e-airflow-runtime");
    private static final Path EVIDENCE_ROOT = Path.of("build/e2e-semantic-hardening/gcp-golden")
            .resolve("dedupe-and-merge")
            .resolve(RUN_ID)
            .resolve("deploy");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void materializeDedupeAndMergeGcpGoldenRuntimeNamespaceTree() throws Exception {
        Path namespacedRoot = RUNTIME_ROOT.resolve(NAMESPACE);
        Path mainPython = namespacedRoot.resolve("jobs/transform/dedupe_and_merge.py");
        Path secretResolver = namespacedRoot.resolve("runtime/pulse_secret_resolver.py");
        Path dag = namespacedRoot.resolve("dags/dedupe_and_merge_gcp_dag.py");

        Files.createDirectories(mainPython.getParent());
        Files.writeString(mainPython, dedupeJob());
        Files.createDirectories(secretResolver.getParent());
        Files.writeString(secretResolver, secretResolverModule());
        Files.createDirectories(dag.getParent());

        GcpComposerDataprocBridgeAdapter.WrittenAdapterPlan written =
                new GcpComposerDataprocBridgeAdapter(objectMapper).writeEvidence(request(), EVIDENCE_ROOT);

        assertTrue(Files.exists(mainPython));
        assertTrue(Files.exists(secretResolver));
        assertTrue(Files.exists(dag));
        assertTrue(Files.readString(mainPython).contains("final-products"));
        assertTrue(Files.readString(mainPython).contains("same-timestamp-conflict"));

        String renderedDag = Files.readString(dag);
        assertTrue(renderedDag.contains("DataprocCreateBatchOperator"));
        assertTrue(renderedDag.contains("pulse_semantic_gcp_dedupe_and_merge"));
        assertTrue(renderedDag.contains("semantic-dedupe-and-merge-batch-20260506-0850"));
        assertFalse(renderedDag.contains("BashOperator"));

        assertTrue(Files.exists(written.adapterPlan()));
        assertTrue(Files.exists(written.composerEvidence()));
        assertTrue(Files.exists(written.dataprocEvidence()));
        assertTrue(Files.exists(written.dataprocSubmitRequest()));
        assertTrue(Files.exists(written.renderedDag()));

        JsonNode submit = objectMapper.readTree(namespacedRoot.resolve("runtime/dataproc-submit-request.json").toFile());
        assertEquals("dedupe-and-merge-gcp-golden", submit.get("scenarioId").asText());
        assertEquals(RUN_ID, submit.get("generationRunId").asText());
        assertEquals("semantic-dedupe-and-merge-batch-20260506-0850", submit.get("batchId").asText());
    }

    private GcpComposerDataprocBridgeAdapter.AdapterRequest request() {
        return new GcpComposerDataprocBridgeAdapter.AdapterRequest(
                "dedupe-and-merge-gcp-golden", RUN_ID, "pulse-proof-04261847", "us-central1",
                "pulse-proof-composer", "gs://us-central1-pulse-proof-com-a7066110-bucket/dags", "us-central1",
                DATAPROC_RUNTIME_SERVICE_ACCOUNT, STAGING_BUCKET, OUTPUT_PREFIX, NAMESPACE,
                "pulse_semantic_gcp_dedupe_and_merge", "dedupe_and_merge_gcp_dag.py",
                "pulse_semantic__dedupe_and_merge_run_20260506_0850", "2026-05-06T08:50:00+00:00",
                "semantic-dedupe-and-merge-batch-20260506-0850", "jobs/transform/dedupe_and_merge.py",
                List.of("runtime/pulse_secret_resolver.py"),
                List.of("--positive-input", INPUT_ROOT + "/positive/product_updates.csv",
                        "--edge-input", INPUT_ROOT + "/edge/product_updates_same_timestamp_duplicate.csv",
                        "--negative-input", INPUT_ROOT + "/negative/product_updates_conflicts.csv",
                        "--config", CONFIG_URI,
                        "--output", OUTPUT_PREFIX),
                "GCLOUD_SUBPROCESS", "GCLOUD_COMPOSER_RUN", "aamer@aamer.net", RUNTIME_ROOT
        );
    }

    private String dedupeJob() {
        return """
                from __future__ import annotations
                import argparse, json
                from pyspark.sql import SparkSession, Window
                from pyspark.sql import functions as F

                def parse_args():
                    p = argparse.ArgumentParser(description="DedupeAndMerge semantic GCP golden proof job")
                    p.add_argument("--positive-input", required=True)
                    p.add_argument("--edge-input", required=True)
                    p.add_argument("--negative-input", required=True)
                    p.add_argument("--config", required=True)
                    p.add_argument("--output", required=True)
                    return p.parse_args()

                def read_json_config(spark, uri):
                    return json.loads("\\n".join(row["value"] for row in spark.read.text(uri).collect()))

                def read_updates(spark, uri):
                    return spark.read.option("header", "true").csv(uri) \\
                        .withColumn("price", F.col("price").cast("double")) \\
                        .withColumn("source_priority", F.col("source_priority").cast("int"))

                def main():
                    args = parse_args()
                    spark = SparkSession.builder.appName("pulse-dedupe-and-merge-gcp-golden").getOrCreate()
                    try:
                        read_json_config(spark, args.config)
                        updates = read_updates(spark, args.positive_input).unionByName(read_updates(spark, args.edge_input))
                        negative = read_updates(spark, args.negative_input)
                        null_keys = negative.where(F.col("product_id").isNull()).select(F.lit("").alias("product_id"), F.lit("null-key").alias("reason"))
                        conflict_ids = negative.where(F.col("product_id").isNotNull()).groupBy("product_id", "version_ts").agg(
                            F.countDistinct(F.struct("name", "price", "status", "source_priority")).alias("variant_count")
                        ).where(F.col("variant_count") > 1).select("product_id")
                        conflicts = conflict_ids.select("product_id", F.lit("same-timestamp-conflict").alias("reason"))
                        quarantine = null_keys.unionByName(conflicts).orderBy("product_id", "reason")
                        accepted_keys = [r["product_id"] for r in conflict_ids.collect()]
                        accepted = updates.where(~F.col("product_id").isin(accepted_keys))
                        deduped_same_rows = accepted.dropDuplicates(["product_id", "version_ts", "name", "price", "status", "source_priority"])
                        duplicate_input_count = updates.count() - deduped_same_rows.count() + quarantine.count()
                        window = Window.partitionBy("product_id").orderBy(F.col("version_ts").desc(), F.col("source_priority").asc())
                        final_products = deduped_same_rows.withColumn("rn", F.row_number().over(window)).where(F.col("rn") == 1).drop("rn").orderBy("product_id")
                        final_products.coalesce(1).write.mode("overwrite").option("header", "true").csv(args.output + "/final-products")
                        quarantine.coalesce(1).write.mode("overwrite").option("header", "true").csv(args.output + "/dedupe-quarantine")
                        quarantine_ids = [r["product_id"] for r in quarantine.select("product_id").collect()]
                        report = {"blueprintKey":"DedupeAndMerge","finalRowCount":final_products.count(),"quarantineIds":quarantine_ids,"duplicateInputCount":duplicate_input_count,"verdict":"draft-pass"}
                        metrics = {"finalRowCount":final_products.count(),"quarantineRowCount":quarantine.count(),"duplicateInputCount":duplicate_input_count}
                        spark.sparkContext.parallelize([json.dumps(report, sort_keys=True)]).saveAsTextFile(args.output + "/dedupe-report")
                        spark.sparkContext.parallelize([json.dumps(metrics, sort_keys=True)]).saveAsTextFile(args.output + "/dedupe-metrics")
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
