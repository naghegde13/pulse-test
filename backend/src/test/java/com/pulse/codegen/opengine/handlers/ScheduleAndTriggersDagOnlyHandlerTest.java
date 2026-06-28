package com.pulse.codegen.opengine.handlers;

import com.pulse.codegen.opengine.EmissionEngine;
import com.pulse.codegen.opengine.EmitContext;
import com.pulse.codegen.opengine.Mode;
import com.pulse.pipeline.opengine.ResolvedConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ScheduleAndTriggersDagOnlyHandlerTest {

    private final ScheduleAndTriggersDagOnlyHandler handler = new ScheduleAndTriggersDagOnlyHandler();

    @Test
    void engineIsDagOnly() {
        assertEquals(EmissionEngine.DAG_ONLY, handler.engine());
    }

    @Test
    void emitsTriggerDagRunOperatorPerDownstreamDag() {
        EmitContext ctx = EmitContext.builder()
                .mode(Mode.GCP_PULSE)
                .config(new ResolvedConfig(Map.of(
                        "schedule", "0 6 * * *",
                        "trigger_dags", List.of("downstream_a", "downstream_b"))))
                .build();

        String dag = handler.emit(ctx);
        assertEquals(
                "# schedule_interval='0 6 * * *'\n"
                        + "trigger_downstream_a = TriggerDagRunOperator("
                        + "task_id='trigger_downstream_a', trigger_dag_id='downstream_a')\n"
                        + "trigger_downstream_b = TriggerDagRunOperator("
                        + "task_id='trigger_downstream_b', trigger_dag_id='downstream_b')",
                dag);
        assertTrue(dag.contains("TriggerDagRunOperator"));
    }

    @Test
    void scheduleOnlyEmitsScheduleNote() {
        EmitContext ctx = EmitContext.builder()
                .mode(Mode.GCP_PULSE)
                .config(new ResolvedConfig(Map.of("schedule", "@daily")))
                .build();

        assertEquals("# schedule_interval='@daily'", handler.emit(ctx));
    }

    @Test
    void emptyConfigDegradesToNoneScheduleNote() {
        EmitContext ctx = EmitContext.builder()
                .mode(Mode.GCP_PULSE)
                .config(ResolvedConfig.empty())
                .build();

        assertEquals("# schedule_interval=None", handler.emit(ctx));
    }
}
