package com.pulse.pipeline.opengine.ops;

import com.pulse.pipeline.opengine.ColumnModel;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;
import com.pulse.pipeline.opengine.Schema;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** union-all (SPEC #1 §B.1 rule 12) — schema passthrough on the primary input. */
class UnionAllOpTest {

    private final UnionAllOp op = new UnionAllOp();

    private static Schema in() {
        return Schema.of(
                ColumnModel.simple("a", "string"),
                ColumnModel.simple("b", "integer"));
    }

    @Test
    void opNameIsCanonical() {
        assertEquals(OpVocabulary.UNION_ALL, op.opName());
    }

    @Test
    void schemaIsPrimaryInputUnchanged() {
        Schema out = op.apply(in(), null, ResolvedConfig.empty());
        assertEquals(List.of("a", "b"), out.names());
        assertEquals(in().names(), out.names());
    }

    @Test
    void secondaryInputDoesNotChangeOutput() {
        Schema secondary = Schema.of(
                ColumnModel.simple("a", "string"),
                ColumnModel.simple("b", "integer"));
        Schema out = op.apply(in(), secondary, ResolvedConfig.empty());
        assertEquals(List.of("a", "b"), out.names());
    }
}
