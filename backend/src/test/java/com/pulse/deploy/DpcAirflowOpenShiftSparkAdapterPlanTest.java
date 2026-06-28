package com.pulse.deploy;

import com.pulse.deploy.adapter.AdapterPlan;
import com.pulse.deploy.adapter.AdapterExecution;
import com.pulse.deploy.adapter.LocalMaterializationAdapter;
import com.pulse.deploy.adapter.dpc.DpcAirflowClient;
import com.pulse.deploy.adapter.dpc.DpcAirflowOpenShiftSparkAdapter;
import com.pulse.deploy.adapter.dpc.DpcObjectStoreClient;
import com.pulse.deploy.adapter.dpc.StubDpcAirflowClient;
import com.pulse.deploy.adapter.dpc.StubDpcObjectStoreClient;
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
 * Phase 7 — fixture-driven plan contract for the DPC adapter.
 *
 * <p>Same shape as the GCP plan test, but exercises DPC's distinct
 * capability-matrix surface (Iceberg-without-hint applies the
 * {@code DPC_PARQUET_LIMITED} fallback; Delta is rejected outright).
 */
class DpcAirflowOpenShiftSparkAdapterPlanTest {

    private DeploymentRunRepository runRepo;
    private PackageRepository packageRepo;
    private DeploymentTargetRepository targetRepo;
    private DpcObjectStoreClient objectStoreClient;
    private DpcAirflowClient airflowClient;
    private DpcAirflowOpenShiftSparkAdapter adapter;

    @BeforeEach
    void setUp() {
        runRepo = mock(DeploymentRunRepository.class);
        packageRepo = mock(PackageRepository.class);
        targetRepo = mock(DeploymentTargetRepository.class);
        objectStoreClient = new StubDpcObjectStoreClient();
        airflowClient = new StubDpcAirflowClient();
        adapter = new DpcAirflowOpenShiftSparkAdapter(
                mock(LocalMaterializationAdapter.class),
                runRepo, packageRepo, targetRepo,
                new RuntimeCapabilityMatrix(),
                objectStoreClient, airflowClient);
    }

    @Test
    @DisplayName("Plan envelope schemaVersion + verb + adapter are pinned")
    void planEnvelopePinned() {
        wireDpcRun("run-1", "tenant-A", "target-1", "pkg-1", Map.of(), "PARQUET");
        AdapterPlan plan = adapter.materialization().plan("run-1");
        assertEquals(DpcAirflowOpenShiftSparkAdapter.SCHEMA_VERSION_PLAN, plan.schemaVersion());
        assertEquals(DpcAirflowOpenShiftSparkAdapter.TARGET_TYPE, plan.adapter());
        assertEquals(AdapterPlan.VERB_MATERIALIZE, plan.verb());
    }

    @Test
    @DisplayName("Plan defaults: dpcAirflowEndpoint / dpcSparkEndpoint / objectStoreBucket populate sensibly")
    void planDefaultsPopulate() {
        wireDpcRun("run-1", "tenant-A", "target-1", "pkg-1", Map.of(), "PARQUET");
        AdapterPlan plan = adapter.submitPoll().plan("run-1");
        Map<String, Object> details = plan.details();
        assertNotNull(details.get("objectStoreEndpoint"));
        assertNotNull(details.get("objectStoreBucket"));
        assertEquals("packages/run-1/", details.get("objectStorePrefix"));
        assertNotNull(details.get("dpcAirflowEndpoint"));
        assertNotNull(details.get("dpcSparkEndpoint"));
        assertEquals("pulse-pipeline", details.get("sparkApp"));
        assertEquals("package/main.py", details.get("mainPyFile"));
        assertEquals("NONE", details.get("validationMode"));
        assertEquals(false, details.get("validationRequested"));
        assertEquals(false, details.get("activationTriggerImmediately"));
    }

    @Test
    @DisplayName("DPC + ICEBERG without platform hint: capability block carries the DPC_PARQUET_LIMITED fallback")
    void dpcIcebergFallback() {
        wireDpcRun("run-1", "tenant-A", "target-1", "pkg-1",
                Map.of("dpcIcebergSupported", false), "ICEBERG");
        AdapterPlan plan = adapter.submitPoll().plan("run-1");
        assertNotNull(plan.capability());
        assertTrue(plan.capability().approved(),
                "fallback variant returns approved=true with a fallbackMode");
        assertEquals(RuntimeCapabilityMatrix.FALLBACK_DPC_PARQUET_LIMITED,
                plan.capability().fallbackMode());
        assertEquals("ICEBERG", plan.capability().requestedFormat());
        assertEquals("PARQUET", plan.capability().resolvedFormat());
        assertEquals(false, plan.details().get("dpcIcebergSupported"));
    }

    @Test
    @DisplayName("DPC + ICEBERG with platform hint: capability block approves with no fallback")
    void dpcIcebergWithHint() {
        wireDpcRun("run-1", "tenant-A", "target-1", "pkg-1",
                Map.of("dpcIcebergSupported", true), "ICEBERG");
        AdapterPlan plan = adapter.submitPoll().plan("run-1");
        assertTrue(plan.capability().approved());
        assertNull(plan.capability().fallbackMode());
        assertEquals("ICEBERG", plan.capability().resolvedFormat());
        assertEquals(true, plan.details().get("dpcIcebergSupported"));
    }

    @Test
    @DisplayName("DPC + DELTA: capability block rejected (matrix v1)")
    void dpcDeltaRejected() {
        wireDpcRun("run-1", "tenant-A", "target-1", "pkg-1", Map.of(), "DELTA");
        AdapterPlan plan = adapter.submitPoll().plan("run-1");
        assertEquals(false, plan.capability().approved());
    }

