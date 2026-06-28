package com.pulse.tenant.service;

import com.pulse.deploy.model.DeploymentTarget;
import com.pulse.deploy.repository.DeploymentTargetRepository;
import com.pulse.runtime.model.RuntimeBinding;
import com.pulse.runtime.repository.RuntimeBindingRepository;
import com.pulse.runtime.service.RuntimeAuthorityService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PKT-0014 + PKT-FINAL-5 / BUG-39: Aggregates deployment runtime and
 * (still-per-tenant) deployment-target readiness categories.
 *
 * <p>Two categories:
 * <ul>
 *   <li>{@code runtimeBinding} — deployment-global readiness: are active
 *       PRIMARY bindings configured for the deployment? Per-tenant calls
 *       still pass a {@code tenantId} for compatibility, but the lookup
 *       ignores it.</li>
 *   <li>{@code deploymentTarget} — per-tenant: are enabled targets present
 *       with persona-legal target types?</li>
 * </ul>
 *
 * <p>Design rules:
 * <ul>
 *   <li>DIAGNOSTIC bindings are excluded from readiness — only PRIMARY/ACTIVE
 *       bindings count.</li>
 *   <li>STUB-validated bindings cannot claim live-GCP readiness even when
 *       VALIDATED. The {@code gcpLiveReady} flag is only true when
 *       validationKind = LIVE_GCP and validationStatus = VALIDATED.</li>
 *   <li>Deployment targets with target types illegal under the active persona
 *       surface as blockers.</li>
 * </ul>
 */
@Service
public class TenantRuntimeReadinessService {

    private final RuntimeBindingRepository bindingRepository;
    private final DeploymentTargetRepository deploymentTargetRepository;
    private final RuntimeAuthorityService runtimeAuthorityService;

    public TenantRuntimeReadinessService(RuntimeBindingRepository bindingRepository,
                                          DeploymentTargetRepository deploymentTargetRepository,
                                          RuntimeAuthorityService runtimeAuthorityService) {
        this.bindingRepository = bindingRepository;
        this.deploymentTargetRepository = deploymentTargetRepository;
        this.runtimeAuthorityService = runtimeAuthorityService;
    }

    /**
     * PKT-FINAL-5 / BUG-39: build the deployment-global runtime binding
     * readiness category. The {@code tenantId} parameter is preserved for
     * signature compatibility with consumers, but the lookup is global
     * (every tenant in the deployment sees the same binding set).
     */
    public Map<String, Object> buildRuntimeBindingCategory(String tenantId) {
        Map<String, Object> category = new LinkedHashMap<>();
        // BUG-39: query global bindings, not per-tenant.
        List<RuntimeBinding> allBindings = bindingRepository.findAllByOrderByEnvironmentAsc();

        // Filter to only PRIMARY + ACTIVE bindings for readiness assessment
        List<RuntimeBinding> primaryActive = allBindings.stream()
                .filter(RuntimeBinding::isPrimary)
                .filter(RuntimeBinding::isActive)
                .toList();

        List<Map<String, Object>> bindingSummaries = new ArrayList<>();
        List<String> blockers = new ArrayList<>();
        boolean anyValidated = false;
        boolean gcpLiveReady = false;

        for (RuntimeBinding b : primaryActive) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("id", b.getId());
            summary.put("environment", b.getEnvironment());
            summary.put("bindingKind", b.getBindingKind());
            summary.put("validationStatus", b.getValidationStatus());
            summary.put("validationKind", b.getValidationKind());
            summary.put("hasCompleteRoots", b.hasCompleteRoots());
            bindingSummaries.add(summary);

            if (b.isValidated()) {
                anyValidated = true;
                // Only live-GCP validation can claim GCP readiness
                if ("LIVE_GCP".equals(b.getValidationKind())) {
                    gcpLiveReady = true;
                }
            }

            if (!b.hasCompleteRoots()) {
                blockers.add("Binding " + b.getEnvironment()
                        + " has incomplete storage roots");
            }
            if ("PENDING".equals(b.getValidationStatus())) {
                blockers.add("Binding " + b.getEnvironment()
                        + " has not been validated");
            }
            if ("FAILED".equals(b.getValidationStatus())) {
                blockers.add("Binding " + b.getEnvironment()
                        + " validation failed: "
                        + (b.getValidationError() != null ? b.getValidationError() : "unknown"));
            }
        }

        if (primaryActive.isEmpty()) {
            // BUG-39: message reflects deployment scope, not tenant scope.
            blockers.add("No active PRIMARY runtime bindings for deployment");
        }

        String status;
        if (primaryActive.isEmpty()) {
            status = "not_configured";
        } else if (!blockers.isEmpty()) {
            status = "incomplete";
        } else {
            status = "ready";
        }

        category.put("scope", "deployment-global");
        category.put("status", status);
        category.put("activePrimaryCount", primaryActive.size());
        category.put("anyValidated", anyValidated);
        category.put("gcpLiveReady", gcpLiveReady);
        category.put("bindings", bindingSummaries);
        if (!blockers.isEmpty()) {
            category.put("blockers", blockers);
        }
        return category;
    }

    /**
     * Build the deploymentTarget readiness category for a tenant.
     * Deployment targets remain per-tenant.
     */
    public Map<String, Object> buildDeploymentTargetCategory(String tenantId) {
        Map<String, Object> category = new LinkedHashMap<>();
        List<DeploymentTarget> allTargets = deploymentTargetRepository
                .findByTenantIdOrderByEnvironmentAsc(tenantId);
        List<DeploymentTarget> enabledTargets = allTargets.stream()
                .filter(DeploymentTarget::isEnabled)
                .toList();

        List<Map<String, Object>> targetSummaries = new ArrayList<>();
        List<String> blockers = new ArrayList<>();

        for (DeploymentTarget t : enabledTargets) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("id", t.getId());
            summary.put("name", t.getName());
            summary.put("environment", t.getEnvironment());
            summary.put("targetType", t.getTargetType());

            // Check persona legality
            boolean personaLegal = runtimeAuthorityService.isTargetTypeAllowed(t.getTargetType());
            summary.put("personaLegal", personaLegal);
            targetSummaries.add(summary);

            if (!personaLegal) {
                blockers.add("Target '" + t.getName() + "' has targetType '"
                        + t.getTargetType() + "' which is illegal under active persona "
                        + runtimeAuthorityService.getActivePersona());
            }
        }

        if (enabledTargets.isEmpty()) {
            blockers.add("No enabled deployment targets for tenant");
        }

        String status;
        if (enabledTargets.isEmpty()) {
            status = "not_configured";
        } else if (!blockers.isEmpty()) {
            status = "incomplete";
        } else {
            status = "ready";
        }

        category.put("status", status);
        category.put("enabledCount", enabledTargets.size());
        category.put("activePersona", runtimeAuthorityService.getActivePersona().name());
        category.put("targets", targetSummaries);
        if (!blockers.isEmpty()) {
            category.put("blockers", blockers);
        }
        return category;
    }
}
