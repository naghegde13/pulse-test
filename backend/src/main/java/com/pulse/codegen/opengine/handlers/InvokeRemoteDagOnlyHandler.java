package com.pulse.codegen.opengine.handlers;

import com.pulse.codegen.opengine.EmissionEngine;
import com.pulse.codegen.opengine.EmitContext;
import com.pulse.codegen.opengine.OpEmitHandler;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;

/**
 * DAG-only emission for the {@code invoke-remote} control op (SPEC #2 §C.1,
 * control-ops-split; ADR 0021 remote pipeline invocation).
 *
 * <p>Emits a {@code RemotePipelineInvocationOperator} that invokes a remote pipeline
 * ({@code remote_pipeline}) at a remote target ({@code remote_target}). A control op
 * emits NO compute artifact — only this distinct Airflow DAG element. Fully
 * deterministic (ADR 0009); not Mode-dependent.
 */
public final class InvokeRemoteDagOnlyHandler implements OpEmitHandler {

    @Override
    public String opName() {
        return OpVocabulary.INVOKE_REMOTE;
    }

    @Override
    public EmissionEngine engine() {
        return EmissionEngine.DAG_ONLY;
    }

    @Override
    public String emit(EmitContext ctx) {
        ResolvedConfig config = ctx.config();
        String taskId = orDefault(config.getString("task_id"), "invoke_remote");
        String remotePipeline = orDefault(config.getString("remote_pipeline"), "");
        String remoteTarget = orDefault(config.getString("remote_target"), "");
        return taskId + " = RemotePipelineInvocationOperator("
                + "task_id='" + taskId + "', "
                + "remote_pipeline='" + remotePipeline + "', "
                + "remote_target='" + remoteTarget + "')";
    }

    private static String orDefault(String v, String dflt) {
        return (v == null || v.isBlank()) ? dflt : v;
    }
}
