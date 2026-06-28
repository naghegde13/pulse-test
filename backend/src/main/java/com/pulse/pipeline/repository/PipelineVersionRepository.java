package com.pulse.pipeline.repository;

import com.pulse.pipeline.model.PipelineVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PipelineVersionRepository extends JpaRepository<PipelineVersion, String> {

    List<PipelineVersion> findByPipelineIdOrderByCreatedAtDesc(String pipelineId);

    Optional<PipelineVersion> findFirstByPipelineIdOrderByCreatedAtDesc(String pipelineId);

    Optional<PipelineVersion> findByPipelineIdAndRevision(String pipelineId, int revision);

    long countByPipelineId(String pipelineId);
}
