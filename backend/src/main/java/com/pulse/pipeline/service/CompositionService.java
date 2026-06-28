package com.pulse.pipeline.service;

import com.pulse.blueprint.exception.BlueprintNotAddableException;
import com.pulse.blueprint.model.Blueprint;
import com.pulse.blueprint.repository.BlueprintRepository;
import com.pulse.blueprint.service.DeprecatedBlueprintCompatibilityService;
import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.pipeline.model.PortWiring;
import com.pulse.pipeline.model.SubPipelineInstance;
import com.pulse.pipeline.repository.PortWiringRepository;
import com.pulse.pipeline.repository.SubPipelineInstanceRepository;
import com.pulse.sor.model.Dataset;
import com.pulse.sor.repository.DatasetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class CompositionService {

    private static final Logger log = LoggerFactory.getLogger(CompositionService.class);

    private final SubPipelineInstanceRepository instanceRepo;
    private final PortWiringRepository wiringRepo;
    private final BlueprintRepository blueprintRepo;
    private final DatasetRepository datasetRepo;
    private final SchemaPropagationService schemaPropagationService;
    private final DeprecatedBlueprintCompatibilityService compat;
    private final BlueprintInstanceConfigurationService instanceConfig;

    public CompositionService(SubPipelineInstanceRepository instanceRepo,
                              PortWiringRepository wiringRepo,
                              BlueprintRepository blueprintRepo,
                              DatasetRepository datasetRepo,
                              @Lazy SchemaPropagationService schemaPropagationService,
                              DeprecatedBlueprintCompatibilityService compat,
                              BlueprintInstanceConfigurationService instanceConfig) {
        this.instanceRepo = instanceRepo;
        this.wiringRepo = wiringRepo;
        this.blueprintRepo = blueprintRepo;
        this.datasetRepo = datasetRepo;
        this.schemaPropagationService = schemaPropagationService;
        this.compat = compat;
        this.instanceConfig = instanceConfig;
    }

    public CompositionView getComposition(String versionId) {
        List<SubPipelineInstance> instances = instanceRepo.findByVersionIdOrderByExecutionOrderAsc(versionId);
        List<PortWiring> wirings = wiringRepo.findByVersionIdOrderByCreatedAtAsc(versionId);
        return new CompositionView(instances, wirings);
    }

    @Transactional
    public SubPipelineInstance addInstance(String pipelineId, String versionId,
                                          String blueprintKey, String name,
                                          Map<String, Object> params) {
        return addInstance(pipelineId, versionId, blueprintKey, name, params, null, null, null).instance();
    }

    /**
     * Canonical add (ARCH-010). Top-level {@code storageBackend},
     * {@code lakeLayer}, {@code lakeFormat} win over mirrored {@code params}
     * keys; missing {@code storageBackend} falls back to the pipeline default.
     */
    @Transactional
    public AddInstanceResult addInstance(String pipelineId, String versionId,
                                         String blueprintKey, String name,
                                         Map<String, Object> params,
                                         String storageBackend,
                                         String lakeLayer,
                                         String lakeFormat) {
        Blueprint bp = blueprintRepo.findByBlueprintKey(blueprintKey)
                .orElseThrow(() -> new ResourceNotFoundException("Blueprint", blueprintKey));

        // ARCH-014: deprecated/deferred -> 422 BLUEPRINT_COMPAT_READ_ONLY.
        compat.rejectIfCompatReadOnly(bp.getBlueprintKey());

        // ARCH-011 / ARCH-012: only composition surface rows may be instantiated.
        rejectNonCompositionSurface(bp);

        // ARCH-010: resolve canonical fields, strip mirrored params keys.
        BlueprintInstanceConfigurationService.Resolution resolution =
                instanceConfig.resolveForAdd(pipelineId, storageBackend, lakeLayer, lakeFormat, params);

        List<SubPipelineInstance> existing = instanceRepo.findByVersionIdOrderByExecutionOrderAsc(versionId);
        int nextOrder = existing.stream().mapToInt(SubPipelineInstance::getExecutionOrder).max().orElse(0) + 1;

        SubPipelineInstance inst = new SubPipelineInstance();
        inst.setPipelineId(pipelineId);
        inst.setVersionId(versionId);
        inst.setBlueprintId(bp.getId());
        inst.setBlueprintKey(bp.getBlueprintKey());
        inst.setBlueprintVersion(bp.getVersion());
        inst.setName(name != null ? name : bp.getName());
        inst.setExecutionOrder(nextOrder);
        inst.setInputDatasets(new ArrayList<>());
        inst.setOutputDatasets(new ArrayList<>());
        instanceConfig.apply(inst, resolution);

        SubPipelineInstance saved = instanceRepo.save(inst);
        tryPropagate(() -> schemaPropagationService.propagateFromInstance(versionId, saved.getId()));
        return new AddInstanceResult(saved, resolution);
    }

    @Transactional
    public void removeInstance(String versionId, String instanceId) {
        SubPipelineInstance inst = instanceRepo.findById(instanceId)
                .orElseThrow(() -> new ResourceNotFoundException("SubPipelineInstance", instanceId));
        if (!versionId.equals(inst.getVersionId())) {
            throw new IllegalArgumentException("Instance does not belong to this version");
        }
        compat.rejectIfInstanceCompatReadOnly(inst);
        instanceRepo.delete(inst);
        tryPropagate(() -> schemaPropagationService.propagateFromVersion(versionId));
    }

    @Transactional
    public List<SubPipelineInstance> reorder(String versionId, List<String> orderedInstanceIds) {
        // ARCH-014: reject reorder operations whose set includes deprecated/deferred instances.
        compat.rejectIfAnyInstanceCompatReadOnly(orderedInstanceIds);
        List<SubPipelineInstance> instances = instanceRepo.findByVersionIdOrderByExecutionOrderAsc(versionId);
        Map<String, SubPipelineInstance> byId = new HashMap<>();
        instances.forEach(inst -> byId.put(inst.getId(), inst));

        for (int i = 0; i < orderedInstanceIds.size(); i++) {
            SubPipelineInstance inst = byId.get(orderedInstanceIds.get(i));
            if (inst == null) {
                throw new ResourceNotFoundException("SubPipelineInstance", orderedInstanceIds.get(i));
            }
            inst.setExecutionOrder(i + 1);
        }
        return instanceRepo.saveAll(instances);
    }

    @Transactional
    public SubPipelineInstance updateInstanceParams(String versionId, String instanceId,
                                                     Map<String, Object> params) {
        return updateInstance(versionId, instanceId, params, null, null, null).instance();
    }

    /**
     * Canonical update (ARCH-010). Extracts mirrored {@code storage_backend},
     * {@code lake_layer}, {@code lake_format} keys from {@code params} into
     * the canonical columns when present, but explicit top-level fields win.
     * Omitted top-level fields preserve the persisted value.
     */
    @Transactional
    public UpdateInstanceResult updateInstance(String versionId, String instanceId,
                                               Map<String, Object> params,
                                               String storageBackend,
                                               String lakeLayer,
                                               String lakeFormat) {
        SubPipelineInstance inst = instanceRepo.findById(instanceId)
                .orElseThrow(() -> new ResourceNotFoundException("SubPipelineInstance", instanceId));
        if (!versionId.equals(inst.getVersionId())) {
            throw new IllegalArgumentException("Instance does not belong to this version");
        }
        compat.rejectIfInstanceCompatReadOnly(inst);

        // ARCH-010: resolve canonical fields, strip mirrored params keys, validate.
        BlueprintInstanceConfigurationService.Resolution resolution =
                instanceConfig.resolveForUpdate(inst, storageBackend, lakeLayer, lakeFormat, params);
        instanceConfig.apply(inst, resolution);

        SubPipelineInstance saved = instanceRepo.save(inst);

        // Schema propagation (deterministic rules first, LLM fallback second, params-driven
        // conflict detection) replaces the prior inline inferOutputSchema(...) call.
        tryPropagate(() -> schemaPropagationService.propagateFromInstance(versionId, instanceId));
        SubPipelineInstance refreshed = instanceRepo.findById(instanceId).orElse(saved);
        return new UpdateInstanceResult(refreshed, resolution);
    }

    private void tryPropagate(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.warn("Schema propagation failed (non-fatal): {}", e.getMessage());
        }
    }

    @Transactional
    public PortWiring wirePort(String versionId,
                               String sourceInstanceId, String sourcePortName,
                               String targetInstanceId, String targetPortName) {
        // Validate both instances belong to this version
        SubPipelineInstance source = instanceRepo.findById(sourceInstanceId)
                .orElseThrow(() -> new ResourceNotFoundException("SubPipelineInstance", sourceInstanceId));
        SubPipelineInstance target = instanceRepo.findById(targetInstanceId)
                .orElseThrow(() -> new ResourceNotFoundException("SubPipelineInstance", targetInstanceId));

        if (!versionId.equals(source.getVersionId()) || !versionId.equals(target.getVersionId())) {
            throw new IllegalArgumentException("Instances do not belong to this version");
        }

        // ARCH-014: wire/unwire touching deprecated/deferred instance -> 422.
        compat.rejectIfInstanceCompatReadOnly(source);
        compat.rejectIfInstanceCompatReadOnly(target);

        validatePortName(source.getBlueprintKey(), sourcePortName, true);
        validatePortName(target.getBlueprintKey(), targetPortName, false);

        PortWiring wiring = new PortWiring();
        wiring.setVersionId(versionId);
        wiring.setSourceInstanceId(sourceInstanceId);
        wiring.setSourcePortName(sourcePortName);
        wiring.setTargetInstanceId(targetInstanceId);
        wiring.setTargetPortName(targetPortName);
        PortWiring saved = wiringRepo.save(wiring);
        tryPropagate(() -> schemaPropagationService.propagateFromInstance(versionId, targetInstanceId));
        return saved;
    }

    /**
     * Rename a composition instance by id (the {@code composition.renameInstance}
     * handler target, ADR 0025 / IMPL-ui-composition Phase 4). Port wirings
     * reference instance IDs, not names, so renaming touches only the name.
     */
    @Transactional
    public SubPipelineInstance renameInstance(String versionId, String instanceId, String newName) {
        if (newName == null || newName.isBlank()) {
            throw new IllegalArgumentException("New instance name must not be blank");
        }
        SubPipelineInstance inst = instanceRepo.findById(instanceId)
                .orElseThrow(() -> new ResourceNotFoundException("SubPipelineInstance", instanceId));
        if (!versionId.equals(inst.getVersionId())) {
            throw new IllegalArgumentException("Instance does not belong to this version");
        }
        compat.rejectIfInstanceCompatReadOnly(inst);
        inst.setName(newName);
        return instanceRepo.save(inst);
    }

    /**
     * Resolve a composition instance id from its NAME (the instanceRef) within a
     * version. Used by the {@code composition.*} command handlers (Apply) to bind
     * pre-apply refs → real ids. The most recently created instance with the name
     * wins when duplicates exist (defensive — names should be unique per version).
     */
    public String resolveInstanceIdByName(String versionId, String name) {
        if (name == null) return null;
        List<SubPipelineInstance> instances = instanceRepo.findByVersionIdOrderByExecutionOrderAsc(versionId);
        String resolved = null;
        for (SubPipelineInstance i : instances) {
            if (name.equals(i.getName())) {
                resolved = i.getId();
            }
        }
        return resolved;
    }

    /** Find the wiring id for a (source,sourcePort,target,targetPort) tuple in a version, or null. */
    public String resolveWiringId(String versionId,
                                  String sourceInstanceId, String sourcePortName,
                                  String targetInstanceId, String targetPortName) {
        for (PortWiring w : wiringRepo.findByVersionIdOrderByCreatedAtAsc(versionId)) {
            if (java.util.Objects.equals(w.getSourceInstanceId(), sourceInstanceId)
                    && java.util.Objects.equals(w.getSourcePortName(), sourcePortName)
                    && java.util.Objects.equals(w.getTargetInstanceId(), targetInstanceId)
                    && java.util.Objects.equals(w.getTargetPortName(), targetPortName)) {
                return w.getId();
            }
        }
        return null;
    }

    @Transactional
    public void unwire(String versionId, String wiringId) {
        PortWiring wiring = wiringRepo.findById(wiringId)
                .orElseThrow(() -> new ResourceNotFoundException("PortWiring", wiringId));
        if (!versionId.equals(wiring.getVersionId())) {
            throw new IllegalArgumentException("Wiring does not belong to this version");
        }
        // ARCH-014: reject unwire that touches a deprecated/deferred instance.
        if (wiring.getSourceInstanceId() != null) {
            instanceRepo.findById(wiring.getSourceInstanceId())
                    .ifPresent(compat::rejectIfInstanceCompatReadOnly);
        }
        if (wiring.getTargetInstanceId() != null) {
            instanceRepo.findById(wiring.getTargetInstanceId())
                    .ifPresent(compat::rejectIfInstanceCompatReadOnly);
        }
        String target = wiring.getTargetInstanceId();
        wiringRepo.delete(wiring);
        tryPropagate(() -> schemaPropagationService.propagateFromInstance(versionId, target));
    }

    @Transactional
    public SubPipelineInstance updateInstanceSchema(String versionId, String instanceId,
                                                     Map<String, Object> outputSchema) {
        SubPipelineInstance inst = instanceRepo.findById(instanceId)
                .orElseThrow(() -> new ResourceNotFoundException("SubPipelineInstance", instanceId));
        if (!versionId.equals(inst.getVersionId())) {
            throw new IllegalArgumentException("Instance does not belong to this version");
        }
        compat.rejectIfInstanceCompatReadOnly(inst);
        inst.setOutputSchema(outputSchema);
        SubPipelineInstance saved = instanceRepo.save(inst);
        // Propagation service picks up the manual write on the next downstream pass.
        tryPropagate(() -> schemaPropagationService.propagateFromInstance(versionId, instanceId));
        return saved;
    }

    /**
     * Follows wirings backwards from the given instance to find the upstream
     * instance's outputSchema. If the upstream instance is an ingestion node
     * with dataset_ids in its params, the schema is auto-populated from the
     * dataset's schemaSnapshot.
     */
    public Map<String, Object> getUpstreamSchema(String versionId, String instanceId) {
        SubPipelineInstance inst = instanceRepo.findById(instanceId)
                .orElseThrow(() -> new ResourceNotFoundException("SubPipelineInstance", instanceId));
        if (!versionId.equals(inst.getVersionId())) {
            throw new IllegalArgumentException("Instance does not belong to this version");
        }

        List<PortWiring> incomingWirings = wiringRepo.findByVersionIdAndTargetInstanceId(versionId, instanceId);
        if (incomingWirings.isEmpty()) {
            return Map.of();
        }

        // Return the first upstream instance's outputSchema (primary input)
        PortWiring wiring = incomingWirings.get(0);
        SubPipelineInstance upstream = instanceRepo.findById(wiring.getSourceInstanceId())
                .orElseThrow(() -> new ResourceNotFoundException("SubPipelineInstance", wiring.getSourceInstanceId()));

        // If the upstream already has an outputSchema, return it
        if (upstream.getOutputSchema() != null && !upstream.getOutputSchema().isEmpty()) {
            return upstream.getOutputSchema();
        }

        // Auto-populate from dataset schemaSnapshot for ingestion instances
        return tryResolveSchemaFromDatasets(upstream);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> tryResolveSchemaFromDatasets(SubPipelineInstance instance) {
        if (instance.getParams() == null) return Map.of();

        Object datasetIds = instance.getParams().get("dataset_ids");
        if (!(datasetIds instanceof List<?> ids) || ids.isEmpty()) return Map.of();

        String firstId = ids.get(0).toString();
        return datasetRepo.findById(firstId)
                .map(Dataset::getSchemaSnapshot)
                .orElse(Map.of());
    }

    private void validatePortName(String blueprintKey, String portName, boolean sourcePort) {
        Blueprint blueprint = blueprintRepo.findByBlueprintKey(blueprintKey)
                .orElseThrow(() -> new ResourceNotFoundException("Blueprint", blueprintKey));
        List<Map<String, Object>> ports = sourcePort ? blueprint.getOutputPorts() : blueprint.getInputPorts();

        Set<String> validPorts = new HashSet<>();
        if (ports != null) {
            for (Map<String, Object> port : ports) {
                Object portNameRaw = port.get("name");
                if (portNameRaw != null) {
                    validPorts.add(portNameRaw.toString());
                }
            }
        }

        // ARCH-011 dynamic output port contract for GenericRouter:
        //   dynamic_outputs.naming = slugify(name)_output
        //   dynamic_outputs.defaultPort = default_output when include_default != false
        if ("GenericRouter".equals(blueprintKey) && sourcePort) {
            validPorts.addAll(resolveRouterDynamicOutputs(blueprint));
        }

        if (validPorts.isEmpty()) {
            throw new IllegalArgumentException(
                    (sourcePort ? "Source" : "Target") + " blueprint '" + blueprintKey + "' has no declared "
                            + (sourcePort ? "output" : "input") + " ports");
        }

        if (!validPorts.contains(portName)) {
            throw new IllegalArgumentException(
                    "Invalid " + (sourcePort ? "source" : "target") + " port '" + portName
                            + "' for blueprint '" + blueprintKey + "'. Valid ports: " + validPorts);
        }
    }

    /**
     * Resolves blueprint-defined dynamic output ports against persisted params.
     * For GenericRouter, this is the union of static output ports plus
     * slugify(route.name)_output for each route, plus default_output when
     * include_default != false.
     */
    @SuppressWarnings("unchecked")
    private Set<String> resolveRouterDynamicOutputs(Blueprint blueprint) {
        // The catalog params_schema lists declared params; persisted dynamic
        // ports come from the SubPipelineInstance.params at wire time. Static
        // catalog-level resolution returns the constant default_output port.
        Set<String> ports = new HashSet<>();
        ports.add("default_output");
        return ports;
    }

    private void rejectNonCompositionSurface(Blueprint bp) {
        String surface = bp.getAddSurface();
        if (surface == null || "composition".equalsIgnoreCase(surface)) {
            return;
        }
        String detail;
        if ("orchestration_policy".equalsIgnoreCase(surface)) {
            detail = "Blueprint '" + bp.getBlueprintKey() + "' is an orchestration policy; "
                    + "configure it through the version orchestration panel, not composition.";
        } else {
            detail = "Blueprint '" + bp.getBlueprintKey() + "' is not addable to composition (add_surface="
                    + surface + ").";
        }
        throw new BlueprintNotAddableException(bp.getBlueprintKey(), surface, detail);
    }

    public record CompositionView(
            List<SubPipelineInstance> instances,
            List<PortWiring> wirings
    ) {}

    /** Result of a canonical add (ARCH-010). */
    public record AddInstanceResult(
            SubPipelineInstance instance,
            BlueprintInstanceConfigurationService.Resolution resolution
    ) {}

    /** Result of a canonical update (ARCH-010). */
    public record UpdateInstanceResult(
            SubPipelineInstance instance,
            BlueprintInstanceConfigurationService.Resolution resolution
    ) {}
}
