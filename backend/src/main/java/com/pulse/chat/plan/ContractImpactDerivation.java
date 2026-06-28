package com.pulse.chat.plan;

import com.pulse.deploy.projection.service.RuntimeProjectionService;
import com.pulse.storage.contract.service.StorageAuthorityFacade;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Derives an {@link ContractImpactCode} hint from upstream contract /
 * projection state (ARCH-018). Pure read-side: no mutation; the helper is
 * safe to call from any chat preview tool.
 *
 * <p>The derivation is intentionally conservative: when in doubt the helper
 * returns {@link ContractImpactCode#READINESS_RECHECK_REQUIRED} so the LLM
 * proposes a re-check rather than a no-op.</p>
 */
@Component
public class ContractImpactDerivation {

    private final StorageAuthorityFacade storageAuthorityFacade;
    private final RuntimeProjectionService runtimeProjectionService;

    public ContractImpactDerivation(StorageAuthorityFacade storageAuthorityFacade,
                                    RuntimeProjectionService runtimeProjectionService) {
        this.storageAuthorityFacade = storageAuthorityFacade;
        this.runtimeProjectionService = runtimeProjectionService;
    }

    /**
     * @param versionId          pipeline version under inspection.
     * @param packageId          optional package id for runtime projection drift; null means no projection check.
     * @param targetId           optional deploy target id; null means no projection check.
     * @param environment        deploy environment for projection lookup; null means no projection check.
     */
    public ContractImpactCode derive(String versionId,
                                      String packageId,
                                      String targetId,
                                      String environment) {
        ContractImpactCode tableCode = checkTableContracts(versionId);
        if (tableCode == ContractImpactCode.TABLE_CONTRACT_STALE) {
            return tableCode;
        }

        ContractImpactCode projectionCode = checkRuntimeProjection(packageId, targetId, environment);
        if (projectionCode == ContractImpactCode.RUNTIME_PROJECTION_STALE) {
            return projectionCode;
        }

        if (tableCode != ContractImpactCode.NONE || projectionCode != ContractImpactCode.NONE) {
            return ContractImpactCode.READINESS_RECHECK_REQUIRED;
        }
        return ContractImpactCode.NONE;
    }

    @SuppressWarnings("unchecked")
    private ContractImpactCode checkTableContracts(String versionId) {
        if (versionId == null || versionId.isBlank()) {
            return ContractImpactCode.NONE;
        }
        try {
            Map<String, Object> readiness = storageAuthorityFacade.getContractReadiness(versionId);
            Object ready = readiness.get("ready");
            if (ready instanceof Boolean b && !b) {
                Object blockers = readiness.get("blockers");
                if (blockers instanceof List<?> list && !list.isEmpty()) {
                    return ContractImpactCode.TABLE_CONTRACT_STALE;
                }
                return ContractImpactCode.READINESS_RECHECK_REQUIRED;
            }
        } catch (Exception e) {
            // Defensive: never break callers on derivation failure.
            return ContractImpactCode.READINESS_RECHECK_REQUIRED;
        }
        return ContractImpactCode.NONE;
    }

    private ContractImpactCode checkRuntimeProjection(String packageId,
                                                       String targetId,
                                                       String environment) {
        if (packageId == null || packageId.isBlank()
                || targetId == null || targetId.isBlank()
                || environment == null || environment.isBlank()) {
            return ContractImpactCode.NONE;
        }
        try {
            Optional<com.pulse.deploy.projection.model.RuntimeProjection> projection =
                    runtimeProjectionService.getActiveProjection(packageId, targetId, environment);
            if (projection.isEmpty()) {
                return ContractImpactCode.RUNTIME_PROJECTION_STALE;
            }
            RuntimeProjectionService.ProjectionDriftResult drift =
                    runtimeProjectionService.checkDrift(projection.get().getId());
            if (drift.drifted()) {
                return ContractImpactCode.RUNTIME_PROJECTION_STALE;
            }
        } catch (IllegalArgumentException e) {
            return ContractImpactCode.RUNTIME_PROJECTION_STALE;
        } catch (Exception e) {
            return ContractImpactCode.READINESS_RECHECK_REQUIRED;
        }
        return ContractImpactCode.NONE;
    }
}
