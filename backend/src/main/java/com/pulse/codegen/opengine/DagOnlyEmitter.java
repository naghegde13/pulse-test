package com.pulse.codegen.opengine;

import com.pulse.pipeline.opengine.OpList;

/**
 * Emits the Airflow DAG element for a control op (SPEC #2 §C.1, control-ops-split):
 * each of the 5 control ops ({@code sense}, {@code schedule-and-triggers},
 * {@code rollback}, {@code advance-time}, {@code invoke-remote}) has its OWN handler
 * producing a distinct Airflow element (sensor / schedule / failure-callback /
 * time-advance task / RemotePipelineInvocation operator). A control op emits NO
 * compute artifact ({@code emission.compute == null}).
 *
 * <p>Mode-aware via {@link EmitContext} (Composer vs plain-Airflow operator flavors).
 */
public final class DagOnlyEmitter {

    private final HandlerRegistry registry;

    public DagOnlyEmitter(HandlerRegistry registry) {
        this.registry = registry;
    }

    /** Emit the DAG element for a single control op. */
    public String emit(OpList.OpEntry controlOp, EmitContext ctx) {
        OpEmitHandler handler = registry.get(controlOp.op(), EmissionEngine.DAG_ONLY);
        return handler.emit(ctx);
    }
}
