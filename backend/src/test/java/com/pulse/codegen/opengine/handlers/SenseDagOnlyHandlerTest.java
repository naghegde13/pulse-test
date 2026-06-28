package com.pulse.codegen.opengine.handlers;

import com.pulse.codegen.opengine.EmissionEngine;
import com.pulse.codegen.opengine.EmitContext;
import com.pulse.codegen.opengine.Mode;
import com.pulse.pipeline.opengine.ResolvedConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SenseDagOnlyHandlerTest {

    private final SenseDagOnlyHandler handler = new SenseDagOnlyHandler();

    @Test
    void engineIsDagOnly() {
        assertEquals(EmissionEngine.DAG_ONLY, handler.engine());
    }

    @Test
    void fileSenseOnGcpEmitsGcsObjectExistenceSensor() {
        EmitContext ctx = EmitContext.builder()
                .mode(Mode.GCP_PULSE)
                .config(new ResolvedConfig(Map.of(
                        "sense_type", "file",
                        "task_id", "wait_for_file",
                        "bucket", "lake-bronze",
                        "object", "inbound/data.csv")))
                .build();

        String dag = handler.emit(ctx);
        assertEquals(
                "wait_for_file = GCSObjectExistenceSensor("
                        + "task_id='wait_for_file', bucket='lake-bronze', object='inbound/data.csv')",
                dag);
    }

    @Test
    void fileSenseOnDpcEmitsFileSensor() {
        EmitContext ctx = EmitContext.builder()
                .mode(Mode.DPC_PULSE)
                .config(new ResolvedConfig(Map.of(
                        "sense_type", "file",
                        "filepath", "/data/inbound/data.csv")))
                .build();

        String dag = handler.emit(ctx);
        assertEquals(
                "sense = FileSensor(task_id='sense', filepath='/data/inbound/data.csv')",
                dag);
    }

    @Test
    void sqlQuerySenseEmitsSqlSensor() {
        EmitContext ctx = EmitContext.builder()
                .mode(Mode.GCP_PULSE)
                .config(new ResolvedConfig(Map.of(
                        "sense_type", "sql_query",
                        "task_id", "wait_for_rows",
                        "conn_id", "warehouse",
                        "sql", "SELECT 1 FROM partitions WHERE ready")))
                .build();

        String dag = handler.emit(ctx);
        assertEquals(
                "wait_for_rows = SqlSensor("
                        + "task_id='wait_for_rows', conn_id='warehouse', "
                        + "sql='SELECT 1 FROM partitions WHERE ready')",
                dag);
    }

    @Test
    void triggerSenseEmitsExternalTaskSensor() {
        EmitContext ctx = EmitContext.builder()
                .mode(Mode.GCP_PULSE)
                .config(new ResolvedConfig(Map.of(
                        "sense_type", "trigger",
                        "task_id", "wait_for_upstream",
                        "external_dag_id", "upstream_dag",
                        "external_task_id", "publish")))
                .build();

        String dag = handler.emit(ctx);
        assertEquals(
                "wait_for_upstream = ExternalTaskSensor("
                        + "task_id='wait_for_upstream', external_dag_id='upstream_dag', "
                        + "external_task_id='publish')",
                dag);
    }
}
