package com.pulse.auth.controller;

import com.pulse.auth.filter.JwtPrincipal;
import com.pulse.auth.model.Tenant;
import com.pulse.auth.policy.PulseRole;
import com.pulse.auth.service.TenantService;
import com.pulse.common.exception.ResourceNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/tenants")
public class TenantController {

    private final TenantService tenantService;

    public TenantController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listTenants() {
        // When authenticated, non-admin users only see their own tenant
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        List<Map<String, Object>> result = new ArrayList<>();
        for (var t : tenantService.listTenants()) {
            if (auth != null && auth.getPrincipal() instanceof JwtPrincipal principal) {
                if (!PulseRole.isPlatformAdmin(principal.role()) && !t.getId().equals(principal.tenantId())) {
                    continue;
                }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("name", t.getName());
            m.put("slug", t.getSlug());
            m.put("domains", tenantService.getDomainsForTenant(t.getId()));
            result.add(m);
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{tenantId}")
    public ResponseEntity<Map<String, Object>> getTenant(@PathVariable String tenantId) {
        // Enforce tenant membership for non-admin users
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof JwtPrincipal principal) {
            if (!PulseRole.isPlatformAdmin(principal.role()) && !tenantId.equals(principal.tenantId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied: tenant mismatch");
            }
        }
        try {
            var t = tenantService.getTenant(tenantId);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("name", t.getName());
            m.put("slug", t.getSlug());
            m.put("domains", tenantService.getDomainsForTenant(tenantId));
            return ResponseEntity.ok(m);
        } catch (ResourceNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /**
     * Create a tenant via API. Gated behind ADMIN role when auth is enabled.
     * Rows are marked {@code origin='api'} so the YAML bootstrap pass at next
     * startup leaves them alone.
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createTenant(
            @RequestBody CreateTenantRequest request) {
        // Enforce ADMIN-only when authenticated
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof JwtPrincipal principal) {
            if (!PulseRole.isPlatformAdmin(principal.role())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Only PLATFORM_ADMIN role can create tenants");
            }
        }

        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
        }
        try {
            Tenant created = tenantService.createTenant(
                    request.id(), request.name(), request.slug());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", created.getId());
            body.put("name", created.getName());
            body.put("slug", created.getSlug());
            body.put("origin", created.getOrigin());
            body.put("status", created.getStatus());
            return ResponseEntity.status(HttpStatus.CREATED).body(body);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /**
     * Update a tenant's mutable fields. Only name is updatable;
     * id and slug are immutable after creation.
     *
     * <p>Role-gated to TENANT_ADMIN (same-tenant) or PLATFORM_ADMIN
     * (cross-tenant). Non-admin same-tenant actors are denied.
     */
    @PatchMapping("/{tenantId}")
    public ResponseEntity<Map<String, Object>> updateTenant(
            @PathVariable String tenantId,
            @RequestBody UpdateTenantRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof JwtPrincipal principal) {
            // Cross-tenant denial: only PLATFORM_ADMIN may target another tenant
            if (!PulseRole.isPlatformAdmin(principal.role()) && !tenantId.equals(principal.tenantId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied: tenant mismatch");
            }
            // Role-gate: must be TENANT_ADMIN or PLATFORM_ADMIN
            if (!PulseRole.isTenantAdminOrHigher(principal.role())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Tenant update requires TENANT_ADMIN or PLATFORM_ADMIN role");
            }
        }
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
        }
        try {
            var updated = tenantService.updateTenant(tenantId, request.name());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", updated.getId());
            body.put("name", updated.getName());
            body.put("slug", updated.getSlug());
            body.put("origin", updated.getOrigin());
            body.put("status", updated.getStatus());
            return ResponseEntity.ok(body);
        } catch (com.pulse.common.exception.ResourceNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    public record CreateTenantRequest(String id, String name, String slug) {}
    public record UpdateTenantRequest(String name) {}
}
