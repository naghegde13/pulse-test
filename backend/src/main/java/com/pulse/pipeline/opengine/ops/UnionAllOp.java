package com.pulse.pipeline.opengine.ops;

import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;
import com.pulse.pipeline.opengine.Schema;
import com.pulse.pipeline.opengine.SchemaOp;

/**
 * {@code union-all} (SPEC #1 §B.1 rule 12) — OUT = IN.
 *
 * <p>All inputs share the same schema; rows are concatenated. The output schema is
 * the primary input's schema ({@code in2} may be present, but a union requires the
 * same column set, so OUT = IN).
 */
public final class UnionAllOp implements SchemaOp {

    @Override
    public String opName() {
        return OpVocabulary.UNION_ALL;
    }

    @Override
    public Schema apply(Schema in, Schema in2, ResolvedConfig cfg) {
        if (in == null) return Schema.empty();
        return new Schema(in.columns());
    }
}
