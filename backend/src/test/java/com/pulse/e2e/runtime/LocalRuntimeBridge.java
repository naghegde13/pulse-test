package com.pulse.e2e.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.codegen.model.GeneratedArtifact;
import com.pulse.codegen.model.GenerationRun;
import com.pulse.codegen.repository.GeneratedArtifactRepository;
import com.pulse.e2e.contract.EvidenceContracts.EvidenceArtifact;
import com.pulse.e2e.contract.EvidenceContracts.EvidenceBundle;
import com.pulse.git.model.GitRepo;
import com.pulse.git.repository.GitRepoRepository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Harness-owned bridge that materializes generated artifacts into a runtime-visible
 * directory and persists a machine-readable evidence bundle.
 */
public class LocalRuntimeBridge {

    private static final String TENANT_SCOPE = "TENANT";

    private final GeneratedArtifactRepository generatedArtifactRepository;
    private final GitRepoRepository gitRepoRepository;
    private final ObjectMapper objectMapper;

    public LocalRuntimeBridge(GeneratedArtifactRepository generatedArtifactRepository,
                              GitRepoRepository gitRepoRepository,
                              ObjectMapper objectMapper) {
        this.generatedArtifactRepository = generatedArtifactRepository;
        this.gitRepoRepository = gitRepoRepository;
        this.objectMapper = objectMapper;
    }

    public RuntimeBridgeResult materialize(BridgeRequest request) throws IOException {
        List<GeneratedArtifact> artifacts = generatedArtifactRepository
                .findByGenerationRunIdOrderByFilePathAsc(request.generationRunId());
        if (artifacts.isEmpty()) {
            throw new IllegalArgumentException("No generated artifacts for run " + request.generationRunId());
        }

        String compileNamespace = request.compileNamespace() != null && !request.compileNamespace().isBlank()
                ? request.compileNamespace()
                : inferCompileNamespace(artifacts);
        Path runtimeRoot = request.runtimeRepoRoot().resolve(compileNamespace).normalize();
        Files.createDirectories(runtimeRoot);

        MaterializationSource source = selectSource(request, compileNamespace);
        if (source.materializedRoot() != null) {
            copyTree(source.materializedRoot(), runtimeRoot);
        } else {
            exportArtifacts(artifacts, request.runtimeRepoRoot());
        }

        Path evidenceRoot = request.evidenceRoot();
        Files.createDirectories(evidenceRoot);

        List<EvidenceArtifact> evidenceArtifacts = new ArrayList<>();
        Path bridgePacket = evidenceRoot.resolve("runtime-bridge.json");
        Map<String, Object> bridgePayload = new LinkedHashMap<>();
        bridgePayload.put("scenarioId", request.scenarioId());
        bridgePayload.put("generationRunId", request.generationRunId());
        bridgePayload.put("compileNamespace", compileNamespace);
        bridgePayload.put("materializedPath", runtimeRoot.toString());
        bridgePayload.put("sourceStrategy", source.strategy());
        bridgePayload.put("artifactCount", artifacts.size());
        bridgePayload.put("materializedFiles", listRelativeFiles(runtimeRoot));
        writeJson(bridgePacket, bridgePayload);
        evidenceArtifacts.add(evidenceArtifact("runtime-bridge", "RUNTIME_BRIDGE_PACKET", bridgePacket, Map.of(
                "sourceStrategy", source.strategy(),
                "compileNamespace", compileNamespace
        )));

        Path evidenceIndex = evidenceRoot.resolve("evidence-index.json");
        Map<String, Object> indexPayload = new LinkedHashMap<>();
        indexPayload.put("scenarioId", request.scenarioId());
        indexPayload.put("generationRunId", request.generationRunId());
        indexPayload.put("artifacts", evidenceArtifacts.stream().map(artifact -> Map.of(
                "artifactId", artifact.artifactId(),
                "type", artifact.type(),
                "path", artifact.path().toString(),
                "sha256", artifact.sha256(),
                "producingAdapter", artifact.producingAdapter(),
                "retentionPolicy", artifact.retentionPolicy(),
                "metadata", artifact.metadata()
        )).toList());
        writeJson(evidenceIndex, indexPayload);
        evidenceArtifacts.add(evidenceArtifact("evidence-index", "EVIDENCE_INDEX", evidenceIndex, Map.of(
                "entries", evidenceArtifacts.size()
        )));

        EvidenceBundle bundle = new EvidenceBundle(
                request.scenarioId(),
                request.generationRunId(),
                evidenceRoot,
                List.copyOf(evidenceArtifacts),
                Map.of(
                        "compileNamespace", compileNamespace,
                        "sourceStrategy", source.strategy(),
                        "materializedFileCount", listRelativeFiles(runtimeRoot).size()
                )
        );
        return new RuntimeBridgeResult(runtimeRoot, bundle, source.strategy());
    }

