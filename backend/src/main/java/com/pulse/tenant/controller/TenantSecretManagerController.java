package com.pulse.tenant.controller;

import com.pulse.auth.filter.JwtPrincipal;
import com.pulse.auth.policy.PulseRole;
import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.tenant.model.TenantGcpRuntimeTopology;
import com.pulse.tenant.service.TenantSecretManagerBindingService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Optional;

/**
 * PKT-FINAL-5 / BUG-54 / BUG-2026-05-26-58 (PKT-CAND-58):
 * Per-tenant Secret Manager binding endpoint.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /api/v1/tenants/{tenantId}/secret-manager} — readback
 *       (mode + GSM project + name prefix + redaction flag).
 *   <li>{@code PUT /api/v1/tenants/{tenantId}/secret-manager} — upsert mode,
 *       project, and prefix. First PUT defaults to {@code LOCAL_STUB} when
 *       no mode is provided. Setting {@code GCP_SECRET_MANAGER} (alias
 *       {@code TENANT_GCP_SECRET_MANAGER}) requires an explicit
 *       {@code gsmProjectId} or the request returns 400.
 * </ul>
 *
 * <p>Same role-gate pattern as {@code TenantGcpRuntimeTopologyController}:
 * same-tenant TENANT_ADMIN or PLATFORM_ADMIN can mutate; same-tenant
 * non-admin and cross-tenant non-platform-admin are denied.
 *
 * <p>Contract-drift bridge: GET returns the wire-format mode
 * {@code TENANT_GCP_SECRET_MANAGER} that the frontend panel's typed enum
 * expects, while PUT accepts both the wire format and the canonical DB
 * value {@code GCP_SECRET_MANAGER}. See
 * {@link TenantSecretManagerBindingService#normalizeMode(String)} and
 * {@code buildReadback(...)}.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/secret-manager")
public class TenantSecretManagerController {

    private final TenantSecretManagerBindingService bindingService;

    public TenantSecretManagerController(TenantSecretManagerBindingService bindingService) {
        this.bindingService = bindingService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getBinding(@PathVariable String tenantId) {
        Optional<TenantGcpRuntimeTopology> existing = bindingService.getBinding(tenantId);
        if (existing.isPresent()) {
            return ResponseEntity.ok(bindingService.buildReadback(tenantId, existing.get()));
        }
        // Tenants that have not yet PUT the binding default to LOCAL_STUB.
        return ResponseEntity.ok(bindingService.buildDefaultReadback(tenantId));
    }

    @PutMapping
    public ResponseEntity<Map<String, Object>> setBinding(
            @PathVariable String tenantId,
            @RequestBody TenantSecretManagerBindingRequest request) {
        // Role gate: same-tenant TENANT_ADMIN or PLATFORM_ADMIN required
        // for mutation (mirrors TenantGcpRuntimeTopologyController).
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof JwtPrincipal principal) {
            if (!PulseRole.isPlatformAdmin(principal.role()) && !tenantId.equals(principal.tenantId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied: tenant mismatch");
            }
            if (!PulseRole.isTenantAdminOrHigher(principal.role())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Secret manager binding mutation requires TENANT_ADMIN or PLATFORM_ADMIN role");
            }
        }
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request body is required");
        }

        var error = bindingService.validate(request.mode(), request.gsmProjectId());
        if (error.isPresent()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    error.get().code() + ": " + error.get().message());
        }

        String actorId = (auth != null && auth.getPrincipal() instanceof JwtPrincipal p)
                ? p.userId() : "system";
        try {
            TenantGcpRuntimeTopology saved = bindingService.upsert(
                    tenantId,
                    actorId,
                    request.mode(),
                    request.gsmProjectId(),
                    request.secretNamePrefix());
            return ResponseEntity.ok(bindingService.buildReadback(tenantId, saved));
        } catch (ResourceNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * Request body for the binding upsert. All fields are nullable on
     * input — null {@code mode} defaults to {@code LOCAL_STUB}.
     */
    public record TenantSecretManagerBindingRequest(
            String mode,
            String gsmProjectId,
            String secretNamePrefix
    ) {
    }
}
