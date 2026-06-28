package com.pulse.pipeline.opengine.ops;

import com.pulse.pipeline.opengine.ColumnModel;
import com.pulse.pipeline.opengine.OpEngineException;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;
import com.pulse.pipeline.opengine.Schema;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** add-column (SPEC #1 §B.1 rule 1). */
class AddColumnOpTest {

    private final AddColumnOp op = new AddColumnOp();

    private static ResolvedConfig cfg(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return new ResolvedConfig(m);
    }

    private static Schema in() {
        return Schema.of(ColumnModel.simple("loan_id", "string"),
                ColumnModel.simple("amount", "double"));
    }

    @Test
    void opNameIsCanonical() {
        assertEquals(OpVocabulary.ADD_COLUMN, op.opName());
    }

    @Test
    void addsOneColumn() {
        Schema out = op.apply(in(), null, cfg("name", "ltv", "type", "double"));
        assertEquals(List.of("loan_id", "amount", "ltv"), out.names());
        ColumnModel added = out.find("ltv");
        assertEquals("double", added.type());
        assertTrue(added.nullable());
    }

    @Test
    void nullableDefaultsTrueAndIsConfigurable() {
        Schema out = op.apply(in(), null,
                cfg("name", "ltv", "type", "double", "nullable", false));
        assertFalse(out.find("ltv").nullable());
    }

    @Test
    void expressionCarriesLineageAndExpr() {
        Schema out = op.apply(in(), null,
                cfg("name", "ltv", "type", "double", "expression", "amount / value"));
        ColumnModel added = out.find("ltv");
        assertEquals("derived:expression", added.extras().get("lineage"));
        assertEquals("amount / value", added.extras().get("expr"));
    }

    @Test
    void loudFailsWhenTypeMissing() {
        OpEngineException ex = assertThrows(OpEngineException.class,
                () -> op.apply(in(), null, cfg("name", "ltv")));
        assertTrue(ex.getMessage().contains("ltv"));
    }

    @Test
    void unconfiguredNamePassesThrough() {
        Schema out = op.apply(in(), null, ResolvedConfig.empty());
        assertEquals(List.of("loan_id", "amount"), out.names());
    }
}
