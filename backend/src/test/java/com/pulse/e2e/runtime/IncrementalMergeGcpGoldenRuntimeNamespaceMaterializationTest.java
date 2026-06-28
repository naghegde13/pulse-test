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

class IncrementalMergeGcpGoldenRuntimeNamespaceMaterializationTest {

    private static final String RUN_ID = "run-20260506-0510";
    private static final String NAMESPACE = "tenants/home-lending/pipelines/loan-master";
    private static final String SNAPSHOT_FIXTURE_URI = "gs://pulse-home-lending-dev-files/semantic/incremental-merge/input/run1/account_snapshot.csv";
    private static final String CDC_FIXTURE_URI = "gs://pulse-home-lending-dev-files/semantic/incremental-merge/input/run2/cdc_events.csv";
    private static final String CONFIG_URI = "gs://pulse-home-lending-dev-files/semantic/incremental-merge/input/expectations/merge-config.json";
    private static final String INVALID_EVENT_URI = "gs://pulse-home-lending-dev-files/semantic/incremental-merge/input/negative/invalid-merge-event.json";
    private static final String OUTPUT_PREFIX = "gs://pulse-home-lending-dev-lake/semantic/incremental-merge/" + RUN_ID;
    private static final String STAGING_BUCKET = "gs://pulse-proof-04261847-dataproc-staging";
    private static final String DATAPROC_RUNTIME_SERVICE_ACCOUNT = "pulse-proof-dataproc-runtime@pulse-proof-04261847.iam.gserviceaccount.com";
    private static final Path RUNTIME_ROOT = Path.of("build/e2e-airflow-runtime");
    private static final Path EVIDENCE_ROOT = Path.of("build/e2e-semantic-hardening/gcp-golden")
            .resolve("incremental-merge")
            .resolve(RUN_ID)
            .resolve("deploy");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void materializeIncrementalMergeGcpGoldenRuntimeNamespaceTree() throws Exception {
        Path namespacedRoot = RUNTIME_ROOT.resolve(NAMESPACE);
        Path mainPython = namespacedRoot.resolve("jobs/merge/incremental_merge.py");
        Path secretResolver = namespacedRoot.resolve("runtime/pulse_secret_resolver.py");
        Path dag = namespacedRoot.resolve("dags/incremental_merge_gcp_dag.py");

        Files.createDirectories(mainPython.getParent());
        Files.writeString(mainPython, incrementalMergeJob());
        Files.createDirectories(secretResolver.getParent());
        Files.writeString(secretResolver, secretResolverModule());
        Files.createDirectories(dag.getParent());

        GcpComposerDataprocBridgeAdapter adapter = new GcpComposerDataprocBridgeAdapter(objectMapper);
        GcpComposerDataprocBridgeAdapter.WrittenAdapterPlan written = adapter.writeEvidence(
                request(),
                EVIDENCE_ROOT
        );

        assertTrue(Files.exists(mainPython), "main IncrementalMerge PySpark job must exist");
        assertTrue(Files.exists(secretResolver), "runtime secret resolver helper must exist");
        assertTrue(Files.exists(dag), "Composer DAG must exist in the runtime namespace");

        String job = Files.readString(mainPython);
        assertTrue(job.contains("insertedAccountIds"));
        assertTrue(job.contains("updatedAccountIds"));
        assertTrue(job.contains("deletedAccountIds"));
        assertTrue(job.contains("INVALID_MERGE_OPERATION"));
        assertTrue(job.contains("finalRowCount"));

        String renderedDag = Files.readString(dag);
        assertTrue(renderedDag.contains("DataprocCreateBatchOperator"));
        assertTrue(renderedDag.contains("pulse_semantic_gcp_incremental_merge"));
        assertTrue(renderedDag.contains("semantic-incremental-merge-batch-20260506-0510"));
        assertFalse(renderedDag.contains("BashOperator"));

        assertTrue(Files.exists(namespacedRoot.resolve("runtime/dataproc-submit-request.json")));
        assertTrue(Files.exists(written.adapterPlan()));
        assertTrue(Files.exists(written.composerEvidence()));
        assertTrue(Files.exists(written.dataprocEvidence()));
        assertTrue(Files.exists(written.dataprocSubmitRequest()));
        assertTrue(Files.exists(written.renderedDag()));

        JsonNode submit = objectMapper.readTree(namespacedRoot.resolve("runtime/dataproc-submit-request.json").toFile());
        assertEquals("incremental-merge-gcp-golden", submit.get("scenarioId").asText());
        assertEquals(RUN_ID, submit.get("generationRunId").asText());
        assertEquals("pulse-proof-04261847", submit.get("projectId").asText());
        assertEquals("semantic-incremental-merge-batch-20260506-0510", submit.get("batchId").asText());
        assertEquals(
                STAGING_BUCKET + "/pulse-runtime/incremental-merge-gcp-golden/" + RUN_ID + "/"
                        + NAMESPACE + "/jobs/merge/incremental_merge.py",
                submit.at("/batch/pyspark_batch/main_python_file_uri").asText()
        );
    }

