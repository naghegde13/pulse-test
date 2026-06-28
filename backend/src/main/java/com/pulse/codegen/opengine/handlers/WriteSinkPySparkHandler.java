package com.pulse.codegen.opengine.handlers;

import com.pulse.codegen.opengine.EmissionEngine;
import com.pulse.codegen.opengine.EmitContext;
import com.pulse.codegen.opengine.OpEmitHandler;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;

/**
 * PySpark emission for {@code write-sink} (SPEC #2 §C.1, §C.2).
 *
 * <p><b>Mode-aware format</b> (via {@link com.pulse.codegen.opengine.ModeResolver#fileFormatFor}):
 * <ul>
 *   <li>GCP bronze/silver &rarr; {@code iceberg}.</li>
 *   <li>GCP gold &rarr; {@code bq_native} — emits the BigQuery write form
 *       ({@code df.write.format('bigquery').option('table', ...)}).</li>
 *   <li>DPC (all layers) &rarr; {@code parquet} (Hive). [P2-flagged]</li>
 * </ul>
 *
 * <p><b>Delta is NEVER emitted</b> — Delta is the format for neither Mode (C-2,
 * ADR 0007 corrected); this kills the old Mode-blind {@code .format('delta')}.
 *
 * <p>Write mode comes from {@code write_mode} (default {@code overwrite}); the
 * target path from {@code target_path}, falling back to the {@code PULSE_TARGET_URI}
 * env var at runtime.
 */
public final class WriteSinkPySparkHandler implements OpEmitHandler {

    @Override
    public String opName() {
        return OpVocabulary.WRITE_SINK;
    }

    @Override
    public EmissionEngine engine() {
        return EmissionEngine.PYSPARK;
    }

    @Override
    public String emit(EmitContext ctx) {
        ResolvedConfig config = ctx.config();
        String df = ctx.dfVar();
        String writeMode = orDefault(config.getString("write_mode"), "overwrite");
        String target = orDefault(config.getString("target_path"), "");
        // Mode-aware format: iceberg (GCP bronze/silver) / bq_native (GCP gold) / parquet (DPC).
        String format = ctx.modeResolver().fileFormatFor(ctx.mode(), ctx.lakeLayer());

        StringBuilder py = new StringBuilder();
        py.append("# write-sink: PySpark write of '").append(df).append("'")
                .append(" (Mode=").append(ctx.mode())
                .append(", layer=").append(ctx.lakeLayer())
                .append(", format=").append(format).append(").\n");
        py.append("import os\n");
        py.append("PULSE_TARGET_URI = os.environ.get('PULSE_TARGET_URI', ")
                .append(pyStr(target)).append(")\n");

        if ("bq_native".equals(format)) {
            // GCP gold: BigQuery-native sink. 'table' is the target identifier.
            py.append(df).append(".write.format('bigquery')")
                    .append(".option('table', PULSE_TARGET_URI)")
                    .append(".mode(").append(pyStr(writeMode)).append(")")
                    .append(".save()\n");
            return py.toString();
        }

        // GCP bronze/silver -> iceberg; DPC -> parquet. Never 'delta'.
        py.append(df).append(".write")
                .append(".mode(").append(pyStr(writeMode)).append(")")
                .append(".format(").append(pyStr(format)).append(")")
                .append(".save(PULSE_TARGET_URI)\n");
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