    @Test
    @DisplayName("Plan tokenReference is gcp-sm://; details must not include plaintext")
    void noPlaintextSecrets() {
        wireDpcRun("run-1", "tenant-A", "target-1", "pkg-1", Map.of(), "PARQUET");
        AdapterPlan plan = adapter.submitPoll().plan("run-1");
        Object tokenRef = plan.details().get("tokenReference");
        assertNotNull(tokenRef);
        assertTrue(String.valueOf(tokenRef).startsWith("gcp-sm://"),
                "DPC plan tokenReference must be a gcp-sm:// URI");
    }

    @Test
    @DisplayName("Custom DPC config overrides flow into the plan")
    void targetConfigOverrides() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("objectStoreEndpoint", "https://dpc-prod.example.com");
        config.put("objectStoreBucket", "pulse-prod-dpc-packages");
        config.put("dpcAirflowEndpoint", "https://airflow.dpc-prod.example.com");
        config.put("dpcSparkEndpoint", "https://spark.dpc-prod.example.com");
        config.put("sparkApp", "pulse-prod-pipeline");
        config.put("mainPyFile", "package/entry.py");
        config.put("dagFilePaths", List.of("package/dags/sales.py"));
        config.put("tokenReference",
                "gcp-sm://projects/pulse-dpc/secrets/pulse-deploy-sa/versions/12");
        wireDpcRun("run-1", "tenant-A", "target-1", "pkg-1", config, "PARQUET");
        AdapterPlan plan = adapter.submitPoll().plan("run-1");
        Map<String, Object> details = plan.details();
        assertEquals("https://airflow.dpc-prod.example.com", details.get("dpcAirflowEndpoint"));
        assertEquals("https://spark.dpc-prod.example.com", details.get("dpcSparkEndpoint"));
        assertEquals("pulse-prod-pipeline", details.get("sparkApp"));
        assertEquals("package/entry.py", details.get("mainPyFile"));
        assertEquals(List.of("package/dags/sales.py"), details.get("dagFilePaths"));
        assertEquals("gcp-sm://projects/pulse-dpc/secrets/pulse-deploy-sa/versions/12",
                details.get("tokenReference"));
    }

    @Test
    @DisplayName("Submit syncs DPC Airflow only; Spark jobs are runtime DAG work")
    void submitSyncsAirflowWithoutSparkJob() {
        wireDpcRun("run-1", "tenant-A", "target-1", "pkg-1", Map.of(), "PARQUET");
        AdapterExecution execution = adapter.submitPoll().submit("run-1");
        assertEquals(DeploymentRunState.RUNNING, execution.resultingState());
        assertEquals("dpc-airflow-sync-run-1", execution.providerRunId());
        assertEquals("dpc-airflow-sync-run-1", execution.details().get("dpcAirflowSyncId"));
        assertEquals("dpc-airflow-sync-run-1", execution.details().get("activationProviderRunId"));
        assertNull(execution.details().get("validationDagRunId"),
                "normal deploy sync must not trigger a DPC Airflow DAG run");
        assertEquals("NOT_REQUESTED", execution.details().get("validationStatus"));
    }

    @Test
    @DisplayName("Normal submit keeps DPC Airflow triggerImmediately=false")
    void normalSubmitKeepsImmediateTriggerDisabled() {
        DpcAirflowClient recordingClient = mock(DpcAirflowClient.class);
        when(recordingClient.syncDags(any())).thenAnswer(inv -> {
            DpcAirflowClient.SyncRequest request = inv.getArgument(0);
            assertEquals(false, request.triggerImmediately(),
                    "normal deploy must sync Airflow without triggering validation");
            return new DpcAirflowClient.SyncResult(List.of("pipeline_dag"), null);
        });
        adapter = new DpcAirflowOpenShiftSparkAdapter(
                mock(LocalMaterializationAdapter.class),
                runRepo, packageRepo, targetRepo,
                new RuntimeCapabilityMatrix(),
                objectStoreClient, recordingClient);
        wireDpcRun("run-1", "tenant-A", "target-1", "pkg-1", Map.of(), "PARQUET");

        AdapterExecution execution = adapter.submitPoll().submit("run-1");

        assertNull(execution.details().get("validationDagRunId"));
        verify(recordingClient).syncDags(any());
    }

    @Test
    @DisplayName("Smoke validation triggers DPC Airflow only when explicitly requested")
    void smokeValidationTriggersDpcAirflowOnlyWhenRequested() {
        wireDpcRun("run-2", "tenant-A", "target-1", "pkg-1", Map.of(), "PARQUET",
                true, "SMOKE", true, Map.of("sample", "value"));

        AdapterExecution execution = adapter.submitPoll().submit("run-2");

        assertEquals("RUNNING", execution.details().get("validationStatus"));
        assertNotNull(execution.details().get("validationDagRunId"));
        assertEquals(true, execution.details().get("validationRequested"));
    }

    private void wireDpcRun(String runId, String tenantId, String targetId,
                            String packageId, Map<String, Object> targetConfig, String tableFormat) {
        wireDpcRun(runId, tenantId, targetId, packageId, targetConfig, tableFormat,
                false, "NONE", false, Map.of());
    }

    private void wireDpcRun(String runId, String tenantId, String targetId,
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
        target.setTargetType(DpcAirflowOpenShiftSparkAdapter.TARGET_TYPE);
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
