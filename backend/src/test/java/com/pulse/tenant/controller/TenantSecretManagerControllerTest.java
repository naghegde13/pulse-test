package com.pulse.tenant.controller;

import com.pulse.tenant.controller.TenantSecretManagerController.TenantSecretManagerBindingRequest;
import com.pulse.tenant.model.TenantGcpRuntimeTopology;
import com.pulse.tenant.service.TenantSecretManagerBindingService;
import com.pulse.tenant.service.TenantSecretManagerBindingService.ValidationError;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PKT-FINAL-5 / BUG-54 / BUG-2026-05-26-58: Unit tests for
 * {@link TenantSecretManagerController}.
 */
@ExtendWith(MockitoExtension.class)
class TenantSecretManagerControllerTest {

    private static final String TENANT_ID = "tenant-acme";

    @Mock private TenantSecretManagerBindingService bindingService;

    private TenantSecretManagerController controller;

    @BeforeEach
    void setUp() {
        controller = new TenantSecretManagerController(bindingService);
    }

    @Test
    void get_existingBinding_returnsReadback() {
        TenantGcpRuntimeTopology topology = new TenantGcpRuntimeTopology();
        topology.setTenantId(TENANT_ID);
        topology.setSecretAuthorityMode("GCP_SECRET_MANAGER");
        topology.setSecretManagerProjectId("acme-secrets");
        when(bindingService.getBinding(TENANT_ID)).thenReturn(Optional.of(topology));

        // Controller forwards to service-built readback; service is responsible
        // for the DB→wire reverse mapping. We assert pass-through here and
        // cover the actual mapping in TenantSecretManagerBindingServiceTest.
        Map<String, Object> readback = new LinkedHashMap<>();
        readback.put("tenantId", TENANT_ID);
        readback.put("mode", "TENANT_GCP_SECRET_MANAGER");
        readback.put("gsmProjectId", "acme-secrets");
        when(bindingService.buildReadback(TENANT_ID, topology)).thenReturn(readback);

        var response = controller.getBinding(TENANT_ID);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("TENANT_GCP_SECRET_MANAGER", response.getBody().get("mode"));
        assertEquals("acme-secrets", response.getBody().get("gsmProjectId"));
    }

    @Test
    void get_missingBinding_returnsDefaultLocalStub() {
        when(bindingService.getBinding(TENANT_ID)).thenReturn(Optional.empty());

        Map<String, Object> defaultReadback = new LinkedHashMap<>();
        defaultReadback.put("tenantId", TENANT_ID);
        defaultReadback.put("mode", "LOCAL_STUB");
        defaultReadback.put("gsmProjectId", null);
        when(bindingService.buildDefaultReadback(TENANT_ID)).thenReturn(defaultReadback);

        var response = controller.getBinding(TENANT_ID);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("LOCAL_STUB", response.getBody().get("mode"));
    }

    @Test
    void put_localStub_succeeds() {
        when(bindingService.validate(eq("LOCAL_STUB"), any())).thenReturn(Optional.empty());
        TenantGcpRuntimeTopology saved = new TenantGcpRuntimeTopology();
        saved.setTenantId(TENANT_ID);
        saved.setSecretAuthorityMode("LOCAL_STUB");
        when(bindingService.upsert(eq(TENANT_ID), any(), eq("LOCAL_STUB"), any(), any()))
                .thenReturn(saved);
        Map<String, Object> readback = new LinkedHashMap<>();
        readback.put("mode", "LOCAL_STUB");
        when(bindingService.buildReadback(TENANT_ID, saved)).thenReturn(readback);

        var response = controller.setBinding(TENANT_ID,
                new TenantSecretManagerBindingRequest("LOCAL_STUB", null, null));

        assertEquals(200, response.getStatusCode().value());
        assertEquals("LOCAL_STUB", response.getBody().get("mode"));
    }

    @Test
    void put_gcpSecretManager_missingProjectId_returns400() {
        when(bindingService.validate(eq("GCP_SECRET_MANAGER"), any()))
                .thenReturn(Optional.of(new ValidationError("MISSING_GSM_PROJECT_ID",
                        "gsmProjectId is required when mode=GCP_SECRET_MANAGER")));

        var ex = assertThrows(ResponseStatusException.class, () ->
                controller.setBinding(TENANT_ID,
                        new TenantSecretManagerBindingRequest("GCP_SECRET_MANAGER", null, null)));

        assertEquals(400, ex.getStatusCode().value());
        verify(bindingService, never()).upsert(any(), any(), any(), any(), any());
    }

