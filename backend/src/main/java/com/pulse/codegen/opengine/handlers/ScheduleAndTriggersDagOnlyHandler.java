package com.pulse.codegen.opengine.handlers;

import com.pulse.codegen.opengine.EmissionEngine;
import com.pulse.codegen.opengine.EmitContext;
import com.pulse.codegen.opengine.OpEmitHandler;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;

import java.util.List;

/**
 * DAG-only emission for the {@code schedule-and-triggers} control op (SPEC #2 §C.1,
 * control-ops-split).
 *
 * <p>Emits the DAG's schedule note and/or one {@code TriggerDagRunOperator} per
 * downstream DAG:
 * <ul>
 *   <li>{@code schedule} (cron) &rarr; a {@code # schedule_interval='<cron>'} note
 *       (the DAG-level schedule belongs on the DAG object, recorded here as a comment).</li>
 *   <li>{@code trigger_dags} (list) &rarr; one
 *       {@code trigger_<dag> = TriggerDagRunOperator(task_id='trigger_<dag>', trigger_dag_id='<dag>')}
 *       per downstream dag, in config order (deterministic, ADR 0009).</li>
 * </ul>
 * With neither configured it degrades to a bare schedule note. Fully deterministic;
 * not Mode-dependent ({@code TriggerDagRunOperator} is the same flavor in both Modes).
 */
public final class ScheduleAndTriggersDagOnlyHandler implements OpEmitHandler {

    @Override
    public String opName() {
        return OpVocabulary.SCHEDULE_AND_TRIGGERS;
    }

    @Override
    public EmissionEngine engine() {
        return EmissionEngine.DAG_ONLY;
    }

    @Override
    public String emit(EmitContext ctx) {
        ResolvedConfig config = ctx.config();
        String schedule = config.getString("schedule");
        List<String> triggerDags = config.getStringList("trigger_dags");

        StringBuilder sb = new StringBuilder();
        if (schedule != null && !schedule.isBlank()) {
            sb.append("# schedule_interval='").append(schedule.trim()).append("'");
        } else {
            sb.append("# schedule_interval=None");
        }
        for (String dag : triggerDags) {
            sb.append("\n")
                    .append("trigger_").append(dag).append(" = TriggerDagRunOperator(")
                    .append("task_id='trigger_").append(dag).append("', ")
                    .append("trigger_dag_id='").append(dag).append("')");
        }
        return sb.toString();
    }
}