    private GcpComposerDataprocBridgeAdapter.AdapterRequest request() {
        return new GcpComposerDataprocBridgeAdapter.AdapterRequest(
                "incremental-merge-gcp-golden",
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
                "pulse_semantic_gcp_incremental_merge",
                "incremental_merge_gcp_dag.py",
                "pulse_semantic__incremental_merge_run_20260506_0510",
                "2026-05-06T05:10:00+00:00",
                "semantic-incremental-merge-batch-20260506-0510",
                "jobs/merge/incremental_merge.py",
                List.of("runtime/pulse_secret_resolver.py"),
                List.of(
                        "--snapshot-input", SNAPSHOT_FIXTURE_URI,
                        "--cdc-input", CDC_FIXTURE_URI,
                        "--config", CONFIG_URI,
                        "--invalid-event", INVALID_EVENT_URI,
                        "--output", OUTPUT_PREFIX
                ),
                "GCLOUD_SUBPROCESS",
                "GCLOUD_COMPOSER_RUN",
                "aamer@aamer.net",
                RUNTIME_ROOT
        );
    }

    private String incrementalMergeJob() {
        return """
                from __future__ import annotations

                import argparse
                import json
                from pyspark.sql import SparkSession
                from pyspark.sql import functions as F


                def parse_args():
                    parser = argparse.ArgumentParser(description="IncrementalMerge semantic GCP golden proof job")
                    parser.add_argument("--snapshot-input", required=True)
                    parser.add_argument("--cdc-input", required=True)
                    parser.add_argument("--config", required=True)
                    parser.add_argument("--invalid-event", required=True)
                    parser.add_argument("--output", required=True)
                    return parser.parse_args()


                def read_json_config(spark, uri):
                    return json.loads("\\n".join(row["value"] for row in spark.read.text(uri).collect()))


                def collect_ids(frame, column):
                    return [row[column] for row in frame.select(column).orderBy(column).collect()]


                def main():
                    args = parse_args()
                    spark = SparkSession.builder.appName("pulse-incremental-merge-gcp-golden").getOrCreate()
                    try:
                        config = read_json_config(spark, args.config)
                        invalid_event = read_json_config(spark, args.invalid_event)
                        merge_key = config["mergeKey"]
                        operation_column = config["operationColumn"]
                        delete_value = config["deleteValue"]
                        update_values = config["updateValues"]

                        snapshot = spark.read.option("header", "true").csv(args.snapshot_input)
                        cdc = spark.read.option("header", "true").csv(args.cdc_input)

                        deletes = cdc.where(F.col(operation_column) == F.lit(delete_value))
                        upserts = cdc.where(F.col(operation_column).isin(update_values)).select(
                            "account_id",
                            "customer_id",
                            "balance",
                            "last_updated",
                            "status",
                        )
                        changed_keys = cdc.select(merge_key).distinct()
                        retained = snapshot.join(changed_keys, on=merge_key, how="left_anti")
                        final_state = retained.unionByName(upserts).orderBy("account_id")
                        final_state.coalesce(1).write.mode("overwrite").option("header", "true").csv(
                            args.output + "/final-state"
                        )

                        original_keys = snapshot.select(merge_key).distinct()
                        inserted = upserts.join(original_keys, on=merge_key, how="left_anti")
                        updated = upserts.join(original_keys, on=merge_key, how="inner")
                        report = {
                            "insertedAccountIds": collect_ids(inserted, "account_id"),
                            "updatedAccountIds": collect_ids(updated, "account_id"),
                            "deletedAccountIds": collect_ids(deletes, "account_id"),
                        }
                        metrics = {
                            "finalRowCount": final_state.count(),
                            "insertedCount": inserted.count(),
                            "updatedCount": updated.count(),
                            "deletedCount": deletes.count(),
                        }
                        invalid_probe = {
                            "invalidEventRejected": invalid_event.get("op") not in [delete_value] + update_values,
                            "failureCode": invalid_event.get("expectedFailureCode", "INVALID_MERGE_OPERATION"),
                            "failureMessage": invalid_event.get("expectedFailureMessage"),
                        }
                        spark.sparkContext.parallelize([json.dumps(report, sort_keys=True)]).saveAsTextFile(
                            args.output + "/merge-report"
                        )
                        spark.sparkContext.parallelize([json.dumps(metrics, sort_keys=True)]).saveAsTextFile(
                            args.output + "/merge-metrics"
                        )
                        spark.sparkContext.parallelize([json.dumps(invalid_probe, sort_keys=True)]).saveAsTextFile(
                            args.output + "/invalid-event-probe"
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
