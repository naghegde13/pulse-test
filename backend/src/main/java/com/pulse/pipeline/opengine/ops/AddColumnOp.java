package com.pulse.pipeline.opengine.ops;

import com.pulse.pipeline.opengine.ColumnModel;
import com.pulse.pipeline.opengine.OpEngineException;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;
import com.pulse.pipeline.opengine.Schema;
import com.pulse.pipeline.opengine.SchemaOp;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code add-column} (SPEC #1 §B.1 rule 1) — OUT = IN + one new derived column.
 *
 * <p>Config:
 * <pre>
 *   name       (the new column's name; absent =&gt; unconfigured passthrough)
 *   type       (REQUIRED when name is present — a derived column requires a
 *              declared output type; loud-fail via OpEngineException if name is
 *              present but type is absent)
 *   nullable   (default true)
 *   expression (optional; when present the new column carries
 *              lineage="derived:expression" and an "expr" extra)
 * </pre>
 *
 * <p>Window functions are this op (no separate op): they are an
 * {@code add-column} with an expression.
 *
 * <p>An absent {@code name} is the unconfigured do-nothing passthrough default
 * (SPEC #1 §A.2).
 */
public final class AddColumnOp implements SchemaOp {

    @Override
    public String opName() {
        return OpVocabulary.ADD_COLUMN;
    }

    @Override
    public Schema apply(Schema in, Schema in2, ResolvedConfig cfg) {
        if (!cfg.has("name")) {
            // Unconfigured — pass columns through unchanged.
            return passthrough(in);
        }
        String name = cfg.getString("name");
        if (!cfg.has("type")) {
            throw new OpEngineException(
                    "add-column: a derived column requires a declared output type "
                            + "('type'); none provided for column '" + name + "'");
        }
        String type = cfg.getString("type");
        boolean nullable = cfg.getBool("nullable", true);

        ColumnModel added = ColumnModel.simple(name, type, nullable);
        if (cfg.has("expression")) {
            added = added
                    .withExtra("lineage", "derived:expression")
                    .withExtra("expr", cfg.getString("expression"));
        }

        List<ColumnModel> out = new ArrayList<>();
        if (in != null) out.addAll(in.columns());
        out.add(added);
        return new Schema(out);
    }

    private static Schema passthrough(Schema in) {
        return in == null ? Schema.empty() : new Schema(in.columns());
    }
}
