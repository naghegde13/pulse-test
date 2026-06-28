package com.pulse.pipeline.service;

import com.pulse.blueprint.service.DeprecatedBlueprintCompatibilityService;
import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.pipeline.model.SubPipelineInstance;
import com.pulse.pipeline.repository.SubPipelineInstanceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Shared authority for DQ expectations (ARCH-013 parity). Both the panel
 * endpoint (DqController.saveDqExpectations) and the chat tool
 * (apply_dq_expectations) route through this service so they persist to the
 * canonical {@code SubPipelineInstance.dqExpectations} column rather than to
 * the legacy {@code params.dq_expectations} key.
 */
@Service
public class DqExpectationService {

    private final SubPipelineInstanceRepository instanceRepo;
    private final DeprecatedBlueprintCompatibilityService compat;

    public DqExpectationService(SubPipelineInstanceRepository instanceRepo,
                                DeprecatedBlueprintCompatibilityService compat) {
        this.instanceRepo = instanceRepo;
        this.compat = compat;
    }

    /**
     * Persist DQ expectations on the given instance. Validates the instance
     * belongs to the supplied version and rejects deprecated/deferred
     * blueprints (ARCH-014).
     */
    @Transactional
    public SubPipelineInstance save(String versionId,
                                    String instanceId,
                                    List<Map<String, Object>> expectations) {
        SubPipelineInstance instance = instanceRepo.findById(instanceId)
                .orElseThrow(() -> new ResourceNotFoundException("SubPipelineInstance", instanceId));
        if (instance.getVersionId() == null || !instance.getVersionId().equals(versionId)) {
            throw new IllegalArgumentException(
                    "Instance " + instanceId + " does not belong to version " + versionId);
        }
        compat.rejectIfInstanceCompatReadOnly(instance);
        instance.setDqExpectations(expectations == null ? new ArrayList<>() : List.copyOf(expectations));
        return instanceRepo.save(instance);
    }

    /**
     * Read-only accessor used by chat read tools and panel re-fetch flows.
     */
    public List<Map<String, Object>> get(String instanceId) {
        return instanceRepo.findById(instanceId)
                .map(SubPipelineInstance::getDqExpectations)
                .orElseThrow(() -> new ResourceNotFoundException("SubPipelineInstance", instanceId));
    }
}
