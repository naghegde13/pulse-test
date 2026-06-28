package com.pulse.pipeline.opengine.ops;

import com.pulse.pipeline.opengine.ColumnModel;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;
import com.pulse.pipeline.opengine.Schema;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** distinct-union (SPEC #1 §B.1 rule 13) — schema passthrough (union then dedupe). */
class DistinctUnionOpTest {

    private final DistinctUnionOp op = new DistinctUnionOp();

    private static Schema in() {
        return Schema.of(
                ColumnModel.simple("a", "string"),
                ColumnModel.simple("b", "integer"));
    }

    @Test
    void opNameIsCanonical() {
        assertEquals(OpVocabulary.DISTINCT_UNION, op.opName());
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
