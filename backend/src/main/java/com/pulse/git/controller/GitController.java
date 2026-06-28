package com.pulse.git.controller;

import com.pulse.auth.policy.ActorResolverService;
import com.pulse.auth.policy.CallerSurface;
import com.pulse.auth.service.TenantService;
import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.config.TenantConfig.TenantDefinition;
import com.pulse.git.config.GitCloneBaseService;
import com.pulse.git.identity.UserGitIdentity;
import com.pulse.git.identity.UserGitIdentityService;
import com.pulse.git.model.GitRepo;
import com.pulse.git.model.PullRequest;
import com.pulse.git.repository.GitRepoRepository;
import com.pulse.git.repository.PullRequestRepository;
import com.pulse.git.service.GitAuthenticationException;
import com.pulse.git.service.GitIdentityInvalidException;
import com.pulse.git.service.GitOnboardingException;
import com.pulse.git.service.GitIdentityRequiredException;
import com.pulse.git.service.GitRepoAccessDeniedException;
import com.pulse.git.service.GitHubRepoUrlValidator;
import com.pulse.git.service.LocalGitService;
import com.pulse.git.service.RemoteGitService;
import com.pulse.git.service.RepoScaffoldService;
import com.pulse.git.service.ScaffoldPreview;
import com.pulse.git.service.ScaffoldPreviewService;
import com.pulse.git.service.ScaffoldRequest;
import com.pulse.git.service.ScaffoldResult;
import com.pulse.git.service.UnsupportedGitAuthModeException;
import com.pulse.pipeline.model.Pipeline;
import com.pulse.pipeline.repository.PipelineRepository;
import com.pulse.sor.model.Domain;
import com.pulse.sor.repository.DomainRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.util.FileUtils;

@RestController
public class GitController {

    private static final Logger log = LoggerFactory.getLogger(GitController.class);
    private static final String TENANT_SCOPE = "TENANT";
    private static final String LEGACY_SCOPE = "LEGACY";
    // PKT-FINAL-3 (BUG-05): LOCAL tenant-repo onboarding is no longer
    // supported. Every tenant repo must be REMOTE / GITHUB. The local
    // working-clone directory at PULSE_GIT_CLONE_BASE survives — it is
    // runtime infrastructure, not a tenant-config choice.
    private static final String REMOTE_TYPE = "REMOTE";
    private static final String PROVIDER_GITHUB = "GITHUB";

    private final GitRepoRepository repoRepo;
    private final PullRequestRepository prRepo;
    private final PipelineRepository pipelineRepository;
    private final DomainRepository domainRepository;
    private final TenantService tenantService;
    private final LocalGitService localGitService;
    private final RemoteGitService remoteGitService;
    private final RepoScaffoldService repoScaffoldService;
    private final UserGitIdentityService identityService;
    private final ActorResolverService actorResolver;
    private final ScaffoldPreviewService scaffoldPreviewService;
    private final GitHubRepoUrlValidator gitHubRepoUrlValidator;
    /**
     * PKT-FINAL-4 (BUG-36): clone-base is resolved by
     * {@link GitCloneBaseService} with macOS-aware fallback so the
     * controller no longer derives a raw {@code @Value} path that may
     * be unwritable. Path is read from
     * {@link GitCloneBaseService#getResolvedCloneBase()} per request
     * to honor live re-resolution in tests.
     */
    private final GitCloneBaseService gitCloneBaseService;

    public GitController(GitRepoRepository repoRepo,
                         PullRequestRepository prRepo,
                         PipelineRepository pipelineRepository,
                         DomainRepository domainRepository,
                         TenantService tenantService,
                         LocalGitService localGitService,
                         RemoteGitService remoteGitService,
                         RepoScaffoldService repoScaffoldService,
                         UserGitIdentityService identityService,
                         ActorResolverService actorResolver,
                         ScaffoldPreviewService scaffoldPreviewService,
                         GitHubRepoUrlValidator gitHubRepoUrlValidator,
                         GitCloneBaseService gitCloneBaseService) {
        this.repoRepo = repoRepo;
        this.prRepo = prRepo;
        this.pipelineRepository = pipelineRepository;
        this.domainRepository = domainRepository;
        this.tenantService = tenantService;
        this.localGitService = localGitService;
        this.remoteGitService = remoteGitService;
        this.repoScaffoldService = repoScaffoldService;
        this.identityService = identityService;
        this.actorResolver = actorResolver;
        this.scaffoldPreviewService = scaffoldPreviewService;
        this.gitHubRepoUrlValidator = gitHubRepoUrlValidator;
        this.gitCloneBaseService = gitCloneBaseService;
    }

