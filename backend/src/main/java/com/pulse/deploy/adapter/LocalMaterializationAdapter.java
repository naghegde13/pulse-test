package com.pulse.deploy.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.pulse.codegen.model.GeneratedArtifact;
import com.pulse.codegen.repository.GeneratedArtifactRepository;
import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.deploy.evidence.DeploymentEvidenceService;
import com.pulse.deploy.model.DeploymentRun;
import com.pulse.deploy.model.Package;
import com.pulse.deploy.repository.DeploymentEventRepository;
import com.pulse.deploy.repository.DeploymentEvidenceRepository;
import com.pulse.deploy.repository.DeploymentRunRepository;
import com.pulse.deploy.repository.PackageRepository;
import com.pulse.deploy.run.DeploymentRunState;
import com.pulse.deploy.run.DeploymentRunStateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Phase 5 — deterministic local product materialization.
 *
 * <p>Takes a {@link DeploymentRun} that has reached
 * {@link DeploymentRunState#PREFLIGHT_PASSED} and materializes the
 * underlying {@link Package} into
 * {@code backend/build/deployment-materialization/<runId>/} (or another
 * configured root). Writes:
 *
 * <ul>
 *   <li>{@code package/<normalized-paths>} — generated artifact content
 *       copied byte-for-byte from {@link GeneratedArtifact#getContent()};</li>
 *   <li>{@code materialization-manifest.json} — deterministic
 *       {@code deployment-materialization-manifest.v1} envelope listing
 *       every materialized file (sorted by normalized POSIX path), each
 *       file's SHA-256, the total package content hash, and excluded
 *       paths;</li>
 *   <li>{@code evidence-index.json} — index of every evidence row
 *       associated with the run.</li>
 * </ul>
 *
 * <p>Determinism rules (matching the plan):
 * <ul>
 *   <li>Files are sorted by normalized POSIX path before serialization.</li>
 *   <li>The {@code packageContentSha256} is computed over the canonical
 *       JSON of the {@code (path, sha256)} tuples — two identical
 *       packages produce byte-equal hashes regardless of generation
 *       order.</li>
 *   <li>JSON output uses stable key ordering (LinkedHashMap insertion
 *       order) so a re-render produces the same bytes.</li>
 * </ul>
 *
 * <p>Forbidden paths are matched against {@link #FORBIDDEN_PATTERNS}
 * and {@link #FORBIDDEN_GLOBS} and excluded from the materialization
 * package; the manifest records each exclusion with a reason so a
 * future preflight blocker (Phase 7+) can hard-gate them.
 *
 * <p>No Docker, Airflow, Spark, MinIO, cloud, or harness runtime
 * services are used.
 */
@Service
public class LocalMaterializationAdapter {

    private static final Logger log = LoggerFactory.getLogger(LocalMaterializationAdapter.class);

    public static final String SCHEMA_VERSION_MANIFEST = "deployment-materialization-manifest.v1";
    public static final String SCHEMA_VERSION_EVIDENCE_INDEX = "deployment-evidence-index.v1";
    public static final String EVIDENCE_TYPE_MATERIALIZATION_MANIFEST = "MATERIALIZATION_MANIFEST";

    /** Substring patterns rejected from a materialized path. */
    private static final Set<String> FORBIDDEN_SEGMENT_PREFIXES = Set.of(
            ".git/",
            "target/",
            "dbt_packages/",
            "node_modules/",
            "build/");
    /** Path globs rejected (suffix matches; case-insensitive). */
    private static final Set<String> FORBIDDEN_GLOBS = Set.of(
            ".env",
            ".env.local",
            ".secret");
    /** Compiled rejection patterns for explicit clarity in failure messages. */
    private static final List<Pattern> FORBIDDEN_PATTERNS = List.of(
            Pattern.compile("(^|/)\\.\\.(/|$)"),     // .. anywhere
            Pattern.compile("^/"),                          // absolute paths
            Pattern.compile("^[a-zA-Z]:[\\\\/]")            // windows drive letters
    );

    private final PackageRepository packageRepo;
    private final GeneratedArtifactRepository artifactRepo;
    private final DeploymentRunRepository deploymentRunRepository;
    private final DeploymentRunStateService runStateService;
    private final DeploymentEvidenceService evidenceService;
    private final DeploymentEvidenceRepository evidenceRepository;
    private final DeploymentEventRepository eventRepository;
    private final Path defaultOutputRoot;
    private final ObjectMapper canonicalJson = new ObjectMapper();
    private final ObjectMapper prettyJson = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public LocalMaterializationAdapter(PackageRepository packageRepo,
                                       GeneratedArtifactRepository artifactRepo,
                                       DeploymentRunRepository deploymentRunRepository,
                                       DeploymentRunStateService runStateService,
                                       DeploymentEvidenceService evidenceService,
                                       DeploymentEvidenceRepository evidenceRepository,
                                       DeploymentEventRepository eventRepository,
                                       @Value("${pulse.deploy.materialization-root:build/deployment-materialization}")
                                               String defaultOutputRoot) {
        this.packageRepo = packageRepo;
        this.artifactRepo = artifactRepo;
        this.deploymentRunRepository = deploymentRunRepository;
        this.runStateService = runStateService;
        this.evidenceService = evidenceService;
        this.evidenceRepository = evidenceRepository;
        this.eventRepository = eventRepository;
        this.defaultOutputRoot = Path.of(defaultOutputRoot);
    }

    /**
     * Materialize the package backing {@code deploymentRunId} into the
     * default output root. The run must be in
     * {@link DeploymentRunState#PREFLIGHT_PASSED}; the adapter advances
     * it to {@code MATERIALIZING} for the duration of the work and
     * lands at {@code MATERIALIZED} on success or {@code FAILED} on
     * error.
     */
    public MaterializationResult materialize(String deploymentRunId) {
        return materialize(deploymentRunId, null);
    }

    /**
     * Materialize with an explicit output root (used by tests for
     * deterministic temp dirs).
     */
    public MaterializationResult materialize(String deploymentRunId, Path outputRootOverride) {
        DeploymentRun run = deploymentRunRepository.findById(deploymentRunId)
                .orElseThrow(() -> new ResourceNotFoundException("DeploymentRun", deploymentRunId));
        DeploymentRunState fromState = DeploymentRunState.parse(run.getStatus());
        if (fromState != DeploymentRunState.PREFLIGHT_PASSED) {
            throw new IllegalStateException(
                    "Materialization requires PREFLIGHT_PASSED, got " + run.getStatus()
                            + " for run " + deploymentRunId);
        }
        // Resolve the package by walking back through the deployment row.
        // We don't need the parent Deployment for materialization — package
        // id is derivable from any artifact tagged with the run's
        // deployment, but Phase 4 stores it on the run's metadata when
        // available; otherwise the test path passes packageId via the
        // metadata field. Defensive: re-fetch by traversing artifacts.
        Map<String, Object> runMeta = run.getMetadata();
        Object packageIdObj = runMeta == null ? null : runMeta.get("packageId");
        if (!(packageIdObj instanceof String packageIdStr) || packageIdStr.isBlank()) {
            throw new IllegalStateException(
                    "DeploymentRun " + deploymentRunId
                            + " has no metadata.packageId; Phase 5 requires it to materialize.");
        }
        Package pkg = packageRepo.findById(packageIdStr)
                .orElseThrow(() -> new ResourceNotFoundException("Package", packageIdStr));

        Path outputRoot = (outputRootOverride != null ? outputRootOverride : defaultOutputRoot)
                .resolve(deploymentRunId);
        runStateService.transition(deploymentRunId, DeploymentRunState.MATERIALIZING, null);
        try {
            MaterializationResult result = doMaterialize(run, pkg, outputRoot);
            runStateService.transition(deploymentRunId, DeploymentRunState.MATERIALIZED, null);
            return result;
        } catch (ForbiddenArtifactPathException forbidden) {
            // Phase 5 closeout — forbidden artifact paths fail the run
            // with a stable, parseable reason ("forbidden_artifact_paths:
            // <reason>=<path>,..."), NOT a silent exclusion. Downstream
            // can grep failure_reason for "forbidden_artifact_paths" to
            // route the run to the right operator.
            log.warn("Materialization rejected for run {}: {}",
                    deploymentRunId, forbidden.stableFailureReason());
            runStateService.transition(deploymentRunId, DeploymentRunState.FAILED,
                    forbidden.stableFailureReason());
            throw forbidden;
        } catch (RuntimeException | IOException e) {
            log.warn("Materialization failed for run {}: {}", deploymentRunId, e.getMessage());
            runStateService.transition(deploymentRunId, DeploymentRunState.FAILED,
                    "materialization_failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            throw e instanceof RuntimeException re ? re : new IllegalStateException(e);
        }
    }

    /** Output of a successful materialization. */
    public record MaterializationResult(
            String runId,
            String packageId,
            Path outputRoot,
            Path manifestPath,
            Path evidenceIndexPath,
            String packageContentSha256,
            String manifestSha256,
            int fileCount,
            List<String> sortedPaths,
            Map<String, Object> manifest
    ) {}

    private MaterializationResult doMaterialize(DeploymentRun run, Package pkg, Path outputRoot) throws IOException {
        Files.createDirectories(outputRoot);
        Path packageDir = outputRoot.resolve("package");
        Files.createDirectories(packageDir);

        if (isWorkspacePackage(pkg)) {
            return materializeWorkspacePackage(run, pkg, outputRoot, packageDir);
        }

        // Pull the raw artifact set behind this package via the
        // generationRunId stored on package metadata. Phase 4 wired
        // metadata.generationRunId; older rows fall back to the legacy
        // metadata.packageManifest.generationRunId field.
        String generationRunId = resolveGenerationRunId(pkg);
        List<GeneratedArtifact> artifacts = generationRunId == null
                ? List.of()
                : artifactRepo.findByGenerationRunIdOrderByFilePathAsc(generationRunId);

        // Phase 5 closeout — first pass: detect every forbidden artifact
        // path and bail BEFORE writing anything. The plan says the
        // package builder must FAIL on forbidden file matches (no
        // silent-exclude). The adapter's catch block converts the
        // exception below into a stable run failure_reason.
        List<ForbiddenArtifactPathException.Violation> violations = new ArrayList<>();
        for (GeneratedArtifact artifact : artifacts) {
            String raw = artifact.getFilePath();
            String reason = forbiddenRaw(raw);
            String normalized = normalizePath(raw);
            if (reason == null) {
                reason = forbidden(normalized);
            }
            if (reason == null) {
                // Final path-escape guard: the normalized path must
                // resolve back inside packageDir even after OS-level
                // resolution (catches e.g. symlink-style escapes).
                Path target = packageDir.resolve(normalized).normalize();
                if (!target.startsWith(packageDir)) {
                    reason = "path_escape";
                }
            }
            if (reason != null) {
                violations.add(new ForbiddenArtifactPathException.Violation(
                        normalized.isBlank() ? String.valueOf(raw) : normalized, reason));
            }
        }
        if (!violations.isEmpty()) {
            // Sort for deterministic failure messages / reasons.
            violations.sort(Comparator.comparing(ForbiddenArtifactPathException.Violation::path));
            throw new ForbiddenArtifactPathException(violations);
        }

        List<Map<String, Object>> fileRows = new ArrayList<>(artifacts.size());
        long totalSize = 0L;
        for (GeneratedArtifact artifact : artifacts) {
            String normalized = normalizePath(artifact.getFilePath());
            byte[] bytes = (artifact.getContent() == null ? "" : artifact.getContent())
                    .getBytes(StandardCharsets.UTF_8);
            // Write under package/<normalized> — keep the source layout
            // so downstream zipping (Phase 5+) can reuse it.
            Path target = packageDir.resolve(normalized).normalize();
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);

            String fileSha = sha256Hex(bytes);
            totalSize += bytes.length;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("path", "package/" + normalized);
            row.put("sourceArtifactPath", artifact.getFilePath());
            row.put("fileType", artifact.getFileType());
            row.put("sizeBytes", bytes.length);
            row.put("sha256", fileSha);
            fileRows.add(row);
        }

        // Sort file rows by normalized path for stable output. The
        // artifact repository already orders by file path, but sort
        // again so a future change to the repo ordering can't break
        // determinism here.
        fileRows.sort(Comparator.comparing(r -> (String) r.get("path")));

        // Compute package content hash over the canonical JSON of the
        // (path, sha256) tuples.
        String packageContentSha = computeContentHash(fileRows);

        // Build the manifest in stable insertion order.
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", SCHEMA_VERSION_MANIFEST);
        manifest.put("deploymentRunId", run.getId());
        manifest.put("deploymentId", run.getDeploymentId());
        manifest.put("packageId", pkg.getId());
        manifest.put("tenantId", pkg.getTenantId());
        manifest.put("pipelineId", pkg.getPipelineId());
        manifest.put("versionId", pkg.getVersionId());
        manifest.put("generationRunId", generationRunId);
        manifest.put("outputRoot", outputRoot.toString());
        manifest.put("fileCount", fileRows.size());
        manifest.put("totalSizeBytes", totalSize);
        manifest.put("hashAlgorithm", "SHA-256");
        manifest.put("packageContentSha256", packageContentSha);
        manifest.put("files", fileRows);
        manifest.put("createdAt", run.getStartedAt() != null
                ? run.getStartedAt().toString()
                : Instant.now().toString());
        // Phase 5 closeout — write canonical pretty JSON ONCE and hash
        // those exact bytes. Previously the on-disk file was pretty-
        // printed but the evidence sha256 was computed from a separate
        // compact serialization, so the two never matched. Now an
        // independent reader can SHA-256 the on-disk file and get
        // exactly the value stored in DeploymentEvidence.sha256.
        Path manifestPath = outputRoot.resolve("materialization-manifest.json");
        byte[] manifestBytes = prettyJson.writeValueAsBytes(manifest);
        Files.write(manifestPath, manifestBytes);
        String manifestSha = sha256Hex(manifestBytes);

        // Phase 4 evidence row + Phase 5 evidence index.
        evidenceService.recordMaterializationManifest(
                run.getDeploymentId(), run.getId(), pkg.getId(),
                manifest, manifestSha, run.getCorrelationId());

        Map<String, Object> evidenceIndex = buildEvidenceIndex(run, pkg);
        Path evidenceIndexPath = outputRoot.resolve("evidence-index.json");
        Files.write(evidenceIndexPath, prettyJson.writeValueAsBytes(evidenceIndex));

        List<String> sortedPaths = fileRows.stream()
                .map(r -> (String) r.get("path"))
                .toList();

        return new MaterializationResult(
                run.getId(),
                pkg.getId(),
                outputRoot,
                manifestPath,
                evidenceIndexPath,
                packageContentSha,
                manifestSha,
                fileRows.size(),
                sortedPaths,
                manifest);
    }

    private String resolveGenerationRunId(Package pkg) {
        Map<String, Object> meta = pkg.getMetadata();
        if (meta == null) return null;
        Object direct = meta.get("generationRunId");
        if (direct instanceof String s && !s.isBlank()) return s;
        Object manifest = meta.get("packageManifest");
        if (manifest instanceof Map<?, ?> manifestMap) {
            Object grid = manifestMap.get("generationRunId");
            if (grid instanceof String s && !s.isBlank()) return s;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private MaterializationResult materializeWorkspacePackage(DeploymentRun run,
                                                              Package pkg,
                                                              Path outputRoot,
                                                              Path packageDir) throws IOException {
        String artifactUri = pkg.getPackageArtifactUri();
        if (artifactUri == null || artifactUri.isBlank()) {
            throw new IllegalStateException("Workspace package is missing packageArtifactUri");
        }
        Path artifactPath = Path.of(URI.create(artifactUri));
        byte[] artifactBytes = Files.readAllBytes(artifactPath);
        if (pkg.getPackageArtifactSha256() != null && !pkg.getPackageArtifactSha256().isBlank()) {
            String actualSha = sha256Hex(artifactBytes);
            if (!pkg.getPackageArtifactSha256().equals(actualSha)) {
                throw new IllegalStateException("Workspace package artifact SHA mismatch");
            }
        }
        Map<String, Object> bundle = canonicalJson.readValue(artifactBytes, new TypeReference<>() {});
        Object contentsValue = bundle.get("contents");
        if (!(contentsValue instanceof Map<?, ?> contents)) {
            throw new IllegalStateException("Workspace package artifact is missing contents map");
        }

        List<ForbiddenArtifactPathException.Violation> violations = new ArrayList<>();
        for (Object rawPath : contents.keySet()) {
            String normalized = normalizePath(String.valueOf(rawPath));
            String reason = forbiddenRaw(String.valueOf(rawPath));
            if (reason == null) reason = forbidden(normalized);
            if (reason == null) {
                Path target = packageDir.resolve(normalized).normalize();
                if (!target.startsWith(packageDir)) reason = "path_escape";
            }
            if (reason != null) {
                violations.add(new ForbiddenArtifactPathException.Violation(normalized, reason));
            }
        }
        if (!violations.isEmpty()) {
            violations.sort(Comparator.comparing(ForbiddenArtifactPathException.Violation::path));
            throw new ForbiddenArtifactPathException(violations);
        }

        List<Map<String, Object>> fileRows = new ArrayList<>(contents.size());
        long totalSize = 0L;
        List<String> sortedPaths = contents.keySet().stream()
                .map(String::valueOf)
                .sorted()
                .toList();
        for (String rawPath : sortedPaths) {
            String normalized = normalizePath(rawPath);
            byte[] bytes = String.valueOf(contents.get(rawPath)).getBytes(StandardCharsets.UTF_8);
            Path target = packageDir.resolve(normalized).normalize();
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);

            String fileSha = sha256Hex(bytes);
            totalSize += bytes.length;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("path", "package/" + normalized);
            row.put("sourceArtifactPath", rawPath);
            row.put("fileType", "WORKSPACE_FILE");
            row.put("sizeBytes", bytes.length);
            row.put("sha256", fileSha);
            fileRows.add(row);
        }

        fileRows.sort(Comparator.comparing(r -> (String) r.get("path")));
        String packageContentSha = computeContentHash(fileRows);

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", SCHEMA_VERSION_MANIFEST);
        manifest.put("deploymentRunId", run.getId());
        manifest.put("deploymentId", run.getDeploymentId());
        manifest.put("packageId", pkg.getId());
        manifest.put("tenantId", pkg.getTenantId());
        manifest.put("pipelineId", pkg.getPipelineId());
        manifest.put("versionId", pkg.getVersionId());
        manifest.put("generationRunId", null);
        manifest.put("outputRoot", outputRoot.toString());
        manifest.put("fileCount", fileRows.size());
        manifest.put("totalSizeBytes", totalSize);
        manifest.put("hashAlgorithm", "SHA-256");
        manifest.put("packageContentSha256", packageContentSha);
        manifest.put("files", fileRows);
        manifest.put("createdAt", run.getStartedAt() != null
                ? run.getStartedAt().toString()
                : Instant.now().toString());

        Path manifestPath = outputRoot.resolve("materialization-manifest.json");
        byte[] manifestBytes = prettyJson.writeValueAsBytes(manifest);
        Files.write(manifestPath, manifestBytes);
        String manifestSha = sha256Hex(manifestBytes);

        evidenceService.recordMaterializationManifest(
                run.getDeploymentId(), run.getId(), pkg.getId(),
                manifest, manifestSha, run.getCorrelationId());

        Map<String, Object> evidenceIndex = buildEvidenceIndex(run, pkg);
        Path evidenceIndexPath = outputRoot.resolve("evidence-index.json");
        Files.write(evidenceIndexPath, prettyJson.writeValueAsBytes(evidenceIndex));

        return new MaterializationResult(
                run.getId(),
                pkg.getId(),
                outputRoot,
                manifestPath,
                evidenceIndexPath,
                packageContentSha,
                manifestSha,
                fileRows.size(),
                fileRows.stream().map(r -> (String) r.get("path")).toList(),
                manifest);
    }

    private static boolean isWorkspacePackage(Package pkg) {
        return "WORKSPACE_SNAPSHOT".equals(pkg.getSourceKind())
                || "GIT_COMMIT".equals(pkg.getSourceKind());
    }

    /** Normalize a raw artifact path: strip leading slashes, collapse separators, lowercase the path-comparison form. */
    private static String normalizePath(String raw) {
        if (raw == null) return "";
        String trimmed = raw.replace("\\", "/").trim();
        while (trimmed.startsWith("./")) {
            trimmed = trimmed.substring(2);
        }
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        return trimmed;
    }

    /**
     * First-pass rejection that runs against the raw artifact path
     * BEFORE normalization. Catches absolute paths and traversal
     * markers that would be silently stripped by {@link #normalizePath}.
     */
    private static String forbiddenRaw(String rawPath) {
        if (rawPath == null) return "blank_path";
        String trimmed = rawPath.replace("\\", "/").trim();
        if (trimmed.isEmpty()) return "blank_path";
        if (trimmed.startsWith("/")) return "forbidden_absolute_path";
        if (trimmed.matches("^[a-zA-Z]:/.*")) return "forbidden_drive_letter";
        if (trimmed.contains("../")) return "forbidden_parent_traversal";
        return null;
    }

    /** Returns the rejection reason if the path is forbidden; null when allowed. */
    private static String forbidden(String normalizedPath) {
        if (normalizedPath == null || normalizedPath.isBlank()) {
            return "blank_path";
        }
        for (Pattern p : FORBIDDEN_PATTERNS) {
            if (p.matcher(normalizedPath).find()) {
                return "forbidden_pattern:" + p.pattern();
            }
        }
        for (String prefix : FORBIDDEN_SEGMENT_PREFIXES) {
            if (normalizedPath.equals(prefix.substring(0, prefix.length() - 1))
                    || normalizedPath.startsWith(prefix)
                    || normalizedPath.contains("/" + prefix)) {
                return "forbidden_segment:" + prefix;
            }
        }
        String lowerPath = normalizedPath.toLowerCase(Locale.ROOT);
        for (String glob : FORBIDDEN_GLOBS) {
            if (lowerPath.endsWith(glob)) {
                return "forbidden_glob:" + glob;
            }
        }
        return null;
    }

    private String computeContentHash(List<Map<String, Object>> fileRows) {
        // Hash over (path, sha256) only — file metadata noise (size,
        // sourceArtifactPath, fileType) is excluded so re-renderers that
        // tweak metadata without changing content keep the same hash.
        List<Map<String, Object>> reduced = new ArrayList<>(fileRows.size());
        for (Map<String, Object> row : fileRows) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("path", row.get("path"));
            r.put("sha256", row.get("sha256"));
            reduced.add(r);
        }
        try {
            return sha256Hex(canonicalJson.writeValueAsBytes(reduced));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("manifest content hash failed", e);
        }
    }

    private Map<String, Object> buildEvidenceIndex(DeploymentRun run, Package pkg) {
        Map<String, Object> index = new LinkedHashMap<>();
        index.put("schemaVersion", SCHEMA_VERSION_EVIDENCE_INDEX);
        index.put("deploymentRunId", run.getId());
        index.put("deploymentId", run.getDeploymentId());
        index.put("packageId", pkg.getId());
        List<Map<String, Object>> rows = new ArrayList<>();
        evidenceRepository.findByDeploymentRunIdOrderByCreatedAtAsc(run.getId()).forEach(ev -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("artifactId", ev.getArtifactId());
            r.put("type", ev.getType());
            r.put("path", ev.getPath());
            r.put("sha256", ev.getSha256());
            r.put("producedBy", ev.getProducedBy());
            r.put("createdAt", ev.getCreatedAt() == null ? null : ev.getCreatedAt().toString());
            rows.add(r);
        });
        rows.sort(Comparator.comparing(r -> (String) r.get("artifactId")));
        index.put("evidence", rows);
        index.put("eventCount", eventRepository.findByDeploymentRunIdOrderByCreatedAtAsc(run.getId()).size());
        index.put("createdAt", Instant.now().toString());
        return index;
    }

    /** SHA-256 over an arbitrary byte array, lowercase hex, 64 chars. */
    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
