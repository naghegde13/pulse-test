package com.pulse.tenant.controller;

import com.pulse.auth.filter.JwtPrincipal;
import com.pulse.tenant.model.TenantGcpRuntimeTopology;
import com.pulse.tenant.service.GcpIamManifestService;
import com.pulse.tenant.service.GcpRuntimeTopologyService;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PKT-0025 — focused authorization tests for PUT /api/v1/tenants/{tenantId}/gcp-runtime-topology.
 *
 * <p>Matches the TenantController.updateTenant authorization pattern:
 * <ul>
 *   <li>Same-tenant TENANT_ADMIN → allowed</li>
 *   <li>Same-tenant PLATFORM_ADMIN → allowed</li>
 *   <li>Cross-tenant PLATFORM_ADMIN → allowed (bypasses tenant check)</li>
 *   <li>Same-tenant DATA_ENGINEER (non-admin) → denied 403</li>
 *   <li>Same-tenant CITIZEN (non-admin) → denied 403</li>
 *   <li>Cross-tenant TENANT_ADMIN → denied 403</li>
 *   <li>Cross-tenant ADMIN → denied 403</li>
 *   <li>No auth (dev mode) → allowed (no SecurityContext)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class TopologyMutationAuthorizationTest {

    private static final String TENANT_ID = "tenant-acme-lending";
    private static final String OTHER_TENANT = "tenant-other";

    @Mock private GcpRuntimeTopologyService topologyService;
    @Mock private GcpIamManifestService iamManifestService;

    private TenantGcpRuntimeTopologyController controller;

    @BeforeEach
    void setUp() {
        controller = new TenantGcpRuntimeTopologyController(topologyService, iamManifestService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ---- Allowed: admin actors ----

    @Test
    @DisplayName("ADMIN same-tenant → 200 (legacy ADMIN maps to TENANT_ADMIN)")
    void legacyAdmin_sameTenant_allowed() {
        setAuth(TENANT_ID, "ADMIN");
        stubSetTopology();

        var response = controller.setTopology(TENANT_ID, minimalRequest());
        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    @DisplayName("TENANT_ADMIN same-tenant → 200")
    void tenantAdmin_sameTenant_allowed() {
        setAuth(TENANT_ID, "TENANT_ADMIN");
        stubSetTopology();

        var response = controller.setTopology(TENANT_ID, minimalRequest());
        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    @DisplayName("PLATFORM_ADMIN same-tenant → 200")
    void platformAdmin_sameTenant_allowed() {
        setAuth(TENANT_ID, "PLATFORM_ADMIN");
        stubSetTopology();

        var response = controller.setTopology(TENANT_ID, minimalRequest());
        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    @DisplayName("PLATFORM_ADMIN cross-tenant → 200 (bypasses tenant check)")
    void platformAdmin_crossTenant_allowed() {
        setAuth(OTHER_TENANT, "PLATFORM_ADMIN");
        stubSetTopology();

        var response = controller.setTopology(TENANT_ID, minimalRequest());
        assertEquals(200, response.getStatusCode().value());
    }

    // ---- Denied: same-tenant non-admin actors ----

    @Test
    @DisplayName("DATA_ENGINEER same-tenant → 403 (maps to PIPELINE_DEVELOPER, not TENANT_ADMIN)")
    void dataEngineer_sameTenant_denied() {
        setAuth(TENANT_ID, "DATA_ENGINEER");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.setTopology(TENANT_ID, minimalRequest()));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertTrue_contains(ex.getReason(), "TENANT_ADMIN");
        verify(topologyService, never()).setTopology(any(), any());
    }

    @Test
    @DisplayName("CITIZEN same-tenant → 403 (maps to TENANT_USER only)")
    void citizen_sameTenant_denied() {
        setAuth(TENANT_ID, "CITIZEN");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.setTopology(TENANT_ID, minimalRequest()));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(topologyService, never()).setTopology(any(), any());
    }

    @Test
    @DisplayName("DEPLOYER same-tenant → 403 (maps to DEPLOYMENT_OPERATOR, not TENANT_ADMIN)")
    void deployer_sameTenant_denied() {
        setAuth(TENANT_ID, "DEPLOYER");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.setTopology(TENANT_ID, minimalRequest()));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    @DisplayName("TENANT_USER same-tenant → 403 (non-admin)")
    void tenantUser_sameTenant_denied() {
        setAuth(TENANT_ID, "TENANT_USER");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.setTopology(TENANT_ID, minimalRequest()));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    // ---- Denied: cross-tenant non-PLATFORM_ADMIN ----

    @Test
    @DisplayName("TENANT_ADMIN cross-tenant → 403 (tenant-scoped admin, not cross-tenant)")
    void tenantAdmin_crossTenant_denied() {
        setAuth(OTHER_TENANT, "TENANT_ADMIN");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.setTopology(TENANT_ID, minimalRequest()));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertTrue_contains(ex.getReason(), "tenant mismatch");
    }

    @Test
    @DisplayName("ADMIN cross-tenant → 403 (ADMIN is TENANT_ADMIN, not PLATFORM_ADMIN)")
    void legacyAdmin_crossTenant_denied() {
        setAuth(OTHER_TENANT, "ADMIN");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.setTopology(TENANT_ID, minimalRequest()));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    @DisplayName("DATA_ENGINEER cross-tenant → 403 (tenant mismatch fires first)")
    void dataEngineer_crossTenant_denied() {
        setAuth(OTHER_TENANT, "DATA_ENGINEER");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.setTopology(TENANT_ID, minimalRequest()));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    // ---- No auth (dev mode): allowed ----

    @Test
    @DisplayName("No authentication (dev mode) → 200")
    void noAuth_devMode_allowed() {
        stubSetTopology();

        var response = controller.setTopology(TENANT_ID, minimalRequest());
        assertEquals(200, response.getStatusCode().value());
    }

    // ---- Read endpoints remain open to authorized tenant members ----

    @Test
    @DisplayName("DATA_ENGINEER same-tenant can read topology (GET not gated)")
    void dataEngineer_canReadTopology() {
        setAuth(TENANT_ID, "DATA_ENGINEER");
        TenantGcpRuntimeTopology t = new TenantGcpRuntimeTopology();
        t.setTenantId(TENANT_ID);
        when(topologyService.getTopology(TENANT_ID)).thenReturn(java.util.Optional.of(t));
        when(topologyService.buildReadback(t)).thenReturn(Map.of("tenantId", TENANT_ID));

        var response = controller.getTopology(TENANT_ID);
        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    @DisplayName("DATA_ENGINEER same-tenant can read IAM manifest (GET not gated)")
    void dataEngineer_canReadIamManifest() {
        setAuth(TENANT_ID, "DATA_ENGINEER");
        when(iamManifestService.generateManifest(TENANT_ID))
                .thenReturn(Map.of("status", "generated"));

        var response = controller.getIamManifest(TENANT_ID);
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

    private void stubSetTopology() {
        TenantGcpRuntimeTopology saved = new TenantGcpRuntimeTopology();
        saved.setTenantId(TENANT_ID);
        when(topologyService.setTopology(eq(TENANT_ID), any())).thenReturn(saved);
        when(topologyService.buildReadback(saved)).thenReturn(Map.of("tenantId", TENANT_ID));
    }

    private static TenantGcpRuntimeTopologyController.TopologyRequest minimalRequest() {
        return new TenantGcpRuntimeTopologyController.TopologyRequest(
                "proj", "env", "us-central1", "bucket", "dags/", "plugins/", "data/", "logs/",
                "proj", "us-central1", "sa@proj.iam", "default", "default", "staging",
                "proj", "us-central1", "bronze", "silver", "gold",
                "conn", "us-central1", "bq@proj.iam",
                "iceberg", "evidence", "ev_ds",
                "proj", "proj", "log-bucket", "cp@proj.iam"
        );
    }

    private static void assertTrue_contains(String actual, String expected) {
        if (actual == null || !actual.contains(expected)) {
            throw new AssertionError("Expected string containing '" + expected + "' but got: " + actual);
        }
    }
}
