package com.pulse.deploy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.deploy.adapter.AdapterPlan;
import com.pulse.deploy.adapter.DeploymentTargetAdapter;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 7 — every registered {@link DeploymentTargetAdapter} returns a
 * conforming {@link AdapterPlan} for its supported verbs:
 *
 * <ul>
 *   <li>schemaVersion is per-adapter and stable;</li>
 *   <li>verb is one of the documented constants;</li>
 *   <li>byte-stable JSON round-trip (canonical hash determinism);</li>
 *   <li>capability block is present and not null;</li>
 *   <li>secret references in details start with {@code gcp-sm://}
 *       — never a raw token value.</li>
 * </ul>
 *
 * <p>Each adapter's plan JSON is also written to
 * {@code build/deployment-adapter-plans/<adapter>/<runId>/<verb>.json}
 * so {@code DeterministicAdapterPlanTest} can re-render and compare
 * later, and operators can inspect the fixture artifact directly.
 */
class DeploymentTargetAdapterPlanContractTest {

    private DeploymentRunRepository runRepo;
    private DeploymentTargetRepository targetRepo;
    private PackageRepository packageRepo;
    private RuntimeCapabilityMatrix capabilityMatrix;

    private LocalDeploymentTargetAdapter local;
    private GcpComposerDataprocAdapter gcp;
    private DpcAirflowOpenShiftSparkAdapter dpc;

    @BeforeEach
    void setUp() {
        runRepo = mock(DeploymentRunRepository.class);
        targetRepo = mock(DeploymentTargetRepository.class);
        packageRepo = mock(PackageRepository.class);
        capabilityMatrix = new RuntimeCapabilityMatrix();
        LocalMaterializationAdapter localImpl = mock(LocalMaterializationAdapter.class);
        local = new LocalDeploymentTargetAdapter(localImpl, runRepo, capabilityMatrix);
        gcp = new GcpComposerDataprocAdapter(localImpl, runRepo, packageRepo, targetRepo,
                capabilityMatrix,
                new StubGcsPackageDeliveryClient(),
                new StubComposerDagSyncClient());
        dpc = new DpcAirflowOpenShiftSparkAdapter(localImpl, runRepo, packageRepo, targetRepo,
                capabilityMatrix,
                new StubDpcObjectStoreClient(),
                new StubDpcAirflowClient());
    }

    @Test
    @DisplayName("LocalDeploymentTargetAdapter returns conforming MATERIALIZE + SUBMIT plans")
    void localPlansAreConforming() throws Exception {
        wireRunForAdapter("run-local", "tenant-A", "target-local", "pkg-local",
                LocalDeploymentTargetAdapter.TARGET_TYPE);
        AdapterPlan matPlan = local.materialization().plan("run-local");
        assertConformingPlan(matPlan, LocalDeploymentTargetAdapter.SCHEMA_VERSION_PLAN,
                LocalDeploymentTargetAdapter.TARGET_TYPE, AdapterPlan.VERB_MATERIALIZE);
        AdapterPlan subPlan = local.submitPoll().plan("run-local");
        assertConformingPlan(subPlan, LocalDeploymentTargetAdapter.SCHEMA_VERSION_PLAN,
                LocalDeploymentTargetAdapter.TARGET_TYPE, AdapterPlan.VERB_SUBMIT);
        writePlanFixture(matPlan);
        writePlanFixture(subPlan);
    }

    @Test
    @DisplayName("GcpComposerDataprocAdapter returns conforming MATERIALIZE + SUBMIT plans")
    void gcpPlansAreConforming() throws Exception {
        wireRunForAdapter("run-gcp", "tenant-A", "target-gcp", "pkg-gcp",
                GcpComposerDataprocAdapter.TARGET_TYPE);
        wirePackageWithFormat("pkg-gcp", "ICEBERG");
        AdapterPlan matPlan = gcp.materialization().plan("run-gcp");
        assertConformingPlan(matPlan, GcpComposerDataprocAdapter.SCHEMA_VERSION_PLAN,
                GcpComposerDataprocAdapter.TARGET_TYPE, AdapterPlan.VERB_MATERIALIZE);
        AdapterPlan subPlan = gcp.submitPoll().plan("run-gcp");
        assertConformingPlan(subPlan, GcpComposerDataprocAdapter.SCHEMA_VERSION_PLAN,
                GcpComposerDataprocAdapter.TARGET_TYPE, AdapterPlan.VERB_SUBMIT);
        // GCP plan must include the gcp-sm:// token reference (not a value).
        Object tokenRef = subPlan.details().get("tokenReference");
        assertNotNull(tokenRef, "GCP plan must include tokenReference");
        assertTrue(String.valueOf(tokenRef).startsWith("gcp-sm://"),
                "GCP plan tokenReference must be a gcp-sm:// URI, got: " + tokenRef);
        // Must NOT contain plaintext-shaped fields like 'token' or 'password'
        // anywhere in the canonical JSON.
        String json = new ObjectMapper().writeValueAsString(subPlan.toCanonicalJson());
        assertFalse(json.contains("\"token\":") || json.contains("\"password\":"),
                "GCP plan JSON must not embed plaintext token/password fields, got: " + json);
        writePlanFixture(matPlan);
        writePlanFixture(subPlan);
    }

    @Test
    @DisplayName("DpcAirflowOpenShiftSparkAdapter returns conforming MATERIALIZE + SUBMIT plans")
    void dpcPlansAreConforming() throws Exception {
        wireRunForAdapter("run-dpc", "tenant-A", "target-dpc", "pkg-dpc",
                DpcAirflowOpenShiftSparkAdapter.TARGET_TYPE);
        wirePackageWithFormat("pkg-dpc", "PARQUET");
        AdapterPlan matPlan = dpc.materialization().plan("run-dpc");
        assertConformingPlan(matPlan, DpcAirflowOpenShiftSparkAdapter.SCHEMA_VERSION_PLAN,
                DpcAirflowOpenShiftSparkAdapter.TARGET_TYPE, AdapterPlan.VERB_MATERIALIZE);
        AdapterPlan subPlan = dpc.submitPoll().plan("run-dpc");
        assertConformingPlan(subPlan, DpcAirflowOpenShiftSparkAdapter.SCHEMA_VERSION_PLAN,
                DpcAirflowOpenShiftSparkAdapter.TARGET_TYPE, AdapterPlan.VERB_SUBMIT);
        Object tokenRef = subPlan.details().get("tokenReference");
        assertNotNull(tokenRef, "DPC plan must include tokenReference");
        assertTrue(String.valueOf(tokenRef).startsWith("gcp-sm://"),
                "DPC plan tokenReference must be a gcp-sm:// URI, got: " + tokenRef);
        writePlanFixture(matPlan);
        writePlanFixture(subPlan);
    }

    // ------------------------------------------------------------------

    private void assertConformingPlan(AdapterPlan plan, String expectedSchema,
                                      String expectedAdapter, String expectedVerb)
            throws Exception {
        assertNotNull(plan, "plan must not be null");
        assertEquals(expectedSchema, plan.schemaVersion());
        assertEquals(expectedAdapter, plan.adapter());
        assertEquals(expectedVerb, plan.verb());
        assertNotNull(plan.deploymentRunId(), "deploymentRunId required");
        assertNotNull(plan.tenantId(), "tenantId required");
        assertNotNull(plan.capability(), "capability block required");
        assertNotNull(plan.planSha256(), "planSha256 required");
        assertEquals(64, plan.planSha256().length(), "planSha256 must be SHA-256 hex");

        // Round-trip canonical JSON: serializing the plan twice produces
        // byte-identical output.
        ObjectMapper mapper = new ObjectMapper();
        byte[] firstPass = mapper.writeValueAsBytes(plan.toCanonicalJson());
        byte[] secondPass = mapper.writeValueAsBytes(plan.toCanonicalJson());
        assertArrayEquals(firstPass, secondPass, "plan JSON must be byte-stable across renders");
    }

    private void wireRunForAdapter(String runId, String tenantId, String targetId,
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
        target.setName("Target " + targetType);
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
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("requestedTableFormat", format);
        pkg.setMetadata(meta);
        when(packageRepo.findById(packageId)).thenReturn(Optional.of(pkg));
    }

    private static void writePlanFixture(AdapterPlan plan) throws Exception {
        Path dir = Paths.get("build", "deployment-adapter-plans",
                plan.adapter(), plan.deploymentRunId());
        Files.createDirectories(dir);
        Path file = dir.resolve(plan.verb().toLowerCase() + ".json");
        Files.write(file, new ObjectMapper().writerWithDefaultPrettyPrinter()
                .writeValueAsBytes(plan.toCanonicalJson()));
        // Byte-stable fixture; downstream tests assert on this exact path.
        assertTrue(Files.exists(file), "fixture file must exist: " + file);
    }
}