    @Test
    void put_tenantGcpSecretManager_missingProjectId_returns400() {
        // Wire-format alias must be rejected with the same 400 contract.
        when(bindingService.validate(eq("TENANT_GCP_SECRET_MANAGER"), any()))
                .thenReturn(Optional.of(new ValidationError("MISSING_GSM_PROJECT_ID",
                        "gsmProjectId is required when mode=GCP_SECRET_MANAGER")));

        var ex = assertThrows(ResponseStatusException.class, () ->
                controller.setBinding(TENANT_ID,
                        new TenantSecretManagerBindingRequest("TENANT_GCP_SECRET_MANAGER", null, null)));

        assertEquals(400, ex.getStatusCode().value());
        verify(bindingService, never()).upsert(any(), any(), any(), any(), any());
    }

    @Test
    void put_missingRequestBody_returns400() {
        var ex = assertThrows(ResponseStatusException.class, () ->
                controller.setBinding(TENANT_ID, null));

        assertEquals(400, ex.getStatusCode().value());
    }

    @Test
    void put_invalidMode_returns400() {
        when(bindingService.validate(eq("HASHICORP"), any()))
                .thenReturn(Optional.of(new ValidationError("INVALID_MODE", "bad mode")));

        var ex = assertThrows(ResponseStatusException.class, () ->
                controller.setBinding(TENANT_ID,
                        new TenantSecretManagerBindingRequest("HASHICORP", null, null)));

        assertEquals(400, ex.getStatusCode().value());
    }

    @Test
    void put_gcpSecretManager_withProjectId_succeeds() {
        when(bindingService.validate(eq("GCP_SECRET_MANAGER"), eq("acme-secrets")))
                .thenReturn(Optional.empty());
        TenantGcpRuntimeTopology saved = new TenantGcpRuntimeTopology();
        saved.setSecretAuthorityMode("GCP_SECRET_MANAGER");
        saved.setSecretManagerProjectId("acme-secrets");
        when(bindingService.upsert(eq(TENANT_ID), any(), eq("GCP_SECRET_MANAGER"),
                eq("acme-secrets"), any())).thenReturn(saved);
        Map<String, Object> readback = new LinkedHashMap<>();
        readback.put("mode", "TENANT_GCP_SECRET_MANAGER");
        readback.put("gsmProjectId", "acme-secrets");
        when(bindingService.buildReadback(TENANT_ID, saved)).thenReturn(readback);

        var response = controller.setBinding(TENANT_ID,
                new TenantSecretManagerBindingRequest("GCP_SECRET_MANAGER", "acme-secrets", null));

        assertEquals(200, response.getStatusCode().value());
        assertEquals("TENANT_GCP_SECRET_MANAGER", response.getBody().get("mode"));
    }

    @Test
    void put_tenantGcpSecretManager_withProjectId_succeeds() {
        when(bindingService.validate(eq("TENANT_GCP_SECRET_MANAGER"), eq("acme-secrets")))
                .thenReturn(Optional.empty());
        TenantGcpRuntimeTopology saved = new TenantGcpRuntimeTopology();
        saved.setSecretAuthorityMode("GCP_SECRET_MANAGER");
        saved.setSecretManagerProjectId("acme-secrets");
        when(bindingService.upsert(eq(TENANT_ID), any(), eq("TENANT_GCP_SECRET_MANAGER"),
                eq("acme-secrets"), any())).thenReturn(saved);
        Map<String, Object> readback = new LinkedHashMap<>();
        readback.put("mode", "TENANT_GCP_SECRET_MANAGER");
        readback.put("gsmProjectId", "acme-secrets");
        when(bindingService.buildReadback(TENANT_ID, saved)).thenReturn(readback);

        var response = controller.setBinding(TENANT_ID,
                new TenantSecretManagerBindingRequest("TENANT_GCP_SECRET_MANAGER",
                        "acme-secrets", null));

        assertEquals(200, response.getStatusCode().value());
        assertEquals("TENANT_GCP_SECRET_MANAGER", response.getBody().get("mode"));
    }
}
