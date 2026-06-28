package com.pulse.codegen.opengine.handlers;

import com.pulse.codegen.opengine.EmissionEngine;
import com.pulse.codegen.opengine.EmitContext;
import com.pulse.codegen.opengine.Mode;
import com.pulse.pipeline.opengine.ResolvedConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AdvanceTimeDagOnlyHandlerTest {

    private final AdvanceTimeDagOnlyHandler handler = new AdvanceTimeDagOnlyHandler();

    @Test
    void engineIsDagOnly() {
        assertEquals(EmissionEngine.DAG_ONLY, handler.engine());
    }

    @Test
    void emitsAdvanceTimeTask() {
        EmitContext ctx = EmitContext.builder()
                .mode(Mode.GCP_PULSE)
                .config(new ResolvedConfig(Map.of(
                        "task_id", "advance_time",
                        "advance_to", "{{ ds }}")))
                .build();

        String dag = handler.emit(ctx);
        assertTrue(dag.contains("advance_time = AdvanceTimeDimensionOperator("));
        assertTrue(dag.contains("task_id='advance_time'"));
        assertTrue(dag.contains("'requested_asof_expr': '{{ ds }}'"));
        assertTrue(dag.contains("pool='pulse_time_state_advance_time'"));
        assertFalse(dag.contains("advance_pipeline_time"));
    }

    @Test
    void defaultsTaskId() {
        EmitContext ctx = EmitContext.builder()
                .mode(Mode.GCP_PULSE)
                .config(ResolvedConfig.empty())
                .build();

        String dag = handler.emit(ctx);
        assertTrue(dag.contains("advance_time = AdvanceTimeDimensionOperator("));
        assertTrue(dag.contains("task_id='advance_time'"));
        assertTrue(dag.contains("pool='pulse_time_state_advance_time'"));
        assertFalse(dag.contains("advance_pipeline_time"));
    }
}
