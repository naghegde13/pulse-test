package com.pulse.sor.service;

import com.pulse.sor.model.AsofAdvanceLog;
import com.pulse.sor.model.Dataset;
import com.pulse.sor.repository.AsofAdvanceLogRepository;
import com.pulse.sor.repository.DatasetRepository;
import com.pulse.sor.repository.DomainAdvanceLogRepository;
import com.pulse.sor.repository.DomainRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TimeDimensionServiceTest {

    @Mock private DatasetRepository datasetRepo;
    @Mock private DomainRepository domainRepo;
    @Mock private AsofAdvanceLogRepository asofLogRepo;
    @Mock private DomainAdvanceLogRepository domainLogRepo;

    @Test
    void advanceDatasetRejectsBackwardRequestedAsofWithoutMutatingStateOrWritingAuditLog() {
        Dataset dataset = dataset("loan-master", "2026-04-30T04:00:00Z", "DAILY", "America/New_York", Map.of());
        when(datasetRepo.findById("loan-master")).thenReturn(Optional.of(dataset));

        TimeDimensionService service = new TimeDimensionService(datasetRepo, domainRepo, asofLogRepo, domainLogRepo);

        assertThrows(IllegalArgumentException.class,
                () -> service.advanceDataset("loan-master", "airflow", "semantic-proof", "backward", "2026-04-29"));

        assertEquals(Instant.parse("2026-04-30T04:00:00Z"), dataset.getCurrentAsof());
        verify(datasetRepo, org.mockito.Mockito.never()).save(any());
        ArgumentCaptor<AsofAdvanceLog> log = ArgumentCaptor.forClass(AsofAdvanceLog.class);
        verify(asofLogRepo).save(log.capture());
        assertEquals("REJECTED", log.getValue().getAdvanceStatus());
        assertEquals(Instant.parse("2026-04-29T04:00:00Z"), log.getValue().getRequestedAsof());
    }

    @Test
    void advanceDatasetRejectsDuplicateRequestedAsofWithoutMutatingStateOrWritingAuditLog() {
        Dataset dataset = dataset("loan-master", "2026-04-30T04:00:00Z", "DAILY", "America/New_York", Map.of());
        when(datasetRepo.findById("loan-master")).thenReturn(Optional.of(dataset));

        TimeDimensionService service = new TimeDimensionService(datasetRepo, domainRepo, asofLogRepo, domainLogRepo);

        assertThrows(IllegalArgumentException.class,
                () -> service.advanceDataset("loan-master", "airflow", "semantic-proof", "duplicate", "2026-04-30"));

        assertEquals(Instant.parse("2026-04-30T04:00:00Z"), dataset.getCurrentAsof());
        verify(datasetRepo, org.mockito.Mockito.never()).save(any());
        ArgumentCaptor<AsofAdvanceLog> log = ArgumentCaptor.forClass(AsofAdvanceLog.class);
        verify(asofLogRepo).save(log.capture());
        assertEquals("REJECTED", log.getValue().getAdvanceStatus());
        assertEquals(Instant.parse("2026-04-30T04:00:00Z"), log.getValue().getRequestedAsof());
    }

    @Test
    void advanceDatasetHandlesDstBoundaryAndWritesAuditLogForForwardAdvance() {
        Dataset dataset = dataset("loan-master", "2026-03-07T05:00:00Z", "DAILY", "America/New_York", Map.of());
        when(datasetRepo.findById("loan-master")).thenReturn(Optional.of(dataset));
        when(datasetRepo.save(dataset)).thenReturn(dataset);

        TimeDimensionService service = new TimeDimensionService(datasetRepo, domainRepo, asofLogRepo, domainLogRepo);
        service.advanceDataset("loan-master", "airflow", "semantic-proof", "dst boundary");

        assertEquals(Instant.parse("2026-03-08T05:00:00Z"), dataset.getCurrentAsof());
        ArgumentCaptor<AsofAdvanceLog> log = ArgumentCaptor.forClass(AsofAdvanceLog.class);
        verify(asofLogRepo).save(log.capture());
        assertEquals(Instant.parse("2026-03-07T05:00:00Z"), log.getValue().getPreviousAsof());
        assertEquals(Instant.parse("2026-03-08T05:00:00Z"), log.getValue().getNewAsof());
        assertEquals(Instant.parse("2026-03-08T05:00:00Z"), log.getValue().getRequestedAsof());
        assertEquals("ACCEPTED", log.getValue().getAdvanceStatus());
        assertEquals("airflow", log.getValue().getAdvancedBy());
        assertEquals("semantic-proof", log.getValue().getAdvanceSource());
    }

    @Test
    void advanceDatasetHandlesYearBoundaryForForwardRequestedAsof() {
        Dataset dataset = dataset("loan-master", "2026-12-31T05:00:00Z", "DAILY", "America/New_York", Map.of());
        when(datasetRepo.findById("loan-master")).thenReturn(Optional.of(dataset));
        when(datasetRepo.save(dataset)).thenReturn(dataset);

        TimeDimensionService service = new TimeDimensionService(datasetRepo, domainRepo, asofLogRepo, domainLogRepo);
        service.advanceDataset("loan-master", "airflow", "semantic-proof", "year boundary", "2027-01-01");

        assertEquals(Instant.parse("2027-01-01T05:00:00Z"), dataset.getCurrentAsof());
        verify(asofLogRepo).save(any(AsofAdvanceLog.class));
    }

    private Dataset dataset(String id, String currentAsof, String grain, String timezone, Map<String, Object> config) {
        Dataset dataset = new Dataset();
        dataset.setId(id);
        dataset.setName(id);
        dataset.setCurrentAsof(Instant.parse(currentAsof));
        dataset.setTimeGrain(grain);
        dataset.setAsofTimezone(timezone);
        dataset.setTimeGrainConfig(config);
        return dataset;
    }
}
