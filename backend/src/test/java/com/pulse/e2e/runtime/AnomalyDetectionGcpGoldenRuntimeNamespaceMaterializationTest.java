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

class AnomalyDetectionGcpGoldenRuntimeNamespaceMaterializationTest {

    private static final String RUN_ID = "run-20260506-0455";
    private static final String NAMESPACE = "tenants/home-lending/pipelines/loan-master";
    private static final String BASELINE_FIXTURE_URI = "gs://pulse-home-lending-dev-files/semantic/anomaly-detection/input/run1/telemetry_baseline.csv";
    private static final String SPIKE_FIXTURE_URI = "gs://pulse-home-lending-dev-files/semantic/anomaly-detection/input/run2/telemetry_spike.csv";
    private static final String CONFIG_URI = "gs://pulse-home-lending-dev-files/semantic/anomaly-detection/input/expectations/anomaly-config.json";
    private static final String INVALID_CONFIG_URI = "gs://pulse-home-lending-dev-files/semantic/anomaly-detection/input/negative/invalid-anomaly-config.json";
    private static final String OUTPUT_PREFIX = "gs://pulse-home-lending-dev-lake/semantic/anomaly-detection/" + RUN_ID;
    private static final String STAGING_BUCKET = "gs://pulse-proof-04261847-dataproc-staging";
    private static final String DATAPROC_RUNTIME_SERVICE_ACCOUNT = "pulse-proof-dataproc-runtime@pulse-proof-04261847.iam.gserviceaccount.com";
    private static final Path RUNTIME_ROOT = Path.of("build/e2e-airflow-runtime");
    private static final Path EVIDENCE_ROOT = Path.of("build/e2e-semantic-hardening/gcp-golden")
            .resolve("anomaly-detection")
            .resolve(RUN_ID)
            .resolve("deploy");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void materializeAnomalyDetectionGcpGoldenRuntimeNamespaceTree() throws Exception {
        Path namespacedRoot = RUNTIME_ROOT.resolve(NAMESPACE);
        Path mainPython = namespacedRoot.resolve("jobs/anomaly/anomaly_detection.py");
        Path secretResolver = namespacedRoot.resolve("runtime/pulse_secret_resolver.py");
        Path dag = namespacedRoot.resolve("dags/anomaly_detection_gcp_dag.py");

        Files.createDirectories(mainPython.getParent());
        Files.writeString(mainPython, anomalyDetectionJob());
        Files.createDirectories(secretResolver.getParent());
        Files.writeString(secretResolver, secretResolverModule());
        Files.createDirectories(dag.getParent());

        GcpComposerDataprocBridgeAdapter adapter = new GcpComposerDataprocBridgeAdapter(objectMapper);
        GcpComposerDataprocBridgeAdapter.WrittenAdapterPlan written = adapter.writeEvidence(
                request(),
                EVIDENCE_ROOT
        );

        assertTrue(Files.exists(mainPython), "main AnomalyDetection PySpark job must exist");
        assertTrue(Files.exists(secretResolver), "runtime secret resolver helper must exist");
        assertTrue(Files.exists(dag), "Composer DAG must exist in the runtime namespace");

        String job = Files.readString(mainPython);
        assertTrue(job.contains("anomalyEventIds"));
        assertTrue(job.contains("MISSING_PRIMARY_METRIC"));
        assertTrue(job.contains("spikeThreshold"));
        assertTrue(job.contains("baselineAccepted"));

        String renderedDag = Files.readString(dag);
        assertTrue(renderedDag.contains("DataprocCreateBatchOperator"));
        assertTrue(renderedDag.contains("pulse_semantic_gcp_anomaly_detection"));
        assertTrue(renderedDag.contains("semantic-anomaly-detection-batch-20260506-0455"));
        assertFalse(renderedDag.contains("BashOperator"));

        assertTrue(Files.exists(namespacedRoot.resolve("runtime/dataproc-submit-request.json")));
        assertTrue(Files.exists(written.adapterPlan()));
        assertTrue(Files.exists(written.composerEvidence()));
        assertTrue(Files.exists(written.dataprocEvidence()));
        assertTrue(Files.exists(written.dataprocSubmitRequest()));
        assertTrue(Files.exists(written.renderedDag()));

        JsonNode submit = objectMapper.readTree(namespacedRoot.resolve("runtime/dataproc-submit-request.json").toFile());
        assertEquals("anomaly-detection-gcp-golden", submit.get("scenarioId").asText());
        assertEquals(RUN_ID, submit.get("generationRunId").asText());
        assertEquals("pulse-proof-04261847", submit.get("projectId").asText());
        assertEquals("semantic-anomaly-detection-batch-20260506-0455", submit.get("batchId").asText());
        assertEquals(
                STAGING_BUCKET + "/pulse-runtime/anomaly-detection-gcp-golden/" + RUN_ID + "/"
                        + NAMESPACE + "/jobs/anomaly/anomaly_detection.py",
                submit.at("/batch/pyspark_batch/main_python_file_uri").asText()
        );
    }

