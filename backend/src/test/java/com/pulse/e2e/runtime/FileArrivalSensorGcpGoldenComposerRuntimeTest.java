package com.pulse.e2e.runtime;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileArrivalSensorGcpGoldenComposerRuntimeTest {

    private static final String RUN_ID = "run-20260506-1110";
    private static final String NAMESPACE = "tenants/home-lending/pipelines/loan-master";
    private static final Path RUNTIME_ROOT = Path.of("build/e2e-airflow-runtime");
    private static final Path EVIDENCE_ROOT = Path.of("build/e2e-semantic-hardening/gcp-golden")
            .resolve("file-arrival-sensor")
            .resolve(RUN_ID)
            .resolve("deploy");

    @Test
    void materializeFileArrivalSensorGcpGoldenComposerDag() throws Exception {
        Path namespacedRoot = RUNTIME_ROOT.resolve(NAMESPACE);
        Path dag = namespacedRoot.resolve("dags/file_arrival_sensor_gcp_dag.py");
        Files.createDirectories(dag.getParent());
        Files.writeString(dag, dagSource());

        Files.createDirectories(EVIDENCE_ROOT.resolve("runtime/composer-gcs"));
        Files.writeString(EVIDENCE_ROOT.resolve("runtime/composer-gcs/file_arrival_sensor_gcp_dag.py"), dagSource());
        Files.writeString(EVIDENCE_ROOT.resolve("gcp-composer-gcs-sensor-plan.json"), """
                {
                  "scenarioId": "file-arrival-sensor-gcp-golden",
                  "generationRunId": "run-20260506-1110",
                  "composerDagId": "pulse_semantic_gcp_file_arrival_sensor",
                  "composerDagRunId": "pulse_semantic__file_arrival_sensor_run_20260506_1110",
                  "sensorTaskId": "wait_for_partner_daily_file",
                  "downstreamTaskId": "mark_file_ready",
                  "bucket": "pulse-home-lending-dev-files",
                  "expectedObject": "semantic/file-arrival-sensor/run-20260506-1110/expected/partner_daily_20260506.csv",
                  "wrongPrefixObject": "semantic/file-arrival-sensor/run-20260506-1110/wrong-prefix/partner_daily_20260506.csv",
                  "missingObject": "semantic/file-arrival-sensor/run-20260506-1110/missing/partner_daily_20260506.csv"
                }
                """);

        String renderedDag = Files.readString(dag);
        assertTrue(renderedDag.contains("GCSObjectExistenceSensor"));
        assertTrue(renderedDag.contains("pulse_semantic_gcp_file_arrival_sensor"));
        assertTrue(renderedDag.contains("wait_for_partner_daily_file"));
        assertTrue(renderedDag.contains("mark_file_ready"));
        assertFalse(renderedDag.contains("DataprocCreateBatchOperator"));
        assertFalse(renderedDag.contains("BashOperator"));
    }

    private String dagSource() {
        return """
                from __future__ import annotations

                import pendulum
                from airflow import DAG
                from airflow.operators.empty import EmptyOperator
                from airflow.providers.google.cloud.sensors.gcs import GCSObjectExistenceSensor

                DAG_ID = "pulse_semantic_gcp_file_arrival_sensor"
                BUCKET = "pulse-home-lending-dev-files"
                EXPECTED_OBJECT = "semantic/file-arrival-sensor/run-20260506-1110/expected/partner_daily_20260506.csv"

                with DAG(
                    dag_id=DAG_ID,
                    start_date=pendulum.datetime(2026, 1, 1, tz="UTC"),
                    schedule=None,
                    catchup=False,
                    max_active_runs=1,
                    tags=["pulse", "semantic-proof", "gcp-composer-gcs-sensor"],
                ) as dag:
                    wait_for_partner_daily_file = GCSObjectExistenceSensor(
                        task_id="wait_for_partner_daily_file",
                        bucket=BUCKET,
                        object=EXPECTED_OBJECT,
                        poke_interval=10,
                        timeout=120,
                        mode="reschedule",
                        soft_fail=False,
                        google_cloud_conn_id="google_cloud_default",
                    )

                    mark_file_ready = EmptyOperator(task_id="mark_file_ready")

                    wait_for_partner_daily_file >> mark_file_ready
                """;
    }
}
