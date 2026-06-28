package com.pulse.deploy;

import com.pulse.deploy.adapter.AdapterPlan;
import com.pulse.deploy.adapter.AdapterExecution;
import com.pulse.deploy.adapter.LocalMaterializationAdapter;
import com.pulse.deploy.adapter.gcp.ComposerDagSyncClient;
import com.pulse.deploy.adapter.gcp.GcpComposerDataprocAdapter;
import com.pulse.deploy.adapter.gcp.GcsPackageDeliveryClient;
import com.pulse.deploy.adapter.gcp.StubComposerDagSyncClient;
import com.pulse.deploy.adapter.gcp.StubGcsPackageDeliveryClient;
import com.pulse.deploy.capability.RuntimeCapabilityMatrix;
import com.pulse.deploy.model.DeploymentRun;
import com.pulse.deploy.model.DeploymentTarget;
import com.pulse.deploy.model.Package;
import com.pulse.deploy.repository.DeploymentRunRepository;
import com.pulse.deploy.repository.DeploymentTargetRepository;
import com.pulse.deploy.repository.PackageRepository;
import com.pulse.deploy.run.DeploymentRunState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 7 — fixture-driven plan contract for the GCP adapter.
 *
 * <p>Pins:
 * <ul>
 *   <li>config defaults (gcp project, GCS bucket, Composer environment,
 *       Dataproc region/cluster, main py file, DAG file paths) flow into
 *       the plan;</li>
 *   <li>capability matrix outcome is included;</li>
 *   <li>secret references are gcp-sm:// only — never raw values;</li>
 *   <li>no real GCP SDK call is made (uses Stub*Client).</li>
 * </ul>
 */
class GcpComposerDataprocAdapterPlanTest {

    private DeploymentRunRepository runRepo;
    private PackageRepository packageRepo;
    private DeploymentTargetRepository targetRepo;
    private GcpComposerDataprocAdapter adapter;
    private GcsPackageDeliveryClient gcsClient;
    private ComposerDagSyncClient composerClient;

    @BeforeEach
    void setUp() {
        runRepo = mock(DeploymentRunRepository.class);
        packageRepo = mock(PackageRepository.class);
        targetRepo = mock(DeploymentTargetRepository.class);
        gcsClient = new StubGcsPackageDeliveryClient();
        composerClient = new StubComposerDagSyncClient();
        adapter = new GcpComposerDataprocAdapter(
                mock(LocalMaterializationAdapter.class),
                runRepo, packageRepo, targetRepo,
                new RuntimeCapabilityMatrix(),
                gcsClient, composerClient);
    }

    @Test
    @DisplayName("Plan envelope schemaVersion + verb + adapter are pinned")
    void planEnvelopePinned() {
        wireGcpRun("run-1", "tenant-A", "target-1", "pkg-1", Map.of(), "ICEBERG");
        AdapterPlan plan = adapter.materialization().plan("run-1");
        assertEquals(GcpComposerDataprocAdapter.SCHEMA_VERSION_PLAN, plan.schemaVersion());
        assertEquals(GcpComposerDataprocAdapter.TARGET_TYPE, plan.adapter());
        assertEquals(AdapterPlan.VERB_MATERIALIZE, plan.verb());
        assertEquals("run-1", plan.deploymentRunId());
        assertEquals("pkg-1", plan.packageId());
        assertEquals("tenant-A", plan.tenantId());
        assertEquals("dev", plan.environment());
        assertEquals("target-1", plan.targetId());
    }

    @Test
    @DisplayName("Plan defaults: gcsBucket / composerEnvironment / dataprocRegion populate sensibly")
    void planDefaultsPopulate() {
        wireGcpRun("run-1", "tenant-A", "target-1", "pkg-1", Map.of(), "PARQUET");
        AdapterPlan plan = adapter.submitPoll().plan("run-1");
        Map<String, Object> details = plan.details();
        assertNotNull(details.get("gcpProject"), "gcpProject required");
        assertNotNull(details.get("gcsBucket"), "gcsBucket required");
        assertEquals("packages/run-1/", details.get("gcsPrefix"));
        assertNotNull(details.get("composerEnvironment"), "composerEnvironment required");
        assertEquals("us-central1", details.get("dataprocRegion"));
        assertEquals("pulse-dataproc", details.get("dataprocCluster"));
        assertEquals("package/main.py", details.get("mainPyFile"));
        assertEquals(List.of("package/dags/pipeline_dag.py"), details.get("dagFilePaths"));
        assertEquals("PARQUET", details.get("requestedTableFormat"));
        assertEquals("NONE", details.get("validationMode"));
        assertEquals(false, details.get("validationRequested"));
        assertEquals(false, details.get("activationTriggerImmediately"));
    }

