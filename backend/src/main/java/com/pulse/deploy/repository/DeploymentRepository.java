package com.pulse.deploy.repository;

import com.pulse.deploy.model.Deployment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DeploymentRepository extends JpaRepository<Deployment, String> {
    List<Deployment> findByPipelineIdOrderByCreatedAtDesc(String pipelineId);
    List<Deployment> findByTenantIdOrderByCreatedAtDesc(String tenantId);
    List<Deployment> findByTargetIdAndStatusOrderByCreatedAtDesc(String targetId, String status);
}
