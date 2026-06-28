package com.pulse.auth.filter;

import com.pulse.auth.policy.PulseRole;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enforces tenant membership on tenant-scoped API routes when auth is enabled.
 *
 * <p>If the request URL matches {@code /api/v1/tenants/{tenantId}/...} and
 * the authenticated principal's tenantId does not match the path tenantId,
 * the request is rejected with 403 Forbidden — unless the user has the
 * PLATFORM_ADMIN policy role (legacy ADMIN maps to TENANT_ADMIN only).
 *
 * <p>This filter runs AFTER {@link JwtAuthenticationFilter} and only activates
 * when there is an authenticated JWT principal in the SecurityContext.
 */
public class TenantMembershipFilter extends OncePerRequestFilter {

    private static final Pattern TENANT_PATH = Pattern.compile(
            "^/api/v1/tenants/([^/]+)(/.*)?$");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof JwtPrincipal principal)) {
            // No JWT principal — let SecurityConfig handle (will 401 if auth required)
            filterChain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        Matcher matcher = TENANT_PATH.matcher(path);
        if (matcher.matches()) {
            String pathTenantId = matcher.group(1);
            String principalTenantId = principal.tenantId();

            // Only PLATFORM_ADMIN gets cross-tenant access.
            // Legacy ADMIN maps to TENANT_ADMIN, not PLATFORM_ADMIN.
            if (!PulseRole.isPlatformAdmin(principal.role()) && !pathTenantId.equals(principalTenantId)) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json");
                response.getWriter().write(
                        "{\"error\":\"tenant_membership\",\"message\":\"Access denied: tenant mismatch\"}");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }
}
