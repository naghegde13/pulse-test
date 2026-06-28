package com.pulse.e2e.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Harness-owned planner for promoting a generated runtime package into a Cloud
 * Composer-compatible DAG bucket layout without pushing any production deploy
 * behavior into the main application.
 */
public class GcpComposerRuntimeBridge {

    private final ObjectMapper objectMapper;

    public GcpComposerRuntimeBridge(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public BridgePlan plan(BridgeRequest request) {
        if (request.runtimeRepoRoot() == null || request.compileNamespace() == null || request.compileNamespace().isBlank()) {
            throw new IllegalArgumentException("runtimeRepoRoot and compileNamespace are required");
        }
        if (request.dagId() == null || request.dagId().isBlank()) {
            throw new IllegalArgumentException("dagId is required");
        }

        Path namespacedRoot = request.runtimeRepoRoot().resolve(request.compileNamespace()).normalize();
        Path dagFile = namespacedRoot.resolve("dags").resolve(request.dagFileName()).normalize();
        Path dbtProjectRoot = request.runtimeRepoRoot().resolve("dbt_project").normalize();
        String dagGcsPrefix = normalizeGcsPrefix(request.dagGcsPrefix());
        String namespacedGcsPrefix = dagGcsPrefix + "/" + request.compileNamespace();

        List<String> preflightCommands = List.of(
                join(
                        "gcloud", "composer", "environments", "describe",
                        request.composerEnvironment(),
                        "--location=" + request.composerLocation(),
                        "--project=" + request.projectId(),
                        "--format=json"
                ),
                join(
                        "gcloud", "composer", "environments", "run",
                        request.composerEnvironment(),
                        "--location=" + request.composerLocation(),
                        "--project=" + request.projectId(),
                        "dags", "list"
                ),
                join(
                        "gcloud", "composer", "environments", "run",
                        request.composerEnvironment(),
                        "--location=" + request.composerLocation(),
                        "--project=" + request.projectId(),
                        "list-import-errors"
                )
        );

        List<String> uploadCommands = Files.exists(dbtProjectRoot)
                ? List.of(
                        join("gcloud", "storage", "cp", "--recursive", namespacedRoot.toString(), namespacedGcsPrefix),
                        join("gcloud", "storage", "cp", "--recursive", dbtProjectRoot.toString(), dagGcsPrefix + "/dbt_project")
                )
                : List.of(join("gcloud", "storage", "cp", "--recursive", namespacedRoot.toString(), namespacedGcsPrefix));

        String triggerCommand = join(
                "gcloud", "composer", "environments", "run",
                request.composerEnvironment(),
                "--location=" + request.composerLocation(),
                "--project=" + request.projectId(),
                "dags", "trigger", "--",
                request.dagId(),
                "--run-id=" + request.dagRunId(),
                "--logical-date=" + request.logicalDate(),
                "--conf=" + shellSingleQuote(json(Map.of(
                        "scenario_id", request.scenarioId(),
                        "generation_run_id", request.generationRunId()
                )))
        );

        List<String> statusCommands = List.of(
                join(
                        "gcloud", "composer", "environments", "run",
                        request.composerEnvironment(),
                        "--location=" + request.composerLocation(),
                        "--project=" + request.projectId(),
                        "dags", "state", "--",
                        request.dagId(),
                        request.logicalDate()
                ),
                join(
                        "gcloud", "composer", "environments", "run",
                        request.composerEnvironment(),
                        "--location=" + request.composerLocation(),
                        "--project=" + request.projectId(),
                        "tasks", "states-for-dag-run", "--",
                        request.dagId(),
                        request.logicalDate()
                )
        );

        Map<String, Object> notes = new LinkedHashMap<>();
        notes.put("dagFile", dagFile.toString());
        notes.put("composerDagGcsPrefix", dagGcsPrefix);
        notes.put("namespacedDagGcsPrefix", namespacedGcsPrefix);
        notes.put("dbtProjectPresent", Files.exists(dbtProjectRoot));
        notes.put("generatedAt", Instant.now().toString());
        notes.put("assumptions", List.of(
                "Composer DAG uploads go through the environment's DAG GCS prefix.",
                "The generated namespace tree is copied as-is below the DAG prefix so the existing relative artifact layout survives.",
                "Shared dbt_project content remains repo-root scoped and uploads separately."
        ));

        return new BridgePlan(
                request.scenarioId(),
                request.generationRunId(),
                request.projectId(),
                request.composerLocation(),
                request.composerEnvironment(),
                request.compileNamespace(),
                request.dagId(),
                request.dagRunId(),
                request.logicalDate(),
                namespacedRoot,
                dagFile,
                dagGcsPrefix,
                namespacedGcsPrefix,
                preflightCommands,
                uploadCommands,
                triggerCommand,
                statusCommands,
                notes
        );
    }

    public Path writeEvidence(BridgeRequest request, Path evidenceRoot) throws IOException {
        Files.createDirectories(evidenceRoot);
        BridgePlan plan = plan(request);
        Path packet = evidenceRoot.resolve("gcp-runtime-bridge.json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(packet.toFile(), plan);
        return packet;
    }

    private String json(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize Composer trigger payload", e);
        }
    }

    private static String normalizeGcsPrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("dagGcsPrefix is required");
        }
        String trimmed = prefix.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (!trimmed.startsWith("gs://")) {
            throw new IllegalArgumentException("dagGcsPrefix must start with gs://");
        }
        return trimmed;
    }

    private static String join(String... parts) {
        return String.join(" ", parts);
    }

    private static String shellSingleQuote(String raw) {
        return "'" + raw.replace("'", "'\"'\"'") + "'";
    }

    public record BridgeRequest(
            String scenarioId,
            String generationRunId,
            String projectId,
            String composerLocation,
            String composerEnvironment,
            String dagGcsPrefix,
            String compileNamespace,
            String dagId,
            String dagFileName,
            String dagRunId,
            String logicalDate,
            Path runtimeRepoRoot
    ) {
    }

    public record BridgePlan(
            String scenarioId,
            String generationRunId,
            String projectId,
            String composerLocation,
            String composerEnvironment,
            String compileNamespace,
            String dagId,
            String dagRunId,
            String logicalDate,
            Path localNamespacedRoot,
            Path localDagFile,
            String dagGcsPrefix,
            String namespacedDagGcsPrefix,
            List<String> preflightCommands,
            List<String> uploadCommands,
            String triggerCommand,
            List<String> statusCommands,
            Map<String, Object> notes
    ) {
    }
}
