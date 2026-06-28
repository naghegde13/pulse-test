package com.pulse.git.workspace;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DeveloperWorkspaceRepository extends JpaRepository<DeveloperWorkspace, String> {
    Optional<DeveloperWorkspace> findFirstByTenantIdAndVersionIdAndActorUserIdAndLifecycleStatusOrderByCreatedAtDesc(
            String tenantId, String versionId, String actorUserId, String lifecycleStatus);

    Optional<DeveloperWorkspace> findFirstByGitRepoIdAndBranchNameAndLifecycleStatus(
            String gitRepoId, String branchName, String lifecycleStatus);

    List<DeveloperWorkspace> findByVersionIdOrderByCreatedAtDesc(String versionId);
}
