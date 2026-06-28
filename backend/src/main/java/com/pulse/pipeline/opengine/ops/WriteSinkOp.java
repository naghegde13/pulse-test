package com.pulse.pipeline.opengine.ops;

import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;
import com.pulse.pipeline.opengine.Schema;
import com.pulse.pipeline.opengine.SchemaOp;

/**
 * {@code write-sink} (SPEC #1 §B.1 rule 26) — OUT = IN.
 *
 * <p>Rows are written to a destination; the column set is unchanged. Always a
 * schema-passthrough (the carried schema is what gets written).
 */
public final class WriteSinkOp implements SchemaOp {

    @Override
    public String opName() {
        return OpVocabulary.WRITE_SINK;
    }

    @Override
    public Schema apply(Schema in, Schema in2, ResolvedConfig cfg) {
        if (in == null) return Schema.empty();
        return new Schema(in.columns());
    }
}
