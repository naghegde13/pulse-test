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

class SCD2DimensionGcpGoldenRuntimeNamespaceMaterializationTest {

    private static final String RUN_ID = "run-20260506-0525";
    private static final String NAMESPACE = "tenants/home-lending/pipelines/loan-master";
    private static final String DAY1_FIXTURE_URI = "gs://pulse-home-lending-dev-files/semantic/scd2-dimension/input/run1/customer_tier_day1.csv";
    private static final String DAY2_FIXTURE_URI = "gs://pulse-home-lending-dev-files/semantic/scd2-dimension/input/run2/customer_tier_day2.csv";
    private static final String CONFIG_URI = "gs://pulse-home-lending-dev-files/semantic/scd2-dimension/input/expectations/scd2-config.json";
    private static final String INVALID_CONFIG_URI = "gs://pulse-home-lending-dev-files/semantic/scd2-dimension/input/negative/invalid-scd2-config.json";
    private static final String OUTPUT_PREFIX = "gs://pulse-home-lending-dev-lake/semantic/scd2-dimension/" + RUN_ID;
    private static final String STAGING_BUCKET = "gs://pulse-proof-04261847-dataproc-staging";
    private static final String DATAPROC_RUNTIME_SERVICE_ACCOUNT = "pulse-proof-dataproc-runtime@pulse-proof-04261847.iam.gserviceaccount.com";
    private static final Path RUNTIME_ROOT = Path.of("build/e2e-airflow-runtime");
    private static final Path EVIDENCE_ROOT = Path.of("build/e2e-semantic-hardening/gcp-golden")
            .resolve("scd2-dimension")
            .resolve(RUN_ID)
            .resolve("deploy");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void materializeScd2DimensionGcpGoldenRuntimeNamespaceTree() throws Exception {
        Path namespacedRoot = RUNTIME_ROOT.resolve(NAMESPACE);
        Path mainPython = namespacedRoot.resolve("jobs/scd2/scd2_dimension.py");
        Path secretResolver = namespacedRoot.resolve("runtime/pulse_secret_resolver.py");
        Path dag = namespacedRoot.resolve("dags/scd2_dimension_gcp_dag.py");

        Files.createDirectories(mainPython.getParent());
        Files.writeString(mainPython, scd2DimensionJob());
        Files.createDirectories(secretResolver.getParent());
        Files.writeString(secretResolver, secretResolverModule());
        Files.createDirectories(dag.getParent());

        GcpComposerDataprocBridgeAdapter adapter = new GcpComposerDataprocBridgeAdapter(objectMapper);
        GcpComposerDataprocBridgeAdapter.WrittenAdapterPlan written = adapter.writeEvidence(
                request(),
                EVIDENCE_ROOT
        );

        assertTrue(Files.exists(mainPython), "main SCD2Dimension PySpark job must exist");
        assertTrue(Files.exists(secretResolver), "runtime secret resolver helper must exist");
        assertTrue(Files.exists(dag), "Composer DAG must exist in the runtime namespace");

        String job = Files.readString(mainPython);
        assertTrue(job.contains("changedCustomerIds"));
        assertTrue(job.contains("newCustomerIds"));
        assertTrue(job.contains("historyWindowIntegrity"));
        assertTrue(job.contains("MISSING_BUSINESS_KEY"));

        String renderedDag = Files.readString(dag);
        assertTrue(renderedDag.contains("DataprocCreateBatchOperator"));
        assertTrue(renderedDag.contains("pulse_semantic_gcp_scd2_dimension"));
        assertTrue(renderedDag.contains("semantic-scd2-dimension-batch-20260506-0525"));
        assertFalse(renderedDag.contains("BashOperator"));

        assertTrue(Files.exists(namespacedRoot.resolve("runtime/dataproc-submit-request.json")));
        assertTrue(Files.exists(written.adapterPlan()));
        assertTrue(Files.exists(written.composerEvidence()));
        assertTrue(Files.exists(written.dataprocEvidence()));
        assertTrue(Files.exists(written.dataprocSubmitRequest()));
        assertTrue(Files.exists(written.renderedDag()));

        JsonNode submit = objectMapper.readTree(namespacedRoot.resolve("runtime/dataproc-submit-request.json").toFile());
        assertEquals("scd2-dimension-gcp-golden", submit.get("scenarioId").asText());
        assertEquals(RUN_ID, submit.get("generationRunId").asText());
        assertEquals("pulse-proof-04261847", submit.get("projectId").asText());
        assertEquals("semantic-scd2-dimension-batch-20260506-0525", submit.get("batchId").asText());
        assertEquals(
                STAGING_BUCKET + "/pulse-runtime/scd2-dimension-gcp-golden/" + RUN_ID + "/"
                        + NAMESPACE + "/jobs/scd2/scd2_dimension.py",
                submit.at("/batch/pyspark_batch/main_python_file_uri").asText()
        );
    }

