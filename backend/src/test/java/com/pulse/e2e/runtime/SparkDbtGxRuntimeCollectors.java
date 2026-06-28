package com.pulse.e2e.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.codegen.model.GeneratedArtifact;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Harness-owned collectors for Spark, dbt, and GX runtime evidence.
 *
 * <p>Phase 3 lanes can feed this collector task/command level runtime evidence and the
 * generated artifact set for a scenario. The collector distinguishes:
 * <ul>
 *   <li>NOT_APPLICABLE - the scenario never emitted that artifact/runtime surface</li>
 *   <li>MISSING - the surface should exist, but no runtime proof was captured</li>
 *   <li>FAIL - runtime proof exists and indicates a failed execution</li>
 *   <li>PASS - required runtime proof exists and succeeded</li>
 * </ul>
 */
public class SparkDbtGxRuntimeCollectors {

    private static final Set<String> SPARK_ARTIFACT_TYPES = Set.of("PYSPARK_JOB");
    private static final Set<String> DBT_ARTIFACT_TYPES = Set.of("DBT_MODEL", "DBT_SNAPSHOT", "DBT_SOURCE", "DBT_SELECTOR");
    private static final Set<String> GX_ARTIFACT_TYPES = Set.of("GX_CHECKPOINT");

    private final ObjectMapper objectMapper;

