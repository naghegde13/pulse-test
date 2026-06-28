package com.pulse.storage.repository;

import com.pulse.storage.model.StorageBackend;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StorageBackendRepository extends JpaRepository<StorageBackend, String> {

    /** Look up the storage backend for a (tenant, environment, backend) tuple.
     * This is the canonical lookup at codegen / deploy time — one row per
     * tuple is enforced by V96's UNIQUE constraint. */
    Optional<StorageBackend> findByTenantIdAndEnvironmentAndBackend(
            String tenantId, String environment, String backend);

    List<StorageBackend> findByTenantId(String tenantId);

    List<StorageBackend> findByTenantIdAndEnvironment(String tenantId, String environment);
}
