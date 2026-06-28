package com.pulse.e2e.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Harness-owned planner for staging generated PySpark artifacts to GCS and
 * constructing exact Dataproc submit/probe commands for the guided GCP runtime
 * proof.
 */
public class GcpDataprocExecutionPlanner {

    private final ObjectMapper objectMapper;

    public GcpDataprocExecutionPlanner(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ExecutionPlan plan(ExecutionRequest request) {
        if (request.runtimeRepoRoot() == null || request.compileNamespace() == null || request.compileNamespace().isBlank()) {
            throw new IllegalArgumentException("runtimeRepoRoot and compileNamespace are required");
        }
        if (request.mainPythonRelativePath() == null || request.mainPythonRelativePath().isBlank()) {
            throw new IllegalArgumentException("mainPythonRelativePath is required");
        }
        if (request.targetMode() == null) {
            throw new IllegalArgumentException("targetMode is required");
        }

        Path namespacedRoot = request.runtimeRepoRoot().resolve(request.compileNamespace()).normalize();
        Path localMainPy = namespacedRoot.resolve(request.mainPythonRelativePath()).normalize();
        String stagingBucket = normalizeBucket(request.stagingBucket());
        String outputPrefix = normalizeGcsUri(request.runtimeOutputPrefix());
        String stageRoot = stagingBucket + "/pulse-runtime/" + request.scenarioId() + "/" + request.generationRunId();
        String stagedNamespaceRoot = stageRoot + "/" + request.compileNamespace();
        String stagedMainPy = stagedNamespaceRoot + "/" + request.mainPythonRelativePath();

        List<String> preflightCommands = List.of(
                join("gcloud", "storage", "ls", stagingBucket),
                join("gcloud", "storage", "ls", outputPrefix)
        );

        List<String> stageCommands = new ArrayList<>();
        stageCommands.add(join("gcloud", "storage", "cp", "--recursive", namespacedRoot.toString(), stagedNamespaceRoot));
        stageCommands.add(join("gcloud", "storage", "ls", "--recursive", stagedNamespaceRoot));

        List<String> pyFilesArgs = request.additionalPyFilesRelativePaths().stream()
                .map(path -> stagedNamespaceRoot + "/" + path)
                .toList();
        String pyFilesFlag = pyFilesArgs.isEmpty() ? null : "--py-files=" + String.join(",", pyFilesArgs);
        String labelsFlag = "--labels=scenario=" + slugLabel(request.scenarioId()) + ",generation_run=" + slugLabel(request.generationRunId());

        String submitCommand;
        List<String> statusCommands;
        if (request.targetMode() == TargetMode.EXISTING_CLUSTER) {
            if (request.clusterName() == null || request.clusterName().isBlank()) {
                throw new IllegalArgumentException("clusterName is required for EXISTING_CLUSTER mode");
            }
            List<String> submitParts = new ArrayList<>(List.of(
                    "JOB_ID=\"$(gcloud", "dataproc", "jobs", "submit", "pyspark",
                    stagedMainPy,
                    "--cluster=" + request.clusterName(),
                    "--region=" + request.region(),
                    "--project=" + request.projectId(),
                    "--bucket=" + bucketName(stagingBucket),
                    labelsFlag,
                    "--async",
                    "--format=value(reference.jobId)"
            ));
            if (pyFilesFlag != null) {
                submitParts.add(pyFilesFlag);
            }
            submitParts.add("--");
            submitParts.addAll(request.jobArgs());
            submitParts.add(")\"");
            submitParts.add("&&");
            submitParts.add("echo");
            submitParts.add("$JOB_ID");
            submitCommand = String.join(" ", submitParts);
            statusCommands = List.of(
                    join("gcloud", "dataproc", "jobs", "wait", "$JOB_ID", "--region=" + request.region(), "--project=" + request.projectId()),
                    join("gcloud", "dataproc", "jobs", "describe", "$JOB_ID", "--region=" + request.region(), "--project=" + request.projectId(), "--format=json")
            );
        } else {
            if (request.batchId() == null || request.batchId().isBlank()) {
                throw new IllegalArgumentException("batchId is required for SERVERLESS_BATCH mode");
            }
            if (request.serviceAccount() == null || request.serviceAccount().isBlank()) {
                throw new IllegalArgumentException("serviceAccount is required for SERVERLESS_BATCH mode");
            }
            List<String> submitParts = new ArrayList<>(List.of(
                    "gcloud", "dataproc", "batches", "submit", "pyspark",
                    stagedMainPy,
                    "--batch=" + request.batchId(),
                    "--region=" + request.region(),
                    "--project=" + request.projectId(),
                    "--deps-bucket=" + stagingBucket,
                    "--staging-bucket=" + stagingBucket,
                    "--service-account=" + request.serviceAccount(),
                    labelsFlag
            ));
            if (pyFilesFlag != null) {
                submitParts.add(pyFilesFlag);
            }
            submitParts.add("--");
            submitParts.addAll(request.jobArgs());
            submitCommand = String.join(" ", submitParts);
            statusCommands = List.of(
                    join("gcloud", "dataproc", "batches", "describe", request.batchId(), "--region=" + request.region(), "--project=" + request.projectId(), "--format=json")
            );
        }

        List<String> outputProbeCommands = List.of(
                join("gcloud", "storage", "ls", "--recursive", outputPrefix)
        );

        Map<String, Object> notes = new LinkedHashMap<>();
        notes.put("generatedAt", Instant.now().toString());
        notes.put("localMainPython", localMainPy.toString());
        notes.put("stagedMainPython", stagedMainPy);
        notes.put("stagedNamespaceRoot", stagedNamespaceRoot);
        notes.put("targetMode", request.targetMode().name());
        notes.put("additionalPyFiles", pyFilesArgs);
        notes.put("assumptions", List.of(
                "The generated PySpark driver can run from a staged GCS URI.",
                "Any non-driver Python helpers needed at submit time are passed through --py-files.",
                "Output validation is performed with Cloud Storage object probes before any deeper data-oracle comparison."
        ));

        return new ExecutionPlan(
                request.scenarioId(),
                request.generationRunId(),
                request.projectId(),
                request.region(),
                request.targetMode(),
                request.clusterName(),
                request.batchId(),
                request.serviceAccount(),
                request.compileNamespace(),
                namespacedRoot,
                localMainPy,
                stageRoot,
                stagedNamespaceRoot,
                stagedMainPy,
                preflightCommands,
                List.copyOf(stageCommands),
                submitCommand,
                statusCommands,
                outputProbeCommands,
                notes
        );
    }

    public Path writeEvidence(ExecutionRequest request, Path evidenceRoot) throws IOException {
        Files.createDirectories(evidenceRoot);
        Path packet = evidenceRoot.resolve("gcp-dataproc-execution-plan.json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(packet.toFile(), plan(request));
        return packet;
    }

    private static String normalizeBucket(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("stagingBucket is required");
        }
        String trimmed = raw.trim();
        if (!trimmed.startsWith("gs://")) {
            trimmed = "gs://" + trimmed;
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String normalizeGcsUri(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("runtimeOutputPrefix is required");
        }
        String trimmed = raw.trim();
        if (!trimmed.startsWith("gs://")) {
            throw new IllegalArgumentException("runtimeOutputPrefix must start with gs://");
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String bucketName(String bucketUri) {
        return bucketUri.replaceFirst("^gs://", "");
    }

    private static String slugLabel(String value) {
        return value.toLowerCase().replaceAll("[^a-z0-9_-]", "-");
    }

    private static String join(String... parts) {
        return String.join(" ", parts);
    }

    public enum TargetMode {
        EXISTING_CLUSTER,
        SERVERLESS_BATCH
    }

    public record ExecutionRequest(
            String scenarioId,
            String generationRunId,
            String projectId,
            String region,
            TargetMode targetMode,
            String clusterName,
            String batchId,
            String serviceAccount,
            String stagingBucket,
            String runtimeOutputPrefix,
            String compileNamespace,
            String mainPythonRelativePath,
            List<String> additionalPyFilesRelativePaths,
            List<String> jobArgs,
            Path runtimeRepoRoot
    ) {
        public ExecutionRequest {
            additionalPyFilesRelativePaths = additionalPyFilesRelativePaths == null ? List.of() : List.copyOf(additionalPyFilesRelativePaths);
            jobArgs = jobArgs == null ? List.of() : List.copyOf(jobArgs);
        }
    }

    public record ExecutionPlan(
            String scenarioId,
            String generationRunId,
            String projectId,
            String region,
            TargetMode targetMode,
            String clusterName,
            String batchId,
            String serviceAccount,
            String compileNamespace,
            Path localNamespacedRoot,
            Path localMainPython,
            String stageRoot,
            String stagedNamespaceRoot,
            String stagedMainPython,
            List<String> preflightCommands,
            List<String> stageCommands,
            String submitCommand,
            List<String> statusCommands,
            List<String> outputProbeCommands,
            Map<String, Object> notes
    ) {
    }
}
