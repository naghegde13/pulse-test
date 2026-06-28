package com.pulse.cobol.repository;

import com.pulse.cobol.model.CobolDiscoveryArtifact;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CobolDiscoveryArtifactRepository extends JpaRepository<CobolDiscoveryArtifact, String> {
    List<CobolDiscoveryArtifact> findBySessionIdOrderByCreatedAtAsc(String sessionId);
    List<CobolDiscoveryArtifact> findBySessionIdAndArtifactTypeAndCleanupStatusOrderByCreatedAtAsc(
            String sessionId, String artifactType, String cleanupStatus);
    Optional<CobolDiscoveryArtifact> findFirstBySessionIdAndArtifactTypeAndCleanupStatusOrderByCreatedAtDesc(
            String sessionId, String artifactType, String cleanupStatus);
    List<CobolDiscoveryArtifact> findByExpiresAtBeforeAndCleanupStatus(Instant expiresAt, String cleanupStatus);
}
