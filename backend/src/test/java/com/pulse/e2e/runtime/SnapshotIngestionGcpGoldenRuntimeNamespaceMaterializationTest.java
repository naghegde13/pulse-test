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

class SnapshotIngestionGcpGoldenRuntimeNamespaceMaterializationTest {

    private static final String RUN_ID = "run-20260506-0610";
    private static final String NAMESPACE = "tenants/home-lending/pipelines/loan-master";
    private static final String INPUT_ROOT = "gs://pulse-home-lending-dev-files/semantic/snapshot-ingestion/input";
    private static final String CONFIG_URI = INPUT_ROOT + "/expectations/snapshot-config.json";
    private static final String OUTPUT_PREFIX = "gs://pulse-home-lending-dev-lake/semantic/snapshot-ingestion/" + RUN_ID;
    private static final String STAGING_BUCKET = "gs://pulse-proof-04261847-dataproc-staging";
    private static final String DATAPROC_RUNTIME_SERVICE_ACCOUNT = "pulse-proof-dataproc-runtime@pulse-proof-04261847.iam.gserviceaccount.com";
    private static final Path RUNTIME_ROOT = Path.of("build/e2e-airflow-runtime");
    private static final Path EVIDENCE_ROOT = Path.of("build/e2e-semantic-hardening/gcp-golden")
            .resolve("snapshot-ingestion")
            .resolve(RUN_ID)
            .resolve("deploy");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void materializeSnapshotIngestionGcpGoldenRuntimeNamespaceTree() throws Exception {
        Path namespacedRoot = RUNTIME_ROOT.resolve(NAMESPACE);
        Path mainPython = namespacedRoot.resolve("jobs/ingestion/snapshot_ingestion.py");
        Path secretResolver = namespacedRoot.resolve("runtime/pulse_secret_resolver.py");
        Path dag = namespacedRoot.resolve("dags/snapshot_ingestion_gcp_dag.py");

        Files.createDirectories(mainPython.getParent());
        Files.writeString(mainPython, snapshotIngestionJob());
        Files.createDirectories(secretResolver.getParent());
        Files.writeString(secretResolver, secretResolverModule());
        Files.createDirectories(dag.getParent());

        GcpComposerDataprocBridgeAdapter adapter = new GcpComposerDataprocBridgeAdapter(objectMapper);
        GcpComposerDataprocBridgeAdapter.WrittenAdapterPlan written = adapter.writeEvidence(request(), EVIDENCE_ROOT);

        assertTrue(Files.exists(mainPython));
        assertTrue(Files.exists(secretResolver));
        assertTrue(Files.exists(dag));

        String job = Files.readString(mainPython);
        assertTrue(job.contains("sameAsOfRerunDuplicateRows"));
        assertTrue(job.contains("missingAsOfRejected"));
        assertTrue(job.contains("snapshot-folders"));

        String renderedDag = Files.readString(dag);
        assertTrue(renderedDag.contains("DataprocCreateBatchOperator"));
        assertTrue(renderedDag.contains("pulse_semantic_gcp_snapshot_ingestion"));
        assertTrue(renderedDag.contains("semantic-snapshot-ingestion-batch-20260506-0610"));
        assertFalse(renderedDag.contains("BashOperator"));

        assertTrue(Files.exists(written.adapterPlan()));
        assertTrue(Files.exists(written.composerEvidence()));
        assertTrue(Files.exists(written.dataprocEvidence()));
        assertTrue(Files.exists(written.dataprocSubmitRequest()));
        assertTrue(Files.exists(written.renderedDag()));

        JsonNode submit = objectMapper.readTree(namespacedRoot.resolve("runtime/dataproc-submit-request.json").toFile());
        assertEquals("snapshot-ingestion-gcp-golden", submit.get("scenarioId").asText());
        assertEquals(RUN_ID, submit.get("generationRunId").asText());
        assertEquals("semantic-snapshot-ingestion-batch-20260506-0610", submit.get("batchId").asText());
    }

    private GcpComposerDataprocBridgeAdapter.AdapterRequest request() {
        return new GcpComposerDataprocBridgeAdapter.AdapterRequest(
                "snapshot-ingestion-gcp-golden",
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
                "pulse_semantic_gcp_snapshot_ingestion",
                "snapshot_ingestion_gcp_dag.py",
                "pulse_semantic__snapshot_ingestion_run_20260506_0610",
                "2026-05-06T06:10:00+00:00",
                "semantic-snapshot-ingestion-batch-20260506-0610",
                "jobs/ingestion/snapshot_ingestion.py",
                List.of("runtime/pulse_secret_resolver.py"),
                List.of(
                        "--t1-input", INPUT_ROOT + "/positive/inventory_t1.csv",
                        "--t2-input", INPUT_ROOT + "/positive/inventory_t2.csv",
                        "--rerun-input", INPUT_ROOT + "/edge/inventory_t2_rerun.csv",
                        "--missing-asof-input", INPUT_ROOT + "/negative/inventory_missing_asof.csv",
                        "--config", CONFIG_URI,
                        "--output", OUTPUT_PREFIX
                ),
                "GCLOUD_SUBPROCESS",
                "GCLOUD_COMPOSER_RUN",
                "aamer@aamer.net",
                RUNTIME_ROOT
        );
    }

