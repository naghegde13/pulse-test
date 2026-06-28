package com.pulse.pipeline.opengine.ops;

import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;
import com.pulse.pipeline.opengine.Schema;
import com.pulse.pipeline.opengine.SchemaOp;

/**
 * {@code deduplicate} (SPEC #1 §B.1 rule 17) — OUT = IN.
 *
 * <p>Duplicate rows are removed at runtime; the column set is unchanged. Always a
 * schema-passthrough.
 */
public final class DeduplicateOp implements SchemaOp {

    @Override
    public String opName() {
        return OpVocabulary.DEDUPLICATE;
    }

    @Override
    public Schema apply(Schema in, Schema in2, ResolvedConfig cfg) {
        if (in == null) return Schema.empty();
        return new Schema(in.columns());
    }
}
