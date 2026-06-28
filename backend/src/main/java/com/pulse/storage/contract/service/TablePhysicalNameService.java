package com.pulse.storage.contract.service;

import com.pulse.storage.contract.model.TableContract;
import com.pulse.storage.contract.model.TablePhysicalName;
import com.pulse.storage.contract.repository.TablePhysicalNameRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Authoritative physical table name registry with conflict detection.
 *
 * <p>When a table contract is activated, this service registers its
 * physical table name in the {@code table_physical_names} table. The
 * unique index on (tenant_id, catalog_kind, schema_name,
 * physical_table_name) WHERE status='active' prevents two contracts
 * from claiming the same physical table name within a catalog.
 *
 * <p>An optional {@code shared_group} allows multiple contracts to
 * share a physical table name when they belong to the same logical
 * group (e.g. multi-output blueprints writing to a shared staging table).
 */
@Service
public class TablePhysicalNameService {

    private static final Logger log = LoggerFactory.getLogger(TablePhysicalNameService.class);

    private final TablePhysicalNameRepository physicalNameRepository;

    public TablePhysicalNameService(TablePhysicalNameRepository physicalNameRepository) {
        this.physicalNameRepository = physicalNameRepository;
    }

    /**
     * Register a physical table name for a table contract. Throws
     * {@link PhysicalTableNameConflictException} if the name is already
     * claimed by a different contract (unless both belong to the same
     * shared group).
     *
     * @param tenantId      tenant scope
     * @param contract      the table contract claiming the name
     * @param domainSlug    domain slug for provenance
     * @param sharedGroup   optional shared group ID (null if exclusive)
     * @return the registered physical name record
     */
    @Transactional
    public TablePhysicalName register(String tenantId, TableContract contract,
                                       String domainSlug, String sharedGroup) {
        String catalogKind = contract.getCatalogKind();
        String schemaName = contract.getSchemaName();
        String physicalName = contract.getTableName();

        if (catalogKind == null || schemaName == null || physicalName == null) {
            throw new IllegalArgumentException(
                    "Cannot register physical name: catalogKind, schemaName, and tableName are all required");
        }

        // Check for existing active registration
        Optional<TablePhysicalName> existing = physicalNameRepository
                .findByTenantIdAndCatalogKindAndSchemaNameAndPhysicalTableNameAndStatus(
                        tenantId, catalogKind, schemaName, physicalName, "active");

        if (existing.isPresent()) {
            TablePhysicalName prev = existing.get();
            // Same contract re-registering is idempotent
            if (contract.getId().equals(prev.getTableContractId())) {
                log.debug("Physical name already registered for same contract id={}; idempotent", contract.getId());
                return prev;
            }
            // Different contract but same shared group: return the existing
            // governed group claim rather than inserting a duplicate active
            // row (the DB unique index enforces single active ownership).
            if (sharedGroup != null && sharedGroup.equals(prev.getSharedGroup())) {
                log.info("Physical name {}.{} governed by shared group {}; "
                                + "contract {} joins existing claim by contract {}",
                        schemaName, physicalName, sharedGroup,
                        contract.getId(), prev.getTableContractId());
                return prev;
            }
            throw new PhysicalTableNameConflictException(
                    tenantId, catalogKind, schemaName, physicalName,
                    prev.getTableContractId(), contract.getId());
        }

        TablePhysicalName registration = new TablePhysicalName();
        registration.setTenantId(tenantId);
        registration.setTableContractId(contract.getId());
        registration.setCatalogKind(catalogKind);
        registration.setSchemaName(schemaName);
        registration.setPhysicalTableName(physicalName);
        registration.setLayer(contract.getLayer());
        registration.setDomainSlug(domainSlug);
        registration.setPipelineId(contract.getPipelineId());
        registration.setVersionId(contract.getVersionId());
        registration.setSharedGroup(sharedGroup);
        registration.setStatus("active");

        TablePhysicalName saved = physicalNameRepository.save(registration);
        log.info("Registered physical name {}.{}.{} for contract {} (shared_group={})",
                catalogKind, schemaName, physicalName, contract.getId(), sharedGroup);
        return saved;
    }

    /**
     * Retire all active physical name registrations for a table contract.
     * Called when a contract is marked stale or superseded.
     */
    @Transactional
    public void retireForContract(String tableContractId) {
        List<TablePhysicalName> active = physicalNameRepository
                .findByTableContractIdAndStatus(tableContractId, "active");
        for (TablePhysicalName name : active) {
            name.setStatus("retired");
            physicalNameRepository.save(name);
        }
        if (!active.isEmpty()) {
            log.info("Retired {} physical name registration(s) for contract {}",
                    active.size(), tableContractId);
        }
    }

    /**
     * Find active physical name registrations for a pipeline version.
     */
    public List<TablePhysicalName> findActiveForVersion(String pipelineId, String versionId) {
        return physicalNameRepository.findByPipelineIdAndVersionIdAndStatus(
                pipelineId, versionId, "active");
    }

    /**
     * Thrown when a physical table name is already claimed by a different
     * contract and the two contracts do not share a group.
     */
    public static class PhysicalTableNameConflictException extends RuntimeException {
        private final String tenantId;
        private final String catalogKind;
        private final String schemaName;
        private final String physicalTableName;
        private final String existingContractId;
        private final String requestingContractId;

        public PhysicalTableNameConflictException(String tenantId, String catalogKind,
                                                    String schemaName, String physicalTableName,
                                                    String existingContractId, String requestingContractId) {
            super("physical_table_name_conflict: " + catalogKind + "." + schemaName + "."
                    + physicalTableName + " is already claimed by contract " + existingContractId
                    + " (requested by " + requestingContractId + ")");
            this.tenantId = tenantId;
            this.catalogKind = catalogKind;
            this.schemaName = schemaName;
            this.physicalTableName = physicalTableName;
            this.existingContractId = existingContractId;
            this.requestingContractId = requestingContractId;
        }

        public String getTenantId() { return tenantId; }
        public String getCatalogKind() { return catalogKind; }
        public String getSchemaName() { return schemaName; }
        public String getPhysicalTableName() { return physicalTableName; }
        public String getExistingContractId() { return existingContractId; }
        public String getRequestingContractId() { return requestingContractId; }
    }
}
