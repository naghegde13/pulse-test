package com.pulse.storage.contract.service;

import com.pulse.storage.contract.model.DatasetArrivalEvent;
import com.pulse.storage.contract.model.DatasetLandingContract;
import com.pulse.storage.contract.repository.DatasetArrivalEventRepository;
import com.pulse.storage.contract.repository.DatasetLandingContractRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Creates and manages {@link DatasetLandingContract} records for
 * dataset-owned file landing zones.
 *
 * <p>A landing contract is the single authority for a dataset's file
 * arrival location, partition template, rejection path, archive path,
 * and outgoing path. These paths are relative to
 * {@code storage_root_files} on the tenant's runtime binding.
 *
 * <p>Landing path convention:
 * <pre>
 * {domainSlug}/{sorSlug}/{datasetSlug}/landing/ingest_date={ingest_date}/arrival_id={arrival_id}/
 * </pre>
 *
 * <p>After the first arrival event, the landing path is immutable.
 * Display-name changes do not mutate the path once data has landed.
 */
@Service
public class DatasetLandingContractService {

    private static final Logger log = LoggerFactory.getLogger(DatasetLandingContractService.class);

    private final DatasetLandingContractRepository landingContractRepository;
    private final DatasetArrivalEventRepository arrivalEventRepository;

    public DatasetLandingContractService(DatasetLandingContractRepository landingContractRepository,
                                          DatasetArrivalEventRepository arrivalEventRepository) {
        this.landingContractRepository = landingContractRepository;
        this.arrivalEventRepository = arrivalEventRepository;
    }

    // ------------------------------------------------------------------ generation

    /**
     * Generate a landing contract for a dataset. If an active contract
     * already exists for the dataset, it is marked as superseded and
     * a new version is created.
     *
     * @param tenantId    tenant scope
     * @param domainId    owning domain
     * @param domainSlug  path-safe domain slug
     * @param sorId       owning system of record
     * @param sorSlug     path-safe SOR slug
     * @param datasetId   dataset being contracted
     * @param datasetSlug path-safe dataset slug
     * @return the new active landing contract
     */
    @Transactional
    public DatasetLandingContract generateLandingContract(String tenantId,
                                                           String domainId,
                                                           String domainSlug,
                                                           String sorId,
                                                           String sorSlug,
                                                           String datasetId,
                                                           String datasetSlug) {
        // Supersede any existing active contract for this dataset
        int newVersion = 1;
        Optional<DatasetLandingContract> existing = findActiveContract(datasetId);
        if (existing.isPresent()) {
            DatasetLandingContract prev = existing.get();

            // Path immutability enforcement: if the previous contract has
            // recorded a first arrival, the path is frozen. A new contract
            // with different slugs would produce a different path, which
            // violates immutability. Block unless the slugs match.
            if (prev.getFirstArrivalAt() != null) {
                boolean slugsMatch = domainSlug.equals(prev.getDomainSlug())
                        && sorSlug.equals(prev.getSorSlug())
                        && datasetSlug.equals(prev.getDatasetSlug());
                if (!slugsMatch) {
                    throw new LandingPathImmutableException(
                            prev.getId(), prev.getRelativeLandingPath(),
                            domainSlug, sorSlug, datasetSlug);
                }
            }

            prev.setStatus("superseded");
            landingContractRepository.save(prev);
            newVersion = prev.getContractVersion() + 1;
            log.debug("Superseded landing contract id={} for dataset={}",
                    prev.getId(), datasetId);
        }

        // Build relative paths per the locked convention
        String basePath = domainSlug + "/" + sorSlug + "/" + datasetSlug;

        String relativeLandingPath = basePath
                + "/landing/ingest_date={ingest_date}/arrival_id={arrival_id}/";
        String rejectedRelativePath = basePath + "/rejected/";
        String archiveRelativePath = basePath + "/archive/";
        String outgoingRelativePath = basePath + "/outgoing/";
        String arrivalPartitionTemplate = "ingest_date={ingest_date}/arrival_id={arrival_id}";

        DatasetLandingContract contract = new DatasetLandingContract();
        contract.setTenantId(tenantId);
        contract.setDomainId(domainId);
        contract.setDomainSlug(domainSlug);
        contract.setSorId(sorId);
        contract.setSorSlug(sorSlug);
        contract.setDatasetId(datasetId);
        contract.setDatasetSlug(datasetSlug);
        contract.setContractVersion(newVersion);
        contract.setStatus("active");
        contract.setRootKind("files");
        contract.setRelativeLandingPath(relativeLandingPath);
        contract.setArrivalPartitionTemplate(arrivalPartitionTemplate);
        contract.setRejectedRelativePath(rejectedRelativePath);
        contract.setArchiveRelativePath(archiveRelativePath);
        contract.setOutgoingRelativePath(outgoingRelativePath);
        contract.setProvenance(Map.of(
                "source", "contract_generation",
                "domainSlug", domainSlug,
                "sorSlug", sorSlug,
                "datasetSlug", datasetSlug
        ));

        DatasetLandingContract saved = landingContractRepository.save(contract);
        log.info("Generated landing contract id={} v{} for dataset={} path={}",
                saved.getId(), newVersion, datasetId, relativeLandingPath);
        return saved;
    }

    // ------------------------------------------------------------------ queries

    /**
     * Find the active landing contract for a dataset, if one exists.
     */
    public Optional<DatasetLandingContract> findActiveContract(String datasetId) {
        return landingContractRepository.findByDatasetIdAndStatus(datasetId, "active");
    }

    /**
     * Check whether a landing contract's path is immutable (has at least
     * one arrival event recorded).
     */
    public boolean isPathImmutable(String datasetId) {
        Optional<DatasetLandingContract> contract = findActiveContract(datasetId);
        return contract.isPresent() && contract.get().getFirstArrivalAt() != null;
    }

    /**
     * Get all arrival events for a dataset's active landing contract.
     */
    public List<DatasetArrivalEvent> getArrivalEvents(String datasetId) {
        Optional<DatasetLandingContract> contract = findActiveContract(datasetId);
        if (contract.isEmpty()) {
            return List.of();
        }
        return arrivalEventRepository.findByLandingContractIdOrderByCreatedAtDesc(
                contract.get().getId());
    }

    /**
     * Thrown when a landing contract regeneration would change the path
     * after data has already landed.
     */
    public static class LandingPathImmutableException extends RuntimeException {
        private final String contractId;
        private final String frozenPath;

        public LandingPathImmutableException(String contractId, String frozenPath,
                                              String newDomainSlug, String newSorSlug,
                                              String newDatasetSlug) {
            super("Landing path immutable: contract " + contractId
                    + " has frozen path '" + frozenPath
                    + "' after first arrival; cannot regenerate with different slugs ("
                    + newDomainSlug + "/" + newSorSlug + "/" + newDatasetSlug + ")");
            this.contractId = contractId;
            this.frozenPath = frozenPath;
        }

        public String getContractId() { return contractId; }
        public String getFrozenPath() { return frozenPath; }
    }
}
