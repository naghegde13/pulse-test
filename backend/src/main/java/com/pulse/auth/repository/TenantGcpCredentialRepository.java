package com.pulse.auth.repository;

import com.pulse.auth.model.TenantGcpCredential;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TenantGcpCredentialRepository extends JpaRepository<TenantGcpCredential, String> {

    Optional<TenantGcpCredential> findByTenantId(String tenantId);

    boolean existsByTenantId(String tenantId);
}
