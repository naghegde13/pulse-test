package com.pulse.e2e.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.e2e.contract.EvidenceContracts.EvidenceArtifact;
import com.pulse.e2e.contract.EvidenceContracts.EvidenceBundle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Test-harness adapter around the local docker-compose Airflow CLI.
 * This keeps live-runtime orchestration logic in the e2e runtime lane rather
 * than pushing local-only assumptions into production codegen/deploy surfaces.
 */
public class LocalAirflowCliAdapter {

    private static final TypeReference<List<Map<String, Object>>> LIST_OF_MAPS = new TypeReference<>() {};
    private static final int TRIGGER_DAG_NOT_FOUND_RETRIES = 4;
    private static final Duration TRIGGER_DAG_NOT_FOUND_BACKOFF = Duration.ofSeconds(3);

    private final ObjectMapper objectMapper;
    private final CommandRunner commandRunner;
    private final String airflowContainerName;

    public LocalAirflowCliAdapter(ObjectMapper objectMapper) {
        this(objectMapper, new DockerCommandRunner(), "pulse-airflow-1");
    }

    LocalAirflowCliAdapter(ObjectMapper objectMapper,
                           CommandRunner commandRunner,
                           String airflowContainerName) {
        this.objectMapper = objectMapper;
        this.commandRunner = commandRunner;
        this.airflowContainerName = airflowContainerName;
    }

    public boolean dagExists(String dagId) throws IOException, InterruptedException {
        String raw = run("airflow", "dags", "list", "--output", "json");
        List<Map<String, Object>> dags = parseListOfMaps(raw);
        return dags.stream().anyMatch(dag -> dagId.equals(String.valueOf(dag.get("dag_id"))));
    }

