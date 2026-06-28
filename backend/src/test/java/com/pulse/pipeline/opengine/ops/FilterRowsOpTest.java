package com.pulse.pipeline.opengine.ops;

import com.pulse.pipeline.opengine.ColumnModel;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;
import com.pulse.pipeline.opengine.Schema;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** filter-rows (SPEC #1 §B.1 rule 16) — schema passthrough. */
class FilterRowsOpTest {

    private final FilterRowsOp op = new FilterRowsOp();

    private static Schema in() {
        return Schema.of(
                ColumnModel.simple("a", "string"),
                ColumnModel.simple("b", "integer"),
                ColumnModel.simple("c", "double"));
    }

    @Test
    void opNameIsCanonical() {
        assertEquals(OpVocabulary.FILTER_ROWS, op.opName());
    }

    @Test
    void schemaIsUnchanged() {
        Schema out = op.apply(in(), null, ResolvedConfig.empty());
        assertEquals(List.of("a", "b", "c"), out.names());
        assertEquals(in().names(), out.names());
    }

    @Test
    void emptyInputStaysEmpty() {
        Schema out = op.apply(Schema.empty(), null, ResolvedConfig.empty());
        assertTrue(out.isEmpty());
    }
}
