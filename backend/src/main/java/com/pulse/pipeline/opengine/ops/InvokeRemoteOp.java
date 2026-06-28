package com.pulse.pipeline.opengine.ops;

import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;
import com.pulse.pipeline.opengine.Schema;
import com.pulse.pipeline.opengine.SchemaOp;

/**
 * {@code invoke-remote} (SPEC #1 §B.1 rule 32) — control op, PORTLESS, NO schema
 * effect.
 *
 * <p>Invoking a remote system (an external trigger / call-out) carries no data
 * ports and therefore no columns. {@code apply} returns an empty schema.
 */
public final class InvokeRemoteOp implements SchemaOp {

    @Override
    public String opName() {
        return OpVocabulary.INVOKE_REMOTE;
    }

    @Override
    public Schema apply(Schema in, Schema in2, ResolvedConfig cfg) {
        // Portless control op: no data ports, no columns.
        return Schema.empty();
    }
}
