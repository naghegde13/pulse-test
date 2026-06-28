package com.pulse.command.handler;

import com.pulse.command.model.CommandLog;
import com.pulse.command.service.CommandService;
import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.pipeline.model.PortWiring;
import com.pulse.pipeline.model.SubPipelineInstance;
import com.pulse.pipeline.service.CompositionService;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * First-class Command-Logged composition mutations (IMPL-ui-composition Phase 4;
 * 06 §F). Composition writes used to bypass the Command Log — chat tools called
 * {@link CompositionService} directly. These six handlers make composition
 * mutations first-class, idempotent, Command-Logged commands so the atomic Apply
 * ({@code apply_plan}) writes one Command-Log row per staged op under one shared
 * {@code planId}.
 *
 * <p>Naming follows the {@code noun.verb} convention of
 * {@code PipelineCommandHandlers} ({@code pipeline.createRevision} etc.).
 * {@code setName} / {@code setPipelineSetting} ops do NOT get their own types —
 * they fold into the existing {@code pipeline.update} (§7.16 #9), so only SIX
 * new types are registered here.</p>
 *
 * <p>The aggregate is the pipeline VERSION ({@code aggregateType="composition"},
 * {@code aggregateId=versionId}). Each handler resolves the {@code instanceRef}
 * (the instance NAME / the staged ref) to a real id and calls the matching
 * {@link CompositionService} method (the canonical writer).</p>
 */
@Component
public class CompositionCommandHandlers {

    public static final String ADD_INSTANCE = "composition.addInstance";
    public static final String REMOVE_INSTANCE = "composition.removeInstance";
    public static final String UPDATE_INSTANCE = "composition.updateInstance";
    public static final String WIRE_PORTS = "composition.wirePorts";
    public static final String REMOVE_WIRING = "composition.removeWiring";
    public static final String RENAME_INSTANCE = "composition.renameInstance";

    private final CommandService commandService;
    private final CompositionService compositionService;

    public CompositionCommandHandlers(CommandService commandService,
                                      CompositionService compositionService) {
        this.commandService = commandService;
        this.compositionService = compositionService;
    }

    @PostConstruct
    void register() {
        commandService.registerHandler(ADD_INSTANCE, this::handleAddInstance);
        commandService.registerHandler(REMOVE_INSTANCE, this::handleRemoveInstance);
        commandService.registerHandler(UPDATE_INSTANCE, this::handleUpdateInstance);
        commandService.registerHandler(WIRE_PORTS, this::handleWirePorts);
        commandService.registerHandler(REMOVE_WIRING, this::handleRemoveWiring);
        commandService.registerHandler(RENAME_INSTANCE, this::handleRenameInstance);
    }

    @SuppressWarnings("unchecked")
    private Object handleAddInstance(CommandLog cmd) {
        Map<String, Object> p = cmd.getPayload();
        String pipelineId = str(p, "pipelineId");
        String versionId = cmd.getAggregateId();
        String blueprintKey = str(p, "blueprintKey");
        String instanceRef = str(p, "instanceRef");
        Map<String, Object> params = p.get("params") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        String storageBackend = str(p, "storageBackend");
        String lakeLayer = str(p, "lakeLayer");
        String lakeFormat = str(p, "lakeFormat");

        CompositionService.AddInstanceResult result = compositionService.addInstance(
                pipelineId, versionId, blueprintKey, instanceRef, params,
                storageBackend, lakeLayer, lakeFormat);
        SubPipelineInstance inst = result.instance();
        return Map.of(
                "createdAggregateType", "instance",
                "createdAggregateId", inst.getId(),
                "instanceId", inst.getId(),
                "instanceRef", inst.getName());
    }

    private Object handleRemoveInstance(CommandLog cmd) {
        Map<String, Object> p = cmd.getPayload();
        String versionId = cmd.getAggregateId();
        String instanceId = resolveInstanceId(versionId, p);
        compositionService.removeInstance(versionId, instanceId);
        return Map.of("instanceId", instanceId);
    }

    @SuppressWarnings("unchecked")
    private Object handleUpdateInstance(CommandLog cmd) {
        Map<String, Object> p = cmd.getPayload();
        String versionId = cmd.getAggregateId();
        String instanceId = resolveInstanceId(versionId, p);
        Map<String, Object> params = p.get("params") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        String storageBackend = str(p, "storageBackend");
        String lakeLayer = str(p, "lakeLayer");
        String lakeFormat = str(p, "lakeFormat");
        SubPipelineInstance inst = compositionService.updateInstance(
                versionId, instanceId, params, storageBackend, lakeLayer, lakeFormat).instance();
        return Map.of("instanceId", inst.getId());
    }

    private Object handleWirePorts(CommandLog cmd) {
        Map<String, Object> p = cmd.getPayload();
        String versionId = cmd.getAggregateId();
        String sourceId = resolveNamedInstanceId(versionId, str(p, "sourceRef"));
        String targetId = resolveNamedInstanceId(versionId, str(p, "targetRef"));
        PortWiring wiring = compositionService.wirePort(
                versionId, sourceId, str(p, "sourcePort"), targetId, str(p, "targetPort"));
        return Map.of("wiringId", wiring.getId());
    }

    private Object handleRemoveWiring(CommandLog cmd) {
        Map<String, Object> p = cmd.getPayload();
        String versionId = cmd.getAggregateId();
        String sourceId = resolveNamedInstanceId(versionId, str(p, "sourceRef"));
        String targetId = resolveNamedInstanceId(versionId, str(p, "targetRef"));
        String wiringId = compositionService.resolveWiringId(
                versionId, sourceId, str(p, "sourcePort"), targetId, str(p, "targetPort"));
        if (wiringId == null) {
            throw new ResourceNotFoundException("PortWiring",
                    str(p, "sourceRef") + ":" + str(p, "sourcePort")
                            + "->" + str(p, "targetRef") + ":" + str(p, "targetPort"));
        }
        compositionService.unwire(versionId, wiringId);
        return Map.of("wiringId", wiringId);
    }

    private Object handleRenameInstance(CommandLog cmd) {
        Map<String, Object> p = cmd.getPayload();
        String versionId = cmd.getAggregateId();
        String instanceId = resolveNamedInstanceId(versionId, str(p, "oldRef"));
        SubPipelineInstance inst = compositionService.renameInstance(versionId, instanceId, str(p, "newRef"));
        return Map.of("instanceId", inst.getId(), "instanceRef", inst.getName());
    }

    // ------------------------------------------------------------------
    // Ref resolution helpers
    // ------------------------------------------------------------------

    /** Resolve instanceId from an explicit {@code instanceId} payload key or via {@code instanceRef} name. */
    private String resolveInstanceId(String versionId, Map<String, Object> p) {
        String explicit = str(p, "instanceId");
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        return resolveNamedInstanceId(versionId, str(p, "instanceRef"));
    }

    private String resolveNamedInstanceId(String versionId, String name) {
        String id = compositionService.resolveInstanceIdByName(versionId, name);
        if (id == null) {
            throw new ResourceNotFoundException("SubPipelineInstance (by name)", name);
        }
        return id;
    }

    private static String str(Map<String, Object> p, String key) {
        Object v = p == null ? null : p.get(key);
        return v == null ? null : v.toString();
    }
}
