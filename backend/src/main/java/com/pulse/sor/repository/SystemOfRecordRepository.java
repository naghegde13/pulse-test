package com.pulse.sor.repository;

import com.pulse.sor.model.SystemOfRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SystemOfRecordRepository extends JpaRepository<SystemOfRecord, String> {
    List<SystemOfRecord> findByTenantIdOrderByNameAsc(String tenantId);
    java.util.Optional<SystemOfRecord> findByTenantIdAndName(String tenantId, String name);

    @Query(
            value = "SELECT * FROM systems_of_record " +
                    "WHERE tenant_id = :tenantId " +
                    "AND metadata ->> 'registry_type' = 'TARGET' " +
                    "ORDER BY name ASC",
            nativeQuery = true
    )
    List<SystemOfRecord> findTargetsByTenantId(@Param("tenantId") String tenantId);
}
