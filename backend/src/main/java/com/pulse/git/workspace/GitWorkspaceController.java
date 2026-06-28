package com.pulse.git.workspace;

import com.pulse.deploy.promotion.PromotionProofReadback;
import com.pulse.deploy.promotion.PromotionProofService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class GitWorkspaceController {

    private final DeveloperWorkspaceService workspaceService;
    private final WorkspaceFileService fileService;
    private final WorkspaceGenerationService generationService;
    private final WorkspacePackageService packageService;
    private final WorkspaceCommitService commitService;
    private final WorkspacePullRequestService pullRequestService;
    private final PromotionProofService promotionProofService;

    public GitWorkspaceController(DeveloperWorkspaceService workspaceService,
                                  WorkspaceFileService fileService,
                                  WorkspaceGenerationService generationService,
                                  WorkspacePackageService packageService,
                                  WorkspaceCommitService commitService,
                                  WorkspacePullRequestService pullRequestService,
                                  PromotionProofService promotionProofService) {
        this.workspaceService = workspaceService;
        this.fileService = fileService;
        this.generationService = generationService;
        this.packageService = packageService;
        this.commitService = commitService;
        this.pullRequestService = pullRequestService;
        this.promotionProofService = promotionProofService;
    }

    @PostMapping("/api/v1/versions/{versionId}/workspace")
    public ResponseEntity<WorkspaceDtos.WorkspaceStatusDto> startWorkspace(
            @PathVariable String versionId,
            @RequestBody(required = false) WorkspaceDtos.StartWorkspaceRequest request) {
        return ResponseEntity.ok(workspaceService.startWorkspace(versionId, request));
    }

    @GetMapping("/api/v1/versions/{versionId}/workspace-context")
    public ResponseEntity<WorkspaceDtos.WorkspaceContextDto> getWorkspaceContext(@PathVariable String versionId) {
        return ResponseEntity.ok(workspaceService.getWorkspaceContext(versionId));
    }

    @GetMapping("/api/v1/workspaces/{workspaceId}")
    public ResponseEntity<WorkspaceDtos.WorkspaceStatusDto> getWorkspace(@PathVariable String workspaceId) {
        return ResponseEntity.ok(workspaceService.getWorkspace(workspaceId));
    }

    @GetMapping("/api/v1/workspaces/{workspaceId}/diff")
    public ResponseEntity<WorkspaceDtos.WorkspaceDiffDto> getDiff(@PathVariable String workspaceId) {
        return ResponseEntity.ok(workspaceService.getDiff(workspaceId));
    }

    @PostMapping("/api/v1/workspaces/{workspaceId}/archive")
    public ResponseEntity<WorkspaceDtos.WorkspaceStatusDto> archive(@PathVariable String workspaceId) {
        return ResponseEntity.ok(workspaceService.archive(workspaceId));
    }

    @GetMapping("/api/v1/workspaces/{workspaceId}/files")
    public ResponseEntity<List<WorkspaceDtos.WorkspaceFileDto>> listFiles(@PathVariable String workspaceId) {
        return ResponseEntity.ok(fileService.listFiles(workspaceId));
    }

    @GetMapping("/api/v1/workspaces/{workspaceId}/file")
    public ResponseEntity<WorkspaceDtos.WorkspaceFileContentDto> readFile(
            @PathVariable String workspaceId,
            @RequestParam String path) {
        return ResponseEntity.ok(fileService.readFile(workspaceId, path));
    }

    @PutMapping("/api/v1/workspaces/{workspaceId}/file")
    public ResponseEntity<WorkspaceDtos.WorkspaceFileContentDto> updateFile(
            @PathVariable String workspaceId,
            @RequestBody WorkspaceDtos.WorkspaceFileUpdateRequest request) {
        return ResponseEntity.ok(fileService.updateFile(workspaceId, request));
    }

    @PostMapping("/api/v1/workspaces/{workspaceId}/generate")
    public ResponseEntity<WorkspaceDtos.WorkspaceGenerationDto> generate(@PathVariable String workspaceId) {
        return ResponseEntity.ok(generationService.generate(workspaceId));
    }

    @PostMapping("/api/v1/workspaces/{workspaceId}/packages/dev")
    public ResponseEntity<WorkspaceDtos.WorkspacePackageDto> buildDevPackage(@PathVariable String workspaceId) {
        return ResponseEntity.ok(packageService.buildDevPackage(workspaceId));
    }

    @PostMapping("/api/v1/workspaces/{workspaceId}/packages/committed")
    public ResponseEntity<WorkspaceDtos.WorkspacePackageDto> buildCommittedPackage(@PathVariable String workspaceId) {
        return ResponseEntity.ok(packageService.buildCommittedPackage(workspaceId));
    }

    @PostMapping("/api/v1/workspaces/{workspaceId}/commit")
    public ResponseEntity<WorkspaceDtos.WorkspaceStatusDto> commit(
            @PathVariable String workspaceId,
            @RequestBody(required = false) WorkspaceDtos.WorkspaceCommitRequest request) {
        return ResponseEntity.ok(commitService.commit(workspaceId, request));
    }

    @PostMapping("/api/v1/workspaces/{workspaceId}/push")
    public ResponseEntity<WorkspaceDtos.WorkspaceStatusDto> push(@PathVariable String workspaceId) {
        return ResponseEntity.ok(commitService.push(workspaceId));
    }

    @PostMapping("/api/v1/workspaces/{workspaceId}/pull-request")
    public ResponseEntity<com.pulse.git.model.PullRequest> createPullRequest(
            @PathVariable String workspaceId,
            @RequestBody(required = false) WorkspaceDtos.CreatePullRequestRequest request) {
        return ResponseEntity.ok(pullRequestService.createPullRequest(workspaceId, request));
    }

    @PostMapping("/api/v1/pull-requests/{prId}/sync")
    public ResponseEntity<com.pulse.git.model.PullRequest> syncPullRequest(@PathVariable String prId) {
        return ResponseEntity.ok(pullRequestService.syncPullRequest(prId));
    }

    @GetMapping("/api/v1/versions/{versionId}/acceptance")
    public ResponseEntity<com.pulse.pipeline.model.VersionAcceptance> getAcceptance(@PathVariable String versionId) {
        return ResponseEntity.ok(pullRequestService.getAcceptance(versionId));
    }

    /** PKT-0008: promotion proof-state readback for a pipeline version. */
    @GetMapping("/api/v1/versions/{versionId}/promotion-proof-state")
    public ResponseEntity<PromotionProofReadback> getPromotionProofState(@PathVariable String versionId) {
        return ResponseEntity.ok(promotionProofService.computeProofState(versionId));
    }
}
