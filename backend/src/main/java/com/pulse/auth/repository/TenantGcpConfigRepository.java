package com.pulse.auth.repository;

import com.pulse.auth.model.TenantGcpConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TenantGcpConfigRepository extends JpaRepository<TenantGcpConfig, String> {

    Optional<TenantGcpConfig> findByTenantId(String tenantId);

    boolean existsByTenantId(String tenantId);
}
