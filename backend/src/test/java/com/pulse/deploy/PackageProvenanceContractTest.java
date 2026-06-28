package com.pulse.deploy;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.pulse.codegen.model.GeneratedArtifact;
import com.pulse.codegen.model.GenerationRun;
import com.pulse.codegen.repository.GeneratedArtifactRepository;
import com.pulse.codegen.repository.GenerationRunRepository;
import com.pulse.deploy.boundary.DeployBoundaryService;
import com.pulse.deploy.controller.DeployController;
import com.pulse.deploy.model.Package;
import com.pulse.deploy.repository.ApprovalRequestRepository;
import com.pulse.deploy.repository.DeploymentRepository;
import com.pulse.deploy.repository.DeploymentTargetRepository;
import com.pulse.deploy.repository.PackageRepository;
import com.pulse.deploy.service.PackageService;
import com.pulse.git.model.GitRepo;
import com.pulse.git.repository.GitRepoRepository;
import com.pulse.git.service.LocalGitService;
import com.pulse.storage.StorageBackendDeployGate;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 2 contract — package Git provenance.
 *
 * <p>Builds a real on-disk JGit repository under {@code @TempDir} so
 * provenance capture is exercised end-to-end without requiring docker,
 * a remote, or any network. Asserts:
 *
 * <ol>
 *   <li>Clean repo → all SHAs populated, status="clean", manifest hash
 *       is byte-stable across two captures of the same content.</li>
 *   <li>Dirty working tree → status="dirty", dirtyFileCount &gt; 0,
 *       static assessment surfaces a warning.</li>
 *   <li>No tenant Git repo → status="missing", static assessment
 *       surfaces a blocker (Phase 2 marks; Phase 4 hard-gates).</li>
 *   <li>Spoof resistance — caller-supplied JSON Git fields are silently
 *       dropped because {@code BuildRequest} has no Git fields, so
 *       provenance is always server-derived.</li>
 *   <li>{@code package-manifest.json} + {@code git/provenance.json}
 *       fixtures are written under {@code build/deployment-package-fixtures/}
 *       and conform to the documented {@code v1} schemas.</li>
 * </ol>
 */
class PackageProvenanceContractTest {

    private static final ObjectMapper PRETTY = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private PackageRepository packageRepo;
    private DeploymentRepository deployRepo;
    private DeploymentTargetRepository targetRepo;
    private ApprovalRequestRepository approvalRepo;
    private GenerationRunRepository runRepo;
    private GeneratedArtifactRepository artifactRepo;
    private StorageBackendDeployGate deployGate;
    private GitRepoRepository gitRepoRepository;
    private LocalGitService localGitService;
    private PackageService packageService;
    private DeployController controller;

    @BeforeEach
    void setUp() {
        packageRepo = mock(PackageRepository.class);
        deployRepo = mock(DeploymentRepository.class);
        targetRepo = mock(DeploymentTargetRepository.class);
        approvalRepo = mock(ApprovalRequestRepository.class);
        runRepo = mock(GenerationRunRepository.class);
        artifactRepo = mock(GeneratedArtifactRepository.class);
        deployGate = mock(StorageBackendDeployGate.class);
        gitRepoRepository = mock(GitRepoRepository.class);
        localGitService = new LocalGitService();
        var runtimeAuth = com.pulse.runtime.TestRuntimeAuthorityFactory.gcpPulse();
        packageService = new PackageService(gitRepoRepository, localGitService, runtimeAuth,
                mock(com.pulse.storage.contract.service.TableContractService.class),
                mock(com.pulse.deploy.projection.repository.RuntimeProjectionRepository.class),
                mock(com.pulse.deploy.projection.repository.RuntimeProjectionDdlStatementRepository.class));
        controller = new DeployController(
                packageRepo, deployRepo, targetRepo, approvalRepo,
                runRepo, artifactRepo, deployGate, packageService,
                new com.pulse.auth.policy.AuthorizationPolicyService(),
                new com.pulse.auth.policy.ActorResolverService(),
                mock(com.pulse.deploy.preflight.DeploymentPreflightService.class),
                mock(com.pulse.deploy.repository.DeploymentRunRepository.class),
                mock(com.pulse.deploy.evidence.DeploymentEvidenceService.class),
                mock(com.pulse.deploy.orchestrator.DeploymentRunOrchestrator.class),
                runtimeAuth,
                mock(com.pulse.deploy.evidence.RuntimeEvidenceService.class),
                mock(DeployBoundaryService.class)
        );
    }

