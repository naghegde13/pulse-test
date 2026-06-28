package com.pulse.storage.contract.controller;

import com.pulse.storage.contract.model.TableContract;
import com.pulse.storage.contract.service.StorageAuthorityFacade;
import com.pulse.storage.contract.service.TableContractService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class StorageContractController {

    private final StorageAuthorityFacade storageAuthorityFacade;
    private final TableContractService tableContractService;

    public StorageContractController(StorageAuthorityFacade storageAuthorityFacade,
                                      TableContractService tableContractService) {
        this.storageAuthorityFacade = storageAuthorityFacade;
        this.tableContractService = tableContractService;
    }

    @GetMapping("/versions/{versionId}/table-contracts")
    public ResponseEntity<List<TableContract>> getActiveContracts(@PathVariable String versionId) {
        List<TableContract> contracts = tableContractService.findActiveContracts(versionId);
        return ResponseEntity.ok(contracts);
    }

    @GetMapping("/versions/{versionId}/table-contracts/preview")
    public ResponseEntity<Map<String, Object>> getTableContractPreview(
            @PathVariable String versionId,
            @RequestParam String instanceId,
            @RequestParam String tenantId,
            @RequestParam(defaultValue = "dev") String environment) {
        Map<String, Object> preview = storageAuthorityFacade.getTableContractPreview(
                instanceId, versionId, tenantId, environment);
        return ResponseEntity.ok(preview);
    }

    @GetMapping("/datasets/{datasetId}/landing-contract/preview")
    public ResponseEntity<Map<String, Object>> getDatasetLandingPreview(
            @PathVariable String datasetId,
            @RequestParam String tenantId,
            @RequestParam(defaultValue = "dev") String environment) {
        Map<String, Object> preview = storageAuthorityFacade.getDatasetLandingPreview(
                datasetId, tenantId, environment);
        return ResponseEntity.ok(preview);
    }

    @GetMapping("/pipelines/{pipelineId}/ops-artifact/preview")
    public ResponseEntity<Map<String, Object>> getOpsArtifactPreview(
            @PathVariable String pipelineId,
            @RequestParam String tenantId,
            @RequestParam(defaultValue = "dev") String environment,
            @RequestParam String artifactKind,
            @RequestParam(required = false) String name) {
        Map<String, Object> preview = storageAuthorityFacade.getOpsArtifactPreview(
                pipelineId, tenantId, environment, artifactKind, name);
        return ResponseEntity.ok(preview);
    }

    @GetMapping("/versions/{versionId}/contract-readiness")
    public ResponseEntity<Map<String, Object>> getContractReadiness(@PathVariable String versionId) {
        Map<String, Object> readiness = storageAuthorityFacade.getContractReadiness(versionId);
        return ResponseEntity.ok(readiness);
    }
}
