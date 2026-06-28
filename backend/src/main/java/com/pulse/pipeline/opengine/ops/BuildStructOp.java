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
 * {@code build-struct} (SPEC #1 §B.1 rule 9) — pack named source columns into ONE
 * struct column (the inverse of {@code flatten-json}).
 *
 * <p>Config:
 * <pre>
 *   struct_name    (the new struct column's name)
 *   source_columns (the columns to pack — they become the struct's sub-fields)
 *   drop_sources   (default true — when true the packed source columns are
 *                  removed from the top level; when false they remain)
 * </pre>
 *
 * <p>The new struct column is placed where the FIRST source column appeared in
 * IN; if none of the source columns exist in IN (or {@code drop_sources} is
 * false in a way that leaves no anchor), the struct is appended at the end.
 *
 * <p>An absent {@code struct_name} or {@code source_columns} is the unconfigured
 * do-nothing passthrough default (SPEC #1 §A.2).
 */
public final class BuildStructOp implements SchemaOp {

    @Override
    public String opName() {
        return OpVocabulary.BUILD_STRUCT;
    }

    @Override
    public Schema apply(Schema in, Schema in2, ResolvedConfig cfg) {
        if (in == null) return Schema.empty();

        String structName = cfg.getString("struct_name");
        List<String> sources = cfg.getStringList("source_columns");
        if (structName == null || structName.isBlank() || sources.isEmpty()) {
            return new Schema(in.columns());
        }
        boolean dropSources = cfg.getBool("drop_sources", true);

        // The struct's sub-fields: the source columns (in config order) that
        // actually exist in IN, as ColumnModel children.
        List<ColumnModel> fields = new ArrayList<>();
        for (String name : sources) {
            ColumnModel src = in.find(name);
            if (src != null) fields.add(src);
        }
        ColumnModel structCol = ColumnModel.struct(structName, fields, true);

        Set<String> sourceSet = new LinkedHashSet<>(sources);

        // Find the anchor: position of the first source column in IN.
        int anchor = -1;
        List<ColumnModel> cols = in.columns();
        for (int i = 0; i < cols.size(); i++) {
            if (sourceSet.contains(cols.get(i).name())) {
                anchor = i;
                break;
            }
        }

        List<ColumnModel> out = new ArrayList<>();
        boolean structPlaced = false;
        for (int i = 0; i < cols.size(); i++) {
            ColumnModel c = cols.get(i);
            boolean isSource = sourceSet.contains(c.name());
            // Place the struct at the first source column's position.
            if (i == anchor) {
                out.add(structCol);
                structPlaced = true;
            }
            // Keep the source column at the top level only when not dropping.
            if (isSource) {
                if (!dropSources) out.add(c);
            } else {
                out.add(c);
            }
        }
        if (!structPlaced) {
            // No source column found in IN — append the (possibly empty) struct.
            out.add(structCol);
        }
        return new Schema(out);
    }
}