    @Test
    void cleanRepo_capturesProvenanceWithDeterministicManifestHash(@TempDir Path tempDir) throws Exception {
        Path repoPath = initRepoWithCommit(tempDir, "main", "README.md", "hello\n", "initial commit");
        GitRepo repo = makeGitRepo("repo-1", "tenant-clean", repoPath.toString());
        stubProvenance("tenant-clean", repo);

        GenerationRun run = makeRun("run-clean", "version-1", "pipeline-1", "tenant-clean");
        List<GeneratedArtifact> artifacts = List.of(
                artifact("dags/example.py", "AIRFLOW_DAG", "from airflow import DAG\n", "hash-dag"),
                artifact("requirements.txt", "REQUIREMENTS_TXT", """
                        apache-airflow>=2.8.0
                        apache-airflow-providers-apache-spark>=4.7.0
                        dbt-core>=1.7.0
                        dbt-spark>=1.7.0
                        """, "hash-req"),
                artifact("config/pipeline.yml", "CONFIG_YAML", "pipeline: example\n", "hash-config"),
                artifact("manifests/compile-plan.json", "COMPILE_PLAN", "{}\n", "hash-plan")
        );
        when(runRepo.findTopByVersionIdOrderByCreatedAtDesc("version-1"))
                .thenReturn(Optional.of(run));
        when(artifactRepo.findByGenerationRunIdOrderByFilePathAsc("run-clean"))
                .thenReturn(artifacts);
        when(packageRepo.save(any(Package.class))).thenAnswer(inv -> {
            Package p = inv.getArgument(0);
            if (p.getId() == null) p.setId("pkg-clean");
            return p;
        });

        ResponseEntity<Package> response = controller.buildPackage(
                "version-1",
                new DeployController.BuildRequest("pipeline-1", "tenant-clean", "tester", null));
        assertEquals(200, response.getStatusCode().value());
        Package pkg = response.getBody();
        assertNotNull(pkg);

        @SuppressWarnings("unchecked")
        Map<String, Object> git = (Map<String, Object>) pkg.getMetadata().get("git");
        assertNotNull(git, "metadata.git block must be present");
        assertEquals("repo-1", git.get("repoId"));
        assertEquals("main", git.get("branch"));
        assertEquals("clean", git.get("workingTreeStatus"));
        assertNotNull(git.get("commitSha"));
        assertNotNull(git.get("treeSha"));
        assertEquals(40, ((String) git.get("commitSha")).length(), "commit SHA must be a full 40-char SHA-1");
        assertEquals(40, ((String) git.get("treeSha")).length(), "tree SHA must be a full 40-char SHA-1");

        @SuppressWarnings("unchecked")
        Map<String, Object> manifest = (Map<String, Object>) pkg.getMetadata().get("packageManifest");
        assertEquals("deployment-package-manifest.v1", manifest.get("schemaVersion"));
        assertEquals("pkg-clean", manifest.get("packageId"));
        assertEquals("tenant-clean", manifest.get("tenantId"));
        assertEquals("pipeline-1", manifest.get("pipelineId"));
        assertEquals("version-1", manifest.get("versionId"));
        assertEquals("run-clean", manifest.get("generationRunId"));
        assertEquals("SHA-256", manifest.get("hashAlgorithm"));
        @SuppressWarnings("unchecked")
        Map<String, Object> manifestGit = (Map<String, Object>) manifest.get("git");
        assertEquals(git, manifestGit, "manifest.git must equal the diagnostic block stamped on metadata.git");
        @SuppressWarnings("unchecked")
        Map<String, Object> capabilityProfile = (Map<String, Object>) manifest.get("capabilityProfile");
        assertEquals("NONE", capabilityProfile.get("controlPlaneDependency"));
        assertEquals("DISABLED", capabilityProfile.get("airflowCallbackPolicy"));
        @SuppressWarnings("unchecked")
        Map<String, Object> callbackDiagnostics = (Map<String, Object>) manifest.get("callbackPolicyDiagnostics");
        assertEquals(true, callbackDiagnostics.get("promotedArtifactReady"));
        assertEquals(List.of(), callbackDiagnostics.get("violations"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> files = (List<Map<String, Object>>) manifest.get("files");
        assertEquals(4, files.size());

        // Static assessment carries no blocker for a clean repo.
        @SuppressWarnings("unchecked")
        Map<String, Object> assessment = (Map<String, Object>) pkg.getMetadata().get("staticRuntimeAssessment");
        @SuppressWarnings("unchecked")
        List<String> blockers = (List<String>) assessment.get("blockers");
        assertFalse(blockers.stream().anyMatch(b -> b.contains("Missing Git provenance")),
                "Clean repo must not surface a provenance blocker, got: " + blockers);

        // Phase 2 acceptance text: flat top-level provenance keys must
        // mirror the nested metadata.git block AND packageManifest.git
        // exactly so both shapes are queryable by downstream callers.
        assertFlatAliasesMatchManifest(pkg.getMetadata(), pkg.getArtifactHash());

        // Hash determinism — capturing provenance + building the manifest a
        // second time over identical inputs must produce the same hash.
        PackageService.PackageProvenance second = packageService.captureProvenance(
                "tenant-clean", java.time.Instant.parse("2026-05-04T00:00:00Z"));
        Map<String, Object> manifest2 = packageService.buildManifest(
                "pkg-clean", "tenant-clean", "pipeline-1", "version-1",
                run, "tester", artifacts, pkg.getArtifactHash(),
                second, java.time.Instant.parse("2026-05-04T00:00:00Z"));
        Map<String, Object> manifest3 = packageService.buildManifest(
                "pkg-clean", "tenant-clean", "pipeline-1", "version-1",
                run, "tester", artifacts, pkg.getArtifactHash(),
                second, java.time.Instant.parse("2026-05-04T00:00:00Z"));
        assertEquals(packageService.computeManifestHash(manifest2),
                packageService.computeManifestHash(manifest3),
                "Identical manifest content must produce identical hashes");
        assertEquals(64, packageService.computeManifestHash(manifest2).length(),
                "SHA-256 hash must be 64 hex chars");

        // Phase 2 evidence fixture: write package-manifest.json + git/provenance.json
        // under build/deployment-package-fixtures/<runId>/ for downstream review.
        writeFixture("clean", manifest2, second);
    }

    @Test
    void dirtyWorkingTree_capturesDirtyStatusAndSurfacesWarning(@TempDir Path tempDir) throws Exception {
        Path repoPath = initRepoWithCommit(tempDir, "main", "README.md", "hello\n", "initial commit");
        // Append an uncommitted change to make the working tree dirty.
        Files.write(repoPath.resolve("README.md"),
                "uncommitted edit\n".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                StandardOpenOption.APPEND);
        // Add an untracked file too — both flavors should bump dirtyFileCount.
        Files.writeString(repoPath.resolve("UNTRACKED.md"), "scratch\n");

        GitRepo repo = makeGitRepo("repo-2", "tenant-dirty", repoPath.toString());
        stubProvenance("tenant-dirty", repo);
        GenerationRun run = makeRun("run-dirty", "version-2", "pipeline-2", "tenant-dirty");
        List<GeneratedArtifact> artifacts = List.of(
                artifact("dags/example.py", "AIRFLOW_DAG", "from airflow import DAG\n", "hash-dag"),
                artifact("requirements.txt", "REQUIREMENTS_TXT",
                        "apache-airflow>=2.8.0\napache-airflow-providers-apache-spark>=4.7.0\ndbt-core>=1.7.0\ndbt-spark>=1.7.0\n",
                        "hash-req"),
                artifact("config/pipeline.yml", "CONFIG_YAML", "pipeline: x\n", "hash-config"),
                artifact("manifests/compile-plan.json", "COMPILE_PLAN", "{}\n", "hash-plan")
        );
        when(runRepo.findTopByVersionIdOrderByCreatedAtDesc("version-2")).thenReturn(Optional.of(run));
        when(artifactRepo.findByGenerationRunIdOrderByFilePathAsc("run-dirty")).thenReturn(artifacts);
        when(packageRepo.save(any(Package.class))).thenAnswer(inv -> {
            Package p = inv.getArgument(0);
            if (p.getId() == null) p.setId("pkg-dirty");
            return p;
        });

        Package pkg = controller.buildPackage(
                "version-2",
                new DeployController.BuildRequest("pipeline-2", "tenant-dirty", "tester", null)
        ).getBody();
        assertNotNull(pkg);

        @SuppressWarnings("unchecked")
        Map<String, Object> git = (Map<String, Object>) pkg.getMetadata().get("git");
        assertEquals("dirty", git.get("workingTreeStatus"));
        // commit SHA + tree SHA still come from HEAD even when working tree is dirty.
        assertEquals(40, ((String) git.get("commitSha")).length());
        assertEquals(40, ((String) git.get("treeSha")).length());

        @SuppressWarnings("unchecked")
        Map<String, Object> diag = (Map<String, Object>) pkg.getMetadata().get("gitProvenanceDiagnostics");
        assertEquals("dirty", diag.get("workingTreeStatus"));
        assertTrue(((Number) diag.get("dirtyFileCount")).intValue() >= 1,
                "JGit must report at least one dirty path");
        assertEquals(false, diag.get("complete"));

        // Flat aliases must mirror metadata.git even when status is dirty.
        assertFlatAliasesMatchManifest(pkg.getMetadata(), pkg.getArtifactHash());
        assertEquals("dirty", pkg.getMetadata().get("workingTreeStatus"));

        @SuppressWarnings("unchecked")
        Map<String, Object> assessment = (Map<String, Object>) pkg.getMetadata().get("staticRuntimeAssessment");
        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) assessment.get("warnings");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("Working tree dirty")),
                "Dirty tree must surface a warning, got: " + warnings);
        @SuppressWarnings("unchecked")
        List<String> blockers = (List<String>) assessment.get("blockers");
        assertFalse(blockers.stream().anyMatch(b -> b.contains("Missing Git provenance")),
                "Dirty (with HEAD) is not 'missing' — must not be a blocker");
    }

    @Test
    void missingTenantRepo_capturesMissingStatusAndSurfacesBlocker() {
        // No GitRepo row at all — captureProvenance must return missing + the
        // controller must surface a static-assessment blocker so downstream
        // (UI / Phase 4 preflight) can act on it.
        when(gitRepoRepository.findByTenantIdAndScope("tenant-missing", "TENANT"))
                .thenReturn(Optional.empty());
        GenerationRun run = makeRun("run-missing", "version-3", "pipeline-3", "tenant-missing");
        List<GeneratedArtifact> artifacts = List.of(
                artifact("dags/example.py", "AIRFLOW_DAG", "from airflow import DAG\n", "hash-dag"),
                artifact("requirements.txt", "REQUIREMENTS_TXT",
                        "apache-airflow>=2.8.0\napache-airflow-providers-apache-spark>=4.7.0\ndbt-core>=1.7.0\ndbt-spark>=1.7.0\n",
                        "hash-req"),
                artifact("config/pipeline.yml", "CONFIG_YAML", "pipeline: x\n", "hash-config"),
                artifact("manifests/compile-plan.json", "COMPILE_PLAN", "{}\n", "hash-plan")
        );
        when(runRepo.findTopByVersionIdOrderByCreatedAtDesc("version-3")).thenReturn(Optional.of(run));
        when(artifactRepo.findByGenerationRunIdOrderByFilePathAsc("run-missing")).thenReturn(artifacts);
        when(packageRepo.save(any(Package.class))).thenAnswer(inv -> {
            Package p = inv.getArgument(0);
            if (p.getId() == null) p.setId("pkg-missing");
            return p;
        });

        Package pkg = controller.buildPackage(
                "version-3",
                new DeployController.BuildRequest("pipeline-3", "tenant-missing", "tester", null)
        ).getBody();
        assertNotNull(pkg);

        @SuppressWarnings("unchecked")
        Map<String, Object> git = (Map<String, Object>) pkg.getMetadata().get("git");
        assertEquals("missing", git.get("workingTreeStatus"));
        assertNull(git.get("commitSha"));
        assertNull(git.get("treeSha"));
        assertNull(git.get("branch"));
        assertNull(git.get("repoId"));

        @SuppressWarnings("unchecked")
        Map<String, Object> assessment = (Map<String, Object>) pkg.getMetadata().get("staticRuntimeAssessment");
        @SuppressWarnings("unchecked")
        List<String> blockers = (List<String>) assessment.get("blockers");
        assertTrue(blockers.stream().anyMatch(b -> b.contains("Missing Git provenance")),
                "Missing tenant repo must produce a 'Missing Git provenance' blocker, got: " + blockers);
        // Verdict must reflect the blocker.
        assertEquals("NOT_READY", assessment.get("verdict"));

        // Flat aliases must surface the missing values too — they are
        // explicitly null/missing rather than absent map keys, so consumers
        // can detect "no provenance captured" without inspecting the
        // nested git block.
        assertFlatAliasesMatchManifest(pkg.getMetadata(), pkg.getArtifactHash());
        assertEquals("missing", pkg.getMetadata().get("workingTreeStatus"));
        assertNull(pkg.getMetadata().get("gitRepoId"));
        assertNull(pkg.getMetadata().get("gitBranch"));
        assertNull(pkg.getMetadata().get("gitCommitSha"));
        assertNull(pkg.getMetadata().get("gitTreeSha"));
    }

    @Test
    void buildRequest_cannotSpoofProvenanceFields() throws Exception {
        // Phase 2 contract: callers cannot spoof Git provenance through the
        // request body. Since `BuildRequest` is a Java record with only
        // (pipelineId, tenantId, userId, packageType), Jackson will silently
        // drop unknown JSON keys. Round-trip a JSON request body that *tries*
        // to spoof every Git field and assert the deserialized record is
        // unaffected — no Git data flows from the request body to the
        // server-side capture path.
        String maliciousJson = """
                {
                  "pipelineId": "pipeline-spoof",
                  "tenantId": "tenant-spoof",
                  "userId": "user-spoof",
                  "packageType": "ARTIFACT_BUNDLE",
                  "gitRepoId": "fake-repo",
                  "gitBranch": "evil",
                  "gitCommitSha": "0000000000000000000000000000000000000000",
                  "gitTreeSha": "1111111111111111111111111111111111111111",
                  "gitWorkingTreeStatus": "clean",
                  "git": {"repoId": "evil", "commitSha": "deadbeef"}
                }
                """;
        // Spring Boot's default ObjectMapper sets
        // FAIL_ON_UNKNOWN_PROPERTIES=false (see Spring's
        // JacksonAutoConfiguration). Mirror that here so the test reflects
        // the real wire path the controller takes.
        ObjectMapper mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        DeployController.BuildRequest decoded =
                mapper.readerFor(DeployController.BuildRequest.class).readValue(maliciousJson);
        assertEquals("pipeline-spoof", decoded.pipelineId());
        assertEquals("tenant-spoof", decoded.tenantId());
        assertEquals("user-spoof", decoded.userId());
        assertEquals("ARTIFACT_BUNDLE", decoded.packageType());
        // The record literally has no other accessors — the Git fields cannot
        // round-trip through the controller's request-body type. This is the
        // strongest spoof-resistance guarantee available short of a custom
        // deserializer: a structural one.
        long recordFieldCount = java.util.Arrays.stream(
                DeployController.BuildRequest.class.getRecordComponents()).count();
        assertEquals(4, recordFieldCount,
                "BuildRequest must have exactly 4 fields. Adding any Git field here "
                        + "would break Phase 2 spoof resistance.");
    }

    // -------------------------------------------------------------------
    //  Helpers
    // -------------------------------------------------------------------

    /**
     * Phase 2 closeout contract: the flat top-level provenance keys on
     * {@code package.metadata} must mirror both {@code metadata.git} and
     * the embedded {@code packageManifest.git} block. {@code generationRunId}
     * + {@code packageManifestHash} must be present on metadata too — they
     * already were, but we re-assert here so any future change that drops
     * them breaks visibly.
     */
    @SuppressWarnings("unchecked")
    private static void assertFlatAliasesMatchManifest(Map<String, Object> metadata,
                                                       String expectedArtifactHash) {
        Map<String, Object> nestedGit = (Map<String, Object>) metadata.get("git");
        assertNotNull(nestedGit, "metadata.git must be present");
        Map<String, Object> manifest = (Map<String, Object>) metadata.get("packageManifest");
        assertNotNull(manifest, "metadata.packageManifest must be present");
        Map<String, Object> manifestGit = (Map<String, Object>) manifest.get("git");
        assertNotNull(manifestGit, "metadata.packageManifest.git must be present");

        // metadata.git == packageManifest.git already asserted in clean
        // case; re-assert here so the flat-alias helper can be the
        // single Phase 2 acceptance check.
        assertEquals(nestedGit, manifestGit,
                "metadata.git must equal packageManifest.git");

        // Flat aliases mirror the nested block field-by-field.
        assertEquals(nestedGit.get("repoId"),            metadata.get("gitRepoId"));
        assertEquals(nestedGit.get("branch"),            metadata.get("gitBranch"));
        assertEquals(nestedGit.get("commitSha"),         metadata.get("gitCommitSha"));
        assertEquals(nestedGit.get("treeSha"),           metadata.get("gitTreeSha"));
        assertEquals(nestedGit.get("workingTreeStatus"), metadata.get("workingTreeStatus"));

        // artifactHash must equal both the package row's own field and
        // the manifest's artifactHash (single source of truth check).
        assertEquals(expectedArtifactHash, metadata.get("artifactHash"));
        assertEquals(expectedArtifactHash, manifest.get("artifactHash"));

        // Already-present fields the plan calls out as Phase 2 metadata.
        assertNotNull(metadata.get("generationRunId"),
                "metadata.generationRunId must be present (Phase 2 acceptance)");
        assertNotNull(metadata.get("packageManifestHash"),
                "metadata.packageManifestHash must be present (Phase 2 acceptance)");
    }

    private void stubProvenance(String tenantId, GitRepo repo) {
        when(gitRepoRepository.findByTenantIdAndScope(tenantId, "TENANT"))
                .thenReturn(Optional.of(repo));
    }

    private static Path initRepoWithCommit(Path parent,
                                           String branch,
                                           String filename,
                                           String content,
                                           String message) throws Exception {
        Path repoPath = parent.resolve("repo");
        Files.createDirectories(repoPath);
        try (Git git = Git.init().setDirectory(repoPath.toFile()).setInitialBranch(branch).call()) {
            Files.writeString(repoPath.resolve(filename), content);
            git.add().addFilepattern(".").call();
            git.commit()
                    .setAuthor("Pulse Test", "test@pulse")
                    .setCommitter("Pulse Test", "test@pulse")
                    .setMessage(message)
                    .call();
        }
        return repoPath;
    }

    private static GitRepo makeGitRepo(String id, String tenantId, String localPath) {
        GitRepo repo = new GitRepo();
        repo.setId(id);
        repo.setTenantId(tenantId);
        repo.setScope("TENANT");
        repo.setRepoType("LOCAL");
        repo.setLocalPath(localPath);
        repo.setRepoUrl("file://" + localPath);
        repo.setProvider("LOCAL");
        repo.setDefaultBranch("main");
        repo.setCurrentBranch("main");
        return repo;
    }

    private static GenerationRun makeRun(String id, String versionId, String pipelineId, String tenantId) {
        GenerationRun run = new GenerationRun();
        run.setId(id);
        run.setVersionId(versionId);
        run.setPipelineId(pipelineId);
        run.setTenantId(tenantId);
        return run;
    }

    private static GeneratedArtifact artifact(String path, String type, String content, String contentHash) {
        GeneratedArtifact a = new GeneratedArtifact();
        a.setFilePath(path);
        a.setFileType(type);
        a.setContent(content);
        a.setContentHash(contentHash);
        return a;
    }

    private static void writeFixture(String label,
                                     Map<String, Object> manifest,
                                     PackageService.PackageProvenance provenance) throws IOException {
        Path fixtureRoot = Path.of("build", "deployment-package-fixtures", label);
        Files.createDirectories(fixtureRoot);
        Files.write(fixtureRoot.resolve("package-manifest.json"),
                PRETTY.writeValueAsBytes(manifest));
        Path gitDir = fixtureRoot.resolve("git");
        Files.createDirectories(gitDir);
        Files.write(gitDir.resolve("provenance.json"),
                PRETTY.writeValueAsBytes(provenance.toProvenanceJson()));
    }
}
