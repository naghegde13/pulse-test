package com.pulse.pipeline.opengine.ops;

import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;
import com.pulse.pipeline.opengine.Schema;
import com.pulse.pipeline.opengine.SchemaOp;

/**
 * {@code sample-limit} (SPEC #1 §B.1 rule 15) — OUT = IN.
 *
 * <p>A row subset (sample/limit) is taken at runtime; the column set is unchanged.
 * Always a schema-passthrough. This op backs its own atomic Blueprint.
 */
public final class SampleLimitOp implements SchemaOp {

    @Override
    public String opName() {
        return OpVocabulary.SAMPLE_LIMIT;
    }

    @Override
    public Schema apply(Schema in, Schema in2, ResolvedConfig cfg) {
        if (in == null) return Schema.empty();
        return new Schema(in.columns());
    }
}
