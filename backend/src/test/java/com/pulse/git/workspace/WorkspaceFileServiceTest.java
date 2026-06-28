package com.pulse.git.workspace;

import com.pulse.codegen.model.GeneratedArtifact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkspaceFileServiceTest {

    @TempDir Path tempDir;

    @Test
    void rejectsUnsafePaths() {
        assertThrows(IllegalArgumentException.class, () -> WorkspaceFileService.validateRelativePath("/tmp/x"));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceFileService.validateRelativePath("../x"));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceFileService.validateRelativePath("a/../x"));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceFileService.validateRelativePath("a\\b.py"));
        assertEquals("models/orders.sql", WorkspaceFileService.validateRelativePath("models/orders.sql"));
    }

    @Test
    void materializeArtifactCreatesManifestAndFile() throws Exception {
        DeveloperWorkspaceRepository workspaceRepo = mock(DeveloperWorkspaceRepository.class);
        WorkspaceFileManifestRepository manifestRepo = mock(WorkspaceFileManifestRepository.class);
        WorkspaceFileService service = new WorkspaceFileService(workspaceRepo, manifestRepo);
        DeveloperWorkspace workspace = workspace();
        GeneratedArtifact artifact = artifact("dbt/models/orders.sql", "select 1\n");

        when(manifestRepo.findByWorkspaceIdAndPath("workspace-1", "dbt/models/orders.sql"))
                .thenReturn(Optional.empty());
        when(manifestRepo.save(any(WorkspaceFileManifest.class))).thenAnswer(inv -> inv.getArgument(0));

        WorkspaceFileManifest manifest = service.materializeArtifact(workspace, artifact);

        assertEquals("dbt/models/orders.sql", manifest.getPath());
        assertEquals(WorkspaceFileService.sha256("select 1\n"), manifest.getLastMaterializedSha256());
        assertEquals("select 1\n", Files.readString(tempDir.resolve("dbt/models/orders.sql")));
    }

    @Test
    void materializeArtifactSupportsRootLevelFiles() throws Exception {
        DeveloperWorkspaceRepository workspaceRepo = mock(DeveloperWorkspaceRepository.class);
        WorkspaceFileManifestRepository manifestRepo = mock(WorkspaceFileManifestRepository.class);
        WorkspaceFileService service = new WorkspaceFileService(workspaceRepo, manifestRepo);
        DeveloperWorkspace workspace = workspace();

        when(manifestRepo.findByWorkspaceIdAndPath("workspace-1", "dbt_project.yml"))
                .thenReturn(Optional.empty());
        when(manifestRepo.save(any(WorkspaceFileManifest.class))).thenAnswer(inv -> inv.getArgument(0));

        WorkspaceFileManifest manifest = service.materializeArtifact(
                workspace, artifact("dbt_project.yml", "name: pulse\n"));

        assertEquals("dbt_project.yml", manifest.getPath());
        assertEquals("name: pulse\n", Files.readString(tempDir.resolve("dbt_project.yml")));
    }

    @Test
    void updateRejectsUnmanifestedWrites() {
        DeveloperWorkspaceRepository workspaceRepo = mock(DeveloperWorkspaceRepository.class);
        WorkspaceFileManifestRepository manifestRepo = mock(WorkspaceFileManifestRepository.class);
        WorkspaceFileService service = new WorkspaceFileService(workspaceRepo, manifestRepo);

        when(workspaceRepo.findById("workspace-1")).thenReturn(Optional.of(workspace()));
        when(manifestRepo.findByWorkspaceIdAndPath("workspace-1", "dbt/models/orders.sql"))
                .thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.updateFile("workspace-1",
                        new WorkspaceDtos.WorkspaceFileUpdateRequest("dbt/models/orders.sql", "select 2\n")));
        assertEquals("UNMANIFESTED_WORKSPACE_WRITE", ex.getReason());
    }

    @Test
    void regenerationBlocksManualEdit() throws Exception {
        DeveloperWorkspaceRepository workspaceRepo = mock(DeveloperWorkspaceRepository.class);
        WorkspaceFileManifestRepository manifestRepo = mock(WorkspaceFileManifestRepository.class);
        WorkspaceFileService service = new WorkspaceFileService(workspaceRepo, manifestRepo);
        DeveloperWorkspace workspace = workspace();
        Path file = tempDir.resolve("dbt/models/orders.sql");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "manual edit\n");

        WorkspaceFileManifest manifest = new WorkspaceFileManifest();
        manifest.setWorkspaceId("workspace-1");
        manifest.setPath("dbt/models/orders.sql");
        manifest.setLastMaterializedSha256(WorkspaceFileService.sha256("old generated\n"));

        when(manifestRepo.findByWorkspaceIdAndPath("workspace-1", "dbt/models/orders.sql"))
                .thenReturn(Optional.of(manifest));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.materializeArtifact(workspace, artifact("dbt/models/orders.sql", "new generated\n")));
        assertEquals("REGENERATION_WOULD_OVERWRITE_MANUAL_EDIT", ex.getReason());
    }

    private DeveloperWorkspace workspace() {
        DeveloperWorkspace workspace = new DeveloperWorkspace();
        workspace.setId("workspace-1");
        workspace.setPipelineId("pipeline-1");
        workspace.setCheckoutPath(tempDir.toString());
        return workspace;
    }

    private static GeneratedArtifact artifact(String path, String content) {
        GeneratedArtifact artifact = new GeneratedArtifact();
        artifact.setId("artifact-1");
        artifact.setFilePath(path);
        artifact.setContent(content);
        return artifact;
    }
}
