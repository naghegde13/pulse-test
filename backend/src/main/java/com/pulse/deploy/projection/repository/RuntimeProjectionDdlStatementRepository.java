package com.pulse.deploy.projection.repository;

import com.pulse.deploy.projection.model.RuntimeProjectionDdlStatement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RuntimeProjectionDdlStatementRepository extends JpaRepository<RuntimeProjectionDdlStatement, String> {

    List<RuntimeProjectionDdlStatement> findByProjectionIdOrderByPhaseAsc(String projectionId);
}
