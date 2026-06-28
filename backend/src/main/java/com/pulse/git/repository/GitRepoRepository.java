package com.pulse.git.repository;

import com.pulse.git.model.GitRepo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GitRepoRepository extends JpaRepository<GitRepo, String> {
    Optional<GitRepo> findByPipelineId(String pipelineId);
    Optional<GitRepo> findFirstByDomainIdOrderByCreatedAtDesc(String domainId);
    List<GitRepo> findByTenantIdOrderByCreatedAtDesc(String tenantId);
    Optional<GitRepo> findByTenantIdAndScope(String tenantId, String scope);
    List<GitRepo> findByTenantIdAndScopeOrderByCreatedAtDesc(String tenantId, String scope);
}
