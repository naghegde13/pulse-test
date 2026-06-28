package com.pulse.storage.contract.service;

import com.pulse.runtime.service.RuntimeBindingAuthorityFacade;
import com.pulse.runtime.service.RuntimeBindingAuthorityFacade.ResolutionResult;
import com.pulse.runtime.service.RuntimeBindingAuthorityFacade.StorageRoots;
import com.pulse.storage.contract.model.DatasetArrivalEvent;
import com.pulse.storage.contract.model.DatasetLandingContract;
import com.pulse.storage.contract.model.TableContract;
import com.pulse.storage.contract.service.TableContractProjectionService.TableContractProjection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Single read surface for contract and storage authority.
 *
 * <p>Chat tools, codegen, package services, and deploy adapters consume
 * storage and contract information through this facade rather than
 * querying contract repositories or projection services directly. This
 * guarantees a consistent shape for previews and readiness checks across
 * all product surfaces.
 *
 * <p>All methods return {@code Map<String,Object>} for easy JSON
 * serialization in chat tool results and API responses.
 */
@Service
public class StorageAuthorityFacade {

    private static final Logger log = LoggerFactory.getLogger(StorageAuthorityFacade.class);

    private final TableContractService tableContractService;
    private final TableContractProjectionService projectionService;
    private final DatasetLandingContractService landingContractService;
    private final DatasetArrivalEventService arrivalEventService;
    private final DdlPlanService ddlPlanService;
    private final RuntimeBindingAuthorityFacade bindingAuthorityFacade;

    public StorageAuthorityFacade(TableContractService tableContractService,
                                   TableContractProjectionService projectionService,
                                   DatasetLandingContractService landingContractService,
                                   DatasetArrivalEventService arrivalEventService,
                                   DdlPlanService ddlPlanService,
                                   RuntimeBindingAuthorityFacade bindingAuthorityFacade) {
        this.tableContractService = tableContractService;
        this.projectionService = projectionService;
        this.landingContractService = landingContractService;
        this.arrivalEventService = arrivalEventService;
        this.ddlPlanService = ddlPlanService;
        this.bindingAuthorityFacade = bindingAuthorityFacade;
    }

    // ------------------------------------------------------------------ table contract preview

    /**
     * Build a table contract preview for a specific producing instance
     * within a version, projected to an environment.
     *
     * <p>Returns contract details and the resolved environment projection
     * (object-store URI, catalog identifier, DDL summary).
     *
     * @param instanceId  producing sub-pipeline instance ID
     * @param versionId   pipeline version ID
     * @param tenantId    tenant scope for binding resolution
     * @param environment target environment
     * @return preview map suitable for JSON serialization
     */
    public Map<String, Object> getTableContractPreview(String instanceId,
                                                        String versionId,
                                                        String tenantId,
                                                        String environment) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("instanceId", instanceId);
        result.put("versionId", versionId);
        result.put("environment", environment);

        List<TableContract> active = tableContractService.findActiveContracts(versionId);
        List<TableContract> instanceContracts = active.stream()
                .filter(c -> instanceId.equals(c.getProducingInstanceId()))
                .toList();

        if (instanceContracts.isEmpty()) {
            result.put("status", "no_contracts");
            result.put("message", "No active table contracts for instance " + instanceId);
            return result;
        }

        List<Map<String, Object>> contractPreviews = instanceContracts.stream()
                .map(c -> buildContractPreviewEntry(c, tenantId, environment))
                .toList();