    @Test
    @DisplayName("Custom target.config overrides flow into the plan")
    void targetConfigOverridesApply() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("gcpProject", "pulse-prod-001");
        config.put("gcsBucket", "pulse-prod-packages");
        config.put("composerEnvironment",
                "projects/pulse-prod-001/locations/us-east1/environments/pulse-prod-composer");
        config.put("dataprocRegion", "us-east1");
        config.put("dataprocCluster", "pulse-prod-dataproc");
        config.put("mainPyFile", "package/entrypoint.py");
        config.put("dagFilePaths", List.of("package/dags/sales_dag.py", "package/dags/orders_dag.py"));
        config.put("tokenReference",
                "gcp-sm://projects/pulse-prod-001/secrets/pulse-deploy-sa/versions/7");
        wireGcpRun("run-1", "tenant-A", "target-1", "pkg-1", config, "ICEBERG");
        AdapterPlan plan = adapter.submitPoll().plan("run-1");
        Map<String, Object> details = plan.details();
        assertEquals("pulse-prod-001", details.get("gcpProject"));
        assertEquals("pulse-prod-packages", details.get("gcsBucket"));
        assertEquals("us-east1", details.get("dataprocRegion"));
        assertEquals("package/entrypoint.py", details.get("mainPyFile"));
        assertEquals(List.of("package/dags/sales_dag.py", "package/dags/orders_dag.py"),
                details.get("dagFilePaths"));
        assertEquals("gcp-sm://projects/pulse-prod-001/secrets/pulse-deploy-sa/versions/7",
                details.get("tokenReference"));
    }

    @Test
    @DisplayName("Plan capability block shows GCP/ICEBERG approved under matrix v1")
    void capabilityBlockApproved() {
        wireGcpRun("run-1", "tenant-A", "target-1", "pkg-1", Map.of(), "ICEBERG");
        AdapterPlan plan = adapter.submitPoll().plan("run-1");
        assertNotNull(plan.capability());
        assertTrue(plan.capability().approved(),
                "GCP + ICEBERG must be approved by matrix v1");
        assertEquals("ICEBERG", plan.capability().requestedFormat());
        assertEquals("ICEBERG", plan.capability().resolvedFormat());
        assertEquals(GcpComposerDataprocAdapter.TARGET_TYPE, plan.capability().targetType());
    }

    @Test
    @DisplayName("Plan tokenReference is gcp-sm://; no plaintext token field")
    void noPlaintextSecrets() {
        wireGcpRun("run-1", "tenant-A", "target-1", "pkg-1", Map.of(), "PARQUET");
        AdapterPlan plan = adapter.submitPoll().plan("run-1");
        Object tokenRef = plan.details().get("tokenReference");
        assertNotNull(tokenRef);
        assertTrue(String.valueOf(tokenRef).startsWith("gcp-sm://"),
                "tokenReference must be a gcp-sm:// URI; got: " + tokenRef);
        // No raw 'token' key anywhere in details (defense-in-depth).
        assertTrue(plan.details().get("token") == null,
                "details must not contain a raw 'token' field");
        assertTrue(plan.details().get("password") == null,
                "details must not contain a raw 'password' field");
    }

    @Test
    @DisplayName("Submit syncs Composer only; Dataproc jobs are runtime DAG work")
    void submitSyncsComposerWithoutDataprocJob() {
        wireGcpRun("run-1", "tenant-A", "target-1", "pkg-1", Map.of(), "ICEBERG");
        AdapterExecution execution = adapter.submitPoll().submit("run-1");
        assertEquals(DeploymentRunState.RUNNING, execution.resultingState());
        assertEquals("composer-sync-run-1", execution.providerRunId());
        assertEquals("composer-sync-run-1", execution.details().get("composerSyncId"));
        assertEquals("composer-sync-run-1", execution.details().get("activationProviderRunId"));
        assertNull(execution.details().get("validationDagRunId"),
                "normal deploy sync must not trigger a Composer DAG run");
        assertEquals("NOT_REQUESTED", execution.details().get("validationStatus"));
    }

    @Test
    @DisplayName("Normal submit keeps Composer triggerImmediately=false")
    void normalSubmitKeepsImmediateTriggerDisabled() {
        ComposerDagSyncClient recordingClient = mock(ComposerDagSyncClient.class);
        when(recordingClient.syncDags(any())).thenAnswer(inv -> {
            ComposerDagSyncClient.SyncRequest request = inv.getArgument(0);
            assertEquals(false, request.triggerImmediately(),
                    "normal deploy must sync Airflow without triggering validation");
            return new ComposerDagSyncClient.SyncResult(List.of("pipeline_dag"), null);
        });
        adapter = new GcpComposerDataprocAdapter(
                mock(LocalMaterializationAdapter.class),
                runRepo, packageRepo, targetRepo,
                new RuntimeCapabilityMatrix(),
                gcsClient, recordingClient);
        wireGcpRun("run-1", "tenant-A", "target-1", "pkg-1", Map.of(), "ICEBERG");

        AdapterExecution execution = adapter.submitPoll().submit("run-1");

        assertNull(execution.details().get("validationDagRunId"));
        verify(recordingClient).syncDags(any());
    }

    @Test
    @DisplayName("Smoke validation triggers Composer only when explicitly requested")
    void smokeValidationTriggersComposerOnlyWhenRequested() {
        wireGcpRun("run-2", "tenant-A", "target-1", "pkg-1", Map.of(), "ICEBERG",
                true, "SMOKE", true, Map.of("sample", "value"));

        AdapterExecution execution = adapter.submitPoll().submit("run-2");

        assertEquals("RUNNING", execution.details().get("validationStatus"));
        assertNotNull(execution.details().get("validationDagRunId"));
        assertEquals(true, execution.details().get("validationRequested"));
    }

    private void wireGcpRun(String runId, String tenantId, String targetId,
                            String packageId, Map<String, Object> targetConfig, String tableFormat) {
        wireGcpRun(runId, tenantId, targetId, packageId, targetConfig, tableFormat,
                false, "NONE", false, Map.of());
    }

    private void wireGcpRun(String runId, String tenantId, String targetId,
                            String packageId, Map<String, Object> targetConfig, String tableFormat,
                            boolean validationRequested, String validationMode,
                            boolean awaitValidation, Map<String, Object> validationConf) {
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
        meta.put("validationRequested", validationRequested);
        meta.put("validationMode", validationMode);
        meta.put("awaitValidation", awaitValidation);
        meta.put("validationStatus", validationRequested ? "REQUESTED" : "NOT_REQUESTED");
        if (!validationConf.isEmpty()) {
            meta.put("validationConf", validationConf);
            meta.put("validationConfHash", "validation-conf-hash");
        }
        run.setMetadata(meta);
        when(runRepo.findById(runId)).thenReturn(Optional.of(run));

        DeploymentTarget target = new DeploymentTarget();
        target.setId(targetId);
        target.setTenantId(tenantId);
        target.setEnvironment("dev");
        target.setTargetType(GcpComposerDataprocAdapter.TARGET_TYPE);
        target.setEnabled(true);
        target.setConfig(new LinkedHashMap<>(targetConfig));
        when(targetRepo.findById(targetId)).thenReturn(Optional.of(target));

        Package pkg = new Package();
        pkg.setId(packageId);
        pkg.setTenantId(tenantId);
        pkg.setPipelineId("pipeline-A");
        pkg.setVersionId("version-1");
        pkg.setBuildStatus("COMPLETED");
        pkg.setBuiltBy("user-test");
        Map<String, Object> pkgMeta = new LinkedHashMap<>();
        pkgMeta.put("requestedTableFormat", tableFormat);
        pkg.setMetadata(pkgMeta);
        when(packageRepo.findById(packageId)).thenReturn(Optional.of(pkg));
    }
}
