package com.pulse.auth.filter;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import com.pulse.auth.policy.PulseRole;

/**
 * Utility for controllers that access entities by direct ID (not under a
 * tenant-scoped path). When auth is enabled, verifies that the entity's
 * tenant matches the authenticated principal's tenant.
 *
 * <p>When no authenticated principal is present (auth disabled / dev mode),
 * the check is a no-op to preserve dev ergonomics.
 */
public final class TenantIsolationEnforcer {

    private TenantIsolationEnforcer() {}

    /**
     * Verify the current authenticated principal has access to the given entity tenant.
     * Explicit PLATFORM_ADMIN authority bypasses the check.
     *
     * @param entityTenantId the tenantId of the entity being accessed
     * @throws ResponseStatusException with 403 if tenant mismatch
     */
    public static void enforce(String entityTenantId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof JwtPrincipal principal)) {
            // No JWT principal (auth disabled or unauthenticated) — no-op
            return;
        }
        if (PulseRole.isPlatformAdmin(principal.role())) {
            return;
        }
        if (entityTenantId != null && !entityTenantId.equals(principal.tenantId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied: tenant mismatch");
        }
    }
}