        result.put("status", "ok");
        result.put("contractCount", contractPreviews.size());
        result.put("contracts", contractPreviews);
        return result;
    }

    // ------------------------------------------------------------------ dataset landing preview

    /**
     * Build a dataset landing contract preview, projected to an environment.
     *
     * @param datasetId   dataset ID
     * @param tenantId    tenant scope for binding resolution
     * @param environment target environment
     * @return preview map suitable for JSON serialization
     */
    public Map<String, Object> getDatasetLandingPreview(String datasetId,
                                                         String tenantId,
                                                         String environment) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("datasetId", datasetId);
        result.put("environment", environment);

        Optional<DatasetLandingContract> active = landingContractService.findActiveContract(datasetId);
        if (active.isEmpty()) {
            result.put("status", "no_contract");
            result.put("message", "No active landing contract for dataset " + datasetId);
            return result;
        }

        DatasetLandingContract contract = active.get();
        result.put("status", "ok");
        result.put("contractId", contract.getId());
        result.put("contractVersion", contract.getContractVersion());
        result.put("domainSlug", contract.getDomainSlug());
        result.put("sorSlug", contract.getSorSlug());
        result.put("datasetSlug", contract.getDatasetSlug());
        result.put("relativeLandingPath", contract.getRelativeLandingPath());
        result.put("arrivalPartitionTemplate", contract.getArrivalPartitionTemplate());
        result.put("rejectedRelativePath", contract.getRejectedRelativePath());
        result.put("archiveRelativePath", contract.getArchiveRelativePath());
        result.put("outgoingRelativePath", contract.getOutgoingRelativePath());

        // PKT-0023: Arrival event status and path immutability
        result.put("firstArrivalAt", contract.getFirstArrivalAt());
        result.put("firstArrivalEventId", contract.getFirstArrivalEventId());
        result.put("pathImmutable", contract.getFirstArrivalAt() != null);
        List<DatasetArrivalEvent> arrivals = arrivalEventService.findByDataset(datasetId);
        result.put("arrivalEventCount", arrivals.size());
        if (!arrivals.isEmpty()) {
            DatasetArrivalEvent latest = arrivals.get(0);
            Map<String, Object> latestArrival = new LinkedHashMap<>();
            latestArrival.put("eventId", latest.getId());
            latestArrival.put("ingestDate", latest.getIngestDate());
            latestArrival.put("arrivalId", latest.getArrivalId());
            latestArrival.put("fileCount", latest.getFileCount());
            latestArrival.put("totalBytes", latest.getTotalBytes());
            latestArrival.put("recordedAt", latest.getCreatedAt());
            result.put("latestArrival", latestArrival);
        }

        // Resolve storage roots for the environment
        ResolutionResult<StorageRoots> rootsResult =
                bindingAuthorityFacade.resolveStorageRoots(environment); // PKT-FINAL-5 / BUG-39: global
        if (rootsResult instanceof ResolutionResult.Resolved<StorageRoots> r) {
            String filesRoot = r.value().files();
            if (filesRoot != null && !filesRoot.isBlank()) {
                String normalizedRoot = filesRoot.endsWith("/")
                        ? filesRoot.substring(0, filesRoot.length() - 1) : filesRoot;
                result.put("resolvedLandingUri",
                        normalizedRoot + "/" + contract.getRelativeLandingPath());
                result.put("resolvedRejectedUri",
                        normalizedRoot + "/" + contract.getRejectedRelativePath());
                result.put("resolvedArchiveUri",
                        normalizedRoot + "/" + contract.getArchiveRelativePath());
                result.put("resolvedOutgoingUri",
                        normalizedRoot + "/" + contract.getOutgoingRelativePath());
            }
        } else if (rootsResult instanceof ResolutionResult.Unresolved<StorageRoots> u) {
            result.put("bindingBlocker", u.blockerCode());
            result.put("bindingBlockerMessage", u.message());
        }

        return result;
    }

    // ------------------------------------------------------------------ ops artifact preview

    /**
     * Build an ops-root artifact path preview.
     *
     * <p>Operational artifacts (checkpoints, GX docs, manifests) live under
     * {@code storage_root_ops} and are separate from business data paths.
     *
     * @param pipelineId   pipeline ID
     * @param tenantId     tenant scope
     * @param environment  target environment
     * @param artifactKind artifact type (checkpoint, gx_docs, manifest)
     * @param name         artifact name or stream name
     * @return preview map
     */
    public Map<String, Object> getOpsArtifactPreview(String pipelineId,
                                                      String tenantId,
                                                      String environment,
                                                      String artifactKind,
                                                      String name) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pipelineId", pipelineId);
        result.put("environment", environment);
        result.put("artifactKind", artifactKind);
        result.put("name", name);

        ResolutionResult<StorageRoots> rootsResult =
                bindingAuthorityFacade.resolveStorageRoots(environment); // PKT-FINAL-5 / BUG-39: global
        if (rootsResult instanceof ResolutionResult.Unresolved<StorageRoots> u) {
            result.put("status", "unresolved");
            result.put("blockerCode", u.blockerCode());
            result.put("message", u.message());
            return result;
        }

        StorageRoots roots = ((ResolutionResult.Resolved<StorageRoots>) rootsResult).value();
        String opsRoot = roots.ops();
        if (opsRoot == null || opsRoot.isBlank()) {
            result.put("status", "ops_root_missing");
            result.put("message", "storage_root_ops is not configured for tenant "
                    + tenantId + " environment " + environment);
            return result;
        }

        String normalizedRoot = opsRoot.endsWith("/")
                ? opsRoot.substring(0, opsRoot.length() - 1) : opsRoot;
        String relativePath = "pipelines/" + pipelineId + "/" + artifactKind + "/";
        if (name != null && !name.isBlank()) {
            relativePath += name + "/";
        }

        result.put("status", "ok");
        result.put("opsRoot", opsRoot);
        result.put("relativePath", relativePath);
        result.put("resolvedUri", normalizedRoot + "/" + relativePath);
        return result;
    }

    // ------------------------------------------------------------------ readiness check

    /**
     * Check contract readiness for a pipeline version.
     *
     * <p>Returns a summary of active, stale, and missing contracts. A
     * version is contract-ready when it has no stale contracts and every
     * materializing instance has at least one active contract.
     *
     * @param versionId pipeline version to check
     * @return readiness map with status and blockers
     */
    public Map<String, Object> getContractReadiness(String versionId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("versionId", versionId);

        List<TableContract> allContracts = tableContractService.findActiveContracts(versionId);

        // Count active contracts
        long activeCount = allContracts.stream()
                .filter(c -> "active".equals(c.getStatus()))
                .count();

        result.put("activeContractCount", activeCount);
        result.put("totalContracts", allContracts.size());

        List<Map<String, Object>> blockers = new java.util.ArrayList<>();

        if (activeCount == 0) {
            blockers.add(Map.of(
                    "code", "no_active_contracts",
                    "message", "No active table contracts for version " + versionId
            ));
        }

        // PKT-0023: Check physical design completeness on active contracts
        for (TableContract contract : allContracts) {
            if (!"active".equals(contract.getStatus())) continue;

            // partitionSpec is required for runtime-ready physical design
            if (contract.getPartitionSpec() == null || contract.getPartitionSpec().isEmpty()) {
                blockers.add(Map.of(
                        "code", "missing_partition_spec",
                        "contractId", contract.getId(),
                        "tableName", contract.getTableName(),
                        "message", "Table contract " + contract.getTableName()
                                + " is missing partitionSpec; physical design incomplete"
                ));
            }

            // catalogKind is required for DDL generation
            if (contract.getCatalogKind() == null || contract.getCatalogKind().isBlank()) {
                blockers.add(Map.of(
                        "code", "missing_catalog_kind",
                        "contractId", contract.getId(),
                        "tableName", contract.getTableName(),
                        "message", "Table contract " + contract.getTableName()
                                + " is missing catalogKind; DDL cannot be generated"
                ));
            }
        }

        result.put("blockers", blockers);
        result.put("ready", blockers.isEmpty() && activeCount > 0);
        return result;
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> buildContractPreviewEntry(TableContract contract,
                                                           String tenantId,
                                                           String environment) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("contractId", contract.getId());
        entry.put("contractVersion", contract.getContractVersion());
        entry.put("layer", contract.getLayer());
        entry.put("tableRole", contract.getTableRole());
        entry.put("tableName", contract.getTableName());
        entry.put("tableFormat", contract.getTableFormat());
        entry.put("catalogKind", contract.getCatalogKind());
        entry.put("schemaName", contract.getSchemaName());
        entry.put("catalogTableName", contract.getCatalogTableName());
        entry.put("relativeStoragePath", contract.getRelativeStoragePath());
        entry.put("partitionSpec", contract.getPartitionSpec());
        entry.put("layoutSpec", contract.getLayoutSpec());
        entry.put("writeMode", contract.getWriteMode());
        entry.put("ddlStrategy", contract.getDdlStrategy());

        // Attempt projection
        try {
            TableContractProjection projection = projectionService.project(
                    contract, tenantId, environment);
            entry.put("resolvedObjectStoreUri", projection.resolvedObjectStoreUri());
            entry.put("resolvedCatalogIdentifier", projection.resolvedCatalogIdentifier());
            entry.put("projectionHash", projection.projectionHash());
            entry.put("projectionStatus", "resolved");
        } catch (TableContractProjectionService.TableContractProjectionException e) {
            entry.put("projectionStatus", "unresolved");
            entry.put("projectionBlocker", e.getMessage());
        }

        return entry;
    }
}
