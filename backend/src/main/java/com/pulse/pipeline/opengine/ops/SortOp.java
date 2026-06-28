package com.pulse.pipeline.opengine.ops;

import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;
import com.pulse.pipeline.opengine.Schema;
import com.pulse.pipeline.opengine.SchemaOp;

/**
 * {@code sort} (SPEC #1 §B.1 rule 14) — OUT = IN.
 *
 * <p>Row order only is affected; the column set is unchanged. Always a
 * schema-passthrough.
 */
public final class SortOp implements SchemaOp {

    @Override
    public String opName() {
        return OpVocabulary.SORT;
    }

    @Override
    public Schema apply(Schema in, Schema in2, ResolvedConfig cfg) {
        if (in == null) return Schema.empty();
        return new Schema(in.columns());
    }
}
