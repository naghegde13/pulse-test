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

/** mask-columns (SPEC #1 §B.1 rule 7). */
class MaskColumnsOpTest {

    private final MaskColumnsOp op = new MaskColumnsOp();

    private static Schema in() {
        return Schema.of(ColumnModel.simple("id", "string"),
                ColumnModel.simple("email", "string"),
                ColumnModel.simple("birth_date", "date"));
    }

    private static ResolvedConfig cfg(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return new ResolvedConfig(m);
    }

    @Test
    void opNameIsCanonical() {
        assertEquals(OpVocabulary.MASK_COLUMNS, op.opName());
    }

    @Test
    void piiColumnsDefaultHashTypeBecomesStringWithTagsAndTransform() {
        Schema out = op.apply(in(), null, cfg("pii_columns", List.of("email")));
        ColumnModel masked = out.find("email");
        // hash -> string
        assertEquals("string", masked.type());
        assertEquals("masked:hash", masked.extras().get("lineage"));
        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) masked.extras().get("tags");
        assertEquals(List.of("masked", "pii"), tags);
        @SuppressWarnings("unchecked")
        Map<String, Object> transform = (Map<String, Object>) masked.extras().get("transform");
        assertEquals("mask", transform.get("kind"));
        assertEquals("hash", transform.get("strategy"));
    }

    @Test
    void nonMaskedColumnsPassThroughUnchanged() {
        Schema out = op.apply(in(), null, cfg("pii_columns", List.of("email")));
        ColumnModel id = out.find("id");
        ColumnModel birth = out.find("birth_date");
        assertEquals("string", id.type());
        assertTrue(id.extras().isEmpty());
        assertEquals("date", birth.type());
        assertTrue(birth.extras().isEmpty());
        // order preserved, all columns retained
        assertEquals(List.of("id", "email", "birth_date"), out.names());
    }

    @Test
    void nonHashStrategyPreservesSourceType() {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("column", "birth_date");
        spec.put("strategy", "redact");
        Schema out = op.apply(in(), null, cfg("mask_specs", List.of(spec)));
        ColumnModel masked = out.find("birth_date");
        // redact preserves source type (date), not string
        assertEquals("date", masked.type());
        assertEquals("masked:redact", masked.extras().get("lineage"));
        @SuppressWarnings("unchecked")
        Map<String, Object> transform = (Map<String, Object>) masked.extras().get("transform");
        assertEquals("redact", transform.get("strategy"));
    }

    @Test
    void maskSpecsDefaultStrategyIsHashWhenAbsent() {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("column", "email"); // no strategy
        Schema out = op.apply(in(), null, cfg("mask_specs", List.of(spec)));
        ColumnModel masked = out.find("email");
        assertEquals("string", masked.type());
        assertEquals("masked:hash", masked.extras().get("lineage"));
    }

    @Test
    void existingTagsAreMergedNotReplaced() {
        Schema withTags = Schema.of(
                ColumnModel.simple("email", "string").withExtra("tags", List.of("sensitive")));
        Schema out = op.apply(withTags, null, cfg("pii_columns", List.of("email")));
        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) out.find("email").extras().get("tags");
        assertEquals(List.of("sensitive", "masked", "pii"), tags);
    }

    @Test
    void unconfiguredPassesThrough() {
        Schema out = op.apply(in(), null, ResolvedConfig.empty());
        assertEquals(List.of("id", "email", "birth_date"), out.names());
        assertTrue(out.find("email").extras().isEmpty());
    }
}
