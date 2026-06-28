package com.pulse.pipeline.opengine;

import com.pulse.codegen.audit.IngestionAuditColumns;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase 1 — the op-engine spine: walks the op-list and applies each rule in order. */
class SchemaOpEngineTest {

    private final SchemaOpEngine engine = new SchemaOpEngine(
            new SqlModelSchemaService(new com.pulse.expression.service.CalciteSqlModelValidator()));

    @Test
    void registryCoversExactly32Ops() {
        assertEquals(32, engine.registry().size());
        assertEquals(32, OpVocabulary.ALL.size());
    }

    @Test
    void walksOpsInOrder_renameThenDrop() {
        // rename a->a2, then drop b: input {a,b,c} => {a2, c}
        Map<String, Object> sb = new LinkedHashMap<>();
        sb.put("ops", List.of(
                OpList.opEntryMap("rename-columns", "Rename",
                        Map.of("rename_map", Map.of("param", "rmap"))),
                OpList.opEntryMap("drop-columns", "Drop",
                        Map.of("drop_columns", Map.of("param", "drops")))));
        OpList ol = OpList.parse(sb);
        ParamSurface surface = ParamSurface.derive(ol, List.of(
                Map.of("name", "rmap", "type", "object"),
                Map.of("name", "drops", "type", "array")));

        Schema in = Schema.of(
                ColumnModel.simple("a", "string"),
                ColumnModel.simple("b", "string"),
                ColumnModel.simple("c", "integer"));
        Map<String, Object> params = Map.of(
                "rmap", Map.of("a", "a2"),
                "drops", List.of("b"));

        Schema out = engine.applyOpList(ol, surface, params, in, null);
        assertEquals(List.of("a2", "c"), out.names());
    }

    @Test
    void readSourceThenAddAuditColumns_yields8Audit() {
        // ingestion shape: read-source -> add-audit-columns ; input = 2 source cols => 2 + 8
        Map<String, Object> sb = new LinkedHashMap<>();
        sb.put("ops", List.of(
                OpList.opEntryMap("read-source", "Read", Map.of()),
                OpList.opEntryMap("add-audit-columns", "Audit", Map.of())));
        OpList ol = OpList.parse(sb);
        ParamSurface surface = ParamSurface.derive(ol, List.of());

        Schema in = Schema.of(
                ColumnModel.simple("loan_id", "string"),
                ColumnModel.simple("amount", "decimal"));
        Schema out = engine.applyOpList(ol, surface, Map.of(), in, null);
        assertEquals(10, out.size()); // 2 source + 8 audit
        assertTrue(out.hasColumn("_pulse_dag_id"));
    }

    @Test
    void addAuditColumnsIsIdempotentByColumnName() {
        Map<String, Object> sb = new LinkedHashMap<>();
        sb.put("ops", List.of(OpList.opEntryMap("add-audit-columns", "Audit", Map.of())));
        OpList ol = OpList.parse(sb);
        ParamSurface surface = ParamSurface.derive(ol, List.of());

        List<ColumnModel> inputColumns = new ArrayList<>();
        inputColumns.add(ColumnModel.simple("loan_id", "string"));
        for (Map<String, Object> audit : IngestionAuditColumns.asColumnDescriptors()) {
            inputColumns.add(ColumnModel.fromMap(audit));
        }

        Schema out = engine.applyOpList(ol, surface, Map.of(), new Schema(inputColumns), null);

        assertEquals(1 + IngestionAuditColumns.NAMES.size(), out.size());
        for (String auditName : IngestionAuditColumns.NAMES) {
            assertEquals(1, out.names().stream().filter(auditName::equals).count(),
                    "audit column duplicated: " + auditName);
        }
    }

    @Test
    void mapOverloadReadsDatasetFieldsBeforeAddingAuditColumns() {
        Map<String, Object> sb = new LinkedHashMap<>();
        sb.put("ops", List.of(
                OpList.opEntryMap("read-source", "Read", Map.of()),
                OpList.opEntryMap("add-audit-columns", "Audit", Map.of())));
        OpList ol = OpList.parse(sb);
        ParamSurface surface = ParamSurface.derive(ol, List.of());

        Map<String, Object> datasetSnapshot = Map.of("fields", List.of(
                Map.of("name", "loan_id", "type", "VARCHAR"),
                Map.of("name", "borrower_name", "type", "VARCHAR")));

        Map<String, Object> outWrapper = engine.applyOpListAsMap(
                ol, surface, Map.of(), datasetSnapshot, null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cols = (List<Map<String, Object>>) outWrapper.get("columns");
        assertEquals(10, cols.size()); // 2 dataset fields + 8 audit
        assertEquals("loan_id", cols.get(0).get("name"));
        assertEquals("borrower_name", cols.get(1).get("name"));
        assertEquals("_pulse_ingested_at", cols.get(2).get("name"));
    }

    @Test
    void sqlModelOpResolvesCalcitePrimaryThroughWalker() {
        // sql-model derives OUT from the steps chain against the input schema (Calcite-primary),
        // walked through the engine. Never an LLM / schemaInferenceService (ADR 0011).
        Map<String, Object> sb = new LinkedHashMap<>();
        sb.put("ops", List.of(
                OpList.opEntryMap("sql-model", "Model",
                        Map.of("steps", Map.of("param", "steps")))));
        OpList ol = OpList.parse(sb);
        ParamSurface surface = ParamSurface.derive(ol, List.of(
                Map.of("name", "steps", "type", "array")));

        Schema in = Schema.of(
                ColumnModel.simple("loan_id", "string"),
                ColumnModel.simple("amount", "double"));
        Map<String, Object> params = Map.of("steps", List.of(
                Map.of("name", "s1", "sql", "SELECT loan_id, amount AS total FROM input")));

        Schema out = engine.applyOpList(ol, surface, params, in, null);
        assertEquals(List.of("loan_id", "total"), out.names());
        assertEquals("double", out.find("total").type());
    }

    @Test
    void unknownOpInListLoudFailsAtParse() {
        Map<String, Object> sb = new LinkedHashMap<>();
        sb.put("ops", List.of(OpList.opEntryMap("teleport-rows", "x", Map.of())));
        assertThrows(OpEngineException.class, () -> OpList.parse(sb));
    }

    @Test
    void mapOverloadRoundTripsLegacyWrapper() {
        Map<String, Object> sb = new LinkedHashMap<>();
        sb.put("ops", List.of(OpList.opEntryMap("keep-columns", "Keep",
                Map.of("keep_columns", Map.of("param", "kc")))));
        OpList ol = OpList.parse(sb);
        ParamSurface surface = ParamSurface.derive(ol, List.of(Map.of("name", "kc", "type", "array")));

        Map<String, Object> inWrapper = new LinkedHashMap<>();
        inWrapper.put("columns", List.of(
                Map.of("name", "x", "type", "string"),
                Map.of("name", "y", "type", "string")));
        Map<String, Object> outWrapper = engine.applyOpListAsMap(
                ol, surface, Map.of("kc", List.of("y")), inWrapper, null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cols = (List<Map<String, Object>>) outWrapper.get("columns");
        assertEquals(1, cols.size());
        assertEquals("y", cols.get(0).get("name"));
    }
}
