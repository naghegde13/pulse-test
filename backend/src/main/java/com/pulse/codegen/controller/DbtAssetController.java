package com.pulse.codegen.controller;

import com.pulse.codegen.service.DbtAssetRegistryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class DbtAssetController {

    private final DbtAssetRegistryService dbtAssetRegistryService;

    public DbtAssetController(DbtAssetRegistryService dbtAssetRegistryService) {
        this.dbtAssetRegistryService = dbtAssetRegistryService;
    }

    @GetMapping("/api/v1/domains/{domainId}/dbt-assets")
    public ResponseEntity<List<Map<String, Object>>> listAssets(@PathVariable String domainId) {
        return ResponseEntity.ok(
                dbtAssetRegistryService.listDomainAssets(domainId).stream()
                        .map(dbtAssetRegistryService::toApiPayload)
                        .toList()
        );
    }

    @PostMapping("/api/v1/domains/{domainId}/dbt-assets/refresh")
    public ResponseEntity<List<Map<String, Object>>> refreshAssets(
            @PathVariable String domainId,
            @RequestBody RefreshDbtAssetsRequest request) {
        return ResponseEntity.ok(
                dbtAssetRegistryService.refreshFromManifest(domainId, request.projectName(), request.assets()).stream()
                        .map(dbtAssetRegistryService::toApiPayload)
                        .toList()
        );
    }

    @GetMapping("/api/v1/domains/{domainId}/dbt-assets/reuse-candidate")
    public ResponseEntity<Map<String, Object>> findReuseCandidate(
            @PathVariable String domainId,
            @RequestParam String businessConcept,
            @RequestParam String assetType,
            @RequestParam(required = false) String grain,
            @RequestParam(required = false) String accessLevel,
            @RequestParam(required = false) String schemaSignature,
            @RequestParam(required = false) String emitStrategy) {
        return dbtAssetRegistryService.findReuseCandidate(
                        domainId,
                        businessConcept,
                        assetType,
                        grain,
                        accessLevel,
                        schemaSignature,
                        emitStrategy)
                .map(match -> {
                    Map<String, Object> payload = new java.util.LinkedHashMap<>();
                    payload.put("asset", dbtAssetRegistryService.toApiPayload(match.asset()));
                    payload.putAll(dbtAssetRegistryService.toDecisionPayload(match));
                    return ResponseEntity.ok(payload);
                })
                .orElseGet(() -> ResponseEntity.ok(Map.of(
                        "asset", Map.of(),
                        "emitStrategy", "generate"
                )));
    }

    public record RefreshDbtAssetsRequest(
            String projectName,
            List<DbtAssetRegistryService.ManifestAssetInput> assets
    ) {}
}