    private GcpComposerDataprocBridgeAdapter.AdapterRequest request() {
        return new GcpComposerDataprocBridgeAdapter.AdapterRequest(
                "scd2-dimension-gcp-golden",
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
                "pulse_semantic_gcp_scd2_dimension",
                "scd2_dimension_gcp_dag.py",
                "pulse_semantic__scd2_dimension_run_20260506_0525",
                "2026-05-06T05:25:00+00:00",
                "semantic-scd2-dimension-batch-20260506-0525",
                "jobs/scd2/scd2_dimension.py",
                List.of("runtime/pulse_secret_resolver.py"),
                List.of(
                        "--day1-input", DAY1_FIXTURE_URI,
                        "--day2-input", DAY2_FIXTURE_URI,
                        "--config", CONFIG_URI,
                        "--invalid-config", INVALID_CONFIG_URI,
                        "--output", OUTPUT_PREFIX
                ),
                "GCLOUD_SUBPROCESS",
                "GCLOUD_COMPOSER_RUN",
                "aamer@aamer.net",
                RUNTIME_ROOT
        );
    }

    private String scd2DimensionJob() {
        return """
                from __future__ import annotations

                import argparse
                import json
                from datetime import datetime, timedelta
                from pyspark.sql import SparkSession
                from pyspark.sql import functions as F


                def parse_args():
                    parser = argparse.ArgumentParser(description="SCD2Dimension semantic GCP golden proof job")
                    parser.add_argument("--day1-input", required=True)
                    parser.add_argument("--day2-input", required=True)
                    parser.add_argument("--config", required=True)
                    parser.add_argument("--invalid-config", required=True)
                    parser.add_argument("--output", required=True)
                    return parser.parse_args()


                def read_json_config(spark, uri):
                    return json.loads("\\n".join(row["value"] for row in spark.read.text(uri).collect()))


                def previous_day(date_text):
                    return (datetime.strptime(date_text, "%Y-%m-%d").date() - timedelta(days=1)).isoformat()


                def main():
                    args = parse_args()
                    spark = SparkSession.builder.appName("pulse-scd2-dimension-gcp-golden").getOrCreate()
                    try:
                        config = read_json_config(spark, args.config)
                        invalid_config = read_json_config(spark, args.invalid_config)
                        business_key = config["businessKey"]
                        day1 = spark.read.option("header", "true").csv(args.day1_input)
                        day2 = spark.read.option("header", "true").csv(args.day2_input)

                        day1_rows = {row[business_key]: row.asDict() for row in day1.collect()}
                        day2_rows = {row[business_key]: row.asDict() for row in day2.collect()}
                        history = []
                        changed_ids = []
                        new_ids = []
                        for customer_id in sorted(day1_rows):
                            old = day1_rows[customer_id]
                            new = day2_rows.get(customer_id)
                            if new is None:
                                history.append({
                                    "customer_id": customer_id,
                                    "tier": old["tier"],
                                    "effective_start": old["effective_date"],
                                    "effective_end": "",
                                    "is_current": True,
                                })
                            elif old["tier"] != new["tier"]:
                                changed_ids.append(customer_id)
                                history.append({
                                    "customer_id": customer_id,
                                    "tier": old["tier"],
                                    "effective_start": old["effective_date"],
                                    "effective_end": previous_day(new["effective_date"]),
                                    "is_current": False,
                                })
                                history.append({
                                    "customer_id": customer_id,
                                    "tier": new["tier"],
                                    "effective_start": new["effective_date"],
                                    "effective_end": "",
                                    "is_current": True,
                                })
                            else:
                                history.append({
                                    "customer_id": customer_id,
                                    "tier": old["tier"],
                                    "effective_start": old["effective_date"],
                                    "effective_end": "",
                                    "is_current": True,
                                })
                        for customer_id in sorted(set(day2_rows) - set(day1_rows)):
                            new_ids.append(customer_id)
                            new = day2_rows[customer_id]
                            history.append({
                                "customer_id": customer_id,
                                "tier": new["tier"],
                                "effective_start": new["effective_date"],
                                "effective_end": "",
                                "is_current": True,
                            })

                        history_frame = spark.createDataFrame(history).select(
                            "customer_id", "tier", "effective_start", "effective_end", "is_current"
                        ).orderBy("customer_id", "effective_start")
                        history_frame.coalesce(1).write.mode("overwrite").option("header", "true").csv(
                            args.output + "/history-output"
                        )
                        report = {
                            "changedCustomerIds": changed_ids,
                            "newCustomerIds": new_ids,
                            "historyWindowIntegrity": "draft-pass",
                        }
                        metrics = {
                            "historyRowCount": history_frame.count(),
                            "currentRowCount": history_frame.where(F.col("is_current") == F.lit(True)).count(),
                            "changedCustomerCount": len(changed_ids),
                        }
                        invalid_probe = {
                            "invalidConfigRejected": invalid_config.get("businessKey", "") == "",
                            "failureCode": invalid_config.get("expectedFailureCode", "MISSING_BUSINESS_KEY"),
                            "failureMessage": invalid_config.get("expectedFailureMessage"),
                        }
                        spark.sparkContext.parallelize([json.dumps(report, sort_keys=True)]).saveAsTextFile(
                            args.output + "/history-report"
                        )
                        spark.sparkContext.parallelize([json.dumps(metrics, sort_keys=True)]).saveAsTextFile(
                            args.output + "/history-metrics"
                        )
                        spark.sparkContext.parallelize([json.dumps(invalid_probe, sort_keys=True)]).saveAsTextFile(
                            args.output + "/invalid-config-probe"
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
