package com.pulse.deploy.repository;

import com.pulse.deploy.model.Package;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PackageRepository extends JpaRepository<Package, String> {
    List<Package> findByVersionIdOrderByCreatedAtDesc(String versionId);
    List<Package> findByPipelineIdOrderByCreatedAtDesc(String pipelineId);
    List<Package> findByWorkspaceIdOrderByCreatedAtDesc(String workspaceId);
    List<Package> findByVersionIdAndPromotableTrueOrderByCreatedAtDesc(String versionId);
}
