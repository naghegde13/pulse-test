package com.pulse.deploy.repository;

import com.pulse.deploy.model.DeploymentEvidence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DeploymentEvidenceRepository extends JpaRepository<DeploymentEvidence, String> {
    List<DeploymentEvidence> findByDeploymentRunIdOrderByCreatedAtAsc(String deploymentRunId);
    List<DeploymentEvidence> findByDeploymentIdOrderByCreatedAtAsc(String deploymentId);
}
