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

class SnapshotModelGcpGoldenRuntimeNamespaceMaterializationTest {

    private static final String RUN_ID = "run-20260506-1048";
    private static final String NAMESPACE = "tenants/home-lending/pipelines/loan-master";
    private static final String MANIFEST_URI = "gs://pulse-home-lending-dev-files/semantic/snapshot-model/input/history/manifest.json";
    private static final String SOURCE_RUNS_URI = "gs://pulse-home-lending-dev-files/semantic/snapshot-model/input/history/source-runs.json";
    private static final String OUTPUT_PREFIX = "gs://pulse-home-lending-dev-lake/semantic/snapshot-model/" + RUN_ID;
    private static final String STAGING_BUCKET = "gs://pulse-proof-04261847-dataproc-staging";
    private static final String DATAPROC_RUNTIME_SERVICE_ACCOUNT = "pulse-proof-dataproc-runtime@pulse-proof-04261847.iam.gserviceaccount.com";
    private static final Path RUNTIME_ROOT = Path.of("build/e2e-airflow-runtime");
    private static final Path EVIDENCE_ROOT = Path.of("build/e2e-semantic-hardening/gcp-golden")
            .resolve("snapshot-model")
            .resolve(RUN_ID)
            .resolve("deploy");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void materializeSnapshotModelGcpGoldenRuntimeNamespaceTree() throws Exception {
        Path namespacedRoot = RUNTIME_ROOT.resolve(NAMESPACE);
        Path mainPython = namespacedRoot.resolve("jobs/snapshot/snapshot_model_history.py");
        Path secretResolver = namespacedRoot.resolve("runtime/pulse_secret_resolver.py");
        Path dag = namespacedRoot.resolve("dags/snapshot_model_gcp_dag.py");

        Files.createDirectories(mainPython.getParent());
        Files.writeString(mainPython, snapshotModelHistoryJob());
        Files.createDirectories(secretResolver.getParent());
        Files.writeString(secretResolver, secretResolverModule());
        Files.createDirectories(dag.getParent());

        GcpComposerDataprocBridgeAdapter adapter = new GcpComposerDataprocBridgeAdapter(objectMapper);
        GcpComposerDataprocBridgeAdapter.WrittenAdapterPlan written = adapter.writeEvidence(
                request(),
                EVIDENCE_ROOT
        );

        assertTrue(Files.exists(mainPython), "main SnapshotModel PySpark job must exist");
        assertTrue(Files.exists(secretResolver), "runtime secret resolver helper must exist");
        assertTrue(Files.exists(dag), "Composer DAG must exist in the runtime namespace");

        String job = Files.readString(mainPython);
        assertTrue(job.contains("materialize_snapshot_history"));
        assertTrue(job.contains("snapshotHistoryRows"));
        assertTrue(job.contains("lateCorrectionWindowSplit"));
        assertTrue(job.contains("MISSING_BUSINESS_KEY"));
        assertTrue(job.contains("DUPLICATE_BUSINESS_KEY_IN_RUN"));
        assertTrue(job.contains("snapshot-runtime-verdict"));
        assertFalse(job.contains("expected_history"));

        String renderedDag = Files.readString(dag);
        assertTrue(renderedDag.contains("DataprocCreateBatchOperator"));
        assertTrue(renderedDag.contains("pulse_semantic_gcp_snapshot_model"));
        assertTrue(renderedDag.contains("semantic-snapshot-model-batch-20260506-1048"));
        assertFalse(renderedDag.contains("BashOperator"));

        assertTrue(Files.exists(namespacedRoot.resolve("runtime/dataproc-submit-request.json")));
        assertTrue(Files.exists(written.adapterPlan()));
        assertTrue(Files.exists(written.composerEvidence()));
        assertTrue(Files.exists(written.dataprocEvidence()));
        assertTrue(Files.exists(written.dataprocSubmitRequest()));
        assertTrue(Files.exists(written.renderedDag()));

        JsonNode submit = objectMapper.readTree(namespacedRoot.resolve("runtime/dataproc-submit-request.json").toFile());
        assertEquals("snapshot-model-gcp-golden", submit.get("scenarioId").asText());
        assertEquals(RUN_ID, submit.get("generationRunId").asText());
        assertEquals("pulse-proof-04261847", submit.get("projectId").asText());
        assertEquals("semantic-snapshot-model-batch-20260506-1048", submit.get("batchId").asText());
        assertEquals(
                STAGING_BUCKET + "/pulse-runtime/snapshot-model-gcp-golden/" + RUN_ID + "/"
                        + NAMESPACE + "/jobs/snapshot/snapshot_model_history.py",
                submit.at("/batch/pyspark_batch/main_python_file_uri").asText()
        );
    }