    public boolean awaitDagExists(String dagId,
                                  Duration timeout,
                                  Duration pollInterval) throws IOException, InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (!Instant.now().isAfter(deadline)) {
            if (dagExists(dagId)) {
                return true;
            }
            Thread.sleep(Math.max(250L, pollInterval.toMillis()));
        }
        return false;
    }

    public DagRunHandle triggerDag(String dagId,
                                   String executionDate,
                                   Map<String, Object> conf) throws IOException, InterruptedException {
        return triggerDag(dagId, executionDate, conf, null);
    }

    public DagRunHandle triggerDag(String dagId,
                                   String executionDate,
                                   Map<String, Object> conf,
                                   String subdir) throws IOException, InterruptedException {
        String runId = "pulse_e2e__" + UUID.randomUUID();
        List<String> command = new ArrayList<>(List.of(
                "airflow", "dags", "trigger",
                dagId,
                "--exec-date", executionDate,
                "--run-id", runId,
                "--output", "json"
        ));
        if (subdir != null && !subdir.isBlank()) {
            command.add("--subdir");
            command.add(subdir);
        }
        if (conf != null && !conf.isEmpty()) {
            command.add("--conf");
            command.add(objectMapper.writeValueAsString(conf));
        }
        String raw = runTriggerWithDagNotFoundRetry(dagId, command);
        Map<String, Object> payload = parseMap(raw);
        String resolvedRunId = payload.get("run_id") != null ? payload.get("run_id").toString() : runId;
        return new DagRunHandle(dagId, executionDate, resolvedRunId, payload);
    }

    private String runTriggerWithDagNotFoundRetry(String dagId,
                                                  List<String> command) throws IOException, InterruptedException {
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= TRIGGER_DAG_NOT_FOUND_RETRIES; attempt++) {
            try {
                return run(command.toArray(String[]::new));
            } catch (IOException e) {
                lastFailure = e;
                if (!isDagNotFoundDuringTrigger(e, dagId) || attempt == TRIGGER_DAG_NOT_FOUND_RETRIES) {
                    throw e;
                }
                Thread.sleep(TRIGGER_DAG_NOT_FOUND_BACKOFF.toMillis());
            }
        }
        throw lastFailure != null ? lastFailure : new IOException("Failed to trigger DAG " + dagId);
    }

    private boolean isDagNotFoundDuringTrigger(IOException e, String dagId) {
        if (e == null || e.getMessage() == null) {
            return false;
        }
        String message = e.getMessage();
        return message.contains("Dag id " + dagId + " not found");
    }

    public DagRunStatus awaitTerminalState(String dagId,
                                           String executionDate,
                                           Duration timeout,
                                           Duration pollInterval) throws IOException, InterruptedException {
        return awaitTerminalState(dagId, executionDate, timeout, pollInterval, null);
    }

    public DagRunStatus awaitTerminalState(String dagId,
                                           String executionDate,
                                           Duration timeout,
                                           Duration pollInterval,
                                           String subdir) throws IOException, InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        List<String> observedStates = new ArrayList<>();
        while (!Instant.now().isAfter(deadline)) {
            String state = dagState(dagId, executionDate, subdir);
            observedStates.add(state);
            if (isTerminal(state)) {
                return new DagRunStatus(dagId, executionDate, state, List.copyOf(observedStates));
            }
            String inferredState = inferTerminalStateFromTasks(dagId, executionDate, subdir);
            if (inferredState != null) {
                observedStates.add("task_state_inferred:" + inferredState);
                return new DagRunStatus(dagId, executionDate, inferredState, List.copyOf(observedStates));
            }
            Thread.sleep(Math.max(250L, pollInterval.toMillis()));
        }
        return new DagRunStatus(dagId, executionDate, "timeout", List.copyOf(observedStates));
    }

    public List<Map<String, Object>> taskStatesForDagRun(String dagId,
                                                         String executionDateOrRunId) throws IOException, InterruptedException {
        return taskStatesForDagRun(dagId, executionDateOrRunId, null);
    }

    public List<Map<String, Object>> taskStatesForDagRun(String dagId,
                                                         String executionDateOrRunId,
                                                         String subdir) throws IOException, InterruptedException {
        String raw = run("airflow", "tasks", "states-for-dag-run",
                dagId, executionDateOrRunId, "--output", "json");
        return parseListOfMaps(raw);
    }

    public List<Map<String, Object>> taskLogsForDagRun(String dagId,
                                                       String runId) throws IOException, InterruptedException {
        String script = """
                python3 - <<'PY'
                import json
                from pathlib import Path

                dag_id = %s
                run_id = %s
                root = Path('/opt/airflow/logs') / f'dag_id={dag_id}' / f'run_id={run_id}'
                rows = []
                if root.exists():
                    for log_path in sorted(root.rglob('*.log')):
                        rows.append({
                            'path': str(log_path),
                            'content': log_path.read_text(),
                        })
                print(json.dumps(rows))
                PY
                """.formatted(pythonQuote(dagId), pythonQuote(runId));
        return parseListOfMaps(runShell(script));
    }

    public List<Map<String, Object>> listImportErrors() throws IOException, InterruptedException {
        String raw = runShell("airflow dags list-import-errors --output json || true");
        return parseListOfMaps(raw);
    }

    public void reserializeDag(String subdir) throws IOException, InterruptedException {
        if (subdir == null || subdir.isBlank()) {
            throw new IllegalArgumentException("subdir is required");
        }
        run("airflow", "dags", "reserialize", "--subdir", subdir);
    }

    public void unpauseDag(String dagId, String subdir) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("airflow", "dags", "unpause", dagId));
        if (subdir != null && !subdir.isBlank()) {
            command.add("--subdir");
            command.add(subdir);
        }
        run(command.toArray(String[]::new));
    }

    public EvidenceBundle writeEvidence(String scenarioId,
                                        Path evidenceRoot,
                                        DagRunHandle dagRunHandle,
                                        DagRunStatus dagRunStatus,
                                        List<Map<String, Object>> taskStates,
                                        List<Map<String, Object>> taskLogs) throws IOException {
        Files.createDirectories(evidenceRoot);
        Path dagStatePath = evidenceRoot.resolve("airflow-dag-state.json");
        Map<String, Object> dagStatePayload = new LinkedHashMap<>();
        dagStatePayload.put("scenarioId", scenarioId);
        dagStatePayload.put("dagId", dagRunHandle.dagId());
        dagStatePayload.put("executionDate", dagRunHandle.executionDate());
        dagStatePayload.put("runId", dagRunHandle.runId());
        dagStatePayload.put("triggerPayload", dagRunHandle.rawPayload());
        dagStatePayload.put("state", dagRunStatus.state());
        dagStatePayload.put("observedStates", dagRunStatus.observedStates());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(dagStatePath.toFile(), dagStatePayload);

        Path taskStatesPath = evidenceRoot.resolve("airflow-task-states.json");
        Map<String, Object> taskStatesPayload = new LinkedHashMap<>();
        taskStatesPayload.put("scenarioId", scenarioId);
        taskStatesPayload.put("dagId", dagRunHandle.dagId());
        taskStatesPayload.put("executionDate", dagRunHandle.executionDate());
        taskStatesPayload.put("runId", dagRunHandle.runId());
        taskStatesPayload.put("taskStates", taskStates);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(taskStatesPath.toFile(), taskStatesPayload);

        Path taskLogsPath = evidenceRoot.resolve("airflow-task-logs.json");
        Map<String, Object> taskLogsPayload = new LinkedHashMap<>();
        taskLogsPayload.put("scenarioId", scenarioId);
        taskLogsPayload.put("dagId", dagRunHandle.dagId());
        taskLogsPayload.put("runId", dagRunHandle.runId());
        taskLogsPayload.put("taskLogs", taskLogs);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(taskLogsPath.toFile(), taskLogsPayload);

        Path packet = evidenceRoot.resolve("airflow-runtime.json");
        Map<String, Object> payload = new LinkedHashMap<>(dagStatePayload);
        payload.put("taskStates", taskStates);
        payload.put("taskLogs", taskLogs);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(packet.toFile(), payload);

        List<EvidenceArtifact> artifacts = List.of(
                new EvidenceArtifact(
                        "airflow-dag-state",
                        "AIRFLOW_DAG_STATE",
                        dagStatePath,
                        sha256(dagStatePath),
                        "local-airflow-cli-adapter",
                        "test-run",
                        Map.of(
                                "dagId", dagRunHandle.dagId(),
                                "state", dagRunStatus.state(),
                                "observedStateCount", dagRunStatus.observedStates().size()
                        )
                ),
                new EvidenceArtifact(
                        "airflow-task-state",
                        "AIRFLOW_TASK_STATE",
                        taskStatesPath,
                        sha256(taskStatesPath),
                        "local-airflow-cli-adapter",
                        "test-run",
                        Map.of(
                                "dagId", dagRunHandle.dagId(),
                                "taskCount", taskStates.size()
                        )
                ),
                new EvidenceArtifact(
                        "airflow-task-log",
                        "AIRFLOW_TASK_LOG",
                        taskLogsPath,
                        sha256(taskLogsPath),
                        "local-airflow-cli-adapter",
                        "test-run",
                        Map.of(
                                "dagId", dagRunHandle.dagId(),
                                "taskLogCount", taskLogs.size()
                        )
                ),
                new EvidenceArtifact(
                        "airflow-runtime",
                        "AIRFLOW_RUNTIME_PACKET",
                        packet,
                        sha256(packet),
                        "local-airflow-cli-adapter",
                        "test-run",
                        Map.of(
                                "dagId", dagRunHandle.dagId(),
                                "state", dagRunStatus.state(),
                                "taskCount", taskStates.size(),
                                "taskLogCount", taskLogs.size()
                        )
                )
        );
        return new EvidenceBundle(
                scenarioId,
                dagRunHandle.runId(),
                evidenceRoot,
                artifacts,
                Map.of(
                        "state", dagRunStatus.state(),
                        "taskCount", taskStates.size(),
                        "taskLogCount", taskLogs.size()
                )
        );
    }

    public EvidenceBundle writeImportErrorEvidence(String scenarioId,
                                                   Path evidenceRoot,
                                                   String dagId,
                                                   List<Map<String, Object>> importErrors) throws IOException {
        Files.createDirectories(evidenceRoot);
        Path packet = evidenceRoot.resolve("airflow-import-errors.json");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scenarioId", scenarioId);
        payload.put("dagId", dagId);
        payload.put("importErrors", importErrors);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(packet.toFile(), payload);

        EvidenceArtifact artifact = new EvidenceArtifact(
                "airflow-import-errors",
                "AIRFLOW_IMPORT_ERRORS_PACKET",
                packet,
                sha256(packet),
                "local-airflow-cli-adapter",
                "test-run",
                Map.of(
                        "dagId", dagId,
                        "errorCount", importErrors.size()
                )
        );
        return new EvidenceBundle(
                scenarioId,
                dagId,
                evidenceRoot,
                List.of(artifact),
                Map.of(
                        "errorCount", importErrors.size()
                )
        );
    }

    private String dagState(String dagId, String executionDate, String subdir) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("airflow", "dags", "state", dagId, executionDate));
        if (subdir != null && !subdir.isBlank()) {
            command.add("--subdir");
            command.add(subdir);
        }
        return normalizeDagState(run(command.toArray(String[]::new)));
    }

    private boolean isTerminal(String state) {
        return switch (state.toLowerCase()) {
            case "success", "failed", "upstream_failed", "skipped", "timeout" -> true;
            default -> false;
        };
    }

    private String inferTerminalStateFromTasks(String dagId,
                                               String executionDateOrRunId,
                                               String subdir) throws IOException, InterruptedException {
        List<Map<String, Object>> taskStates = taskStatesForDagRun(dagId, executionDateOrRunId, subdir);
        if (taskStates.isEmpty()) {
            return null;
        }

        boolean allTerminal = taskStates.stream()
                .map(task -> String.valueOf(task.getOrDefault("state", "")))
                .allMatch(this::isTerminal);
        if (!allTerminal) {
            return null;
        }

        boolean anyFailed = taskStates.stream()
                .map(task -> String.valueOf(task.getOrDefault("state", "")))
                .anyMatch(state -> switch (state.toLowerCase()) {
                    case "failed", "upstream_failed", "timeout" -> true;
                    default -> false;
                });
        if (anyFailed) {
            return "failed";
        }

        boolean anySuccess = taskStates.stream()
                .map(task -> String.valueOf(task.getOrDefault("state", "")))
                .anyMatch(state -> "success".equalsIgnoreCase(state));
        return anySuccess ? "success" : "skipped";
    }

    private String normalizeDagState(String raw) {
        String state = raw == null ? "" : raw.trim();
        int comma = state.indexOf(',');
        if (comma >= 0) {
            state = state.substring(0, comma);
        }
        return state.trim();
    }

    private List<Map<String, Object>> parseListOfMaps(String raw) throws IOException {
        String trimmed = extractJsonPayload(raw);
        if (trimmed.isEmpty()) {
            return List.of();
        }
        if (!(trimmed.startsWith("[") || trimmed.startsWith("{"))) {
            return List.of();
        }
        return objectMapper.readValue(trimmed, LIST_OF_MAPS);
    }

    private Map<String, Object> parseMap(String raw) throws IOException {
        String trimmed = extractJsonPayload(raw);
        if (trimmed.isEmpty()) {
            return Map.of();
        }
        if (trimmed.startsWith("[")) {
            List<Map<String, Object>> rows = objectMapper.readValue(trimmed, LIST_OF_MAPS);
            return rows.isEmpty() ? Map.of() : rows.getFirst();
        }
        return objectMapper.readValue(trimmed, new TypeReference<>() {});
    }

    private String extractJsonPayload(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String[] lines = trimmed.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String candidate = lines[i].trim();
            if (candidate.startsWith("{") || candidate.startsWith("[")) {
                return String.join("\n", java.util.Arrays.copyOfRange(lines, i, lines.length)).trim();
            }
        }
        return trimmed;
    }

    private String run(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("docker");
        command.add("exec");
        command.add(airflowContainerName);
        command.addAll(List.of(args));
        return commandRunner.run(command);
    }

    private String runShell(String script) throws IOException, InterruptedException {
        return run("bash", "-lc", script);
    }

    private String pythonQuote(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
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

    interface CommandRunner {
        String run(List<String> command) throws IOException, InterruptedException;
    }

    private static final class DockerCommandRunner implements CommandRunner {
        @Override
        public String run(List<String> command) throws IOException, InterruptedException {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("Command failed (" + exitCode + "): " + String.join(" ", command) + "\n" + output);
            }
            return output;
        }
    }

    public record DagRunHandle(
            String dagId,
            String executionDate,
            String runId,
            Map<String, Object> rawPayload
    ) {
    }

    public record DagRunStatus(
            String dagId,
            String executionDate,
            String state,
            List<String> observedStates
    ) {
    }
}