    // =======================================================================
    //  Tenant-scoped endpoints (V82+)
    // =======================================================================

    @PostMapping("/api/v1/tenants/{tenantId}/onboard")
    public ResponseEntity<GitRepo> onboard(@PathVariable String tenantId,
                                           @RequestBody OnboardRequest request) {
        TenantDefinition tenant = tenantService.getTenant(tenantId);
        if (repoRepo.findByTenantIdAndScope(tenantId, TENANT_SCOPE).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Tenant already onboarded with a TENANT-scoped git repo");
        }
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
        }
        // PKT-FINAL-3 (BUG-05): tenant repos are REMOTE/GITHUB only. Accept a
        // null repoType (frontend may omit it now) but reject any explicit
        // value other than "REMOTE" so callers get a deterministic 400 rather
        // than silently downgrading.
        String requestedType = request.repoType() == null ? REMOTE_TYPE : request.repoType().toUpperCase();
        if (!REMOTE_TYPE.equals(requestedType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "repoType must be REMOTE (LOCAL repos are no longer supported)");
        }

        String tenantSlug = tenant.getSlug() != null ? tenant.getSlug() : tenant.getId();
        // PKT-FINAL-4 (BUG-36): use the GitCloneBaseService-resolved root so
        // the clone target inherits the macOS-aware fallback path.
        String localPath = gitCloneBaseService.getResolvedCloneBase().resolve(tenantSlug).toString();
        String defaultBranch = request.defaultBranch() != null ? request.defaultBranch() : "main";
        var caller = actorResolver.resolve(CallerSurface.UI, tenantId);

        GitRepo repo = new GitRepo();
        repo.setTenantId(tenantId);
        repo.setDomainId(null);
        repo.setPipelineId(null);
        repo.setScope(TENANT_SCOPE);
        repo.setLocalPath(localPath);
        repo.setCurrentBranch(defaultBranch);
        repo.setDefaultBranch(defaultBranch);

