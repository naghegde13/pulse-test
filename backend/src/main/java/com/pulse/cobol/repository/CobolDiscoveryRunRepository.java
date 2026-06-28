package com.pulse.cobol.repository;

import com.pulse.cobol.model.CobolDiscoveryRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CobolDiscoveryRunRepository extends JpaRepository<CobolDiscoveryRun, String> {
    List<CobolDiscoveryRun> findBySessionIdOrderByCreatedAtDesc(String sessionId);
    Optional<CobolDiscoveryRun> findFirstBySessionIdAndStatusOrderByCreatedAtDesc(String sessionId, String status);
    List<CobolDiscoveryRun> findByExpiresAtBeforeAndCleanupStatus(Instant expiresAt, String cleanupStatus);
}
