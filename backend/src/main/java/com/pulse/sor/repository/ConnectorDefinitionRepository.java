package com.pulse.sor.repository;

import com.pulse.sor.model.ConnectorDefinition;
import com.pulse.sor.model.ConnectorType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ConnectorDefinitionRepository extends JpaRepository<ConnectorDefinition, String> {
    List<ConnectorDefinition> findByConnectorTypeOrderByNameAsc(ConnectorType connectorType);
    List<ConnectorDefinition> findAllByOrderByConnectorTypeAscNameAsc();
    List<ConnectorDefinition> findByNameContainingIgnoreCaseOrderByNameAsc(String name);
}
