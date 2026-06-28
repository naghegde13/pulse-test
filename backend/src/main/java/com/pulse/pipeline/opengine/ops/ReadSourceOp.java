package com.pulse.pipeline.opengine.ops;

import com.pulse.pipeline.opengine.ColumnModel;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;
import com.pulse.pipeline.opengine.Schema;
import com.pulse.pipeline.opengine.SchemaOp;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code read-source} (SPEC #1 §B.1 rule 24) — OUT = the source dataset's
 * discovered/declared schema.
 *
 * <p>The engine (e.g. {@code SchemaPropagationService}) resolves the source dataset
 * before calling the op and supplies its discovered/declared columns AS the input
 * schema {@code in}. The op therefore does <em>not</em> fetch a dataset itself; its
 * rule is simply OUT = the source columns = {@code in}.
 *
 * <p>As a convenience, each output column is tagged with an
 * {@code lineage="source"} extra when it does not already carry a lineage tag, so
 * the DAG view can mark source-origin columns. Columns that already have a lineage
 * are left untouched.
 */
public final class ReadSourceOp implements SchemaOp {

    private static final String LINEAGE_KEY = "lineage";
    private static final String LINEAGE_SOURCE = "source";

    @Override
    public String opName() {
        return OpVocabulary.READ_SOURCE;
    }

    @Override
    public Schema apply(Schema in, Schema in2, ResolvedConfig cfg) {
        if (in == null) return Schema.empty();
        List<ColumnModel> out = new ArrayList<>(in.size());
        for (ColumnModel c : in.columns()) {
            if (c.extras().get(LINEAGE_KEY) == null) {
                out.add(c.withExtra(LINEAGE_KEY, LINEAGE_SOURCE));
            } else {
                out.add(c);
            }
        }
        return new Schema(out);
    }
}
