package com.pulse.pipeline.repository;

import com.pulse.pipeline.model.SubPipelineInstance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SubPipelineInstanceRepository extends JpaRepository<SubPipelineInstance, String> {

    List<SubPipelineInstance> findByPipelineIdOrderByExecutionOrderAsc(String pipelineId);

    List<SubPipelineInstance> findByVersionIdOrderByExecutionOrderAsc(String versionId);
}
