package com.pulse.codegen.audit;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase 2 — IngestionAuditColumns 7 -> 8 (SPEC #1 §B rule 25, #2 §C.1, C-1). */
class IngestionAuditColumnsTest {

    @Test
    void hasExactlyEightAuditColumns() {
        assertEquals(8, IngestionAuditColumns.NAMES.size());
        assertTrue(IngestionAuditColumns.NAMES.contains("_pulse_dag_id"));
    }

    @Test
    void phantomCreatedAsTimestampIsAbsent() {
        assertFalse(IngestionAuditColumns.NAMES.contains("created_as_timestamp"));
    }

    @Test
    void descriptorsMatchNamesCountAndOrder() {
        List<Map<String, Object>> descs = IngestionAuditColumns.asColumnDescriptors();
        assertEquals(8, descs.size());
        // the new column appears in the design-time descriptors
        boolean hasDag = descs.stream().anyMatch(d -> "_pulse_dag_id".equals(d.get("name")));
        assertTrue(hasDag);
    }

    @Test
    void emitPysparkEmitsLiveTaskRunAndDagTemplates() {
        StringBuilder py = new StringBuilder();
        IngestionAuditColumns.emitPyspark(py, "my_pipeline", "my_task",
                "airflow_run_time", IngestionAuditColumns.SourceContext.GENERIC);
        String out = py.toString();
        // _pulse_task is now LIVE (env-var fallback to {{ task.task_id }}), not the baked slug.
        assertTrue(out.contains("os.environ.get('PULSE_TASK_ID', '{{ task.task_id }}')"),
                "expected live _pulse_task template, got:\n" + out);
        // _pulse_run_id stays live.
        assertTrue(out.contains("os.environ.get('PULSE_RUN_ID', '{{ run_id }}')"));
        // _pulse_dag_id is new + live.
        assertTrue(out.contains("os.environ.get('PULSE_DAG_ID', '{{ dag.dag_id }}')"));
        // _pulse_task is NOT the baked slug literal anymore.
        assertFalse(out.contains(".withColumn('_pulse_task', lit('my_task'))"));
        // _pulse_pipeline stays the baked slug.
        assertTrue(out.contains(".withColumn('_pulse_pipeline', lit('my_pipeline'))"));
    }
}
