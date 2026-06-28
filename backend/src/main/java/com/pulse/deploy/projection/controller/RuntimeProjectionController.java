package com.pulse.deploy.projection.controller;

import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.deploy.projection.model.RuntimeProjection;
import com.pulse.deploy.projection.repository.RuntimeProjectionRepository;
import com.pulse.deploy.projection.service.RuntimeProjectionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * ARCH-006 / PKT-0023 — Runtime projection lifecycle endpoints.
 *
 * <p>Projections capture the physical DDL plan + table contract snapshot
 * for a given package → target → environment tuple. They are derived
 * entirely from product state (package.versionId → active TableContract
 * records → DdlPlanService-generated DDL) and consumed by the deployment
 * preflight to verify DDL readiness and detect drift.
 *
 * <p>PKT-0023: The POST endpoint no longer accepts a request body with
 * caller-supplied tableContracts or ddlStatements. All physical design
 * proof is derived from product state to prevent arbitrary caller
 * injection of DDL or contract snapshots.
 */
@RestController
@RequestMapping("/api/v1")
public class RuntimeProjectionController {

    private final RuntimeProjectionService runtimeProjectionService;
    private final RuntimeProjectionRepository runtimeProjectionRepository;

    public RuntimeProjectionController(RuntimeProjectionService runtimeProjectionService,
                                        RuntimeProjectionRepository runtimeProjectionRepository) {
        this.runtimeProjectionService = runtimeProjectionService;
        this.runtimeProjectionRepository = runtimeProjectionRepository;
    }

    /**
     * Create or refresh a runtime projection for the given package +
     * target + environment. All table contracts and DDL statements are
     * derived from product state (package.versionId → active contracts
     * → DDL plan). No request body is accepted.
     *
     * <p>Idempotent: if an active projection already exists for the same
     * (packageId, targetId, environment) tuple, it is superseded.
     */
    @PostMapping("/packages/{packageId}/runtime-projections")
    public ResponseEntity<RuntimeProjection> createOrRefresh(
            @PathVariable String packageId,
            @RequestParam String targetId,
            @RequestParam String environment) {
        RuntimeProjection projection = runtimeProjectionService.createOrRefresh(
                packageId, targetId, environment);
        return ResponseEntity.ok(projection);
    }

    /**
     * Retrieve the active runtime projection for a package + target +
     * environment tuple. Returns 404 when no active projection exists.
     */
    @GetMapping("/packages/{packageId}/runtime-projections")
    public ResponseEntity<RuntimeProjection> getActiveProjection(
            @PathVariable String packageId,
            @RequestParam String targetId,
            @RequestParam String environment) {
        RuntimeProjection projection = runtimeProjectionService
                .getActiveProjection(packageId, targetId, environment)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "RuntimeProjection", packageId + "/" + targetId + "/" + environment));
        return ResponseEntity.ok(projection);
    }

    /**
     * Check whether the projection's DDL plan + table contract snapshot
     * still matches the current state. Returns a drift result indicating
     * whether re-projection is needed.
     */
    @GetMapping("/packages/{packageId}/runtime-projections/{projectionId}/drift-check")
    public ResponseEntity<Map<String, Object>> checkDrift(
            @PathVariable String packageId,
            @PathVariable String projectionId) {
        runtimeProjectionRepository.findById(projectionId)
                .orElseThrow(() -> new ResourceNotFoundException("RuntimeProjection", projectionId));
        RuntimeProjectionService.ProjectionDriftResult drift =
                runtimeProjectionService.checkDrift(projectionId);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("projectionId", projectionId);
        result.put("drifted", drift.drifted());
        result.put("storedHash", drift.storedHash());
        result.put("currentHash", drift.currentHash());
        return ResponseEntity.ok(result);
    }
}
