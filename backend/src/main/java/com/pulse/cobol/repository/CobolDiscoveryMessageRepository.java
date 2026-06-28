package com.pulse.cobol.repository;

import com.pulse.cobol.model.CobolDiscoveryMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CobolDiscoveryMessageRepository extends JpaRepository<CobolDiscoveryMessage, String> {
    List<CobolDiscoveryMessage> findBySessionIdOrderByCreatedAtAsc(String sessionId);
}
