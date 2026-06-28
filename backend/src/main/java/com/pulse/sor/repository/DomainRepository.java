package com.pulse.sor.repository;

import com.pulse.sor.model.Domain;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DomainRepository extends JpaRepository<Domain, String> {
    List<Domain> findByTenantIdOrderByNameAsc(String tenantId);
    Optional<Domain> findByTenantIdAndName(String tenantId, String name);
    Optional<Domain> findByTenantIdAndSlug(String tenantId, String slug);
}
