package com.pulse.pipeline.opengine.ops;

import com.pulse.pipeline.opengine.ColumnModel;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;
import com.pulse.pipeline.opengine.Schema;
import com.pulse.pipeline.opengine.SchemaOp;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code drop-columns} (SPEC #1 §B.1 rule 3) — OUT = IN minus the named columns.
 *
 * <p>Config: {@code drop_columns: [names]}. Remaining columns keep their input
 * order. An absent/empty {@code drop_columns} is the unconfigured do-nothing
 * passthrough default (SPEC #1 §A.2).
 */
public final class DropColumnsOp implements SchemaOp {

    @Override
    public String opName() {
        return OpVocabulary.DROP_COLUMNS;
    }

    @Override
    public Schema apply(Schema in, Schema in2, ResolvedConfig cfg) {
        if (in == null) return Schema.empty();
        Set<String> drop = new LinkedHashSet<>(cfg.getStringList("drop_columns"));
        if (drop.isEmpty()) {
            return new Schema(in.columns());
        }
        List<ColumnModel> out = new ArrayList<>();
        for (ColumnModel c : in.columns()) {
            if (!drop.contains(c.name())) out.add(c);
        }
        return new Schema(out);
    }
}
