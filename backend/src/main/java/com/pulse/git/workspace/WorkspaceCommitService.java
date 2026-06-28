package com.pulse.git.workspace;

import com.pulse.auth.policy.CallerContext;
import com.pulse.auth.policy.PulseAction;
import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.git.identity.UserGitIdentity;
import com.pulse.git.identity.UserGitIdentityService;
import com.pulse.git.model.GitRepo;
import com.pulse.git.repository.GitRepoRepository;
import com.pulse.git.service.LocalGitService;
import com.pulse.git.service.RemoteGitService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.springframework.http.HttpStatus.CONFLICT;

@Service
public class WorkspaceCommitService {

    private final DeveloperWorkspaceRepository workspaceRepository;
    private final WorkspaceFileManifestRepository manifestRepository;
    private final GitRepoRepository gitRepoRepository;
    private final LocalGitService localGitService;
    private final RemoteGitService remoteGitService;
    private final UserGitIdentityService identityService;
    private final WorkspaceGitStorageService gitStorageService;
    private final WorkspaceAuthorizationService authorizationService;

    public WorkspaceCommitService(DeveloperWorkspaceRepository workspaceRepository,
                                  WorkspaceFileManifestRepository manifestRepository,
                                  GitRepoRepository gitRepoRepository,
                                  LocalGitService localGitService,
                                  RemoteGitService remoteGitService,
                                  UserGitIdentityService identityService,
                                  WorkspaceGitStorageService gitStorageService,
                                  WorkspaceAuthorizationService authorizationService) {
        this.workspaceRepository = workspaceRepository;
        this.manifestRepository = manifestRepository;
        this.gitRepoRepository = gitRepoRepository;
        this.localGitService = localGitService;
        this.remoteGitService = remoteGitService;
        this.identityService = identityService;
        this.gitStorageService = gitStorageService;
        this.authorizationService = authorizationService;
    }

    @Transactional
    public WorkspaceDtos.WorkspaceStatusDto commit(String workspaceId, WorkspaceDtos.WorkspaceCommitRequest request) {
        DeveloperWorkspace workspace = workspace(workspaceId);
        CallerContext caller = authorizationService.enforce(workspace.getTenantId(), PulseAction.COMMIT);
        Set<String> allowed = manifestRepository.findByWorkspaceIdOrderByPathAsc(workspaceId)
                .stream()
                .filter(WorkspaceFileManifest::isManagedByPulse)
                .map(WorkspaceFileManifest::getPath)
                .collect(java.util.stream.Collectors.toSet());
        for (String changed : gitStorageService.changedPaths(workspace)) {
            if (!allowed.contains(changed)) {
                throw new ResponseStatusException(CONFLICT, "MANAGED_PATH_VIOLATION");
            }
        }
        UserGitIdentity identity = identityService.requireValidIdentity(caller);
        String message = request == null || request.message() == null || request.message().isBlank()
                ? "pulse: workspace changes " + workspace.getId()
                : request.message().trim();
        localGitService.commitAsUser(workspace.getCheckoutPath(), message,
                identity.getAuthorName(), identity.getAuthorEmail());
        gitStorageService.refreshStatus(workspace);
        workspace.setLastCommitSha(workspace.getHeadSha());
        workspace.setRemoteSyncStatus("not_pushed");
        Path checkout = Path.of(workspace.getCheckoutPath()).normalize();
        manifestRepository.findByWorkspaceIdOrderByPathAsc(workspaceId).forEach(manifest -> {
            manifest.setCurrentWorkspaceSha256(currentSha(checkout, manifest.getPath()));
            manifest.setLastCommittedSha256(manifest.getCurrentWorkspaceSha256());
            manifestRepository.save(manifest);
        });
        return WorkspaceDtos.WorkspaceStatusDto.from(workspaceRepository.save(workspace));
    }

    @Transactional
    public WorkspaceDtos.WorkspaceStatusDto push(String workspaceId) {
        DeveloperWorkspace workspace = workspace(workspaceId);
        CallerContext caller = authorizationService.enforce(workspace.getTenantId(), PulseAction.COMMIT);
        UserGitIdentity identity = identityService.requireValidIdentity(caller);
        GitRepo repo = gitRepoRepository.findById(workspace.getGitRepoId())
                .orElseThrow(() -> new ResourceNotFoundException("GitRepo", workspace.getGitRepoId()));
        GitRepo workspaceRepo = new GitRepo();
        workspaceRepo.setId(repo.getId());
        workspaceRepo.setTenantId(repo.getTenantId());
        workspaceRepo.setRepoUrl(repo.getRepoUrl());
        workspaceRepo.setDefaultBranch(repo.getDefaultBranch());
        workspaceRepo.setLocalPath(workspace.getCheckoutPath());
        workspaceRepo.setCurrentBranch(workspace.getBranchName());
        remoteGitService.pushToRemote(workspaceRepo, identity);
        gitStorageService.refreshStatus(workspace);
        workspace.setLastPushSha(workspace.getHeadSha());
        workspace.setRemoteSyncStatus("pushed");
        return WorkspaceDtos.WorkspaceStatusDto.from(workspaceRepository.save(workspace));
    }

    private static String currentSha(Path checkout, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) return null;
        Path file = checkout.resolve(relativePath).normalize();
        if (!file.startsWith(checkout) || !Files.exists(file)) return null;
        try {
            return WorkspacePackageService.sha256(Files.readString(file));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to hash committed workspace file " + file, e);
        }
    }

    private DeveloperWorkspace workspace(String workspaceId) {
        return workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("DeveloperWorkspace", workspaceId));
    }
}
