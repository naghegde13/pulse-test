package com.pulse.git;

import com.pulse.auth.policy.ActorResolverService;
import com.pulse.auth.policy.AuthorizationPolicyService;
import com.pulse.auth.policy.CallerContext;
import com.pulse.auth.policy.CallerSurface;
import com.pulse.auth.policy.PulseRole;
import com.pulse.codegen.model.GeneratedArtifact;
import com.pulse.codegen.repository.GeneratedArtifactRepository;
import com.pulse.git.identity.UserGitIdentityService;
import com.pulse.git.model.GitRepo;
import com.pulse.git.repository.GitRepoRepository;
import com.pulse.git.service.GitCommitAuthorizationException;
import com.pulse.git.service.GitCommitService;
import com.pulse.git.service.LocalGitService;
import com.pulse.git.service.RemoteGitService;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * Phase 3 enforcement contract — {@link GitCommitService#commitGeneratedCode}
 * gates on {@code PulseAction.COMMIT} and stamps the resolved actor's
 * name + email on the resulting JGit commit (user-attributed path),
 * leaving system commits via {@code RepoScaffoldService} on the
 * {@code PULSE System} identity.
 */
class GitCommitGeneratedCodeAuthorizationTest {

    private GitRepoRepository gitRepoRepository;
    private GeneratedArtifactRepository artifactRepository;
    private RemoteGitService remoteGitService;
    private LocalGitService localGitService;
    private AuthorizationPolicyService policy;
    private ActorResolverService actorResolver;
    private UserGitIdentityService identityService;
    private GitCommitService service;

    @TempDir Path tempDir;

    @BeforeEach
    void setUp() {
        gitRepoRepository = mock(GitRepoRepository.class);
        artifactRepository = mock(GeneratedArtifactRepository.class);
        remoteGitService = mock(RemoteGitService.class);
        localGitService = new LocalGitService();
        policy = new AuthorizationPolicyService();
        actorResolver = spy(new ActorResolverService());
        identityService = mock(UserGitIdentityService.class);
        service = new GitCommitService(
                gitRepoRepository, artifactRepository,
                localGitService, remoteGitService,
                policy, actorResolver, identityService);
    }

    @Test
    @DisplayName("commitGeneratedCode denied for TENANT_USER actor → throws GitCommitAuthorizationException")
    void commitGeneratedCodeDeniedForTenantUser() {
        when(actorResolver.resolve(CallerSurface.AGENT, "tenant-A")).thenReturn(
                new CallerContext("user-test", "tenant-A",
                        Set.of(PulseRole.TENANT_USER), CallerSurface.AGENT));

        GitCommitAuthorizationException denied = assertThrows(
                GitCommitAuthorizationException.class,
                () -> service.commitGeneratedCode("tenant-A", "run-1"));
        assertEquals("Git commit authorization denied: missing_role", denied.getMessage());
    }

    @Test
    @DisplayName("commitGeneratedCode allowed for PIPELINE_DEVELOPER stamps actor identity via commitAsUser")
    void commitGeneratedCodeStampsActorIdentity() throws Exception {
        Path repoPath = tempDir.resolve("tenant-repo");
        Files.createDirectories(repoPath);
        localGitService.initRepo(repoPath.toString(), "main");
        Files.writeString(repoPath.resolve("seed.txt"), "seed\n");
        localGitService.commitAll(repoPath.toString(), "seed");

        GitRepo repo = new GitRepo();
        repo.setId("git-A");
        repo.setTenantId("tenant-A");
        repo.setScope("TENANT");
        repo.setRepoType("LOCAL");
        repo.setLocalPath(repoPath.toString());
        when(gitRepoRepository.findByTenantIdAndScope("tenant-A", "TENANT"))
                .thenReturn(Optional.of(repo));

        GeneratedArtifact artifact = new GeneratedArtifact();
        artifact.setFilePath("dags/example.py");
        artifact.setFileType("AIRFLOW_DAG");
        artifact.setContent("from airflow import DAG\n");
        when(artifactRepository.findByGenerationRunIdOrderByFilePathAsc("run-1"))
                .thenReturn(List.of(artifact));

        when(actorResolver.resolve(CallerSurface.AGENT, "tenant-A")).thenReturn(
                new CallerContext("user-test", "tenant-A",
                        Set.of(PulseRole.PIPELINE_DEVELOPER), CallerSurface.AGENT));
        // Without an HTTP request the resolver returns the dev-stub
        // author identity (Dev Builder / builder@pulse.dev). That's the
        // expected behavior in this no-context test.

        service.commitGeneratedCode("tenant-A", "run-1");

        try (Git git = Git.open(new File(repoPath.toString()))) {
            RevCommit head = git.log().setMaxCount(1).call().iterator().next();
            assertNotNull(head);
            // User-attributed path was used: the dev-stub author shows up,
            // NOT the legacy "PULSE System" identity that commitAll uses.
            assertEquals(ActorResolverService.DEV_STUB_AUTHOR_NAME,
                    head.getAuthorIdent().getName(),
                    "commitGeneratedCode must use commitAsUser, not commitAll");
            assertEquals(ActorResolverService.DEV_STUB_AUTHOR_EMAIL,
                    head.getAuthorIdent().getEmailAddress());
        }
    }

    @Test
    @DisplayName("System scaffold path on commitAll still stamps PULSE System (unchanged)")
    void scaffoldStillUsesSystemIdentity() throws Exception {
        // Sanity: commitAll's system identity is the audit signal that
        // distinguishes scaffold/maintenance from user-attributed commits.
        // Phase 3 explicitly leaves this alone.
        Path repoPath = tempDir.resolve("scaffold-repo");
        Files.createDirectories(repoPath);
        localGitService.initRepo(repoPath.toString(), "main");
        Files.writeString(repoPath.resolve("scaffold.txt"), "scaffold\n");
        localGitService.commitAll(repoPath.toString(), "pulse: scaffold");

        try (Git git = Git.open(new File(repoPath.toString()))) {
            RevCommit head = git.log().setMaxCount(1).call().iterator().next();
            assertEquals(LocalGitService.SYSTEM_AUTHOR_NAME, head.getAuthorIdent().getName());
            assertEquals(LocalGitService.SYSTEM_AUTHOR_EMAIL, head.getAuthorIdent().getEmailAddress());
        }
    }
}
