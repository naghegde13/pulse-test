package com.pulse.codegen.repository;

import com.pulse.codegen.model.GenerationRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GenerationRunRepository extends JpaRepository<GenerationRun, String> {
    List<GenerationRun> findByVersionIdOrderByCreatedAtDesc(String versionId);
    List<GenerationRun> findByPipelineIdOrderByCreatedAtDesc(String pipelineId);
    Optional<GenerationRun> findTopByVersionIdOrderByCreatedAtDesc(String versionId);
}
