package com.pulse.pipeline.opengine.ops;

import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;
import com.pulse.pipeline.opengine.Schema;
import com.pulse.pipeline.opengine.SchemaOp;

/**
 * {@code emit-report} (SPEC #1 §B.1 rule 23) — main-path OUT = IN.
 *
 * <p>The emitted report (run metrics / DQ summary) is an append-only side-output
 * table, NOT the main data. The main flow continues with its columns unchanged,
 * so {@code apply} is a passthrough on the main path.
 */
public final class EmitReportOp implements SchemaOp {

    @Override
    public String opName() {
        return OpVocabulary.EMIT_REPORT;
    }

    @Override
    public Schema apply(Schema in, Schema in2, ResolvedConfig cfg) {
        // Main-path passthrough: the report is a side-output, not the main schema.
        return in == null ? Schema.empty() : new Schema(in.columns());
    }
}