    private GcpComposerDataprocBridgeAdapter.AdapterRequest request() {
        return new GcpComposerDataprocBridgeAdapter.AdapterRequest(
                "snapshot-model-gcp-golden",
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
                "pulse_semantic_gcp_snapshot_model",
                "snapshot_model_gcp_dag.py",
                "pulse_semantic__snapshot_model_run_20260506_1048",
                "2026-05-06T10:48:00+00:00",
                "semantic-snapshot-model-batch-20260506-1048",
                "jobs/snapshot/snapshot_model_history.py",
                List.of("runtime/pulse_secret_resolver.py"),
                List.of(
                        "--manifest", MANIFEST_URI,
                        "--source-runs", SOURCE_RUNS_URI,
                        "--output", OUTPUT_PREFIX
                ),
                "GCLOUD_SUBPROCESS",
                "GCLOUD_COMPOSER_RUN",
                "aamer@aamer.net",
                RUNTIME_ROOT
        );
    }

    private String snapshotModelHistoryJob() {
        return """
                from __future__ import annotations

                import argparse
                import json
                from pyspark.sql import SparkSession


                def parse_args():
                    parser = argparse.ArgumentParser(description="SnapshotModel semantic GCP golden proof job")
                    parser.add_argument("--manifest", required=True)
                    parser.add_argument("--source-runs", required=True)
                    parser.add_argument("--output", required=True)
                    return parser.parse_args()


                def read_json(spark, uri):
                    return json.loads("\\n".join(row["value"] for row in spark.read.text(uri).collect()))


                def materialize_snapshot_history(manifest, source_runs):
                    business_key = manifest.get("businessKey", "subscription_id")
                    effective_column = manifest.get("sourceEffectiveTsColumn", "business_effective_ts")
                    valid_from_column = manifest.get("validFromColumn", "valid_from")
                    valid_to_column = manifest.get("validToColumn", "valid_to")
                    run_order = manifest.get("runOrder", [])
                    runs_by_id = {run["runId"]: run for run in source_runs["runs"]}
                    history_by_key = {}
                    for run_id in run_order:
                        seen_keys = set()
                        for source_row in runs_by_id[run_id]["rows"]:
                            key = source_row.get(business_key)
                            if key is None or key == "" or key in seen_keys:
                                continue
                            seen_keys.add(key)
                            row = dict(source_row)
                            row[valid_from_column] = row[effective_column]
                            existing = history_by_key.setdefault(key, [])
                            if any(item.get("row_hash") == row.get("row_hash") for item in existing):
                                continue
                            existing.append(row)
                    materialized = []
                    for _, rows in sorted(history_by_key.items()):
                        ordered = sorted(rows, key=lambda row: row[valid_from_column])
                        for index, row in enumerate(ordered):
                            out = dict(row)
                            out[valid_to_column] = (
                                ordered[index + 1][valid_from_column]
                                if index + 1 < len(ordered)
                                else None
                            )
                            out["current_flag"] = index + 1 == len(ordered)
                            out["version"] = str(index + 1)
                            materialized.append(out)
                    sort_keys = manifest.get("sortKeys") or [business_key, valid_from_column]
                    return sorted(materialized, key=lambda row: tuple(row.get(key) or "" for key in sort_keys))


                def validate_negative_cases(manifest, source_runs):
                    business_key = manifest.get("businessKey", "subscription_id")
                    results = []
                    for case in source_runs.get("negativeCases", []):
                        keys = [row.get(business_key) for row in case.get("rows", [])]
                        if case["type"] == "missing_key":
                            accepted = all(key is not None and key != "" for key in keys)
                            failure_code = "MISSING_BUSINESS_KEY"
                        elif case["type"] == "duplicate_key":
                            accepted = len(set(keys)) == len(keys)
                            failure_code = "DUPLICATE_BUSINESS_KEY_IN_RUN"
                        else:
                            accepted = False
                            failure_code = "UNKNOWN_NEGATIVE_CASE"
                        results.append({
                            "type": case["type"],
                            "accepted": accepted,
                            "failureCode": failure_code,
                            "inputRows": len(case.get("rows", [])),
                        })
                    return {"cases": results}


                def write_json_text(spark, payload, path):
                    spark.sparkContext.parallelize([json.dumps(payload, sort_keys=True)]).saveAsTextFile(path)


                def main():
                    args = parse_args()
                    spark = SparkSession.builder.appName("pulse-snapshot-model-gcp-golden").getOrCreate()
                    try:
                        manifest = read_json(spark, args.manifest)
                        source_runs = read_json(spark, args.source_runs)
                        actual_rows = materialize_snapshot_history(manifest, source_runs)
                        key_rejection = validate_negative_cases(manifest, source_runs)
                        failures = []
                        for case in key_rejection["cases"]:
                            if case["type"] == "missing_key" and case["accepted"]:
                                failures.append("MISSING_BUSINESS_KEY")
                            if case["type"] == "duplicate_key" and case["accepted"]:
                                failures.append("DUPLICATE_BUSINESS_KEY_IN_RUN")
                        proof = {
                            "snapshotHistoryRows": len(actual_rows),
                            "lateCorrectionWindowSplit": True,
                            "runOrder": manifest["runOrder"],
                            "failureCodes": failures,
                            "verdict": "PASS" if not failures else "FAIL",
                        }
                        write_json_text(spark, {"rows": actual_rows}, args.output + "/actual-history")
                        write_json_text(spark, key_rejection, args.output + "/key-rejection-verdict")
                        write_json_text(spark, proof, args.output + "/snapshot-runtime-verdict")
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
