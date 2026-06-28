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

/** build-struct (SPEC #1 §B.1 rule 9) — pack source columns into one struct. */
class BuildStructOpTest {

    private final BuildStructOp op = new BuildStructOp();

    private static Schema in() {
        return Schema.of(ColumnModel.simple("loan_id", "string"),
                ColumnModel.simple("fico", "integer"),
                ColumnModel.simple("name", "string"),
                ColumnModel.simple("amount", "double"));
    }

    private static ResolvedConfig cfg(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return new ResolvedConfig(m);
    }

    @Test
    void opNameIsCanonical() {
        assertEquals(OpVocabulary.BUILD_STRUCT, op.opName());
    }

    @Test
    void packsSourceColumnsIntoStructAndDropsSourcesByDefault() {
        Schema out = op.apply(in(), null,
                cfg("struct_name", "borrower", "source_columns", List.of("fico", "name")));
        // struct placed where the first source (fico) was; sources dropped.
        assertEquals(List.of("loan_id", "borrower", "amount"), out.names());
        ColumnModel borrower = out.find("borrower");
        assertTrue(borrower.isStruct());
        assertEquals(List.of("fico", "name"), List.of(
                borrower.fields().get(0).name(), borrower.fields().get(1).name()));
        assertEquals("integer", borrower.fields().get(0).type());
        assertEquals("string", borrower.fields().get(1).type());
    }

    @Test
    void dropSourcesFalseKeepsSourceColumns() {
        Schema out = op.apply(in(), null,
                cfg("struct_name", "borrower", "source_columns", List.of("fico", "name"),
                        "drop_sources", false));
        // struct placed at the first source's anchor; sources retained.
        assertEquals(List.of("loan_id", "borrower", "fico", "name", "amount"), out.names());
    }

    @Test
    void missingStructNamePassesThrough() {
        Schema out = op.apply(in(), null, cfg("source_columns", List.of("fico", "name")));
        assertEquals(List.of("loan_id", "fico", "name", "amount"), out.names());
    }

    @Test
    void missingSourceColumnsPassesThrough() {
        Schema out = op.apply(in(), null, cfg("struct_name", "borrower"));
        assertEquals(List.of("loan_id", "fico", "name", "amount"), out.names());
    }

    @Test
    void unconfiguredPassesThrough() {
        Schema out = op.apply(in(), null, ResolvedConfig.empty());
        assertEquals(List.of("loan_id", "fico", "name", "amount"), out.names());
    }
}
