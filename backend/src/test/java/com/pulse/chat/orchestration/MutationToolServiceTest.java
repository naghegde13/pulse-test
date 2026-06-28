package com.pulse.chat.orchestration;

import com.pulse.blueprint.model.Blueprint;
import com.pulse.blueprint.repository.BlueprintRepository;
import com.pulse.blueprint.service.DeprecatedBlueprintCompatibilityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P3 — the op-emitting mutation tier (H2-free unit lane). Each tool emits exactly
 * ONE correct op; bad refs / deprecated blueprints are rejected; the service
 * NEVER writes the canonical graph (it touches no instance/wiring repository).
 */
@ExtendWith(MockitoExtension.class)
class MutationToolServiceTest {

    @Mock private BlueprintRepository blueprintRepo;
    @Mock private DeprecatedBlueprintCompatibilityService compat;

    private MutationToolService svc;

    @BeforeEach
    void setUp() {
        svc = new MutationToolService(blueprintRepo, compat);
    }

    private Blueprint composableBlueprint(String key) {
        Blueprint bp = new Blueprint();
        bp.setBlueprintKey(key);
        bp.setName(key);
        bp.setAddSurface("composition");
        return bp;
    }

    private StagingGraph withInstances(String... refs) {
        java.util.List<PlanOperation.InstanceSpec> specs = new java.util.ArrayList<>();
        for (String r : refs) specs.add(new PlanOperation.InstanceSpec(r, "X", null, null, null));
        return StagingGraph.applyOps(StagingGraph.empty(),
                List.of(new PlanOperation.AddInstances(specs, "seed")));
    }

    @Test
    void addBlueprintInstanceEmitsAddInstances() {
        when(blueprintRepo.findByBlueprintKey("BronzeToSilverCleaning"))
                .thenReturn(Optional.of(composableBlueprint("BronzeToSilverCleaning")));
        when(compat.isCompatReadOnly(any(Blueprint.class))).thenReturn(false);

        PlanOperation op = svc.toOperation("add_blueprint_instance", Map.of(
                "instance_name", "clean",
                "blueprint_key", "BronzeToSilverCleaning",
                "reasoning", "needs cleaning"), StagingGraph.empty());

        PlanOperation.AddInstances add = assertInstanceOf(PlanOperation.AddInstances.class, op);
        assertEquals(1, add.instances().size());
        assertEquals("clean", add.instances().get(0).ref());
        assertEquals("BronzeToSilverCleaning", add.instances().get(0).blueprintKey());
        assertEquals("needs cleaning", add.reasoning());
        // NO initial params (3-F4): InstanceSpec carries no params field.
    }

    @Test
    void addRequiresReasoning() {
        // Reasoning is validated before the blueprint lookup, so no stubbing needed.
        var ex = assertThrows(MutationToolService.MutationValidationException.class,
                () -> svc.toOperation("add_blueprint_instance", Map.of(
                        "instance_name", "clean", "blueprint_key", "BronzeToSilverCleaning"),
                        StagingGraph.empty()));
        assertTrue(ex.getMessage().toLowerCase().contains("reasoning"));
    }

    @Test
    void addRejectsDeprecatedBlueprint() {
        Blueprint dep = composableBlueprint("OldThing");
        when(blueprintRepo.findByBlueprintKey("OldThing")).thenReturn(Optional.of(dep));
        when(compat.isCompatReadOnly(dep)).thenReturn(true);
        lenient().when(compat.replacementFor("OldThing")).thenReturn("NewThing");

        var ex = assertThrows(MutationToolService.MutationValidationException.class,
                () -> svc.toOperation("add_blueprint_instance", Map.of(
                        "instance_name", "x", "blueprint_key", "OldThing", "reasoning", "r"),
                        StagingGraph.empty()));
        assertTrue(ex.getMessage().contains("BLUEPRINT_COMPAT_READ_ONLY"));
    }

    @Test
    void addRejectsOrchestrationPolicyBlueprint() {
        Blueprint policy = composableBlueprint("SchedulePolicy");
        policy.setAddSurface("orchestration_policy");
        when(blueprintRepo.findByBlueprintKey("SchedulePolicy")).thenReturn(Optional.of(policy));
        when(compat.isCompatReadOnly(policy)).thenReturn(false);

        var ex = assertThrows(MutationToolService.MutationValidationException.class,
                () -> svc.toOperation("add_blueprint_instance", Map.of(
                        "instance_name", "x", "blueprint_key", "SchedulePolicy", "reasoning", "r"),
                        StagingGraph.empty()));
        assertTrue(ex.getMessage().contains("STEP_REQUIRES_PIPELINE_ORCHESTRATION"));
    }

