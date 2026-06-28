package com.pulse.deploy.controller;

import com.pulse.deploy.model.DeploymentTarget;
import com.pulse.deploy.repository.ApprovalRequestRepository;
import com.pulse.deploy.repository.DeploymentRepository;
import com.pulse.deploy.repository.DeploymentTargetRepository;
import com.pulse.deploy.repository.PackageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeployControllerCrudTest {

    @Mock private PackageRepository packageRepo;
    @Mock private DeploymentRepository deployRepo;
    @Mock private DeploymentTargetRepository targetRepo;
    @Mock private ApprovalRequestRepository approvalRepo;

    @InjectMocks
    private DeployController controller;

    // -----------------------------------------------------------------------
    //  updateTarget tests
    // -----------------------------------------------------------------------

    @Test
    void updateTarget_validRequest_returnsUpdatedRecord() {
        // Given an existing target in tenant "acme"
        DeploymentTarget existing = buildTarget("tgt-1", "acme", "Old Name",
                "INTEGRATION", "KUBERNETES", "https://old.example.com");
        when(targetRepo.findById("tgt-1")).thenReturn(Optional.of(existing));
        when(targetRepo.save(any(DeploymentTarget.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> newConfig = Map.of("namespace", "prod-data");
        DeployController.UpdateTargetReq req = new DeployController.UpdateTargetReq(
                "New Name", "https://new.example.com", newConfig, false);

        // When
        ResponseEntity<DeploymentTarget> response = controller.updateTarget("acme", "tgt-1", req);

        // Then
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("New Name", response.getBody().getName());
        assertEquals("https://new.example.com", response.getBody().getEndpointUrl());
        assertEquals(newConfig, response.getBody().getConfig());
        assertFalse(response.getBody().isEnabled());
        verify(targetRepo).save(any(DeploymentTarget.class));
    }

    @Test
    void updateTarget_crossTenantAccess_returns403() {
        // Given a target that belongs to "globex" but caller is "acme"
        DeploymentTarget existing = buildTarget("tgt-1", "globex", "Globex Target",
                "PROD", "KUBERNETES", "https://globex.example.com");
        when(targetRepo.findById("tgt-1")).thenReturn(Optional.of(existing));

        DeployController.UpdateTargetReq req = new DeployController.UpdateTargetReq(
                "Hijacked", null, null, null);

        // When / Then
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.updateTarget("acme", "tgt-1", req));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(targetRepo, never()).save(any(DeploymentTarget.class));
    }

    @Test
    void updateTarget_immutableFieldsCannotChange() {
        // Given an existing target
        DeploymentTarget existing = buildTarget("tgt-1", "acme", "Original",
                "INTEGRATION", "KUBERNETES", "https://example.com");
        when(targetRepo.findById("tgt-1")).thenReturn(Optional.of(existing));
        when(targetRepo.save(any(DeploymentTarget.class))).thenAnswer(inv -> inv.getArgument(0));

        // UpdateTargetReq exposes only name, endpointUrl, config, enabled.
        DeployController.UpdateTargetReq req = new DeployController.UpdateTargetReq(
                "Renamed", "https://new.example.com",
                Map.of("namespace", "data"), true);

        // When
        ResponseEntity<DeploymentTarget> response = controller.updateTarget("acme", "tgt-1", req);

        // Then mutable fields are updated
        assertEquals("Renamed", response.getBody().getName());
        assertEquals("https://new.example.com", response.getBody().getEndpointUrl());

        // And immutable fields are preserved
        assertEquals("INTEGRATION", response.getBody().getEnvironment());
        assertEquals("KUBERNETES", response.getBody().getTargetType());

        // Sanity: the request record has no setters for environment/targetType
        java.lang.reflect.Method[] methods = DeployController.UpdateTargetReq.class.getDeclaredMethods();
        for (java.lang.reflect.Method m : methods) {
            assertNotEquals("environment", m.getName(),
                    "UpdateTargetReq must not expose environment");
            assertNotEquals("targetType", m.getName(),
                    "UpdateTargetReq must not expose targetType");
        }
    }

    // -----------------------------------------------------------------------
    //  deleteTarget tests
    // -----------------------------------------------------------------------

    @Test
    void deleteTarget_marksDisabled_returns204() {
        // Given an enabled target in tenant "acme"
        DeploymentTarget existing = buildTarget("tgt-1", "acme", "Active",
                "INTEGRATION", "KUBERNETES", "https://example.com");
        assertTrue(existing.isEnabled(), "precondition: target starts enabled");
        when(targetRepo.findById("tgt-1")).thenReturn(Optional.of(existing));
        when(targetRepo.save(any(DeploymentTarget.class))).thenAnswer(inv -> inv.getArgument(0));

        // When
        ResponseEntity<Void> response = controller.deleteTarget("acme", "tgt-1");

        // Then
        assertEquals(204, response.getStatusCode().value());
        assertFalse(existing.isEnabled(), "target should be soft-deleted (enabled=false)");
        verify(targetRepo).save(existing);
        // And no hard delete
        verify(targetRepo, never()).deleteById(any());
    }

    @Test
    void deleteTarget_crossTenantAccess_returns403() {
        // Given a target that belongs to "globex" but caller is "acme"
        DeploymentTarget existing = buildTarget("tgt-1", "globex", "Globex Target",
                "PROD", "KUBERNETES", "https://globex.example.com");
        when(targetRepo.findById("tgt-1")).thenReturn(Optional.of(existing));

        // When / Then
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.deleteTarget("acme", "tgt-1"));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(targetRepo, never()).save(any(DeploymentTarget.class));
    }

    // -----------------------------------------------------------------------
    //  listTargets tests
    // -----------------------------------------------------------------------

    @Test
    void listTargets_includeDisabledFalse_excludesDisabled() {
        // Given two targets: one enabled, one disabled. The enabled-only query
        // returns just the active one.
        DeploymentTarget active = buildTarget("tgt-1", "acme", "Active",
                "INTEGRATION", "KUBERNETES", "https://a.example.com");
        when(targetRepo.findByTenantIdAndEnabledTrueOrderByEnvironmentAsc("acme"))
                .thenReturn(List.of(active));

        // When includeDisabled=false (default)
        ResponseEntity<List<DeploymentTarget>> response = controller.listTargets("acme", false);

        // Then only the enabled-only repository call is made and the disabled
        // target is excluded.
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
        assertEquals("tgt-1", response.getBody().get(0).getId());
        assertTrue(response.getBody().get(0).isEnabled());
        verify(targetRepo).findByTenantIdAndEnabledTrueOrderByEnvironmentAsc("acme");
        verify(targetRepo, never()).findByTenantIdOrderByEnvironmentAsc(any());
    }

    @Test
    void listTargets_includeDisabledTrue_returnsAll() {
        // Sanity check: includeDisabled=true uses the all-records query path.
        DeploymentTarget active = buildTarget("tgt-1", "acme", "Active",
                "INTEGRATION", "KUBERNETES", "https://a.example.com");
        DeploymentTarget disabled = buildTarget("tgt-2", "acme", "Disabled",
                "PROD", "KUBERNETES", "https://b.example.com");
        disabled.setEnabled(false);
        when(targetRepo.findByTenantIdOrderByEnvironmentAsc("acme"))
                .thenReturn(List.of(active, disabled));

        ResponseEntity<List<DeploymentTarget>> response = controller.listTargets("acme", true);

        assertEquals(2, response.getBody().size());
        verify(targetRepo).findByTenantIdOrderByEnvironmentAsc("acme");
        verify(targetRepo, never()).findByTenantIdAndEnabledTrueOrderByEnvironmentAsc(any());
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    private DeploymentTarget buildTarget(String id, String tenantId, String name,
                                          String environment, String targetType,
                                          String endpointUrl) {
        DeploymentTarget t = new DeploymentTarget();
        t.setId(id);
        t.setTenantId(tenantId);
        t.setName(name);
        t.setEnvironment(environment);
        t.setTargetType(targetType);
        t.setEndpointUrl(endpointUrl);
        t.setConfig(new HashMap<>());
        t.setEnabled(true);
        return t;
    }
}
