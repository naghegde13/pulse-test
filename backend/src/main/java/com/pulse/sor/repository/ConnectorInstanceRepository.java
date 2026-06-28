package com.pulse.sor.repository;

import com.pulse.sor.model.ConnectorInstance;
import com.pulse.sor.model.ConnectorType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ConnectorInstanceRepository extends JpaRepository<ConnectorInstance, String> {
    List<ConnectorInstance> findBySorIdOrderByNameAsc(String sorId);

    @Query("""
            select ci from ConnectorInstance ci
            where ci.sorId = :sorId
            and ci.connectorDefinitionId in (
                select cd.id from ConnectorDefinition cd where cd.connectorType = :connectorType
            )
            order by ci.name asc
            """)
    List<ConnectorInstance> findBySorIdAndConnectorTypeOrderByNameAsc(String sorId, ConnectorType connectorType);

    @Query("""
            select ci from ConnectorInstance ci
            where ci.sorId in (
                select sor.id from SystemOfRecord sor where sor.tenantId = :tenantId
            )
            order by ci.name asc
            """)
    List<ConnectorInstance> findByTenantIdOrderByNameAsc(String tenantId);

    @Query("""
            select ci from ConnectorInstance ci
            where ci.sorId in (
                select sor.id from SystemOfRecord sor where sor.tenantId = :tenantId
            )
            and ci.connectorDefinitionId in (
                select cd.id from ConnectorDefinition cd where cd.connectorType = :connectorType
            )
            order by ci.name asc
            """)
    List<ConnectorInstance> findByTenantIdAndConnectorTypeOrderByNameAsc(String tenantId, ConnectorType connectorType);

    long countBySorId(String sorId);
}
