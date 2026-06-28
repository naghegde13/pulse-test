package com.pulse.auth.controller;

import com.pulse.auth.controller.TenantGcpController.GcpConfigRequest;
import com.pulse.auth.controller.TenantGcpController.GcpCredentialRequest;
import com.pulse.auth.controller.TenantGcpController.RoleManifestRequest;
import com.pulse.auth.model.TenantGcpConfig;
import com.pulse.auth.service.GcpRoleManifestValidator;
import com.pulse.auth.service.GcpRoleManifestValidator.ValidationResult;
import com.pulse.auth.service.TenantGcpConfigService;
import com.pulse.auth.service.TenantGcpCredentialResolver;
import com.pulse.auth.service.TenantGcpCredentialService;
import com.pulse.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TenantGcpControllerTest {

    @Mock private TenantGcpConfigService configService;
    @Mock private TenantGcpCredentialService credentialService;
    @Mock private TenantGcpCredentialResolver credentialResolver;
    @Mock private GcpRoleManifestValidator roleValidator;
    @InjectMocks private TenantGcpController controller;

    // ---- GCP Config Endpoints ----

    @Test
    void getGcpConfig_found_returns200() {
        TenantGcpConfig config = new TenantGcpConfig();
        config.setTenantId("tenant-acme");
        config.setControlPlaneProjectId("pulse-proof-04261847");
        config.setGcpRegion("us-central1");
        config.setStatus("active");
        when(configService.getConfig("tenant-acme")).thenReturn(Optional.of(config));

        ResponseEntity<Map<String, Object>> response = controller.getGcpConfig("tenant-acme");

        assertEquals(200, response.getStatusCode().value());
        assertEquals("pulse-proof-04261847", response.getBody().get("gcpProjectId"));
        assertEquals("us-central1", response.getBody().get("gcpRegion"));
    }

    @Test
    void getGcpConfig_notFound_returns404() {
        when(configService.getConfig("missing")).thenReturn(Optional.empty());

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> controller.getGcpConfig("missing"));
        assertEquals(HttpStatus.NOT_FOUND, thrown.getStatusCode());
    }

    @Test
    void setGcpConfig_valid_returns200() {
        TenantGcpConfig config = new TenantGcpConfig();
        config.setTenantId("tenant-acme");
        config.setControlPlaneProjectId("pulse-proof-04261847");
        config.setGcpRegion("us-central1");
        config.setStatus("active");
        when(configService.setConfig(eq("tenant-acme"), eq("pulse-proof-04261847"), eq("us-central1")))
                .thenReturn(config);

        ResponseEntity<Map<String, Object>> response = controller.setGcpConfig(
                "tenant-acme",
                new GcpConfigRequest("pulse-proof-04261847", "us-central1"));

        assertEquals(200, response.getStatusCode().value());
        assertEquals("pulse-proof-04261847", response.getBody().get("gcpProjectId"));
    }

    @Test
    void setGcpConfig_tenantNotFound_returns404() {
        when(configService.setConfig(anyString(), anyString(), any()))
                .thenThrow(new ResourceNotFoundException("Tenant", "missing"));

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> controller.setGcpConfig("missing",
                        new GcpConfigRequest("project", "region")));
        assertEquals(HttpStatus.NOT_FOUND, thrown.getStatusCode());
    }

    @Test
    void setGcpConfig_nullBody_returns400() {
        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> controller.setGcpConfig("tenant-acme", null));
        assertEquals(HttpStatus.BAD_REQUEST, thrown.getStatusCode());
    }

    @Test
    void setGcpConfig_invalidArgs_returns400() {
        when(configService.setConfig(eq("tenant-acme"), isNull(), isNull()))
                .thenThrow(new IllegalArgumentException("gcpProjectId is required"));

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> controller.setGcpConfig("tenant-acme",
                        new GcpConfigRequest(null, null)));
        assertEquals(HttpStatus.BAD_REQUEST, thrown.getStatusCode());
    }

    // ---- GCP Credentials Endpoints ----

    @Test
    void getGcpCredentials_found_returnsRedacted() {
        Map<String, Object> redacted = new LinkedHashMap<>();
        redacted.put("serviceAccountEmail", "sa@p.iam.gserviceaccount.com");
        redacted.put("keyId", "key-123");
        redacted.put("privateKeyRedacted", true);
        when(credentialService.getRedactedCredential("tenant-acme"))
                .thenReturn(Optional.of(redacted));

        ResponseEntity<Map<String, Object>> response = controller.getGcpCredentials("tenant-acme");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(true, response.getBody().get("privateKeyRedacted"));
        assertFalse(response.getBody().toString().contains("PRIVATE KEY"));
    }

    @Test
    void getGcpCredentials_notFound_returns404() {
        when(credentialService.getRedactedCredential("missing")).thenReturn(Optional.empty());

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> controller.getGcpCredentials("missing"));
        assertEquals(HttpStatus.NOT_FOUND, thrown.getStatusCode());
    }

    @Test
    void submitGcpCredentials_valid_returns200() {
        Map<String, Object> readback = new LinkedHashMap<>();
        readback.put("status", "active");
        readback.put("privateKeyRedacted", true);
        when(credentialService.submitCredential(eq("tenant-acme"), anyString()))
                .thenReturn(readback);

        ResponseEntity<Map<String, Object>> response = controller.submitGcpCredentials(
                "tenant-acme",
                new GcpCredentialRequest("{\"test\": true}"));

        assertEquals(200, response.getStatusCode().value());
        assertEquals(true, response.getBody().get("privateKeyRedacted"));
    }

    @Test
    void submitGcpCredentials_nullBody_returns400() {
        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> controller.submitGcpCredentials("tenant-acme", null));
        assertEquals(HttpStatus.BAD_REQUEST, thrown.getStatusCode());
    }

    @Test
    void submitGcpCredentials_invalidJson_returns400() {
        when(credentialService.submitCredential(anyString(), anyString()))
                .thenThrow(new IllegalArgumentException("Invalid JSON"));

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> controller.submitGcpCredentials("tenant-acme",
                        new GcpCredentialRequest("bad")));
        assertEquals(HttpStatus.BAD_REQUEST, thrown.getStatusCode());
    }

    // ---- Identity Probe ----

    @Test
    void probeGcpIdentity_delegatesToResolver() {
        Map<String, Object> probeResult = new LinkedHashMap<>();
        probeResult.put("status", "ready");
        probeResult.put("credentialSource", "tenant_postgres");
        when(credentialResolver.probe("tenant-acme")).thenReturn(probeResult);

        ResponseEntity<Map<String, Object>> response = controller.probeGcpIdentity("tenant-acme");

        assertEquals(200, response.getStatusCode().value());
        assertEquals("ready", response.getBody().get("status"));
        assertEquals("tenant_postgres", response.getBody().get("credentialSource"));
    }

    // ---- Role Manifest Validation ----

    @Test
    void validateRoleManifest_valid_returns200() {
        when(roleValidator.validate(anyList())).thenReturn(
                new ValidationResult("valid", List.of(), List.of(), true, true));

        ResponseEntity<Map<String, Object>> response = controller.validateRoleManifest(
                "tenant-acme",
                new RoleManifestRequest(List.of("roles/storage.objectAdmin")));

        assertEquals(200, response.getStatusCode().value());
        assertEquals("valid", response.getBody().get("status"));
        assertEquals("OPERATOR_BLOCKED", response.getBody().get("iamBindingExecution"));
    }

    @Test
    void validateRoleManifest_rejected_returnsErrors() {
        when(roleValidator.validate(anyList())).thenReturn(
                new ValidationResult("rejected",
                        List.of("roles/owner is overbroad"),
                        List.of(), true, false));

        ResponseEntity<Map<String, Object>> response = controller.validateRoleManifest(
                "tenant-acme",
                new RoleManifestRequest(List.of("roles/owner")));

        assertEquals(200, response.getStatusCode().value());
        assertEquals("rejected", response.getBody().get("status"));
        assertEquals("OPERATOR_BLOCKED", response.getBody().get("iamBindingExecution"));
    }

    @Test
    void validateRoleManifest_nullBody_returns400() {
        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> controller.validateRoleManifest("tenant-acme", null));
        assertEquals(HttpStatus.BAD_REQUEST, thrown.getStatusCode());
    }

    @Test
    void getRecommendedRoleManifest_returns200() {
        TenantGcpConfig config = new TenantGcpConfig();
        config.setControlPlaneProjectId("pulse-proof-04261847");
        when(configService.getConfig("tenant-acme")).thenReturn(Optional.of(config));

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("gcpProjectId", "pulse-proof-04261847");
        manifest.put("minimumRoles", List.of());
        when(roleValidator.getRecommendedManifest("pulse-proof-04261847")).thenReturn(manifest);

        ResponseEntity<Map<String, Object>> response =
                controller.getRecommendedRoleManifest("tenant-acme");

        assertEquals(200, response.getStatusCode().value());
        assertEquals("OPERATOR_BLOCKED", response.getBody().get("iamBindingExecution"));
    }
}
