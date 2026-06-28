package com.pulse.deploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.codegen.model.GeneratedArtifact;
import com.pulse.codegen.repository.GeneratedArtifactRepository;
import com.pulse.deploy.adapter.LocalMaterializationAdapter;
import com.pulse.deploy.evidence.DeploymentEvidenceService;
import com.pulse.deploy.model.DeploymentEvidence;
import com.pulse.deploy.model.DeploymentRun;
import com.pulse.deploy.model.Package;
import com.pulse.deploy.repository.DeploymentEventRepository;
import com.pulse.deploy.repository.DeploymentEvidenceRepository;
import com.pulse.deploy.repository.DeploymentRunRepository;
import com.pulse.deploy.repository.PackageRepository;
import com.pulse.deploy.run.DeploymentRunState;
import com.pulse.deploy.run.DeploymentRunStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 5 contract — LocalMaterializationAdapter writes the
 * documented evidence + manifest envelopes, advances the run state
 * machine through MATERIALIZING → MATERIALIZED, and rejects forbidden
 * paths. No Docker/Airflow/Spark/MinIO/cloud touched.
 */
class LocalMaterializationAdapterContractTest {

    private PackageRepository packageRepo;
    private GeneratedArtifactRepository artifactRepo;
    private DeploymentRunRepository runRepo;
    private DeploymentRunStateService stateService;
    private DeploymentEvidenceService evidenceService;
    private DeploymentEvidenceRepository evidenceRepository;
    private DeploymentEventRepository eventRepository;
    private LocalMaterializationAdapter adapter;

    @TempDir Path tempDir;

    @BeforeEach
    void setUp() {
        packageRepo = mock(PackageRepository.class);
        artifactRepo = mock(GeneratedArtifactRepository.class);
        runRepo = mock(DeploymentRunRepository.class);
        evidenceService = mock(DeploymentEvidenceService.class);
        evidenceRepository = mock(DeploymentEvidenceRepository.class);
        eventRepository = mock(DeploymentEventRepository.class);
        // Real state service over a mocked run repo so the adapter's
        // PREFLIGHT_PASSED → MATERIALIZING → MATERIALIZED transitions
        // exercise the locked transition table.
        stateService = new DeploymentRunStateService(runRepo);
        when(runRepo.save(any(DeploymentRun.class))).thenAnswer(inv -> inv.getArgument(0));
        when(evidenceRepository.findByDeploymentRunIdOrderByCreatedAtAsc(any())).thenReturn(List.of());
        when(eventRepository.findByDeploymentRunIdOrderByCreatedAtAsc(any())).thenReturn(List.of());
        adapter = new LocalMaterializationAdapter(packageRepo, artifactRepo, runRepo,
                stateService, evidenceService, evidenceRepository, eventRepository,
                tempDir.toString());
    }

