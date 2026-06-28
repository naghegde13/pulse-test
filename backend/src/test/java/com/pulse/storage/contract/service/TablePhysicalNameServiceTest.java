package com.pulse.storage.contract.service;

import com.pulse.storage.contract.model.TableContract;
import com.pulse.storage.contract.model.TablePhysicalName;
import com.pulse.storage.contract.repository.TablePhysicalNameRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TablePhysicalNameServiceTest {

    @Mock
    private TablePhysicalNameRepository physicalNameRepository;

    @InjectMocks
    private TablePhysicalNameService physicalNameService;

    // ------------------------------------------------------------------ registration

    @Test
    @DisplayName("register creates physical name entry for new table")
    void register_createsEntryForNewTable() {
        TableContract contract = buildContract("tc-001", "HIVE", "finance_bronze",
                "salesforce_accounts", "bronze", "pip-001", "ver-001");

        when(physicalNameRepository.findByTenantIdAndCatalogKindAndSchemaNameAndPhysicalTableNameAndStatus(
                "tenant-1", "HIVE", "finance_bronze", "salesforce_accounts", "active"))
                .thenReturn(Optional.empty());
        when(physicalNameRepository.save(any(TablePhysicalName.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        TablePhysicalName result = physicalNameService.register(
                "tenant-1", contract, "finance", null);

        assertNotNull(result);
        assertEquals("tenant-1", result.getTenantId());
        assertEquals("tc-001", result.getTableContractId());
        assertEquals("HIVE", result.getCatalogKind());
        assertEquals("finance_bronze", result.getSchemaName());
        assertEquals("salesforce_accounts", result.getPhysicalTableName());
        assertEquals("bronze", result.getLayer());
        assertEquals("finance", result.getDomainSlug());
        assertEquals("active", result.getStatus());
    }

    @Test
    @DisplayName("register is idempotent for same contract")
    void register_idempotentForSameContract() {
        TableContract contract = buildContract("tc-001", "HIVE", "finance_bronze",
                "salesforce_accounts", "bronze", "pip-001", "ver-001");

        TablePhysicalName existing = new TablePhysicalName();
        existing.setId("tpn-existing");
        existing.setTableContractId("tc-001");

        when(physicalNameRepository.findByTenantIdAndCatalogKindAndSchemaNameAndPhysicalTableNameAndStatus(
                "tenant-1", "HIVE", "finance_bronze", "salesforce_accounts", "active"))
                .thenReturn(Optional.of(existing));

        TablePhysicalName result = physicalNameService.register(
                "tenant-1", contract, "finance", null);

        assertEquals("tpn-existing", result.getId());
        verify(physicalNameRepository, never()).save(any());
    }

    // ------------------------------------------------------------------ conflict detection

    @Test
    @DisplayName("register throws PhysicalTableNameConflictException on conflict")
    void register_throwsOnConflict() {
        TableContract contract = buildContract("tc-002", "HIVE", "finance_bronze",
                "salesforce_accounts", "bronze", "pip-002", "ver-002");

        TablePhysicalName existing = new TablePhysicalName();
        existing.setId("tpn-existing");
        existing.setTableContractId("tc-001"); // Different contract!

        when(physicalNameRepository.findByTenantIdAndCatalogKindAndSchemaNameAndPhysicalTableNameAndStatus(
                "tenant-1", "HIVE", "finance_bronze", "salesforce_accounts", "active"))
                .thenReturn(Optional.of(existing));

        TablePhysicalNameService.PhysicalTableNameConflictException ex =
                assertThrows(TablePhysicalNameService.PhysicalTableNameConflictException.class, () ->
                        physicalNameService.register("tenant-1", contract, "finance", null));

        assertTrue(ex.getMessage().contains("physical_table_name_conflict"));
        assertEquals("tc-001", ex.getExistingContractId());
        assertEquals("tc-002", ex.getRequestingContractId());
    }

    // ------------------------------------------------------------------ shared group

    @Test
    @DisplayName("register returns existing claim for shared group — no duplicate save")
    void register_returnsExistingForSharedGroup() {
        TableContract contract = buildContract("tc-002", "HIVE", "finance_bronze",
                "shared_staging", "bronze", "pip-002", "ver-002");

        TablePhysicalName existing = new TablePhysicalName();
        existing.setId("tpn-existing");
        existing.setTableContractId("tc-001");
        existing.setSharedGroup("staging-group-1");

        when(physicalNameRepository.findByTenantIdAndCatalogKindAndSchemaNameAndPhysicalTableNameAndStatus(
                "tenant-1", "HIVE", "finance_bronze", "shared_staging", "active"))
                .thenReturn(Optional.of(existing));

        TablePhysicalName result = physicalNameService.register(
                "tenant-1", contract, "finance", "staging-group-1");

        // Returns the existing governed claim, not a new row
        assertEquals("tpn-existing", result.getId());
        assertEquals("tc-001", result.getTableContractId());
        // No save must be called — the existing row governs the group
        verify(physicalNameRepository, never()).save(any());
    }

    // ------------------------------------------------------------------ retirement

    @Test
    @DisplayName("retireForContract marks active registrations as retired")
    void retireForContract_marksAsRetired() {
        TablePhysicalName name1 = new TablePhysicalName();
        name1.setId("tpn-1");
        name1.setStatus("active");
        TablePhysicalName name2 = new TablePhysicalName();
        name2.setId("tpn-2");
        name2.setStatus("active");

        when(physicalNameRepository.findByTableContractIdAndStatus("tc-001", "active"))
                .thenReturn(List.of(name1, name2));
        when(physicalNameRepository.save(any(TablePhysicalName.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        physicalNameService.retireForContract("tc-001");

        assertEquals("retired", name1.getStatus());
        assertEquals("retired", name2.getStatus());
        verify(physicalNameRepository, times(2)).save(any());
    }

    // ------------------------------------------------------------------ helpers

    private TableContract buildContract(String id, String catalogKind, String schemaName,
                                         String tableName, String layer,
                                         String pipelineId, String versionId) {
        TableContract contract = new TableContract();
        contract.setId(id);
        contract.setCatalogKind(catalogKind);
        contract.setSchemaName(schemaName);
        contract.setTableName(tableName);
        contract.setLayer(layer);
        contract.setPipelineId(pipelineId);
        contract.setVersionId(versionId);
        return contract;
    }
}
