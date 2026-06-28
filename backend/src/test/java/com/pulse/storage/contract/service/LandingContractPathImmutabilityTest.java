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

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LandingContractPathImmutabilityTest {

    @Mock
    private DatasetLandingContractRepository landingContractRepository;

    @Mock
    private DatasetArrivalEventRepository arrivalEventRepository;

    @InjectMocks
    private DatasetLandingContractService landingContractService;

    @Test
    @DisplayName("generateLandingContract blocks when slugs differ after first arrival")
    void generateLandingContract_blocksOnSlugChangeAfterFirstArrival() {
        DatasetLandingContract existing = new DatasetLandingContract();
        existing.setId("lc-001");
        existing.setStatus("active");
        existing.setContractVersion(1);
        existing.setDomainSlug("finance");
        existing.setSorSlug("salesforce");
        existing.setDatasetSlug("accounts");
        existing.setRelativeLandingPath("finance/salesforce/accounts/landing/ingest_date={ingest_date}/arrival_id={arrival_id}/");
        existing.setFirstArrivalAt(Instant.now()); // Path is now frozen

        when(landingContractRepository.findByDatasetIdAndStatus("ds-001", "active"))
                .thenReturn(Optional.of(existing));

        // Attempt to regenerate with DIFFERENT slugs
        DatasetLandingContractService.LandingPathImmutableException ex =
                assertThrows(DatasetLandingContractService.LandingPathImmutableException.class, () ->
                        landingContractService.generateLandingContract(
                                "tenant-1", "domain-001", "finance", "sor-001",
                                "workday",  // Different SOR slug!
                                "ds-001", "accounts"));

        assertTrue(ex.getMessage().contains("immutable"));
        assertEquals("lc-001", ex.getContractId());
        verify(landingContractRepository, never()).save(any());
    }

    @Test
    @DisplayName("generateLandingContract allows regeneration with same slugs after first arrival")
    void generateLandingContract_allowsSameSlugAfterFirstArrival() {
        DatasetLandingContract existing = new DatasetLandingContract();
        existing.setId("lc-001");
        existing.setStatus("active");
        existing.setContractVersion(1);
        existing.setDomainSlug("finance");
        existing.setSorSlug("salesforce");
        existing.setDatasetSlug("accounts");
        existing.setRelativeLandingPath("finance/salesforce/accounts/landing/ingest_date={ingest_date}/arrival_id={arrival_id}/");
        existing.setFirstArrivalAt(Instant.now());

        when(landingContractRepository.findByDatasetIdAndStatus("ds-001", "active"))
                .thenReturn(Optional.of(existing));
        when(landingContractRepository.save(any(DatasetLandingContract.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // Same slugs = allowed even after first arrival
        DatasetLandingContract result = landingContractService.generateLandingContract(
                "tenant-1", "domain-001", "finance", "sor-001", "salesforce",
                "ds-001", "accounts");

        assertNotNull(result);
        assertEquals(2, result.getContractVersion());
        assertEquals("superseded", existing.getStatus());
    }

    @Test
    @DisplayName("generateLandingContract allows different slugs when no arrival yet")
    void generateLandingContract_allowsDifferentSlugsBeforeFirstArrival() {
        DatasetLandingContract existing = new DatasetLandingContract();
        existing.setId("lc-001");
        existing.setStatus("active");
        existing.setContractVersion(1);
        existing.setDomainSlug("finance");
        existing.setSorSlug("old_sor");
        existing.setDatasetSlug("accounts");
        // No firstArrivalAt — path not frozen yet

        when(landingContractRepository.findByDatasetIdAndStatus("ds-001", "active"))
                .thenReturn(Optional.of(existing));
        when(landingContractRepository.save(any(DatasetLandingContract.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // Different slugs = allowed before first arrival
        DatasetLandingContract result = landingContractService.generateLandingContract(
                "tenant-1", "domain-001", "finance", "sor-001", "new_sor",
                "ds-001", "accounts");

        assertNotNull(result);
        assertEquals("new_sor", result.getSorSlug());
    }

    @Test
    @DisplayName("isPathImmutable returns true when contract has first arrival")
    void isPathImmutable_trueWhenContractHasFirstArrival() {
        DatasetLandingContract contract = new DatasetLandingContract();
        contract.setFirstArrivalAt(Instant.now());
        when(landingContractRepository.findByDatasetIdAndStatus("ds-001", "active"))
                .thenReturn(Optional.of(contract));

        assertTrue(landingContractService.isPathImmutable("ds-001"));
    }

    @Test
    @DisplayName("isPathImmutable returns false when no active contract")
    void isPathImmutable_falseWhenNoContract() {
        when(landingContractRepository.findByDatasetIdAndStatus("ds-001", "active"))
                .thenReturn(Optional.empty());

        assertFalse(landingContractService.isPathImmutable("ds-001"));
    }
}
