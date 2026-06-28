package com.pulse.codegen.opengine.handlers;

import com.pulse.codegen.opengine.EmissionEngine;
import com.pulse.codegen.opengine.EmitContext;
import com.pulse.codegen.opengine.OpEmitHandler;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;

import java.util.List;
import java.util.Map;

/**
 * Great-Expectations emission for {@code check-data} (SPEC #2 §C.1, §C.5).
 *
 * <p>Emits a self-contained ephemeral GX suite + checkpoint over the
 * {@link EmitContext#dfVar() df} DataFrame, built deterministically from the
 * configured {@code expectations} (a list of {@code {type, kwargs}} maps).
 *
 * <p><b>FIX / §C.5 — on_failure=block raises:</b> when {@code on_failure == "block"}
 * (the default) and a check fails, the checkpoint must hard-fail the Airflow task.
 * We emit an explicit {@code assert checkpoint_result.success} plus a {@code raise}
 * so the task is failed rather than silently continuing. {@code warn} only logs.
 *
 * <p><b>Quarantine:</b> when {@code quarantine == true}, failing rows are routed to a
 * managed side-table (a filter-derived DataFrame) written with mode {@code append}
 * (never clobbering prior quarantine batches) before the (optional) raise.
 */
public final class CheckDataGxHandler implements OpEmitHandler {

    @Override
    public String opName() {
        return OpVocabulary.CHECK_DATA;
    }

    @Override
    public EmissionEngine engine() {
        return EmissionEngine.GX;
    }

    @Override
    public String emit(EmitContext ctx) {
        ResolvedConfig config = ctx.config();
        String df = ctx.dfVar();
        String onFailure = orDefault(config.getString("on_failure"), "block");
        boolean quarantine = config.getBool("quarantine", false);
        List<Map<String, Object>> expectations = config.getMapList("expectations");
        // Quarantine writes the GX-rejected rows; reuse the Mode-aware format (never delta).
        String quarantineFormat = ctx.modeResolver().fileFormatFor(ctx.mode(), ctx.lakeLayer());

        StringBuilder py = new StringBuilder();
        py.append("# check-data: Great Expectations checkpoint over '").append(df).append("'")
                .append(" (on_failure=").append(onFailure)
                .append(", quarantine=").append(quarantine).append(").\n");
        py.append("import os\n");
        py.append("import great_expectations as gx\n\n");

        py.append("gx_context = gx.get_context(mode=\"ephemeral\")\n");
        py.append("gx_ds = gx_context.data_sources.add_spark(name=\"pulse_spark\")\n");
        py.append("gx_asset = gx_ds.add_dataframe_asset(name=\"check_data_asset\")\n");
        py.append("gx_batch_def = gx_asset.add_batch_definition_whole_dataframe(name=\"check_data_batch\")\n");
        py.append("gx_suite = gx.ExpectationSuite(name=\"check_data_suite\")\n");

        for (Map<String, Object> exp : expectations) {
            Object typeObj = exp.get("type");
            if (typeObj == null || typeObj.toString().isBlank()) {
                continue;
            }
            String type = typeObj.toString();
            Object kwObj = exp.get("kwargs");
            Map<?, ?> kwargs = (kwObj instanceof Map<?, ?> m) ? m : Map.of();
            py.append("gx_suite.add_expectation(gx.expectations.").append(type).append("(")
                    .append(renderKwargs(kwargs)).append("))\n");
        }

        py.append("gx_suite = gx_context.suites.add(gx_suite)\n");
        py.append("gx_val_def = gx_context.validation_definitions.add(\n");
        py.append("    gx.ValidationDefinition(name=\"check_data_validation\", data=gx_batch_def, suite=gx_suite))\n");
        py.append("gx_checkpoint = gx_context.checkpoints.add(\n");
        py.append("    gx.Checkpoint(name=\"check_data_checkpoint\", validation_definitions=[gx_val_def]))\n");
        py.append("checkpoint_result = gx_checkpoint.run(batch_parameters={\"dataframe\": ")
                .append(df).append("})\n");
        py.append("print(f\"check-data: success={checkpoint_result.success}\")\n\n");

        // Failure handling.
        py.append("if not checkpoint_result.success:\n");
        if (quarantine) {
            // Route failing rows to a managed quarantine side-table, append (never overwrite).
            py.append("    # Quarantine: route rejected rows to the managed side-table (append).\n");
            py.append("    quarantine_path = os.environ.get('PULSE_QUARANTINE_URI', '')\n");
            py.append("    ").append(df).append("_quarantine = ").append(df)
                    .append(".filter(\"_pulse_dq_failed = true\")\n");
            py.append("    ").append(df).append("_quarantine.write.mode('append').format(")
                    .append(pyStr(quarantineFormat)).append(").save(quarantine_path)\n");
        }
        if ("warn".equalsIgnoreCase(onFailure)) {
            py.append("    print(\"check-data WARNING: expectations failed but on_failure=warn; continuing.\")\n");
        } else {
            // on_failure=block (default): hard-fail the Airflow task.
            py.append("    assert checkpoint_result.success, \"check-data: GX expectations failed (on_failure=block)\"\n");
            py.append("    raise Exception(\"check-data: GX expectations failed; failing the task (on_failure=block).\")\n");
        }
        return py.toString();
    }

    private static String renderKwargs(Map<?, ?> kwargs) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<?, ?> e : kwargs.entrySet()) {
            if (e.getKey() == null) {
                continue;
            }
            if (!first) {
                sb.append(", ");
            }
            sb.append(e.getKey()).append("=").append(pyLiteral(e.getValue()));
            first = false;
        }
        return sb.toString();
    }

    private static String pyLiteral(Object value) {
        if (value == null) return "None";
        if (value instanceof Boolean b) return b ? "True" : "False";
        if (value instanceof Number) return value.toString();
        return pyStr(value.toString());
    }

    private static String orDefault(String v, String dflt) {
        return (v == null || v.isBlank()) ? dflt : v;
    }

    private static String pyStr(String v) {
        String s = v == null ? "" : v;
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}
