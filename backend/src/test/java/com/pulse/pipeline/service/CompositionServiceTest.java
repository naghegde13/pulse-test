package com.pulse.pipeline.service;

import com.pulse.blueprint.model.Blueprint;
import com.pulse.blueprint.model.BlueprintCategory;
import com.pulse.blueprint.repository.BlueprintRepository;
import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.pipeline.model.PortWiring;
import com.pulse.pipeline.model.SubPipelineInstance;
import com.pulse.pipeline.repository.PortWiringRepository;
import com.pulse.pipeline.repository.SubPipelineInstanceRepository;
import com.pulse.sor.model.Dataset;
import com.pulse.sor.repository.DatasetRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompositionServiceTest {

    @Mock private SubPipelineInstanceRepository instanceRepo;
    @Mock private PortWiringRepository wiringRepo;
    @Mock private BlueprintRepository blueprintRepo;
    @Mock private DatasetRepository datasetRepo;
    @Mock private SchemaInferenceService schemaInferenceService;
    @Mock private com.pulse.blueprint.service.DeprecatedBlueprintCompatibilityService compat;
    @Mock private BlueprintInstanceConfigurationService instanceConfig;

    @org.junit.jupiter.api.BeforeEach
    void stubInstanceConfig() {
        // ARCH-010: the canonical-field resolver is exercised by dedicated
        // BlueprintInstanceConfigurationService tests. Here we stub it to a
        // pass-through that preserves the incoming params and reports DPC, so
        // CompositionService's own tests focus on its responsibilities.
        org.mockito.Mockito.lenient().when(instanceConfig.resolveForAdd(
                any(), any(), any(), any(), any()))
                .thenAnswer(inv -> new BlueprintInstanceConfigurationService.Resolution(
                        "DPC", null, null,
                        inv.getArgument(4) == null ? Map.of() : (Map<String, Object>) inv.getArgument(4),
                        List.of(), List.of()));
        org.mockito.Mockito.lenient().when(instanceConfig.resolveForUpdate(
                any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    SubPipelineInstance current = inv.getArgument(0);
                    String backend = current != null && current.getStorageBackend() != null
                            ? current.getStorageBackend() : "DPC";
                    Map<String, Object> params = inv.getArgument(4) == null
                            ? Map.of() : (Map<String, Object>) inv.getArgument(4);
                    return new BlueprintInstanceConfigurationService.Resolution(
                            backend,
                            current != null ? current.getLakeLayer() : null,
                            current != null ? current.getLakeFormat() : null,
                            params,
                            List.of(), List.of());
                });
        org.mockito.Mockito.lenient().doAnswer(inv -> {
            SubPipelineInstance inst = inv.getArgument(0);
            BlueprintInstanceConfigurationService.Resolution r = inv.getArgument(1);
            inst.setStorageBackend(r.storageBackend());
            inst.setLakeLayer(r.lakeLayer());
            inst.setLakeFormat(r.lakeFormat());
            inst.setParams(new java.util.LinkedHashMap<>(r.sanitizedParams()));
            return null;
        }).when(instanceConfig).apply(any(), any());
    }

    @InjectMocks
    private CompositionService service;

    // -----------------------------------------------------------------------
    //  addInstance tests
    // -----------------------------------------------------------------------

    @Test
    void addInstance_createsInstanceWithCorrectExecutionOrder() {
        // Given
        Blueprint bp = buildBlueprint("SnapshotIngestion", BlueprintCategory.INGESTION);
        when(blueprintRepo.findByBlueprintKey("SnapshotIngestion")).thenReturn(Optional.of(bp));

        SubPipelineInstance existing = buildInstance("existing", "version-1", 1);
        when(instanceRepo.findByVersionIdOrderByExecutionOrderAsc("version-1"))
                .thenReturn(List.of(existing));
        when(instanceRepo.save(any(SubPipelineInstance.class))).thenAnswer(inv -> inv.getArgument(0));

        // When
        SubPipelineInstance result = service.addInstance(
                "pipeline-1", "version-1", "SnapshotIngestion", "New Step", Map.of("key", "value"));

        // Then
        assertEquals(2, result.getExecutionOrder());
        assertEquals("New Step", result.getName());
        assertEquals("SnapshotIngestion", result.getBlueprintKey());
        assertEquals("pipeline-1", result.getPipelineId());
        assertEquals("version-1", result.getVersionId());
        assertEquals(Map.of("key", "value"), result.getParams());
        verify(instanceRepo).save(any(SubPipelineInstance.class));
    }

    @Test
    void addInstance_firstInstance_getsExecutionOrderOne() {
        // Given
        Blueprint bp = buildBlueprint("SnapshotIngestion", BlueprintCategory.INGESTION);
        when(blueprintRepo.findByBlueprintKey("SnapshotIngestion")).thenReturn(Optional.of(bp));
        when(instanceRepo.findByVersionIdOrderByExecutionOrderAsc("version-1"))
                .thenReturn(List.of());
        when(instanceRepo.save(any(SubPipelineInstance.class))).thenAnswer(inv -> inv.getArgument(0));

        // When
        SubPipelineInstance result = service.addInstance(
                "pipeline-1", "version-1", "SnapshotIngestion", null, null);

        // Then
        assertEquals(1, result.getExecutionOrder());
        assertEquals(bp.getName(), result.getName()); // Falls back to blueprint name
    }

    // -----------------------------------------------------------------------
    //  removeInstance tests
    // -----------------------------------------------------------------------

    @Test
    void removeInstance_deletesInstance() {
        // Given
        SubPipelineInstance inst = buildInstance("to-delete", "version-1", 1);
        when(instanceRepo.findById("to-delete")).thenReturn(Optional.of(inst));

        // When
        service.removeInstance("version-1", "to-delete");

        // Then
        verify(instanceRepo).delete(inst);
    }

    @Test
    void removeInstance_wrongVersion_throwsException() {
        // Given
        SubPipelineInstance inst = buildInstance("inst-1", "other-version", 1);
        when(instanceRepo.findById("inst-1")).thenReturn(Optional.of(inst));

        // When/Then
        assertThrows(IllegalArgumentException.class,
                () -> service.removeInstance("version-1", "inst-1"));
    }

    // -----------------------------------------------------------------------
    //  wirePort tests
    // -----------------------------------------------------------------------

    @Test
    void wirePort_createsWiringBetweenInstances() {
        // Given
        SubPipelineInstance source = buildInstance("source-inst", "version-1", 1);
        source.setBlueprintKey("SourceBp");
        SubPipelineInstance target = buildInstance("target-inst", "version-1", 2);
        target.setBlueprintKey("TargetBp");
        Blueprint sourceBlueprint = buildBlueprint("SourceBp", BlueprintCategory.INGESTION);
        sourceBlueprint.setOutputPorts(List.of(Map.of("name", "output")));
        Blueprint targetBlueprint = buildBlueprint("TargetBp", BlueprintCategory.TRANSFORM);
        targetBlueprint.setInputPorts(List.of(Map.of("name", "left_input")));
        when(instanceRepo.findById("source-inst")).thenReturn(Optional.of(source));
        when(instanceRepo.findById("target-inst")).thenReturn(Optional.of(target));
        when(blueprintRepo.findByBlueprintKey("SourceBp")).thenReturn(Optional.of(sourceBlueprint));
        when(blueprintRepo.findByBlueprintKey("TargetBp")).thenReturn(Optional.of(targetBlueprint));
        when(wiringRepo.save(any(PortWiring.class))).thenAnswer(inv -> inv.getArgument(0));

        // When
        PortWiring result = service.wirePort("version-1",
                "source-inst", "output", "target-inst", "left_input");

        // Then
        assertEquals("version-1", result.getVersionId());
        assertEquals("source-inst", result.getSourceInstanceId());
        assertEquals("output", result.getSourcePortName());
        assertEquals("target-inst", result.getTargetInstanceId());
        assertEquals("left_input", result.getTargetPortName());
        verify(wiringRepo).save(any(PortWiring.class));
    }

    @Test
    void wirePort_invalidSourcePort_throwsException() {
        SubPipelineInstance source = buildInstance("source-inst", "version-1", 1);
        source.setBlueprintKey("SourceBp");
        SubPipelineInstance target = buildInstance("target-inst", "version-1", 2);
        Blueprint sourceBlueprint = buildBlueprint("SourceBp", BlueprintCategory.INGESTION);
        sourceBlueprint.setOutputPorts(List.of(Map.of("name", "output")));
        when(instanceRepo.findById("source-inst")).thenReturn(Optional.of(source));
        when(instanceRepo.findById("target-inst")).thenReturn(Optional.of(target));
        when(blueprintRepo.findByBlueprintKey("SourceBp")).thenReturn(Optional.of(sourceBlueprint));

        assertThrows(IllegalArgumentException.class,
                () -> service.wirePort("version-1", "source-inst", "bad_output", "target-inst", "left_input"));
    }

    // -----------------------------------------------------------------------
    //  unwire tests
    // -----------------------------------------------------------------------

    @Test
    void unwire_deletesWiring() {
        // Given
        PortWiring wiring = new PortWiring();
        wiring.setId("wiring-1");
        wiring.setVersionId("version-1");
        when(wiringRepo.findById("wiring-1")).thenReturn(Optional.of(wiring));

        // When
        service.unwire("version-1", "wiring-1");

        // Then
        verify(wiringRepo).delete(wiring);
    }

    @Test
    void unwire_wrongVersion_throwsException() {
        // Given
        PortWiring wiring = new PortWiring();
        wiring.setId("wiring-1");
        wiring.setVersionId("other-version");
        when(wiringRepo.findById("wiring-1")).thenReturn(Optional.of(wiring));

        // When/Then
        assertThrows(IllegalArgumentException.class,
                () -> service.unwire("version-1", "wiring-1"));
    }

    // -----------------------------------------------------------------------
    //  reorder tests
    // -----------------------------------------------------------------------

    @Test
    void reorder_updatesExecutionOrder() {
        // Given
        SubPipelineInstance inst1 = buildInstance("inst-1", "version-1", 1);
        SubPipelineInstance inst2 = buildInstance("inst-2", "version-1", 2);
        SubPipelineInstance inst3 = buildInstance("inst-3", "version-1", 3);
        when(instanceRepo.findByVersionIdOrderByExecutionOrderAsc("version-1"))
                .thenReturn(List.of(inst1, inst2, inst3));
        when(instanceRepo.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        // When — reverse order
        List<SubPipelineInstance> result = service.reorder("version-1",
                List.of("inst-3", "inst-1", "inst-2"));

        // Then
        assertEquals(2, inst1.getExecutionOrder()); // was 1, now 2
        assertEquals(3, inst2.getExecutionOrder()); // was 2, now 3
        assertEquals(1, inst3.getExecutionOrder()); // was 3, now 1
        verify(instanceRepo).saveAll(anyList());
    }

    // -----------------------------------------------------------------------
    //  updateParams tests
    // -----------------------------------------------------------------------

    @Test
    void updateParams_updatesInstanceParams() {
        // Given
        SubPipelineInstance inst = buildInstance("inst-1", "version-1", 1);
        inst.setParams(Map.of("old_key", "old_value"));
        when(instanceRepo.findById("inst-1")).thenReturn(Optional.of(inst));
        when(instanceRepo.save(any(SubPipelineInstance.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> newParams = Map.of("new_key", "new_value");

        // When
        SubPipelineInstance result = service.updateInstanceParams("version-1", "inst-1", newParams);

        // Then
        assertEquals(newParams, result.getParams());
        verify(instanceRepo).save(inst);
    }

    // -----------------------------------------------------------------------
    //  getUpstreamSchema tests
    // -----------------------------------------------------------------------

    @Test
    void getUpstreamSchema_followsWiringsToFindUpstreamSchema() {
        // Given
        SubPipelineInstance target = buildInstance("target-inst", "version-1", 2);
        SubPipelineInstance upstream = buildInstance("upstream-inst", "version-1", 1);
        Map<String, Object> schema = Map.of("columns", List.of("id", "name", "amount"));
        upstream.setOutputSchema(schema);

        PortWiring wiring = new PortWiring();
        wiring.setVersionId("version-1");
        wiring.setSourceInstanceId("upstream-inst");
        wiring.setTargetInstanceId("target-inst");

        when(instanceRepo.findById("target-inst")).thenReturn(Optional.of(target));
        when(wiringRepo.findByVersionIdAndTargetInstanceId("version-1", "target-inst"))
                .thenReturn(List.of(wiring));
        when(instanceRepo.findById("upstream-inst")).thenReturn(Optional.of(upstream));

        // When
        Map<String, Object> result = service.getUpstreamSchema("version-1", "target-inst");

        // Then
        assertEquals(schema, result);
    }

    @Test
    void getUpstreamSchema_noWirings_returnsEmptyMap() {
        // Given
        SubPipelineInstance target = buildInstance("target-inst", "version-1", 1);
        when(instanceRepo.findById("target-inst")).thenReturn(Optional.of(target));
        when(wiringRepo.findByVersionIdAndTargetInstanceId("version-1", "target-inst"))
                .thenReturn(List.of());

        // When
        Map<String, Object> result = service.getUpstreamSchema("version-1", "target-inst");

        // Then
        assertTrue(result.isEmpty());
    }

    // -----------------------------------------------------------------------
    //  updateInstanceSchema tests
    // -----------------------------------------------------------------------

    @Test
    void updateInstanceSchema_setsOutputSchemaOnInstance() {
        // Given
        SubPipelineInstance inst = buildInstance("inst-1", "version-1", 1);
        when(instanceRepo.findById("inst-1")).thenReturn(Optional.of(inst));
        when(instanceRepo.save(any(SubPipelineInstance.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> schema = Map.of("columns", List.of("id", "name"));

        // When
        SubPipelineInstance result = service.updateInstanceSchema("version-1", "inst-1", schema);

        // Then
        assertEquals(schema, result.getOutputSchema());
        verify(instanceRepo).save(inst);
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    private SubPipelineInstance buildInstance(String id, String versionId, int order) {
        SubPipelineInstance inst = new SubPipelineInstance();
        inst.setId(id);
        inst.setPipelineId("pipeline-1");
        inst.setVersionId(versionId);
        inst.setBlueprintId("bp-1");
        inst.setBlueprintKey("SnapshotIngestion");
        inst.setBlueprintVersion("1.0");
        inst.setName("Instance " + id);
        inst.setExecutionOrder(order);
        inst.setParams(new HashMap<>());
        return inst;
    }

    private Blueprint buildBlueprint(String key, BlueprintCategory category) {
        Blueprint bp = new Blueprint();
        bp.setId("bp-" + key);
        bp.setBlueprintKey(key);
        bp.setName(key + " Blueprint");
        bp.setDescription("Test blueprint for " + key);
        bp.setCategory(category);
        bp.setVersion("1.0");
        return bp;
    }
}
