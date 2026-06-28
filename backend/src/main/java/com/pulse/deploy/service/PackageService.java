package com.pulse.deploy.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.pulse.codegen.model.GeneratedArtifact;
import com.pulse.codegen.model.GenerationRun;
import com.pulse.deploy.projection.model.RuntimeProjection;
import com.pulse.deploy.projection.model.RuntimeProjectionDdlStatement;
import com.pulse.deploy.projection.model.SourcePackageManifestV2;
import com.pulse.deploy.projection.repository.RuntimeProjectionDdlStatementRepository;
import com.pulse.deploy.projection.repository.RuntimeProjectionRepository;
import com.pulse.git.model.GitRepo;
import com.pulse.git.repository.GitRepoRepository;
import com.pulse.git.service.LocalGitService;
import com.pulse.runtime.model.RuntimeAuthority;
import com.pulse.runtime.service.RuntimeAuthorityService;
import com.pulse.storage.contract.model.TableContract;
import com.pulse.storage.contract.service.TableContractService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 2 — package provenance.
 *
 * <p>Builds the deterministic provenance block stamped onto every
 * deployment {@code Package}: tenant Git repo identity, branch, commit
 * SHA, tree SHA, and a working-tree status snapshot. Also produces a
 * canonical-JSON manifest hash so downstream evidence (Phase 4) can
 * verify the package's content fingerprint without re-walking the
 * artifact list.
 *
 * <p>The service is a read-only adapter over {@link LocalGitService} +
 * {@link GitRepoRepository} for the tenant-scoped git repo. It does NOT
 * mutate the working tree, never reaches a remote, and degrades cleanly
 * when no tenant repo is registered yet (status="missing", surfaces a
 * blocker via {@link #buildStaticAssessmentSeed} so callers can append
 * to their static-runtime assessment).
 *
 * <p>Hard-blocking on missing provenance is deferred to Phase 4
 * preflight policy ({@code PACKAGE_PROVENANCE_PRESENT} blocker matrix
 * row); Phase 2 only guarantees provenance is captured / surfaced.
 */
@Service
public class PackageService {

    private static final Logger log = LoggerFactory.getLogger(PackageService.class);
    private static final String TENANT_SCOPE = "TENANT";
    private static final String SCHEMA_VERSION = "deployment-package-manifest.v1";
    private static final String GIT_PROVENANCE_SCHEMA_VERSION = "deployment-git-provenance.v1";

    private final GitRepoRepository gitRepoRepository;
    private final LocalGitService localGitService;
    private final RuntimeAuthorityService runtimeAuthorityService;
    private final TableContractService tableContractService;
    private final RuntimeProjectionRepository runtimeProjectionRepository;
    private final RuntimeProjectionDdlStatementRepository ddlStatementRepository;
    // Canonical JSON serializer with stable key ordering for hash determinism.
    private final ObjectMapper canonicalJsonMapper = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, false);

    public PackageService(GitRepoRepository gitRepoRepository,
                          LocalGitService localGitService,
                          RuntimeAuthorityService runtimeAuthorityService,
                          TableContractService tableContractService,
                          RuntimeProjectionRepository runtimeProjectionRepository,
                          RuntimeProjectionDdlStatementRepository ddlStatementRepository) {
        this.gitRepoRepository = gitRepoRepository;
        this.localGitService = localGitService;
        this.runtimeAuthorityService = runtimeAuthorityService;
        this.tableContractService = tableContractService;
        this.runtimeProjectionRepository = runtimeProjectionRepository;
        this.ddlStatementRepository = ddlStatementRepository;
    }

    /**
     * Captured provenance for a single package build.
     *
     * @param schemaVersion         constant {@code deployment-git-provenance.v1}
     * @param gitRepoId             id of the {@link GitRepo} row used, or null when missing
     * @param tenantId              tenant the package belongs to
     * @param branch                current branch at HEAD (null when missing/unborn)
     * @param commitSha             40-char SHA of HEAD (null when missing/unborn)
     * @param treeSha               40-char tree SHA of HEAD (null when missing/unborn)
     * @param workingTreeStatus     one of {@code clean|dirty|unborn|missing}
     * @param dirtyFileCount        number of paths reported by JGit status when dirty
     * @param capturedAt            UTC instant when provenance was captured
     */
    public record PackageProvenance(
            String schemaVersion,
            String gitRepoId,
            String tenantId,
            String branch,
            String commitSha,
            String treeSha,
            String workingTreeStatus,
            int dirtyFileCount,
            String capturedAt
    ) {
        /**
         * Convert to the canonical {@code git} block used inside
         * {@code package-manifest.json} (matches the plan's required shape:
         * {@code {repoId, branch, commitSha, treeSha, workingTreeStatus}}).
         */
        public Map<String, Object> toManifestBlock() {
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("repoId", gitRepoId);
            block.put("branch", branch);
            block.put("commitSha", commitSha);
            block.put("treeSha", treeSha);
            block.put("workingTreeStatus", workingTreeStatus);
            return block;
        }

        /**
         * Convert to the canonical {@code git/provenance.json} document body.
         * Includes the schema version + dirty-file count + capture timestamp
         * that aren't in the manifest's compact {@code git} block.
         */
        public Map<String, Object> toProvenanceJson() {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("schemaVersion", schemaVersion);
            doc.put("tenantId", tenantId);
            doc.put("repoId", gitRepoId);
            doc.put("branch", branch);
            doc.put("commitSha", commitSha);
            doc.put("treeSha", treeSha);
            doc.put("workingTreeStatus", workingTreeStatus);
            doc.put("dirtyFileCount", dirtyFileCount);
            doc.put("capturedAt", capturedAt);
            return doc;
        }

        /** True only for {@code clean} status with all SHAs populated. */
        public boolean isComplete() {
            return "clean".equals(workingTreeStatus)
                    && commitSha != null && !commitSha.isBlank()
                    && treeSha != null && !treeSha.isBlank()
                    && branch != null && !branch.isBlank()
                    && gitRepoId != null && !gitRepoId.isBlank();
        }
    }

    /**
     * Capture provenance for the given tenant. Uses the TENANT-scoped
     * {@link GitRepo} row and inspects its on-disk working tree via
     * {@link LocalGitService}. Returns a {@code missing} provenance when
     * no tenant repo is registered, the local path doesn't exist, or
     * JGit can't open the directory — the caller decides whether that's
     * a blocker (Phase 2 → static assessment blocker; Phase 4 → preflight
     * hard gate).
     *
     * @param tenantId  tenant whose package is being built
     * @param now       capture timestamp (injected for deterministic test fixtures)
     */
    public PackageProvenance captureProvenance(String tenantId, Instant now) {
        if (tenantId == null || tenantId.isBlank()) {
            return missing(null, null, "tenant_id_missing", now);
        }
        var repoOpt = gitRepoRepository.findByTenantIdAndScope(tenantId, TENANT_SCOPE);
        if (repoOpt.isEmpty()) {
            return missing(tenantId, null, "no_tenant_git_repo", now);
        }
        GitRepo repo = repoOpt.get();
        String localPath = repo.getLocalPath();
        if (localPath == null || localPath.isBlank()) {
            return missing(tenantId, repo.getId(), "no_local_path", now);
        }
        java.io.File dir = new java.io.File(localPath);
        if (!dir.exists() || !new java.io.File(dir, ".git").exists()) {
            return missing(tenantId, repo.getId(), "local_path_not_a_git_repo", now);
        }

        String branch;
        String commitSha;
        String treeSha;
        LocalGitService.WorkingTreeStatus status;
        try {
            branch = localGitService.getCurrentBranch(localPath);
            commitSha = localGitService.getHeadSha(localPath);
            treeSha = localGitService.getHeadTreeSha(localPath);
            status = localGitService.getWorkingTreeStatus(localPath);
        } catch (RuntimeException e) {
            log.warn("Provenance capture failed for tenant {}: {}", tenantId, e.getMessage());
            return missing(tenantId, repo.getId(), "git_read_failed", now);
        }

        return new PackageProvenance(
                GIT_PROVENANCE_SCHEMA_VERSION,
                repo.getId(),
                tenantId,
                branch,
                commitSha,
                treeSha,
                status.status(),
                status.dirtyFileCount(),
                now.toString()
        );
    }

    /**
     * Build the canonical {@code package-manifest.json} body for a package.
     * The hash returned via {@link #computeManifestHash(Map)} is computed
     * over the canonical JSON encoding of this same map.
     *
     * @param packageId       newly assigned package id
     * @param tenantId        tenant the package belongs to
     * @param pipelineId      pipeline the package was generated for
     * @param versionId       pipeline version the package was generated from
     * @param run             generation run feeding the package
     * @param createdBy       user id that triggered the build
     * @param artifacts       generated artifacts feeding the package
     * @param artifactHash    the existing per-artifact content hash (already SHA-256)
     * @param provenance      Phase 2 git provenance capture
     * @param createdAt       package creation instant (matches {@code Package.builtAt})
     */
    public Map<String, Object> buildManifest(String packageId,
                                             String tenantId,
                                             String pipelineId,
                                             String versionId,
                                             GenerationRun run,
                                             String createdBy,
                                             List<GeneratedArtifact> artifacts,
                                             String artifactHash,
                                             PackageProvenance provenance,
                                             Instant createdAt) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", SCHEMA_VERSION);
        manifest.put("packageId", packageId);
        manifest.put("tenantId", tenantId);
        manifest.put("pipelineId", pipelineId);
        manifest.put("versionId", versionId);
        manifest.put("generationRunId", run == null ? null : run.getId());
        manifest.put("git", provenance.toManifestBlock());
        manifest.put("createdBy", createdBy);
        manifest.put("createdAt", createdAt == null ? null : createdAt.toString());
        manifest.put("hashAlgorithm", "SHA-256");
        manifest.put("artifactHash", artifactHash);

        List<Map<String, Object>> files = new ArrayList<>(artifacts.size());
        for (GeneratedArtifact artifact : artifacts) {
            Map<String, Object> file = new LinkedHashMap<>();
            file.put("path", artifact.getFilePath());
            file.put("fileType", artifact.getFileType());
            file.put("sha256", artifact.getContentHash());
            file.put("sizeBytes", artifact.getContent() == null
                    ? 0
                    : artifact.getContent().getBytes(StandardCharsets.UTF_8).length);
            files.add(file);
        }
        manifest.put("files", files);

        // ARCH-004: stamp runtime authority so preflight and adapters can prove
        // which persona/matrix version approved this package.
        RuntimeAuthority auth = runtimeAuthorityService.getAuthority();
        Map<String, Object> runtimeAuthorityBlock = new LinkedHashMap<>();
        runtimeAuthorityBlock.put("activePersona", auth.activePersona().name());
        runtimeAuthorityBlock.put("legalRuntimeMatrixVersion", auth.legalRuntimeMatrixVersion());
        runtimeAuthorityBlock.put("allowedTargetTypes", List.copyOf(auth.allowedTargetTypes()));
        runtimeAuthorityBlock.put("secretAuthority", auth.secretAuthority().name());
        manifest.put("runtimeAuthority", runtimeAuthorityBlock);

        manifest.put("capabilityProfile", SourcePackageManifestV2.normalizeCapabilityProfile(Map.of()));
        manifest.put("callbackPolicyDiagnostics", buildCallbackPolicyDiagnostics(artifacts));

        // ARCH-006: Add table contract refs for sourcePackageManifest.v2
        List<TableContract> activeContracts = tableContractService.findActiveContracts(versionId);
        List<Map<String, Object>> tableContractRefs = activeContracts.stream()
                .map(c -> {
                    Map<String, Object> ref = new LinkedHashMap<>();
                    ref.put("contractId", c.getId());
                    ref.put("contractVersion", c.getContractVersion());
                    ref.put("layer", c.getLayer());
                    ref.put("tableName", c.getTableName());
                    ref.put("schemaName", c.getSchemaName());
                    ref.put("catalogKind", c.getCatalogKind());
                    ref.put("tableFormat", c.getTableFormat());
                    ref.put("relativeStoragePath", c.getRelativeStoragePath());
                    ref.put("producingInstanceId", c.getProducingInstanceId());
                    return ref;
                })
                .toList();
        manifest.put("tableContractRefs", tableContractRefs);

        // ARCH-006: Entrypoint catalog stub (populated by codegen)
        Map<String, Object> entrypointCatalog = new LinkedHashMap<>();
        entrypointCatalog.put("catalogVersion", "entrypointCatalog.v1");
        List<Map<String, Object>> dagEntrypoints = new ArrayList<>();
        for (GeneratedArtifact a : artifacts) {
            if (a.getFilePath() != null && a.getFilePath().endsWith("_dag.py")) {
                Map<String, Object> dagEntry = new LinkedHashMap<>();
                dagEntry.put("dagFilePath", a.getFilePath());
                dagEntry.put("sha256", a.getContentHash());
                dagEntrypoints.add(dagEntry);
            }
        }
        entrypointCatalog.put("dagEntrypoints", dagEntrypoints);
        entrypointCatalog.put("brokerInvocations", collectBrokerInvocations(artifacts));
        entrypointCatalog.put("timeStateBindings", collectTimeStateBindings(artifacts));
        manifest.put("entrypointCatalog", entrypointCatalog);

        // PKT-0023: Add runtime projection refs for sourcePackageManifest.v2
        List<RuntimeProjection> projections = runtimeProjectionRepository
                .findByPackageIdOrderByProjectedAtDesc(packageId);
        List<Map<String, Object>> runtimeProjectionRefs = projections.stream()
                .filter(p -> "active".equals(p.getStatus()))
                .map(p -> {
                    Map<String, Object> ref = new LinkedHashMap<>();
                    ref.put("projectionId", p.getId());
                    ref.put("targetId", p.getTargetId());
                    ref.put("environment", p.getEnvironment());
                    ref.put("runtimePersona", p.getRuntimePersona());
                    ref.put("projectionHash", p.getProjectionHash());
                    ref.put("projectedAt", p.getProjectedAt() == null ? null : p.getProjectedAt().toString());
                    return ref;
                })
                .toList();
        manifest.put("runtimeProjectionRefs", runtimeProjectionRefs);

        // PKT-0023: Add DDL plan refs from active projections
        List<Map<String, Object>> ddlPlanRefs = new ArrayList<>();
        for (RuntimeProjection p : projections) {
            if (!"active".equals(p.getStatus())) continue;
            List<RuntimeProjectionDdlStatement> stmts = ddlStatementRepository
                    .findByProjectionIdOrderByPhaseAsc(p.getId());
            for (RuntimeProjectionDdlStatement stmt : stmts) {
                Map<String, Object> ref = new LinkedHashMap<>();
                ref.put("statementId", stmt.getStatementId());
                ref.put("projectionId", p.getId());
                ref.put("executor", stmt.getExecutor());
                ref.put("dialect", stmt.getDialect());
                ref.put("tableContractId", stmt.getTableContractId());
                ref.put("idempotencyMode", stmt.getIdempotencyMode());
                ref.put("sha256", stmt.getSha256());
                ddlPlanRefs.add(ref);
            }
        }
        manifest.put("ddlPlanRefs", ddlPlanRefs);

        // ARCH-006: Manifest version upgrade marker
        manifest.put("sourcePackageManifestVersion", "sourcePackageManifest.v2");

        return manifest;
    }

    /**
     * Deterministic SHA-256 over the canonical JSON encoding of the
     * manifest. Two manifests with identical key/value content (and
     * identical insertion order) produce identical hashes.
     */
    public String computeManifestHash(Map<String, Object> manifest) {
        try {
            byte[] json = canonicalJsonMapper.writeValueAsBytes(manifest);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (JsonProcessingException | java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("manifest hash failed", e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> collectBrokerInvocations(List<GeneratedArtifact> artifacts) {
        List<Map<String, Object>> invocations = new ArrayList<>();
        for (GeneratedArtifact artifact : artifacts) {
            Map<String, Object> metadata = artifact.getMetadata();
            Object raw = metadata == null ? null : metadata.get("brokerInvocations");
            if (!(raw instanceof List<?> list)) {
                continue;
            }
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) map);
                    copy.put("artifactPath", artifact.getFilePath());
                    invocations.add(copy);
                }
            }
        }
        return invocations.stream()
                .collect(java.util.stream.Collectors.toMap(
                        m -> String.valueOf(m.get("instanceId")),
                        m -> m,
                        (left, right) -> left,
                        LinkedHashMap::new))
                .values()
                .stream()
                .toList();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> collectTimeStateBindings(List<GeneratedArtifact> artifacts) {
        List<Map<String, Object>> bindings = new ArrayList<>();
        for (GeneratedArtifact artifact : artifacts) {
            Map<String, Object> metadata = artifact.getMetadata();
            Object raw = metadata == null ? null : metadata.get("timeStateBindings");
            if (!(raw instanceof List<?> list)) {
                continue;
            }
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) map);
                    copy.put("artifactPath", artifact.getFilePath());
                    bindings.add(copy);
                }
            }
        }
        return bindings.stream()
                .collect(java.util.stream.Collectors.toMap(
                        m -> String.valueOf(m.get("instanceId")),
                        m -> m,
                        (left, right) -> left,
                        LinkedHashMap::new))
                .values()
                .stream()
                .toList();
    }

    /**
     * Phase 2 surfaces missing/dirty provenance as a static-assessment
     * blocker so callers (currently {@code DeployController}) can append
     * to their existing {@code staticRuntimeAssessment} block without
     * hard-blocking package creation. Phase 4 will turn these into
     * preflight blockers under {@code PACKAGE_PROVENANCE_PRESENT}.
     *
     * @return list of human-readable blocker strings (empty when complete)
     */
    public List<String> buildStaticAssessmentSeed(PackageProvenance provenance) {
        List<String> blockers = new ArrayList<>();
        if (provenance == null) {
            blockers.add("Missing Git provenance: provenance capture returned null");
            return blockers;
        }
        switch (provenance.workingTreeStatus()) {
            case "missing" ->
                blockers.add("Missing Git provenance: no tenant Git repo registered or local path inaccessible "
                        + "(repoId=" + provenance.gitRepoId() + ")");
            case "unborn" ->
                blockers.add("Missing Git provenance: tenant repo has no commits yet "
                        + "(repoId=" + provenance.gitRepoId() + ")");
            case "dirty" ->
                blockers.add("Working tree dirty at package build "
                        + "(repoId=" + provenance.gitRepoId()
                        + ", dirtyFiles=" + provenance.dirtyFileCount()
                        + "). Higher environments require a clean tree.");
            case "clean" -> { /* no blocker */ }
            default -> blockers.add("Unknown working tree status: " + provenance.workingTreeStatus());
        }
        return blockers;
    }

    /**
     * Compact diagnostic record stamped onto package metadata so future
     * preflight phases can identify which capture path was taken without
     * having to re-derive it.
     */
    public Map<String, Object> diagnostics(PackageProvenance provenance) {
        Map<String, Object> diag = new LinkedHashMap<>();
        diag.put("workingTreeStatus", provenance.workingTreeStatus());
        diag.put("dirtyFileCount", provenance.dirtyFileCount());
        diag.put("complete", provenance.isComplete());
        return diag;
    }

    public Map<String, Object> buildCallbackPolicyDiagnostics(List<GeneratedArtifact> artifacts) {
        List<String> violations = new ArrayList<>();
        List<String> scannedArtifacts = new ArrayList<>();
        for (GeneratedArtifact artifact : artifacts) {
            String path = artifact.getFilePath();
            String content = artifact.getContent();
            if (path == null || content == null || content.isBlank()) {
                continue;
            }
            scannedArtifacts.add(path);
            if (content.contains("PULSE_AIRFLOW_CALLBACK_URL")) {
                violations.add(path + " contains PULSE_AIRFLOW_CALLBACK_URL despite disabled callback policy defaults");
            }
            if (content.contains("/api/v1/callbacks/airflow")) {
                violations.add(path + " contains generic /api/v1/callbacks/airflow callback endpoint wiring");
            }
            if (content.contains("http://localhost:8080")) {
                violations.add(path + " contains a forbidden localhost callback fallback");
            }
            if (content.contains("on_success_callback") || content.contains("on_failure_callback")) {
                violations.add(path + " contains unconditional Airflow callback hook wiring");
            }
            if (content.contains("PULSE_API_URL") && content.toLowerCase(java.util.Locale.ROOT).contains("callback")) {
                violations.add(path + " reuses PULSE_API_URL for callback-related runtime behavior");
            }
        }
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("controlPlaneDependency",
                SourcePackageManifestV2.CONTROL_PLANE_DEPENDENCY_NONE);
        diagnostics.put("airflowCallbackPolicy",
                SourcePackageManifestV2.AIRFLOW_CALLBACK_POLICY_DISABLED);
        diagnostics.put("promotedArtifactReady", violations.isEmpty());
        diagnostics.put("violations", violations);
        diagnostics.put("scannedArtifacts", scannedArtifacts);
        return diagnostics;
    }

    private PackageProvenance missing(String tenantId, String repoId, String reason, Instant now) {
        log.debug("Provenance capture missing for tenant={}: {}", tenantId, reason);
        return new PackageProvenance(
                GIT_PROVENANCE_SCHEMA_VERSION,
                repoId,
                tenantId,
                null,
                null,
                null,
                "missing",
                0,
                now == null ? null : now.toString()
        );
    }
}
