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

/** transform-values (SPEC #1 §B.1 rule 2) — always passthrough on schema. */
class TransformValuesOpTest {

    private final TransformValuesOp op = new TransformValuesOp();

    private static Schema in() {
        return Schema.of(ColumnModel.simple("name", "string"),
                ColumnModel.simple("city", "string"));
    }

    @Test
    void opNameIsCanonical() {
        assertEquals(OpVocabulary.TRANSFORM_VALUES, op.opName());
    }

    @Test
    void schemaUnchangedEvenWhenConfigured() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("column", "name");
        m.put("expression", "trim(name)");
        Schema out = op.apply(in(), null, new ResolvedConfig(m));
        assertEquals(List.of("name", "city"), out.names());
        assertEquals("string", out.find("name").type());
    }

    @Test
    void unconfiguredPassesThrough() {
        Schema out = op.apply(in(), null, ResolvedConfig.empty());
        assertEquals(List.of("name", "city"), out.names());
    }
}
