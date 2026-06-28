package com.pulse.storage.contract.service;

import com.pulse.storage.contract.model.DatasetArrivalEvent;
import com.pulse.storage.contract.model.DatasetLandingContract;
import com.pulse.storage.contract.repository.DatasetArrivalEventRepository;
import com.pulse.storage.contract.repository.DatasetLandingContractRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Records and queries dataset arrival events — the first-arrival ledger
 * that proves data has landed at a contracted path.
 *
 * <p>Recording the first arrival for a landing contract freezes its path:
 * subsequent rename/slug changes on the dataset or SOR do not mutate the
 * contract's relative landing path. This is the path immutability
 * enforcement described in the landing contract Javadoc.
 */
@Service
public class DatasetArrivalEventService {

    private static final Logger log = LoggerFactory.getLogger(DatasetArrivalEventService.class);

    private final DatasetArrivalEventRepository arrivalEventRepository;
    private final DatasetLandingContractRepository landingContractRepository;

    public DatasetArrivalEventService(DatasetArrivalEventRepository arrivalEventRepository,
                                       DatasetLandingContractRepository landingContractRepository) {
        this.arrivalEventRepository = arrivalEventRepository;
        this.landingContractRepository = landingContractRepository;
    }

    /**
     * Record an arrival event for a dataset's active landing contract.
     *
     * <p>If this is the first arrival for the contract, it sets
     * {@code firstArrivalAt} and {@code firstArrivalEventId} on the
     * landing contract, freezing the contract path.
     *
     * @param tenantId      tenant scope
     * @param datasetId     dataset that received data
     * @param ingestDate    partition ingest date (e.g. "2026-05-25")
     * @param arrivalId     unique arrival identifier within the partition
     * @param arrivalPath   resolved arrival path
     * @param fileCount     number of files in this arrival
     * @param totalBytes    total bytes across all files
     * @param sourceSystem  optional source system identifier
     * @return the recorded arrival event
     * @throws IllegalStateException if no active landing contract exists
     * @throws PathImmutabilityViolationException if the arrival path doesn't
     *         match the contract's landing path pattern after first arrival
     */
    @Transactional
    public DatasetArrivalEvent recordArrival(String tenantId,
                                              String datasetId,
                                              String ingestDate,
                                              String arrivalId,
                                              String arrivalPath,
                                              int fileCount,
                                              long totalBytes,
                                              String sourceSystem) {
        Optional<DatasetLandingContract> contractOpt = landingContractRepository
                .findByDatasetIdAndStatus(datasetId, "active");
        if (contractOpt.isEmpty()) {
            throw new IllegalStateException(
                    "No active landing contract for dataset " + datasetId
                            + "; cannot record arrival");
        }

        DatasetLandingContract contract = contractOpt.get();

        // Duplicate check
        Optional<DatasetArrivalEvent> existing = arrivalEventRepository
                .findByLandingContractIdAndIngestDateAndArrivalId(
                        contract.getId(), ingestDate, arrivalId);
        if (existing.isPresent()) {
            log.debug("Duplicate arrival event for contract={} ingestDate={} arrivalId={}; returning existing",
                    contract.getId(), ingestDate, arrivalId);
            return existing.get();
        }

        // Path immutability enforcement: if the contract already has a first
        // arrival, verify the arrival path is consistent with the contract's
        // relative landing path pattern.
        if (contract.getFirstArrivalAt() != null) {
            if (arrivalPath == null || arrivalPath.isBlank()) {
                throw new IllegalArgumentException(
                        "arrivalPath is required for arrivals on a frozen contract (first arrival already recorded)");
            }
            // Build the expected resolved path for this partition
            String expectedResolved = contract.getRelativeLandingPath()
                    .replace("ingest_date={ingest_date}", "ingest_date=" + ingestDate)
                    .replace("arrival_id={arrival_id}", "arrival_id=" + arrivalId);
            // The arrival path must end with (or equal) the expected resolved relative path
            if (!arrivalPath.endsWith(expectedResolved) && !arrivalPath.equals(expectedResolved)) {
                // Extract the base domain/sor/dataset prefix from the contract path
                int landingIdx = contract.getRelativeLandingPath().indexOf("/landing/");
                String basePath = landingIdx > 0
                        ? contract.getRelativeLandingPath().substring(0, landingIdx)
                        : contract.getRelativeLandingPath();
                if (!arrivalPath.contains(basePath)) {
                    throw new PathImmutabilityViolationException(
                            contract.getId(), contract.getRelativeLandingPath(), arrivalPath);
                }
            }
        }

        DatasetArrivalEvent event = new DatasetArrivalEvent();
        event.setTenantId(tenantId);
        event.setDatasetId(datasetId);
        event.setLandingContractId(contract.getId());
        event.setContractVersion(contract.getContractVersion());
        event.setArrivalPath(arrivalPath);
        event.setIngestDate(ingestDate);
        event.setArrivalId(arrivalId);
        event.setFileCount(fileCount);
        event.setTotalBytes(totalBytes);
        event.setSourceSystem(sourceSystem);
        event.setStatus("recorded");
        event.setProvenance(Map.of(
                "source", "arrival_recording",
                "contractId", contract.getId(),
                "contractVersion", contract.getContractVersion()
        ));

        DatasetArrivalEvent saved = arrivalEventRepository.save(event);

        // Mark first arrival on the contract if this is the first event
        if (contract.getFirstArrivalAt() == null) {
            contract.setFirstArrivalAt(Instant.now());
            contract.setFirstArrivalEventId(saved.getId());
            landingContractRepository.save(contract);
            log.info("First arrival recorded for contract {} dataset {} — path now immutable",
                    contract.getId(), datasetId);
        }

        log.info("Recorded arrival event id={} for dataset={} ingestDate={} arrivalId={}",
                saved.getId(), datasetId, ingestDate, arrivalId);
        return saved;
    }

    /**
     * Get all arrival events for a dataset, most recent first.
     */
    public List<DatasetArrivalEvent> findByDataset(String datasetId) {
        return arrivalEventRepository.findByDatasetIdOrderByCreatedAtDesc(datasetId);
    }

    /**
     * Get the first arrival event for a landing contract.
     */
    public Optional<DatasetArrivalEvent> findFirstArrival(String landingContractId) {
        return arrivalEventRepository.findFirstByLandingContractIdOrderByCreatedAtAsc(
                landingContractId);
    }

    /**
     * Check whether a landing contract's path is immutable (has at least
     * one arrival event).
     */
    public boolean isPathImmutable(String landingContractId) {
        return arrivalEventRepository.findFirstByLandingContractIdOrderByCreatedAtAsc(
                landingContractId).isPresent();
    }

    /**
     * Thrown when an arrival event attempts to record at a path that
     * doesn't match the contract's frozen landing path.
     */
    public static class PathImmutabilityViolationException extends RuntimeException {
        private final String contractId;
        private final String contractPath;
        private final String attemptedPath;

        public PathImmutabilityViolationException(String contractId, String contractPath,
                                                    String attemptedPath) {
            super("Path immutability violation: contract " + contractId
                    + " has frozen path '" + contractPath
                    + "' but arrival attempted at '" + attemptedPath + "'");
            this.contractId = contractId;
            this.contractPath = contractPath;
            this.attemptedPath = attemptedPath;
        }

        public String getContractId() { return contractId; }
        public String getContractPath() { return contractPath; }
        public String getAttemptedPath() { return attemptedPath; }
    }
}
