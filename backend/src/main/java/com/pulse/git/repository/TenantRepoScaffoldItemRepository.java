package com.pulse.git.repository;

import com.pulse.git.model.TenantRepoScaffoldItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TenantRepoScaffoldItemRepository extends JpaRepository<TenantRepoScaffoldItem, String> {
    List<TenantRepoScaffoldItem> findByGitRepoIdAndBranchNameOrderByItemTypeAscDomainSlugAsc(
            String gitRepoId, String branchName);

    Optional<TenantRepoScaffoldItem> findByGitRepoIdAndBranchNameAndItemTypeAndDomainIdIsNull(
            String gitRepoId, String branchName, String itemType);

    Optional<TenantRepoScaffoldItem> findByGitRepoIdAndBranchNameAndDomainId(
            String gitRepoId, String branchName, String domainId);
}
