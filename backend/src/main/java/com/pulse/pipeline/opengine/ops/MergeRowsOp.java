package com.pulse.pipeline.opengine.ops;

import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;
import com.pulse.pipeline.opengine.Schema;
import com.pulse.pipeline.opengine.SchemaOp;

/**
 * {@code merge-rows} (SPEC #1 §B.1 rule 19) — OUT = IN.
 *
 * <p>An upsert/merge by key into a target; the column set is unchanged. Always a
 * schema-passthrough.
 */
public final class MergeRowsOp implements SchemaOp {

    @Override
    public String opName() {
        return OpVocabulary.MERGE_ROWS;
    }

    @Override
    public Schema apply(Schema in, Schema in2, ResolvedConfig cfg) {
        if (in == null) return Schema.empty();
        return new Schema(in.columns());
    }
}
