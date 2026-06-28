package com.pulse.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.blueprint.model.Blueprint;
import com.pulse.blueprint.repository.BlueprintRepository;
import com.pulse.expression.service.ExpressionValidationService;
import com.pulse.pipeline.model.Pipeline;
import com.pulse.pipeline.model.PortWiring;
import com.pulse.pipeline.model.SubPipelineInstance;
import com.pulse.pipeline.repository.PipelineRepository;
import com.pulse.pipeline.service.CompositionService;
import com.pulse.pipeline.service.CompositionService.CompositionView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Phase 9 — deterministic logic behind the new validation read tools. The
 * service is pure logic over mocked CompositionService / BlueprintRepository,
 * so these are fast Mockito unit tests (no Spring / H2 context needed).
 */
@ExtendWith(MockitoExtension.class)
class ChatValidationToolServiceTest {

    @Mock private CompositionService compositionService;
    @Mock private BlueprintRepository blueprintRepo;
    @Mock private PipelineRepository pipelineRepo;
    @Mock private ChatReadToolHandler readToolHandler;

    private ChatValidationToolService service;

    @BeforeEach
    void setUp() {
        service = new ChatValidationToolService(
                compositionService, blueprintRepo, pipelineRepo,
                new ExpressionValidationService(), readToolHandler, new ObjectMapper());
        lenient().when(readToolHandler.checkTableContractReadiness(anyString(), anyMap()))
                .thenReturn("{\"ready\":true}");
    }

    private SubPipelineInstance inst(String id, String name, String bpKey) {
        SubPipelineInstance i = new SubPipelineInstance();
        i.setId(id);
        i.setName(name);
        i.setBlueprintKey(bpKey);
        return i;
    }

    private PortWiring wire(String srcId, String srcPort, String tgtId, String tgtPort) {
        PortWiring w = new PortWiring();
        w.setSourceInstanceId(srcId);
        w.setSourcePortName(srcPort);
        w.setTargetInstanceId(tgtId);
        w.setTargetPortName(tgtPort);
        return w;
    }

    private Blueprint bp(String key, List<Map<String, Object>> inputPorts,
                        List<Map<String, Object>> paramsSchema) {
        Blueprint b = new Blueprint();
        b.setBlueprintKey(key);
        b.setInputPorts(inputPorts);
        b.setParamsSchema(paramsSchema);
        return b;
    }

    // ---------------- get_composition_overview ----------------

    @Test
    void getCompositionOverviewCountsStepsAndWires() {
        Pipeline p = new Pipeline();
        p.setId("pipe-1");
        p.setName("Loan Master");
        p.setActiveVersionId("v-1");
        when(pipelineRepo.findById("pipe-1")).thenReturn(Optional.of(p));

        SubPipelineInstance ingest = inst("i1", "Ingest", "FileIngestion");
        ingest.setLakeLayer("bronze");
        SubPipelineInstance clean = inst("i2", "Clean", "BronzeToSilverCleaning");
        clean.setLakeLayer("silver");
        when(compositionService.getComposition("v-1")).thenReturn(new CompositionView(
                List.of(ingest, clean),
                List.of(wire("i1", "raw_output", "i2", "data_input"))));
        // Both blueprints' ports resolved as having no required input ports here.
        when(blueprintRepo.findByBlueprintKey(anyString()))
                .thenReturn(Optional.of(bp("X", List.of(), List.of())));

        String out = service.getCompositionOverview("pipe-1");
        assertTrue(out.contains("Steps: 2"), out);
        assertTrue(out.contains("Wires: 1"), out);
        assertTrue(out.contains("bronze") && out.contains("silver"), out);
        // Neither step has an output schema set → both unresolved.
        assertTrue(out.contains("unresolved output schema: 2"), out);
    }

    // ---------------- validate_structure: orphan + cycle ----------------

    @Test
    void validateStructureDetectsOrphan() {
        SubPipelineInstance a = inst("a", "A", "FileIngestion");
        SubPipelineInstance b = inst("b", "B", "BronzeToSilverCleaning");
        SubPipelineInstance orphan = inst("c", "Orphan", "DQValidator");
        when(compositionService.getComposition("v-1")).thenReturn(new CompositionView(
                List.of(a, b, orphan),
                List.of(wire("a", "out", "b", "in"))));
        when(blueprintRepo.findByBlueprintKey(anyString()))
                .thenReturn(Optional.of(bp("X", List.of(), List.of())));

        String out = service.validateStructure(Map.of("version_id", "v-1"));
        assertTrue(out.contains("ORPHAN_STEP"), out);
        assertTrue(out.contains("Orphan"), out);
    }

    @Test
    void validateStructureDetectsCycle() {
        SubPipelineInstance a = inst("a", "A", "SqlModel");
        SubPipelineInstance b = inst("b", "B", "SqlModel");
        when(compositionService.getComposition("v-1")).thenReturn(new CompositionView(
                List.of(a, b),
                List.of(wire("a", "out", "b", "in"), wire("b", "out", "a", "in"))));
        when(blueprintRepo.findByBlueprintKey(anyString()))
                .thenReturn(Optional.of(bp("X", List.of(), List.of())));

        String out = service.validateStructure(Map.of("version_id", "v-1"));
        assertTrue(out.contains("CYCLE_DETECTED"), out);
    }

