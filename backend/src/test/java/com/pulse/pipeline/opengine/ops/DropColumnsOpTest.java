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

/** drop-columns (SPEC #1 §B.1 rule 3). */
class DropColumnsOpTest {

    private final DropColumnsOp op = new DropColumnsOp();

    private static ResolvedConfig cfg(List<String> drop) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("drop_columns", drop);
        return new ResolvedConfig(m);
    }

    private static Schema in() {
        return Schema.of(ColumnModel.simple("a", "string"),
                ColumnModel.simple("b", "integer"),
                ColumnModel.simple("c", "double"));
    }

    @Test
    void opNameIsCanonical() {
        assertEquals(OpVocabulary.DROP_COLUMNS, op.opName());
    }

    @Test
    void dropsNamedColumnsPreservingOrder() {
        Schema out = op.apply(in(), null, cfg(List.of("b")));
        assertEquals(List.of("a", "c"), out.names());
    }

    @Test
    void dropUnknownIsNoOpForThatName() {
        Schema out = op.apply(in(), null, cfg(List.of("zzz")));
        assertEquals(List.of("a", "b", "c"), out.names());
    }

    @Test
    void emptyConfigPassesThrough() {
        Schema out = op.apply(in(), null, ResolvedConfig.empty());
        assertEquals(List.of("a", "b", "c"), out.names());
    }
}
