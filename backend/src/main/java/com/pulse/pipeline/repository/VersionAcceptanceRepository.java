package com.pulse.pipeline.repository;

import com.pulse.pipeline.model.VersionAcceptance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface VersionAcceptanceRepository extends JpaRepository<VersionAcceptance, String> {
    Optional<VersionAcceptance> findFirstByVersionIdAndAcceptanceStatusOrderByCreatedAtDesc(
            String versionId, String acceptanceStatus);

    List<VersionAcceptance> findByVersionIdOrderByCreatedAtDesc(String versionId);

    List<VersionAcceptance> findByVersionIdAndAcceptanceStatus(String versionId, String acceptanceStatus);
}
