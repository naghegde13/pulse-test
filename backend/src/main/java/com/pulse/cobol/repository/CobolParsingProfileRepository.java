package com.pulse.cobol.repository;

import com.pulse.cobol.model.CobolParsingProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CobolParsingProfileRepository extends JpaRepository<CobolParsingProfile, String> {
    List<CobolParsingProfile> findByTenantIdOrderByUpdatedAtDesc(String tenantId);
    Optional<CobolParsingProfile> findByTenantIdAndName(String tenantId, String name);
}
