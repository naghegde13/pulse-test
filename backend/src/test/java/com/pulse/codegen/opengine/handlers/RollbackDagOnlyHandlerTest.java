package com.pulse.codegen.opengine.handlers;

import com.pulse.codegen.opengine.EmissionEngine;
import com.pulse.codegen.opengine.EmitContext;
import com.pulse.codegen.opengine.Mode;
import com.pulse.pipeline.opengine.ResolvedConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RollbackDagOnlyHandlerTest {

    private final RollbackDagOnlyHandler handler = new RollbackDagOnlyHandler();

    @Test
    void engineIsDagOnly() {
        assertEquals(EmissionEngine.DAG_ONLY, handler.engine());
    }

    @Test
    void emitsRollbackTaskWithOnFailedTriggerRule() {
        EmitContext ctx = EmitContext.builder()
                .mode(Mode.GCP_PULSE)
                .config(new ResolvedConfig(Map.of(
                        "task_id", "rollback",
                        "python_callable", "do_rollback")))
                .build();

        String dag = handler.emit(ctx);
        assertEquals(
                "rollback = PythonOperator("
                        + "task_id='rollback', python_callable=do_rollback, "
                        + "trigger_rule='one_failed')",
                dag);
        assertTrue(dag.contains("trigger_rule='one_failed'"));
    }

    @Test
    void defaultsTaskIdAndCallable() {
        EmitContext ctx = EmitContext.builder()
                .mode(Mode.GCP_PULSE)
                .config(ResolvedConfig.empty())
                .build();

        assertEquals(
                "rollback = PythonOperator("
                        + "task_id='rollback', python_callable=rollback_callable, "
                        + "trigger_rule='one_failed')",
                handler.emit(ctx));
    }
}