    @Test
    @DisplayName("Materialize writes manifest + evidence-index + every artifact under package/")
    void writesManifestAndEvidenceIndexAndArtifacts() throws Exception {
        DeploymentRun run = preflightPassedRun("run-1", "dep-1", "pkg-1");
        Package pkg = pkg("pkg-1", "tenant-A", "pipeline-1", "run-gen-1");
        when(runRepo.findById("run-1")).thenReturn(Optional.of(run));
        when(packageRepo.findById("pkg-1")).thenReturn(Optional.of(pkg));
        when(artifactRepo.findByGenerationRunIdOrderByFilePathAsc("run-gen-1")).thenReturn(List.of(
                artifact("dags/example.py", "AIRFLOW_DAG", "from airflow import DAG\n"),
                artifact("config/pipeline.yml", "CONFIG_YAML", "pipeline: example\n")));

        var result = adapter.materialize("run-1");
        assertTrue(Files.exists(result.manifestPath()), "manifest must exist");
        assertTrue(Files.exists(result.evidenceIndexPath()), "evidence-index must exist");
        assertTrue(Files.exists(result.outputRoot().resolve("package/dags/example.py")));
        assertTrue(Files.exists(result.outputRoot().resolve("package/config/pipeline.yml")));
        assertEquals(2, result.fileCount());

        ObjectMapper mapper = new ObjectMapper();
        JsonNode manifest = mapper.readTree(Files.readAllBytes(result.manifestPath()));
        assertEquals("deployment-materialization-manifest.v1", manifest.path("schemaVersion").asText());
        assertEquals("pkg-1", manifest.path("packageId").asText());
        assertEquals("run-1", manifest.path("deploymentRunId").asText());
        assertEquals(2, manifest.path("fileCount").asInt());
        // Files are sorted by normalized POSIX path.
        JsonNode files = manifest.path("files");
        assertEquals("package/config/pipeline.yml", files.get(0).path("path").asText());
        assertEquals("package/dags/example.py", files.get(1).path("path").asText());
        assertEquals(64, manifest.path("packageContentSha256").asText().length());

        JsonNode evidenceIndex = mapper.readTree(Files.readAllBytes(result.evidenceIndexPath()));
        assertEquals("deployment-evidence-index.v1", evidenceIndex.path("schemaVersion").asText());
        assertEquals("run-1", evidenceIndex.path("deploymentRunId").asText());
    }

    @Test
    @DisplayName("Run advances PREFLIGHT_PASSED → MATERIALIZING → MATERIALIZED on success")
    void advancesRunStateOnSuccess() throws Exception {
        DeploymentRun run = preflightPassedRun("run-2", "dep-2", "pkg-2");
        Package pkg = pkg("pkg-2", "tenant-A", "pipeline-2", "run-gen-2");
        when(runRepo.findById("run-2")).thenReturn(Optional.of(run));
        when(packageRepo.findById("pkg-2")).thenReturn(Optional.of(pkg));
        when(artifactRepo.findByGenerationRunIdOrderByFilePathAsc("run-gen-2")).thenReturn(List.of(
                artifact("dags/x.py", "AIRFLOW_DAG", "from airflow import DAG\n")));

        adapter.materialize("run-2");
        // Two save() calls: one for MATERIALIZING transition, one for
        // MATERIALIZED transition. The terminal state must be
        // MATERIALIZED at the end.
        verify(runRepo, org.mockito.Mockito.atLeast(2)).save(any(DeploymentRun.class));
        assertEquals("MATERIALIZED", run.getStatus(),
                "Run must end in MATERIALIZED after a successful materialization");
    }

