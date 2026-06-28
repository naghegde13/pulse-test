package com.pulse.git.workspace;

import com.pulse.auth.policy.ActorResolverService;
import com.pulse.auth.policy.CallerContext;
import com.pulse.auth.policy.CallerSurface;
import com.pulse.auth.policy.PulseRole;
import com.pulse.codegen.repository.GeneratedArtifactRepository;
import com.pulse.codegen.repository.GenerationRunRepository;
import com.pulse.git.model.GitRepo;
import com.pulse.git.repository.GitRepoRepository;
import com.pulse.pipeline.model.Pipeline;
import com.pulse.pipeline.model.PipelineStage;
import com.pulse.pipeline.model.PipelineVersion;
import com.pulse.pipeline.repository.PipelineRepository;
import com.pulse.pipeline.repository.PipelineVersionRepository;
import com.pulse.pipeline.repository.VersionAcceptanceRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeveloperWorkspaceServiceTest {

    @Test
    void workspaceContextUsesDomainRepoAndExistingActiveWorkspace() {
        DeveloperWorkspaceRepository workspaceRepo = mock(DeveloperWorkspaceRepository.class);
        GitRepoRepository gitRepoRepo = mock(GitRepoRepository.class);
        PipelineRepository pipelineRepo = mock(PipelineRepository.class);
        PipelineVersionRepository versionRepo = mock(PipelineVersionRepository.class);
        ActorResolverService actorResolver = mock(ActorResolverService.class);
        DeveloperWorkspaceService service = new DeveloperWorkspaceService(
                workspaceRepo,
                gitRepoRepo,
                pipelineRepo,
                versionRepo,
                mock(VersionAcceptanceRepository.class),
                mock(GenerationRunRepository.class),
                mock(GeneratedArtifactRepository.class),
                mock(WorkspaceGitStorageService.class),
                mock(WorkspaceFileService.class),
                actorResolver);
        Pipeline pipeline = pipeline();
        PipelineVersion version = version();
        GitRepo domainRepo = gitRepo("domain-repo", "DOMAIN");
        GitRepo tenantRepo = gitRepo("tenant-repo", "TENANT");
        DeveloperWorkspace workspace = workspace();

        when(versionRepo.findById("version-1")).thenReturn(Optional.of(version));
        when(pipelineRepo.findById("pipeline-1")).thenReturn(Optional.of(pipeline));
        when(actorResolver.resolve(CallerSurface.UI, "tenant-1"))
                .thenReturn(new CallerContext("user-1", "tenant-1", Set.of(PulseRole.TENANT_ADMIN), CallerSurface.UI));
        when(workspaceRepo.findFirstByTenantIdAndVersionIdAndActorUserIdAndLifecycleStatusOrderByCreatedAtDesc(
                "tenant-1", "version-1", "user-1", "ACTIVE"))
                .thenReturn(Optional.of(workspace));
        when(gitRepoRepo.findFirstByDomainIdOrderByCreatedAtDesc("domain-1"))
                .thenReturn(Optional.of(domainRepo));
        when(gitRepoRepo.findByTenantIdAndScope("tenant-1", "TENANT"))
                .thenReturn(Optional.of(tenantRepo));

        WorkspaceDtos.WorkspaceContextDto context = service.getWorkspaceContext("version-1");

        assertEquals("domain-repo", context.gitRepo().getId());
        assertNotNull(context.workspace());
        assertEquals("workspace-1", context.workspace().id());
    }

    private static Pipeline pipeline() {
        Pipeline pipeline = new Pipeline();
        pipeline.setId("pipeline-1");
        pipeline.setTenantId("tenant-1");
        pipeline.setDomainId("domain-1");
        pipeline.setName("Customer Orders");
        pipeline.setDomainName("Lending");
        pipeline.setCreatedBy("user-1");
        return pipeline;
    }

    private static PipelineVersion version() {
        PipelineVersion version = new PipelineVersion();
        version.setId("version-1");
        version.setPipelineId("pipeline-1");
        version.setRevision(2);
        version.setCreatedBy("user-1");
        version.setLifecycleStage(PipelineStage.ENGINEERING);
        return version;
    }

    private static GitRepo gitRepo(String id, String scope) {
        GitRepo repo = new GitRepo();
        repo.setId(id);
        repo.setTenantId("tenant-1");
        repo.setScope(scope);
        repo.setProvider("GITHUB");
        repo.setRepoType("REMOTE");
        repo.setRepoUrl("https://github.com/acme/pulse.git");
        return repo;
    }

    private static DeveloperWorkspace workspace() {
        DeveloperWorkspace workspace = new DeveloperWorkspace();
        workspace.setId("workspace-1");
        workspace.setTenantId("tenant-1");
        workspace.setPipelineId("pipeline-1");
        workspace.setVersionId("version-1");
        workspace.setGitRepoId("domain-repo");
        workspace.setActorUserId("user-1");
        workspace.setBranchName("pulse/customer-orders/r2-user-1");
        workspace.setBaseBranch("main");
        workspace.setCheckoutPath("/tmp/workspace-1");
        return workspace;
    }
}
