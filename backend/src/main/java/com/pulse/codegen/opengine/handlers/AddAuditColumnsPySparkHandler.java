package com.pulse.codegen.opengine.handlers;

import com.pulse.codegen.audit.IngestionAuditColumns;
import com.pulse.codegen.audit.IngestionAuditColumns.SourceContext;
import com.pulse.codegen.opengine.EmissionEngine;
import com.pulse.codegen.opengine.EmitContext;
import com.pulse.codegen.opengine.OpEmitHandler;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;

/**
 * PySpark emission for {@code add-audit-columns} (SPEC #2 §C.1).
 *
 * <p>Delegates entirely to {@link IngestionAuditColumns#emitPyspark} — the single
 * source of truth for the locked 8-column audit set (C-1, 2026-06-15), so the
 * runtime emit and the design-time schema propagation stay in lockstep and every
 * audit column (including {@code _pulse_dag_id}) is guaranteed present.
 *
 * <p>Config:
 * <ul>
 *   <li>{@code pipeline_slug} (default {@code pipeline}) — baked into {@code _pulse_pipeline}.</li>
 *   <li>{@code task_slug} (default {@code task}).</li>
 *   <li>{@code processing_datetime_source} — drives {@code _pulse_processing_ts}
 *       ({@code file_arrival_time} | {@code filename_segment} | {@code airflow_run_time}).</li>
 *   <li>{@code source_context} — one of FILE/JDBC/STREAM/API/GENERIC (default GENERIC);
 *       controls how {@code _pulse_source_uri} is derived.</li>
 * </ul>
 */
public final class AddAuditColumnsPySparkHandler implements OpEmitHandler {

    @Override
    public String opName() {
        return OpVocabulary.ADD_AUDIT_COLUMNS;
    }

    @Override
    public EmissionEngine engine() {
        return EmissionEngine.PYSPARK;
    }

    @Override
    public String emit(EmitContext ctx) {
        ResolvedConfig config = ctx.config();
        String pipelineSlug = orDefault(config.getString("pipeline_slug"), "pipeline");
        String taskSlug = orDefault(config.getString("task_slug"), "task");
        String pdtSource = config.getString("processing_datetime_source");
        SourceContext sourceContext = parseSourceContext(config.getString("source_context"));

        StringBuilder py = new StringBuilder();
        IngestionAuditColumns.emitPyspark(py, pipelineSlug, taskSlug, pdtSource, sourceContext);
        return py.toString();
    }

    private static String orDefault(String v, String dflt) {
        return (v == null || v.isBlank()) ? dflt : v;
    }

    private static SourceContext parseSourceContext(String raw) {
        if (raw == null || raw.isBlank()) {
            return SourceContext.GENERIC;
        }
        try {
            return SourceContext.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return SourceContext.GENERIC;
        }
    }
}
