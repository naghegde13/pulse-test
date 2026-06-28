package com.pulse.pipeline.opengine;

import com.pulse.expression.service.BuildFailureException;
import com.pulse.expression.service.CalciteSqlModelValidator;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * sql-model OUT-schema resolution (SPEC #1 rule 27, SPEC #6 §A.6) — Calcite-primary
 * (CALCITE-PHASE-2 landed), declare-schema §A.6 fallback, loud-fail otherwise.
 * NEVER an LLM / schemaInferenceService (ADR 0011).
 */
class SqlModelSchemaServiceTest {

    /** A real validator — the Calcite lane's deterministic, zero-LLM derivation. */
    private final SqlModelSchemaService service =
            new SqlModelSchemaService(new CalciteSqlModelValidator());

    private static Map<String, Object> col(String name, String type) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("type", type);
        return m;
    }

    private static Map<String, Object> step(String name, String sql) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("sql", sql);
        return m;
    }

    private static Schema inputSchema() {
        return Schema.of(
                ColumnModel.simple("loan_id", "string"),
                ColumnModel.simple("amount", "double"));
    }

    @Test
    void calcitePrimaryDerivesOutputFromSteps() {
        ResolvedConfig cfg = new ResolvedConfig(Map.of(
                "steps", List.of(step("s1",
                        "SELECT loan_id, amount AS total FROM input"))));
        Schema out = service.resolve(inputSchema(), cfg);
        assertEquals(List.of("loan_id", "total"), out.names());
        assertEquals("double", out.find("total").type());
    }

    @Test
    void calciteErrorFallsBackToDeclaredSchema() {
        // 'nope' is not a column on input -> Calcite error -> §A.6 declared fallback.
        ResolvedConfig cfg = new ResolvedConfig(Map.of(
                "steps", List.of(step("s1", "SELECT nope FROM input")),
                "declared_schema", List.of(
                        col("loan_id", "string"),
                        col("total", "double"))));
        Schema out = service.resolve(inputSchema(), cfg);
        assertEquals(List.of("loan_id", "total"), out.names());
    }

    @Test
    void calciteErrorWithNoDeclaredSchemaLoudFails() {
        ResolvedConfig cfg = new ResolvedConfig(Map.of(
                "steps", List.of(step("s1", "SELECT nope FROM input"))));
        assertThrows(BuildFailureException.class, () -> service.resolve(inputSchema(), cfg));
    }

    @Test
    void noStepsFallsToDeclareSchemaPath() {
        ResolvedConfig cfg = new ResolvedConfig(Map.of(
                "declared_schema", List.of(
                        col("loan_id", "string"),
                        col("total", "double"))));
        Schema out = service.resolveByDeclaredSchema(cfg);
        assertEquals(List.of("loan_id", "total"), out.names());
        assertEquals("double", out.find("total").type());
    }

    @Test
    void noStepsAndNoDeclaredLoudFails() {
        OpEngineException ex = assertThrows(OpEngineException.class,
                () -> service.resolve(Schema.empty(), new ResolvedConfig(Map.of())));
        assertTrue(ex.getMessage().contains("declared output schema")
                || ex.getMessage().contains("steps"));
    }
}
