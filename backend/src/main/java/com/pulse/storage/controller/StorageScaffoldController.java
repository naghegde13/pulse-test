package com.pulse.storage.controller;

import com.pulse.storage.service.StorageScaffoldService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * PKT-0012: REST surface for GCP storage scaffold operations.
 *
 * <ul>
 *   <li>GET  /preview — generate scaffold manifest without writing to GCS</li>
 *   <li>POST /execute — attempt scaffold execution (gated for live writes)</li>
 *   <li>GET  /status  — read back current scaffold status per domain</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/storage-scaffold")
public class StorageScaffoldController {

    private final StorageScaffoldService scaffoldService;

    public StorageScaffoldController(StorageScaffoldService scaffoldService) {
        this.scaffoldService = scaffoldService;
    }

    /**
     * Preview the storage scaffold manifest. Returns lifecycle folders,
     * medallion layers, reserved prefixes, and proof prefixes without
     * writing to GCS. Idempotent — repeated calls produce the same manifest.
     */
    @GetMapping("/preview")
    public ResponseEntity<Map<String, Object>> preview(@PathVariable String tenantId) {
        Map<String, Object> result = scaffoldService.preview(tenantId);
        String status = (String) result.get("status");
        if ("failed".equals(status)) {
            return ResponseEntity.unprocessableEntity().body(result);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Execute the storage scaffold — create GCS folders. HTTP shape:
     * <ul>
     *   <li>200 — every folder marker either created or already existed</li>
     *   <li>207 — Multi-Status: at least one path failed, but not all (partial)</li>
     *   <li>409 — operator_blocked: live writes gate is closed</li>
     *   <li>422 — every path failed, or a prerequisite check failed</li>
     * </ul>
     */
    @PostMapping("/execute")
    public ResponseEntity<Map<String, Object>> execute(@PathVariable String tenantId) {
        Map<String, Object> result = scaffoldService.execute(tenantId);
        Object http = result.get("httpStatus");
        if (http instanceof Integer code) {
            return ResponseEntity.status(code).body(result);
        }
        String status = (String) result.get("status");
        if ("operator_blocked".equals(status)) {
            return ResponseEntity.status(409).body(result);
        }
        if ("failed".equals(status)) {
            return ResponseEntity.unprocessableEntity().body(result);
        }
        if ("partial".equals(status)) {
            return ResponseEntity.status(207).body(result);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Read back the current scaffold status for all domains in the tenant.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status(@PathVariable String tenantId) {
        return ResponseEntity.ok(scaffoldService.getStatus(tenantId));
    }
}
