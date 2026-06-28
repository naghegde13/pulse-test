package com.pulse.blueprint.service;

import com.pulse.blueprint.exception.BlueprintCompatReadOnlyException;
import com.pulse.blueprint.model.Blueprint;
import com.pulse.blueprint.repository.BlueprintRepository;
import com.pulse.pipeline.model.SubPipelineInstance;
import com.pulse.pipeline.repository.SubPipelineInstanceRepository;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Shared read-only compatibility guard for deprecated and deferred blueprints
 * (ARCH-014). Used by composition mutation, compile/package preview, and
 * deploy preflight to enforce a single rule: deprecated/deferred blueprint
 * instances may be read and compiled in compatibility mode but cannot be
 * mutated through generic paths or deployed.
 */
@Service
public class DeprecatedBlueprintCompatibilityService {

    private final BlueprintRepository blueprintRepo;
    private final SubPipelineInstanceRepository instanceRepo;

    public DeprecatedBlueprintCompatibilityService(BlueprintRepository blueprintRepo,
                                                   SubPipelineInstanceRepository instanceRepo) {
        this.blueprintRepo = blueprintRepo;
        this.instanceRepo = instanceRepo;
    }

    /**
     * @return true if the blueprint with the given key is deprecated or deferred.
     *         Returns false when the key is unknown (callers handle unknown keys
     *         through normal {@code ResourceNotFoundException} paths).
     */
    public boolean isCompatReadOnly(String blueprintKey) {
        if (blueprintKey == null) {
            return false;
        }
        return blueprintRepo.findByBlueprintKey(blueprintKey)
                .map(this::isCompatReadOnly)
                .orElse(false);
    }

    public boolean isCompatReadOnly(Blueprint bp) {
        if (bp == null) {
            return false;
        }
        return bp.isDeferred() || "deprecated".equalsIgnoreCase(bp.getStatus());
    }

    /**
     * @return the replacement blueprint key for the given key, or null if there
     *         is no replacement or the key is unknown.
     */
    public String replacementFor(String blueprintKey) {
        if (blueprintKey == null) {
            return null;
        }
        return blueprintRepo.findByBlueprintKey(blueprintKey)
                .map(Blueprint::getReplacementBlueprintKey)
                .orElse(null);
    }

    /**
     * Throws {@link BlueprintCompatReadOnlyException} if the blueprint is in
     * read-only compatibility mode. Used by add-instance paths where the caller
     * already has the blueprint key.
     */
    public void rejectIfCompatReadOnly(String blueprintKey) {
        if (isCompatReadOnly(blueprintKey)) {
            throw new BlueprintCompatReadOnlyException(blueprintKey, replacementFor(blueprintKey));
        }
    }

    /**
     * Throws if the given instance points at a deprecated/deferred blueprint.
     * Used by remove/update/wire/unwire paths after loading the instance.
     */
    public void rejectIfInstanceCompatReadOnly(SubPipelineInstance instance) {
        if (instance == null) {
            return;
        }
        rejectIfCompatReadOnly(instance.getBlueprintKey());
    }

    /**
     * Throws if any of the given instance IDs targets a deprecated/deferred
     * blueprint. Used by reorder operations that touch a set of instances.
     */
    public void rejectIfAnyInstanceCompatReadOnly(Collection<String> instanceIds) {
        if (instanceIds == null || instanceIds.isEmpty()) {
            return;
        }
        Set<String> unique = new LinkedHashSet<>(instanceIds);
        for (String instanceId : unique) {
            Optional<SubPipelineInstance> instOpt = instanceRepo.findById(instanceId);
            instOpt.ifPresent(this::rejectIfInstanceCompatReadOnly);
        }
    }

    /**
     * @return blueprint keys in the version whose instances target
     *         deprecated/deferred blueprints. Used by package readiness and
     *         deploy preflight to emit compatibility warnings/blockers.
     */
    public List<String> readOnlyBlueprintKeysInVersion(String versionId) {
        List<SubPipelineInstance> instances =
                instanceRepo.findByVersionIdOrderByExecutionOrderAsc(versionId);
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (SubPipelineInstance inst : instances) {
            if (isCompatReadOnly(inst.getBlueprintKey())) {
                keys.add(inst.getBlueprintKey());
            }
        }
        return List.copyOf(keys);
    }
}
