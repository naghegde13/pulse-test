package com.pulse.tenant.service;

import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.git.model.GitRepo;
import com.pulse.git.model.TenantRepoScaffoldItem;
import com.pulse.git.repository.GitRepoRepository;
import com.pulse.git.repository.TenantRepoScaffoldItemRepository;
import com.pulse.git.service.RepoScaffoldService;
import com.pulse.sor.model.Domain;
import com.pulse.sor.repository.DomainRepository;
import com.pulse.storage.model.StorageScaffoldStatus;
import com.pulse.storage.repository.StorageScaffoldStatusRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * PKT-0013: Aggregates Git scaffold and GCS storage scaffold status
 * per domain into a combined readiness verdict for pipeline-development
 * readiness.
 *
 * <p>Design rules:
 * <ul>
 *   <li>A domain is {@code ready} only when both its Git scaffold and
 *       storage scaffold categories are in a terminal state (ready or
 *       acknowledged-blocked).</li>
 *   <li>A domain row alone (no scaffold work) is never ready.</li>
 *   <li>For REMOTE repos with no domain scaffold item, Git is reported
 *       as {@code blocked} (requires identity/PAT to scaffold+push).</li>
 *   <li>Preview data (expected paths) is always available regardless
 *       of readiness state.</li>
 *   <li>No secret material is included in any readback.</li>
 * </ul>
 */
@Service
public class DomainReadinessService {

    private static final String TENANT_SCOPE = "TENANT";
    private static final String STATUS_SCAFFOLDED = "SCAFFOLDED";

    /** Git scaffold statuses considered terminal (ready or acknowledged-blocked). */
    private static final Set<String> GIT_TERMINAL = Set.of("scaffolded", "blocked");

    /** Storage scaffold statuses considered terminal. */
    private static final Set<String> STORAGE_TERMINAL = Set.of("executed", "operator_blocked", "blocked");

    private final DomainRepository domainRepository;
    private final GitRepoRepository gitRepoRepository;
    private final TenantRepoScaffoldItemRepository scaffoldItemRepository;
    private final StorageScaffoldStatusRepository storageScaffoldStatusRepository;

    public DomainReadinessService(DomainRepository domainRepository,
                                  GitRepoRepository gitRepoRepository,
                                  TenantRepoScaffoldItemRepository scaffoldItemRepository,
                                  StorageScaffoldStatusRepository storageScaffoldStatusRepository) {
        this.domainRepository = domainRepository;
        this.gitRepoRepository = gitRepoRepository;
        this.scaffoldItemRepository = scaffoldItemRepository;
        this.storageScaffoldStatusRepository = storageScaffoldStatusRepository;
    }

    /**
     * Build readiness for a single domain identified by domainId.
     *
     * @param tenantId tenant scope
     * @param domainId domain to evaluate
     * @return readiness map with gitScaffold, storageScaffold, and combined ready flag
     */
    public Map<String, Object> buildDomainReadiness(String tenantId, String domainId) {
        Domain domain = domainRepository.findById(domainId)
                .filter(d -> d.getTenantId().equals(tenantId))
                .orElseThrow(() -> new ResourceNotFoundException("Domain", domainId));
        return buildReadinessForDomain(tenantId, domain);
    }

