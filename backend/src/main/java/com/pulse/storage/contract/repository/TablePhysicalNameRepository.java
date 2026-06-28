package com.pulse.storage.contract.repository;

import com.pulse.storage.contract.model.TablePhysicalName;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TablePhysicalNameRepository extends JpaRepository<TablePhysicalName, String> {

    Optional<TablePhysicalName> findByTenantIdAndCatalogKindAndSchemaNameAndPhysicalTableNameAndStatus(
            String tenantId, String catalogKind, String schemaName, String physicalTableName, String status);

    List<TablePhysicalName> findByTableContractIdAndStatus(String tableContractId, String status);

    List<TablePhysicalName> findByPipelineIdAndVersionIdAndStatus(
            String pipelineId, String versionId, String status);

    List<TablePhysicalName> findByTenantIdAndSchemaNameAndStatus(
            String tenantId, String schemaName, String status);
}
