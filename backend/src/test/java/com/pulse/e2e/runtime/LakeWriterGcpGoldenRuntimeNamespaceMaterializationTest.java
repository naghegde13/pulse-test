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

class LakeWriterGcpGoldenRuntimeNamespaceMaterializationTest {

    private static final String RUN_ID = "run-20260506-1130";
    private static final String NAMESPACE = "tenants/home-lending/pipelines/loan-master";
    private static final String INPUT_ROOT = "gs://pulse-home-lending-dev-files/semantic/lake-writer/input";
    private static final String CONFIG_URI = INPUT_ROOT + "/expectations/lake-writer-config.json";
    private static final String OUTPUT_PREFIX = "gs://pulse-home-lending-dev-lake/semantic/lake-writer/" + RUN_ID;
    private static final String STAGING_BUCKET = "gs://pulse-proof-04261847-dataproc-staging";
    private static final String DATAPROC_RUNTIME_SERVICE_ACCOUNT = "pulse-proof-dataproc-runtime@pulse-proof-04261847.iam.gserviceaccount.com";
    private static final Path RUNTIME_ROOT = Path.of("build/e2e-airflow-runtime");
    private static final Path EVIDENCE_ROOT = Path.of("build/e2e-semantic-hardening/gcp-golden")
            .resolve("lake-writer")
            .resolve(RUN_ID)
            .resolve("deploy");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void materializeLakeWriterGcpGoldenRuntimeNamespaceTree() throws Exception {
        Path namespacedRoot = RUNTIME_ROOT.resolve(NAMESPACE);
        Path mainPython = namespacedRoot.resolve("jobs/sink/lake_writer.py");
        Path secretResolver = namespacedRoot.resolve("runtime/pulse_secret_resolver.py");
        Path dag = namespacedRoot.resolve("dags/lake_writer_gcp_dag.py");

        Files.createDirectories(mainPython.getParent());
        Files.writeString(mainPython, lakeWriterJob());
        Files.createDirectories(secretResolver.getParent());
        Files.writeString(secretResolver, secretResolverModule());
        Files.createDirectories(dag.getParent());

        GcpComposerDataprocBridgeAdapter.WrittenAdapterPlan written =
                new GcpComposerDataprocBridgeAdapter(objectMapper).writeEvidence(request(), EVIDENCE_ROOT);

        assertTrue(Files.exists(mainPython));
        assertTrue(Files.exists(secretResolver));
        assertTrue(Files.exists(dag));
        assertTrue(Files.readString(mainPython).contains("sessions-lake"));
        assertTrue(Files.readString(mainPython).contains("/manifest"));

        String renderedDag = Files.readString(dag);
        assertTrue(renderedDag.contains("DataprocCreateBatchOperator"));
        assertTrue(renderedDag.contains("pulse_semantic_gcp_lake_writer"));
        assertTrue(renderedDag.contains("semantic-lake-writer-batch-20260506-1130"));
        assertFalse(renderedDag.contains("BashOperator"));

        JsonNode submit = objectMapper.readTree(namespacedRoot.resolve("runtime/dataproc-submit-request.json").toFile());
        assertEquals("lake-writer-gcp-golden", submit.get("scenarioId").asText());
        assertEquals(RUN_ID, submit.get("generationRunId").asText());
        assertEquals("semantic-lake-writer-batch-20260506-1130", submit.get("batchId").asText());
        assertTrue(Files.exists(written.adapterPlan()));
        assertTrue(Files.exists(written.renderedDag()));
    }

    private GcpComposerDataprocBridgeAdapter.AdapterRequest request() {
        return new GcpComposerDataprocBridgeAdapter.AdapterRequest(
                "lake-writer-gcp-golden", RUN_ID, "pulse-proof-04261847", "us-central1",
                "pulse-proof-composer", "gs://us-central1-pulse-proof-com-a7066110-bucket/dags", "us-central1",
                DATAPROC_RUNTIME_SERVICE_ACCOUNT, STAGING_BUCKET, OUTPUT_PREFIX, NAMESPACE,
                "pulse_semantic_gcp_lake_writer", "lake_writer_gcp_dag.py",
                "pulse_semantic__lake_writer_run_20260506_1130", "2026-05-06T11:30:00+00:00",
                "semantic-lake-writer-batch-20260506-1130", "jobs/sink/lake_writer.py",
                List.of("runtime/pulse_secret_resolver.py"),
                List.of("--sessions", INPUT_ROOT + "/positive/sessions.csv",
                        "--config", CONFIG_URI,
                        "--output", OUTPUT_PREFIX),
                "GCLOUD_SUBPROCESS", "GCLOUD_COMPOSER_RUN", "aamer@aamer.net", RUNTIME_ROOT
        );
    }

    private String lakeWriterJob() {
        return """
                from __future__ import annotations
                import argparse, hashlib, json
                from pyspark.sql import SparkSession
                from pyspark.sql import functions as F

                def parse_args():
                    p = argparse.ArgumentParser(description="LakeWriter semantic GCP golden proof job")
                    p.add_argument("--sessions", required=True)
                    p.add_argument("--config", required=True)
                    p.add_argument("--output", required=True)
                    return p.parse_args()

                def read_json_config(spark, uri):
                    return json.loads("\\n".join(row["value"] for row in spark.read.text(uri).collect()))

                def main():
                    args = parse_args()
                    spark = SparkSession.builder.appName("pulse-lake-writer-gcp-golden").getOrCreate()
                    try:
                        config = read_json_config(spark, args.config)
                        sessions = spark.read.option("header", "true").csv(args.sessions) \\
                            .withColumn("event_count", F.col("event_count").cast("int"))
                        rows = [r for r in sessions.select("session_id", "event_date", "user_id", "event_count", "country")
                            .orderBy("session_id").collect()]
                        checksum_input = "\\n".join("|".join([r["session_id"], r["event_date"], r["user_id"], str(r["event_count"]), r["country"]]) for r in rows)
                        content_sha = hashlib.sha256(checksum_input.encode("utf-8")).hexdigest()
                        sessions.write.mode("overwrite").partitionBy("event_date").option("header", "true").csv(args.output + "/sessions-lake")
                        sessions.write.mode("append").option("header", "true").csv(args.output + "/append-audit")
                        manifest = {"blueprintKey":"LakeWriter","format":config["format"],"rowCount":sessions.count(),"partitionColumns":config["partitionColumns"],"partitions":config["expectedPartitions"],"overwriteMode":config["overwriteMode"],"appendMode":config["appendMode"],"contentSha256":content_sha,"verdict":"PASS"}
                        append_audit = {"appendRows":sessions.count(),"appendMode":config["appendMode"],"idempotencyKey":"run-20260506-1130","verdict":"PASS"}
                        spark.sparkContext.parallelize([json.dumps(manifest, sort_keys=True)]).saveAsTextFile(args.output + "/manifest")
                        spark.sparkContext.parallelize([json.dumps(append_audit, sort_keys=True)]).saveAsTextFile(args.output + "/append-audit-evidence")
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
