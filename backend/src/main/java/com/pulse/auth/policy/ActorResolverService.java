package com.pulse.auth.policy;

import com.pulse.auth.filter.JwtPrincipal;
import com.pulse.auth.model.UserRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Actor resolver with two modes:
 *
 * <p><b>Auth-enabled mode</b> ({@code pulse.auth.enabled=true}): Derives
 * the {@link CallerContext} from the Spring Security context (JWT principal).
 * X-Pulse-* headers are IGNORED — they cannot spoof identity when auth is
 * enabled. This is the production-proof path.
 *
 * <p><b>Dev/test mode</b> ({@code pulse.auth.enabled=false}): Preserves
 * legacy behavior — resolves actor from X-Pulse-* headers with permissive
 * defaults. This mode is explicitly non-proof and confined to development.
 */
@Service
public class ActorResolverService {

    public static final String HEADER_USER_ID     = "X-Pulse-User-Id";
    public static final String HEADER_TENANT_ID   = "X-Pulse-Tenant-Id";
    public static final String HEADER_ROLES       = "X-Pulse-Roles";
    public static final String HEADER_AUTHOR_NAME = "X-Pulse-Author-Name";
    public static final String HEADER_AUTHOR_EMAIL = "X-Pulse-Author-Email";

    /** Legacy seeded dev builder. Matches the stub returned by /api/v1/auth/me when auth is disabled. */
    public static final String DEV_STUB_USER_ID = "01JUSER00000000000000000";
    public static final String DEV_STUB_AUTHOR_NAME = "Dev Builder";
    public static final String DEV_STUB_AUTHOR_EMAIL = "builder@pulse.dev";
    /**
     * Permissive default role set for dev/test mode only.
     * When auth is enabled, roles are derived from JWT claims exclusively.
     */
    static final Set<PulseRole> DEV_STUB_ROLES = EnumSet.allOf(PulseRole.class);

    private static final Logger log = LoggerFactory.getLogger(ActorResolverService.class);

    @Value("${pulse.auth.enabled:false}")
    private boolean authEnabled;

    /**
     * Resolve the calling actor for a UI / agent / system code path.
     *
     * <p>When auth is enabled, derives identity from the JWT principal in
     * Spring Security context. X-Pulse-* headers are ignored.
     *
     * <p>When auth is disabled (dev/test), falls back to header-based
     * resolution with permissive defaults.
     *
     * @param surface          declared caller surface
     * @param defaultTenantId  tenant the controller is operating against
     */
    public CallerContext resolve(CallerSurface surface, String defaultTenantId) {
        if (authEnabled) {
            return resolveFromSecurityContext(surface, defaultTenantId);
        }
        return resolveFromHeaders(surface, defaultTenantId);
    }

    /**
     * Resolve a Git author identity for user-attributed commits.
     */
    public AuthorIdentity resolveAuthorIdentity() {
        if (authEnabled) {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof JwtPrincipal principal) {
                return new AuthorIdentity(principal.email(), principal.email());
            }
        }
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (!(attrs instanceof ServletRequestAttributes servletAttrs)) {
            return new AuthorIdentity(DEV_STUB_AUTHOR_NAME, DEV_STUB_AUTHOR_EMAIL);
        }
        var request = servletAttrs.getRequest();
        String name = headerOrDefault(request.getHeader(HEADER_AUTHOR_NAME), DEV_STUB_AUTHOR_NAME);
        String email = headerOrDefault(request.getHeader(HEADER_AUTHOR_EMAIL), DEV_STUB_AUTHOR_EMAIL);
        return new AuthorIdentity(name, email);
    }

    /** Author identity used for user-attributed Git commits. */
    public record AuthorIdentity(String name, String email) {}

    // -----------------------------------------------------------------------
    //  Auth-enabled resolution (production path)
    // -----------------------------------------------------------------------

    private CallerContext resolveFromSecurityContext(CallerSurface surface, String defaultTenantId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof JwtPrincipal principal) {
            String userId = principal.userId();
            String tenantId = principal.tenantId();
            Set<PulseRole> roles = deriveRolesFromJwt(principal.role());
            return new CallerContext(userId, tenantId, roles, surface);
        }
        // No authenticated principal — return empty context (will be denied by policy)
        log.warn("Auth enabled but no JWT principal in SecurityContext for surface={}", surface);
        return new CallerContext(null, defaultTenantId, Set.of(), surface);
    }

    private Set<PulseRole> deriveRolesFromJwt(String roleName) {
        if (roleName == null || roleName.isBlank()) {
            return Set.of();
        }
        try {
            UserRole legacy = UserRole.valueOf(roleName);
            return PulseRole.fromLegacy(legacy);
        } catch (IllegalArgumentException e) {
            // Try direct PulseRole mapping
            try {
                return Set.of(PulseRole.valueOf(roleName.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e2) {
                log.warn("Cannot map JWT role '{}' to PulseRole", roleName);
                return Set.of();
            }
        }
    }

    // -----------------------------------------------------------------------
    //  Dev/test mode resolution (header-based, non-proof)
    // -----------------------------------------------------------------------

    private CallerContext resolveFromHeaders(CallerSurface surface, String defaultTenantId) {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (!(attrs instanceof ServletRequestAttributes servletAttrs)) {
            return new CallerContext(DEV_STUB_USER_ID, defaultTenantId, DEV_STUB_ROLES, surface);
        }
        var request = servletAttrs.getRequest();
        String userId = headerOrDefault(request.getHeader(HEADER_USER_ID), DEV_STUB_USER_ID);
        String tenantId = headerOrDefault(request.getHeader(HEADER_TENANT_ID), defaultTenantId);
        Set<PulseRole> roles = parseRoles(request.getHeader(HEADER_ROLES));
        return new CallerContext(userId, tenantId, roles, surface);
    }

    private static String headerOrDefault(String headerValue, String fallback) {
        if (headerValue == null || headerValue.isBlank()) {
            return fallback;
        }
        return headerValue.trim();
    }

    private static Set<PulseRole> parseRoles(String header) {
        if (header == null || header.isBlank()) {
            return DEV_STUB_ROLES;
        }
        Set<PulseRole> out = new LinkedHashSet<>();
        for (String raw : header.split(",")) {
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) continue;
            try {
                out.add(PulseRole.valueOf(trimmed.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException unknown) {
                log.warn("Ignoring unknown role in {} header: '{}'", HEADER_ROLES, trimmed);
            }
        }
        return out.isEmpty() ? Set.of() : out;
    }

    /**
     * Test/internal helper for resolving a deterministic actor without an
     * active HTTP request.
     */
    public static CallerContext explicit(String userId,
                                         String tenantId,
                                         Set<PulseRole> roles,
                                         CallerSurface surface) {
        return new CallerContext(
                userId == null ? DEV_STUB_USER_ID : userId,
                tenantId,
                roles == null ? DEV_STUB_ROLES : roles,
                surface);
    }

    /** Constants for tests that need to enumerate roles without rebuilding the enum set. */
    public static Set<PulseRole> defaultRoles() {
        return DEV_STUB_ROLES;
    }

    static Set<PulseRole> parseRolesForTest(String header) {
        return parseRoles(header);
    }

    static Set<PulseRole> rolesFromArray(PulseRole... roles) {
        return EnumSet.copyOf(Arrays.asList(roles));
    }
}
