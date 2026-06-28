package com.pulse.pipeline.opengine.ops;

import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;
import com.pulse.pipeline.opengine.Schema;
import com.pulse.pipeline.opengine.SchemaOp;

/**
 * {@code check-data} (SPEC #1 §B.1 rule 22) — OUT = IN on the MAIN path.
 *
 * <p>DQ checks (assertions / expectations) inspect the data but do not reshape
 * the main flow. Failing / quarantined rows land in a side-table, not the main
 * schema, so the main output schema is unchanged. {@code apply} is therefore a
 * passthrough.
 */
public final class CheckDataOp implements SchemaOp {

    @Override
    public String opName() {
        return OpVocabulary.CHECK_DATA;
    }

    @Override
    public Schema apply(Schema in, Schema in2, ResolvedConfig cfg) {
        // Main-path passthrough: the quarantine/side-table is not the main schema.
        return in == null ? Schema.empty() : new Schema(in.columns());
    }
}
