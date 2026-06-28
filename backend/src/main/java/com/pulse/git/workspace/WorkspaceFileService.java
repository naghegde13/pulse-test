package com.pulse.git.workspace;

import com.pulse.codegen.model.GeneratedArtifact;
import com.pulse.common.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

import static org.springframework.http.HttpStatus.CONFLICT;

@Service
public class WorkspaceFileService {

    private final DeveloperWorkspaceRepository workspaceRepository;
    private final WorkspaceFileManifestRepository manifestRepository;

    public WorkspaceFileService(DeveloperWorkspaceRepository workspaceRepository,
                                WorkspaceFileManifestRepository manifestRepository) {
        this.workspaceRepository = workspaceRepository;
        this.manifestRepository = manifestRepository;
    }

    @Transactional(readOnly = true)
    public List<WorkspaceDtos.WorkspaceFileDto> listFiles(String workspaceId) {
        requireWorkspace(workspaceId);
        return manifestRepository.findByWorkspaceIdOrderByPathAsc(workspaceId).stream()
                .map(WorkspaceDtos.WorkspaceFileDto::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public WorkspaceDtos.WorkspaceFileContentDto readFile(String workspaceId, String path) {
        DeveloperWorkspace workspace = requireWorkspace(workspaceId);
        String safePath = validateRelativePath(path);
        WorkspaceFileManifest manifest = manifestRepository.findByWorkspaceIdAndPath(workspaceId, safePath)
                .orElseThrow(() -> new ResourceNotFoundException("WorkspaceFileManifest", safePath));
        return new WorkspaceDtos.WorkspaceFileContentDto(
                WorkspaceDtos.WorkspaceFileDto.from(refreshCurrentSha(workspace, manifest)),
                readString(workspaceFile(workspace, safePath)));
    }

    @Transactional
    public WorkspaceDtos.WorkspaceFileContentDto updateFile(String workspaceId,
                                                            WorkspaceDtos.WorkspaceFileUpdateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        DeveloperWorkspace workspace = requireWorkspace(workspaceId);
        String safePath = validateRelativePath(request.path());
        WorkspaceFileManifest manifest = manifestRepository.findByWorkspaceIdAndPath(workspaceId, safePath)
                .orElseThrow(() -> new ResponseStatusException(CONFLICT, "UNMANIFESTED_WORKSPACE_WRITE"));
        if (!manifest.isManagedByPulse()) {
            throw new ResponseStatusException(CONFLICT, "UNMANAGED_WORKSPACE_WRITE");
        }
        writeString(workspaceFile(workspace, safePath), request.content() == null ? "" : request.content());
        manifest.setCurrentWorkspaceSha256(sha256(request.content() == null ? "" : request.content()));
        manifest = manifestRepository.save(manifest);
        return new WorkspaceDtos.WorkspaceFileContentDto(
                WorkspaceDtos.WorkspaceFileDto.from(manifest),
                request.content() == null ? "" : request.content());
    }

    @Transactional
    public WorkspaceFileManifest materializeArtifact(DeveloperWorkspace workspace, GeneratedArtifact artifact) {
        String safePath = validateRelativePath(artifact.getFilePath());
        String content = artifact.getContent() == null ? "" : artifact.getContent();
        String nextSha = sha256(content);
        WorkspaceFileManifest manifest = manifestRepository
                .findByWorkspaceIdAndPath(workspace.getId(), safePath)
                .orElseGet(() -> newManifest(workspace, artifact, safePath));

        String currentSha = currentSha(workspaceFile(workspace, safePath));
        if (currentSha != null && manifest.getLastMaterializedSha256() != null
                && !currentSha.equals(manifest.getLastMaterializedSha256())) {
            throw new ResponseStatusException(CONFLICT, "REGENERATION_WOULD_OVERWRITE_MANUAL_EDIT");
        }

        writeString(workspaceFile(workspace, safePath), content);
        manifest.setSourceArtifactId(artifact.getId());
        manifest.setLastMaterializedSha256(nextSha);
        manifest.setCurrentWorkspaceSha256(nextSha);
        manifest.setLastMaterializedAt(Instant.now());
        return manifestRepository.save(manifest);
    }

    public static String validateRelativePath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path is required");
        }
        String trimmed = path.trim();
        if (trimmed.startsWith("/") || trimmed.startsWith("\\") || trimmed.contains("\\")) {
            throw new IllegalArgumentException("workspace path must be relative");
        }
        for (String segment : trimmed.split("/")) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("workspace path contains an unsafe segment");
            }
        }
        Path normalized = Path.of(trimmed).normalize();
        if (normalized.isAbsolute() || normalized.startsWith("..")) {
            throw new IllegalArgumentException("workspace path escapes workspace root");
        }
        return normalized.toString().replace('\\', '/');
    }

    private WorkspaceFileManifest newManifest(DeveloperWorkspace workspace, GeneratedArtifact artifact, String safePath) {
        WorkspaceFileManifest manifest = new WorkspaceFileManifest();
        manifest.setWorkspaceId(workspace.getId());
        manifest.setPath(safePath);
        manifest.setSourceArtifactId(artifact.getId());
        manifest.setManagedByPulse(true);
        manifest.setPathScope("PIPELINE");
        manifest.setOwnershipKey(workspace.getPipelineId());
        return manifest;
    }

    private DeveloperWorkspace requireWorkspace(String workspaceId) {
        return workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new ResourceNotFoundException("DeveloperWorkspace", workspaceId));
    }

    private WorkspaceFileManifest refreshCurrentSha(DeveloperWorkspace workspace, WorkspaceFileManifest manifest) {
        manifest.setCurrentWorkspaceSha256(currentSha(workspaceFile(workspace, manifest.getPath())));
        return manifest;
    }

    private Path workspaceFile(DeveloperWorkspace workspace, String safePath) {
        Path root = Path.of(workspace.getCheckoutPath()).normalize();
        Path file = root.resolve(safePath).normalize();
        if (!file.startsWith(root)) {
            throw new IllegalArgumentException("workspace path escapes checkout root");
        }
        return file;
    }

    private static String currentSha(Path path) {
        if (!Files.exists(path)) {
            return null;
        }
        return sha256(readString(path));
    }

    private static String readString(Path path) {
        try {
            return Files.exists(path) ? Files.readString(path) : "";
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read workspace file " + path, e);
        }
    }

    private static void writeString(Path path, String content) {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to write workspace file " + path, e);
        }
    }

    static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
