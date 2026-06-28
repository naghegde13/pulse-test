package com.pulse.pipeline.opengine.ops;

import com.pulse.pipeline.opengine.ColumnModel;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;
import com.pulse.pipeline.opengine.Schema;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** change-types (SPEC #1 §B.1 rule 6) — named columns' types replaced, order preserved. */
class ChangeTypesOpTest {

    private final ChangeTypesOp op = new ChangeTypesOp();

    private static ResolvedConfig cfg(Map<String, String> coercions) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type_coercions", coercions);
        return new ResolvedConfig(m);
    }

    private static Schema in() {
        return Schema.of(ColumnModel.simple("a", "string"),
                ColumnModel.simple("b", "string"),
                ColumnModel.simple("c", "string"));
    }

    @Test
    void opNameIsCanonical() {
        assertEquals(OpVocabulary.CHANGE_TYPES, op.opName());
    }

    @Test
    void coercesNamedTypesPreservingOrderAndOtherTypes() {
        Map<String, String> coercions = new LinkedHashMap<>();
        coercions.put("b", "integer");
        coercions.put("c", "timestamp");
        Schema out = op.apply(in(), null, cfg(coercions));
        assertEquals(List.of("a", "b", "c"), out.names());
        assertEquals("string", out.find("a").type());
        assertEquals("integer", out.find("b").type());
        assertEquals("timestamp", out.find("c").type());
    }

    @Test
    void emptyConfigPassesThrough() {
        Schema out = op.apply(in(), null, ResolvedConfig.empty());
        assertEquals(List.of("a", "b", "c"), out.names());
        assertEquals("string", out.find("b").type());
    }
}
