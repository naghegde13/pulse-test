package com.pulse.tenant.repository;

import com.pulse.tenant.model.TenantGcpRuntimeTopology;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TenantGcpRuntimeTopologyRepository extends JpaRepository<TenantGcpRuntimeTopology, String> {

    Optional<TenantGcpRuntimeTopology> findByTenantId(String tenantId);
}
