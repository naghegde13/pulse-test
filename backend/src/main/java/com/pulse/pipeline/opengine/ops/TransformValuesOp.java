package com.pulse.pipeline.opengine.ops;

import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;
import com.pulse.pipeline.opengine.Schema;
import com.pulse.pipeline.opengine.SchemaOp;

/**
 * {@code transform-values} (SPEC #1 §B.1 rule 2) — OUT = IN UNCHANGED.
 *
 * <p>This op replaces a column's VALUES via an expression (trim, fill-nulls,
 * standardize, …); it changes neither the schema nor the row count. Its
 * schema-effect rule is therefore always a passthrough — the column set out
 * equals the column set in, regardless of config.
 */
public final class TransformValuesOp implements SchemaOp {

    @Override
    public String opName() {
        return OpVocabulary.TRANSFORM_VALUES;
    }

    @Override
    public Schema apply(Schema in, Schema in2, ResolvedConfig cfg) {
        // Values change; schema does not. Always pass columns through unchanged.
        return in == null ? Schema.empty() : new Schema(in.columns());
    }
}
