package com.pulse.codegen.opengine.handlers;

import com.pulse.codegen.opengine.EmissionEngine;
import com.pulse.codegen.opengine.EmitContext;
import com.pulse.codegen.opengine.OpEmitHandler;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;

/**
 * DAG-only emission for the {@code advance-time} control op (SPEC #2 §C.1,
 * control-ops-split; ADR 0023 typed temporal dimension).
 *
 * <p>Emits the runtime-backed {@code AdvanceTimeDimensionOperator}. This keeps the
 * op-engine path on the same durable temporal-state contract as the previous
 * hand-written branch while still making the DAG-only control op deterministic.
 */
public final class AdvanceTimeDagOnlyHandler implements OpEmitHandler {

    @Override
    public String opName() {
        return OpVocabulary.ADVANCE_TIME;
    }

    @Override
    public EmissionEngine engine() {
        return EmissionEngine.DAG_ONLY;
    }

    @Override
    public String emit(EmitContext ctx) {
        ResolvedConfig config = ctx.config();
        String taskId = orDefault(config.getString("task_id"), "advance_time");
        String concurrencyPolicy = orDefault(config.getString("concurrency_policy"), "serialized_airflow");

        StringBuilder py = new StringBuilder();
        py.append(taskId).append(" = AdvanceTimeDimensionOperator(\n");
        py.append("    task_id=").append(pyStr(taskId)).append(",\n");
        py.append("    **").append(pyDict(config)).append(",\n");
        if ("serialized_airflow".equals(concurrencyPolicy)) {
            py.append("    pool=").append(pyStr(orDefault(config.getString("pool_name"),
                    "pulse_time_state_" + taskId))).append(",\n");
            py.append("    pool_slots=1,\n");
        }
        py.append(")");
        return py.toString();
    }

    private static String orDefault(String v, String dflt) {
        return (v == null || v.isBlank()) ? dflt : v;
    }

    private static String pyDict(ResolvedConfig config) {
        StringBuilder out = new StringBuilder("{");
        append(out, "state_binding_ref", config.getString("state_binding_ref"));
        append(out, "variable_key", config.getString("variable_key"));
        append(out, "calendar_binding_ref", config.getString("calendar_binding_ref"));
        append(out, "calendar_bundle_uri", config.getString("calendar_bundle_uri"));
        append(out, "calendar_bundle_hash", config.getString("calendar_bundle_hash"));
        append(out, "calendar_id", config.getString("calendar_id"));
        append(out, "advance_mode", "asof_expr");
        append(out, "requested_asof_expr", orDefault(config.getString("advance_to"),
                config.getString("requested_asof_expr")));
        append(out, "replay_policy", config.getString("replay_policy"));
        append(out, "evidence_prefix", config.getString("evidence_prefix"));
        append(out, "initial_value", config.getString("initial_value"));
        append(out, "initialization_policy", config.getString("initialization_policy"));
        append(out, "concurrency_policy", config.getString("concurrency_policy"));
        append(out, "target_scope", config.getString("target_scope"));
        append(out, "grain", config.getString("grain"));
        append(out, "timezone", config.getString("timezone"));
        append(out, "evidence_required", config.getBool("evidence_required", false));
        append(out, "notes_template", config.getString("notes_template"));
        append(out, "source", config.getString("source"));
        append(out, "advanced_by", config.getString("advanced_by"));
        out.append("}");
        return out.toString();
    }

    private static void append(StringBuilder out, String key, Object value) {
        if (out.length() > 1) {
            out.append(", ");
        }
        out.append(pyStr(key)).append(": ");
        if (value == null) {
            out.append("None");
        } else if (value instanceof Boolean b) {
            out.append(b ? "True" : "False");
        } else if (value instanceof Number) {
            out.append(value);
        } else {
            out.append(pyStr(String.valueOf(value)));
        }
    }

    private static String pyStr(String v) {
        String s = v == null ? "" : v;
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}