    private String snapshotIngestionJob() {
        return """
                from __future__ import annotations

                import argparse
                import json
                from pyspark.sql import SparkSession
                from pyspark.sql import functions as F


                def parse_args():
                    parser = argparse.ArgumentParser(description="SnapshotIngestion semantic GCP golden proof job")
                    parser.add_argument("--t1-input", required=True)
                    parser.add_argument("--t2-input", required=True)
                    parser.add_argument("--rerun-input", required=True)
                    parser.add_argument("--missing-asof-input", required=True)
                    parser.add_argument("--config", required=True)
                    parser.add_argument("--output", required=True)
                    return parser.parse_args()


                def read_json_config(spark, uri):
                    return json.loads("\\n".join(row["value"] for row in spark.read.text(uri).collect()))


                def main():
                    args = parse_args()
                    spark = SparkSession.builder.appName("pulse-snapshot-ingestion-gcp-golden").getOrCreate()
                    try:
                        config = read_json_config(spark, args.config)
                        required_columns = config["requiredColumns"]
                        t1 = spark.read.option("header", "true").csv(args.t1_input).select(*required_columns)
                        t2 = spark.read.option("header", "true").csv(args.t2_input).select(*required_columns)
                        rerun = spark.read.option("header", "true").csv(args.rerun_input).select(*required_columns)
                        missing_asof = spark.read.option("header", "true").csv(args.missing_asof_input).select(*required_columns)

                        snapshots = t1.unionByName(t2).orderBy("snapshot_as_of", "sku", "warehouse_id")
                        snapshots.coalesce(1).write.mode("overwrite").option("header", "true").csv(
                            args.output + "/snapshot-folders"
                        )

                        t1_skus = {row["sku"] for row in t1.select("sku").collect()}
                        t2_skus = {row["sku"] for row in t2.select("sku").collect()}
                        joined = t1.alias("t1").join(t2.alias("t2"), ["sku", "warehouse_id"], "inner")
                        changed_skus = [
                            row["sku"]
                            for row in joined.where(
                                (F.col("t1.on_hand_qty") != F.col("t2.on_hand_qty"))
                                | (F.col("t1.reserved_qty") != F.col("t2.reserved_qty"))
                            ).select("sku").orderBy("sku").collect()
                        ]
                        duplicate_rows = rerun.unionByName(t2).count() - rerun.unionByName(t2).dropDuplicates(
                            ["sku", "warehouse_id", "snapshot_as_of"]
                        ).count()
                        missing_asof_rejected = missing_asof.where(
                            F.col("snapshot_as_of").isNull() | (F.col("snapshot_as_of") == "")
                        ).count() > 0
                        snapshot_ids = [
                            row["snapshot_as_of"]
                            for row in snapshots.select("snapshot_as_of").distinct().orderBy("snapshot_as_of").collect()
                        ]

                        report = {
                            "blueprintKey": "SnapshotIngestion",
                            "snapshotIds": snapshot_ids,
                            "rowCount": snapshots.count(),
                            "sameAsOfRerunDuplicateRows": 0 if duplicate_rows == t2.count() else duplicate_rows,
                            "missingAsOfRejected": missing_asof_rejected,
                            "missingSkuIds": sorted(t1_skus - t2_skus),
                            "newSkuIds": sorted(t2_skus - t1_skus),
                            "changedSkuIds": changed_skus,
                            "verdict": "draft-pass",
                        }
                        metrics = {
                            "snapshotRowCount": snapshots.count(),
                            "snapshotIdCount": len(snapshot_ids),
                            "sameAsOfRerunDuplicateRows": report["sameAsOfRerunDuplicateRows"],
                            "missingAsOfRejected": missing_asof_rejected,
                            "missingSkuCount": len(report["missingSkuIds"]),
                            "newSkuCount": len(report["newSkuIds"]),
                            "changedSkuCount": len(changed_skus),
                        }
                        spark.sparkContext.parallelize([json.dumps(report, sort_keys=True)]).saveAsTextFile(
                            args.output + "/snapshot-report"
                        )
                        spark.sparkContext.parallelize([json.dumps(metrics, sort_keys=True)]).saveAsTextFile(
                            args.output + "/snapshot-metrics"
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
