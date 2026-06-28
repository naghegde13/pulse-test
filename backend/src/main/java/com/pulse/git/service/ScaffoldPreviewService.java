package com.pulse.git.service;

import com.pulse.auth.policy.CallerContext;
import com.pulse.git.identity.UserGitIdentity;
import com.pulse.git.identity.UserGitIdentityService;
import com.pulse.git.model.GitRepo;
import com.pulse.git.model.TenantRepoScaffoldItem;
import com.pulse.git.repository.GitRepoRepository;
import com.pulse.git.repository.TenantRepoScaffoldItemRepository;
import com.pulse.sor.model.Domain;
import com.pulse.sor.repository.DomainRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ScaffoldPreviewService {

    private static final String TENANT_SCOPE = "TENANT";
    private static final String REMOTE_TYPE = "REMOTE";

    private final GitRepoRepository gitRepoRepository;
    private final DomainRepository domainRepository;
    private final TenantRepoScaffoldItemRepository scaffoldItemRepository;
    private final UserGitIdentityService identityService;

    public ScaffoldPreviewService(GitRepoRepository gitRepoRepository,
                                  DomainRepository domainRepository,
                                  TenantRepoScaffoldItemRepository scaffoldItemRepository,
                                  UserGitIdentityService identityService) {
        this.gitRepoRepository = gitRepoRepository;
        this.domainRepository = domainRepository;
        this.scaffoldItemRepository = scaffoldItemRepository;
        this.identityService = identityService;
    }

    public ScaffoldPreview previewExisting(String tenantId, CallerContext caller) {
        GitRepo repo = gitRepoRepository.findByTenantIdAndScope(tenantId, TENANT_SCOPE)
                .orElseThrow(() -> new com.pulse.common.exception.ResourceNotFoundException("Tenant git repo", tenantId));
        return preview(tenantId, repo.getRepoType(), branchName(repo), repo.getId(), caller);
    }

    public ScaffoldPreview previewOnboarding(String tenantId,
                                             String repoType,
                                             String defaultBranch,
                                             CallerContext caller) {
        return preview(tenantId, repoType, defaultBranch == null || defaultBranch.isBlank() ? "main" : defaultBranch,
                null, caller);
    }

    private ScaffoldPreview preview(String tenantId,
                                    String repoType,
                                    String branchName,
                                    String gitRepoId,
                                    CallerContext caller) {
        GitIdentityReadiness readiness = identityReadiness(repoType, caller);
        Map<String, TenantRepoScaffoldItem> byDomain = gitRepoId == null
                ? Map.of()
                : scaffoldItemRepository.findByGitRepoIdAndBranchNameOrderByItemTypeAscDomainSlugAsc(gitRepoId, branchName)
                .stream()
                .filter(item -> item.getDomainId() != null)
                .collect(Collectors.toMap(TenantRepoScaffoldItem::getDomainId, Function.identity(), (a, b) -> a));
        boolean topLevelMissing = gitRepoId == null || scaffoldItemRepository
                .findByGitRepoIdAndBranchNameAndItemTypeAndDomainIdIsNull(gitRepoId, branchName, "TOP_LEVEL")
                .map(item -> !"SCAFFOLDED".equals(item.getStatus()))
                .orElse(true);
        List<ScaffoldDomainPreview> domains = domainRepository.findByTenantIdOrderByNameAsc(tenantId)
                .stream()
                .map(domain -> domainPreview(domain, byDomain.get(domain.getId())))
                .toList();
        return new ScaffoldPreview(
                tenantId,
                repoType == null ? "LOCAL" : repoType,
                branchName,
                readiness,
                topLevelMissing,
                RepoScaffoldService.topLevelPaths(),
                domains);
    }

    private GitIdentityReadiness identityReadiness(String repoType, CallerContext caller) {
        if (!REMOTE_TYPE.equals(repoType)) {
            return new GitIdentityReadiness(false, true, "not_required",
                    "Local repositories do not require a GitHub identity.", null, null);
        }
        try {
            UserGitIdentity identity = identityService.requireValidIdentity(caller);
            return new GitIdentityReadiness(true, true, "ready", "GitHub identity is ready.",
                    identity.getAuthorName(), identity.getAuthorEmail());
        } catch (GitIdentityRequiredException e) {
            return new GitIdentityReadiness(true, false, "git_identity_required",
                    e.getMessage(), null, null);
        } catch (GitIdentityInvalidException e) {
            return new GitIdentityReadiness(true, false, "git_identity_invalid",
                    e.getMessage(), null, null);
        }
    }

    private ScaffoldDomainPreview domainPreview(Domain domain, TenantRepoScaffoldItem item) {
        String slug = RepoScaffoldService.slugFor(domain);
        String status = Optional.ofNullable(item)
                .map(TenantRepoScaffoldItem::getStatus)
                .orElse("MISSING");
        return new ScaffoldDomainPreview(
                domain.getId(),
                domain.getName(),
                slug,
                status,
                RepoScaffoldService.domainPaths(slug));
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
