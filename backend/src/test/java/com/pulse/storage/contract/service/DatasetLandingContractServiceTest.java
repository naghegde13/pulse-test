package com.pulse.storage.contract.service;

import com.pulse.storage.contract.model.DatasetLandingContract;
import com.pulse.storage.contract.repository.DatasetArrivalEventRepository;
import com.pulse.storage.contract.repository.DatasetLandingContractRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DatasetLandingContractServiceTest {

    @Mock
    private DatasetLandingContractRepository landingContractRepository;

    @Mock
    private DatasetArrivalEventRepository arrivalEventRepository;

    @InjectMocks
    private DatasetLandingContractService datasetLandingContractService;

    // ------------------------------------------------------------------ generation

    @Test
    @DisplayName("generateLandingContract creates with correct paths")
    void generateLandingContract_createsWithCorrectPaths() {
        when(landingContractRepository.findByDatasetIdAndStatus("ds-001", "active"))
                .thenReturn(Optional.empty());
        when(landingContractRepository.save(any(DatasetLandingContract.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        DatasetLandingContract result = datasetLandingContractService.generateLandingContract(
                "tenant-1", "domain-001", "finance", "sor-001", "salesforce",
                "ds-001", "accounts");

        assertNotNull(result);
        assertEquals("finance/salesforce/accounts/landing/ingest_date={ingest_date}/arrival_id={arrival_id}/",
                result.getRelativeLandingPath());
        assertEquals("finance/salesforce/accounts/rejected/", result.getRejectedRelativePath());
        assertEquals("finance/salesforce/accounts/archive/", result.getArchiveRelativePath());
        assertEquals("finance/salesforce/accounts/outgoing/", result.getOutgoingRelativePath());
        assertEquals("active", result.getStatus());
        assertEquals(1, result.getContractVersion());
        assertEquals("files", result.getRootKind());
        assertEquals("tenant-1", result.getTenantId());
        assertEquals("domain-001", result.getDomainId());
        assertEquals("finance", result.getDomainSlug());
        assertEquals("sor-001", result.getSorId());
        assertEquals("salesforce", result.getSorSlug());
        assertEquals("ds-001", result.getDatasetId());
        assertEquals("accounts", result.getDatasetSlug());
    }

    // ------------------------------------------------------------------ supersede

    @Test
    @DisplayName("generateLandingContract supersedes previous active contract")
    void generateLandingContract_supersedesPreviousActive() {
        DatasetLandingContract existing = new DatasetLandingContract();
        existing.setId("existing-lc-1");
        existing.setStatus("active");
        existing.setContractVersion(2);
        existing.setDatasetId("ds-001");

        when(landingContractRepository.findByDatasetIdAndStatus("ds-001", "active"))
                .thenReturn(Optional.of(existing));
        when(landingContractRepository.save(any(DatasetLandingContract.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        DatasetLandingContract result = datasetLandingContractService.generateLandingContract(
                "tenant-1", "domain-001", "finance", "sor-001", "salesforce",
                "ds-001", "accounts");

        assertEquals("superseded", existing.getStatus());
        assertEquals(3, result.getContractVersion());
        assertEquals("active", result.getStatus());
    }

    // ------------------------------------------------------------------ partition template

    @Test
    @DisplayName("generateLandingContract sets correct arrival partition template")
    void generateLandingContract_setsArrivalPartitionTemplate() {
        when(landingContractRepository.findByDatasetIdAndStatus("ds-002", "active"))
                .thenReturn(Optional.empty());
        when(landingContractRepository.save(any(DatasetLandingContract.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        DatasetLandingContract result = datasetLandingContractService.generateLandingContract(
                "tenant-1", "domain-001", "finance", "sor-001", "erp",
                "ds-002", "orders");

        assertEquals("ingest_date={ingest_date}/arrival_id={arrival_id}",
                result.getArrivalPartitionTemplate());
    }

    // ------------------------------------------------------------------ delegation

    @Test
    @DisplayName("findActiveContract delegates to repository")
    void findActiveContract_delegatesToRepository() {
        DatasetLandingContract contract = new DatasetLandingContract();
        contract.setId("lc-100");
        contract.setDatasetId("ds-010");
        contract.setStatus("active");

        when(landingContractRepository.findByDatasetIdAndStatus("ds-010", "active"))
                .thenReturn(Optional.of(contract));

        Optional<DatasetLandingContract> result = datasetLandingContractService
                .findActiveContract("ds-010");

        assertTrue(result.isPresent());
        assertEquals("lc-100", result.get().getId());
        verify(landingContractRepository).findByDatasetIdAndStatus("ds-010", "active");
    }

    // ------------------------------------------------------------------ provenance

    @Test
    @DisplayName("generateLandingContract sets provenance with source info")
    void generateLandingContract_setsProvenance() {
        when(landingContractRepository.findByDatasetIdAndStatus("ds-003", "active"))
                .thenReturn(Optional.empty());
        when(landingContractRepository.save(any(DatasetLandingContract.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        DatasetLandingContract result = datasetLandingContractService.generateLandingContract(
                "tenant-1", "domain-001", "finance", "sor-001", "salesforce",
                "ds-003", "contacts");

        assertNotNull(result.getProvenance());
        assertEquals("contract_generation", result.getProvenance().get("source"));
        assertEquals("finance", result.getProvenance().get("domainSlug"));
        assertEquals("salesforce", result.getProvenance().get("sorSlug"));
        assertEquals("contacts", result.getProvenance().get("datasetSlug"));
    }
}
