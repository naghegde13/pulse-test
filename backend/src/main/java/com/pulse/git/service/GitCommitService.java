package com.pulse.git.service;

import com.pulse.auth.policy.ActionContext;
import com.pulse.auth.policy.ActorResolverService;
import com.pulse.auth.policy.AuthorizationPolicyService;
import com.pulse.auth.policy.CallerContext;
import com.pulse.auth.policy.CallerSurface;
import com.pulse.auth.policy.PolicyDecision;
import com.pulse.auth.policy.PulseAction;
import com.pulse.git.identity.UserGitIdentity;
import com.pulse.git.identity.UserGitIdentityService;
import com.pulse.codegen.model.GeneratedArtifact;
import com.pulse.codegen.repository.GeneratedArtifactRepository;
import com.pulse.git.model.GitRepo;
import com.pulse.git.repository.GitRepoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Orchestrates writing generated artifacts into the tenant-scoped git repo.
 * Intentionally fault-tolerant:
 *   - No TENANT-scoped repo for the tenant → warn + return (tenant not onboarded).
 *   - Remote push fails on auth → warn + return; local commit stays durable.
 */
@Service
public class GitCommitService {

    private static final Logger log = LoggerFactory.getLogger(GitCommitService.class);
    private static final String TENANT_SCOPE = "TENANT";
    private static final String REMOTE_TYPE = "REMOTE";

    private final GitRepoRepository gitRepoRepository;
    private final GeneratedArtifactRepository artifactRepository;
    private final LocalGitService localGitService;
    private final RemoteGitService remoteGitService;
    private final AuthorizationPolicyService authPolicy;
    private final ActorResolverService actorResolver;
    private final UserGitIdentityService identityService;

    public GitCommitService(GitRepoRepository gitRepoRepository,
                            GeneratedArtifactRepository artifactRepository,
                            LocalGitService localGitService,
                            RemoteGitService remoteGitService,
                            AuthorizationPolicyService authPolicy,
                            ActorResolverService actorResolver,
                            UserGitIdentityService identityService) {
        this.gitRepoRepository = gitRepoRepository;
        this.artifactRepository = artifactRepository;
        this.localGitService = localGitService;
        this.remoteGitService = remoteGitService;
        this.authPolicy = authPolicy;
        this.actorResolver = actorResolver;
        this.identityService = identityService;
    }

    /**
     * Materializes every {@link GeneratedArtifact} for the run under the
     * tenant repo's working tree, commits, and (for REMOTE repos) pushes.
     * Swallows {@link GitAuthenticationException} on push so the local commit
     * remains durable.
     */
    public void commitGeneratedCode(String tenantId, String generationRunId) {
        // Phase 3: code generation produces a user-attributed commit.
        // Gate at the COMMIT action with the AGENT surface (codegen is
        // typically triggered from the chat tool today; UI-driven runs
        // resolve the same way through the request context).
        CallerContext caller = actorResolver.resolve(CallerSurface.AGENT, tenantId);
        PolicyDecision decision = authPolicy.check(caller, PulseAction.COMMIT,
                ActionContext.forTenant(tenantId));
        if (!decision.allowed()) {
            log.warn("commitGeneratedCode denied for tenant {} run {}: {}",
                    tenantId, generationRunId, decision.denyReason());
            throw new GitCommitAuthorizationException(decision.denyReason());
        }
        var repoOpt = gitRepoRepository.findByTenantIdAndScope(tenantId, TENANT_SCOPE);
        if (repoOpt.isEmpty()) {
            log.warn("No TENANT-scoped git repo for tenant {}; skipping commit of run {}",
                    tenantId, generationRunId);
            return;
        }
        GitRepo repo = repoOpt.get();
        UserGitIdentity identity = null;
        if (REMOTE_TYPE.equals(repo.getRepoType())) {
            identity = identityService.requireValidIdentity(caller);
        }
        Path root = Path.of(repo.getLocalPath());

        List<GeneratedArtifact> artifacts = artifactRepository
                .findByGenerationRunIdOrderByFilePathAsc(generationRunId);
        if (artifacts.isEmpty()) {
            log.info("No generated artifacts for run {}; nothing to commit", generationRunId);
            return;
        }

        for (GeneratedArtifact artifact : artifacts) {
            Path target = root.resolve(artifact.getFilePath()).normalize();
            if (!target.startsWith(root)) {
                throw new IllegalStateException("Artifact path escapes repo root: " + artifact.getFilePath());
            }
            try {
                Files.createDirectories(target.getParent());
                Files.writeString(target, artifact.getContent() == null ? "" : artifact.getContent());
            } catch (IOException e) {
                throw new IllegalStateException(
                        "Failed to write artifact " + artifact.getFilePath() + " under " + root, e);
            }
        }

        // Phase 3: this commit is user-initiated (codegen is triggered by
        // an actor — chat or UI), so attribute it to the resolved actor's
        // name/email rather than the legacy "PULSE System" identity.
        // RepoScaffoldService still uses commitAll (system path) for
        // tenant-onboarding / scaffold maintenance commits.
        if (identity != null) {
            localGitService.commitAsUser(repo.getLocalPath(),
                    "pulse: generate run " + generationRunId,
                    identity.getAuthorName(), identity.getAuthorEmail());
        } else {
            ActorResolverService.AuthorIdentity author = actorResolver.resolveAuthorIdentity();
            localGitService.commitAsUser(repo.getLocalPath(),
                    "pulse: generate run " + generationRunId,
                    author.name(), author.email());
        }

        if (REMOTE_TYPE.equals(repo.getRepoType())) {
            try {
                remoteGitService.pushToRemote(repo, identity);
            } catch (GitAuthenticationException e) {
                log.warn("Push skipped for tenant {}: {}", tenantId, e.getMessage());
            }
        }
    }
}
