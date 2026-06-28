package com.pulse.git.controller;

import com.pulse.auth.policy.ActorResolverService;
import com.pulse.auth.service.TenantService;
import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.config.TenantConfig;
import com.pulse.git.config.GitCloneBaseService;
import com.pulse.git.identity.UserGitIdentityService;
import com.pulse.git.model.GitRepo;
import com.pulse.git.repository.GitRepoRepository;
import com.pulse.git.repository.PullRequestRepository;
import com.pulse.git.service.GitAuthenticationException;
import com.pulse.git.service.GitHubRepoUrlValidator;
import com.pulse.git.service.LocalGitService;
import com.pulse.git.service.RemoteGitService;
import com.pulse.git.service.RepoScaffoldService;
import com.pulse.git.service.ScaffoldPreviewService;
import com.pulse.pipeline.model.Pipeline;
import com.pulse.pipeline.repository.PipelineRepository;
import com.pulse.sor.model.Domain;
import com.pulse.sor.repository.DomainRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GitControllerTest {

    @Mock private GitRepoRepository repoRepo;
    @Mock private PullRequestRepository prRepo;
    @Mock private PipelineRepository pipelineRepository;
    @Mock private DomainRepository domainRepository;
    @Mock private TenantService tenantService;
    @Mock private LocalGitService localGitService;
    @Mock private RemoteGitService remoteGitService;
    @Mock private RepoScaffoldService repoScaffoldService;
    @Mock private UserGitIdentityService identityService;
    @Mock private ActorResolverService actorResolver;
    @Mock private ScaffoldPreviewService scaffoldPreviewService;

    private GitController controller;

    @BeforeEach
    void setUp() {
        // PKT-FINAL-4 (BUG-36): construct a real GitCloneBaseService pointing
        // at a writable tmpdir-relative path so validateAndFallback() does not
        // trip the canonical-default fallback or the operator-supplied
        // fail-fast branch during tests.
        GitCloneBaseService cloneBase = new GitCloneBaseService("/tmp/pulse-repos");
        // validateAndFallback() is package-private; constructor sets the path which is enough for unit-test usage.
        controller = new GitController(
                repoRepo, prRepo, pipelineRepository, domainRepository,
                tenantService, localGitService,
                remoteGitService, repoScaffoldService,
                identityService, actorResolver, scaffoldPreviewService,
                new GitHubRepoUrlValidator(),
                cloneBase);
    }

    @Test
    void linkRepoToDomain_usesAuthoritativeDomainTenant() throws Exception {
        Domain domain = new Domain();
        domain.setId("domain-1");
        domain.setTenantId("tenant-real");
        domain.setName("Servicing");

        when(domainRepository.findById("domain-1")).thenReturn(Optional.of(domain));
        when(repoRepo.findFirstByDomainIdOrderByCreatedAtDesc("domain-1")).thenReturn(Optional.empty());
        when(repoRepo.save(any(GitRepo.class))).thenAnswer(inv -> inv.getArgument(0));

        var request = new GitController.LinkRepoRequest("tenant-real", "GITHUB", "https://github.com/home-lending/domain-repo", "main");

        ResponseEntity<GitRepo> response = controller.linkRepoToDomain("domain-1", request);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("tenant-real", response.getBody().getTenantId());
        assertNull(response.getBody().getPipelineId());
        assertEquals("domain-1", response.getBody().getDomainId());
    }

    @Test
    void linkRepo_pipelineShim_usesPipelineTenant() throws Exception {
        Pipeline pipeline = new Pipeline();
        pipeline.setId("pipeline-1");
        pipeline.setTenantId("tenant-real");
        pipeline.setDomainName("Servicing");

        when(pipelineRepository.findById("pipeline-1")).thenReturn(Optional.of(pipeline));
        when(repoRepo.findByPipelineId("pipeline-1")).thenReturn(Optional.empty());
        when(repoRepo.save(any(GitRepo.class))).thenAnswer(inv -> inv.getArgument(0));

        var request = new GitController.LinkRepoRequest("tenant-spoofed", "GITHUB", "https://github.com/home-lending/pipeline-repo", "main");

        ResponseEntity<GitRepo> response = controller.linkRepo("pipeline-1", request);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("tenant-real", response.getBody().getTenantId());
        assertEquals("pipeline-1", response.getBody().getPipelineId());
        assertEquals(Boolean.TRUE, response.getBody().getMetadata().get("legacyPipelineShim"));
    }

    @Test
    void getRepo_pipelineWithDomainId_readsDomainScopedRepo() {
        Pipeline pipeline = new Pipeline();
        pipeline.setId("pipeline-1");
        pipeline.setTenantId("tenant-real");
        pipeline.setDomainId("domain-1");
        pipeline.setDomainName("Servicing");

        GitRepo repo = new GitRepo();
        repo.setId("repo-1");
        repo.setTenantId("tenant-real");
        repo.setDomainId("domain-1");
        repo.setRepoUrl("https://github.com/home-lending/domain-repo");
        repo.setDefaultBranch("main");

        when(pipelineRepository.findById("pipeline-1")).thenReturn(Optional.of(pipeline));
        when(repoRepo.findFirstByDomainIdOrderByCreatedAtDesc("domain-1")).thenReturn(Optional.of(repo));

        ResponseEntity<GitRepo> response = controller.getRepo("pipeline-1");

        assertEquals(200, response.getStatusCode().value());
        assertEquals("repo-1", response.getBody().getId());
    }

    @Test
    void getRepo_pipelineWithoutDomainId_readsPipelineScopedRepo() {
        Pipeline pipeline = new Pipeline();
        pipeline.setId("pipeline-1");
        pipeline.setTenantId("tenant-real");
        pipeline.setDomainName("Servicing");

        GitRepo repo = new GitRepo();
        repo.setId("repo-1");
        repo.setTenantId("tenant-real");
        repo.setPipelineId("pipeline-1");
        repo.setRepoUrl("https://github.com/home-lending/domain-repo");
        repo.setDefaultBranch("main");

        when(pipelineRepository.findById("pipeline-1")).thenReturn(Optional.of(pipeline));
        when(repoRepo.findByPipelineId("pipeline-1")).thenReturn(Optional.of(repo));

        ResponseEntity<GitRepo> response = controller.getRepo("pipeline-1");

        assertEquals(200, response.getStatusCode().value());
        assertEquals("repo-1", response.getBody().getId());
    }

    @Test
    void linkRepo_pipelineWithCanonicalDomainId_reusesDomainScopedRepoWithoutLegacyShim() {
        Pipeline pipeline = new Pipeline();
        pipeline.setId("pipeline-1");
        pipeline.setTenantId("tenant-real");
        pipeline.setDomainId("domain-1");
        pipeline.setDomainName("Servicing");

        when(pipelineRepository.findById("pipeline-1")).thenReturn(Optional.of(pipeline));
        when(repoRepo.findFirstByDomainIdOrderByCreatedAtDesc("domain-1")).thenReturn(Optional.empty());
        when(repoRepo.save(any(GitRepo.class))).thenAnswer(inv -> inv.getArgument(0));

        var request = new GitController.LinkRepoRequest("tenant-spoofed", "GITHUB", "https://github.com/home-lending/pipeline-repo", "main");

        ResponseEntity<GitRepo> response = controller.linkRepo("pipeline-1", request);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("tenant-real", response.getBody().getTenantId());
        assertEquals("domain-1", response.getBody().getDomainId());
        assertNull(response.getBody().getPipelineId());
        assertEquals(Boolean.FALSE, response.getBody().getMetadata().get("legacyPipelineShim"));
    }

    @Test
    void linkRepoToDomain_wrongTenant_throwsNotFound() throws Exception {
        Domain domain = new Domain();
        domain.setId("domain-1");
        domain.setTenantId("tenant-real");
        domain.setName("Servicing");

        when(domainRepository.findById("domain-1")).thenReturn(Optional.of(domain));
        var request = new GitController.LinkRepoRequest("tenant-other", "GITHUB", "https://github.com/home-lending/domain-repo", "main");

        assertThrows(ResourceNotFoundException.class, () -> controller.linkRepoToDomain("domain-1", request));
    }

    // -----------------------------------------------------------------------
    //  Tenant-scoped endpoint tests (V82+)
    // -----------------------------------------------------------------------

    private TenantConfig.TenantDefinition tenantDef() {
        TenantConfig.TenantDefinition def = new TenantConfig.TenantDefinition();
        def.setId("tenant-home-lending");
        def.setSlug("home-lending");
        def.setName("Acme Corp");
        return def;
    }

    @Test
    void onboard_rejectsNonGithubRemoteProvider() {
        when(tenantService.getTenant("tenant-home-lending")).thenReturn(tenantDef());
        when(repoRepo.findByTenantIdAndScope("tenant-home-lending", "TENANT")).thenReturn(Optional.empty());

        var req = new GitController.OnboardRequest("REMOTE", "https://github.com/a/b.git", "GITLAB", "main", null);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.onboard("tenant-home-lending", req));
        assertEquals(400, ex.getStatusCode().value());
    }

    @Test
    void onboard_rejectsLocalRepoType_pktFinal3() {
        // PKT-FINAL-3 (BUG-05): LOCAL tenant repos are no longer supported.
        when(tenantService.getTenant("tenant-home-lending")).thenReturn(tenantDef());
        when(repoRepo.findByTenantIdAndScope("tenant-home-lending", "TENANT")).thenReturn(Optional.empty());

        var req = new GitController.OnboardRequest("LOCAL", null, null, "main", null);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.onboard("tenant-home-lending", req));
        assertEquals(400, ex.getStatusCode().value());
        // Sanity: nothing was written and no local-git init was attempted.
        verify(repoRepo, org.mockito.Mockito.never()).save(any(GitRepo.class));
        verify(localGitService, org.mockito.Mockito.never()).initRepo(anyString(), anyString());
    }

    @Test
    void updateGitRepo_updatesProviderToGithub() {
        GitRepo existing = new GitRepo();
        existing.setTenantId("tenant-home-lending");
        existing.setScope("TENANT");
        existing.setRepoType("REMOTE");
        existing.setLocalPath("/tmp/pulse-repos/home-lending");
        existing.setRepoUrl("https://github.com/home-lending/repo.git");
        when(repoRepo.findByTenantIdAndScope("tenant-home-lending", "TENANT")).thenReturn(Optional.of(existing));
        when(repoRepo.save(any(GitRepo.class))).thenAnswer(inv -> inv.getArgument(0));

        var req = new GitController.UpdateRepoRequest();
        req.setProvider("GITHUB");
        ResponseEntity<GitRepo> response = controller.updateTenantRepo("tenant-home-lending", req);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("GITHUB", response.getBody().getProvider());
    }

    @Test
    void updateGitRepo_rejectsNonGithubProvider() {
        GitRepo existing = new GitRepo();
        existing.setScope("TENANT");
        existing.setRepoType("REMOTE");
        when(repoRepo.findByTenantIdAndScope("tenant-home-lending", "TENANT")).thenReturn(Optional.of(existing));

        var req = new GitController.UpdateRepoRequest();
        req.setProvider("BITBUCKET");
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.updateTenantRepo("tenant-home-lending", req));
        assertEquals(400, ex.getStatusCode().value());
    }

    @Test
    void updateGitRepo_tenantNotOnboarded_returns404() {
        when(repoRepo.findByTenantIdAndScope("tenant-home-lending", "TENANT")).thenReturn(Optional.empty());
        var req = new GitController.UpdateRepoRequest();
        req.setProvider("GITHUB");
        assertThrows(ResourceNotFoundException.class,
                () -> controller.updateTenantRepo("tenant-home-lending", req));
    }

    @Test
    void branches_returnsList() {
        GitRepo existing = new GitRepo();
        existing.setScope("TENANT");
        existing.setRepoType("REMOTE");
        existing.setProvider("GITHUB");
        existing.setLocalPath("/tmp/pulse-repos/home-lending");
        existing.setDefaultBranch("main");
        when(repoRepo.findByTenantIdAndScope("tenant-home-lending", "TENANT")).thenReturn(Optional.of(existing));
        // PKT-FINAL-3 (BUG-05): listBranches always pulls now (REMOTE-only).
        when(identityService.requireValidIdentity(any())).thenReturn(new com.pulse.git.identity.UserGitIdentity());
        when(localGitService.listBranches("/tmp/pulse-repos/home-lending"))
                .thenReturn(List.of("main", "feature/x"));

        ResponseEntity<List<String>> response = controller.listBranches("tenant-home-lending");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(List.of("main", "feature/x"), response.getBody());
        verify(remoteGitService).pullFromRemote(eq(existing), any());
    }

    @Test
    void branches_emptyRepo_fallsBackToDefault() {
        GitRepo existing = new GitRepo();
        existing.setScope("TENANT");
        existing.setRepoType("REMOTE");
        existing.setProvider("GITHUB");
        existing.setLocalPath("/tmp/pulse-repos/home-lending");
        existing.setDefaultBranch("main");
        when(repoRepo.findByTenantIdAndScope("tenant-home-lending", "TENANT")).thenReturn(Optional.of(existing));
        when(identityService.requireValidIdentity(any())).thenReturn(new com.pulse.git.identity.UserGitIdentity());
        when(localGitService.listBranches("/tmp/pulse-repos/home-lending")).thenReturn(Collections.emptyList());

        ResponseEntity<List<String>> response = controller.listBranches("tenant-home-lending");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(List.of("main"), response.getBody());
    }

    // -----------------------------------------------------------------------
    //  PKT-FINAL-4 (BUG-41): handleGitAuth + handleGitRepoAccessDenied moved
    //  to GlobalExceptionHandler so the cause chain is walked. Tests for
    //  those handlers now live in GlobalExceptionHandlerTest.
    // -----------------------------------------------------------------------

    // -----------------------------------------------------------------------
    //  PKT-FINAL-4 (BUG-42): catch-block must deleteAndFlush the saved row
    //  and recursively remove the partial-clone working tree so retries
    //  don't hit the idempotency conflict.
    // -----------------------------------------------------------------------

    @Test
    void onboard_cloneFailureCleansDbRowAndFilesystem(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir)
            throws Exception {
        // Use a writable tempDir as the clone-base so the helper actually has a
        // partial working tree to remove. Swap the controller's clone-base.
        GitCloneBaseService cloneBase = new GitCloneBaseService(tempDir.toString());
        // validateAndFallback() is package-private; constructor sets the path which is enough for unit-test usage.
        GitController scoped = new GitController(
                repoRepo, prRepo, pipelineRepository, domainRepository,
                tenantService, localGitService,
                remoteGitService, repoScaffoldService,
                identityService, actorResolver, scaffoldPreviewService,
                new GitHubRepoUrlValidator(),
                cloneBase);

        when(tenantService.getTenant("tenant-home-lending")).thenReturn(tenantDef());
        when(repoRepo.findByTenantIdAndScope("tenant-home-lending", "TENANT")).thenReturn(Optional.empty());
        when(identityService.requireValidIdentity(any())).thenReturn(new com.pulse.git.identity.UserGitIdentity());

        // Pre-create a fake partial-clone directory at the expected local path so
        // we can assert the cleanup helper recursively removed it.
        java.nio.file.Path partial = tempDir.resolve("home-lending");
        java.nio.file.Files.createDirectories(partial.resolve(".git"));
        java.nio.file.Files.writeString(partial.resolve("README.md"), "stale");

        GitRepo saved = new GitRepo();
        saved.setId("repo-1");
        saved.setTenantId("tenant-home-lending");
        saved.setLocalPath(partial.toString());
        when(repoRepo.save(any(GitRepo.class))).thenReturn(saved);

        // Force the clone to throw — the catch block must run.
        doThrow(new GitAuthenticationException("Bad PAT for origin")).when(remoteGitService)
                .cloneRepo(any(GitRepo.class), any());

        var req = new GitController.OnboardRequest(
                "REMOTE", "https://github.com/zadam2008/pulse-acme-lending", "GITHUB", "main", null);

        assertThrows(GitAuthenticationException.class,
                () -> scoped.onboard("tenant-home-lending", req));

        // BUG-42 assertions: row was deleted AND flushed AND partial dir gone.
        verify(repoRepo).delete(saved);
        verify(repoRepo).flush();
        assertEquals(false, partial.toFile().exists(),
                "Expected partial clone dir to be recursively removed on cleanup");
    }
}
