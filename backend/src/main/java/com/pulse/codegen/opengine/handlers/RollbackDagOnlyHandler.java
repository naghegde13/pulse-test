package com.pulse.codegen.opengine.handlers;

import com.pulse.codegen.opengine.EmissionEngine;
import com.pulse.codegen.opengine.EmitContext;
import com.pulse.codegen.opengine.OpEmitHandler;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;

/**
 * DAG-only emission for the {@code rollback} control op (SPEC #2 §C.1,
 * control-ops-split; ADR 0020 rollback = redeploy last-good).
 *
 * <p>Emits an Airflow rollback task wired to fire on upstream failure: a
 * {@code PythonOperator} with {@code trigger_rule='one_failed'} so it runs when any
 * upstream task fails (the failure-callback / rollback element of the DAG). The
 * {@code python_callable} is the deterministic {@code rollback_callable} reference.
 * Fully deterministic (ADR 0009); not Mode-dependent.
 */
public final class RollbackDagOnlyHandler implements OpEmitHandler {

    @Override
    public String opName() {
        return OpVocabulary.ROLLBACK;
    }

    @Override
    public EmissionEngine engine() {
        return EmissionEngine.DAG_ONLY;
    }

    @Override
    public String emit(EmitContext ctx) {
        ResolvedConfig config = ctx.config();
        String taskId = orDefault(config.getString("task_id"), "rollback");
        String callable = orDefault(config.getString("python_callable"), "rollback_callable");
        return taskId + " = PythonOperator("
                + "task_id='" + taskId + "', "
                + "python_callable=" + callable + ", "
                + "trigger_rule='one_failed')";
    }

    private static String orDefault(String v, String dflt) {
        return (v == null || v.isBlank()) ? dflt : v;
    }
}
