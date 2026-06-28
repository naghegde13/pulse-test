package com.pulse.sor.repository;

import com.pulse.sor.model.Dataset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DatasetRepository extends JpaRepository<Dataset, String> {
    List<Dataset> findByTenantIdOrderByQualifiedNameAsc(String tenantId);
    List<Dataset> findByConnectorInstanceIdOrderByNameAsc(String connectorInstanceId);
    List<Dataset> findBySorIdOrderByNameAsc(String sorId);
    long countByConnectorInstanceId(String connectorInstanceId);
    long countBySorId(String sorId);
    boolean existsByQualifiedName(String qualifiedName);
    Optional<Dataset> findByQualifiedName(String qualifiedName);
}
