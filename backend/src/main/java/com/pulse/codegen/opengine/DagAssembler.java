package com.pulse.codegen.opengine;

import com.pulse.pipeline.opengine.OpList;

import java.util.ArrayList;
import java.util.List;

/**
 * Assembles one Airflow DAG per business pipeline (SPEC #1 §A.5, #2 §C.1, §C.7).
 * A data-Blueprint contributes a task; a control-Blueprint contributes its own
 * Airflow element via the per-op {@link DagOnlyEmitter} (each of the 5 control ops
 * is a distinct element — sensor / schedule-trigger / failure-callback /
 * time-advance / RemotePipelineInvocation). Cross-pipeline dependencies are
 * expressed as data-aware edges using {@code pulse://} dataset URIs.
 *
 * <p>This is the op-engine-level DAG composer; it is Mode-aware via the
 * {@link EmitContext}s the caller supplies (Composer operator flavors on GCP,
 * plain-Airflow on DPC). Deterministic: tasks are emitted in the given order.
 */
public final class DagAssembler {

    private final DagOnlyEmitter dagOnlyEmitter;

    public DagAssembler(DagOnlyEmitter dagOnlyEmitter) {
        this.dagOnlyEmitter = dagOnlyEmitter;
    }

    /** One node in the DAG: either a data task or a control element. */
    public record DagNode(String taskId, OpList.OpEntry controlOp, EmitContext ctx,
                          String dataTaskFragment, List<String> producesUris,
                          List<String> consumesUris) {

        /** A control node (sense/schedule/rollback/advance-time/invoke-remote). */
        public static DagNode control(String taskId, OpList.OpEntry op, EmitContext ctx) {
            return new DagNode(taskId, op, ctx, null, List.of(), List.of());
        }

        /** A data-task node (a data-Blueprint's compute task). */
        public static DagNode dataTask(String taskId, String fragment,
                                       List<String> producesUris, List<String> consumesUris) {
            return new DagNode(taskId, null, null, fragment,
                    producesUris == null ? List.of() : producesUris,
                    consumesUris == null ? List.of() : consumesUris);
        }

        public boolean isControl() { return controlOp != null; }
    }

    /**
     * Assemble the DAG body from ordered nodes. Each control node emits its Airflow
     * element via the DAG-only handler; each data node emits its task fragment plus
     * any {@code outlets=[Dataset(...)]} (producers) it carries. The consumer's
     * {@code schedule=[Dataset(...)]} edges are collected and returned for the DAG header.
     */
    public Result assemble(String dagId, List<DagNode> nodes) {
        StringBuilder body = new StringBuilder();
        List<String> scheduleUris = new ArrayList<>();
        body.append("# DAG: ").append(dagId)
            .append(" — one DAG per business pipeline (PULSE op-engine).\n");
        for (DagNode node : nodes) {
            if (node.isControl()) {
                body.append("# control op: ").append(node.controlOp().op())
                    .append(" -> its own Airflow element\n");
                body.append(dagOnlyEmitter.emit(node.controlOp(), node.ctx()));
            } else {
                body.append("# data task: ").append(node.taskId()).append("\n");
                body.append(node.dataTaskFragment());
                for (String uri : node.producesUris()) {
                    body.append("# producer ").append(DataAwareUri.outlets(uri)).append("\n");
                }
            }
            if (body.length() == 0 || body.charAt(body.length() - 1) != '\n') body.append("\n");
            scheduleUris.addAll(node.consumesUris());
        }
        return new Result(body.toString(), scheduleUris);
    }

    /** The assembled DAG body + the cross-pipeline {@code schedule=} URIs for the header. */
    public record Result(String dagBody, List<String> scheduleUris) {}
}
