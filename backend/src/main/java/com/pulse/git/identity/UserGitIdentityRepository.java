package com.pulse.git.identity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserGitIdentityRepository extends JpaRepository<UserGitIdentity, String> {
    Optional<UserGitIdentity> findByTenantIdAndPulseUserIdAndProvider(
            String tenantId, String pulseUserId, String provider);
    List<UserGitIdentity> findByTenantIdOrderByCreatedAtDesc(String tenantId);
}
