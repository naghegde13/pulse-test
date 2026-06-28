package com.pulse.tenant.service;

import com.pulse.auth.model.Tenant;
import com.pulse.auth.service.TenantGcpConfigService;
import com.pulse.auth.service.TenantGcpCredentialService;
import com.pulse.auth.service.TenantService;
import com.pulse.git.model.GitRepo;
import com.pulse.git.repository.GitRepoRepository;
import com.pulse.git.service.GitHubRepoUrlValidator;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Aggregates foundation category inputs for tenant platform readiness
 * (PKT-0010). Provides reusable category readbacks consumed by child
 * packets (PKT-0012 through PKT-0015) without executing child-owned logic.
 *
 * <p>Design rules:
 * <ul>
 *   <li>Tenant-level GCP config is consumed independently of storage-backend
 *       gcpProject. A missing tenant GCP config fails closed even when a
 *       storage backend with gcpProject exists.</li>
 *   <li>No secret values (PAT, private key, credential JSON) are ever
 *       included in readback.</li>
 *   <li>This service does not implement storage scaffold, domain rescaffold,
 *       runtime/deployment readiness, or consolidated verdict — those are
 *       owned by PKT-0012 through PKT-0015.</li>
 * </ul>
 */
@Service
public class TenantReadinessFoundationService {

    private final TenantService tenantService;
    private final TenantGcpConfigService gcpConfigService;
    private final TenantGcpCredentialService gcpCredentialService;
    private final GitRepoRepository gitRepoRepository;
    private final GitHubRepoUrlValidator gitHubRepoUrlValidator;

    public TenantReadinessFoundationService(TenantService tenantService,
                                            TenantGcpConfigService gcpConfigService,
                                            TenantGcpCredentialService gcpCredentialService,
                                            GitRepoRepository gitRepoRepository,
                                            GitHubRepoUrlValidator gitHubRepoUrlValidator) {
        this.tenantService = tenantService;
        this.gcpConfigService = gcpConfigService;
        this.gcpCredentialService = gcpCredentialService;
        this.gitRepoRepository = gitRepoRepository;
        this.gitHubRepoUrlValidator = gitHubRepoUrlValidator;
    }

    /**
     * Build the foundation readiness readback for a tenant. Each category
     * returns its status independently — downstream packets can consume
     * these inputs to determine their own readiness.
     */
    public Map<String, Object> getFoundation(String tenantId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tenantId", tenantId);
        result.put("packet", "PKT-0010");
        result.put("tenantIdentity", buildTenantIdentityCategory(tenantId));
        result.put("gcpConfig", buildGcpConfigCategory(tenantId));
        result.put("gcpCredential", buildGcpCredentialCategory(tenantId));
        result.put("gitRepo", buildGitRepoCategory(tenantId));
        return result;
    }

    private Map<String, Object> buildTenantIdentityCategory(String tenantId) {
        Map<String, Object> category = new LinkedHashMap<>();
        try {
            Tenant tenant = tenantService.getTenantEntity(tenantId);
            category.put("status", "configured");
            category.put("id", tenant.getId());
            category.put("name", tenant.getName());
            category.put("slug", tenant.getSlug());
            category.put("origin", tenant.getOrigin());
        } catch (Exception e) {
            category.put("status", "missing");
            category.put("error", "Tenant not found: " + tenantId);
        }
        return category;
    }

    /**
     * GCP config category — reads from the tenant GCP config table,
     * NOT from storage-backend gcpProject. Missing config always fails
     * closed regardless of what storage backends exist.
     */
    private Map<String, Object> buildGcpConfigCategory(String tenantId) {
        Map<String, Object> category = new LinkedHashMap<>();
        var config = gcpConfigService.getConfig(tenantId);
        if (config.isPresent()) {
            var c = config.get();
            category.put("status", "configured");
            category.put("gcpProjectId", c.getControlPlaneProjectId());
            category.put("gcpRegion", c.getGcpRegion());
            category.put("source", "tenant_gcp_config");
        } else {
            category.put("status", "missing");
            category.put("error", "Tenant GCP config not set. "
                    + "Use PUT /api/v1/tenants/{tenantId}/gcp-config to configure. "
                    + "Storage-backend gcpProject does not substitute for tenant GCP config.");
            category.put("source", "tenant_gcp_config");
        }
        return category;
    }

    /**
     * GCP credential category — redacted readback only. Never includes
     * private key material, encrypted credential, or client_email JSON body.
     */
    /** Allowlist of fields safe to copy from the redacted credential readback. */
    private static final java.util.Set<String> CREDENTIAL_SAFE_FIELDS = java.util.Set.of(
            "status", "serviceAccountEmail", "keyId", "gcpProjectId");

    private Map<String, Object> buildGcpCredentialCategory(String tenantId) {
        Map<String, Object> category = new LinkedHashMap<>();
        var cred = gcpCredentialService.getRedactedCredential(tenantId);
        if (cred.isPresent()) {
            var c = cred.get();
            // Only copy allowlisted fields — defense-in-depth against upstream changes
            for (String key : CREDENTIAL_SAFE_FIELDS) {
                if (c.containsKey(key)) {
                    category.put(key, c.get(key));
                }
            }
            category.put("privateKeyRedacted", true);
            category.put("source", "tenant_gcp_credential");
        } else {
            category.put("status", "missing");
            category.put("error", "No GCP credential configured for tenant.");
            category.put("privateKeyRedacted", true);
            category.put("source", "tenant_gcp_credential");
        }
        return category;
    }

    /**
     * Git repo category — reports the tenant-scoped git repo linkage status
     * including GitHub URL validity and PAT readiness. No secret values are
     * included.
     */
    private Map<String, Object> buildGitRepoCategory(String tenantId) {
        Map<String, Object> category = new LinkedHashMap<>();
        var repo = gitRepoRepository.findByTenantIdAndScope(tenantId, "TENANT");
        if (repo.isPresent()) {
            GitRepo r = repo.get();
            category.put("status", "linked");
            category.put("repoUrl", r.getRepoUrl());
            category.put("repoType", r.getRepoType());
            category.put("provider", r.getProvider());
            category.put("defaultBranch", r.getDefaultBranch());
            // Validate the linked URL against GitHub requirements
            if ("REMOTE".equals(r.getRepoType()) && r.getRepoUrl() != null) {
                var validation = gitHubRepoUrlValidator.validate(r.getRepoUrl());
                category.put("githubUrlValid", validation.valid());
                if (!validation.valid()) {
                    category.put("githubUrlError", validation.reason());
                }
            }
        } else {
            category.put("status", "not_linked");
            category.put("error", "No tenant-scoped git repo linked.");
        }
        return category;
    }
}
