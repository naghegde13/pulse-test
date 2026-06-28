package com.pulse.chat.orchestration;

import com.pulse.command.handler.CompositionCommandHandlers;
import com.pulse.command.service.PlanService.PlannedCommand;
import com.pulse.pipeline.service.PipelineCommandHandlers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps staged {@link PlanOperation}s to {@link PlannedCommand}s — the §7.4
 * op→command-type table (06 §F). The Plan-Preview persists these; the atomic
 * Apply ({@code apply_plan} → {@code PlanService.apply}) executes them as
 * {@code composition.*} Command-Log rows under one shared {@code planId}.
 *
 * <ul>
 *   <li>{@code addInstances} → one {@code composition.addInstance} per instance</li>
 *   <li>{@code removeInstance} → {@code composition.removeInstance}</li>
 *   <li>{@code updateInstance} → {@code composition.updateInstance}</li>
 *   <li>{@code mergeWiring}/{@code setWiring} → one {@code composition.wirePorts} per wire</li>
 *   <li>{@code removeWiring} → {@code composition.removeWiring}</li>
 *   <li>{@code rename} → {@code composition.renameInstance}</li>
 *   <li>{@code setName}/{@code setPipelineSetting} → {@code pipeline.update} (§7.16 #9)</li>
 *   <li>{@code clear} → no command (reducer control only)</li>
 * </ul>
 *
 * <p>The aggregate for composition.* is the VERSION ({@code aggregateType="composition"},
 * {@code aggregateId=versionId}); for pipeline.update it is the pipeline.</p>
 */
public final class OpToCommandMapper {

    private OpToCommandMapper() {}

    public static List<PlannedCommand> toCommands(List<PlanOperation> ops,
                                                  String pipelineId, String versionId) {
        List<PlannedCommand> out = new ArrayList<>();
        for (PlanOperation op : ops) {
            switch (op) {
                case PlanOperation.AddInstances add -> {
                    for (PlanOperation.InstanceSpec spec : add.instances()) {
                        Map<String, Object> payload = new LinkedHashMap<>();
                        payload.put("pipelineId", pipelineId);
                        payload.put("instanceRef", spec.ref());
                        payload.put("blueprintKey", spec.blueprintKey());
                        if (spec.storageBackend() != null) payload.put("storageBackend", spec.storageBackend());
                        if (spec.lakeLayer() != null) payload.put("lakeLayer", spec.lakeLayer());
                        if (spec.lakeFormat() != null) payload.put("lakeFormat", spec.lakeFormat());
                        out.add(composition(CompositionCommandHandlers.ADD_INSTANCE, versionId,
                                "Add step '" + spec.ref() + "' [" + spec.blueprintKey() + "]", payload));
                    }
                }
                case PlanOperation.RemoveInstance rm -> {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("instanceRef", rm.instanceRef());
                    out.add(composition(CompositionCommandHandlers.REMOVE_INSTANCE, versionId,
                            "Remove step '" + rm.instanceRef() + "'", payload));
                }
                case PlanOperation.UpdateInstance up -> {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("instanceRef", up.instanceRef());
                    if (up.params() != null && !up.params().isEmpty()) payload.put("params", up.params());
                    if (up.storageBackend() != null) payload.put("storageBackend", up.storageBackend());
                    if (up.lakeLayer() != null) payload.put("lakeLayer", up.lakeLayer());
                    if (up.lakeFormat() != null) payload.put("lakeFormat", up.lakeFormat());
                    out.add(composition(CompositionCommandHandlers.UPDATE_INSTANCE, versionId,
                            "Update params of '" + up.instanceRef() + "'", payload));
                }
                case PlanOperation.MergeWiring merge -> {
                    for (PlanOperation.WireSpec w : merge.wirings()) {
                        out.add(wireCommand(versionId, w));
                    }
                }
                case PlanOperation.SetWiring set -> {
                    for (PlanOperation.WireSpec w : set.wirings()) {
                        out.add(wireCommand(versionId, w));
                    }
                }
                case PlanOperation.RemoveWiring rmw -> {
                    PlanOperation.WireSpec w = rmw.wiring();
                    Map<String, Object> payload = wirePayload(w);
                    out.add(composition(CompositionCommandHandlers.REMOVE_WIRING, versionId,
                            "Remove wiring " + w.sourceRef() + "." + w.sourcePort()
                                    + " -> " + w.targetRef() + "." + w.targetPort(), payload));
                }
                case PlanOperation.Rename ren -> {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("oldRef", ren.oldRef());
                    payload.put("newRef", ren.newRef());
                    out.add(composition(CompositionCommandHandlers.RENAME_INSTANCE, versionId,
                            "Rename '" + ren.oldRef() + "' to '" + ren.newRef() + "'", payload));
                }
                case PlanOperation.SetName sn -> {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("name", sn.name());
                    out.add(new PlannedCommand(PipelineCommandHandlers.UPDATE_PIPELINE, "pipeline",
                            pipelineId, "Rename pipeline to '" + sn.name() + "'", payload));
                }
                case PlanOperation.SetPipelineSetting setting -> {
                    Map<String, Object> payload = new LinkedHashMap<>(setting.settings());
                    out.add(new PlannedCommand(PipelineCommandHandlers.UPDATE_PIPELINE, "pipeline",
                            pipelineId, "Update pipeline settings", payload));
                }
                case PlanOperation.Clear ignored -> {
                    // Reducer control only — no command.
                }
            }
        }
        return out;
    }

    private static PlannedCommand wireCommand(String versionId, PlanOperation.WireSpec w) {
        return composition(CompositionCommandHandlers.WIRE_PORTS, versionId,
                "Wire " + w.sourceRef() + "." + w.sourcePort()
                        + " -> " + w.targetRef() + "." + w.targetPort(), wirePayload(w));
    }

    private static Map<String, Object> wirePayload(PlanOperation.WireSpec w) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sourceRef", w.sourceRef());
        payload.put("sourcePort", w.sourcePort());
        payload.put("targetRef", w.targetRef());
        payload.put("targetPort", w.targetPort());
        return payload;
    }

    private static PlannedCommand composition(String type, String versionId,
                                              String description, Map<String, Object> payload) {
        return new PlannedCommand(type, "composition", versionId, description, payload);
    }
}
