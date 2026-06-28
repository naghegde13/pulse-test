package com.pulse.deploy.repository;

import com.pulse.deploy.model.DeploymentTarget;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DeploymentTargetRepository extends JpaRepository<DeploymentTarget, String> {
    List<DeploymentTarget> findByTenantIdAndEnabledTrueOrderByEnvironmentAsc(String tenantId);
    List<DeploymentTarget> findByTenantIdOrderByEnvironmentAsc(String tenantId);
}