    @Test
    void validateStructureFlagsUnwiredRequiredPort() {
        SubPipelineInstance a = inst("a", "A", "FileIngestion");
        SubPipelineInstance b = inst("b", "Clean", "BronzeToSilverCleaning");
        when(compositionService.getComposition("v-1")).thenReturn(new CompositionView(
                List.of(a, b),
                // a -> b on a DIFFERENT port; data_input on b stays unwired.
                List.of(wire("a", "out", "b", "other_input"))));
        when(blueprintRepo.findByBlueprintKey("FileIngestion"))
                .thenReturn(Optional.of(bp("FileIngestion", List.of(), List.of())));
        when(blueprintRepo.findByBlueprintKey("BronzeToSilverCleaning"))
                .thenReturn(Optional.of(bp("BronzeToSilverCleaning",
                        List.of(Map.of("name", "data_input")), List.of())));

        String out = service.validateStructure(Map.of("version_id", "v-1"));
        assertTrue(out.contains("UNWIRED_REQUIRED_PORT"), out);
        assertTrue(out.contains("data_input"), out);
    }

    @Test
    void validateStructureCleanGraphIsOk() {
        SubPipelineInstance a = inst("a", "A", "FileIngestion");
        SubPipelineInstance b = inst("b", "Clean", "BronzeToSilverCleaning");
        when(compositionService.getComposition("v-1")).thenReturn(new CompositionView(
                List.of(a, b),
                List.of(wire("a", "raw_output", "b", "data_input"))));
        when(blueprintRepo.findByBlueprintKey("FileIngestion"))
                .thenReturn(Optional.of(bp("FileIngestion", List.of(), List.of())));
        when(blueprintRepo.findByBlueprintKey("BronzeToSilverCleaning"))
                .thenReturn(Optional.of(bp("BronzeToSilverCleaning",
                        List.of(Map.of("name", "data_input")), List.of())));

        String out = service.validateStructure(Map.of("version_id", "v-1"));
        assertTrue(out.contains("OK"), out);
        assertTrue(out.contains("No issues"), out);
    }

    // ---------------- validate_configuration ----------------

    @Test
    void validateConfigurationFlagsMissingRequiredParam() {
        SubPipelineInstance a = inst("a", "Join", "GenericJoin");
        when(compositionService.getComposition("v-1")).thenReturn(new CompositionView(
                List.of(a), List.of()));
        when(blueprintRepo.findByBlueprintKey("GenericJoin"))
                .thenReturn(Optional.of(bp("GenericJoin", List.of(),
                        List.of(Map.of("name", "join_keys", "required", true, "tier", "user")))));

        String out = service.validateConfiguration(Map.of("version_id", "v-1"), "tenant-1");
        assertTrue(out.contains("MISSING_REQUIRED_PARAM"), out);
        assertTrue(out.contains("join_keys"), out);
        assertTrue(out.contains("punch-list"), out);
    }

    // ---------------- validate_plan (interim) ----------------

    @Test
    void validatePlanIsLabelledInterim() {
        SubPipelineInstance a = inst("a", "A", "FileIngestion");
        when(compositionService.getComposition("v-1")).thenReturn(new CompositionView(
                List.of(a), List.of()));
        when(blueprintRepo.findByBlueprintKey(anyString()))
                .thenReturn(Optional.of(bp("X", List.of(), List.of())));

        String out = service.validatePlan(Map.of("version_id", "v-1"), "tenant-1");
        assertTrue(out.contains("INTERIM"), out);
        assertTrue(out.contains("Structure"), out);
        assertTrue(out.contains("Configuration"), out);
        assertTrue(out.contains("Contract readiness"), out);
    }

    // ---------------- get_blueprint_op_list ----------------

    @Test
    void getBlueprintOpListReadsSeededSchemaBehavior() {
        Blueprint b = bp("FileIngestion", List.of(), List.of());
        b.setSchemaBehavior(Map.of(
                "version", 1,
                "ops", List.of(
                        Map.of("op", "read-source", "ui_label", "Read source"),
                        Map.of("op", "write-sink", "ui_label", "Write to bronze",
                                "config", Map.of("target", "bronze")))));
        when(blueprintRepo.findByBlueprintKey("FileIngestion")).thenReturn(Optional.of(b));

        String out = service.getBlueprintOpList("FileIngestion");
        assertTrue(out.contains("read-source"), out);
        assertTrue(out.contains("write-sink"), out);
        assertFalse(out.contains("V153 pending"), out);
    }

    @Test
    void getBlueprintOpListFallsBackWhenNotSeeded() {
        Blueprint b = bp("Unseeded", List.of(Map.of("name", "data_input")),
                List.of(Map.of("name", "p1", "required", true)));
        // No schemaBehavior set.
        when(blueprintRepo.findByBlueprintKey("Unseeded")).thenReturn(Optional.of(b));

        String out = service.getBlueprintOpList("Unseeded");
        assertTrue(out.contains("V153 pending"), out);
        assertTrue(out.contains("data_input"), out);
        assertTrue(out.contains("p1*"), out);
    }

    // ---------------- validate_sql_expression ----------------

    @Test
    void validateSqlExpressionParseValid() {
        String out = service.validateSqlExpression(Map.of("expression", "amount * 1.1"));
        assertTrue(out.contains("PARSE-VALID"), out);
        assertTrue(out.contains("Parse-only"), out);
    }

    @Test
    void validateSqlExpressionParseInvalid() {
        String out = service.validateSqlExpression(Map.of("expression", "amount +"));
        assertTrue(out.contains("INVALID"), out);
    }

    @Test
    void validateSqlExpressionFlagsUnknownColumnAgainstSchema() {
        String out = service.validateSqlExpression(Map.of(
                "expression", "nope + 1",
                "input_schemas", List.of(Map.of(
                        "port_name", "data_input",
                        "columns", List.of(Map.of("name", "amount", "type", "decimal"))))));
        // 'nope' is unqualified + not on any port → a warning diagnostic.
        assertTrue(out.contains("nope"), out);
    }
}
