package com.pulse.cobol.repository;

import com.pulse.cobol.model.CobolDiscoverySession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CobolDiscoverySessionRepository extends JpaRepository<CobolDiscoverySession, String> {
    List<CobolDiscoverySession> findByTenantIdOrderByUpdatedAtDesc(String tenantId);
}