    @Test
    @DisplayName("Materialize from a non-PREFLIGHT_PASSED run is rejected")
    void rejectsRunsNotInPreflightPassed() {
        DeploymentRun run = new DeploymentRun();
        run.setId("run-3");
        run.setDeploymentId("dep-3");
        run.setTenantId("tenant-A");
        run.setStatus(DeploymentRunState.PENDING.name());
        run.setMetadata(Map.of("packageId", "pkg-3"));
        when(runRepo.findById("run-3")).thenReturn(Optional.of(run));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> adapter.materialize("run-3"));
        assertTrue(ex.getMessage().contains("PREFLIGHT_PASSED"),
                "Error message must call out the required state, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Forbidden paths (.git, .env, .. traversal) FAIL the run; nothing is materialized")
    void forbiddenPathsFailMaterialization() {
        DeploymentRun run = preflightPassedRun("run-4", "dep-4", "pkg-4");
        Package pkg = pkg("pkg-4", "tenant-A", "pipeline-4", "run-gen-4");
        when(runRepo.findById("run-4")).thenReturn(Optional.of(run));
        when(packageRepo.findById("pkg-4")).thenReturn(Optional.of(pkg));
        when(artifactRepo.findByGenerationRunIdOrderByFilePathAsc("run-gen-4")).thenReturn(List.of(
                artifact("dags/legit.py", "AIRFLOW_DAG", "from airflow import DAG\n"),
                artifact(".git/HEAD", "AIRFLOW_DAG", "ref: refs/heads/main\n"),
                artifact("config/.env.local", "CONFIG_YAML", "DB_PASSWORD=oops\n"),
                artifact("../../etc/passwd", "AIRFLOW_DAG", "evil"),
                artifact("/absolute/leak", "AIRFLOW_DAG", "evil"),
                artifact("dbt_packages/dep/model.sql", "AIRFLOW_DAG", "select 1")));

        var thrown = assertThrows(
                com.pulse.deploy.adapter.ForbiddenArtifactPathException.class,
                () -> adapter.materialize("run-4"));
        // Five forbidden paths must produce five violations.
        assertEquals(5, thrown.violations().size(),
                "expected 5 forbidden-path violations, got: " + thrown.violations());

        // Run terminated in FAILED with a stable, parseable reason.
        assertEquals("FAILED", run.getStatus(),
                "Forbidden artifact path must drive the run to FAILED, not MATERIALIZED");
        assertNotNull(run.getFailureReason());
        assertTrue(run.getFailureReason().startsWith("forbidden_artifact_paths:"),
                "failure_reason must start with stable 'forbidden_artifact_paths:' prefix, got: "
                        + run.getFailureReason());

        // All-or-nothing: no artifact (not even the legit one) is
        // written when ANY forbidden path is in the set.
        Path runRoot = tempDir.resolve("run-4");
        assertFalse(Files.exists(runRoot.resolve("package/dags/legit.py")));
        assertFalse(Files.exists(runRoot.resolve("materialization-manifest.json")),
                "manifest must NOT be written for a failed materialization");
    }

    @Test
    @DisplayName("Single forbidden artifact still fails — no allowlist for forbidden paths")
    void singleForbiddenArtifactFails() {
        DeploymentRun run = preflightPassedRun("run-7", "dep-7", "pkg-7");
        Package pkg = pkg("pkg-7", "tenant-A", "pipeline-7", "run-gen-7");
        when(runRepo.findById("run-7")).thenReturn(Optional.of(run));
        when(packageRepo.findById("pkg-7")).thenReturn(Optional.of(pkg));
        when(artifactRepo.findByGenerationRunIdOrderByFilePathAsc("run-gen-7")).thenReturn(List.of(
                artifact("config/secrets.secret", "CONFIG_YAML", "PASSWORD=leak\n")));

        var thrown = assertThrows(
                com.pulse.deploy.adapter.ForbiddenArtifactPathException.class,
                () -> adapter.materialize("run-7"));
        assertEquals(1, thrown.violations().size());
        assertEquals("FAILED", run.getStatus());
        assertTrue(run.getFailureReason().contains("forbidden_glob:.secret"),
                "failure_reason must include the specific forbidden code, got: "
                        + run.getFailureReason());
    }

    @Test
    @DisplayName("Manifest evidence sha256 matches the on-disk file's bytes")
    void evidenceShaMatchesOnDiskFile() throws Exception {
        DeploymentRun run = preflightPassedRun("run-6", "dep-6", "pkg-6");
        Package pkg = pkg("pkg-6", "tenant-A", "pipeline-6", "run-gen-6");
        when(runRepo.findById("run-6")).thenReturn(Optional.of(run));
        when(packageRepo.findById("pkg-6")).thenReturn(Optional.of(pkg));
        when(artifactRepo.findByGenerationRunIdOrderByFilePathAsc("run-gen-6")).thenReturn(List.of(
                artifact("dags/example.py", "AIRFLOW_DAG", "from airflow import DAG\n"),
                artifact("config/x.yml", "CONFIG_YAML", "p: x\n")));
        when(evidenceService.recordMaterializationManifest(any(), any(), any(), any(), any(), any()))
                .thenReturn(new com.pulse.deploy.model.DeploymentEvidence());

        var result = adapter.materialize("run-6");

        // Independently SHA-256 the on-disk file and compare against the
        // value recorded by the adapter and forwarded to evidence.
        byte[] onDisk = Files.readAllBytes(result.manifestPath());
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        byte[] hashBytes = digest.digest(onDisk);
        StringBuilder hex = new StringBuilder(hashBytes.length * 2);
        for (byte b : hashBytes) hex.append(String.format("%02x", b));
        String onDiskSha = hex.toString();
        assertEquals(onDiskSha, result.manifestSha256(),
                "MaterializationResult.manifestSha256 MUST equal SHA-256 of the on-disk manifest bytes");

        // And the value passed into the evidence service must equal the
        // same on-disk hash.
        org.mockito.ArgumentCaptor<String> shaCaptor =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(evidenceService).recordMaterializationManifest(
                org.mockito.ArgumentMatchers.eq("dep-6"),
                org.mockito.ArgumentMatchers.eq("run-6"),
                org.mockito.ArgumentMatchers.eq("pkg-6"),
                org.mockito.ArgumentMatchers.any(),
                shaCaptor.capture(),
                org.mockito.ArgumentMatchers.any());
        assertEquals(onDiskSha, shaCaptor.getValue(),
                "DeploymentEvidence.sha256 MUST equal SHA-256 of the on-disk manifest bytes");
    }

