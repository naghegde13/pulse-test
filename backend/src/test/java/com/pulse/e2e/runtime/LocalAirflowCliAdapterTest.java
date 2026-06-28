package com.pulse.e2e.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LocalAirflowCliAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void dagExists_readsJsonDagCatalog() throws Exception {
        FakeRunner runner = new FakeRunner()
                .whenContains("airflow dags list --output json", """
                        [{"dag_id":"pulse_demo_v1"},{"dag_id":"pulse_other_v1"}]
                        """);
        LocalAirflowCliAdapter adapter = new LocalAirflowCliAdapter(objectMapper, runner, "pulse-airflow-1");

        assertTrue(adapter.dagExists("pulse_demo_v1"));
        assertFalse(adapter.dagExists("missing_dag"));
    }

    @Test
    void awaitDagExists_pollsUntilDagAppears() throws Exception {
        FakeRunner runner = new FakeRunner()
                .whenSequenceContains("airflow dags list --output json", List.of(
                        "[]",
                        "[{\"dag_id\":\"pulse_demo_v1\"}]"
                ));
        LocalAirflowCliAdapter adapter = new LocalAirflowCliAdapter(objectMapper, runner, "pulse-airflow-1");

        assertTrue(adapter.awaitDagExists("pulse_demo_v1", Duration.ofSeconds(5), Duration.ofMillis(1)));
    }

    @Test
    void dagExists_toleratesNonJsonAirflowOutput() throws Exception {
        FakeRunner runner = new FakeRunner()
                .whenContains("airflow dags list --output json", "Error: Failed to load all files.");
        LocalAirflowCliAdapter adapter = new LocalAirflowCliAdapter(objectMapper, runner, "pulse-airflow-1");

        assertFalse(adapter.dagExists("pulse_demo_v1"));
    }

    @Test
    void awaitTerminalState_pollsUntilSuccess_andWritesEvidence() throws Exception {
        FakeRunner runner = new FakeRunner()
                .whenContains("airflow dags trigger pulse_demo_v1", """
                        {"dag_id":"pulse_demo_v1","run_id":"manual__2026-04-26T00:00:00+00:00"}
                        """)
                .whenSequenceContains("airflow dags state pulse_demo_v1 2026-04-26", List.of("queued\n", "running\n", "success\n"))
                .whenContains("bash -lc python3 - <<'PY'", """
                        [{"path":"/opt/airflow/logs/dag_id=pulse_demo_v1/run_id=manual__2026-04-26T00:00:00+00:00/task_id=ingest/attempt=1.log","content":"ok"}]
                        """)
                .whenContains("airflow tasks states-for-dag-run pulse_demo_v1 2026-04-26 --output json", """
                        [{"task_id":"ingest","state":"success"},{"task_id":"dbt","state":"success"}]
                        """);
        LocalAirflowCliAdapter adapter = new LocalAirflowCliAdapter(objectMapper, runner, "pulse-airflow-1");

        LocalAirflowCliAdapter.DagRunHandle handle = adapter.triggerDag(
                "pulse_demo_v1",
                "2026-04-26",
                Map.of("scenario_id", "loan-master-live-runtime")
        );
        LocalAirflowCliAdapter.DagRunStatus status = adapter.awaitTerminalState(
                "pulse_demo_v1",
                "2026-04-26",
                Duration.ofSeconds(5),
                Duration.ofMillis(1)
        );
        List<Map<String, Object>> taskStates = adapter.taskStatesForDagRun("pulse_demo_v1", "2026-04-26");
        List<Map<String, Object>> taskLogs = adapter.taskLogsForDagRun("pulse_demo_v1", handle.runId());
        var bundle = adapter.writeEvidence("loan-master-live-runtime", tempDir, handle, status, taskStates, taskLogs);

        assertEquals("manual__2026-04-26T00:00:00+00:00", handle.runId());
        assertEquals("success", status.state());
        assertEquals(List.of("queued", "task_state_inferred:success"), status.observedStates());
        assertEquals(2, taskStates.size());
        assertEquals(1, taskLogs.size());

        assertTrue(Files.exists(tempDir.resolve("airflow-dag-state.json")));
        assertTrue(Files.exists(tempDir.resolve("airflow-task-states.json")));
        assertTrue(Files.exists(tempDir.resolve("airflow-task-logs.json")));
        Path evidence = tempDir.resolve("airflow-runtime.json");
        assertTrue(Files.exists(evidence));
        JsonNode json = objectMapper.readTree(evidence.toFile());
        assertEquals("success", json.get("state").asText());
        assertEquals(2, json.get("taskStates").size());
        assertEquals(1, json.get("taskLogs").size());
        assertEquals(
                List.of("AIRFLOW_DAG_STATE", "AIRFLOW_TASK_STATE", "AIRFLOW_TASK_LOG", "AIRFLOW_RUNTIME_PACKET"),
                bundle.artifacts().stream().map(artifact -> artifact.type()).toList()
        );
        assertEquals("success", bundle.summary().get("state"));
        assertEquals(2, bundle.summary().get("taskCount"));
        assertEquals(1, bundle.summary().get("taskLogCount"));
    }

    @Test
    void triggerDag_retriesTransientDagNotFound() throws Exception {
        FakeRunner runner = new FakeRunner()
                .whenSequenceContains("airflow dags trigger pulse_demo_v1", List.of(
                        new IOException("Command failed (1): docker exec pulse-airflow-1 airflow dags trigger pulse_demo_v1\n"
                                + "airflow.exceptions.DagNotFound: Dag id pulse_demo_v1 not found"),
                        "{\"dag_id\":\"pulse_demo_v1\",\"run_id\":\"manual__retry\"}"
                ));
        LocalAirflowCliAdapter adapter = new LocalAirflowCliAdapter(objectMapper, runner, "pulse-airflow-1");

        LocalAirflowCliAdapter.DagRunHandle handle = adapter.triggerDag(
                "pulse_demo_v1",
                "2026-04-26",
                Map.of("scenario_id", "loan-master-live-runtime")
        );

        assertEquals("manual__retry", handle.runId());
    }

    @Test
    void triggerDag_doesNotMaskPersistentDagNotFound() {
        FakeRunner runner = new FakeRunner()
                .whenSequenceContains("airflow dags trigger pulse_demo_v1", List.of(
                        new IOException("airflow.exceptions.DagNotFound: Dag id pulse_demo_v1 not found"),
                        new IOException("airflow.exceptions.DagNotFound: Dag id pulse_demo_v1 not found"),
                        new IOException("airflow.exceptions.DagNotFound: Dag id pulse_demo_v1 not found"),
                        new IOException("airflow.exceptions.DagNotFound: Dag id pulse_demo_v1 not found")
                ));
        LocalAirflowCliAdapter adapter = new LocalAirflowCliAdapter(objectMapper, runner, "pulse-airflow-1");

        IOException error = assertThrows(IOException.class, () -> adapter.triggerDag(
                "pulse_demo_v1",
                "2026-04-26",
                Map.of("scenario_id", "loan-master-live-runtime")
        ));

        assertTrue(error.getMessage().contains("Dag id pulse_demo_v1 not found"));
    }

    @Test
    void awaitTerminalState_infersSuccess_whenDagStateNeverTurnsTerminal_butTaskEvidenceIsSuccessful() throws Exception {
        FakeRunner runner = new FakeRunner()
                .whenSequenceContains("airflow dags state pulse_demo_v1 2026-04-26", List.of("running\n", "running\n", "running\n"))
                .whenContains("airflow tasks states-for-dag-run pulse_demo_v1 2026-04-26 --output json", """
                        [{"task_id":"ingest","state":"success"},{"task_id":"dbt","state":"success"}]
                        """);
        LocalAirflowCliAdapter adapter = new LocalAirflowCliAdapter(objectMapper, runner, "pulse-airflow-1");

        LocalAirflowCliAdapter.DagRunStatus status = adapter.awaitTerminalState(
                "pulse_demo_v1",
                "2026-04-26",
                Duration.ofMillis(3),
                Duration.ofMillis(1)
        );
        List<Map<String, Object>> taskStates = adapter.taskStatesForDagRun("pulse_demo_v1", "2026-04-26");

        assertEquals("success", status.state());
        assertEquals(List.of("running", "task_state_inferred:success"), status.observedStates());
        assertEquals(List.of("success", "success"),
                taskStates.stream().map(task -> String.valueOf(task.get("state"))).toList());

        LocalAirflowCliAdapter.DagRunHandle handle = new LocalAirflowCliAdapter.DagRunHandle(
                "pulse_demo_v1",
                "2026-04-26",
                "manual__2026-04-26T00:00:00+00:00",
                Map.of("dag_id", "pulse_demo_v1")
        );
        var bundle = adapter.writeEvidence("loan-master-live-runtime", tempDir, handle, status, taskStates, List.of());
        JsonNode evidence = objectMapper.readTree(tempDir.resolve("airflow-runtime.json").toFile());
        assertEquals("success", evidence.get("state").asText());
        assertEquals(2, evidence.get("taskStates").size());
        assertEquals(0, evidence.get("taskLogs").size());
        assertEquals(
                List.of("AIRFLOW_DAG_STATE", "AIRFLOW_TASK_STATE", "AIRFLOW_TASK_LOG", "AIRFLOW_RUNTIME_PACKET"),
                bundle.artifacts().stream().map(artifact -> artifact.type()).toList()
        );
        assertEquals("success", bundle.summary().get("state"));
        assertEquals(2, bundle.summary().get("taskCount"));
        assertEquals(0, bundle.summary().get("taskLogCount"));
    }

    @Test
    void writeImportErrorEvidence_persistsPacket() throws Exception {
        FakeRunner runner = new FakeRunner()
                .whenContains("airflow dags list-import-errors --output json", """
                        [{"filepath":"/opt/pulse/repo/demo/dags/demo.py","error":"SyntaxError"}]
                        """);
        LocalAirflowCliAdapter adapter = new LocalAirflowCliAdapter(objectMapper, runner, "pulse-airflow-1");

        List<Map<String, Object>> errors = adapter.listImportErrors();
        var bundle = adapter.writeImportErrorEvidence("loan-master-live-runtime", tempDir, "pulse_demo_v1", errors);

        Path evidence = tempDir.resolve("airflow-import-errors.json");
        assertTrue(Files.exists(evidence));
        JsonNode json = objectMapper.readTree(evidence.toFile());
        assertEquals(1, json.get("importErrors").size());
        assertEquals("AIRFLOW_IMPORT_ERRORS_PACKET", bundle.artifacts().getFirst().type());
    }

    private static final class FakeRunner implements LocalAirflowCliAdapter.CommandRunner {
        private final Map<String, String> singleResponses = new java.util.LinkedHashMap<>();
        private final Map<String, Deque<Object>> sequenceResponses = new java.util.LinkedHashMap<>();

        FakeRunner whenContains(String key, String output) {
            singleResponses.put(key, output);
            return this;
        }

        FakeRunner whenSequenceContains(String key, List<?> outputs) {
            sequenceResponses.put(key, new ArrayDeque<>(outputs));
            return this;
        }

        @Override
        public String run(List<String> command) throws IOException {
            String joined = String.join(" ", command);
            for (var entry : sequenceResponses.entrySet()) {
                if (joined.contains(entry.getKey())) {
                    Deque<Object> outputs = entry.getValue();
                    if (outputs.isEmpty()) {
                        throw new IllegalStateException("No fake output left for " + entry.getKey());
                    }
                    Object next = outputs.removeFirst();
                    if (next instanceof IOException ioException) {
                        throw ioException;
                    }
                    return String.valueOf(next);
                }
            }
            for (var entry : singleResponses.entrySet()) {
                if (joined.contains(entry.getKey())) {
                    return entry.getValue();
                }
            }
            throw new IllegalStateException("Unexpected command: " + joined);
        }
    }
}
