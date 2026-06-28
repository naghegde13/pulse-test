package com.pulse.codegen.opengine.handlers;

import com.pulse.codegen.opengine.EmissionEngine;
import com.pulse.codegen.opengine.EmitContext;
import com.pulse.codegen.opengine.Mode;
import com.pulse.pipeline.opengine.ResolvedConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InvokeRemoteDagOnlyHandlerTest {

    private final InvokeRemoteDagOnlyHandler handler = new InvokeRemoteDagOnlyHandler();

    @Test
    void engineIsDagOnly() {
        assertEquals(EmissionEngine.DAG_ONLY, handler.engine());
    }

    @Test
    void emitsRemotePipelineInvocationOperator() {
        EmitContext ctx = EmitContext.builder()
                .mode(Mode.GCP_PULSE)
                .config(new ResolvedConfig(Map.of(
                        "task_id", "call_remote",
                        "remote_pipeline", "curation_pipeline",
                        "remote_target", "dpc-cluster")))
                .build();

        String dag = handler.emit(ctx);
        assertEquals(
                "call_remote = RemotePipelineInvocationOperator("
                        + "task_id='call_remote', remote_pipeline='curation_pipeline', "
                        + "remote_target='dpc-cluster')",
                dag);
        assertTrue(dag.contains("RemotePipelineInvocationOperator"));
    }

    @Test
    void defaultsTaskId() {
        EmitContext ctx = EmitContext.builder()
                .mode(Mode.GCP_PULSE)
                .config(new ResolvedConfig(Map.of(
                        "remote_pipeline", "p1",
                        "remote_target", "t1")))
                .build();

        assertEquals(
                "invoke_remote = RemotePipelineInvocationOperator("
                        + "task_id='invoke_remote', remote_pipeline='p1', remote_target='t1')",
                handler.emit(ctx));
    }
}
