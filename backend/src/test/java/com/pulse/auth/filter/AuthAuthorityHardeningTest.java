package com.pulse.auth.filter;

import com.pulse.auth.policy.ActorResolverService;
import com.pulse.auth.policy.CallerContext;
import com.pulse.auth.policy.CallerSurface;
import com.pulse.auth.policy.PulseRole;
import com.pulse.auth.service.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PKT-0002 Auth Authority Hardening tests.
 *
 * Tests JWT filter, tenant membership filter, actor resolver in auth-enabled mode,
 * tenant isolation enforcement, and header-spoof prevention.
 */
class AuthAuthorityHardeningTest {

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        jwtService = new JwtService(
                "pulse-dev-secret-key-change-in-production-minimum-256-bits!!",
                28800);
    }

    // -----------------------------------------------------------------------
    //  JwtAuthenticationFilter tests
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("JwtAuthenticationFilter")
    class JwtFilterTests {

        private JwtAuthenticationFilter filter;

        @BeforeEach
        void setUp() {
            filter = new JwtAuthenticationFilter(jwtService);
        }

        @Test
        @DisplayName("Valid Bearer token populates SecurityContext with JwtPrincipal")
        void validToken_setsSecurityContext() throws ServletException, IOException {
            String token = jwtService.generateToken(
                    "user-123", "test@pulse.dev", "Test User", "tenant-home-lending",
                    "DATA_ENGINEER", List.of("pipeline:read", "pipeline:write"));

            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("Authorization", "Bearer " + token);
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilterInternal(request, response, chain);

            var auth = SecurityContextHolder.getContext().getAuthentication();
            assertNotNull(auth);
            assertInstanceOf(JwtPrincipal.class, auth.getPrincipal());
            JwtPrincipal principal = (JwtPrincipal) auth.getPrincipal();
            assertEquals("user-123", principal.userId());
            assertEquals("test@pulse.dev", principal.email());
            assertEquals("Test User", principal.displayName());
            assertEquals("tenant-home-lending", principal.tenantId());
            assertEquals("DATA_ENGINEER", principal.role());
            assertEquals(List.of("pipeline:read", "pipeline:write"), principal.permissions());
        }

        @Test
        @DisplayName("Missing Authorization header leaves SecurityContext empty")
        void noToken_noAuth() throws ServletException, IOException {
            MockHttpServletRequest request = new MockHttpServletRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilterInternal(request, response, chain);

            assertNull(SecurityContextHolder.getContext().getAuthentication());
        }

        @Test
        @DisplayName("Invalid token leaves SecurityContext empty")
        void invalidToken_noAuth() throws ServletException, IOException {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("Authorization", "Bearer totally-invalid-token");
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilterInternal(request, response, chain);

            assertNull(SecurityContextHolder.getContext().getAuthentication());
        }

        @Test
        @DisplayName("Expired token leaves SecurityContext empty")
        void expiredToken_noAuth() throws ServletException, IOException {
            // Create a JwtService with 0 second expiry to get an expired token
            JwtService shortLived = new JwtService(
                    "pulse-dev-secret-key-change-in-production-minimum-256-bits!!", 0);
            String token = shortLived.generateToken(
                    "user-123", "test@pulse.dev", "t1", "DATA_ENGINEER", List.of());

            // Small delay to ensure expiry
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}

            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("Authorization", "Bearer " + token);
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilterInternal(request, response, chain);

            assertNull(SecurityContextHolder.getContext().getAuthentication());
        }
    }

    // -----------------------------------------------------------------------
    //  TenantMembershipFilter tests
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("TenantMembershipFilter")
    class TenantMembershipFilterTests {

        private TenantMembershipFilter filter;

        @BeforeEach
        void setUp() {
            filter = new TenantMembershipFilter();
        }

        @Test
        @DisplayName("Matching tenant path passes through")
        void matchingTenant_passes() throws ServletException, IOException {
            setAuthenticated("user-1", "test@pulse.dev", "tenant-home-lending", "DATA_ENGINEER");

            MockHttpServletRequest request = new MockHttpServletRequest("GET",
                    "/api/v1/tenants/tenant-home-lending/domains");
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilterInternal(request, response, chain);

            assertEquals(200, response.getStatus());
            assertNotNull(chain.getRequest()); // chain was called
        }

        @Test
        @DisplayName("Mismatched tenant path returns 403")
        void mismatchedTenant_returns403() throws ServletException, IOException {
            setAuthenticated("user-1", "test@pulse.dev", "tenant-home-lending", "DATA_ENGINEER");

            MockHttpServletRequest request = new MockHttpServletRequest("GET",
                    "/api/v1/tenants/tenant-unsecured-lending/domains");
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilterInternal(request, response, chain);

            assertEquals(403, response.getStatus());
            assertTrue(response.getContentAsString().contains("tenant_membership"));
        }

        @Test
        @DisplayName("Legacy ADMIN role does not bypass tenant membership check")
        void legacyAdminRole_doesNotBypassTenantCheck() throws ServletException, IOException {
            setAuthenticated("admin-1", "admin@pulse.dev", "tenant-home-lending", "ADMIN");

            MockHttpServletRequest request = new MockHttpServletRequest("GET",
                    "/api/v1/tenants/tenant-unsecured-lending/domains");
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilterInternal(request, response, chain);

            assertEquals(403, response.getStatus());
        }

        @Test
        @DisplayName("Explicit PLATFORM_ADMIN role bypasses tenant membership check")
        void platformAdminRole_bypassesTenantCheck() throws ServletException, IOException {
            setAuthenticated("platform-1", "platform@pulse.dev", "tenant-home-lending", "PLATFORM_ADMIN");

            MockHttpServletRequest request = new MockHttpServletRequest("GET",
                    "/api/v1/tenants/tenant-unsecured-lending/domains");
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilterInternal(request, response, chain);

            assertEquals(200, response.getStatus());
            assertNotNull(chain.getRequest());
        }

        @Test
        @DisplayName("Non-tenant-scoped path passes through without check")
        void nonTenantPath_passes() throws ServletException, IOException {
            setAuthenticated("user-1", "test@pulse.dev", "tenant-home-lending", "DATA_ENGINEER");

            MockHttpServletRequest request = new MockHttpServletRequest("GET",
                    "/api/v1/auth/me");
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilterInternal(request, response, chain);

            assertEquals(200, response.getStatus());
            assertNotNull(chain.getRequest());
        }

        @Test
        @DisplayName("No authenticated principal passes through (handled by SecurityConfig)")
        void noAuth_passes() throws ServletException, IOException {
            MockHttpServletRequest request = new MockHttpServletRequest("GET",
                    "/api/v1/tenants/tenant-home-lending/domains");
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilterInternal(request, response, chain);

            assertEquals(200, response.getStatus());
            assertNotNull(chain.getRequest());
        }
    }

    // -----------------------------------------------------------------------
    //  Header-spoof prevention tests
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Header-spoof prevention (auth enabled)")
    class HeaderSpoofTests {

        private ActorResolverService actorResolver;

        @BeforeEach
        void setUp() {
            actorResolver = new ActorResolverService();
            // Set authEnabled via reflection since it's @Value injected
            try {
                var field = ActorResolverService.class.getDeclaredField("authEnabled");
                field.setAccessible(true);
                field.setBoolean(actorResolver, true);
            } catch (Exception e) {
                fail("Cannot set authEnabled: " + e.getMessage());
            }
        }

        @Test
        @DisplayName("When auth enabled, resolver uses SecurityContext not headers")
        void authEnabled_usesSecurityContext() {
            setAuthenticated("real-user", "real@pulse.dev", "tenant-home-lending", "DATA_ENGINEER");

            CallerContext ctx = actorResolver.resolve(CallerSurface.UI, "tenant-home-lending");

            assertEquals("real-user", ctx.userId());
            assertEquals("tenant-home-lending", ctx.tenantId());
            assertTrue(ctx.roles().contains(PulseRole.PIPELINE_DEVELOPER));
        }

        @Test
        @DisplayName("When auth enabled with no SecurityContext, returns empty context")
        void authEnabled_noContext_returnsEmpty() {
            // No authentication set
            CallerContext ctx = actorResolver.resolve(CallerSurface.UI, "tenant-home-lending");

            assertNull(ctx.userId());
            assertTrue(ctx.roles().isEmpty());
        }

        @Test
        @DisplayName("X-Pulse-User-Id header cannot spoof identity when auth enabled")
        void authEnabled_headerIgnored() {
            setAuthenticated("real-user", "real@pulse.dev", "tenant-home-lending", "DATA_ENGINEER");
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(ActorResolverService.HEADER_USER_ID, "spoofed-user");
            request.addHeader(ActorResolverService.HEADER_TENANT_ID, "spoofed-tenant");
            request.addHeader(ActorResolverService.HEADER_ROLES, "PLATFORM_ADMIN");
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

            CallerContext ctx = actorResolver.resolve(CallerSurface.UI, "tenant-home-lending");

            assertEquals("real-user", ctx.userId());
            assertEquals("tenant-home-lending", ctx.tenantId());
            assertFalse(ctx.roles().contains(PulseRole.PLATFORM_ADMIN));
            assertNotEquals("spoofed-user", ctx.userId());
            RequestContextHolder.resetRequestAttributes();
        }
    }

    // -----------------------------------------------------------------------
    //  Dev-mode compatibility tests
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Dev-mode compatibility (auth disabled)")
    class DevModeTests {

        private ActorResolverService actorResolver;

        @BeforeEach
        void setUp() {
            actorResolver = new ActorResolverService();
            // authEnabled defaults to false — no reflection needed
            try {
                var field = ActorResolverService.class.getDeclaredField("authEnabled");
                field.setAccessible(true);
                field.setBoolean(actorResolver, false);
            } catch (Exception e) {
                fail("Cannot set authEnabled: " + e.getMessage());
            }
        }

        @Test
        @DisplayName("Dev mode returns stub user with all roles")
        void devMode_returnsStub() {
            // No request context
            CallerContext ctx = actorResolver.resolve(CallerSurface.UI, "tenant-home-lending");

            assertEquals(ActorResolverService.DEV_STUB_USER_ID, ctx.userId());
            assertEquals("tenant-home-lending", ctx.tenantId());
            assertEquals(ActorResolverService.defaultRoles(), ctx.roles());
        }
    }

    // -----------------------------------------------------------------------
    //  TenantIsolationEnforcer tests
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("TenantIsolationEnforcer")
    class TenantIsolationTests {

        @Test
        @DisplayName("Matching tenant passes")
        void matchingTenant_noException() {
            setAuthenticated("user-1", "test@pulse.dev", "tenant-home-lending", "DATA_ENGINEER");
            assertDoesNotThrow(() -> TenantIsolationEnforcer.enforce("tenant-home-lending"));
        }

        @Test
        @DisplayName("Mismatched tenant throws 403")
        void mismatchedTenant_throws403() {
            setAuthenticated("user-1", "test@pulse.dev", "tenant-home-lending", "DATA_ENGINEER");
            var ex = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                    () -> TenantIsolationEnforcer.enforce("tenant-unsecured-lending"));
            assertEquals(403, ex.getStatusCode().value());
        }

        @Test
        @DisplayName("Legacy ADMIN does not bypass tenant check")
        void admin_doesNotBypassTenantCheck() {
            setAuthenticated("admin-1", "admin@pulse.dev", "tenant-home-lending", "ADMIN");
            var ex = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                    () -> TenantIsolationEnforcer.enforce("tenant-unsecured-lending"));
            assertEquals(403, ex.getStatusCode().value());
        }

        @Test
        @DisplayName("PLATFORM_ADMIN bypasses tenant check")
        void platformAdmin_bypassesTenantCheck() {
            setAuthenticated("platform-1", "platform@pulse.dev", "tenant-home-lending", "PLATFORM_ADMIN");
            assertDoesNotThrow(() -> TenantIsolationEnforcer.enforce("tenant-unsecured-lending"));
        }

        @Test
        @DisplayName("No auth (dev mode) passes without check")
        void noAuth_passes() {
            SecurityContextHolder.clearContext();
            assertDoesNotThrow(() -> TenantIsolationEnforcer.enforce("any-tenant"));
        }
    }

    // -----------------------------------------------------------------------
    //  AuthController /auth/me principal-derived tests
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("AuthController /auth/me contract")
    class AuthMeTests {

        @Test
        @DisplayName("JWT claims correctly map to JwtPrincipal fields")
        void jwtClaimsMapCorrectly() {
            String token = jwtService.generateToken(
                    "user-42", "eng@acme.dev", "acme-lending",
                    "DATA_ENGINEER", List.of("pipeline:read", "pipeline:write", "chat:use"));

            var claims = jwtService.parseToken(token);
            assertEquals("user-42", claims.getSubject());
            assertEquals("eng@acme.dev", claims.get("email", String.class));
            assertEquals("acme-lending", claims.get("tenantId", String.class));
            assertEquals("DATA_ENGINEER", claims.get("role", String.class));
            assertEquals("eng@acme.dev", claims.get("displayName", String.class));
        }
    }

    // -----------------------------------------------------------------------
    //  Tenant membership matrix tests
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Tenant membership matrix")
    class TenantMembershipMatrixTests {

        @Test
        @DisplayName("Authorized tenant succeeds")
        void authorizedTenant_succeeds() throws ServletException, IOException {
            TenantMembershipFilter filter = new TenantMembershipFilter();
            setAuthenticated("user-1", "test@pulse.dev", "acme-lending", "DATA_ENGINEER");

            MockHttpServletRequest request = new MockHttpServletRequest("GET",
                    "/api/v1/tenants/acme-lending/sors");
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilterInternal(request, response, chain);

            assertNotNull(chain.getRequest());
            assertEquals(200, response.getStatus());
        }

        @Test
        @DisplayName("Cross-tenant access denied for non-admin")
        void crossTenant_denied() throws ServletException, IOException {
            TenantMembershipFilter filter = new TenantMembershipFilter();
            setAuthenticated("user-1", "test@pulse.dev", "acme-lending", "DATA_ENGINEER");

            MockHttpServletRequest request = new MockHttpServletRequest("GET",
                    "/api/v1/tenants/other-tenant/sors");
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilterInternal(request, response, chain);

            assertEquals(403, response.getStatus());
        }

        @Test
        @DisplayName("Legacy ADMIN cross-tenant access denied")
        void adminCrossTenant_denied() throws ServletException, IOException {
            TenantMembershipFilter filter = new TenantMembershipFilter();
            setAuthenticated("admin-1", "admin@pulse.dev", "acme-lending", "ADMIN");

            MockHttpServletRequest request = new MockHttpServletRequest("GET",
                    "/api/v1/tenants/other-tenant/sors");
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilterInternal(request, response, chain);

            assertEquals(403, response.getStatus());
        }

        @Test
        @DisplayName("Explicit PLATFORM_ADMIN cross-tenant access allowed")
        void platformAdminCrossTenant_allowed() throws ServletException, IOException {
            TenantMembershipFilter filter = new TenantMembershipFilter();
            setAuthenticated("platform-1", "platform@pulse.dev", "acme-lending", "PLATFORM_ADMIN");

            MockHttpServletRequest request = new MockHttpServletRequest("GET",
                    "/api/v1/tenants/other-tenant/sors");
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilterInternal(request, response, chain);

            assertNotNull(chain.getRequest());
            assertEquals(200, response.getStatus());
        }
    }

    // -----------------------------------------------------------------------
    //  Role derivation tests
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Role derivation from JWT")
    class RoleDerivationTests {

        private ActorResolverService actorResolver;

        @BeforeEach
        void setUp() {
            actorResolver = new ActorResolverService();
            try {
                var field = ActorResolverService.class.getDeclaredField("authEnabled");
                field.setAccessible(true);
                field.setBoolean(actorResolver, true);
            } catch (Exception e) {
                fail("Cannot set authEnabled: " + e.getMessage());
            }
        }

        @Test
        @DisplayName("DATA_ENGINEER maps to TENANT_USER + PIPELINE_DEVELOPER")
        void dataEngineerMapping() {
            setAuthenticated("u1", "e@p.dev", "t1", "DATA_ENGINEER");
            CallerContext ctx = actorResolver.resolve(CallerSurface.UI, "t1");
            assertTrue(ctx.roles().contains(PulseRole.TENANT_USER));
            assertTrue(ctx.roles().contains(PulseRole.PIPELINE_DEVELOPER));
            assertFalse(ctx.roles().contains(PulseRole.PLATFORM_ADMIN));
        }

        @Test
        @DisplayName("ADMIN maps to full set including TENANT_ADMIN")
        void adminMapping() {
            setAuthenticated("u1", "e@p.dev", "t1", "ADMIN");
            CallerContext ctx = actorResolver.resolve(CallerSurface.UI, "t1");
            assertTrue(ctx.roles().contains(PulseRole.TENANT_USER));
            assertTrue(ctx.roles().contains(PulseRole.PIPELINE_DEVELOPER));
            assertTrue(ctx.roles().contains(PulseRole.DEPLOYMENT_OPERATOR));
            assertTrue(ctx.roles().contains(PulseRole.TENANT_ADMIN));
            assertFalse(ctx.roles().contains(PulseRole.PLATFORM_ADMIN));
        }

        @Test
        @DisplayName("DEPLOYER maps to DEPLOYMENT_OPERATOR + PULL_REQUEST_APPROVER")
        void deployerMapping() {
            setAuthenticated("u1", "e@p.dev", "t1", "DEPLOYER");
            CallerContext ctx = actorResolver.resolve(CallerSurface.UI, "t1");
            assertTrue(ctx.roles().contains(PulseRole.DEPLOYMENT_OPERATOR));
            assertTrue(ctx.roles().contains(PulseRole.PULL_REQUEST_APPROVER));
            assertFalse(ctx.roles().contains(PulseRole.PIPELINE_DEVELOPER));
        }
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    private void setAuthenticated(String userId, String email, String tenantId, String role) {
        JwtPrincipal principal = new JwtPrincipal(userId, email, email, tenantId, role, List.of());
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(principal, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
