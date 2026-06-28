package com.pulse.pipeline.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.blueprint.model.Blueprint;
import com.pulse.blueprint.model.BlueprintCategory;
import com.pulse.blueprint.repository.BlueprintRepository;
import com.pulse.codegen.audit.IngestionAuditColumns;
import com.pulse.pipeline.model.InstancePortSchema;
import com.pulse.pipeline.model.PortWiring;
import com.pulse.pipeline.model.SchemaConflict;
import com.pulse.pipeline.model.SubPipelineInstance;
import com.pulse.pipeline.repository.InstancePortSchemaRepository;
import com.pulse.pipeline.repository.PortWiringRepository;
import com.pulse.pipeline.repository.SchemaConflictRepository;
import com.pulse.pipeline.repository.SubPipelineInstanceRepository;
import com.pulse.sor.repository.DatasetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchemaPropagationServiceTest {

    @Mock private SubPipelineInstanceRepository instanceRepo;
    @Mock private PortWiringRepository wiringRepo;
    @Mock private BlueprintRepository blueprintRepo;
    @Mock private InstancePortSchemaRepository portSchemaRepo;
    @Mock private SchemaConflictRepository conflictRepo;
    @Mock private DatasetRepository datasetRepo;
    @Mock private SourceSqlSchemaResolver sourceSqlSchemaResolver;
    @Mock private JdbcTemplate jdbcTemplate;

    private SchemaPropagationService service;

    private final List<InstancePortSchema> storedPorts = new ArrayList<>();
    private final List<SchemaConflict> storedConflicts = new ArrayList<>();

    @BeforeEach
    void setUp() {
        // The op-engine collaborators are pure, stateless deterministic services — use REAL
        // instances (not mocks). Legacy test blueprints carry no schema_behavior op-list, so
        // they resolve via the transitional legacy-shim path; the engine path only fires when a
        // blueprint has an op-list.
        service = new SchemaPropagationService(
                instanceRepo, wiringRepo, blueprintRepo, portSchemaRepo,
                conflictRepo, datasetRepo,
                new com.pulse.pipeline.opengine.SchemaBehaviorReader(),
                new com.pulse.pipeline.opengine.SchemaOpEngine(
                        new com.pulse.pipeline.opengine.SqlModelSchemaService(
                                new com.pulse.expression.service.CalciteSqlModelValidator())),
                sourceSqlSchemaResolver,
                new com.pulse.pipeline.opengine.ConflictClassifier(),
                jdbcTemplate,
                new ObjectMapper());

        storedPorts.clear();
        storedConflicts.clear();

        lenient().when(instanceRepo.save(any(SubPipelineInstance.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(instanceRepo.saveAll(anyList()))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(portSchemaRepo.save(any(InstancePortSchema.class)))
                .thenAnswer(inv -> {
                    InstancePortSchema arg = inv.getArgument(0);
                    if (arg.getId() == null) arg.setId("ips-" + (storedPorts.size() + 1));
                    storedPorts.removeIf(p -> p.getInstanceId().equals(arg.getInstanceId())
                            && p.getPortName().equals(arg.getPortName())
                            && p.getDirection().equals(arg.getDirection()));
                    storedPorts.add(arg);
                    return arg;
                });
        lenient().when(portSchemaRepo.findByInstanceIdAndPortNameAndDirection(anyString(), anyString(), anyString()))
                .thenAnswer(inv -> storedPorts.stream()
                        .filter(p -> p.getInstanceId().equals(inv.getArgument(0))
                                && p.getPortName().equals(inv.getArgument(1))
                                && p.getDirection().equals(inv.getArgument(2)))
                        .findFirst());
        lenient().doAnswer(inv -> {
            InstancePortSchema arg = inv.getArgument(0);
            storedPorts.removeIf(p -> p == arg
                    || (arg.getId() != null && arg.getId().equals(p.getId()))
                    || (p.getInstanceId().equals(arg.getInstanceId())
                    && p.getPortName().equals(arg.getPortName())
                    && p.getDirection().equals(arg.getDirection())));
            return null;
        }).when(portSchemaRepo).delete(any(InstancePortSchema.class));
        lenient().when(conflictRepo.save(any(SchemaConflict.class)))
                .thenAnswer(inv -> {
                    SchemaConflict c = inv.getArgument(0);
                    if (c.getId() == null) c.setId("conf-" + (storedConflicts.size() + 1));
                    storedConflicts.add(c);
                    return c;
                });
    }

    @Test
    void propagate_passthroughPreservesSchema() {
        SubPipelineInstance ingest = inst("I1", "FileIngestion");
        ingest.setOutputSchema(columns("id", "string", "amount", "decimal"));
        SubPipelineInstance filter = inst("F1", "GenericFilter");
        filter.setParams(Map.of("filter_mode", "sql", "raw_sql", "amount > 0"));
        PortWiring wire = wire("I1", "raw_output", "F1", "data_input");

        stubComposition(List.of(ingest, filter), List.of(wire));
        stubBlueprint(ingestionBlueprint("FileIngestion"));
        stubBlueprint(transformBlueprint("GenericFilter", "data_input", "filtered_output"));

        service.propagateFromVersion("v1");

        List<InstancePortSchema> filterOut = storedPorts.stream()
                .filter(p -> p.getInstanceId().equals("F1") && p.getDirection().equals("output"))
                .toList();
        assertEquals(1, filterOut.size());
        List<Map<String, Object>> outCols = extractColumns(filterOut.get(0).getSchemaJson());
        // Bronze ingestion appends PULSE audit columns (IngestionAuditColumns) to
        // the source schema; filter pass-through carries the audit cols downstream.
        assertEquals(2 + IngestionAuditColumns.NAMES.size(), outCols.size());
        assertEquals("id", outCols.get(0).get("name"));
        // Audit columns must be present and tagged.
        for (String auditName : IngestionAuditColumns.NAMES) {
            assertTrue(outCols.stream().anyMatch(c -> auditName.equals(c.get("name"))),
                    "expected audit column " + auditName + " to propagate through filter");
        }
        assertEquals("clean", filter.getSchemaStatus());
    }

    @Test
    void propagate_snapshotIngestionReadsDatasetFieldsShapeBeforeAudit() {
        SubPipelineInstance ingest = inst("I1", "SnapshotIngestion");
        ingest.setParams(Map.of("dataset_ids", List.of("dataset-1")));

        stubComposition(List.of(ingest), List.of());
        Blueprint bp = ingestionBlueprint("SnapshotIngestion");
        bp.setSchemaBehavior(Map.of(
                "version", 1,
                "ops", List.of(
                        com.pulse.pipeline.opengine.OpList.opEntryMap(
                                "read-source", "Read source", Map.of()),
                        com.pulse.pipeline.opengine.OpList.opEntryMap(
                                "add-audit-columns", "Add audit columns", Map.of()),
                        com.pulse.pipeline.opengine.OpList.opEntryMap(
                                "write-sink", "Write to bronze",
                                Map.of("target", "bronze", "mode", "overwrite"))),
                "emission", Map.of("orchestration", "airflow", "compute", "pyspark")));
        stubBlueprint(bp);
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class), eq("dataset-1")))
                .thenReturn("""
                        {"fields":[
                          {"name":"loan_id","type":"VARCHAR"},
                          {"name":"borrower_name","type":"VARCHAR"}
                        ]}
                        """);

        service.propagateFromVersion("v1");

        InstancePortSchema out = outputPort("I1", "raw_output");
        List<String> names = columnNames(out);
        assertEquals(2 + IngestionAuditColumns.NAMES.size(), names.size());
        assertEquals("loan_id", names.get(0));
        assertEquals("borrower_name", names.get(1));
        assertTrue(names.contains("_pulse_dag_id"));
    }

    @Test
    void propagate_aggregateEmitsMissingColumnWhenGroupByAbsent() {
        SubPipelineInstance ingest = inst("I1", "FileIngestion");
        ingest.setOutputSchema(columns("amount", "decimal", "ts", "timestamp"));
        SubPipelineInstance agg = inst("A1", "GenericAggregate");
        agg.setParams(Map.of(
                "group_by_columns", List.of("customer_id"),
                "aggregations", List.of(Map.of("column", "amount", "function", "SUM", "alias", "total"))
        ));
        PortWiring wire = wire("I1", "raw_output", "A1", "data_input");

        stubComposition(List.of(ingest, agg), List.of(wire));
        stubBlueprint(ingestionBlueprint("FileIngestion"));
        stubBlueprint(transformBlueprint("GenericAggregate", "data_input", "result"));

        service.propagateFromVersion("v1");

        List<SchemaConflict> missing = storedConflicts.stream()
                .filter(c -> "MISSING_COLUMN".equals(c.getConflictType()))
                .toList();
        assertEquals(1, missing.size());
        assertEquals("customer_id", missing.get(0).getDetails().get("missing_column"));
        assertEquals("group_by_columns", missing.get(0).getDetails().get("source_param"));
        assertEquals("conflict", agg.getSchemaStatus());
    }

    @Test
    void propagate_joinEmitsTypeMismatchOnSameNameDifferentTypes() {
        SubPipelineInstance a = inst("A", "FileIngestion");
        a.setOutputSchema(columns("customer_id", "string", "amount", "decimal"));
        SubPipelineInstance d = inst("D", "FileIngestion");
        d.setOutputSchema(columns("customer_id", "int", "region", "string"));
        SubPipelineInstance join = inst("J", "GenericJoin");
        join.setParams(Map.of(
                "left_join_keys", List.of("customer_id"),
                "right_join_keys", List.of("customer_id")));

        PortWiring wLeft = wire("A", "raw_output", "J", "left");
        PortWiring wRight = wire("D", "raw_output", "J", "right");

        stubComposition(List.of(a, d, join), List.of(wLeft, wRight));
        stubBlueprint(ingestionBlueprint("FileIngestion"));
        Blueprint bp = new Blueprint();
        bp.setBlueprintKey("GenericJoin");
        bp.setCategory(BlueprintCategory.TRANSFORM);
        bp.setInputPorts(List.of(portMap("left"), portMap("right")));
        bp.setOutputPorts(List.of(portMap("joined")));
        lenient().when(blueprintRepo.findByBlueprintKey("GenericJoin")).thenReturn(Optional.of(bp));

        service.propagateFromVersion("v1");

        List<SchemaConflict> tm = storedConflicts.stream()
                .filter(c -> "TYPE_MISMATCH".equals(c.getConflictType()))
                .toList();
        assertEquals(1, tm.size());
        assertEquals("customer_id", tm.get(0).getDetails().get("column_name"));
        assertEquals("conflict", join.getSchemaStatus());
    }

    @Test
    void propagate_joinKeysObjectArrayChecksLeftAndRightColumns() {
        SubPipelineInstance left = inst("L", "FileIngestion");
        left.setOutputSchema(columns("loan_id", "string", "amount", "decimal"));
        SubPipelineInstance right = inst("R", "FileIngestion");
        right.setOutputSchema(columns("loan_key", "string", "region", "string"));
        SubPipelineInstance join = inst("J", "GenericJoin");
        join.setParams(Map.of("join_keys", List.of(
                Map.of("left_column", "loan_id", "right_column", "loan_key"))));

        PortWiring wLeft = wire("L", "raw_output", "J", "left");
        PortWiring wRight = wire("R", "raw_output", "J", "right");

        stubComposition(List.of(left, right, join), List.of(wLeft, wRight));
        stubBlueprint(ingestionBlueprint("FileIngestion"));
        Blueprint bp = new Blueprint();
        bp.setBlueprintKey("GenericJoin");
        bp.setCategory(BlueprintCategory.TRANSFORM);
        bp.setInputPorts(List.of(portMap("left"), portMap("right")));
        bp.setOutputPorts(List.of(portMap("joined")));
        lenient().when(blueprintRepo.findByBlueprintKey("GenericJoin")).thenReturn(Optional.of(bp));

        service.propagateFromVersion("v1");

        assertTrue(storedConflicts.stream()
                        .noneMatch(c -> "MISSING_COLUMN".equals(c.getConflictType())),
                "canonical join_keys object-array should validate each side, not the whole map string");
        assertEquals("clean", join.getSchemaStatus());

        InstancePortSchema joinedOut = storedPorts.stream()
                .filter(p -> p.getInstanceId().equals("J") && p.getDirection().equals("output"))
                .findFirst().orElseThrow();
        List<String> names = extractColumns(joinedOut.getSchemaJson()).stream()
                .map(c -> String.valueOf(c.get("name"))).toList();
        assertTrue(names.contains("loan_id"));
        assertTrue(names.contains("loan_key"));
    }

    @Test
    void propagate_scd2Dimension_acceptsBusinessKeyAndTrackedColumnsAliases() {
        SubPipelineInstance ingest = inst("I1", "FileIngestion");
        ingest.setOutputSchema(columns("loan_id", "string", "loan_status", "string", "current_upb", "decimal"));
        SubPipelineInstance scd2 = inst("S1", "SCD2Dimension");
        scd2.setParams(Map.of(
                "business_key", List.of("loan_id"),
                "tracked_columns", List.of("loan_status", "current_upb")));
        PortWiring wire = wire("I1", "raw_output", "S1", "source_data");

        stubComposition(List.of(ingest, scd2), List.of(wire));
        stubBlueprint(ingestionBlueprint("FileIngestion"));
        stubBlueprint(transformBlueprint("SCD2Dimension", "source_data", "scd2_output"));

        service.propagateFromVersion("v1");

        assertTrue(storedConflicts.stream()
                        .noneMatch(c -> "MISSING_COLUMN".equals(c.getConflictType())),
                "canonical SCD2 aliases should validate against upstream columns");
        assertEquals("clean", scd2.getSchemaStatus());

        InstancePortSchema out = storedPorts.stream()
                .filter(p -> p.getInstanceId().equals("S1") && p.getDirection().equals("output"))
                .findFirst().orElseThrow();
        List<String> names = extractColumns(out.getSchemaJson()).stream()
                .map(c -> String.valueOf(c.get("name")))
                .toList();
        // FIX #2: SCD2 emits the dbt-snapshot system columns, not the old transposed set.
        assertTrue(names.contains("dbt_valid_from"));
        assertTrue(names.contains("dbt_valid_to"));
        assertTrue(names.contains("dbt_scd_id"));
        assertTrue(names.contains("dbt_updated_at"));
        assertFalse(names.contains("is_current"), "is_current was the transposed (wrong) column");
    }

    @Test
    void propagate_cycleYieldsConflictWithoutHang() {
        SubPipelineInstance a = inst("A", "GenericFilter");
        SubPipelineInstance b = inst("B", "GenericFilter");
        PortWiring wAB = wire("A", "filtered_output", "B", "data_input");
        PortWiring wBA = wire("B", "filtered_output", "A", "data_input");
        stubComposition(List.of(a, b), List.of(wAB, wBA));
        stubBlueprint(transformBlueprint("GenericFilter", "data_input", "filtered_output"));

        SchemaPropagationService.PropagationSummary summary = service.propagateFromVersion("v1");

        assertTrue(summary.cycleDetected());
        List<SchemaConflict> cycles = storedConflicts.stream()
                .filter(c -> "CYCLE_DETECTED".equals(c.getConflictType()))
                .toList();
        assertEquals(2, cycles.size());
        assertEquals("conflict", a.getSchemaStatus());
        assertEquals("conflict", b.getSchemaStatus());
    }

    @Test
    void propagate_missingUpstreamYieldsConflict() {
        SubPipelineInstance filter = inst("F", "GenericFilter");
        stubComposition(List.of(filter), List.of());
        stubBlueprint(transformBlueprint("GenericFilter", "data_input", "filtered_output"));

        service.propagateFromVersion("v1");

        List<SchemaConflict> mu = storedConflicts.stream()
                .filter(c -> "MISSING_UPSTREAM".equals(c.getConflictType()))
                .toList();
        assertEquals(1, mu.size());
        assertEquals("conflict", filter.getSchemaStatus());
    }

    @Test
    void propagate_mirrorsPrimaryOutputToOutputSchemaColumn() {
        SubPipelineInstance ingest = inst("I", "FileIngestion");
        ingest.setOutputSchema(columns("id", "string"));
        SubPipelineInstance filter = inst("F", "GenericFilter");
        PortWiring wire = wire("I", "raw_output", "F", "data_input");

        stubComposition(List.of(ingest, filter), List.of(wire));
        stubBlueprint(ingestionBlueprint("FileIngestion"));
        stubBlueprint(transformBlueprint("GenericFilter", "data_input", "filtered_output"));

        service.propagateFromVersion("v1");

        // After propagation, F's output_schema on the instance entity matches the output port row.
        // Bronze passthrough = source col (id) + IngestionAuditColumns set.
        assertNotNull(filter.getOutputSchema());
        assertEquals(1 + IngestionAuditColumns.NAMES.size(),
                extractColumns(filter.getOutputSchema()).size());
    }

    @Test
    void propagate_doesNotReDeriveOverriddenPort() {
        SubPipelineInstance ingest = inst("I", "FileIngestion");
        ingest.setOutputSchema(columns("id", "string"));
        SubPipelineInstance filter = inst("F", "GenericFilter");
        PortWiring wire = wire("I", "raw_output", "F", "data_input");

        InstancePortSchema existing = new InstancePortSchema();
        existing.setId("ips-pre");
        existing.setInstanceId("F");
        existing.setPortName("filtered_output");
        existing.setDirection("output");
        existing.setSchemaJson(columns("override_col", "string"));
        existing.setOverride(Map.of("columns", List.of(Map.of("name", "override_col", "type", "string"))));
        storedPorts.add(existing);

        stubComposition(List.of(ingest, filter), List.of(wire));
        stubBlueprint(ingestionBlueprint("FileIngestion"));
        stubBlueprint(transformBlueprint("GenericFilter", "data_input", "filtered_output"));

        service.propagateFromVersion("v1");

        InstancePortSchema after = storedPorts.stream()
                .filter(p -> p.getInstanceId().equals("F") && p.getDirection().equals("output"))
                .findFirst().orElseThrow();
        List<Map<String, Object>> cols = extractColumns(after.getSchemaJson());
        assertEquals(1, cols.size());
        assertEquals("override_col", cols.get(0).get("name"));
    }

    @Test
    void propagate_joinMergesColumnsWithPrefix() {
        SubPipelineInstance left = inst("L", "FileIngestion");
        left.setOutputSchema(columns("customer_id", "string", "amount", "decimal"));
        SubPipelineInstance right = inst("R", "FileIngestion");
        right.setOutputSchema(columns("customer_id", "int", "region", "string"));
        SubPipelineInstance join = inst("J", "GenericJoin");
        join.setParams(Map.of(
                "left_join_keys", List.of("customer_id"),
                "right_join_keys", List.of("customer_id")));

        PortWiring wLeft = wire("L", "raw_output", "J", "left");
        PortWiring wRight = wire("R", "raw_output", "J", "right");

        stubComposition(List.of(left, right, join), List.of(wLeft, wRight));
        stubBlueprint(ingestionBlueprint("FileIngestion"));
        Blueprint bp = new Blueprint();
        bp.setBlueprintKey("GenericJoin");
        bp.setCategory(BlueprintCategory.TRANSFORM);
        bp.setInputPorts(List.of(portMap("left"), portMap("right")));
        bp.setOutputPorts(List.of(portMap("joined")));
        lenient().when(blueprintRepo.findByBlueprintKey("GenericJoin")).thenReturn(Optional.of(bp));

        service.propagateFromVersion("v1");

        InstancePortSchema joinedOut = storedPorts.stream()
                .filter(p -> p.getInstanceId().equals("J") && p.getDirection().equals("output"))
                .findFirst().orElseThrow();
        List<String> names = extractColumns(joinedOut.getSchemaJson()).stream()
                .map(c -> String.valueOf(c.get("name"))).toList();
        // Primary-side columns kept under their own name.
        assertTrue(names.contains("customer_id"));
        assertTrue(names.contains("amount"));
        // Same-name-different-type column from the right side is renamed with the right_ prefix.
        assertTrue(names.contains("right_customer_id"),
                "expected right_customer_id in merged columns, got " + names);
        // Non-colliding right-side columns keep their plain name.
        assertTrue(names.contains("region"));
    }

    @Test
    void propagate_upstreamChangeCascadesDownstream() {
        SubPipelineInstance ingest = inst("I", "FileIngestion");
        ingest.setOutputSchema(columns("id", "string"));
        SubPipelineInstance f1 = inst("F1", "GenericFilter");
        SubPipelineInstance f2 = inst("F2", "GenericFilter");
        PortWiring w1 = wire("I", "raw_output", "F1", "data_input");
        PortWiring w2 = wire("F1", "filtered_output", "F2", "data_input");

        stubComposition(List.of(ingest, f1, f2), List.of(w1, w2));
        stubBlueprint(ingestionBlueprint("FileIngestion"));
        stubBlueprint(transformBlueprint("GenericFilter", "data_input", "filtered_output"));

        service.propagateFromVersion("v1");

        // First pass: F2 output mirrors the source schema + audit columns from bronze.
        int auditSize = IngestionAuditColumns.NAMES.size();
        InstancePortSchema firstF2Out = storedPorts.stream()
                .filter(p -> p.getInstanceId().equals("F2") && p.getDirection().equals("output"))
                .findFirst().orElseThrow();
        assertEquals(1 + auditSize, extractColumns(firstF2Out.getSchemaJson()).size());
        String firstHash = firstF2Out.getSchemaHash();

        // Change upstream — add an extra column — and re-run propagation.
        ingest.setOutputSchema(columns("id", "string", "email", "string"));
        service.propagateFromVersion("v1");

        InstancePortSchema secondF2Out = storedPorts.stream()
                .filter(p -> p.getInstanceId().equals("F2") && p.getDirection().equals("output"))
                .findFirst().orElseThrow();
        List<Map<String, Object>> secondCols = extractColumns(secondF2Out.getSchemaJson());
        assertEquals(2 + auditSize, secondCols.size());
        assertTrue(secondCols.stream().anyMatch(c -> "email".equals(c.get("name"))));
        assertFalse(firstHash.equals(secondF2Out.getSchemaHash()),
                "schema_hash must change when upstream schema changes");
        assertEquals("clean", f2.getSchemaStatus());
    }

    @Test
    void propagate_upstreamColumnAddAndRemoveCascadesThroughChain() {
        SubPipelineInstance ingest = inst("I", "FileIngestion");
        ingest.setOutputSchema(columns("loan_id", "string", "current_upb", "decimal"));
        SubPipelineInstance normalize = inst("N", "SchemaNormalization");
        SubPipelineInstance filter = inst("F", "GenericFilter");
        PortWiring w1 = wire("I", "raw_output", "N", "source_data");
        PortWiring w2 = wire("N", "normalized_output", "F", "data_input");

        stubComposition(List.of(ingest, normalize, filter), List.of(w1, w2));
        stubBlueprint(ingestionBlueprint("FileIngestion"));
        stubBlueprint(transformBlueprint("SchemaNormalization", "source_data", "normalized_output"));
        stubBlueprint(transformBlueprint("GenericFilter", "data_input", "filtered_output"));

        service.propagateFromVersion("v1");

        InstancePortSchema initial = outputPort("F", "filtered_output");
        List<String> initialNames = columnNames(initial);
        String initialHash = initial.getSchemaHash();
        assertTrue(initialNames.contains("loan_id"));
        assertTrue(initialNames.contains("current_upb"));

        ingest.setOutputSchema(columns(
                "loan_id", "string",
                "current_upb", "decimal",
                "servicing_risk_band", "string"));
        service.propagateFromVersion("v1");

        InstancePortSchema afterAdd = outputPort("F", "filtered_output");
        List<String> afterAddNames = columnNames(afterAdd);
        String afterAddHash = afterAdd.getSchemaHash();
        assertTrue(afterAddNames.contains("loan_id"));
        assertTrue(afterAddNames.contains("current_upb"));
        assertTrue(afterAddNames.contains("servicing_risk_band"),
                "new upstream columns must propagate through downstream transforms");
        assertFalse(initialHash.equals(afterAddHash),
                "schema hash must change after upstream column addition");

        ingest.setOutputSchema(columns("loan_id", "string", "servicing_risk_band", "string"));
        service.propagateFromVersion("v1");

        InstancePortSchema afterRemove = outputPort("F", "filtered_output");
        List<String> afterRemoveNames = columnNames(afterRemove);
        assertTrue(afterRemoveNames.contains("loan_id"));
        assertTrue(afterRemoveNames.contains("servicing_risk_band"));
        assertFalse(afterRemoveNames.contains("current_upb"),
                "removed upstream columns must disappear from downstream propagated schemas");
        assertFalse(afterAddHash.equals(afterRemove.getSchemaHash()),
                "schema hash must change after upstream column removal");
        assertEquals("clean", normalize.getSchemaStatus());
        assertEquals("clean", filter.getSchemaStatus());
    }

    @Test
    void propagate_schemaNormalizationMappingRulesRenamesMappedColumnAndPreservesPassthrough() {
        SubPipelineInstance ingest = inst("I", "FileIngestion");
        ingest.setOutputSchema(columns("loan_id", "string", "current_upb", "decimal"));
        SubPipelineInstance normalize = inst("N", "SchemaNormalization");
        normalize.setParams(Map.of(
                "target_schema", "loan_master_canonical",
                "mapping_rules", Map.of("canonical_loan_id", "loan_id"),
                "strict_mode", false
        ));
        PortWiring wire = wire("I", "raw_output", "N", "source_data");

        stubComposition(List.of(ingest, normalize), List.of(wire));
        stubBlueprint(ingestionBlueprint("FileIngestion"));
        stubBlueprint(transformBlueprint("SchemaNormalization", "source_data", "normalized_output"));

        service.propagateFromVersion("v1");

        InstancePortSchema normalized = outputPort("N", "normalized_output");
        List<String> names = columnNames(normalized);
        assertTrue(names.contains("canonical_loan_id"));
        assertTrue(names.contains("current_upb"));
        assertFalse(names.contains("loan_id"));
        assertEquals("clean", normalize.getSchemaStatus());
    }

    @Test
    void propagate_removedColumnReferencedByParamsProducesConflict() {
        SubPipelineInstance ingest = inst("I", "FileIngestion");
        ingest.setOutputSchema(columns("loan_id", "string", "current_upb", "decimal"));
        SubPipelineInstance aggregate = inst("A", "GenericAggregate");
        aggregate.setParams(Map.of(
                "group_by_columns", List.of("loan_id"),
                "aggregations", List.of(Map.of(
                        "column", "current_upb",
                        "function", "sum",
                        "alias", "total_current_upb"))));
        PortWiring wire = wire("I", "raw_output", "A", "data_input");

        stubComposition(List.of(ingest, aggregate), List.of(wire));
        stubBlueprint(ingestionBlueprint("FileIngestion"));
        stubBlueprint(transformBlueprint("GenericAggregate", "data_input", "aggregated_output"));

        service.propagateFromVersion("v1");
        assertEquals("clean", aggregate.getSchemaStatus());
        assertTrue(storedConflicts.stream()
                .noneMatch(c -> "MISSING_COLUMN".equals(c.getConflictType())));

        ingest.setOutputSchema(columns("loan_id", "string"));
        service.propagateFromVersion("v1");

        List<SchemaConflict> missing = storedConflicts.stream()
                .filter(c -> "MISSING_COLUMN".equals(c.getConflictType()))
                .filter(c -> "current_upb".equals(c.getDetails().get("missing_column")))
                .toList();
        assertFalse(missing.isEmpty(), "referenced removed columns must surface as MISSING_COLUMN conflicts");
        assertEquals("aggregations.column", missing.get(0).getDetails().get("source_param"));
        assertEquals("conflict", aggregate.getSchemaStatus());
    }

    @Test
    void propagate_clearingOverrideReverts() {
        SubPipelineInstance ingest = inst("I", "FileIngestion");
        ingest.setOutputSchema(columns("id", "string", "amount", "decimal"));
        SubPipelineInstance filter = inst("F", "GenericFilter");
        PortWiring w = wire("I", "raw_output", "F", "data_input");

        stubComposition(List.of(ingest, filter), List.of(w));
        stubBlueprint(ingestionBlueprint("FileIngestion"));
        stubBlueprint(transformBlueprint("GenericFilter", "data_input", "filtered_output"));

        // Seed an override on F.filtered_output so clearOverride has something to revert.
        InstancePortSchema override = new InstancePortSchema();
        override.setId("ips-override");
        override.setInstanceId("F");
        override.setPortName("filtered_output");
        override.setDirection("output");
        Map<String, Object> custom = Map.of("columns",
                List.of(Map.of("name", "only_override_col", "type", "string")));
        override.setSchemaJson(custom);
        override.setOverride(Map.of(
                "columns", List.of(Map.of("name", "only_override_col", "type", "string")),
                "overridden_by", "tester"));
        override.setSource("override");
        storedPorts.add(override);

        service.clearOverride("v1", "F", "filtered_output");

        InstancePortSchema after = storedPorts.stream()
                .filter(p -> p.getInstanceId().equals("F") && p.getDirection().equals("output"))
                .findFirst().orElseThrow();
        // Override cleared.
        assertEquals(null, after.getOverride());
        // After re-derivation the pass-through brings back the upstream columns.
        List<Map<String, Object>> cols = extractColumns(after.getSchemaJson());
        List<String> names = cols.stream().map(c -> String.valueOf(c.get("name"))).toList();
        assertFalse(names.contains("only_override_col"),
                "override column must be gone after clearOverride + repropagate");
        assertTrue(names.contains("id"));
        assertTrue(names.contains("amount"));
        assertEquals("propagated", after.getSource());
    }

    @Test
    void propagate_unknownBlueprintLoudFailsNoLlmFallback_whenCutoverFlagOn() {
        // ADR 0011/0013 + SPEC #1 §B.3: the LLM fallback is DELETED. With the Risk-#1 cutover
        // flag ON (the post-V153 target), a blueprint with no schema_behavior op-list AND no
        // deterministic rule LOUD-FAILS (blocking) — it does NOT silently pass through and does
        // NOT call an LLM.
        service.setLoudFailOnMissingOpList(true);

        SubPipelineInstance ingest = inst("I", "FileIngestion");
        ingest.setOutputSchema(columns("id", "string", "amount", "decimal"));
        // A blueprint key with no deterministic rule (not a Writer/Publish/DQ/Ingestion/SCD2/etc.)
        // and no schema_behavior op-list.
        SubPipelineInstance unknown = inst("U", "CustomMysteryTransform");
        PortWiring w = wire("I", "raw_output", "U", "data_input");

        stubComposition(List.of(ingest, unknown), List.of(w));
        stubBlueprint(ingestionBlueprint("FileIngestion"));
        Blueprint bp = new Blueprint();
        bp.setBlueprintKey("CustomMysteryTransform");
        bp.setCategory(BlueprintCategory.TRANSFORM);
        bp.setInputPorts(List.of(portMap("data_input")));
        bp.setOutputPorts(List.of(portMap("result")));
        // NOTE: no schemaBehavior set => no op-list.
        lenient().when(blueprintRepo.findByBlueprintKey("CustomMysteryTransform"))
                .thenReturn(Optional.of(bp));

        com.pulse.pipeline.opengine.OpEngineException ex =
                org.junit.jupiter.api.Assertions.assertThrows(
                        com.pulse.pipeline.opengine.OpEngineException.class,
                        () -> service.propagateFromVersion("v1"));
        assertTrue(ex.getMessage().contains("CustomMysteryTransform"));
        assertTrue(ex.getMessage().toLowerCase().contains("no llm")
                || ex.getMessage().toLowerCase().contains("incomplete"));
    }

    @Test
    void propagate_unknownBlueprintPassthroughNoLlm_whenCutoverFlagOff() {
        // Transition default (flag OFF): a not-yet-op-listed blueprint resolves via a
        // deterministic PASSTHROUGH fallback (NOT an LLM), so the pre-V153 catalog keeps
        // working. The LLM fallback is gone in BOTH modes.
        // (service is constructed with the flag defaulting to false.)
        SubPipelineInstance ingest = inst("I", "FileIngestion");
        ingest.setOutputSchema(columns("id", "string", "amount", "decimal"));
        SubPipelineInstance unknown = inst("U", "JsonFlatten");
        PortWiring w = wire("I", "raw_output", "U", "data_input");

        stubComposition(List.of(ingest, unknown), List.of(w));
        stubBlueprint(ingestionBlueprint("FileIngestion"));
        stubBlueprint(transformBlueprint("JsonFlatten", "data_input", "result"));

        // No exception; the unknown TRANSFORM passes its input schema through.
        service.propagateFromVersion("v1");

        InstancePortSchema out = outputPort("U", "result");
        // passthrough carries the upstream columns (id, amount) + ingestion audit cols.
        List<String> names = columnNames(out);
        assertTrue(names.contains("id"));
        assertTrue(names.contains("amount"));
    }

    @Test
    void propagate_opListBlueprintResolvesViaEngine() {
        // A blueprint WITH a schema_behavior op-list resolves via the deterministic op-engine
        // (the primary path post-V153), not the legacy shim.
        SubPipelineInstance ingest = inst("I", "FileIngestion");
        ingest.setOutputSchema(columns("id", "string", "secret", "string", "amount", "decimal"));
        SubPipelineInstance keep = inst("K", "KeepCols");
        keep.setParams(Map.of("keep_columns", List.of("id", "amount")));
        PortWiring w = wire("I", "raw_output", "K", "data_input");

        stubComposition(List.of(ingest, keep), List.of(w));
        stubBlueprint(ingestionBlueprint("FileIngestion"));

        Blueprint bp = transformBlueprint("KeepCols", "data_input", "kept_output");
        Map<String, Object> sb = new HashMap<>();
        sb.put("version", 1);
        sb.put("ops", List.of(com.pulse.pipeline.opengine.OpList.opEntryMap(
                "keep-columns", "Keep columns",
                Map.of("keep_columns", Map.of("param", "keep_columns")))));
        sb.put("emission", Map.of("orchestration", "airflow", "compute", "dbt"));
        bp.setSchemaBehavior(sb);
        bp.setParamsSchema(List.of(Map.of("name", "keep_columns", "type", "array")));
        lenient().when(blueprintRepo.findByBlueprintKey("KeepCols")).thenReturn(Optional.of(bp));

        service.propagateFromVersion("v1");

        InstancePortSchema out = outputPort("K", "kept_output");
        List<String> names = columnNames(out);
        // keep-columns -> exactly id, amount in named order; "secret" + audit cols dropped.
        assertEquals(List.of("id", "amount"), names);
    }

    @Test
    void propagate_doesNotReadRequiredInputColumnsMetadata() {
        SubPipelineInstance ingest = inst("I", "FileIngestion");
        ingest.setOutputSchema(columns("id", "string"));
        SubPipelineInstance filter = inst("F", "GenericFilter");
        PortWiring wire = wire("I", "raw_output", "F", "data_input");

        Blueprint bp = transformBlueprint("GenericFilter", "data_input", "filtered_output");
        // If the engine reads codegen_hints at all, set them up so the test would otherwise
        // pick them up — the assertion then proves the engine does NOT consult this field.
        bp.setCodegenHints(Map.of(
                "required_input_columns", List.of("col_that_should_be_ignored"),
                "merge_columns", List.of("irrelevant")));
        lenient().when(blueprintRepo.findByBlueprintKey("GenericFilter")).thenReturn(Optional.of(bp));
        stubBlueprint(ingestionBlueprint("FileIngestion"));
        stubComposition(List.of(ingest, filter), List.of(wire));

        service.propagateFromVersion("v1");

        // Assertion: no MISSING_COLUMN for "col_that_should_be_ignored" — the engine did not
        // consult required_input_columns (which would have raised a missing-column conflict).
        List<SchemaConflict> mc = storedConflicts.stream()
                .filter(c -> "MISSING_COLUMN".equals(c.getConflictType()))
                .toList();
        assertTrue(mc.stream().noneMatch(c ->
                "col_that_should_be_ignored".equals(c.getDetails().get("missing_column"))));
    }

    @Test
    void propagate_piiMaskingTagsMaskedColumns() {
        SubPipelineInstance ingest = inst("I", "FileIngestion");
        ingest.setOutputSchema(columns(
                "account_id", "int",
                "email", "string",
                "ssn", "string",
                "balance", "decimal"));
        SubPipelineInstance mask = inst("M", "PIIMasking");
        mask.setParams(Map.of("mask_specs", List.of(
                Map.of("column", "email", "strategy", "hash"),
                Map.of("column", "ssn",   "strategy", "redact_left"))));
        PortWiring w = wire("I", "raw_output", "M", "data_input");

        stubComposition(List.of(ingest, mask), List.of(w));
        stubBlueprint(ingestionBlueprint("FileIngestion"));
        stubBlueprint(transformBlueprint("PIIMasking", "data_input", "masked_output"));

        service.propagateFromVersion("v1");

        InstancePortSchema out = storedPorts.stream()
                .filter(p -> p.getInstanceId().equals("M") && p.getDirection().equals("output"))
                .findFirst().orElseThrow();
        List<Map<String, Object>> cols = extractColumns(out.getSchemaJson());

        Map<String, Object> emailCol = cols.stream()
                .filter(c -> "email".equals(c.get("name"))).findFirst().orElseThrow();
        assertEquals("masked:hash", emailCol.get("lineage"));
        assertEquals("string", emailCol.get("type"));
        @SuppressWarnings("unchecked")
        List<String> emailTags = (List<String>) emailCol.get("tags");
        assertTrue(emailTags.contains("masked"));
        assertTrue(emailTags.contains("pii"));

        Map<String, Object> ssnCol = cols.stream()
                .filter(c -> "ssn".equals(c.get("name"))).findFirst().orElseThrow();
        assertEquals("masked:redact_left", ssnCol.get("lineage"));

        // Non-masked columns pass through unchanged.
        Map<String, Object> balanceCol = cols.stream()
                .filter(c -> "balance".equals(c.get("name"))).findFirst().orElseThrow();
        assertFalse(balanceCol.containsKey("lineage")
                && String.valueOf(balanceCol.get("lineage")).startsWith("masked"));
    }

    @Test
    void propagate_universalDerivedColumnsAppendsToOutput() {
        SubPipelineInstance ingest = inst("I", "FileIngestion");
        ingest.setOutputSchema(columns("account_id", "int", "origination_date", "date"));
        SubPipelineInstance silver = inst("S", "BronzeToSilverCleaning");
        silver.setParams(Map.of("derived_columns", List.of(
                Map.of("name", "loan_age_days",
                        "type", "integer",
                        "expression", "datediff(current_date, origination_date)",
                        "description", "Days since loan origination"))));
        PortWiring w = wire("I", "raw_output", "S", "data_input");

        stubComposition(List.of(ingest, silver), List.of(w));
        stubBlueprint(ingestionBlueprint("FileIngestion"));
        stubBlueprint(transformBlueprint("BronzeToSilverCleaning", "data_input", "silver_output"));

        service.propagateFromVersion("v1");

        InstancePortSchema out = storedPorts.stream()
                .filter(p -> p.getInstanceId().equals("S") && p.getDirection().equals("output"))
                .findFirst().orElseThrow();
        List<Map<String, Object>> cols = extractColumns(out.getSchemaJson());

        Map<String, Object> derived = cols.stream()
                .filter(c -> "loan_age_days".equals(c.get("name"))).findFirst().orElseThrow();
        assertEquals("integer", derived.get("type"));
        assertEquals("derived:expression", derived.get("lineage"));
        @SuppressWarnings("unchecked")
        Map<String, Object> transform = (Map<String, Object>) derived.get("transform");
        assertEquals("sql_expression", transform.get("kind"));
        assertEquals("datediff(current_date, origination_date)", transform.get("expression"));
        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) derived.get("tags");
        assertTrue(tags.contains("derived"));
    }

    @Test
    void propagate_universalDroppedColumnsRemovesFromOutput() {
        SubPipelineInstance ingest = inst("I", "FileIngestion");
        ingest.setOutputSchema(columns(
                "account_id", "int",
                "scratch_col", "string",
                "balance", "decimal"));
        SubPipelineInstance silver = inst("S", "BronzeToSilverCleaning");
        silver.setParams(Map.of("dropped_columns", List.of("scratch_col")));
        PortWiring w = wire("I", "raw_output", "S", "data_input");

        stubComposition(List.of(ingest, silver), List.of(w));
        stubBlueprint(ingestionBlueprint("FileIngestion"));
        stubBlueprint(transformBlueprint("BronzeToSilverCleaning", "data_input", "silver_output"));

        service.propagateFromVersion("v1");

        InstancePortSchema out = storedPorts.stream()
                .filter(p -> p.getInstanceId().equals("S") && p.getDirection().equals("output"))
                .findFirst().orElseThrow();
        List<String> names = extractColumns(out.getSchemaJson()).stream()
                .map(c -> String.valueOf(c.get("name"))).toList();
        assertFalse(names.contains("scratch_col"),
                "dropped_columns must remove the named column from output");
        assertTrue(names.contains("account_id"));
        assertTrue(names.contains("balance"));
    }

    @Test
    void propagate_bronzeIngestionAppendsAuditColumnsWithLineage() {
        SubPipelineInstance ingest = inst("I", "FileIngestion");
        ingest.setOutputSchema(columns("account_id", "int", "balance", "decimal"));

        stubComposition(List.of(ingest), List.of());
        stubBlueprint(ingestionBlueprint("FileIngestion"));

        service.propagateFromVersion("v1");

        InstancePortSchema out = storedPorts.stream()
                .filter(p -> p.getInstanceId().equals("I") && p.getDirection().equals("output"))
                .findFirst().orElseThrow();
        List<Map<String, Object>> cols = extractColumns(out.getSchemaJson());

        // Source columns retained.
        assertTrue(cols.stream().anyMatch(c -> "account_id".equals(c.get("name"))));

        // Every audit column from IngestionAuditColumns is present and tagged.
        for (String auditName : IngestionAuditColumns.NAMES) {
            Map<String, Object> auditCol = cols.stream()
                    .filter(c -> auditName.equals(c.get("name"))).findFirst()
                    .orElseThrow(() -> new AssertionError("missing audit col " + auditName));
            assertEquals("injected:audit", auditCol.get("lineage"));
            @SuppressWarnings("unchecked")
            List<String> tags = (List<String>) auditCol.get("tags");
            assertTrue(tags.contains("audit"),
                    auditName + " must be tagged 'audit'");
        }
    }

    @Test
    void propagate_sourceSqlUsesSourcePrepareResolverThroughOpEngine() {
        SubPipelineInstance source = inst("S", "SourceSQL");
        source.setParams(Map.of(
                "source_query", "select loan_id, current_upb from servicing.loan",
                "connector_instance_id", "conn-1"));
        Blueprint bp = sourceSqlBlueprint();

        List<Map<String, Object>> resolvedColumns = List.of(
                Map.of("name", "loan_id", "type", "string", "lineage", "source"),
                Map.of("name", "current_upb", "type", "decimal", "lineage", "source"));
        when(sourceSqlSchemaResolver.resolveSourceColumns(source))
                .thenReturn(Optional.of(resolvedColumns));

        stubComposition(List.of(source), List.of());
        stubBlueprint(bp);

        service.propagateFromVersion("v1");

        verify(sourceSqlSchemaResolver, atLeastOnce()).resolveSourceColumns(source);
        InstancePortSchema out = outputPort("S", "source_output");
        List<String> names = columnNames(out);
        assertTrue(names.contains("loan_id"));
        assertTrue(names.contains("current_upb"));
        for (String auditName : IngestionAuditColumns.NAMES) {
            assertEquals(1, names.stream().filter(auditName::equals).count(),
                    "audit column must be appended exactly once: " + auditName);
        }
    }

    @Test
    void propagate_sourceSqlFallsBackToDeclaredOutputSchemaWhenPrepareUnavailable() {
        SubPipelineInstance source = inst("S", "SourceSQL");
        source.setParams(Map.of(
                "source_query", "select loan_id, status_code from servicing.loan",
                "connector_instance_id", "conn-1",
                "declared_output_schema", List.of(
                        Map.of("name", "loan_id", "type", "string"),
                        Map.of("name", "status_code", "type", "string"))));
        Blueprint bp = sourceSqlBlueprint();

        when(sourceSqlSchemaResolver.resolveSourceColumns(source))
                .thenReturn(Optional.empty());

        stubComposition(List.of(source), List.of());
        stubBlueprint(bp);

        service.propagateFromVersion("v1");

        InstancePortSchema out = outputPort("S", "source_output");
        List<Map<String, Object>> cols = extractColumns(out.getSchemaJson());
        assertTrue(cols.stream().anyMatch(c -> "loan_id".equals(c.get("name"))));
        assertTrue(cols.stream().anyMatch(c -> "status_code".equals(c.get("name"))));
        Map<String, Object> sourceCol = cols.stream()
                .filter(c -> "loan_id".equals(c.get("name")))
                .findFirst()
                .orElseThrow();
        assertEquals("source", sourceCol.get("lineage"));
        for (String auditName : IngestionAuditColumns.NAMES) {
            assertEquals(1, cols.stream()
                            .filter(c -> auditName.equals(c.get("name")))
                            .count(),
                    "audit column must be appended exactly once: " + auditName);
        }
    }

    @Test
    void propagate_fileIngestionUpstreamRoleDoesNotDuplicateAuditColumns() {
        SubPipelineInstance source = inst("SRC", "SnapshotIngestion");
        source.setOutputSchema(columns("loan_id", "string"));
        SubPipelineInstance file = inst("FILE", "FileIngestion");
        PortWiring wire = wire("SRC", "snapshot_output", "FILE", "source_input");

        stubComposition(List.of(source, file), List.of(wire));
        stubBlueprint(ingestionBlueprint("SnapshotIngestion", "snapshot_output"));
        stubBlueprint(fileIngestionUpstreamBlueprint());

        service.propagateFromVersion("v1");

        InstancePortSchema sourceOut = outputPort("SRC", "snapshot_output");
        assertEquals(1 + IngestionAuditColumns.NAMES.size(), columnNames(sourceOut).size());

        InstancePortSchema fileOut = outputPort("FILE", "raw_output");
        List<String> names = columnNames(fileOut);
        assertEquals(1 + IngestionAuditColumns.NAMES.size(), names.size());
        assertTrue(names.contains("loan_id"));
        for (String auditName : IngestionAuditColumns.NAMES) {
            assertEquals(1, names.stream().filter(auditName::equals).count(),
                    "FileIngestion must not duplicate upstream audit column " + auditName);
        }
    }

    @Test
    void propagate_missingUpstreamClearsStaleInputAndOutputSchemas() {
        SubPipelineInstance file = inst("FILE", "FileIngestion");
        file.setOutputSchema(columns(
                "loan_id", "string",
                "_pulse_ingested_at", "timestamp",
                "_pulse_ingested_at", "timestamp"));

        InstancePortSchema staleInput = new InstancePortSchema();
        staleInput.setId("ips-input");
        staleInput.setInstanceId("FILE");
        staleInput.setPortName("source_input");
        staleInput.setDirection("input");
        staleInput.setSchemaJson(columns("loan_id", "string", "_pulse_ingested_at", "timestamp"));
        storedPorts.add(staleInput);

        InstancePortSchema staleOutput = new InstancePortSchema();
        staleOutput.setId("ips-output");
        staleOutput.setInstanceId("FILE");
        staleOutput.setPortName("raw_output");
        staleOutput.setDirection("output");
        staleOutput.setSchemaJson(file.getOutputSchema());
        storedPorts.add(staleOutput);

        stubComposition(List.of(file), List.of());
        stubBlueprint(fileIngestionUpstreamBlueprint());

        service.propagateFromVersion("v1");

        assertTrue(storedPorts.stream().noneMatch(p -> p.getInstanceId().equals("FILE")
                && "source_input".equals(p.getPortName())
                && "input".equals(p.getDirection())));
        assertTrue(storedPorts.stream().noneMatch(p -> p.getInstanceId().equals("FILE")
                && "raw_output".equals(p.getPortName())
                && "output".equals(p.getDirection())));
        assertEquals(0, extractColumns(file.getOutputSchema()).size(),
                "disconnected input must not leave stale mirrored output_schema");
        assertEquals("conflict", file.getSchemaStatus());
        assertTrue(storedConflicts.stream()
                .anyMatch(c -> "MISSING_UPSTREAM".equals(c.getConflictType())
                        && "source_input".equals(c.getPortName())));
    }

    // ------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------

    private SubPipelineInstance inst(String id, String bpKey) {
        SubPipelineInstance inst = new SubPipelineInstance();
        inst.setId(id);
        inst.setVersionId("v1");
        inst.setPipelineId("p1");
        inst.setBlueprintKey(bpKey);
        inst.setBlueprintId("bp-" + bpKey);
        inst.setBlueprintVersion("1.0");
        inst.setName(bpKey + "-" + id);
        inst.setExecutionOrder(1);
        inst.setParams(new HashMap<>());
        return inst;
    }

    private PortWiring wire(String src, String srcPort, String tgt, String tgtPort) {
        PortWiring w = new PortWiring();
        w.setVersionId("v1");
        w.setSourceInstanceId(src);
        w.setSourcePortName(srcPort);
        w.setTargetInstanceId(tgt);
        w.setTargetPortName(tgtPort);
        return w;
    }

    private Blueprint ingestionBlueprint(String key) {
        return ingestionBlueprint(key, "raw_output");
    }

    private Blueprint ingestionBlueprint(String key, String outputPort) {
        Blueprint bp = new Blueprint();
        bp.setBlueprintKey(key);
        bp.setCategory(BlueprintCategory.INGESTION);
        bp.setInputPorts(List.of());
        bp.setOutputPorts(List.of(portMap(outputPort)));
        return bp;
    }

    private Blueprint fileIngestionUpstreamBlueprint() {
        Blueprint bp = new Blueprint();
        bp.setBlueprintKey("FileIngestion");
        bp.setCategory(BlueprintCategory.INGESTION);
        bp.setInputPorts(List.of(portMap("source_input")));
        bp.setOutputPorts(List.of(portMap("raw_output")));
        bp.setParamsSchema(List.of(
                Map.of("name", "storage_backend", "type", "string"),
                Map.of("name", "lake_layer", "type", "string"),
                Map.of("name", "lake_format", "type", "string")));
        bp.setSchemaBehavior(Map.of(
                "version", 1,
                "ops", List.of(
                        com.pulse.pipeline.opengine.OpList.opEntryMap(
                                "read-source", "Read source", Map.of()),
                        com.pulse.pipeline.opengine.OpList.opEntryMap(
                                "add-audit-columns", "Add audit columns", Map.of()),
                        com.pulse.pipeline.opengine.OpList.opEntryMap(
                                "write-sink", "Write to bronze",
                                Map.of("target", "bronze", "mode", "overwrite"))),
                "blueprint_params", List.of("storage_backend", "lake_layer", "lake_format"),
                "emission", Map.of("orchestration", "airflow", "compute", "pyspark")));
        return bp;
    }

    private Blueprint transformBlueprint(String key, String inPort, String outPort) {
        Blueprint bp = new Blueprint();
        bp.setBlueprintKey(key);
        bp.setCategory(BlueprintCategory.TRANSFORM);
        bp.setInputPorts(List.of(portMap(inPort)));
        bp.setOutputPorts(List.of(portMap(outPort)));
        return bp;
    }

    private Blueprint sourceSqlBlueprint() {
        Blueprint bp = new Blueprint();
        bp.setBlueprintKey("SourceSQL");
        bp.setCategory(BlueprintCategory.INGESTION);
        bp.setInputPorts(List.of());
        bp.setOutputPorts(List.of(portMap("source_output")));
        bp.setParamsSchema(List.of(
                Map.of("name", "source_query", "type", "string"),
                Map.of("name", "connector_instance_id", "type", "string"),
                Map.of("name", "declared_output_schema", "type", "object[]"),
                Map.of("name", "storage_backend", "type", "string"),
                Map.of("name", "lake_layer", "type", "string"),
                Map.of("name", "lake_format", "type", "string")));
        bp.setSchemaBehavior(Map.of(
                "version", 1,
                "ops", List.of(
                        com.pulse.pipeline.opengine.OpList.opEntryMap(
                                "read-source", "Read source SQL",
                                Map.of(
                                        "source_query", Map.of("param", "source_query"),
                                        "connector_instance_id", Map.of("param", "connector_instance_id"))),
                        com.pulse.pipeline.opengine.OpList.opEntryMap(
                                "add-audit-columns", "Add audit columns", Map.of()),
                        com.pulse.pipeline.opengine.OpList.opEntryMap(
                                "write-sink", "Write to bronze",
                                Map.of("target", "bronze", "mode", "overwrite"))),
                "blueprint_params", List.of(
                        "declared_output_schema",
                        "storage_backend",
                        "lake_layer",
                        "lake_format"),
                "emission", Map.of("orchestration", "airflow", "compute", "pyspark")));
        return bp;
    }

    private Map<String, Object> portMap(String name) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        return m;
    }

    private Map<String, Object> columns(String... pairs) {
        List<Map<String, Object>> cols = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            Map<String, Object> c = new HashMap<>();
            c.put("name", pairs[i]);
            c.put("type", pairs[i + 1]);
            cols.add(c);
        }
        Map<String, Object> out = new HashMap<>();
        out.put("columns", cols);
        return out;
    }

    private InstancePortSchema outputPort(String instanceId, String portName) {
        return storedPorts.stream()
                .filter(p -> p.getInstanceId().equals(instanceId)
                        && p.getPortName().equals(portName)
                        && p.getDirection().equals("output"))
                .findFirst()
                .orElseThrow();
    }

    private List<String> columnNames(InstancePortSchema schema) {
        return extractColumns(schema.getSchemaJson()).stream()
                .map(c -> String.valueOf(c.get("name")))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractColumns(Map<String, Object> schema) {
        Object cols = schema.get("columns");
        if (cols instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object c : list) {
                if (c instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
            }
            return out;
        }
        return List.of();
    }

    private void stubComposition(List<SubPipelineInstance> instances, List<PortWiring> wirings) {
        when(instanceRepo.findByVersionIdOrderByExecutionOrderAsc("v1")).thenReturn(instances);
        when(wiringRepo.findByVersionIdOrderByCreatedAtAsc("v1")).thenReturn(wirings);
        for (SubPipelineInstance inst : instances) {
            lenient().when(instanceRepo.findById(inst.getId())).thenReturn(Optional.of(inst));
        }
    }

    private void stubBlueprint(Blueprint bp) {
        lenient().when(blueprintRepo.findByBlueprintKey(bp.getBlueprintKey())).thenReturn(Optional.of(bp));
    }
}
