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
 * {@code flatten-json} (SPEC #1 §B.1 rule 8) — each struct column is expanded
 * into its flat sub-fields (nested struct -&gt; flat).
 *
 * <p>For each IN column that {@link ColumnModel#isStruct()}, it is replaced by its
 * {@link ColumnModel#fields()} — each field becomes a top-level column emitted
 * under the field's OWN name (the simple, un-prefixed behavior). Non-struct
 * columns pass through unchanged. List columns pass through unchanged.
 *
 * <p>Config (optional): {@code columns: [names]} limits which struct columns are
 * flattened; when absent, ALL struct columns are flattened.
 */
public final class FlattenJsonOp implements SchemaOp {

    @Override
    public String opName() {
        return OpVocabulary.FLATTEN_JSON;
    }

    @Override
    public Schema apply(Schema in, Schema in2, ResolvedConfig cfg) {
        if (in == null) return Schema.empty();

        List<String> limit = cfg.getStringList("columns");
        Set<String> only = limit.isEmpty() ? null : new LinkedHashSet<>(limit);

        List<ColumnModel> out = new ArrayList<>();
        for (ColumnModel c : in.columns()) {
            boolean selected = only == null || only.contains(c.name());
            if (c.isStruct() && selected) {
                // Expand: emit each sub-field at top level under its own name.
                out.addAll(c.fields());
            } else {
                // Non-struct, list, or a struct not selected for flattening.
                out.add(c);
            }
        }
        return new Schema(out);
    }
}
