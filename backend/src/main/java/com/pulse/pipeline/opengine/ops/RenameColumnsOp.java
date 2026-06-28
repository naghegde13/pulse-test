package com.pulse.pipeline.opengine.ops;

import com.pulse.pipeline.opengine.ColumnModel;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;
import com.pulse.pipeline.opengine.Schema;
import com.pulse.pipeline.opengine.SchemaOp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code rename-columns} (SPEC #1 §B.1 rule 5) — OUT = IN with names remapped per
 * {@code rename_map} (old -&gt; new); types and order are preserved.
 *
 * <p>Config: {@code rename_map: {old: new}}. A column whose name is not a key in
 * the map keeps its name. An absent/empty {@code rename_map} is the unconfigured
 * do-nothing passthrough default (SPEC #1 §A.2).
 */
public final class RenameColumnsOp implements SchemaOp {

    @Override
    public String opName() {
        return OpVocabulary.RENAME_COLUMNS;
    }

    @Override
    public Schema apply(Schema in, Schema in2, ResolvedConfig cfg) {
        if (in == null) return Schema.empty();
        Map<String, String> renames = cfg.getStringMap("rename_map");
        if (renames.isEmpty()) {
            return new Schema(in.columns());
        }
        List<ColumnModel> out = new ArrayList<>();
        for (ColumnModel c : in.columns()) {
            String newName = renames.get(c.name());
            out.add(newName != null ? c.withName(newName) : c);
        }
        return new Schema(out);
    }
}
