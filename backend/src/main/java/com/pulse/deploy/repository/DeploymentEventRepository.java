package com.pulse.deploy.repository;

import com.pulse.deploy.model.DeploymentEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DeploymentEventRepository extends JpaRepository<DeploymentEvent, String> {
    List<DeploymentEvent> findByDeploymentRunIdOrderByCreatedAtAsc(String deploymentRunId);
    List<DeploymentEvent> findByDeploymentIdOrderByCreatedAtAsc(String deploymentId);
}
