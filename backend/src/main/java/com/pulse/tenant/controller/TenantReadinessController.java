package com.pulse.tenant.controller;

import com.pulse.git.service.GitHubRepoUrlValidator;
import com.pulse.storage.service.StorageScaffoldService;
import com.pulse.tenant.model.ConsolidatedReadinessVerdict;
import com.pulse.tenant.service.ConsolidatedTenantReadinessService;
import com.pulse.tenant.service.DomainReadinessService;
import com.pulse.tenant.service.GcpRuntimeTopologyService;
import com.pulse.tenant.service.TenantReadinessFoundationService;
import com.pulse.tenant.service.TenantRuntimeReadinessService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Product API surface for tenant platform readiness foundation (PKT-0010)
 * and child-packet readiness categories.
 *
 * <p>PKT-0012 adds the {@code storageScaffold} category via
 * {@link StorageScaffoldService#buildReadinessCategory(String)}.
 *
 * <p>PKT-0013 adds the {@code domainReadiness} category via
 * {@link DomainReadinessService#buildAllDomainReadiness(String)}.
 *
 * <p>PKT-0014 adds the {@code runtimeBinding} and {@code deploymentTarget}
 * categories via {@link TenantRuntimeReadinessService}.
 *
 * <p>PKT-0025 adds the {@code gcpRuntimeTopology} category via
 * {@link GcpRuntimeTopologyService#buildReadinessCategory(String)}.
 *
 * <p>PKT-0015 adds the consolidated readiness verdict via
 * {@link ConsolidatedTenantReadinessService#computeVerdict(String)}.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}")
public class TenantReadinessController {

    private final TenantReadinessFoundationService foundationService;
    private final GitHubRepoUrlValidator gitHubRepoUrlValidator;
    private final StorageScaffoldService storageScaffoldService;
    private final DomainReadinessService domainReadinessService;
    private final TenantRuntimeReadinessService runtimeReadinessService;
    private final GcpRuntimeTopologyService gcpRuntimeTopologyService;
    private final ConsolidatedTenantReadinessService consolidatedReadinessService;

    public TenantReadinessController(TenantReadinessFoundationService foundationService,
                                     GitHubRepoUrlValidator gitHubRepoUrlValidator,
                                     StorageScaffoldService storageScaffoldService,
                                     DomainReadinessService domainReadinessService,
                                     TenantRuntimeReadinessService runtimeReadinessService,
                                     GcpRuntimeTopologyService gcpRuntimeTopologyService,
                                     ConsolidatedTenantReadinessService consolidatedReadinessService) {
        this.foundationService = foundationService;
        this.gitHubRepoUrlValidator = gitHubRepoUrlValidator;
        this.storageScaffoldService = storageScaffoldService;
        this.domainReadinessService = domainReadinessService;
        this.runtimeReadinessService = runtimeReadinessService;
        this.gcpRuntimeTopologyService = gcpRuntimeTopologyService;
        this.consolidatedReadinessService = consolidatedReadinessService;
    }

    /**
     * Foundation readiness readback for a tenant. Returns independent
     * category inputs (tenant identity, GCP config, GCP credential,
     * git repo) without executing child-packet logic.
     */
    @GetMapping("/readiness/foundation")
    public ResponseEntity<Map<String, Object>> getFoundation(@PathVariable String tenantId) {
        return ResponseEntity.ok(foundationService.getFoundation(tenantId));
    }

    /**
     * Readiness readback including child-packet categories.
     * PKT-0012 contributes the storageScaffold category.
     * PKT-0013 contributes the domainReadiness category.
     * PKT-0014 contributes the runtimeBinding and deploymentTarget categories.
     */
    @GetMapping("/readiness")
    public ResponseEntity<Map<String, Object>> getReadiness(@PathVariable String tenantId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tenantId", tenantId);
        result.put("foundation", foundationService.getFoundation(tenantId));
        result.put("storageScaffold", storageScaffoldService.buildReadinessCategory(tenantId));
        result.put("domainReadiness", domainReadinessService.buildAllDomainReadiness(tenantId));
        result.put("runtimeBinding", runtimeReadinessService.buildRuntimeBindingCategory(tenantId));
        result.put("deploymentTarget", runtimeReadinessService.buildDeploymentTargetCategory(tenantId));
        result.put("gcpRuntimeTopology", gcpRuntimeTopologyService.buildReadinessCategory(tenantId));
        return ResponseEntity.ok(result);
    }

    /**
     * PKT-0015: Consolidated readiness verdict aggregating all tenant
     * platform prerequisites into a fail-closed pipeline-development
     * and live-GCP readiness gate.
     *
     * <p>Returns overallStatus, 16 category statuses, structured blockers
     * with codes/messages/evidenceRefs/safeNextActions, create-vs-validate
     * ownership evidence, and redaction guarantees.
     *
     * <p>Package/deploy preflight is NOT invoked for Scenario 0 readiness.
     */
    @GetMapping("/readiness/consolidated")
    public ResponseEntity<ConsolidatedReadinessVerdict> getConsolidatedReadiness(
            @PathVariable String tenantId) {
        return ResponseEntity.ok(consolidatedReadinessService.computeVerdict(tenantId));
    }

    /**
     * PKT-0013: Per-domain readiness readback aggregating Git scaffold
     * and storage scaffold status.
     */
    @GetMapping("/domains/{domainId}/readiness")
    public ResponseEntity<Map<String, Object>> getDomainReadiness(
            @PathVariable String tenantId,
            @PathVariable String domainId) {
        return ResponseEntity.ok(domainReadinessService.buildDomainReadiness(tenantId, domainId));
    }

    /**
     * Validate a GitHub repo URL against Scenario 0 requirements without
     * actually linking it. Useful for preview/dry-run workflows.
     */
    @PostMapping("/git-repo/validate-url")
    public ResponseEntity<Map<String, Object>> validateGitRepoUrl(
            @PathVariable String tenantId,
            @RequestBody ValidateUrlRequest request) {
        if (request == null || request.repoUrl() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "repoUrl is required");
        }
        var result = gitHubRepoUrlValidator.validate(request.repoUrl());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", tenantId);
        body.put("repoUrl", request.repoUrl());
        body.put("valid", result.valid());
        if (!result.valid()) {
            body.put("reason", result.reason());
        }
        return ResponseEntity.ok(body);
    }

    public record ValidateUrlRequest(String repoUrl) {}
}
