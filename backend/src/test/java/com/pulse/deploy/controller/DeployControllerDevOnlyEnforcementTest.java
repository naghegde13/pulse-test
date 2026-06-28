package com.pulse.deploy.controller;

import com.pulse.deploy.model.Deployment;
import com.pulse.deploy.model.DeploymentTarget;
import com.pulse.deploy.model.Package;
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
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Dev-only deploy-gate enforcement tests for {@link DeployController}.
 *
 * Per PKT-FINAL-2 (BUG-2026-05-25-02), PULSE deploys to dev only;
 * non-dev targets must be rejected at the policy gate with a 403 carrying
 * the canonical {@link DeployController#DEV_ONLY_DEPLOY_MESSAGE}. The
 * underlying DeploymentTarget.environment column itself is preserved
 * (package config matrix) -- only the deploy action is blocked.
 *
 * This test intentionally only covers the rejection path. Happy-path
 * deploy coverage already lives in the comprehensive DeployControllerTest
 * in src/test/java/com/pulse/deploy/, which mocks the full set of
 * Phase-4+ dependencies (orchestrator, preflight, evidence, runtime authority,
 * deploy-boundary service). Re-creating that mock graph here would duplicate
 * setup without adding signal -- the dev-only gate fires before any of those
 * dependencies are reached, so a thin rejection-only test gives the same
 * proof at a fraction of the maintenance cost.
 */
@ExtendWith(MockitoExtension.class)
class DeployControllerDevOnlyEnforcementTest {

    @Mock private PackageRepository packageRepo;
    @Mock private DeploymentRepository deployRepo;
    @Mock private DeploymentTargetRepository targetRepo;
    @Mock private ApprovalRequestRepository approvalRepo;

    @InjectMocks
    private DeployController controller;

    private static Package somePackage() {
        Package pkg = new Package();
        pkg.setId("pkg-1");
        pkg.setPipelineId("pipeline-1");
        pkg.setVersionId("version-1");
        pkg.setTenantId("tenant-1");
        return pkg;
    }

    private static DeploymentTarget targetWithEnv(String env) {
        DeploymentTarget t = new DeploymentTarget();
        t.setId("target-1");
        t.setTenantId("tenant-1");
        t.setName("target-" + env);
        t.setEnvironment(env);
        return t;
    }

    @Test
    void deploy_integrationTarget_returns403WithCanonicalMessage() {
        when(packageRepo.findById("pkg-1")).thenReturn(Optional.of(somePackage()));
        when(targetRepo.findById("target-1")).thenReturn(Optional.of(targetWithEnv("integration")));

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> controller.deploy(
                        "pkg-1",
                        new DeployController.DeployRequest("target-1", "tenant-1", "user-1"),
                        null,
                        null));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertEquals(DeployController.DEV_ONLY_DEPLOY_MESSAGE, ex.getReason());
        verify(deployRepo, never()).save(any(Deployment.class));
    }

    @Test
    void deploy_uatTarget_returns403() {
        when(packageRepo.findById("pkg-1")).thenReturn(Optional.of(somePackage()));
        when(targetRepo.findById("target-1")).thenReturn(Optional.of(targetWithEnv("UAT")));

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> controller.deploy(
                        "pkg-1",
                        new DeployController.DeployRequest("target-1", "tenant-1", "user-1"),
                        null,
                        null));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(deployRepo, never()).save(any(Deployment.class));
    }

    @Test
    void deploy_prodTarget_returns403() {
        when(packageRepo.findById("pkg-1")).thenReturn(Optional.of(somePackage()));
        when(targetRepo.findById("target-1")).thenReturn(Optional.of(targetWithEnv("PRODUCTION")));

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> controller.deploy(
                        "pkg-1",
                        new DeployController.DeployRequest("target-1", "tenant-1", "user-1"),
                        null,
                        null));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(deployRepo, never()).save(any(Deployment.class));
    }
}
