package com.pulse.broker.mirror;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CatalogMirrorController {

    private final CatalogMirrorSyncService service;

    public CatalogMirrorController(CatalogMirrorSyncService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/tenants/{tenantId}/broker/mirror/sync")
    public ResponseEntity<CatalogMirrorSyncService.MirrorSyncResult> sync(
            @PathVariable String tenantId,
            @RequestParam(required = false) String environment) {
        return ResponseEntity.ok(service.syncNow(tenantId, environment));
    }
}
