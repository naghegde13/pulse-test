package com.pulse.command.repository;

import com.pulse.command.model.Plan;
import com.pulse.command.model.PlanStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PlanRepository extends JpaRepository<Plan, String> {

    List<Plan> findByPipelineIdOrderByCreatedAtDesc(String pipelineId);

    List<Plan> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    List<Plan> findByTenantIdAndStatusOrderByCreatedAtDesc(String tenantId, PlanStatus status);
}
