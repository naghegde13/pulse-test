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

/** keep-columns (SPEC #1 §B.1 rule 4) — OUT in NAMED (config) order. */
class KeepColumnsOpTest {

    private final KeepColumnsOp op = new KeepColumnsOp();

    private static ResolvedConfig cfg(List<String> keep) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("keep_columns", keep);
        return new ResolvedConfig(m);
    }

    private static Schema in() {
        return Schema.of(ColumnModel.simple("a", "string"),
                ColumnModel.simple("b", "integer"),
                ColumnModel.simple("c", "double"));
    }

    @Test
    void opNameIsCanonical() {
        assertEquals(OpVocabulary.KEEP_COLUMNS, op.opName());
    }

    @Test
    void keepsOnlyNamedColumnsInConfigOrderNotInputOrder() {
        // Config order is c, a — output MUST follow that, not the input order a, c.
        Schema out = op.apply(in(), null, cfg(List.of("c", "a")));
        assertEquals(List.of("c", "a"), out.names());
        assertEquals("double", out.find("c").type());
        assertEquals("string", out.find("a").type());
    }

    @Test
    void namedColumnAbsentFromInputIsSkipped() {
        Schema out = op.apply(in(), null, cfg(List.of("c", "zzz", "a")));
        assertEquals(List.of("c", "a"), out.names());
    }

    @Test
    void emptyConfigPassesThrough() {
        Schema out = op.apply(in(), null, ResolvedConfig.empty());
        assertEquals(List.of("a", "b", "c"), out.names());
    }
}