    /**
     * Build readiness for all domains in the tenant. Returns per-domain
     * readiness plus an overall verdict.
     *
     * @param tenantId tenant scope
     * @return map with domains list and overall ready flag
     */
    public Map<String, Object> buildAllDomainReadiness(String tenantId) {
        List<Domain> domains = domainRepository.findByTenantIdOrderByNameAsc(tenantId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tenantId", tenantId);
        result.put("packet", "PKT-0013");

        if (domains.isEmpty()) {
            result.put("ready", false);
            result.put("domainCount", 0);
            result.put("domains", List.of());
            result.put("error", "No domains found for tenant " + tenantId);
            return result;
        }

        List<Map<String, Object>> domainResults = domains.stream()
                .map(d -> buildReadinessForDomain(tenantId, d))
                .toList();

        boolean allReady = domainResults.stream()
                .allMatch(dr -> Boolean.TRUE.equals(dr.get("ready")));

        result.put("ready", allReady);
        result.put("domainCount", domains.size());
        result.put("domains", domainResults);
        return result;
    }

    // -----------------------------------------------------------------------
    //  Internal
    // -----------------------------------------------------------------------

    private Map<String, Object> buildReadinessForDomain(String tenantId, Domain domain) {
        Map<String, Object> gitCategory = buildGitScaffoldCategory(tenantId, domain);
        Map<String, Object> storageCategory = buildStorageScaffoldCategory(tenantId, domain.getSlug());

        String gitStatus = (String) gitCategory.get("status");
        String storageStatus = (String) storageCategory.get("status");
        boolean ready = GIT_TERMINAL.contains(gitStatus) && STORAGE_TERMINAL.contains(storageStatus);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tenantId", tenantId);
        result.put("domainId", domain.getId());
        result.put("domainName", domain.getName());
        result.put("domainSlug", domain.getSlug());
        result.put("ready", ready);
        result.put("gitScaffold", gitCategory);
        result.put("storageScaffold", storageCategory);
        return result;
    }

    /**
     * Git scaffold category for a single domain.
     * <ul>
     *   <li>{@code scaffolded} — scaffold item exists with SCAFFOLDED status</li>
     *   <li>{@code missing} — no scaffold item (LOCAL repo) or no git repo</li>
     *   <li>{@code blocked} — REMOTE repo and domain not yet scaffolded
     *       (requires PAT/identity to scaffold+push)</li>
     *   <li>{@code error} — scaffold item exists with ERROR status</li>
     * </ul>
     */
    private Map<String, Object> buildGitScaffoldCategory(String tenantId, Domain domain) {
        Map<String, Object> category = new LinkedHashMap<>();
        String slug = RepoScaffoldService.slugFor(domain);

        Optional<GitRepo> repoOpt = gitRepoRepository.findByTenantIdAndScope(tenantId, TENANT_SCOPE);
        if (repoOpt.isEmpty()) {
            category.put("status", "missing");
            category.put("reason", "no_git_repo");
            category.put("paths", RepoScaffoldService.domainPaths(slug));
            return category;
        }

        GitRepo repo = repoOpt.get();
        String branch = branchName(repo);
        category.put("repoType", repo.getRepoType());

        Optional<TenantRepoScaffoldItem> itemOpt = scaffoldItemRepository
                .findByGitRepoIdAndBranchNameAndDomainId(repo.getId(), branch, domain.getId());

        if (itemOpt.isPresent()) {
            TenantRepoScaffoldItem item = itemOpt.get();
            if (STATUS_SCAFFOLDED.equals(item.getStatus())) {
                category.put("status", "scaffolded");
                category.put("commitSha", item.getLastCommitSha());
            } else {
                category.put("status", "error");
                category.put("lastError", item.getLastError());
            }
        } else if ("REMOTE".equals(repo.getRepoType())) {
            category.put("status", "blocked");
            category.put("blocker", "remote_identity_required");
            category.put("reason", "REMOTE repo requires validated PAT/identity to scaffold and push. "
                    + "Preview is available without identity.");
        } else {
            category.put("status", "missing");
            category.put("reason", "domain_not_scaffolded");
        }

        category.put("paths", RepoScaffoldService.domainPaths(slug));
        return category;
    }

    /**
     * Storage scaffold category for a single domain.
     * <ul>
     *   <li>{@code executed} — storage scaffold fully executed</li>
     *   <li>{@code operator_blocked} — execution gated (live writes disabled)</li>
     *   <li>{@code previewed} — manifest previewed but not executed</li>
     *   <li>{@code not_scaffolded} — no scaffold status recorded</li>
     * </ul>
     */
    private Map<String, Object> buildStorageScaffoldCategory(String tenantId, String domainSlug) {
        Map<String, Object> category = new LinkedHashMap<>();

        Optional<StorageScaffoldStatus> statusOpt =
                storageScaffoldStatusRepository.findByTenantIdAndDomainSlug(tenantId, domainSlug);

        if (statusOpt.isEmpty()) {
            category.put("status", "not_scaffolded");
            category.put("reason", "No storage scaffold status for domain. "
                    + "Preview via GET /api/v1/tenants/" + tenantId + "/storage-scaffold/preview");
            return category;
        }

        StorageScaffoldStatus status = statusOpt.get();
        category.put("status", status.getStatus());
        category.put("gcpProjectId", status.getGcpProjectId());
        category.put("serviceAccountEmail", status.getServiceAccountEmail());
        category.put("credentialSource", status.getCredentialSource());
        category.put("entryCount", status.getEntryCount());
        if (status.getLastPreviewedAt() != null) {
            category.put("lastPreviewedAt", status.getLastPreviewedAt().toString());
        }
        if (status.getLastExecutedAt() != null) {
            category.put("lastExecutedAt", status.getLastExecutedAt().toString());
        }
        if (status.getExecutionError() != null) {
            category.put("executionError", status.getExecutionError());
        }
        return category;
    }

    private String branchName(GitRepo repo) {
        if (repo.getCurrentBranch() != null && !repo.getCurrentBranch().isBlank()) {
            return repo.getCurrentBranch();
        }
        if (repo.getDefaultBranch() != null && !repo.getDefaultBranch().isBlank()) {
            return repo.getDefaultBranch();
        }
        return "main";
    }
}