    @Test
    @DisplayName("Materialization records a MATERIALIZATION_MANIFEST evidence row")
    void recordsMaterializationManifestEvidence() {
        DeploymentRun run = preflightPassedRun("run-5", "dep-5", "pkg-5");
        Package pkg = pkg("pkg-5", "tenant-A", "pipeline-5", "run-gen-5");
        when(runRepo.findById("run-5")).thenReturn(Optional.of(run));
        when(packageRepo.findById("pkg-5")).thenReturn(Optional.of(pkg));
        when(artifactRepo.findByGenerationRunIdOrderByFilePathAsc("run-gen-5")).thenReturn(List.of(
                artifact("dags/a.py", "AIRFLOW_DAG", "from airflow import DAG\n")));
        when(evidenceService.recordMaterializationManifest(any(), any(), any(), any(), any(), any()))
                .thenReturn(new DeploymentEvidence());

        adapter.materialize("run-5");

        org.mockito.ArgumentCaptor<Map<String, Object>> manifestCaptor =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(evidenceService).recordMaterializationManifest(
                org.mockito.ArgumentMatchers.eq("dep-5"),
                org.mockito.ArgumentMatchers.eq("run-5"),
                org.mockito.ArgumentMatchers.eq("pkg-5"),
                manifestCaptor.capture(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
        Map<String, Object> manifest = manifestCaptor.getValue();
        assertEquals("deployment-materialization-manifest.v1", manifest.get("schemaVersion"));
        assertEquals("pkg-5", manifest.get("packageId"));
    }

    // -----------------------------------------------------------------------

    private static DeploymentRun preflightPassedRun(String runId, String depId, String packageId) {
        DeploymentRun run = new DeploymentRun();
        run.setId(runId);
        run.setDeploymentId(depId);
        run.setTenantId("tenant-A");
        run.setStatus(DeploymentRunState.PREFLIGHT_PASSED.name());
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("packageId", packageId);
        run.setMetadata(meta);
        run.setStartedAt(java.time.Instant.parse("2026-05-04T00:00:00Z"));
        run.setCorrelationId("corr-1");
        return run;
    }

    private static Package pkg(String id, String tenantId, String pipelineId, String generationRunId) {
        Package pkg = new Package();
        pkg.setId(id);
        pkg.setTenantId(tenantId);
        pkg.setPipelineId(pipelineId);
        pkg.setVersionId("v-1");
        pkg.setBuildStatus("COMPLETED");
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("generationRunId", generationRunId);
        pkg.setMetadata(meta);
        return pkg;
    }

    private static GeneratedArtifact artifact(String path, String type, String content) {
        GeneratedArtifact a = new GeneratedArtifact();
        a.setFilePath(path);
        a.setFileType(type);
        a.setContent(content);
        a.setContentHash("source-" + path);
        return a;
    }
}