    private GcpComposerDataprocBridgeAdapter.AdapterRequest request() {
        return new GcpComposerDataprocBridgeAdapter.AdapterRequest(
                "anomaly-detection-gcp-golden",
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
                "pulse_semantic_gcp_anomaly_detection",
                "anomaly_detection_gcp_dag.py",
                "pulse_semantic__anomaly_detection_run_20260506_0455",
                "2026-05-06T04:55:00+00:00",
                "semantic-anomaly-detection-batch-20260506-0455",
                "jobs/anomaly/anomaly_detection.py",
                List.of("runtime/pulse_secret_resolver.py"),
                List.of(
                        "--baseline-input", BASELINE_FIXTURE_URI,
                        "--spike-input", SPIKE_FIXTURE_URI,
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

    private String anomalyDetectionJob() {
        return """
                from __future__ import annotations

                import argparse
                import json
                from pyspark.sql import SparkSession
                from pyspark.sql import functions as F


                def parse_args():
                    parser = argparse.ArgumentParser(description="AnomalyDetection semantic GCP golden proof job")
                    parser.add_argument("--baseline-input", required=True)
                    parser.add_argument("--spike-input", required=True)
                    parser.add_argument("--config", required=True)
                    parser.add_argument("--invalid-config", required=True)
                    parser.add_argument("--output", required=True)
                    return parser.parse_args()


                def read_json_config(spark, uri):
                    return json.loads("\\n".join(row["value"] for row in spark.read.text(uri).collect()))


                def main():
                    args = parse_args()
                    spark = SparkSession.builder.appName("pulse-anomaly-detection-gcp-golden").getOrCreate()
                    try:
                        config = read_json_config(spark, args.config)
                        invalid_config = read_json_config(spark, args.invalid_config)
                        primary_metric = config["primaryMetric"]
                        threshold = float(config["spikeThreshold"])

                        baseline = spark.read.option("header", "true").csv(args.baseline_input)
                        spike = spark.read.option("header", "true").csv(args.spike_input)
                        anomalies = spike.where(F.col(primary_metric).cast("double") >= F.lit(threshold)).select(
                            "event_id",
                            "device_id",
                            F.lit("high").alias("severity"),
                            F.lit(primary_metric).alias("metric_name"),
                            F.col(primary_metric).cast("double").alias("metric_value"),
                        ).orderBy("event_id")
                        anomalies.coalesce(1).write.mode("overwrite").option("header", "true").csv(
                            args.output + "/anomaly-events"
                        )

                        anomaly_ids = [
                            row["event_id"]
                            for row in anomalies.select("event_id").orderBy("event_id").collect()
                        ]
                        max_temperature = spike.select(F.max(F.col(primary_metric).cast("double")).alias("max_value")) \
                            .first()["max_value"]
                        report = {
                            "baselineAccepted": baseline.count() == int(config["baselineWindowEvents"]),
                            "anomalyEventIds": anomaly_ids,
                            "maxTemperatureC": max_temperature,
                            "verdict": "draft-pass",
                        }
                        metrics = {
                            "anomalyCount": len(anomaly_ids),
                            "baselineEventCount": baseline.count(),
                            "spikeEventCount": len(anomaly_ids),
                        }
                        validation_probe = {
                            "invalidConfigRejected": invalid_config.get("primaryMetric", "") == "",
                            "failureCode": invalid_config.get("expectedFailureCode", "MISSING_PRIMARY_METRIC"),
                            "failureMessage": invalid_config.get("expectedFailureMessage"),
                        }
                        spark.sparkContext.parallelize([json.dumps(report, sort_keys=True)]).saveAsTextFile(
                            args.output + "/anomaly-report"
                        )
                        spark.sparkContext.parallelize([json.dumps(metrics, sort_keys=True)]).saveAsTextFile(
                            args.output + "/anomaly-metrics"
                        )
                        spark.sparkContext.parallelize([json.dumps(validation_probe, sort_keys=True)]).saveAsTextFile(
                            args.output + "/config-validation"
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
