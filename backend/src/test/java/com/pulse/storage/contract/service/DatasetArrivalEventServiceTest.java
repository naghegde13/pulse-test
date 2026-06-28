package com.pulse.storage.contract.service;

import com.pulse.storage.contract.model.DatasetArrivalEvent;
import com.pulse.storage.contract.model.DatasetLandingContract;
import com.pulse.storage.contract.repository.DatasetArrivalEventRepository;
import com.pulse.storage.contract.repository.DatasetLandingContractRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DatasetArrivalEventServiceTest {

    @Mock
    private DatasetArrivalEventRepository arrivalEventRepository;

    @Mock
    private DatasetLandingContractRepository landingContractRepository;

    @InjectMocks
    private DatasetArrivalEventService arrivalEventService;

    // ------------------------------------------------------------------ record arrival

    @Test
    @DisplayName("recordArrival creates event and sets first arrival on contract")
    void recordArrival_createsEventAndSetsFirstArrival() {
        DatasetLandingContract contract = new DatasetLandingContract();
        contract.setId("lc-001");
        contract.setContractVersion(1);
        contract.setRelativeLandingPath("finance/salesforce/accounts/landing/ingest_date={ingest_date}/arrival_id={arrival_id}/");

        when(landingContractRepository.findByDatasetIdAndStatus("ds-001", "active"))
                .thenReturn(Optional.of(contract));
        when(arrivalEventRepository.findByLandingContractIdAndIngestDateAndArrivalId(
                "lc-001", "2026-05-25", "arr-001"))
                .thenReturn(Optional.empty());
        when(arrivalEventRepository.save(any(DatasetArrivalEvent.class)))
                .thenAnswer(inv -> {
                    DatasetArrivalEvent event = inv.getArgument(0);
                    event.setId("evt-001");
                    return event;
                });
        when(landingContractRepository.save(any(DatasetLandingContract.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        DatasetArrivalEvent result = arrivalEventService.recordArrival(
                "tenant-1", "ds-001", "2026-05-25", "arr-001",
                "finance/salesforce/accounts/landing/ingest_date=2026-05-25/arrival_id=arr-001/",
                5, 1024000, "salesforce");

        assertNotNull(result);
        assertEquals("tenant-1", result.getTenantId());
        assertEquals("ds-001", result.getDatasetId());
        assertEquals("lc-001", result.getLandingContractId());
        assertEquals(1, result.getContractVersion());
        assertEquals("2026-05-25", result.getIngestDate());
        assertEquals("arr-001", result.getArrivalId());
        assertEquals(5, result.getFileCount());
        assertEquals(1024000, result.getTotalBytes());
        assertEquals("salesforce", result.getSourceSystem());
        assertEquals("recorded", result.getStatus());

        // First arrival should have been set on the contract
        assertNotNull(contract.getFirstArrivalAt());
        assertEquals("evt-001", contract.getFirstArrivalEventId());
        verify(landingContractRepository).save(contract);
    }

    @Test
    @DisplayName("recordArrival returns existing on duplicate")
    void recordArrival_returnsExistingOnDuplicate() {
        DatasetLandingContract contract = new DatasetLandingContract();
        contract.setId("lc-001");
        contract.setContractVersion(1);
        contract.setFirstArrivalAt(Instant.now());

        DatasetArrivalEvent existing = new DatasetArrivalEvent();
        existing.setId("evt-existing");

        when(landingContractRepository.findByDatasetIdAndStatus("ds-001", "active"))
                .thenReturn(Optional.of(contract));
        when(arrivalEventRepository.findByLandingContractIdAndIngestDateAndArrivalId(
                "lc-001", "2026-05-25", "arr-001"))
                .thenReturn(Optional.of(existing));

        DatasetArrivalEvent result = arrivalEventService.recordArrival(
                "tenant-1", "ds-001", "2026-05-25", "arr-001",
                "some/path", 1, 100, null);

        assertEquals("evt-existing", result.getId());
        verify(arrivalEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("recordArrival throws when no active contract")
    void recordArrival_throwsWhenNoActiveContract() {
        when(landingContractRepository.findByDatasetIdAndStatus("ds-001", "active"))
                .thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () ->
                arrivalEventService.recordArrival(
                        "tenant-1", "ds-001", "2026-05-25", "arr-001",
                        "path", 1, 100, null));
    }

    @Test
    @DisplayName("recordArrival does not set firstArrival on subsequent events")
    void recordArrival_doesNotSetFirstArrivalOnSubsequentEvents() {
        DatasetLandingContract contract = new DatasetLandingContract();
        contract.setId("lc-001");
        contract.setContractVersion(1);
        contract.setFirstArrivalAt(Instant.now());
        contract.setFirstArrivalEventId("evt-first");
        contract.setRelativeLandingPath("finance/salesforce/accounts/landing/ingest_date={ingest_date}/arrival_id={arrival_id}/");

        when(landingContractRepository.findByDatasetIdAndStatus("ds-001", "active"))
                .thenReturn(Optional.of(contract));
        when(arrivalEventRepository.findByLandingContractIdAndIngestDateAndArrivalId(
                "lc-001", "2026-05-26", "arr-002"))
                .thenReturn(Optional.empty());
        when(arrivalEventRepository.save(any(DatasetArrivalEvent.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        arrivalEventService.recordArrival(
                "tenant-1", "ds-001", "2026-05-26", "arr-002",
                "finance/salesforce/accounts/landing/ingest_date=2026-05-26/arrival_id=arr-002/",
                3, 500, null);

        // Should NOT update the contract since first arrival was already set
        verify(landingContractRepository, never()).save(any());
    }

    // ------------------------------------------------------------------ queries

    @Test
    @DisplayName("findByDataset delegates to repository")
    void findByDataset_delegatesToRepository() {
        DatasetArrivalEvent evt = new DatasetArrivalEvent();
        evt.setId("evt-100");
        when(arrivalEventRepository.findByDatasetIdOrderByCreatedAtDesc("ds-010"))
                .thenReturn(List.of(evt));

        List<DatasetArrivalEvent> result = arrivalEventService.findByDataset("ds-010");
        assertEquals(1, result.size());
        assertEquals("evt-100", result.get(0).getId());
    }

    @Test
    @DisplayName("isPathImmutable returns true when first arrival exists")
    void isPathImmutable_returnsTrue() {
        DatasetArrivalEvent evt = new DatasetArrivalEvent();
        when(arrivalEventRepository.findFirstByLandingContractIdOrderByCreatedAtAsc("lc-001"))
                .thenReturn(Optional.of(evt));

        assertTrue(arrivalEventService.isPathImmutable("lc-001"));
    }

    @Test
    @DisplayName("isPathImmutable returns false when no arrivals")
    void isPathImmutable_returnsFalse() {
        when(arrivalEventRepository.findFirstByLandingContractIdOrderByCreatedAtAsc("lc-001"))
                .thenReturn(Optional.empty());

        assertFalse(arrivalEventService.isPathImmutable("lc-001"));
    }
}
