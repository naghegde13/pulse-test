package com.pulse.pipeline.opengine.ops;

import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;
import com.pulse.pipeline.opengine.Schema;
import com.pulse.pipeline.opengine.SchemaOp;

/**
 * {@code filter-rows} (SPEC #1 §B.1 rule 16) — OUT = IN.
 *
 * <p>Rows are filtered by a predicate at runtime; the column set is unchanged.
 * Always a schema-passthrough.
 */
public final class FilterRowsOp implements SchemaOp {

    @Override
    public String opName() {
        return OpVocabulary.FILTER_ROWS;
    }

    @Override
    public Schema apply(Schema in, Schema in2, ResolvedConfig cfg) {
        if (in == null) return Schema.empty();
        return new Schema(in.columns());
    }
}
