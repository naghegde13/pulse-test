package com.pulse.git.workspace;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WorkspaceFileManifestRepository extends JpaRepository<WorkspaceFileManifest, String> {
    List<WorkspaceFileManifest> findByWorkspaceIdOrderByPathAsc(String workspaceId);
    Optional<WorkspaceFileManifest> findByWorkspaceIdAndPath(String workspaceId, String path);
}
