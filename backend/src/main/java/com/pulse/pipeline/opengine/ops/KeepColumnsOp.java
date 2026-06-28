package com.pulse.pipeline.opengine.ops;

import com.pulse.pipeline.opengine.ColumnModel;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;
import com.pulse.pipeline.opengine.Schema;
import com.pulse.pipeline.opengine.SchemaOp;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code keep-columns} (SPEC #1 §B.1 rule 4) — OUT = only the named columns, in
 * the NAMED ORDER (the config order, not the input order).
 *
 * <p>Config: {@code keep_columns: [names]}. A named column not present in IN is
 * skipped. An absent/empty {@code keep_columns} is the unconfigured do-nothing
 * passthrough default (SPEC #1 §A.2).
 */
public final class KeepColumnsOp implements SchemaOp {

    @Override
    public String opName() {
        return OpVocabulary.KEEP_COLUMNS;
    }

    @Override
    public Schema apply(Schema in, Schema in2, ResolvedConfig cfg) {
        if (in == null) return Schema.empty();
        List<String> keep = cfg.getStringList("keep_columns");
        if (keep.isEmpty()) {
            return new Schema(in.columns());
        }
        List<ColumnModel> out = new ArrayList<>();
        // Emit in the CONFIG order, skipping any name absent from IN.
        for (String name : keep) {
            ColumnModel c = in.find(name);
            if (c != null) out.add(c);
        }
        return new Schema(out);
    }
}
