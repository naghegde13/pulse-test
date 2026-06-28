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

/** rename-columns (SPEC #1 §B.1 rule 5) — names remapped, types/order preserved. */
class RenameColumnsOpTest {

    private final RenameColumnsOp op = new RenameColumnsOp();

    private static ResolvedConfig cfg(Map<String, String> renames) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rename_map", renames);
        return new ResolvedConfig(m);
    }

    private static Schema in() {
        return Schema.of(ColumnModel.simple("a", "string"),
                ColumnModel.simple("b", "integer"),
                ColumnModel.simple("c", "double"));
    }

    @Test
    void opNameIsCanonical() {
        assertEquals(OpVocabulary.RENAME_COLUMNS, op.opName());
    }

    @Test
    void remapsNamesPreservingTypeAndOrder() {
        Map<String, String> renames = new LinkedHashMap<>();
        renames.put("a", "alpha");
        renames.put("c", "gamma");
        Schema out = op.apply(in(), null, cfg(renames));
        assertEquals(List.of("alpha", "b", "gamma"), out.names());
        assertEquals("string", out.find("alpha").type());
        assertEquals("double", out.find("gamma").type());
    }

    @Test
    void emptyConfigPassesThrough() {
        Schema out = op.apply(in(), null, ResolvedConfig.empty());
        assertEquals(List.of("a", "b", "c"), out.names());
    }
}
