package com.pulse.expression.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit suite for {@link CalciteSqlModelValidator} (SPEC #6 §F 1–3 + the
 * never-LLM assertion). Proves: schema derivation for rename / cast / join /
 * aggregate / CTE / window / struct-list / multi-step chain; invalid SQL →
 * {@link BuildFailure} (parse / unknown-col / unknown-relation / unresolved-type /
 * unregistered-fn / unaliased-expr / dup-name); the declare-schema 3-way
 * fallback; determinism; and that the validator is PURE (no schemaInference, no
 * network, no clock).
 *
 * <p>Codegen lowering (§F-4) and {@code materialize} emission (§F-5) are codegen
 * concerns deferred to the integration plan (calcite-INTEGRATION-PLAN.md) — they
 * are NOT this validator's surface, which treats {@code materialize} as
 * schema-irrelevant (asserted below) and never emits SQL.
 */
class CalciteSqlModelValidatorTest {

    private final CalciteSqlModelValidator validator = new CalciteSqlModelValidator();

    // ---- helpers -------------------------------------------------

    private static Map<String, Object> col(String name, String type, boolean nullable) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("type", type);
        m.put("nullable", nullable);
        return m;
    }

    private static List<Map<String, Object>> cols(Map<String, Object>... c) {
        return new ArrayList<>(List.of(c));
    }

    private static Map<String, List<Map<String, Object>>> catalog(String rel, List<Map<String, Object>> schema) {
        Map<String, List<Map<String, Object>>> cat = new LinkedHashMap<>();
        cat.put(rel, schema);
        return cat;
    }

    /** The standard single-relation "input" catalog used by most tests. */
    private static Map<String, List<Map<String, Object>>> inputCatalog(List<Map<String, Object>> schema) {
        return catalog("input", schema);
    }

    private static List<String> names(List<Map<String, Object>> schema) {
        return schema.stream().map(c -> String.valueOf(c.get("name"))).toList();
    }

    private static String typeOf(List<Map<String, Object>> schema, String name) {
        return schema.stream().filter(c -> name.equalsIgnoreCase(String.valueOf(c.get("name"))))
                .map(c -> String.valueOf(c.get("type"))).findFirst().orElse(null);
    }

    // =============================================================
    // §F-1 — (input schema + SQL) -> expected output schema
    // =============================================================

    @Test
    void rename_aliasesColumns() {
        var input = cols(col("account_id", "long", false), col("amt", "double", true));
        var r = validator.validateStatement(
                "SELECT account_id AS acct, amt AS amount FROM input", inputCatalog(input));
        assertFalse(r.isFailure(), () -> "unexpected failure: " + (r.failure() != null ? r.failure().message() : ""));
        assertEquals(List.of("acct", "amount"), names(r.schema()));
        assertEquals("long", typeOf(r.schema(), "acct"));
        assertEquals("double", typeOf(r.schema(), "amount"));
    }

    @Test
    void bareColumn_keepsSourceName() {
        var input = cols(col("id", "integer", false));
        var r = validator.validateStatement("SELECT id FROM input", inputCatalog(input));
        assertFalse(r.isFailure());
        assertEquals(List.of("id"), names(r.schema()));
        assertEquals("integer", typeOf(r.schema(), "id"));
    }

    @Test
    void cast_derivesTargetType() {
        var input = cols(col("raw", "string", true));
        var r = validator.validateStatement(
                "SELECT CAST(raw AS INTEGER) AS n FROM input", inputCatalog(input));
        assertFalse(r.isFailure());
        assertEquals("integer", typeOf(r.schema(), "n"));
    }

    @Test
    void join_combinesBothSides() {
        var cat = new LinkedHashMap<String, List<Map<String, Object>>>();
        cat.put("input", cols(col("id", "long", false), col("amt", "double", true)));
        cat.put("dim", cols(col("id", "long", false), col("label", "string", true)));
        var r = validator.validateStatement(
                "SELECT input.amt AS amt, dim.label AS label "
                        + "FROM input JOIN dim ON input.id = dim.id", cat);
        assertFalse(r.isFailure(), () -> failMsg(r));
        assertEquals(List.of("amt", "label"), names(r.schema()));
    }

    @Test
    void aggregate_derivesGroupAndAgg() {
        var input = cols(col("grp", "string", true), col("amt", "double", true));
        var r = validator.validateStatement(
                "SELECT grp AS grp, SUM(amt) AS total, COUNT(*) AS cnt "
                        + "FROM input GROUP BY grp", inputCatalog(input));
        assertFalse(r.isFailure(), () -> failMsg(r));
        assertEquals(List.of("grp", "total", "cnt"), names(r.schema()));
        assertEquals("double", typeOf(r.schema(), "total"));
        assertEquals("long", typeOf(r.schema(), "cnt"));
    }

    @Test
    void cte_resolvesWithClause() {
        var input = cols(col("id", "long", false), col("amt", "double", true));
        var r = validator.validateStatement(
                "WITH stg AS (SELECT id AS id, amt AS amt FROM input) "
                        + "SELECT id AS id, amt AS amt FROM stg", inputCatalog(input));
        assertFalse(r.isFailure(), () -> failMsg(r));
        assertEquals(List.of("id", "amt"), names(r.schema()));
    }

    @Test
    void window_derivesRowNumber() {
        var input = cols(col("grp", "string", true), col("amt", "double", true));
        var r = validator.validateStatement(
                "SELECT grp AS grp, "
                        + "ROW_NUMBER() OVER (PARTITION BY grp ORDER BY amt) AS rn FROM input",
                inputCatalog(input));
        assertFalse(r.isFailure(), () -> failMsg(r));
        assertEquals(List.of("grp", "rn"), names(r.schema()));
        assertEquals("long", typeOf(r.schema(), "rn"));
    }

    @Test
    void struct_roundTripsNested() {
        // struct addr { city string, zip string }
        Map<String, Object> addr = new LinkedHashMap<>();
        addr.put("name", "addr");
        addr.put("type", "struct");
        addr.put("nullable", true);
        addr.put("fields", List.of(col("city", "string", true), col("zip", "string", true)));
        var input = cols(col("id", "long", false), addr);
        var r = validator.validateStatement("SELECT id AS id, addr AS addr FROM input", inputCatalog(input));
        assertFalse(r.isFailure(), () -> failMsg(r));
        assertEquals("struct", typeOf(r.schema(), "addr"));
        var addrOut = r.schema().stream().filter(c -> "addr".equals(c.get("name"))).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) addrOut.get("fields");
        assertEquals(List.of("city", "zip"), names(fields));
    }

    @Test
    void list_roundTripsNested() {
        Map<String, Object> tags = new LinkedHashMap<>();
        tags.put("name", "tags");
        tags.put("type", "list");
        tags.put("nullable", true);
        tags.put("element", "string");
        var input = cols(col("id", "long", false), tags);
        var r = validator.validateStatement("SELECT id AS id, tags AS tags FROM input", inputCatalog(input));
        assertFalse(r.isFailure(), () -> failMsg(r));
        assertEquals("list", typeOf(r.schema(), "tags"));
    }

    @Test
    void multiStepChain_returnsLastStepSchema() {
        var input = cols(col("grp", "string", true), col("amt", "double", true),
                col("biz_date", "date", true));
        List<CalciteSqlModelValidator.SqlModelStep> steps = List.of(
                CalciteSqlModelValidator.SqlModelStep.of("stg",
                        "SELECT grp AS grp, amt AS amt FROM input WHERE biz_date >= [[ BOM-1 ]]"),
                new CalciteSqlModelValidator.SqlModelStep("agg",
                        "SELECT grp AS grp, SUM(amt) AS total FROM stg GROUP BY grp", true),
                CalciteSqlModelValidator.SqlModelStep.of("out",
                        "SELECT grp AS grp, total AS total FROM agg"));
        var r = validator.validateChain(input, steps);
        assertFalse(r.isFailure(), () -> failMsg(r));
        assertEquals(List.of("grp", "total"), names(r.schema()));
        assertEquals("double", typeOf(r.schema(), "total"));
    }

    @Test
    void dateMnemonic_typesAsDateInPredicateAndFunctionArg() {
        var input = cols(col("biz_date", "date", true));
        // [[ … ]] in a function arg (DATE_SUB) AND a bare predicate operand — both
        // must type as DATE (§D.3). NOTE: DATE_SUB is used (not DATEDIFF) for the
        // function-arg case because the Babel parser has a SPECIAL DATEDIFF
        // grammar that rejects any non-identifier first operand (incl. the DATE
        // placeholder) — a parser limitation, not a typing one (see
        // DateMnemonicTokenScanner javadoc). DATE_SUB exercises the same
        // "token typed as DATE inside a function argument" contract.
        var r = validator.validateStatement(
                "SELECT DATE_SUB([[ RUN_DATE ]], 1) AS prev FROM input "
                        + "WHERE biz_date >= [[ BOM-1 ]]", inputCatalog(input));
        assertFalse(r.isFailure(), () -> failMsg(r));
        assertEquals(List.of("prev"), names(r.schema()));
        assertEquals("date", typeOf(r.schema(), "prev"));
    }

    @Test
    void registeredSparkFunction_sha2_resolves() {
        var input = cols(col("ssn", "string", true));
        var r = validator.validateStatement(
                "SELECT SHA2(ssn, 256) AS ssn_hash FROM input", inputCatalog(input));
        assertFalse(r.isFailure(), () -> failMsg(r));
        assertEquals("string", typeOf(r.schema(), "ssn_hash"));
    }

    // =============================================================
    // §F-2 — invalid SQL -> BuildFailure (no schema)
    // =============================================================

    @Test
    void parseError_yieldsBuildFailure() {
        var input = cols(col("id", "long", false));
        var r = validator.validateStatement("SELECT FROM WHERE", inputCatalog(input));
        assertTrue(r.isFailure());
        assertNull(r.schema());
        assertEquals(BuildFailure.Cause.PARSE_ERROR.name(), r.failure().cause());
    }

    @Test
    void unknownColumn_yieldsBuildFailure() {
        var input = cols(col("id", "long", false));
        var r = validator.validateStatement("SELECT nope AS x FROM input", inputCatalog(input));
        assertTrue(r.isFailure());
        assertEquals(BuildFailure.Cause.UNKNOWN_COLUMN.name(), r.failure().cause());
    }

    @Test
    void unknownRelation_yieldsBuildFailure() {
        var input = cols(col("id", "long", false));
        var r = validator.validateStatement("SELECT id AS id FROM nonesuch", inputCatalog(input));
        assertTrue(r.isFailure());
        // Calcite collapses object/table-not-found into the context family; we accept
        // either UNKNOWN_RELATION (clear table miss) or the documented UNKNOWN_COLUMN
        // default (BuildFailure honesty note) — but it MUST be a failure with no schema.
        assertNull(r.schema());
        assertTrue(BuildFailure.Cause.UNKNOWN_RELATION.name().equals(r.failure().cause())
                        || BuildFailure.Cause.UNKNOWN_COLUMN.name().equals(r.failure().cause()),
                "expected relation/column miss, got " + r.failure().cause());
    }

    @Test
    void unresolvedType_yieldsBuildFailure() {
        // adding a string to a date is a type error the validator rejects.
        var input = cols(col("name", "string", true), col("d", "date", true));
        var r = validator.validateStatement("SELECT (name + d) AS bad FROM input", inputCatalog(input));
        assertTrue(r.isFailure());
        assertNull(r.schema());
    }

    @Test
    void unregisteredFunction_yieldsBuildFailure() {
        var input = cols(col("x", "string", true));
        var r = validator.validateStatement(
                "SELECT TOTALLY_NOT_A_FUNCTION(x) AS y FROM input", inputCatalog(input));
        assertTrue(r.isFailure());
        assertNull(r.schema());
        assertEquals(BuildFailure.Cause.UNREGISTERED_FUNCTION.name(), r.failure().cause());
    }

    @Test
    void unaliasedExpression_yieldsBuildFailure() {
        var input = cols(col("amt", "double", true));
        var r = validator.validateStatement("SELECT amt + 1 FROM input", inputCatalog(input));
        assertTrue(r.isFailure());
        assertNull(r.schema());
        assertEquals(BuildFailure.Cause.UNALIASED_EXPRESSION.name(), r.failure().cause());
    }

    @Test
    void duplicateOutputName_yieldsBuildFailure() {
        var input = cols(col("a", "long", false), col("b", "long", false));
        var r = validator.validateStatement("SELECT a AS x, b AS x FROM input", inputCatalog(input));
        assertTrue(r.isFailure());
        assertNull(r.schema());
        assertEquals(BuildFailure.Cause.DUPLICATE_OUTPUT_NAME.name(), r.failure().cause());
    }

    @Test
    void chain_duplicateStepName_yieldsBuildFailure() {
        var input = cols(col("id", "long", false));
        List<CalciteSqlModelValidator.SqlModelStep> steps = List.of(
                CalciteSqlModelValidator.SqlModelStep.of("s", "SELECT id AS id FROM input"),
                CalciteSqlModelValidator.SqlModelStep.of("s", "SELECT id AS id FROM input"));
        var r = validator.validateChain(input, steps);
        assertTrue(r.isFailure());
        assertEquals(BuildFailure.Cause.STEP_NAME_COLLISION.name(), r.failure().cause());
    }

    // =============================================================
    // §F-3 — declare-schema EITHER/OR fallback (3-way)
    // =============================================================

    @Test
    void fallback_calciteValid_derivedSchemaWins() {
        var input = cols(col("id", "long", false));
        var declared = cols(col("WRONG", "string", true)); // should be IGNORED on success
        List<CalciteSqlModelValidator.SqlModelStep> steps = List.of(
                CalciteSqlModelValidator.SqlModelStep.of("out", "SELECT id AS id FROM input"));
        var resolved = validator.resolveSqlModelSchema(input, steps, declared);
        assertEquals(List.of("id"), names(resolved));
    }

    @Test
    void fallback_calciteError_withDeclared_usesDeclared() {
        var input = cols(col("id", "long", false));
        var declared = cols(col("declared_col", "string", true));
        // references an unknown column -> Calcite error -> fall back to declared.
        List<CalciteSqlModelValidator.SqlModelStep> steps = List.of(
                CalciteSqlModelValidator.SqlModelStep.of("out", "SELECT ghost AS g FROM input"));
        var resolved = validator.resolveSqlModelSchema(input, steps, declared);
        assertSame(declared, resolved);
        assertEquals(List.of("declared_col"), names(resolved));
    }

    @Test
    void fallback_calciteError_noDeclared_loudFail() {
        var input = cols(col("id", "long", false));
        List<CalciteSqlModelValidator.SqlModelStep> steps = List.of(
                CalciteSqlModelValidator.SqlModelStep.of("out", "SELECT ghost AS g FROM input"));
        BuildFailureException ex = assertThrows(BuildFailureException.class,
                () -> validator.resolveSqlModelSchema(input, steps, null));
        assertNotNull(ex.buildFailure());
        assertEquals(BuildFailure.Cause.UNKNOWN_COLUMN.name(), ex.buildFailure().cause());
    }

    // =============================================================
    // §B.3 — materialize is schema-irrelevant
    // =============================================================

    @Test
    void materialize_doesNotAffectSchema() {
        var input = cols(col("id", "long", false), col("amt", "double", true));
        var t = validator.validateChain(input, List.of(
                new CalciteSqlModelValidator.SqlModelStep("out", "SELECT id AS id, amt AS amt FROM input", true)));
        var f = validator.validateChain(input, List.of(
                new CalciteSqlModelValidator.SqlModelStep("out", "SELECT id AS id, amt AS amt FROM input", false)));
        assertFalse(t.isFailure());
        assertFalse(f.isFailure());
        assertEquals(names(t.schema()), names(f.schema()));
        assertEquals(t.schema(), f.schema());
    }

    // =============================================================
    // §A.7 — determinism (same inputs -> byte-identical schema)
    // =============================================================

    @Test
    void determinism_sameInputsSameSchema() {
        var input = cols(col("a", "long", false), col("b", "double", true));
        String sql = "SELECT a AS a, b AS b, a + 1 AS a_plus FROM input";
        var r1 = validator.validateStatement(sql, inputCatalog(input));
        var r2 = validator.validateStatement(sql, inputCatalog(input));
        assertFalse(r1.isFailure());
        assertEquals(r1.schema(), r2.schema());
    }

    // =============================================================
    // ADR-0011 — the validator is PURE: never an LLM / schema-inference path
    // =============================================================

    /**
     * The validator class must have NO reference to any schema-inference / LLM
     * service — derivation is Calcite / declared / loud-fail only (ADR 0011).
     * Asserted reflectively over the declared fields so a future maintainer who
     * wires in a model dependency trips this test.
     */
    @Test
    void neverLlm_noSchemaInferenceDependency() {
        for (var f : CalciteSqlModelValidator.class.getDeclaredFields()) {
            String t = f.getType().getName().toLowerCase();
            assertFalse(t.contains("schemainference") || t.contains("llm")
                            || t.contains("openrouter") || t.contains("vertex") || t.contains("chatmodel"),
                    "validator must not depend on any LLM/schema-inference type, found: " + f.getType().getName());
        }
        // And the resolve path returns deterministically from a pure input set
        // (no network/clock) — a second identical call yields an equal schema.
        var input = cols(col("id", "long", false));
        var steps = List.of(CalciteSqlModelValidator.SqlModelStep.of("out", "SELECT id AS id FROM input"));
        assertEquals(validator.resolveSqlModelSchema(input, steps, null),
                validator.resolveSqlModelSchema(input, steps, null));
    }

    private static String failMsg(CalciteSqlModelValidator.SqlModelResult r) {
        return r.failure() == null ? "(no failure)"
                : (r.failure().cause() + ": " + r.failure().message());
    }
}
