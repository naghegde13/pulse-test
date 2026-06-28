package com.pulse.e2e.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.e2e.contract.EvidenceContracts.EvidenceArtifact;
import com.pulse.e2e.contract.EvidenceContracts.EvidenceBundle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts generated test-harness artifacts into a locally runnable layout for
 * the docker-compose Airflow/Spark substrate without mutating production
 * code-generation logic.
 */
public class LocalRuntimeArtifactRenderer {

    private final ObjectMapper objectMapper;

    public LocalRuntimeArtifactRenderer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public RenderResult render(RenderRequest request) throws IOException {
        List<String> patchedFiles = new ArrayList<>();

        patchedFiles.addAll(rewritePythonJobs(request.runtimeRoot().resolve("jobs").resolve("ingestion"),
                request.outputBaseUri(), null, "_ingest.py"));
        patchedFiles.addAll(rewritePythonJobs(request.runtimeRoot().resolve("jobs").resolve("sink"),
                request.outputBaseUri(), request.upstreamTaskBySinkSlug(), "_sink.py"));
        patchedFiles.addAll(rewriteGxScripts(request.runtimeRoot().resolve("gx").resolve("checkpoints"),
                request.outputBaseUri()));
        patchedFiles.addAll(rewriteDagCommands(request.runtimeRoot().resolve("dags"),
                request.compileNamespace(),
                request.containerDbtProjectPath()));

        Files.createDirectories(request.evidenceRoot());
        Path renderPacket = request.evidenceRoot().resolve("runtime-render.json");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scenarioId", request.scenarioId());
        payload.put("compileNamespace", request.compileNamespace());
        payload.put("outputBaseUri", request.outputBaseUri());
        payload.put("containerDbtProjectPath", request.containerDbtProjectPath());
        payload.put("patchedFiles", patchedFiles);
        payload.put("upstreamTaskBySinkSlug", request.upstreamTaskBySinkSlug());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(renderPacket.toFile(), payload);

        EvidenceArtifact artifact = new EvidenceArtifact(
                "runtime-render",
                "RUNTIME_RENDER_PACKET",
                renderPacket,
                sha256(renderPacket),
                "local-runtime-artifact-renderer",
                "test-run",
                Map.of("patchedFileCount", patchedFiles.size())
        );
        EvidenceBundle bundle = new EvidenceBundle(
                request.scenarioId(),
                request.compileNamespace(),
                request.evidenceRoot(),
                List.of(artifact),
                Map.of(
                        "patchedFileCount", patchedFiles.size(),
                        "outputBaseUri", request.outputBaseUri()
                )
        );
        return new RenderResult(bundle, List.copyOf(patchedFiles));
    }

    private List<String> rewritePythonJobs(Path directory,
                                           String outputBaseUri,
                                           Map<String, String> upstreamTaskBySinkSlug,
                                           String suffix) throws IOException {
        if (!Files.exists(directory)) {
            return List.of();
        }
        List<String> patched = new ArrayList<>();
        try (var stream = Files.list(directory)) {
            for (Path file : stream.filter(path -> path.getFileName().toString().endsWith(suffix)).toList()) {
                String content = Files.readString(file, StandardCharsets.UTF_8)
                        .replace("${OUTPUT_BASE}", outputBaseUri);
                if (upstreamTaskBySinkSlug != null && content.contains("${UPSTREAM_TASK}")) {
                    String slug = file.getFileName().toString().replace(suffix, "");
                    String upstreamTask = upstreamTaskBySinkSlug.get(slug);
                    if (upstreamTask == null || upstreamTask.isBlank()) {
                        throw new IllegalArgumentException("Missing upstream task mapping for sink slug: " + slug);
                    }
                    content = content.replace("${UPSTREAM_TASK}", upstreamTask);
                }
                Files.writeString(file, content, StandardCharsets.UTF_8);
                patched.add(file.toString());
            }
        }
        return patched;
    }

    private List<String> rewriteGxScripts(Path directory, String outputBaseUri) throws IOException {
        if (!Files.exists(directory)) {
            return List.of();
        }
        List<String> patched = new ArrayList<>();
        try (var stream = Files.list(directory)) {
            for (Path file : stream.filter(path -> path.getFileName().toString().endsWith(".py")).toList()) {
                String content = Files.readString(file, StandardCharsets.UTF_8)
                        .replace("os.environ.get('OUTPUT_BASE', '/tmp')", "'" + escapePython(outputBaseUri) + "'");
                Files.writeString(file, content, StandardCharsets.UTF_8);
                patched.add(file.toString());
            }
        }
        return patched;
    }

    private List<String> rewriteDagCommands(Path directory,
                                            String compileNamespace,
                                            String containerDbtProjectPath) throws IOException {
        if (!Files.exists(directory)) {
            return List.of();
        }
        List<String> patched = new ArrayList<>();
        String containerRepoRoot = containerDbtProjectPath.replaceAll("/dbt_project/?$", "");
        String containerNamespaceRoot = containerRepoRoot + "/" + compileNamespace;
        String dbtExecutable = "/home/airflow/.local/bin/dbt";
        String replacement = "cd " + containerDbtProjectPath
                + " && " + dbtExecutable + " deps --project-dir " + containerDbtProjectPath
                + " --profiles-dir " + containerDbtProjectPath
                + " && " + dbtExecutable + " build --project-dir " + containerDbtProjectPath
                + " --profiles-dir " + containerDbtProjectPath + " ";
        try (var stream = Files.list(directory)) {
            for (Path file : stream.filter(path -> path.getFileName().toString().endsWith("_dag.py")).toList()) {
                String content = Files.readString(file, StandardCharsets.UTF_8)
                        .replace("from runtime.pulse_secret_resolver import cleanup_runtime_secret_files, resolve_runtime_secret_env\n",
                                "import sys\nfrom pathlib import Path\nsys.path.append(str(Path(__file__).resolve().parents[1]))\nfrom runtime.pulse_secret_resolver import cleanup_runtime_secret_files, resolve_runtime_secret_env\n")
                        .replace("cd /opt/dbt && dbt build ", replacement)
                        .replace("application='jobs/", "application='" + containerNamespaceRoot + "/jobs/")
                        .replace("'application': 'jobs/", "'application': '" + containerNamespaceRoot + "/jobs/")
                        .replace("['python', 'gx/checkpoints/", "['python', '" + containerNamespaceRoot + "/gx/checkpoints/")
                        .replace("    'retries': 3,\n", "    'retries': 0,\n")
                        .replaceAll("(?m)^    schedule=.*,$", "    schedule=None,")
                        .replaceAll("(?m)^    catchup=.*,$", "    catchup=False,");
                Files.writeString(file, content, StandardCharsets.UTF_8);
                patched.add(file.toString());
            }
        }
        return patched;
    }

    private String escapePython(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
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

    public record RenderRequest(
            String scenarioId,
            String compileNamespace,
            Path runtimeRoot,
            Path evidenceRoot,
            String outputBaseUri,
            String containerDbtProjectPath,
            Map<String, String> upstreamTaskBySinkSlug
    ) {
        public RenderRequest {
            if (runtimeRoot == null || evidenceRoot == null) {
                throw new IllegalArgumentException("runtimeRoot and evidenceRoot are required");
            }
            upstreamTaskBySinkSlug = upstreamTaskBySinkSlug == null ? Map.of() : Map.copyOf(upstreamTaskBySinkSlug);
        }
    }

    public record RenderResult(
            EvidenceBundle evidenceBundle,
            List<String> patchedFiles
    ) {
    }
}
