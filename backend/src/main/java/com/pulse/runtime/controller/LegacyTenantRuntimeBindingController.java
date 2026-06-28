package com.pulse.runtime.controller;

import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.runtime.model.RuntimeBinding;
import com.pulse.runtime.repository.RuntimeBindingRepository;
import com.pulse.runtime.service.RuntimeBindingBackfillService;
import com.pulse.runtime.service.RuntimeBindingService;
import com.pulse.runtime.service.RuntimeBindingValidationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * PKT-FINAL-5 / BUG-39: Deprecated tenant-scoped runtime bindings surface.
 *
 * <p>Runtime bindings are now deployment-global (see {@link RuntimeBindingController}).
 * This controller exists for one release as a deprecation shim so external
 * callers still hitting {@code /api/v1/tenants/{tenantId}/runtime-bindings}
 * keep working. The {@code tenantId} path segment is ignored; every call
 * is serviced from the global binding set. A
 * {@code X-Pulse-Deprecated} header is emitted on every response so the
 * caller knows to migrate.
 *
 * <p>Remove in release N+1.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/runtime-bindings")
public class LegacyTenantRuntimeBindingController {

    private static final Logger log = LoggerFactory.getLogger(LegacyTenantRuntimeBindingController.class);
    private static final String DEPRECATION_HEADER = "X-Pulse-Deprecated";
    private static final String DEPRECATION_MESSAGE =
            "use /api/v1/runtime-bindings — runtime bindings are deployment-global (BUG-39)";

    private final RuntimeBindingRepository repo;
    private final RuntimeBindingService bindingService;
    private final RuntimeBindingValidationService validationService;
    private final RuntimeBindingBackfillService backfillService;

    public LegacyTenantRuntimeBindingController(RuntimeBindingRepository repo,
                                                RuntimeBindingService bindingService,
                                                RuntimeBindingValidationService validationService,
                                                RuntimeBindingBackfillService backfillService) {
        this.repo = repo;
        this.bindingService = bindingService;
        this.validationService = validationService;
        this.backfillService = backfillService;
    }

    @GetMapping
    public ResponseEntity<List<RuntimeBinding>> list(@PathVariable String tenantId) {
        warnOnce(tenantId, "GET list");
        return ResponseEntity.ok()
                .header(DEPRECATION_HEADER, DEPRECATION_MESSAGE)
                .body(repo.findAllByOrderByEnvironmentAsc());
    }

    @GetMapping("/{id}")
    public ResponseEntity<RuntimeBinding> get(@PathVariable String tenantId,
                                              @PathVariable String id) {
        warnOnce(tenantId, "GET id");
        return ResponseEntity.ok()
                .header(DEPRECATION_HEADER, DEPRECATION_MESSAGE)
                .body(require(id));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN') or hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<RuntimeBinding> create(
            @PathVariable String tenantId,
            @RequestBody RuntimeBindingController.CreateRuntimeBindingRequest req) {
        warnOnce(tenantId, "POST create");
        RuntimeBinding binding = new RuntimeBinding();
        binding.setEnvironment(req.environment());
        binding.setBindingKind(req.bindingKind());
        binding.setSettingsRole(req.settingsRole() != null ? req.settingsRole() : "PRIMARY");
        binding.setStorageRootFiles(req.storageRootFiles());
        binding.setStorageRootLake(req.storageRootLake());
        binding.setStorageRootOps(req.storageRootOps());
        RuntimeBinding saved = bindingService.create(binding);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(DEPRECATION_HEADER, DEPRECATION_MESSAGE)
                .body(saved);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<RuntimeBinding> update(
            @PathVariable String tenantId,
            @PathVariable String id,
            @RequestBody RuntimeBindingController.PatchRuntimeBindingRequest req) {
        warnOnce(tenantId, "PATCH update");
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
        row.setValidationStatus("PENDING");
        row.setValidatedAt(null);
        row.setValidationError(null);
        RuntimeBinding saved = bindingService.update(row);
        return ResponseEntity.ok()
                .header(DEPRECATION_HEADER, DEPRECATION_MESSAGE)
                .body(saved);
    }

    @PostMapping("/{id}/validate")
    @PreAuthorize("hasRole('ADMIN') or hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<RuntimeBinding> validate(@PathVariable String tenantId,
                                                   @PathVariable String id) {
        warnOnce(tenantId, "POST validate");
        RuntimeBinding row = require(id);
        RuntimeBinding validated = validationService.validate(row);
        return ResponseEntity.ok()
                .header(DEPRECATION_HEADER, DEPRECATION_MESSAGE)
                .body(validated);
    }

    @PostMapping("/backfill")
    @PreAuthorize("hasRole('ADMIN') or hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<Map<String, Object>> backfill(@PathVariable String tenantId) {
        warnOnce(tenantId, "POST backfill");
        Map<String, Object> result = backfillService.backfillFromLegacyStorageBackends();
        return ResponseEntity.ok()
                .header(DEPRECATION_HEADER, DEPRECATION_MESSAGE)
                .body(result);
    }

    private RuntimeBinding require(String id) {
        return repo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("RuntimeBinding", id));
    }

    private void warnOnce(String tenantId, String action) {
        log.warn("BUG-39 deprecation: tenant-scoped runtime bindings route "
                + "(tenant={} action={}); migrate to /api/v1/runtime-bindings", tenantId, action);
    }
}
