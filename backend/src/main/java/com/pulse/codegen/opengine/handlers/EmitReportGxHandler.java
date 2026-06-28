package com.pulse.codegen.opengine.handlers;

import com.pulse.codegen.opengine.EmissionEngine;
import com.pulse.codegen.opengine.EmitContext;
import com.pulse.codegen.opengine.OpEmitHandler;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;

/**
 * Great-Expectations emission for {@code emit-report} (SPEC #2 §C.1).
 *
 * <p>Writes the DQ / validation report DataFrame to the report sink.
 *
 * <p><b>FIX #7 — report writes APPEND by default.</b> The legacy code wrote DQ
 * reports with {@code mode('overwrite').format('delta')} (CodeGenerationService
 * line ~4281), clobbering prior report runs. The corrected default {@code report_mode}
 * is {@code append} so each run's report is retained. {@code report_mode} may be
 * overridden to {@code overwrite}, but when unset it MUST be {@code append}.
 *
 * <p><b>Mode-aware format</b> (via {@link com.pulse.codegen.opengine.ModeResolver#fileFormatFor}):
 * {@code iceberg} (GCP bronze/silver) / {@code bq_native} (GCP gold) / {@code parquet}
 * (DPC). <b>Never {@code delta}</b> (C-2).
 */
public final class EmitReportGxHandler implements OpEmitHandler {

    @Override
    public String opName() {
        return OpVocabulary.EMIT_REPORT;
    }

    @Override
    public EmissionEngine engine() {
        return EmissionEngine.GX;
    }

    @Override
    public String emit(EmitContext ctx) {
        ResolvedConfig config = ctx.config();
        // FIX #7: default report_mode is 'append' (NOT the legacy 'overwrite').
        String reportMode = orDefault(config.getString("report_mode"), "append");
        String reportPath = orDefault(config.getString("report_path"), "");
        // Mode-aware format; never 'delta'.
        String format = ctx.modeResolver().fileFormatFor(ctx.mode(), ctx.lakeLayer());

        StringBuilder py = new StringBuilder();
        py.append("# emit-report: write the DQ report (FIX #7: append by default; Mode-aware format).\n");
        py.append("# Mode=").append(ctx.mode())
                .append(", layer=").append(ctx.lakeLayer())
                .append(", format=").append(format)
                .append(", report_mode=").append(reportMode).append("\n");
        py.append("import os\n");
        py.append("report_path = os.environ.get('PULSE_REPORT_URI', ")
                .append(pyStr(reportPath)).append(")\n");
        py.append("report_df.write.mode(").append(pyStr(reportMode)).append(")")
                .append(".format(").append(pyStr(format)).append(")")
                .append(".save(report_path)\n");
        return py.toString();
    }

    private static String orDefault(String v, String dflt) {
        return (v == null || v.isBlank()) ? dflt : v;
    }

    private static String pyStr(String v) {
        String s = v == null ? "" : v;
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}
