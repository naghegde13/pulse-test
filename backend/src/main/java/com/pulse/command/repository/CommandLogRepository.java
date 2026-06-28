package com.pulse.command.repository;

import com.pulse.command.model.CommandLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CommandLogRepository extends JpaRepository<CommandLog, String> {

    List<CommandLog> findByPlanIdOrderByCreatedAtAsc(String planId);

    List<CommandLog> findByAggregateIdOrderByCreatedAtDesc(String aggregateId);

    List<CommandLog> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    Optional<CommandLog> findByIdempotencyKey(String idempotencyKey);
}
