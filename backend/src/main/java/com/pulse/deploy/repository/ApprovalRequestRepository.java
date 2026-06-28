package com.pulse.deploy.repository;

import com.pulse.deploy.model.ApprovalRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ApprovalRequestRepository extends JpaRepository<ApprovalRequest, String> {
    List<ApprovalRequest> findByDeploymentIdOrderByCreatedAtDesc(String deploymentId);
    List<ApprovalRequest> findByTenantIdAndStatusOrderByCreatedAtDesc(String tenantId, String status);
}