    @Test
    void addRejectsUnknownBlueprint() {
        when(blueprintRepo.findByBlueprintKey("Nope")).thenReturn(Optional.empty());
        assertThrows(MutationToolService.MutationValidationException.class,
                () -> svc.toOperation("add_blueprint_instance", Map.of(
                        "instance_name", "x", "blueprint_key", "Nope", "reasoning", "r"),
                        StagingGraph.empty()));
    }

    @Test
    void wirePortsEmitsMergeWiringAndRequiresReasoning() {
        // No ports declared on the blueprint -> port check defers to apply-time.
        when(blueprintRepo.findByBlueprintKey("X")).thenReturn(Optional.of(composableBlueprint("X")));
        StagingGraph base = withInstances("a", "b");

        PlanOperation op = svc.toOperation("wire_ports", Map.of(
                "source_instance_name", "a", "source_port", "out",
                "target_instance_name", "b", "target_port", "in",
                "reasoning", "connect"), base);
        PlanOperation.MergeWiring merge = assertInstanceOf(PlanOperation.MergeWiring.class, op);
        assertEquals("a", merge.wirings().get(0).sourceRef());
        assertEquals("b", merge.wirings().get(0).targetRef());

        var ex = assertThrows(MutationToolService.MutationValidationException.class,
                () -> svc.toOperation("wire_ports", Map.of(
                        "source_instance_name", "a", "source_port", "out",
                        "target_instance_name", "b", "target_port", "in"), base));
        assertTrue(ex.getMessage().toLowerCase().contains("reasoning"));
    }

    @Test
    void wirePortsRejectsUnknownRef() {
        StagingGraph base = withInstances("a");
        assertThrows(MutationToolService.MutationValidationException.class,
                () -> svc.toOperation("wire_ports", Map.of(
                        "source_instance_name", "a", "source_port", "out",
                        "target_instance_name", "ghost", "target_port", "in",
                        "reasoning", "x"), base));
    }

    @Test
    void setParamsEmitsUpdateInstance() {
        StagingGraph base = withInstances("clean");
        PlanOperation op = svc.toOperation("set_params", Map.of(
                "instance_name", "clean", "params", Map.of("k", "v")), base);
        PlanOperation.UpdateInstance up = assertInstanceOf(PlanOperation.UpdateInstance.class, op);
        assertEquals("clean", up.instanceRef());
        assertEquals("v", up.params().get("k"));
    }

    @Test
    void removeInstanceEmitsRemoveInstance() {
        StagingGraph base = withInstances("clean");
        PlanOperation op = svc.toOperation("remove_instance", Map.of("instance_name", "clean"), base);
        assertEquals("clean", assertInstanceOf(PlanOperation.RemoveInstance.class, op).instanceRef());
    }

    @Test
    void removeWireEmitsRemoveWiring() {
        PlanOperation op = svc.toOperation("remove_wire", Map.of(
                "source_instance_name", "a", "source_port", "out",
                "target_instance_name", "b", "target_port", "in"), StagingGraph.empty());
        var rmw = assertInstanceOf(PlanOperation.RemoveWiring.class, op);
        assertEquals("a", rmw.wiring().sourceRef());
    }

    @Test
    void renameInstanceEmitsRename() {
        StagingGraph base = withInstances("a");
        PlanOperation op = svc.toOperation("rename_instance", Map.of(
                "instance_name", "a", "new_name", "source"), base);
        var ren = assertInstanceOf(PlanOperation.Rename.class, op);
        assertEquals("a", ren.oldRef());
        assertEquals("source", ren.newRef());
    }

    @Test
    void setPipelineSettingEmitsSetPipelineSetting() {
        PlanOperation op = svc.toOperation("set_pipeline_setting", Map.of(
                "settings", Map.of("schedule_cron", "@daily")), StagingGraph.empty());
        var setting = assertInstanceOf(PlanOperation.SetPipelineSetting.class, op);
        assertEquals("@daily", setting.settings().get("schedule_cron"));
    }

    @Test
    void neverWritesCanonical() {
        // The mutation tier has no repository that writes instances/wirings — it
        // only reads BlueprintRepository for validation. Assert no write occurs
        // by confirming the only repo interaction is the read-only findByKey.
        when(blueprintRepo.findByBlueprintKey("X")).thenReturn(Optional.of(composableBlueprint("X")));
        when(compat.isCompatReadOnly(any(Blueprint.class))).thenReturn(false);
        svc.toOperation("add_blueprint_instance", Map.of(
                "instance_name", "x", "blueprint_key", "X", "reasoning", "r"), StagingGraph.empty());
        verify(blueprintRepo, never()).save(any());
        verify(blueprintRepo, never()).deleteById(any());
    }
}
