package com.pulse.deploy;

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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 5 contract — determinism. Two identical packages produce
 * identical manifests, hashes, and sorted path lists. Changing
 * artifact content changes the package content hash. Changing
 * generation order does NOT change the hash.
 */
class DeterministicPackageMaterializationTest {

    private PackageRepository packageRepo;
    private GeneratedArtifactRepository artifactRepo;
    private DeploymentRunRepository runRepo;
    private DeploymentRunStateService stateService;
    private DeploymentEvidenceService evidenceService;
    private DeploymentEvidenceRepository evidenceRepository;
    private DeploymentEventRepository eventRepository;
    private LocalMaterializationAdapter adapter;

    @TempDir Path tempRoot;

    @BeforeEach
    void setUp() {
        packageRepo = mock(PackageRepository.class);
        artifactRepo = mock(GeneratedArtifactRepository.class);
        runRepo = mock(DeploymentRunRepository.class);
        evidenceService = mock(DeploymentEvidenceService.class);
        evidenceRepository = mock(DeploymentEvidenceRepository.class);
        eventRepository = mock(DeploymentEventRepository.class);
        when(evidenceService.recordMaterializationManifest(any(), any(), any(), any(), any(), any()))
                .thenReturn(new DeploymentEvidence());
        when(evidenceRepository.findByDeploymentRunIdOrderByCreatedAtAsc(any())).thenReturn(List.of());
        when(eventRepository.findByDeploymentRunIdOrderByCreatedAtAsc(any())).thenReturn(List.of());
        stateService = new DeploymentRunStateService(runRepo);
        when(runRepo.save(any(DeploymentRun.class))).thenAnswer(inv -> inv.getArgument(0));
        adapter = new LocalMaterializationAdapter(packageRepo, artifactRepo, runRepo,
                stateService, evidenceService, evidenceRepository, eventRepository,
                tempRoot.toString());
    }

    @Test
    @DisplayName("Two identical packages → identical sorted path lists, manifest hashes, package content hashes")
    void identicalPackagesProduceIdenticalOutput() throws Exception {
        // First materialization.
        wirePackage("run-A", "pkg-X", List.of(
                artifact("dags/example.py", "AIRFLOW_DAG", "from airflow import DAG\n"),
                artifact("config/x.yml", "CONFIG_YAML", "pipeline: example\n"),
                artifact("manifests/compile-plan.json", "COMPILE_PLAN", "{}\n")));
        var first = adapter.materialize("run-A");

        // Reset run status so the same run id can be re-materialized.
        // (In real life we'd use a fresh run id; we want a hash compare
        // so we run twice with the same content via two different run
        // ids and check the package content hash is identical.)
        wirePackage("run-B", "pkg-X", List.of(
                // SAME content, possibly returned in different order — adapter
                // must sort and produce the same hash.
                artifact("manifests/compile-plan.json", "COMPILE_PLAN", "{}\n"),
                artifact("dags/example.py", "AIRFLOW_DAG", "from airflow import DAG\n"),
                artifact("config/x.yml", "CONFIG_YAML", "pipeline: example\n")));
        var second = adapter.materialize("run-B");

        assertEquals(first.sortedPaths(), second.sortedPaths(),
                "sorted path lists must be identical for identical content");
        assertEquals(first.packageContentSha256(), second.packageContentSha256(),
                "package content hash must be byte-stable across re-materializations of identical content");
        assertEquals(first.fileCount(), second.fileCount());
    }

    @Test
    @DisplayName("Changing artifact content changes the package content hash")
    void changingContentChangesPackageHash() throws Exception {
        wirePackage("run-A", "pkg-X", List.of(
                artifact("dags/example.py", "AIRFLOW_DAG", "from airflow import DAG\n")));
        var first = adapter.materialize("run-A");

        wirePackage("run-B", "pkg-X", List.of(
                artifact("dags/example.py", "AIRFLOW_DAG", "from airflow import DAG\n# edited\n")));
        var second = adapter.materialize("run-B");

        assertNotEquals(first.packageContentSha256(), second.packageContentSha256(),
                "package content hash MUST change when an artifact's bytes change");
    }

    @Test
    @DisplayName("Manifest JSON has stable key ordering — re-render produces byte-identical bytes")
    void manifestKeyOrderingIsStable() throws Exception {
        wirePackage("run-A", "pkg-X", List.of(
                artifact("dags/a.py", "AIRFLOW_DAG", "x"),
                artifact("dags/b.py", "AIRFLOW_DAG", "y")));
        var first = adapter.materialize("run-A");

        // Reset for a second materialization with the same content but a
        // different run id.
        wirePackage("run-B", "pkg-X", List.of(
                artifact("dags/a.py", "AIRFLOW_DAG", "x"),
                artifact("dags/b.py", "AIRFLOW_DAG", "y")));
        var second = adapter.materialize("run-B");

        // Strip the per-run-id field so the rest of the JSON can be
        // compared for byte equality. The runId differs by design but
        // every other field is hash-stable.
        String firstJson = Files.readString(first.manifestPath());
        String secondJson = Files.readString(second.manifestPath());
        String firstNormalized = firstJson
                .replace(first.runId(), "<RUNID>")
                .replace(first.outputRoot().toString(), "<OUT>");
        String secondNormalized = secondJson
                .replace(second.runId(), "<RUNID>")
                .replace(second.outputRoot().toString(), "<OUT>");
        assertEquals(firstNormalized, secondNormalized,
                "Manifest JSON must be byte-identical (modulo runId/outputRoot) across re-materializations");
    }

    @Test
    @DisplayName("Hash format is 64-char lowercase hex SHA-256")
    void hashFormatIs64CharHex() {
        wirePackage("run-A", "pkg-X", List.of(
                artifact("dags/a.py", "AIRFLOW_DAG", "x")));
        var result = adapter.materialize("run-A");
        assertEquals(64, result.packageContentSha256().length());
        assertEquals(64, result.manifestSha256().length());
        assertTrue(result.packageContentSha256().matches("[0-9a-f]{64}"));
    }

    // -----------------------------------------------------------------------

    private void wirePackage(String runId, String packageId, List<GeneratedArtifact> artifacts) {
        DeploymentRun run = new DeploymentRun();
        run.setId(runId);
        run.setDeploymentId("dep-" + runId);
        run.setTenantId("tenant-A");
        run.setStatus(DeploymentRunState.PREFLIGHT_PASSED.name());
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("packageId", packageId);
        run.setMetadata(meta);
        run.setStartedAt(java.time.Instant.parse("2026-05-04T00:00:00Z"));
        run.setCorrelationId("corr-" + runId);
        when(runRepo.findById(runId)).thenReturn(Optional.of(run));

        Package pkg = new Package();
        pkg.setId(packageId);
        pkg.setTenantId("tenant-A");
        pkg.setPipelineId("pipeline-1");
        pkg.setVersionId("v-1");
        pkg.setBuildStatus("COMPLETED");
        Map<String, Object> pkgMeta = new LinkedHashMap<>();
        pkgMeta.put("generationRunId", "gen-" + runId);
        pkg.setMetadata(pkgMeta);
        when(packageRepo.findById(packageId)).thenReturn(Optional.of(pkg));
        when(artifactRepo.findByGenerationRunIdOrderByFilePathAsc("gen-" + runId)).thenReturn(artifacts);
    }

    private static GeneratedArtifact artifact(String path, String type, String content) {
        GeneratedArtifact a = new GeneratedArtifact();
        a.setFilePath(path);
        a.setFileType(type);
        a.setContent(content);
        a.setContentHash("h-" + path);
        return a;
    }
}
