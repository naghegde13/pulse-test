package com.pulse.codegen.repository;

import com.pulse.codegen.model.GeneratedArtifact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GeneratedArtifactRepository extends JpaRepository<GeneratedArtifact, String> {
    List<GeneratedArtifact> findByGenerationRunIdOrderByFilePathAsc(String generationRunId);
    Optional<GeneratedArtifact> findByGenerationRunIdAndFilePath(String generationRunId, String filePath);
    List<GeneratedArtifact> findByGenerationRunIdAndManuallyModifiedTrue(String generationRunId);
}
