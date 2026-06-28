package com.pulse.storage;

import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.runtime.service.RuntimeAuthorityService;
import com.pulse.storage.StorageRootConventionService.ConventionDefaults;
import com.pulse.storage.model.ProvisioningStatus;
import com.pulse.storage.model.StorageBackend;
import com.pulse.storage.model.StorageBackendType;
import com.pulse.storage.repository.StorageBackendRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * REST surface for storage_backends rows (#30 P7). Read-only listing per
 * tenant, plus PATCH for editing the project/cluster/root/scheme fields,
 * a validate action that runs a probe and updates provisioning_status,
 * and a disable toggle.
 *
 * <p>Validation probes (for now) accept the row at face value and mark
 * it validated — actual GCS/HDFS/S3 connectivity probes land in a
 * follow-up. The lifecycle plumbing (validated → ok-to-deploy) is what
 * matters for the deploy gate (P10).
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/storage-backends")
public class StorageBackendController {

    private final StorageBackendRepository repo;
    private final RuntimeAuthorityService runtimeAuthorityService;
    private final StorageRootConventionService conventionService;

    public StorageBackendController(StorageBackendRepository repo,
                                    RuntimeAuthorityService runtimeAuthorityService,
                                    StorageRootConventionService conventionService) {
        this.repo = repo;
        this.runtimeAuthorityService = runtimeAuthorityService;
        this.conventionService = conventionService;
    }

    @GetMapping
    public ResponseEntity<List<StorageBackend>> list(@PathVariable String tenantId) {
        List<StorageBackend> all = repo.findByTenantId(tenantId);
        List<StorageBackend> filtered = all.stream()
                .filter(sb -> runtimeAuthorityService.isStorageBackendAllowed(sb.getBackend()))
                .toList();
        return ResponseEntity.ok(filtered);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<StorageBackend> update(@PathVariable String tenantId,
                                                  @PathVariable String id,
                                                  @RequestBody PatchRequest req) {
        StorageBackend row = require(tenantId, id);
        runtimeAuthorityService.validateStorageBackend(row.getBackend());
        if (req.gcpProject() != null && !req.gcpProject().isBlank()) row.setGcpProject(req.gcpProject().trim());
        if (req.dpcScheme() != null && !req.dpcScheme().isBlank())   row.setDpcScheme(req.dpcScheme().trim());
        if (req.dpcCluster() != null && !req.dpcCluster().isBlank()) row.setDpcCluster(req.dpcCluster().trim());
        if (req.storageRootFiles() != null && !req.storageRootFiles().isBlank())
            row.setStorageRootFiles(req.storageRootFiles().trim());
        if (req.storageRootLake() != null && !req.storageRootLake().isBlank())
            row.setStorageRootLake(req.storageRootLake().trim());
        // Editing values invalidates the prior validation; user must re-validate.
        row.setProvisioningStatus(ProvisioningStatus.PENDING.dbValue());
        row.setProvisioningValidatedAt(null);
        row.setProvisioningError(null);
        return ResponseEntity.ok(repo.save(row));
    }

    @PostMapping("/{id}/validate")
    public ResponseEntity<StorageBackend> validate(@PathVariable String tenantId,
                                                    @PathVariable String id) {
        StorageBackend row = require(tenantId, id);
        // Stub probe: in #30 follow-up, this hits the real GCP/DPC API.
        // For now, accept-as-validated so the lifecycle plumbing
        // (deploy gate, status badges) is exercisable.
        row.setProvisioningStatus(ProvisioningStatus.VALIDATED.dbValue());
        row.setProvisioningValidatedAt(Instant.now());
        row.setProvisioningError(null);
        return ResponseEntity.ok(repo.save(row));
    }

    @PostMapping("/{id}/disable")
    public ResponseEntity<StorageBackend> disable(@PathVariable String tenantId,
                                                   @PathVariable String id) {
        StorageBackend row = require(tenantId, id);
        row.setDisabled(true);
        row.setProvisioningStatus(ProvisioningStatus.DISABLED.dbValue());
        return ResponseEntity.ok(repo.save(row));
    }

    @PostMapping("/{id}/enable")
    public ResponseEntity<StorageBackend> enable(@PathVariable String tenantId,
                                                   @PathVariable String id) {
        StorageBackend row = require(tenantId, id);
        row.setDisabled(false);
        row.setProvisioningStatus(ProvisioningStatus.PENDING.dbValue());
        return ResponseEntity.ok(repo.save(row));
    }

    /**
     * SU-6 / BUG-62: Return the V96-conventional defaults for a
     * (tenant, env, backend) cell so the storage-backends Edit dialog
     * can pre-populate the bucket / project / cluster fields with the
     * names PULSE would have used at seed time.
     *
     * <p>This endpoint is read-only. It does NOT touch the row; the
     * caller posts a PATCH to persist whatever it ends up with.
     *
     * <p>Backed by {@link StorageRootConventionService} — the single
     * source of truth for the convention.
     */
    @GetMapping("/conventional")
    public ResponseEntity<ConventionDefaults> conventional(
            @PathVariable String tenantId,
            @RequestParam("env") String env,
            @RequestParam("backend") String backend) {
        StorageBackendType type;
        try {
            type = StorageBackendType.valueOf(backend.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusExceptionWrapper(
                    HttpStatus.BAD_REQUEST,
                    "Unknown backend: " + backend);
        }
        runtimeAuthorityService.validateStorageBackend(type.name());
        try {
            return ResponseEntity.ok(conventionService.derive(tenantId, env, type));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusExceptionWrapper(
                    HttpStatus.BAD_REQUEST,
                    ex.getMessage() == null ? "Invalid request" : ex.getMessage());
        }
    }

    private StorageBackend require(String tenantId, String id) {
        StorageBackend row = repo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("StorageBackend", id));
        if (!tenantId.equals(row.getTenantId())) {
            throw new ResponseStatusExceptionWrapper(
                    HttpStatus.FORBIDDEN,
                    "StorageBackend " + id + " does not belong to tenant " + tenantId);
        }
        return row;
    }

    public record PatchRequest(
            String gcpProject,
            String dpcScheme,
            String dpcCluster,
            String storageRootFiles,
            String storageRootLake) {}

    /** Wrapper to reuse the standard 403 path without importing it everywhere. */
    static class ResponseStatusExceptionWrapper
            extends org.springframework.web.server.ResponseStatusException {
        ResponseStatusExceptionWrapper(HttpStatus status, String reason) { super(status, reason); }
    }
}
