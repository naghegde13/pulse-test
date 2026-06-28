package com.pulse.git.workspace;

import com.pulse.auth.policy.PulseAction;
import com.pulse.codegen.model.GenerationRun;
import com.pulse.codegen.repository.GeneratedArtifactRepository;
import com.pulse.codegen.service.CodeGenerationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkspaceGenerationService {

    private final DeveloperWorkspaceRepository workspaceRepository;
    private final CodeGenerationService codeGenerationService;
    private final GeneratedArtifactRepository artifactRepository;
    private final WorkspaceFileService workspaceFileService;
    private final WorkspaceGitStorageService gitStorageService;
    private final WorkspaceAuthorizationService authorizationService;

    public WorkspaceGenerationService(DeveloperWorkspaceRepository workspaceRepository,
                                      CodeGenerationService codeGenerationService,
                                      GeneratedArtifactRepository artifactRepository,
                                      WorkspaceFileService workspaceFileService,
                                      WorkspaceGitStorageService gitStorageService,
                                      WorkspaceAuthorizationService authorizationService) {
        this.workspaceRepository = workspaceRepository;
        this.codeGenerationService = codeGenerationService;
        this.artifactRepository = artifactRepository;
        this.workspaceFileService = workspaceFileService;
        this.gitStorageService = gitStorageService;
        this.authorizationService = authorizationService;
    }

    @Transactional
    public WorkspaceDtos.WorkspaceGenerationDto generate(String workspaceId) {
        DeveloperWorkspace workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new com.pulse.common.exception.ResourceNotFoundException("DeveloperWorkspace", workspaceId));
        authorizationService.enforce(workspace.getTenantId(), PulseAction.COMMIT);
        GenerationRun run = codeGenerationService.generateToWorkspace(
                workspace.getPipelineId(),
                workspace.getVersionId(),
                workspace.getTenantId(),
                workspace.getActorUserId());
        int count = 0;
        for (var artifact : artifactRepository.findByGenerationRunIdOrderByFilePathAsc(run.getId())) {
            workspaceFileService.materializeArtifact(workspace, artifact);
            count++;
        }
        gitStorageService.refreshStatus(workspace);
        workspaceRepository.save(workspace);
        return new WorkspaceDtos.WorkspaceGenerationDto(
                workspace.getId(),
                run.getId(),
                count,
                WorkspaceDtos.WorkspaceStatusDto.from(workspace));
    }
}
