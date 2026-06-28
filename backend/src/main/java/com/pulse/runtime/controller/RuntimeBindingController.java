package com.pulse.runtime.controller;

import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.runtime.model.RuntimeBinding;
import com.pulse.runtime.repository.RuntimeBindingRepository;
import com.pulse.runtime.service.RuntimeBindingBackfillService;
import com.pulse.runtime.service.RuntimeBindingService;
import com.pulse.runtime.service.RuntimeBindingValidationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * PKT-FINAL-5 / BUG-39: Deployment-global REST surface for runtime bindings.
 *
 * <p>Runtime bindings are global to a PULSE deployment (one binding per
 * (environment, binding_kind, settings_role) across all tenants), so the
 * route is no longer tenant-scoped. The legacy tenant-scoped route lives
 * on a separate redirect controller for one release.
 *
 * <p>Mutating endpoints require deployment-admin (PLATFORM_ADMIN) authority.
 */
@RestController
@RequestMapping("/api/v1/runtime-bindings")
public class RuntimeBindingController {

    private final RuntimeBindingRepository repo;
    private final RuntimeBindingService bindingService;
    private final RuntimeBindingValidationService validationService;
    private final RuntimeBindingBackfillService backfillService;

    public RuntimeBindingController(RuntimeBindingRepository repo,
                                    RuntimeBindingService bindingService,
                                    RuntimeBindingValidationService validationService,
                                    RuntimeBindingBackfillService backfillService) {
        this.repo = repo;
        this.bindingService = bindingService;
        this.validationService = validationService;
        this.backfillService = backfillService;
    }

    @GetMapping
    public ResponseEntity<List<RuntimeBinding>> list() {
        return ResponseEntity.ok(repo.findAllByOrderByEnvironmentAsc());
    }

    @GetMapping("/{id}")
    public ResponseEntity<RuntimeBinding> get(@PathVariable String id) {
        return ResponseEntity.ok(require(id));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN') or hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<RuntimeBinding> create(@RequestBody CreateRuntimeBindingRequest req) {
        RuntimeBinding binding = new RuntimeBinding();
        binding.setEnvironment(req.environment());
        binding.setBindingKind(req.bindingKind());
        binding.setSettingsRole(req.settingsRole() != null ? req.settingsRole() : "PRIMARY");
        binding.setStorageRootFiles(req.storageRootFiles());
        binding.setStorageRootLake(req.storageRootLake());
        binding.setStorageRootOps(req.storageRootOps());
        RuntimeBinding saved = bindingService.create(binding);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<RuntimeBinding> update(@PathVariable String id,
                                                 @RequestBody PatchRuntimeBindingRequest req) {
        RuntimeBinding row = require(id);
        if (req.environment() != null && !req.environment().isBlank())
            row.setEnvironment(req.environment().trim());
        if (req.bindingKind() != null && !req.bindingKind().isBlank())
            row.setBindingKind(req.bindingKind().trim());
        if (req.settingsRole() != null && !req.settingsRole().isBlank())
            row.setSettingsRole(req.settingsRole().trim());
        if (req.storageRootFiles() != null)
            row.setStorageRootFiles(req.storageRootFiles().trim());
        if (req.storageRootLake() != null)
            row.setStorageRootLake(req.storageRootLake().trim());
        if (req.storageRootOps() != null)
            row.setStorageRootOps(req.storageRootOps().trim());
        // Editing values invalidates prior validation; user must re-validate.
        row.setValidationStatus("PENDING");
        row.setValidatedAt(null);
        row.setValidationError(null);
        RuntimeBinding saved = bindingService.update(row);
        return ResponseEntity.ok(saved);
    }

    @PostMapping("/{id}/validate")
    @PreAuthorize("hasRole('ADMIN') or hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<RuntimeBinding> validate(@PathVariable String id) {
        RuntimeBinding row = require(id);
        RuntimeBinding validated = validationService.validate(row);
        return ResponseEntity.ok(validated);
    }

    /**
     * Backfill runtime bindings from legacy storage_backends rows. Global
     * scope: one binding per (env, backend) across the deployment;
     * subsequent calls are no-ops (idempotent consolidation).
     */
    @PostMapping("/backfill")
    @PreAuthorize("hasRole('ADMIN') or hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<Map<String, Object>> backfill() {
        Map<String, Object> result = backfillService.backfillFromLegacyStorageBackends();
        return ResponseEntity.ok(result);
    }

    private RuntimeBinding require(String id) {
        return repo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("RuntimeBinding", id));
    }

    public record CreateRuntimeBindingRequest(
            String environment,
            String bindingKind,
            String settingsRole,
            String storageRootFiles,
            String storageRootLake,
            String storageRootOps) {}

    public record PatchRuntimeBindingRequest(
            String environment,
            String bindingKind,
            String settingsRole,
            String storageRootFiles,
            String storageRootLake,
            String storageRootOps) {}
}
