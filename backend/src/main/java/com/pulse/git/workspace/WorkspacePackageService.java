package com.pulse.git.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.pulse.auth.policy.CallerContext;
import com.pulse.auth.policy.PulseAction;
import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.deploy.model.Package;
import com.pulse.deploy.repository.PackageRepository;
import com.pulse.git.model.GitRepo;
import com.pulse.git.repository.GitRepoRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.CONFLICT;

@Service
public class WorkspacePackageService {

    private final DeveloperWorkspaceRepository workspaceRepository;
    private final WorkspaceFileManifestRepository manifestRepository;
    private final PackageRepository packageRepository;
    private final WorkspaceGitStorageService gitStorageService;
    private final GitRepoRepository gitRepoRepository;
    private final WorkspaceAuthorizationService authorizationService;
    private final Path artifactRoot;
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, false);

    public WorkspacePackageService(DeveloperWorkspaceRepository workspaceRepository,
                                   WorkspaceFileManifestRepository manifestRepository,
                                   PackageRepository packageRepository,
                                   WorkspaceGitStorageService gitStorageService,
                                   GitRepoRepository gitRepoRepository,
                                   WorkspaceAuthorizationService authorizationService,
                                   @Value("${pulse.git.workspace-package-root:/tmp/pulse-workspace-packages}") String artifactRoot) {
        this.workspaceRepository = workspaceRepository;
        this.manifestRepository = manifestRepository;
        this.packageRepository = packageRepository;
        this.gitStorageService = gitStorageService;
        this.gitRepoRepository = gitRepoRepository;
        this.authorizationService = authorizationService;
        this.artifactRoot = Path.of(artifactRoot);
    }

    @Transactional
    public WorkspaceDtos.WorkspacePackageDto buildDevPackage(String workspaceId) {
        DeveloperWorkspace workspace = workspace(workspaceId);
        gitStorageService.refreshStatus(workspace);
        workspaceRepository.save(workspace);
        return build(workspace, "WORKSPACE_SNAPSHOT", false);
    }

    @Transactional
    public WorkspaceDtos.WorkspacePackageDto buildCommittedPackage(String workspaceId) {
        DeveloperWorkspace workspace = workspace(workspaceId);
        gitStorageService.refreshStatus(workspace);
        workspaceRepository.save(workspace);
        if (!"clean".equals(workspace.getWorkingTreeStatus())) {
            throw new ResponseStatusException(CONFLICT, "WORKSPACE_NOT_CLEAN_FOR_COMMITTED_PACKAGE");
        }
        if (blank(workspace.getHeadSha()) || blank(workspace.getHeadTreeSha())) {
            throw new ResponseStatusException(CONFLICT, "WORKSPACE_COMMIT_EVIDENCE_MISSING");
        }
        return build(workspace, "GIT_COMMIT", true);
    }

    private WorkspaceDtos.WorkspacePackageDto build(DeveloperWorkspace workspace, String sourceKind, boolean promotable) {
        CallerContext caller = authorizationService.enforce(workspace.getTenantId(), PulseAction.PACKAGE_BUILD);
        GitRepo repo = gitRepoRepository.findById(workspace.getGitRepoId())
                .orElseThrow(() -> new ResourceNotFoundException("GitRepo", workspace.getGitRepoId()));
        List<WorkspaceFileManifest> manifests = manifestRepository.findByWorkspaceIdOrderByPathAsc(workspace.getId())
                .stream()
                .filter(WorkspaceFileManifest::isManagedByPulse)
                .sorted(Comparator.comparing(WorkspaceFileManifest::getPath))
                .toList();
        if (manifests.isEmpty()) {
            throw new ResponseStatusException(CONFLICT, "PACKAGE_MANIFEST_PATH_UNMANAGED");
        }
        Instant now = Instant.now();
        Package pkg = new Package();
        pkg.setTenantId(workspace.getTenantId());
        pkg.setPipelineId(workspace.getPipelineId());
        pkg.setVersionId(workspace.getVersionId());
        pkg.setWorkspaceId(workspace.getId());
        pkg.setSourceKind(sourceKind);
        pkg.setPackageType("WORKSPACE_ARTIFACT_BUNDLE");
        pkg.setBuiltBy(caller.userId());
        pkg.setBuildStatus("COMPLETED");
        pkg.setBuiltAt(now);
        pkg.setCommitSha(workspace.getHeadSha());
        pkg.setTreeSha(workspace.getHeadTreeSha());
        pkg.setPromotable(promotable);

        List<Map<String, Object>> files = new ArrayList<>();
        Map<String, String> contentByPath = new LinkedHashMap<>();
        Path checkout = Path.of(workspace.getCheckoutPath()).normalize();
        for (WorkspaceFileManifest manifest : manifests) {
            Path file = checkout.resolve(manifest.getPath()).normalize();
            if (!file.startsWith(checkout) || !Files.exists(file)) {
                throw new ResponseStatusException(CONFLICT, "PACKAGE_MANIFEST_PATH_UNMANAGED");
            }
            String content = read(file);
            String sha = sha256(content);
            contentByPath.put(manifest.getPath(), content);
            files.add(Map.of(
                    "path", manifest.getPath(),
                    "sha256", sha,
                    "scope", manifest.getPathScope(),
                    "sizeBytes", content.getBytes(StandardCharsets.UTF_8).length));
            manifest.setCurrentWorkspaceSha256(sha);
            if (promotable) manifest.setLastCommittedSha256(sha);
            manifestRepository.save(manifest);
        }

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", "workspace-package-manifest.v1");
        manifest.put("tenantId", workspace.getTenantId());
        manifest.put("pipelineId", workspace.getPipelineId());
        manifest.put("versionId", workspace.getVersionId());
        manifest.put("workspaceId", workspace.getId());
        manifest.put("sourceKind", sourceKind);
        manifest.put("commitSha", workspace.getHeadSha());
        manifest.put("treeSha", workspace.getHeadTreeSha());
        manifest.put("builtAt", now.toString());
        manifest.put("files", files);

        Map<String, Object> git = new LinkedHashMap<>();
        git.put("repoId", workspace.getGitRepoId());
        git.put("branch", workspace.getBranchName());
        git.put("commitSha", workspace.getHeadSha());
        git.put("treeSha", workspace.getHeadTreeSha());
        git.put("workingTreeStatus", workspace.getWorkingTreeStatus());
        git.put("dirtyFileCount", workspace.getDirtyFileCount());
        git.put("capturedAt", now.toString());
        git.put("repoUrl", repo.getRepoUrl());

        Map<String, Object> staticAssessment = new LinkedHashMap<>();
        staticAssessment.put("verdict", "LIKELY_DEPLOYABLE");
        staticAssessment.put("blockers", List.of());
        staticAssessment.put("warnings", List.of());
        staticAssessment.put("source", "workspace_package_manifest");

        String manifestHash = sha256Json(manifest);
        Map<String, Object> bundle = Map.of("manifest", manifest, "contents", contentByPath);
        byte[] artifactBytes = artifactBytes(bundle);
        String artifactSha = sha256(artifactBytes);
        Path artifactPath = artifactRoot.resolve(workspace.getId()).resolve(artifactSha + ".json").normalize();
        writeBytes(artifactPath, artifactBytes);
        pkg.setPackageArtifactUri(artifactPath.toUri().toString());
        pkg.setPackageArtifactSha256(artifactSha);
        pkg.setPackageManifestHash(manifestHash);
        pkg.setArtifactHash(artifactSha);
        pkg.setArtifactUrl(pkg.getPackageArtifactUri());
        pkg.setBuildLog("Packaged " + manifests.size() + " manifest-managed workspace files.");
        pkg.setMetadata(new LinkedHashMap<>(Map.of(
                "workspacePackageManifest", manifest,
                "packageManifest", manifest,
                "packageManifestHash", manifestHash,
                "git", git,
                "workingTreeStatus", workspace.getWorkingTreeStatus(),
                "staticRuntimeAssessment", staticAssessment,
                "packageArtifactUri", pkg.getPackageArtifactUri(),
                "packageArtifactSha256", artifactSha,
                "artifactHash", artifactSha,
                "sourceKind", sourceKind)));
        pkg = packageRepository.save(pkg);
        workspace.setLastPackageId(pkg.getId());
        workspaceRepository.save(workspace);
        return WorkspaceDtos.WorkspacePackageDto.from(pkg);
    }

    private DeveloperWorkspace workspace(String workspaceId) {
        return workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("DeveloperWorkspace", workspaceId));
    }

    private String sha256Json(Object value) {
        try {
            return sha256(new String(mapper.writeValueAsBytes(value), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Unable to serialize workspace package", e);
        }
    }

    private byte[] artifactBytes(Object value) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to serialize workspace package", e);
        }
    }

    private void writeBytes(Path path, byte[] bytes) {
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, bytes);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to write package artifact " + path, e);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read workspace package file " + path, e);
        }
    }

    static String sha256(String content) {
        return sha256(content.getBytes(StandardCharsets.UTF_8));
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to hash content", e);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
