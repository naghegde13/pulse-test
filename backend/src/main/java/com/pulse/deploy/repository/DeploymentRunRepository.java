package com.pulse.deploy.repository;

import com.pulse.deploy.model.DeploymentRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DeploymentRunRepository extends JpaRepository<DeploymentRun, String> {
    /**
     * Phase 4 idempotency lookup. Returns any run created with the given
     * (deployment, idempotency key) pair. If body hash also matches the
     * stored value, the caller is replaying the same request and should
     * receive the same run back; if the hash differs, the caller is
     * trying to reuse the key with a different request body and must
     * be rejected with {@code 409 idempotency_body_mismatch}.
     */
    Optional<DeploymentRun> findByDeploymentIdAndIdempotencyKey(String deploymentId, String idempotencyKey);

    List<DeploymentRun> findByDeploymentIdOrderByCreatedAtDesc(String deploymentId);
}