    private MaterializationSource selectSource(BridgeRequest request, String compileNamespace) {
        Optional<GitRepo> repoOpt = gitRepoRepository.findByTenantIdAndScope(request.tenantId(), TENANT_SCOPE);
        if (repoOpt.isPresent() && repoOpt.get().getLocalPath() != null) {
            Path root = Path.of(repoOpt.get().getLocalPath()).resolve(compileNamespace).normalize();
            if (Files.exists(root)) {
                return new MaterializationSource("tenant_git_repo", root);
            }
        }
        return new MaterializationSource("generated_artifact_export", null);
    }

    private void exportArtifacts(List<GeneratedArtifact> artifacts, Path runtimeRepoRoot) throws IOException {
        for (GeneratedArtifact artifact : artifacts) {
            Path target = runtimeRepoRoot.resolve(artifact.getFilePath()).normalize();
            if (!target.startsWith(runtimeRepoRoot)) {
                throw new IllegalStateException("Artifact path escapes runtime root: " + artifact.getFilePath());
            }
            Files.createDirectories(target.getParent());
            Files.writeString(target, artifact.getContent() == null ? "" : artifact.getContent(), StandardCharsets.UTF_8);
        }
    }

    private void copyTree(Path source, Path target) {
        try {
            Files.walk(source).forEach(path -> {
                try {
                    Path relative = source.relativize(path);
                    Path destination = target.resolve(relative.toString()).normalize();
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(destination);
                    } else {
                        Files.createDirectories(destination.getParent());
                        Files.copy(path, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<String> listRelativeFiles(Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .map(root::relativize)
                    .map(Path::toString)
                    .sorted()
                    .toList();
        }
    }

    private String inferCompileNamespace(List<GeneratedArtifact> artifacts) {
        return artifacts.stream()
                .map(GeneratedArtifact::getFilePath)
                .filter(path -> path.contains("/"))
                .map(path -> {
                    String[] segments = path.split("/");
                    if (segments.length < 4) {
                        return path.substring(0, path.lastIndexOf('/'));
                    }
                    return String.join("/", segments[0], segments[1], segments[2], segments[3]);
                })
                .min(Comparator.naturalOrder())
                .orElseThrow(() -> new IllegalStateException("Unable to infer compile namespace"));
    }

    private void writeJson(Path target, Map<String, Object> payload) throws IOException {
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), payload);
    }

    private EvidenceArtifact evidenceArtifact(String artifactId, String type, Path path, Map<String, Object> metadata) {
        return new EvidenceArtifact(
                artifactId,
                type,
                path,
                sha256(path),
                "local-runtime-bridge",
                "test-run",
                metadata
        );
    }

    private String sha256(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Files.readAllBytes(path));
            byte[] hash = digest.digest();
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash " + path, e);
        }
    }

    public record BridgeRequest(
            String scenarioId,
            String generationRunId,
            String tenantId,
            String compileNamespace,
            Path runtimeRepoRoot,
            Path evidenceRoot
    ) {
        public BridgeRequest {
            if (runtimeRepoRoot == null || evidenceRoot == null) {
                throw new IllegalArgumentException("runtimeRepoRoot and evidenceRoot are required");
            }
        }

        public static BridgeRequest fromRun(String scenarioId,
                                            GenerationRun run,
                                            Path runtimeRepoRoot,
                                            Path evidenceRoot) {
            String compileNamespace = run.getMetadata() == null
                    ? null
                    : String.valueOf(run.getMetadata().get("compile_namespace"));
            return new BridgeRequest(
                    scenarioId,
                    run.getId(),
                    run.getTenantId(),
                    compileNamespace,
                    runtimeRepoRoot,
                    evidenceRoot
            );
        }
    }

    public record RuntimeBridgeResult(
            Path materializedRoot,
            EvidenceBundle evidenceBundle,
            String sourceStrategy
    ) {
    }

    private record MaterializationSource(String strategy, Path materializedRoot) {
    }
}