        // REMOTE
        if (request.repoUrl() == null || request.repoUrl().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "repoUrl is required");
        }
        String provider = request.provider() == null ? PROVIDER_GITHUB : request.provider();
        validateRemoteProvider(provider);
        // PKT-0010: validate GitHub URL — reject local/non-GitHub paths
        var urlValidation = gitHubRepoUrlValidator.validate(request.repoUrl());
        if (!urlValidation.valid()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, urlValidation.reason());
        }
        UserGitIdentity identity = identityService.requireValidIdentity(caller);
        remoteGitService.verifyRepoAccess(request.repoUrl(), identity);
        repo.setRepoType(REMOTE_TYPE);
        repo.setRepoUrl(request.repoUrl());
        repo.setProvider(provider);
        repo.setMetadata(metadata("onboardedAs", "REMOTE"));

        GitRepo saved = repoRepo.save(repo);
        try {
            remoteGitService.cloneRepo(saved, identity);
        } catch (GitAuthenticationException e) {
            // PKT-FINAL-4 (BUG-42): the prior `delete()` only queued the row
            // for removal in the JPA session — without an active transaction
            // the queued DELETE was dropped when the exception propagated,
            // leaving an orphan `git_repos` row that blocked retries with a
            // 409 "tenant already onboarded". `deleteAndFlush` writes
            // immediately so the row really is gone before the next click.
            cleanupFailedOnboard(saved);
            throw e;
        } catch (RuntimeException e) {
            cleanupFailedOnboard(saved);
            throw e;
        }

        String effectiveBranch = localGitService.getCurrentBranch(localPath);
        if (effectiveBranch != null) {
            saved.setCurrentBranch(effectiveBranch);
        }
        saved = repoRepo.save(saved);

        repoScaffoldService.scaffold(tenantId, request.scaffold(), caller);
        return ResponseEntity.status(HttpStatus.CREATED).body(repoRepo.save(saved));
    }

    @PostMapping("/api/v1/tenants/{tenantId}/onboard/preview")
    public ResponseEntity<ScaffoldPreview> previewOnboarding(@PathVariable String tenantId,
                                                             @RequestBody OnboardRequest request) {
        tenantService.getTenant(tenantId);
        // PKT-FINAL-3 (BUG-05): default the preview to REMOTE since LOCAL is gone.
        String repoType = request == null || request.repoType() == null
                ? REMOTE_TYPE
                : request.repoType().toUpperCase();
        var caller = actorResolver.resolve(CallerSurface.UI, tenantId);
        return ResponseEntity.ok(scaffoldPreviewService.previewOnboarding(
                tenantId, repoType, request == null ? null : request.defaultBranch(), caller));
    }

    @GetMapping("/api/v1/tenants/{tenantId}/onboarding-status")
    public ResponseEntity<OnboardingStatus> onboardingStatus(@PathVariable String tenantId) {
        tenantService.getTenant(tenantId);
        GitRepo repo = repoRepo.findByTenantIdAndScope(tenantId, TENANT_SCOPE).orElse(null);
        List<Map<String, String>> domains = domainRepository.findByTenantIdOrderByNameAsc(tenantId).stream()
                .map(d -> Map.of("id", d.getId(), "name", d.getName()))
                .toList();
        return ResponseEntity.ok(new OnboardingStatus(repo != null, repo, domains));
    }

    @GetMapping("/api/v1/tenants/{tenantId}/scaffold/preview")
    public ResponseEntity<ScaffoldPreview> scaffoldPreview(@PathVariable String tenantId) {
        var caller = actorResolver.resolve(CallerSurface.UI, tenantId);
        return ResponseEntity.ok(scaffoldPreviewService.previewExisting(tenantId, caller));
    }

    @PostMapping("/api/v1/tenants/{tenantId}/scaffold")
    public ResponseEntity<ScaffoldResult> rescaffold(@PathVariable String tenantId,
                                                     @RequestBody(required = false) ScaffoldRequest request) {
        var caller = actorResolver.resolve(CallerSurface.UI, tenantId);
        return ResponseEntity.ok(repoScaffoldService.scaffold(tenantId, request, caller));
    }

    @PutMapping("/api/v1/tenants/{tenantId}/git-repo")
    public ResponseEntity<GitRepo> updateTenantRepo(@PathVariable String tenantId,
                                                    @RequestBody UpdateRepoRequest request) {
        GitRepo repo = repoRepo.findByTenantIdAndScope(tenantId, TENANT_SCOPE)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant git repo", tenantId));

        if (request.fieldProvided(UpdateRepoRequest.Field.REPO_URL)) {
            // PKT-FINAL-3 (BUG-05): LOCAL repos no longer exist; the prior
            // LOCAL-only guard is dead code.
            if (request.repoUrl() == null || request.repoUrl().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "repoUrl cannot be blank");
            }
            // PKT-0010: validate GitHub URL — reject local/non-GitHub paths
            var urlValidation = gitHubRepoUrlValidator.validate(request.repoUrl());
            if (!urlValidation.valid()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        urlValidation.reason());
            }
            UserGitIdentity identity = identityService.requireValidIdentity(
                    actorResolver.resolve(CallerSurface.UI, tenantId));
            remoteGitService.verifyRepoAccess(request.repoUrl(), identity);
            repo.setRepoUrl(request.repoUrl());
            updateOriginUrl(repo.getLocalPath(), request.repoUrl());
        }

        if (request.fieldProvided(UpdateRepoRequest.Field.PROVIDER)) {
            String provider = request.provider();
            validateRemoteProvider(provider);
            repo.setProvider(provider);
        }

        if (request.fieldProvided(UpdateRepoRequest.Field.DEFAULT_BRANCH)) {
            String branch = request.defaultBranch();
            if (branch == null || branch.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "defaultBranch cannot be blank");
            }
            repo.setDefaultBranch(branch);
        }

        return ResponseEntity.ok(repoRepo.save(repo));
    }

    @GetMapping("/api/v1/tenants/{tenantId}/git-repo/branches")
    public ResponseEntity<List<String>> listBranches(@PathVariable String tenantId) {
        GitRepo repo = repoRepo.findByTenantIdAndScope(tenantId, TENANT_SCOPE)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant git repo", tenantId));

        // PKT-FINAL-3 (BUG-05): every tenant repo is REMOTE now, so always
        // pull from the remote before listing locally-known branches.
        UserGitIdentity identity = identityService.requireValidIdentity(
                actorResolver.resolve(CallerSurface.UI, tenantId));
        remoteGitService.pullFromRemote(repo, identity);
        List<String> branches = localGitService.listBranches(repo.getLocalPath());
        if (branches.isEmpty()) {
            branches = List.of(repo.getDefaultBranch() != null ? repo.getDefaultBranch() : "main");
        }
        return ResponseEntity.ok(branches);
    }

    @PutMapping("/api/v1/tenants/{tenantId}/git-repo/branch")
    public ResponseEntity<GitRepo> setCurrentBranch(@PathVariable String tenantId,
                                                    @RequestBody Map<String, String> body) {
        GitRepo repo = repoRepo.findByTenantIdAndScope(tenantId, TENANT_SCOPE)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant git repo", tenantId));
        String branch = body == null ? null : body.get("branch");
        if (branch == null || branch.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "branch is required");
        }
        localGitService.checkoutBranch(repo.getLocalPath(), branch);
        repo.setCurrentBranch(branch);
        return ResponseEntity.ok(repoRepo.save(repo));
    }

    /*
     * PKT-FINAL-4 (BUG-41): the GitAuthenticationException and
     * GitRepoAccessDeniedException @ExceptionHandler methods previously
     * lived here. They moved to GlobalExceptionHandler so the response
     * envelope walks the full cause chain (e.g. surfacing the
     * underlying JGit TransportException message) and logs at ERROR
     * with the full stack. The HTTP contract (502 / 403 + structured
     * envelope with `code` / `type` / `message`) is preserved.
     */

    @ExceptionHandler(GitIdentityRequiredException.class)
    public ResponseEntity<Map<String, String>> handleGitIdentityRequired(GitIdentityRequiredException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("code", "git_identity_required", "message", ex.getMessage()));
    }

    @ExceptionHandler(GitIdentityInvalidException.class)
    public ResponseEntity<Map<String, String>> handleGitIdentityInvalid(GitIdentityInvalidException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("code", "git_identity_invalid", "message", ex.getMessage()));
    }

    @ExceptionHandler(UnsupportedGitAuthModeException.class)
    public ResponseEntity<Map<String, String>> handleUnsupportedGitAuth(UnsupportedGitAuthModeException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("code", "unsupported_git_auth_mode", "message", ex.getMessage()));
    }

    // =======================================================================
    //  Legacy endpoints (kept for backward compatibility with LEGACY repos)
    // =======================================================================

    @PostMapping("/api/v1/domains/{domainId}/git-repo")
    public ResponseEntity<GitRepo> linkRepoToDomain(
            @PathVariable String domainId,
            @RequestBody LinkRepoRequest request) {
        Domain domain = resolveDomain(domainId, request.tenantId());
        var existing = repoRepo.findFirstByDomainIdOrderByCreatedAtDesc(domainId);
        GitRepo repo = existing.orElseGet(GitRepo::new);
        repo.setTenantId(domain.getTenantId());
        repo.setDomainId(domainId);
        repo.setPipelineId(null);
        repo.setProvider(request.provider() != null ? request.provider() : "GITHUB");
        repo.setRepoUrl(request.repoUrl());
        repo.setDefaultBranch(request.defaultBranch() != null ? request.defaultBranch() : "main");
        if (repo.getScope() == null) {
            repo.setScope(LEGACY_SCOPE);
        }
        repo.setMetadata(new HashMap<>(Map.of(
                "scope", "DOMAIN",
                "legacyPipelineShim", false
        )));
        return ResponseEntity.ok(repoRepo.save(repo));
    }

    @GetMapping("/api/v1/domains/{domainId}/git-repo")
    public ResponseEntity<GitRepo> getRepoByDomain(@PathVariable String domainId) {
        return ResponseEntity.ok(repoRepo.findFirstByDomainIdOrderByCreatedAtDesc(domainId)
                .orElseThrow(() -> new ResourceNotFoundException("GitRepo for domain", domainId)));
    }

    @PostMapping("/api/v1/pipelines/{pipelineId}/git-repo")
    public ResponseEntity<GitRepo> linkRepo(
            @PathVariable String pipelineId,
            @RequestBody LinkRepoRequest request) {
        Pipeline pipeline = resolvePipeline(pipelineId);
        String domainId = resolvePipelineDomainId(pipeline);
        if (domainId == null || domainId.isBlank()) {
            var existing = repoRepo.findByPipelineId(pipelineId);
            GitRepo repo = existing.orElseGet(GitRepo::new);
            repo.setTenantId(pipeline.getTenantId());
            repo.setPipelineId(pipelineId);
            repo.setProvider(request.provider() != null ? request.provider() : "GITHUB");
            repo.setRepoUrl(request.repoUrl());
            repo.setDefaultBranch(request.defaultBranch() != null ? request.defaultBranch() : "main");
            if (repo.getScope() == null) {
                repo.setScope(LEGACY_SCOPE);
            }
            repo.setMetadata(new HashMap<>(Map.of(
                    "scope", "PIPELINE",
                    "legacyPipelineShim", true
            )));
            return ResponseEntity.ok(repoRepo.save(repo));
        }

        GitRepo repo = repoRepo.findFirstByDomainIdOrderByCreatedAtDesc(domainId).orElseGet(GitRepo::new);
        repo.setTenantId(pipeline.getTenantId());
        repo.setDomainId(domainId);
        repo.setPipelineId(null);
        repo.setProvider(request.provider() != null ? request.provider() : "GITHUB");
        repo.setRepoUrl(request.repoUrl());
        repo.setDefaultBranch(request.defaultBranch() != null ? request.defaultBranch() : "main");
        if (repo.getScope() == null) {
            repo.setScope(LEGACY_SCOPE);
        }
        repo.setMetadata(new HashMap<>(Map.of(
                "scope", "DOMAIN",
                "legacyPipelineShim", false,
                "resolvedFromPipelineId", pipelineId
        )));
        return ResponseEntity.ok(repoRepo.save(repo));
    }

    @GetMapping("/api/v1/pipelines/{pipelineId}/git-repo")
    public ResponseEntity<GitRepo> getRepo(@PathVariable String pipelineId) {
        Pipeline pipeline = resolvePipeline(pipelineId);
        String resolvedDomainId = resolvePipelineDomainId(pipeline);
        if (resolvedDomainId != null && !resolvedDomainId.isBlank()) {
            return ResponseEntity.ok(repoRepo.findFirstByDomainIdOrderByCreatedAtDesc(resolvedDomainId)
                    .orElseThrow(() -> new ResourceNotFoundException("GitRepo for domain", resolvedDomainId)));
        }
        return ResponseEntity.ok(repoRepo.findByPipelineId(pipelineId)
                .orElseThrow(() -> new ResourceNotFoundException("GitRepo for pipeline", pipelineId)));
    }

    @GetMapping("/api/v1/git-repos/{repoId}/pull-requests")
    public ResponseEntity<List<PullRequest>> listPRs(@PathVariable String repoId) {
        return ResponseEntity.ok(prRepo.findByGitRepoIdOrderByCreatedAtDesc(repoId));
    }

    @GetMapping("/api/v1/versions/{versionId}/pull-requests")
    public ResponseEntity<List<PullRequest>> listPRsByVersion(@PathVariable String versionId) {
        return ResponseEntity.ok(prRepo.findByVersionIdOrderByCreatedAtDesc(versionId));
    }

    @PostMapping("/api/v1/git-repos/{repoId}/pull-requests")
    public ResponseEntity<PullRequest> createPR(
            @PathVariable String repoId,
            @RequestBody CreatePRRequest request) {
        PullRequest pr = new PullRequest();
        pr.setGitRepoId(repoId);
        pr.setGenerationRunId(request.generationRunId());
        pr.setVersionId(request.versionId());
        pr.setPrNumber(request.prNumber());
        pr.setTitle(request.title());
        pr.setSourceBranch(request.sourceBranch());
        pr.setTargetBranch(request.targetBranch() != null ? request.targetBranch() : "main");
        pr.setPrUrl(request.prUrl());
        pr.setMetadata(new HashMap<>());
        return ResponseEntity.ok(prRepo.save(pr));
    }

    @PutMapping("/api/v1/pull-requests/{prId}/status")
    public ResponseEntity<PullRequest> updatePRStatus(
            @PathVariable String prId,
            @RequestBody Map<String, String> body) {
        PullRequest pr = prRepo.findById(prId)
                .orElseThrow(() -> new ResourceNotFoundException("PullRequest", prId));
        pr.setStatus(body.get("status"));
        if ("MERGED".equals(body.get("status")) && body.get("mergeCommitSha") != null) {
            pr.setMergeCommitSha(body.get("mergeCommitSha"));
            pr.setMergedAt(java.time.Instant.now());
        }
        if ("CLOSED".equals(body.get("status"))) {
            pr.setClosedAt(java.time.Instant.now());
        }
        return ResponseEntity.ok(prRepo.save(pr));
    }

    // =======================================================================
    //  Helpers
    // =======================================================================

    private Pipeline resolvePipeline(String pipelineId) {
        return pipelineRepository.findById(pipelineId)
                .orElseThrow(() -> new ResourceNotFoundException("Pipeline", pipelineId));
    }

    private Domain resolveDomain(String domainId, String tenantId) {
        return domainRepository.findById(domainId)
                .filter(domain -> tenantId.equals(domain.getTenantId()))
                .orElseThrow(() -> new ResourceNotFoundException("Domain", domainId));
    }

    private String resolvePipelineDomainId(Pipeline pipeline) {
        if (pipeline.getDomainId() != null && !pipeline.getDomainId().isBlank()) {
            return pipeline.getDomainId();
        }
        return null;
    }

    private Map<String, Object> metadata(String key, String value) {
        Map<String, Object> md = new HashMap<>();
        md.put(key, value);
        return md;
    }

    private void validateRemoteProvider(String provider) {
        if (!"GITHUB".equals(provider)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "provider must be GITHUB");
        }
    }

    /**
     * PKT-FINAL-4 (BUG-42 + BUG-40): rolls back persisted state when
     * {@code onboard} fails after the {@code git_repos} row was saved.
     * Calls {@code delete} + {@code flush} so the DELETE is written
     * immediately — without the flush, the prior implementation's
     * queued delete was dropped when the exception propagated, leaving
     * an orphan row that blocked retries with a 409. The working tree
     * cleanup is best-effort — failures are logged but never suppress
     * the original onboarding exception.
     */
    private void cleanupFailedOnboard(GitRepo saved) {
        try {
            repoRepo.delete(saved);
            repoRepo.flush();
        } catch (RuntimeException dbErr) {
            log.warn("Failed to delete-and-flush git_repos row {} during onboarding cleanup: {}",
                    saved.getId(), dbErr.getMessage());
        }
        String path = saved.getLocalPath();
        if (path == null || path.isBlank()) {
            return;
        }
        File workingTree = new File(path);
        if (!workingTree.exists()) {
            return;
        }
        try {
            FileUtils.delete(workingTree, FileUtils.RECURSIVE | FileUtils.IGNORE_ERRORS);
        } catch (IOException fsErr) {
            log.warn("Failed to clean clone dir {} during onboarding cleanup: {}",
                    path, fsErr.getMessage());
        }
    }

    private void updateOriginUrl(String localPath, String newUrl) {
        try (var git = org.eclipse.jgit.api.Git.open(new File(localPath))) {
            git.remoteSetUrl()
                    .setRemoteName("origin")
                    .setRemoteUri(new org.eclipse.jgit.transport.URIish(newUrl))
                    .call();
        } catch (Exception e) {
            log.warn("Failed to update origin URL at {}: {}", localPath, e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    //  Request / response payloads
    // -----------------------------------------------------------------------

    record LinkRepoRequest(String tenantId, String provider, String repoUrl, String defaultBranch) {}
    record CreatePRRequest(String generationRunId, String versionId, int prNumber,
                           String title, String sourceBranch, String targetBranch, String prUrl) {}

    record OnboardRequest(
            String repoType,
            String repoUrl,
            String provider,
            String defaultBranch,
            ScaffoldRequest scaffold) {}

    record OnboardingStatus(
            boolean onboarded,
            GitRepo gitRepo,
            List<Map<String, String>> domains) {}

    /**
     * Tri-state request: a field is "provided" only when its setter was
     * called by Jackson. This lets the PUT endpoint distinguish explicit
     * {@code null} (clear credential) from "leave untouched".
     */
    public static final class UpdateRepoRequest {
        enum Field { REPO_URL, PROVIDER, DEFAULT_BRANCH }

        private final java.util.EnumSet<Field> provided = java.util.EnumSet.noneOf(Field.class);
        private String repoUrl;
        private String provider;
        private String defaultBranch;

        public String getRepoUrl() { return repoUrl; }
        public void setRepoUrl(String repoUrl) {
            this.repoUrl = repoUrl;
            this.provided.add(Field.REPO_URL);
        }
        public String repoUrl() { return repoUrl; }

        public String getProvider() { return provider; }
        public void setProvider(String provider) {
            this.provider = provider;
            this.provided.add(Field.PROVIDER);
        }
        public String provider() { return provider; }

        public String getDefaultBranch() { return defaultBranch; }
        public void setDefaultBranch(String defaultBranch) {
            this.defaultBranch = defaultBranch;
            this.provided.add(Field.DEFAULT_BRANCH);
        }
        public String defaultBranch() { return defaultBranch; }

        public boolean fieldProvided(Field field) { return provided.contains(field); }
    }
}
