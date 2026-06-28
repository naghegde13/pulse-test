package com.pulse.pipeline.repository;

import com.pulse.pipeline.model.DqValidationResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DqValidationResultRepository extends JpaRepository<DqValidationResult, String> {

    List<DqValidationResult> findByInstanceIdOrderByCreatedAtDesc(String instanceId);

    List<DqValidationResult> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    List<DqValidationResult> findByPipelineRunIdOrderByCreatedAtDesc(String pipelineRunId);

    List<DqValidationResult> findBySuiteNameOrderByCreatedAtDesc(String suiteName);

    @Query("SELECT d FROM DqValidationResult d WHERE d.instanceId IN " +
           "(SELECT s.id FROM SubPipelineInstance s WHERE s.versionId = :versionId) " +
           "ORDER BY d.createdAt DESC")
    List<DqValidationResult> findByVersionId(String versionId);
}
