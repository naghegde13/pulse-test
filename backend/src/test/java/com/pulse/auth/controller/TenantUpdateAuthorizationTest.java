package com.pulse.auth.controller;

import com.pulse.auth.controller.TenantController.UpdateTenantRequest;
import com.pulse.auth.filter.JwtPrincipal;
import com.pulse.auth.model.Tenant;
import com.pulse.auth.service.TenantService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PKT-0010 — focused authorization tests for PATCH /api/v1/tenants/{tenantId}.
 *
 * <p>Validates:
 * <ul>
 *   <li>TENANT_USER (non-admin same-tenant) is denied</li>
 *   <li>DATA_ENGINEER (non-admin same-tenant) is denied</li>
 *   <li>CITIZEN (non-admin same-tenant) is denied</li>
 *   <li>ADMIN (legacy → TENANT_ADMIN, same-tenant) is allowed</li>
 *   <li>TENANT_ADMIN (direct role, same-tenant) is allowed</li>
 *   <li>PLATFORM_ADMIN (cross-tenant) is allowed</li>
 *   <li>Cross-tenant non-PLATFORM_ADMIN is denied</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class TenantUpdateAuthorizationTest {

    private static final String TENANT_ID = "tenant-acme-lending";
    private static final String OTHER_TENANT = "tenant-other";

    @Mock private TenantService tenantService;

    private TenantController controller;

    @BeforeEach
    void setUp() {
        controller = new TenantController(tenantService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ---- Denied: non-admin same-tenant actors ----

    @Test
    @DisplayName("TENANT_USER same-tenant → 403 (non-admin)")
    void tenantUser_sameTenant_denied() {
        setAuth(TENANT_ID, "TENANT_USER");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.updateTenant(TENANT_ID, new UpdateTenantRequest("New Name")));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(tenantService, never()).updateTenant(TENANT_ID, "New Name");
    }

    @Test
    @DisplayName("DATA_ENGINEER same-tenant → 403 (maps to PIPELINE_DEVELOPER, not TENANT_ADMIN)")
    void dataEngineer_sameTenant_denied() {
        setAuth(TENANT_ID, "DATA_ENGINEER");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.updateTenant(TENANT_ID, new UpdateTenantRequest("New Name")));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(tenantService, never()).updateTenant(TENANT_ID, "New Name");
    }

    @Test
    @DisplayName("CITIZEN same-tenant → 403 (maps to TENANT_USER only)")
    void citizen_sameTenant_denied() {
        setAuth(TENANT_ID, "CITIZEN");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.updateTenant(TENANT_ID, new UpdateTenantRequest("New Name")));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    @DisplayName("DEPLOYER same-tenant → 403 (maps to DEPLOYMENT_OPERATOR, not TENANT_ADMIN)")
    void deployer_sameTenant_denied() {
        setAuth(TENANT_ID, "DEPLOYER");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.updateTenant(TENANT_ID, new UpdateTenantRequest("New Name")));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    // ---- Allowed: admin actors ----

    @Test
    @DisplayName("ADMIN same-tenant → 200 (legacy ADMIN maps to TENANT_ADMIN)")
    void legacyAdmin_sameTenant_allowed() {
        setAuth(TENANT_ID, "ADMIN");
        stubUpdateTenant();

        var response = controller.updateTenant(TENANT_ID, new UpdateTenantRequest("New Name"));

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("New Name", response.getBody().get("name"));
    }

    @Test
    @DisplayName("TENANT_ADMIN same-tenant → 200")
    void tenantAdmin_sameTenant_allowed() {
        setAuth(TENANT_ID, "TENANT_ADMIN");
        stubUpdateTenant();

        var response = controller.updateTenant(TENANT_ID, new UpdateTenantRequest("New Name"));

        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    @DisplayName("PLATFORM_ADMIN same-tenant → 200")
    void platformAdmin_sameTenant_allowed() {
        setAuth(TENANT_ID, "PLATFORM_ADMIN");
        stubUpdateTenant();

        var response = controller.updateTenant(TENANT_ID, new UpdateTenantRequest("New Name"));

        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    @DisplayName("PLATFORM_ADMIN cross-tenant → 200 (bypasses tenant check)")
    void platformAdmin_crossTenant_allowed() {
        setAuth(OTHER_TENANT, "PLATFORM_ADMIN");
        stubUpdateTenant();

        var response = controller.updateTenant(TENANT_ID, new UpdateTenantRequest("New Name"));

        assertEquals(200, response.getStatusCode().value());
    }

    // ---- Denied: cross-tenant non-PLATFORM_ADMIN ----

    @Test
    @DisplayName("ADMIN cross-tenant → 403 (ADMIN is TENANT_ADMIN, not PLATFORM_ADMIN)")
    void legacyAdmin_crossTenant_denied() {
        setAuth(OTHER_TENANT, "ADMIN");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.updateTenant(TENANT_ID, new UpdateTenantRequest("New Name")));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    @DisplayName("TENANT_ADMIN cross-tenant → 403 (tenant-scoped admin, not cross-tenant)")
    void tenantAdmin_crossTenant_denied() {
        setAuth(OTHER_TENANT, "TENANT_ADMIN");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.updateTenant(TENANT_ID, new UpdateTenantRequest("New Name")));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    // ---- No auth (dev mode): allowed ----

    @Test
    @DisplayName("No authentication (dev mode) → 200")
    void noAuth_devMode_allowed() {
        // No SecurityContext set — simulates auth disabled
        stubUpdateTenant();

        var response = controller.updateTenant(TENANT_ID, new UpdateTenantRequest("New Name"));

        assertEquals(200, response.getStatusCode().value());
    }

    // ---- Helpers ----

    private void setAuth(String tenantId, String role) {
        JwtPrincipal principal = new JwtPrincipal(
                "user-1", "user@example.com", "Test User",
                tenantId, role, List.of());
        var authToken = new UsernamePasswordAuthenticationToken(
                principal, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authToken);
    }

    private void stubUpdateTenant() {
        Tenant updated = new Tenant();
        updated.setId(TENANT_ID);
        updated.setName("New Name");
        updated.setSlug("acme-lending");
        updated.setOrigin("api");
        updated.setStatus("active");
        when(tenantService.updateTenant(TENANT_ID, "New Name")).thenReturn(updated);
    }
}
