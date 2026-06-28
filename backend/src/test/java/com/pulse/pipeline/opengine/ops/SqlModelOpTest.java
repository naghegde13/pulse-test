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

/** sql-model (SPEC #1 §B.1 rule 27) — declare-schema path until CALCITE-PHASE-2. */
class SqlModelOpTest {

    private final SqlModelOp op = new SqlModelOp();

    private static Map<String, Object> col(String name, String type) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("type", type);
        return m;
    }

    private static Map<String, Object> col(String name, String type, boolean nullable) {
        Map<String, Object> m = col(name, type);
        m.put("nullable", nullable);
        return m;
    }

    private static ResolvedConfig cfg(Object declaredSchema) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (declaredSchema != null) m.put("declared_schema", declaredSchema);
        return new ResolvedConfig(m);
    }

    private static Schema in() {
        return Schema.of(ColumnModel.simple("loan_id", "string"),
                ColumnModel.simple("amount", "double"));
    }

    @Test
    void opNameIsCanonical() {
        assertEquals(OpVocabulary.SQL_MODEL, op.opName());
    }

    @Test
    void declaredSchemaProducesExactColumns() {
        List<Map<String, Object>> declared = List.of(
                col("customer_id", "string", false),
                col("total_balance", "decimal"),
                col("loan_count", "integer"));
        Schema out = op.apply(in(), null, cfg(declared));

        assertEquals(List.of("customer_id", "total_balance", "loan_count"), out.names());
        assertEquals("string", out.find("customer_id").type());
        assertFalse(out.find("customer_id").nullable());
        assertEquals("decimal", out.find("total_balance").type());
        assertEquals("integer", out.find("loan_count").type());
    }

    @Test
    void loudFailsWhenDeclaredSchemaAbsent() {
        OpEngineException ex = assertThrows(OpEngineException.class,
                () -> op.apply(in(), null, cfg(null)));
        assertTrue(ex.getMessage().contains("CALCITE-PHASE-2")
                || ex.getMessage().contains("declared"));
    }

    @Test
    void loudFailsWhenDeclaredSchemaEmpty() {
        OpEngineException ex = assertThrows(OpEngineException.class,
                () -> op.apply(in(), null, cfg(List.of())));
        assertTrue(ex.getMessage().contains("CALCITE-PHASE-2")
                || ex.getMessage().contains("declared"));
    }
}
