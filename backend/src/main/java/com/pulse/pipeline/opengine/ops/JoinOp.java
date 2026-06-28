package com.pulse.pipeline.opengine.ops;

import com.pulse.pipeline.opengine.ColumnModel;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;
import com.pulse.pipeline.opengine.Schema;
import com.pulse.pipeline.opengine.SchemaOp;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code join} (SPEC #1 §B.1 rule 10) — combine the primary input with a
 * secondary input.
 *
 * <p>OUT = all IN columns (in order, unchanged) followed by all IN2 columns
 * (in order). On a same-name collision between an IN column and an IN2 column,
 * the IN2 column is renamed {@code right_<name>}.
 *
 * <p><b>FIX #5</b>: keep BOTH sides on a same-name collision <em>regardless of
 * type</em>. The legacy rule dropped the right column when the types matched;
 * that loses data. Here every colliding IN2 column survives under
 * {@code right_<name>} whether the types differ or match. Non-colliding IN2
 * columns keep their original name.
 *
 * <p>{@code in2} may be null or empty, in which case OUT == IN.
 */
public final class JoinOp implements SchemaOp {

    @Override
    public String opName() {
        return OpVocabulary.JOIN;
    }

    @Override
    public Schema apply(Schema in, Schema in2, ResolvedConfig cfg) {
        List<ColumnModel> out = new ArrayList<>();
        // All IN columns first, unchanged, preserving order.
        if (in != null) {
            out.addAll(in.columns());
        }
        // Then all IN2 columns, renamed where they collide with an IN name.
        if (in2 != null && !in2.isEmpty()) {
            for (ColumnModel rightCol : in2.columns()) {
                String name = rightCol.name();
                boolean collides = in != null && name != null && in.hasColumn(name);
                if (collides) {
                    // FIX #5: keep BOTH sides — rename the right column to
                    // right_<name> regardless of whether the types match.
                    out.add(rightCol.withName("right_" + name));
                } else {
                    out.add(rightCol);
                }
            }
        }
        return new Schema(out);
    }
}
