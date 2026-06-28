package com.pulse.pipeline.opengine.ops;

import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;
import com.pulse.pipeline.opengine.Schema;
import com.pulse.pipeline.opengine.SchemaOp;

/**
 * {@code distinct-union} (SPEC #1 §B.1 rule 13) — OUT = IN.
 *
 * <p>A union of same-schema inputs followed by a dedupe; the column set is the
 * primary input's schema. Always a schema-passthrough.
 */
public final class DistinctUnionOp implements SchemaOp {

    @Override
    public String opName() {
        return OpVocabulary.DISTINCT_UNION;
    }

    @Override
    public Schema apply(Schema in, Schema in2, ResolvedConfig cfg) {
        if (in == null) return Schema.empty();
        return new Schema(in.columns());
    }
}
