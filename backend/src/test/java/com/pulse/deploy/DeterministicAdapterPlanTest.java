package com.pulse.deploy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.deploy.adapter.AdapterPlan;
import com.pulse.deploy.adapter.LocalMaterializationAdapter;
import com.pulse.deploy.adapter.dpc.DpcAirflowOpenShiftSparkAdapter;
import com.pulse.deploy.adapter.dpc.StubDpcAirflowClient;
import com.pulse.deploy.adapter.dpc.StubDpcObjectStoreClient;
import com.pulse.deploy.adapter.gcp.GcpComposerDataprocAdapter;
import com.pulse.deploy.adapter.gcp.StubComposerDagSyncClient;
import com.pulse.deploy.adapter.gcp.StubGcsPackageDeliveryClient;
import com.pulse.deploy.adapter.local.LocalDeploymentTargetAdapter;
import com.pulse.deploy.capability.RuntimeCapabilityMatrix;
import com.pulse.deploy.model.DeploymentRun;
import com.pulse.deploy.model.DeploymentTarget;
import com.pulse.deploy.model.Package;
import com.pulse.deploy.repository.DeploymentRunRepository;
import com.pulse.deploy.repository.DeploymentTargetRepository;
import com.pulse.deploy.repository.PackageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 7 — two identical packages produce byte-equal plan JSON and
 * identical {@code planSha256} across all three adapters.
 */
class DeterministicAdapterPlanTest {

    private DeploymentRunRepository runRepo;
    private PackageRepository packageRepo;
    private DeploymentTargetRepository targetRepo;
    private RuntimeCapabilityMatrix capabilityMatrix;

    @BeforeEach
    void setUp() {
        runRepo = mock(DeploymentRunRepository.class);
        packageRepo = mock(PackageRepository.class);
        targetRepo = mock(DeploymentTargetRepository.class);
        capabilityMatrix = new RuntimeCapabilityMatrix();
    }

    @Test
    @DisplayName("LOCAL: identical inputs → identical plan JSON + identical planSha256")
    void localPlanDeterministic() throws Exception {
        LocalMaterializationAdapter localImpl = mock(LocalMaterializationAdapter.class);
        LocalDeploymentTargetAdapter adapter = new LocalDeploymentTargetAdapter(
                localImpl, runRepo, capabilityMatrix);
        wireRun("run-1", "tenant-A", "target-1", "pkg-1",
                LocalDeploymentTargetAdapter.TARGET_TYPE);
        AdapterPlan a = adapter.materialization().plan("run-1");
        AdapterPlan b = adapter.materialization().plan("run-1");
        assertSamePlan(a, b);
    }

    @Test
    @DisplayName("GCP: identical inputs → identical plan JSON + identical planSha256")
    void gcpPlanDeterministic() throws Exception {
        LocalMaterializationAdapter localImpl = mock(LocalMaterializationAdapter.class);
        GcpComposerDataprocAdapter adapter = new GcpComposerDataprocAdapter(
                localImpl, runRepo, packageRepo, targetRepo, capabilityMatrix,
                new StubGcsPackageDeliveryClient(),
                new StubComposerDagSyncClient());
        wireRun("run-1", "tenant-A", "target-1", "pkg-1",
                GcpComposerDataprocAdapter.TARGET_TYPE);
        wirePackageWithFormat("pkg-1", "ICEBERG");
        AdapterPlan a = adapter.materialization().plan("run-1");
        AdapterPlan b = adapter.materialization().plan("run-1");
        assertSamePlan(a, b);
        AdapterPlan subA = adapter.submitPoll().plan("run-1");
        AdapterPlan subB = adapter.submitPoll().plan("run-1");
        assertSamePlan(subA, subB);
    }

    @Test
    @DisplayName("DPC: identical inputs → identical plan JSON + identical planSha256")
    void dpcPlanDeterministic() throws Exception {
        LocalMaterializationAdapter localImpl = mock(LocalMaterializationAdapter.class);
        DpcAirflowOpenShiftSparkAdapter adapter = new DpcAirflowOpenShiftSparkAdapter(
                localImpl, runRepo, packageRepo, targetRepo, capabilityMatrix,
                new StubDpcObjectStoreClient(),
                new StubDpcAirflowClient());
        wireRun("run-1", "tenant-A", "target-1", "pkg-1",
                DpcAirflowOpenShiftSparkAdapter.TARGET_TYPE);
        wirePackageWithFormat("pkg-1", "PARQUET");
        AdapterPlan a = adapter.materialization().plan("run-1");
        AdapterPlan b = adapter.materialization().plan("run-1");
        assertSamePlan(a, b);
        AdapterPlan subA = adapter.submitPoll().plan("run-1");
        AdapterPlan subB = adapter.submitPoll().plan("run-1");
        assertSamePlan(subA, subB);
    }

    private static void assertSamePlan(AdapterPlan a, AdapterPlan b) throws Exception {
        // planSha256 stable across renders for the same inputs.
        assertEquals(a.planSha256(), b.planSha256(),
                "planSha256 must be byte-stable for identical inputs");
        ObjectMapper mapper = new ObjectMapper();
        assertArrayEquals(
                mapper.writeValueAsBytes(a.toCanonicalJson()),
                mapper.writeValueAsBytes(b.toCanonicalJson()),
                "plan JSON must be byte-equal for identical inputs");
    }

    private void wireRun(String runId, String tenantId, String targetId,
                         String packageId, String targetType) {
        DeploymentRun run = new DeploymentRun();
        run.setId(runId);
        run.setTenantId(tenantId);
        run.setDeploymentId("dep-" + runId);
        run.setStatus("PREFLIGHT_PASSED");
        run.setInitiatedBy("user-test");
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("packageId", packageId);
        meta.put("targetId", targetId);
        meta.put("environment", "dev");
        run.setMetadata(meta);
        when(runRepo.findById(runId)).thenReturn(Optional.of(run));

        DeploymentTarget target = new DeploymentTarget();
        target.setId(targetId);
        target.setTenantId(tenantId);
        target.setEnvironment("dev");
        target.setTargetType(targetType);
        target.setEnabled(true);
        target.setConfig(new LinkedHashMap<>());
        when(targetRepo.findById(targetId)).thenReturn(Optional.of(target));
    }

    private void wirePackageWithFormat(String packageId, String format) {
        Package pkg = new Package();
        pkg.setId(packageId);
        pkg.setTenantId("tenant-A");
        pkg.setPipelineId("pipeline-A");
        pkg.setVersionId("version-1");
        pkg.setBuildStatus("COMPLETED");
        pkg.setBuiltBy("user-test");
        Map<String, Object> pkgMeta = new LinkedHashMap<>();
        pkgMeta.put("requestedTableFormat", format);
        pkg.setMetadata(pkgMeta);
        when(packageRepo.findById(packageId)).thenReturn(Optional.of(pkg));
    }
}