    public SparkDbtGxRuntimeCollectors(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public CollectorBundle collect(CollectorRequest request) throws IOException {
        Files.createDirectories(request.evidenceRoot());

        CollectorReport spark = collectSpark(request);
        CollectorReport dbt = collectDbt(request);
        CollectorReport gx = collectGx(request);

        return new CollectorBundle(
                spark,
                dbt,
                gx,
                List.of(writeReport("spark-runtime-collection.json", spark, request.evidenceRoot()),
                        writeReport("dbt-runtime-collection.json", dbt, request.evidenceRoot()),
                        writeReport("gx-runtime-collection.json", gx, request.evidenceRoot()))
        );
    }

    private CollectorReport collectSpark(CollectorRequest request) {
        List<GeneratedArtifact> artifacts = artifactsOfTypes(request.generatedArtifacts(), SPARK_ARTIFACT_TYPES);
        if (artifacts.isEmpty()) {
            return notApplicable(request, "spark", "no_spark_artifacts_emitted");
        }

        List<RuntimeEvidence> evidence = request.runtimeEvidence().stream()
                .filter(this::matchesSpark)
                .toList();
        if (evidence.isEmpty()) {
            return missing(request, "spark", artifacts, List.of("missing_spark_runtime_evidence"), evidence,
                    Map.of("expectedArtifactTypes", orderedTypes(SPARK_ARTIFACT_TYPES)));
        }

        List<String> failures = failedEvidenceCodes("spark", evidence);
        String verdict = failures.isEmpty() ? "PASS" : "FAIL";
        return report(request, "spark", "APPLICABLE", verdict, artifacts, evidence, failures,
                Map.of(
                        "expectedArtifactTypes", orderedTypes(SPARK_ARTIFACT_TYPES),
                        "runtimeEvidenceCount", evidence.size(),
                        "successfulEvidenceCount", evidence.stream().filter(this::isSuccessful).count()
                ));
    }

    private CollectorReport collectDbt(CollectorRequest request) {
        List<GeneratedArtifact> artifacts = artifactsOfTypes(request.generatedArtifacts(), DBT_ARTIFACT_TYPES);
        if (artifacts.isEmpty()) {
            return notApplicable(request, "dbt", "no_dbt_artifacts_emitted");
        }

        List<RuntimeEvidence> evidence = request.runtimeEvidence().stream()
                .filter(this::matchesDbt)
                .toList();

        boolean parseSeen = evidence.stream().anyMatch(this::isDbtParseEvidence);
        boolean runSeen = evidence.stream().anyMatch(this::isDbtRunEvidence);
        List<String> failures = new ArrayList<>();
        if (!parseSeen) {
            failures.add("missing_dbt_parse_evidence");
        }
        if (!runSeen) {
            failures.add("missing_dbt_run_evidence");
        }
        failures.addAll(failedEvidenceCodes("dbt", evidence));
        failures = new ArrayList<>(new LinkedHashSet<>(failures));

        String verdict;
        if (evidence.isEmpty()) {
            verdict = "MISSING";
        } else if (failures.stream().anyMatch(code -> code.startsWith("dbt_execution_failed"))) {
            verdict = "FAIL";
        } else if (!failures.isEmpty()) {
            verdict = "MISSING";
        } else {
            verdict = "PASS";
        }

        return report(request, "dbt", "APPLICABLE", verdict, artifacts, evidence, failures,
                Map.of(
                        "expectedArtifactTypes", orderedTypes(DBT_ARTIFACT_TYPES),
                        "parseEvidenceSeen", parseSeen,
                        "runEvidenceSeen", runSeen,
                        "runtimeEvidenceCount", evidence.size()
                ));
    }

    private CollectorReport collectGx(CollectorRequest request) {
        List<GeneratedArtifact> artifacts = artifactsOfTypes(request.generatedArtifacts(), GX_ARTIFACT_TYPES);
        if (artifacts.isEmpty()) {
            return notApplicable(request, "gx", "no_gx_checkpoints_emitted");
        }

        List<RuntimeEvidence> evidence = request.runtimeEvidence().stream()
                .filter(this::matchesGx)
                .toList();
        if (evidence.isEmpty()) {
            return missing(request, "gx", artifacts, List.of("missing_gx_runtime_evidence"), evidence,
                    Map.of("expectedArtifactTypes", orderedTypes(GX_ARTIFACT_TYPES)));
        }

        List<String> failures = failedEvidenceCodes("gx", evidence);
        String verdict = failures.isEmpty() ? "PASS" : "FAIL";
        return report(request, "gx", "APPLICABLE", verdict, artifacts, evidence, failures,
                Map.of(
                        "expectedArtifactTypes", orderedTypes(GX_ARTIFACT_TYPES),
                        "runtimeEvidenceCount", evidence.size()
                ));
    }

    private CollectorReport notApplicable(CollectorRequest request, String collector, String reason) {
        return report(request, collector, "NOT_APPLICABLE", "NOT_APPLICABLE", List.of(), List.of(), List.of(),
                Map.of("reason", reason));
    }

    private CollectorReport missing(CollectorRequest request,
                                    String collector,
                                    List<GeneratedArtifact> artifacts,
                                    List<String> failures,
                                    List<RuntimeEvidence> evidence,
                                    Map<String, Object> summary) {
        return report(request, collector, "APPLICABLE", "MISSING", artifacts, evidence, failures, summary);
    }

    private CollectorReport report(CollectorRequest request,
                                   String collector,
                                   String applicability,
                                   String verdict,
                                   List<GeneratedArtifact> artifacts,
                                   List<RuntimeEvidence> evidence,
                                   List<String> failures,
                                   Map<String, Object> summary) {
        return new CollectorReport(
                request.scenarioId(),
                request.generationRunId(),
                collector,
                applicability,
                verdict,
                artifacts.stream().map(GeneratedArtifact::getFilePath).sorted().toList(),
                evidence.stream().map(this::summarizeEvidence).toList(),
                List.copyOf(failures),
                new LinkedHashMap<>(summary)
        );
    }

    private Path writeReport(String fileName, CollectorReport report, Path evidenceRoot) throws IOException {
        Path output = evidenceRoot.resolve(fileName);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        return output;
    }

    private List<GeneratedArtifact> artifactsOfTypes(List<GeneratedArtifact> artifacts, Set<String> types) {
        return artifacts.stream()
                .filter(artifact -> types.contains(artifact.getFileType()))
                .toList();
    }

    private List<String> orderedTypes(Set<String> types) {
        return types.stream().sorted().toList();
    }

    private boolean matchesSpark(RuntimeEvidence evidence) {
        String haystack = lower(evidence.kind()) + " " + lower(evidence.operator()) + " "
                + lower(evidence.command()) + " " + lower(evidence.taskId()) + " " + lower(evidence.scriptPath());
        return haystack.contains("spark") || haystack.contains("pyspark") || haystack.contains("jobs/");
    }

    private boolean matchesDbt(RuntimeEvidence evidence) {
        String haystack = lower(evidence.kind()) + " " + lower(evidence.operator()) + " "
                + lower(evidence.command()) + " " + lower(evidence.taskId());
        return haystack.contains("dbt");
    }

    private boolean matchesGx(RuntimeEvidence evidence) {
        String haystack = lower(evidence.kind()) + " " + lower(evidence.operator()) + " "
                + lower(evidence.command()) + " " + lower(evidence.taskId()) + " " + lower(evidence.scriptPath());
        return haystack.contains("gx") || haystack.contains("great_expectations") || haystack.contains("checkpoint");
    }

    private boolean isDbtParseEvidence(RuntimeEvidence evidence) {
        String haystack = lower(evidence.kind()) + " " + lower(evidence.command());
        return haystack.contains("dbt_parse") || haystack.contains("dbt parse") || haystack.contains("dbt_build") || haystack.contains("dbt build");
    }

    private boolean isDbtRunEvidence(RuntimeEvidence evidence) {
        String haystack = lower(evidence.kind()) + " " + lower(evidence.command());
        return haystack.contains("dbt_run") || haystack.contains("dbt run") || haystack.contains("dbt_build") || haystack.contains("dbt build");
    }

    private List<String> failedEvidenceCodes(String collector, List<RuntimeEvidence> evidence) {
        return evidence.stream()
                .filter(entry -> !isSuccessful(entry))
                .map(entry -> collector + "_execution_failed:" + failureIdentity(entry))
                .toList();
    }

    private boolean isSuccessful(RuntimeEvidence evidence) {
        boolean exitOk = evidence.exitCode() == null || evidence.exitCode() == 0;
        String normalizedState = lower(evidence.state());
        boolean stateOk = normalizedState.isBlank()
                || Set.of("success", "succeeded", "completed", "pass", "passed", "ok").contains(normalizedState);
        return exitOk && stateOk;
    }

    private String failureIdentity(RuntimeEvidence evidence) {
        if (evidence.evidenceId() != null && !evidence.evidenceId().isBlank()) {
            return evidence.evidenceId();
        }
        if (evidence.taskId() != null && !evidence.taskId().isBlank()) {
            return evidence.taskId();
        }
        return evidence.kind() == null ? "unknown" : evidence.kind();
    }

    private EvidenceSummary summarizeEvidence(RuntimeEvidence evidence) {
        Path logPath = evidence.logPath();
        String logSha = null;
        if (logPath != null && Files.exists(logPath) && Files.isRegularFile(logPath)) {
            logSha = sha256(logPath);
        }
        return new EvidenceSummary(
                evidence.evidenceId(),
                evidence.kind(),
                evidence.phase(),
                evidence.taskId(),
                evidence.operator(),
                evidence.command(),
                evidence.scriptPath(),
                evidence.state(),
                evidence.exitCode(),
                logPath,
                logSha,
                evidence.metadata() == null ? Map.of() : evidence.metadata()
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

    private String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    public record CollectorRequest(
            String scenarioId,
            String generationRunId,
            List<GeneratedArtifact> generatedArtifacts,
            List<RuntimeEvidence> runtimeEvidence,
            Path evidenceRoot
    ) {
        public CollectorRequest {
            generatedArtifacts = generatedArtifacts == null ? List.of() : List.copyOf(generatedArtifacts);
            runtimeEvidence = runtimeEvidence == null ? List.of() : List.copyOf(runtimeEvidence);
        }
    }

    public record RuntimeEvidence(
            String evidenceId,
            String kind,
            String phase,
            String taskId,
            String operator,
            String command,
            String scriptPath,
            String state,
            Integer exitCode,
            Path logPath,
            Map<String, Object> metadata
    ) {
        public RuntimeEvidence {
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    public record CollectorBundle(
            CollectorReport spark,
            CollectorReport dbt,
            CollectorReport gx,
            List<Path> outputFiles
    ) {
    }

    public record CollectorReport(
            String scenarioId,
            String generationRunId,
            String collector,
            String applicability,
            String verdict,
            List<String> expectedArtifacts,
            List<EvidenceSummary> observedEvidence,
            List<String> failureCodes,
            Map<String, Object> summary
    ) {
    }

    public record EvidenceSummary(
            String evidenceId,
            String kind,
            String phase,
            String taskId,
            String operator,
            String command,
            String scriptPath,
            String state,
            Integer exitCode,
            Path logPath,
            String logSha256,
            Map<String, Object> metadata
    ) {
    }
}
