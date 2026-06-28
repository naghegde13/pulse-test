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

/** flatten-json (SPEC #1 §B.1 rule 8) — struct -> flat sub-fields. */
class FlattenJsonOpTest {

    private final FlattenJsonOp op = new FlattenJsonOp();

    private static Schema in() {
        ColumnModel borrower = ColumnModel.struct("borrower", List.of(
                ColumnModel.simple("fico", "integer"),
                ColumnModel.simple("name", "string")), true);
        ColumnModel tags = ColumnModel.list("tags", ColumnModel.simple(null, "string"), true);
        return Schema.of(ColumnModel.simple("loan_id", "string"), borrower, tags);
    }

    private static ResolvedConfig cfg(List<String> columns) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("columns", columns);
        return new ResolvedConfig(m);
    }

    @Test
    void opNameIsCanonical() {
        assertEquals(OpVocabulary.FLATTEN_JSON, op.opName());
    }

    @Test
    void expandsStructIntoSubFieldsAtTopLevel() {
        Schema out = op.apply(in(), null, ResolvedConfig.empty());
        // borrower replaced by its fields, fico + name promoted to top level.
        assertEquals(List.of("loan_id", "fico", "name", "tags"), out.names());
        assertEquals("integer", out.find("fico").type());
        assertEquals("string", out.find("name").type());
    }

    @Test
    void listColumnsPassThroughUnchanged() {
        Schema out = op.apply(in(), null, ResolvedConfig.empty());
        ColumnModel tags = out.find("tags");
        assertEquals("list", tags.type());
        assertEquals("string", tags.element().type());
    }

    @Test
    void nonStructColumnsPassThrough() {
        Schema out = op.apply(in(), null, ResolvedConfig.empty());
        assertEquals("string", out.find("loan_id").type());
    }

    @Test
    void columnsConfigLimitsWhichStructsAreFlattened() {
        ColumnModel a = ColumnModel.struct("a", List.of(ColumnModel.simple("x", "integer")), true);
        ColumnModel b = ColumnModel.struct("b", List.of(ColumnModel.simple("y", "integer")), true);
        Schema in = Schema.of(a, b);
        // Only flatten "a"; "b" stays a struct.
        Schema out = op.apply(in, null, cfg(List.of("a")));
        assertEquals(List.of("x", "b"), out.names());
        assertEquals("struct", out.find("b").type());
    }
}
