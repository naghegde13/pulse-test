package com.pulse.chat.orchestration;

import com.pulse.command.handler.CompositionCommandHandlers;
import com.pulse.command.service.PlanService.PlannedCommand;
import com.pulse.pipeline.service.PipelineCommandHandlers;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P4 — the op→command mapping (§7.4 table). Each staged op maps to the right
 * command type; addInstances expands per instance; setName/setPipelineSetting
 * fold into pipeline.update (no new types).
 */
class OpToCommandMapperTest {

    @Test
    void mapsAllSixCompositionTypesPlusPipelineUpdate() {
        List<PlanOperation> ops = List.of(
                new PlanOperation.AddInstances(List.of(
                        new PlanOperation.InstanceSpec("read", "SourceSQL", null, null, null),
                        new PlanOperation.InstanceSpec("clean", "BronzeToSilverCleaning", "DPC", "silver", null)), "add"),
                new PlanOperation.MergeWiring(List.of(
                        new PlanOperation.WireSpec("read", "out", "clean", "in")), "wire"),
                new PlanOperation.UpdateInstance("clean", Map.of("k", "v"), null, null, null, "set"),
                new PlanOperation.RemoveWiring(new PlanOperation.WireSpec("read", "out", "clean", "in"), "unwire"),
                new PlanOperation.RemoveInstance("clean", "rm"),
                new PlanOperation.Rename("read", "source", "rename"),
                new PlanOperation.SetName("My Pipeline", "rename pipeline"),
                new PlanOperation.SetPipelineSetting(Map.of("schedule_cron", "@daily"), "schedule"),
                new PlanOperation.Clear());

        List<PlannedCommand> commands = OpToCommandMapper.toCommands(ops, "pipe-1", "ver-1");

        Set<String> types = commands.stream()
                .map(PlannedCommand::commandType).collect(Collectors.toSet());
        assertTrue(types.contains(CompositionCommandHandlers.ADD_INSTANCE));
        assertTrue(types.contains(CompositionCommandHandlers.WIRE_PORTS));
        assertTrue(types.contains(CompositionCommandHandlers.UPDATE_INSTANCE));
        assertTrue(types.contains(CompositionCommandHandlers.REMOVE_WIRING));
        assertTrue(types.contains(CompositionCommandHandlers.REMOVE_INSTANCE));
        assertTrue(types.contains(CompositionCommandHandlers.RENAME_INSTANCE));
        assertTrue(types.contains(PipelineCommandHandlers.UPDATE_PIPELINE));
        assertEquals(Set.of("composition", "pipeline"),
                commands.stream().map(PlannedCommand::aggregateType).collect(Collectors.toSet()));

        // addInstances with two specs -> two addInstance commands.
        long addCount = commands.stream()
                .filter(c -> c.commandType().equals(CompositionCommandHandlers.ADD_INSTANCE)).count();
        assertEquals(2, addCount);

        // Clear maps to no command.
        assertEquals(9, commands.size(), "2 add + 1 wire + 1 update + 1 removeWire + 1 remove "
                + "+ 1 rename + 2 pipeline.update = 9 (clear -> none)");

        // composition.* aggregateId = versionId.
        commands.stream()
                .filter(c -> c.aggregateType().equals("composition"))
                .forEach(c -> assertEquals("ver-1", c.aggregateId()));
    }
}
