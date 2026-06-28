package com.pulse.storage;

import com.pulse.pipeline.model.Pipeline;
import com.pulse.pipeline.model.SubPipelineInstance;
import com.pulse.pipeline.repository.PipelineRepository;
import com.pulse.pipeline.repository.SubPipelineInstanceRepository;
import com.pulse.storage.model.StorageBackend;
import com.pulse.storage.repository.StorageBackendRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StorageBackendDeployGateTest {

    private SubPipelineInstanceRepository instanceRepo;
    private PipelineRepository pipelineRepo;
    private StorageBackendRepository storageBackendRepo;
    private com.pulse.storage.repository.StorageAuthorityConflictRepository conflictRepo;
    private StorageBackendDeployGate gate;

    @BeforeEach
    void setUp() {
        instanceRepo = mock(SubPipelineInstanceRepository.class);
        pipelineRepo = mock(PipelineRepository.class);
        storageBackendRepo = mock(StorageBackendRepository.class);
        conflictRepo = mock(com.pulse.storage.repository.StorageAuthorityConflictRepository.class);
        gate = new StorageBackendDeployGate(instanceRepo, pipelineRepo, storageBackendRepo, conflictRepo);
    }

    private Pipeline pipeline(String id, String tenantId) {
        Pipeline p = new Pipeline();
        p.setId(id);
        p.setTenantId(tenantId);
        return p;
    }

    private SubPipelineInstance instance(String id, String backend) {
        SubPipelineInstance i = new SubPipelineInstance();
        i.setId(id);
        i.setStorageBackend(backend);
        return i;
    }

    private StorageBackend backendRow(String backend, String env, String status) {
        StorageBackend sb = new StorageBackend();
        sb.setId("01JSTRG_TEST_" + backend);
        sb.setTenantId("tenant-test");
        sb.setEnvironment(env);
        sb.setBackend(backend);
        sb.setStorageRootFiles("pulse-test-" + env + "-files");
        sb.setStorageRootLake("pulse-test-" + env + "-lake");
        sb.setProvisioningStatus(status);
        if ("validated".equals(status)) {
            sb.setProvisioningValidatedAt(Instant.now());
        }
        if ("GCP".equals(backend)) sb.setGcpProject("pulse-test-" + env);
        else { sb.setDpcScheme("s3a"); sb.setDpcCluster("pulse-dpc-test-" + env); }
        return sb;
    }

    @Test
    void allowsDeployWhenAllBackendsValidated() {
        when(pipelineRepo.findById("pipe-1"))
                .thenReturn(Optional.of(pipeline("pipe-1", "tenant-test")));
        when(instanceRepo.findByPipelineIdOrderByExecutionOrderAsc("pipe-1"))
                .thenReturn(List.of(instance("inst-1", "DPC"), instance("inst-2", "GCP")));
        when(storageBackendRepo.findByTenantIdAndEnvironmentAndBackend(
                "tenant-test", "prod", "DPC"))
                .thenReturn(Optional.of(backendRow("DPC", "prod", "validated")));
        when(storageBackendRepo.findByTenantIdAndEnvironmentAndBackend(
                "tenant-test", "prod", "GCP"))
                .thenReturn(Optional.of(backendRow("GCP", "prod", "validated")));

        StorageBackendDeployGate.Result r = gate.check("pipe-1", "prod");
        assertTrue(r.ok());
        assertEquals("ok", r.reason());
    }

    @Test
    void blocksDeployWhenAnyBackendPending() {
        when(pipelineRepo.findById("pipe-1"))
                .thenReturn(Optional.of(pipeline("pipe-1", "tenant-test")));
        when(instanceRepo.findByPipelineIdOrderByExecutionOrderAsc("pipe-1"))
                .thenReturn(List.of(instance("inst-1", "DPC"), instance("inst-2", "GCP")));
        when(storageBackendRepo.findByTenantIdAndEnvironmentAndBackend(
                "tenant-test", "prod", "DPC"))
                .thenReturn(Optional.of(backendRow("DPC", "prod", "validated")));
        when(storageBackendRepo.findByTenantIdAndEnvironmentAndBackend(
                "tenant-test", "prod", "GCP"))
                .thenReturn(Optional.of(backendRow("GCP", "prod", "pending")));

        StorageBackendDeployGate.Result r = gate.check("pipe-1", "prod");
        assertFalse(r.ok());
        assertEquals(1, r.blockers().size());
        assertEquals("GCP", r.blockers().get(0).backend());
        assertEquals("pending", r.blockers().get(0).reason());
        assertTrue(r.reason().contains("Contact the platform team"),
                "Pending blocker must hint at platform team action");
    }

    @Test
    void blocksDeployWhenAnyBackendFailed() {
        when(pipelineRepo.findById("pipe-1"))
                .thenReturn(Optional.of(pipeline("pipe-1", "tenant-test")));
        when(instanceRepo.findByPipelineIdOrderByExecutionOrderAsc("pipe-1"))
                .thenReturn(List.of(instance("inst-1", "DPC")));
        StorageBackend failed = backendRow("DPC", "uat", "failed");
        failed.setProvisioningError("403 Forbidden listing bucket pulse-dpc-test-uat-lake");
        when(storageBackendRepo.findByTenantIdAndEnvironmentAndBackend(
                "tenant-test", "uat", "DPC"))
                .thenReturn(Optional.of(failed));

        StorageBackendDeployGate.Result r = gate.check("pipe-1", "uat");
        assertFalse(r.ok());
        assertTrue(r.reason().contains("403 Forbidden"),
                "Failed blocker must surface the underlying probe error");
    }

    @Test
    void blocksDeployWhenAnyBackendDisabled() {
        when(pipelineRepo.findById("pipe-1"))
                .thenReturn(Optional.of(pipeline("pipe-1", "tenant-test")));
        when(instanceRepo.findByPipelineIdOrderByExecutionOrderAsc("pipe-1"))
                .thenReturn(List.of(instance("inst-1", "GCP")));
        when(storageBackendRepo.findByTenantIdAndEnvironmentAndBackend(
                "tenant-test", "prod", "GCP"))
                .thenReturn(Optional.of(backendRow("GCP", "prod", "disabled")));

        StorageBackendDeployGate.Result r = gate.check("pipe-1", "prod");
        assertFalse(r.ok());
        assertTrue(r.reason().contains("disabled"));
    }

    @Test
    void blocksWhenStorageBackendsRowMissing() {
        when(pipelineRepo.findById("pipe-1"))
                .thenReturn(Optional.of(pipeline("pipe-1", "tenant-test")));
        when(instanceRepo.findByPipelineIdOrderByExecutionOrderAsc("pipe-1"))
                .thenReturn(List.of(instance("inst-1", "DPC")));
        when(storageBackendRepo.findByTenantIdAndEnvironmentAndBackend(
                eq("tenant-test"), eq("prod"), any()))
                .thenReturn(Optional.empty());

        StorageBackendDeployGate.Result r = gate.check("pipe-1", "prod");
        assertFalse(r.ok());
        assertTrue(r.reason().contains("missing_row")
                        || r.reason().contains("No storage_backends row"));
    }

    @Test
    void blocksWhenPipelineNotFound() {
        when(pipelineRepo.findById("missing")).thenReturn(Optional.empty());
        StorageBackendDeployGate.Result r = gate.check("missing", "dev");
        assertFalse(r.ok());
        assertTrue(r.reason().contains("not found"));
    }

    @Test
    void blocksWhenPipelineHasNoInstances() {
        when(pipelineRepo.findById("pipe-empty"))
                .thenReturn(Optional.of(pipeline("pipe-empty", "tenant-test")));
        when(instanceRepo.findByPipelineIdOrderByExecutionOrderAsc("pipe-empty"))
                .thenReturn(List.of());

        StorageBackendDeployGate.Result r = gate.check("pipe-empty", "dev");
        assertFalse(r.ok());
        assertTrue(r.reason().contains("nothing to deploy"));
    }

    @Test
    void deduplicatesBackendsAcrossInstances() {
        // Two instances both DPC → only one storage_backends lookup.
        when(pipelineRepo.findById("pipe-1"))
                .thenReturn(Optional.of(pipeline("pipe-1", "tenant-test")));
        when(instanceRepo.findByPipelineIdOrderByExecutionOrderAsc("pipe-1"))
                .thenReturn(List.of(instance("inst-1", "DPC"), instance("inst-2", "DPC")));
        when(storageBackendRepo.findByTenantIdAndEnvironmentAndBackend(
                "tenant-test", "dev", "DPC"))
                .thenReturn(Optional.of(backendRow("DPC", "dev", "validated")));

        StorageBackendDeployGate.Result r = gate.check("pipe-1", "dev");
        assertTrue(r.ok());
    }

    @Test
    void uniformRule_devAlsoRequiresValidated() {
        // Per the locked rule from the spec discussion: NO env-specific
        // exceptions. Even dev requires validated. Local-dev seed has
        // dev rows pre-validated; but if someone clears them, dev deploy
        // is blocked — same as prod.
        when(pipelineRepo.findById("pipe-1"))
                .thenReturn(Optional.of(pipeline("pipe-1", "tenant-test")));
        when(instanceRepo.findByPipelineIdOrderByExecutionOrderAsc("pipe-1"))
                .thenReturn(List.of(instance("inst-1", "DPC")));
        when(storageBackendRepo.findByTenantIdAndEnvironmentAndBackend(
                "tenant-test", "dev", "DPC"))
                .thenReturn(Optional.of(backendRow("DPC", "dev", "pending")));

        StorageBackendDeployGate.Result r = gate.check("pipe-1", "dev");
        assertFalse(r.ok(), "dev must be gated identically to other envs");
    }

    @Test
    void canonicalizesLegacyEnvironmentInputsBeforeLookup() {
        // Phase 1: callers may still hand in legacy uppercase forms
        // (DEV, PROD, INT, INTEGRATION, PRODUCTION). The gate must
        // normalize these to the canonical lowercase key before doing
        // the storage_backends row lookup, so 'DEV' and 'dev' resolve
        // to the same row.
        when(pipelineRepo.findById("pipe-1"))
                .thenReturn(Optional.of(pipeline("pipe-1", "tenant-test")));
        when(instanceRepo.findByPipelineIdOrderByExecutionOrderAsc("pipe-1"))
                .thenReturn(List.of(instance("inst-1", "DPC")));
        when(storageBackendRepo.findByTenantIdAndEnvironmentAndBackend(
                "tenant-test", "dev", "DPC"))
                .thenReturn(Optional.of(backendRow("DPC", "dev", "validated")));
        when(storageBackendRepo.findByTenantIdAndEnvironmentAndBackend(
                "tenant-test", "prod", "DPC"))
                .thenReturn(Optional.of(backendRow("DPC", "prod", "validated")));
        when(storageBackendRepo.findByTenantIdAndEnvironmentAndBackend(
                "tenant-test", "integration", "DPC"))
                .thenReturn(Optional.of(backendRow("DPC", "integration", "validated")));

        // DEV → dev
        StorageBackendDeployGate.Result devUpper = gate.check("pipe-1", "DEV");
        assertTrue(devUpper.ok(), "DEV must resolve to the same canonical row as dev");
        StorageBackendDeployGate.Result devLower = gate.check("pipe-1", "dev");
        assertTrue(devLower.ok(), "dev must resolve to its canonical row");

        // PRODUCTION + PROD → prod
        StorageBackendDeployGate.Result production = gate.check("pipe-1", "PRODUCTION");
        assertTrue(production.ok(), "PRODUCTION must resolve to the canonical 'prod' row");
        StorageBackendDeployGate.Result prodShort = gate.check("pipe-1", "PROD");
        assertTrue(prodShort.ok(), "PROD must resolve to the canonical 'prod' row");

        // INT + INTEGRATION → integration
        StorageBackendDeployGate.Result intShort = gate.check("pipe-1", "INT");
        assertTrue(intShort.ok(), "INT must resolve to the canonical 'integration' row");
        StorageBackendDeployGate.Result intLong = gate.check("pipe-1", "INTEGRATION");
        assertTrue(intLong.ok(), "INTEGRATION must resolve to the canonical 'integration' row");

        // Verify the lookups went through with canonical keys (and that no
        // uppercase lookup ever happened).
        org.mockito.Mockito.verify(storageBackendRepo, org.mockito.Mockito.atLeastOnce())
                .findByTenantIdAndEnvironmentAndBackend("tenant-test", "dev", "DPC");
        org.mockito.Mockito.verify(storageBackendRepo, org.mockito.Mockito.atLeast(2))
                .findByTenantIdAndEnvironmentAndBackend("tenant-test", "prod", "DPC");
        org.mockito.Mockito.verify(storageBackendRepo, org.mockito.Mockito.atLeast(2))
                .findByTenantIdAndEnvironmentAndBackend("tenant-test", "integration", "DPC");
        org.mockito.Mockito.verify(storageBackendRepo, org.mockito.Mockito.never())
                .findByTenantIdAndEnvironmentAndBackend(eq("tenant-test"), eq("DEV"), any());
        org.mockito.Mockito.verify(storageBackendRepo, org.mockito.Mockito.never())
                .findByTenantIdAndEnvironmentAndBackend(eq("tenant-test"), eq("PRODUCTION"), any());
    }

    @Test
    void rejectsUnknownEnvironmentInputsAtBoundary() {
        // Phase 1: unknown envs (typos, deprecated names) must produce a
        // structured blocker with reason='unknown_environment' instead of
        // silently missing every storage_backends row.
        StorageBackendDeployGate.Result r = gate.check("pipe-1", "STAGING");
        assertFalse(r.ok());
        assertEquals(1, r.blockers().size());
        assertEquals("unknown_environment", r.blockers().get(0).reason());
        assertTrue(r.reason().contains("Unknown deployment environment"),
                "Expected unknown-env error, got: " + r.reason());
    }
}
