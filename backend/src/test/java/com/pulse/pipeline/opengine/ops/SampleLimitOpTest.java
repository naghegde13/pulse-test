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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** sample-limit (SPEC #1 §B.1 rule 15) — schema passthrough (row subset). */
class SampleLimitOpTest {

    private final SampleLimitOp op = new SampleLimitOp();

    private static Schema in() {
        return Schema.of(
                ColumnModel.simple("a", "string"),
                ColumnModel.simple("b", "integer"),
                ColumnModel.simple("c", "double"));
    }

    @Test
    void opNameIsCanonical() {
        assertEquals(OpVocabulary.SAMPLE_LIMIT, op.opName());
    }

    @Test
    void schemaIsUnchanged() {
        Schema out = op.apply(in(), null, ResolvedConfig.empty());
        assertEquals(List.of("a", "b", "c"), out.names());
        assertEquals(in().names(), out.names());
    }

    @Test
    void schemaUnchangedEvenWithLimitConfig() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("limit", 100);
        Schema out = op.apply(in(), null, new ResolvedConfig(m));
        assertEquals(List.of("a", "b", "c"), out.names());
    }

    @Test
    void emptyInputStaysEmpty() {
        Schema out = op.apply(Schema.empty(), null, ResolvedConfig.empty());
        assertTrue(out.isEmpty());
    }
}
