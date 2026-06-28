package com.pulse.e2e.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GcpComposerDataprocExecutionRunnerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir Path tempDir;

    @Test
    void execute_materializesRuntimeInputs_runsCloudCommands_andWritesRuntimeArtifacts() throws Exception {
        String namespace = "tenants/home-lending/pipelines/loan-master";
        Path runtimeRepoRoot = tempDir.resolve("runtime");
        Path namespacedRoot = runtimeRepoRoot.resolve(namespace);
        Files.createDirectories(namespacedRoot.resolve("jobs/filter"));
        Files.writeString(namespacedRoot.resolve("jobs/filter/generic_filter.py"), "print('filter')");
        Files.createDirectories(namespacedRoot.resolve("runtime"));
        Files.writeString(namespacedRoot.resolve("runtime/pulse_secret_resolver.py"), "# helper");
        Files.createDirectories(namespacedRoot.resolve("dags"));

        FakeRunner runner = new FakeRunner()
                .whenContains("gcloud composer environments describe pulse-proof-composer", "{\"name\":\"composer\"}")
                .whenContains("gcloud composer environments run pulse-proof-composer --location=us-central1 --project=pulse-proof-04261847 dags list", "[]")
                .whenContains("gcloud composer environments run pulse-proof-composer --location=us-central1 --project=pulse-proof-04261847 list-import-errors", "[]")
                .whenContains("gcloud storage cp --recursive", "")
                .whenContains("gcloud storage ls --recursive gs://pulse-proof-stage/pulse-runtime/generic-filter-gcp-golden/run-0426/" + namespace, """
                        gs://pulse-proof-stage/pulse-runtime/generic-filter-gcp-golden/run-0426/%s/jobs/filter/generic_filter.py
                        gs://pulse-proof-stage/pulse-runtime/generic-filter-gcp-golden/run-0426/%s/runtime/pulse_secret_resolver.py
                        """.formatted(namespace, namespace))
                .whenContains("gcloud storage ls --recursive --json gs://us-central1-pulse-proof-com-a7066110-bucket/dags/" + namespace, """
                        [
                          {
                            "uri":"gs://us-central1-pulse-proof-com-a7066110-bucket/dags/%s/dags/generic_filter_gcp_dag.py",
                            "generation":"123",
                            "metageneration":"1",
                            "size":"512",
                            "crc32c":"AAAAAA==",
                            "sha256":"dag-sha"
                          }
                        ]
                        """.formatted(namespace))
                .whenContains("gcloud storage ls --recursive --json gs://pulse-proof-stage/pulse-runtime/generic-filter-gcp-golden/run-0426/" + namespace, """
                        [
                          {
                            "uri":"gs://pulse-proof-stage/pulse-runtime/generic-filter-gcp-golden/run-0426/%s/jobs/filter/generic_filter.py",
                            "generation":"456",
                            "metageneration":"1",
                            "size":"128",
                            "crc32c":"BBBBBB==",
                            "sha256":"job-sha"
                          },
                          {
                            "uri":"gs://pulse-proof-stage/pulse-runtime/generic-filter-gcp-golden/run-0426/%s/runtime/pulse_secret_resolver.py",
                            "generation":"457",
                            "metageneration":"1",
                            "size":"64",
                            "crc32c":"CCCCCC==",
                            "sha256":"helper-sha"
                          }
                        ]
                        """.formatted(namespace, namespace))
                .whenContains("gcloud composer environments run pulse-proof-composer --location=us-central1 --project=pulse-proof-04261847 dags trigger -- pulse_semantic_gcp_generic_filter", "{\"dag_run_id\":\"pulse_semantic__run_0426\"}")
                .whenContains("gcloud composer environments run pulse-proof-composer --location=us-central1 --project=pulse-proof-04261847 dags state -- pulse_semantic_gcp_generic_filter 2026-04-26T04:00:00+00:00", "success\n")
                .whenContains("gcloud composer environments run pulse-proof-composer --location=us-central1 --project=pulse-proof-04261847 tasks states-for-dag-run -- pulse_semantic_gcp_generic_filter 2026-04-26T04:00:00+00:00", """
                        [{"task_id":"submit_dataproc_batch","state":"success"},{"task_id":"done","state":"success"}]
                        """)
                .whenContains("gcloud dataproc batches describe semantic-generic-filter-batch --region=us-central1 --project=pulse-proof-04261847 --format=json", """
                        {"state":"SUCCEEDED","stateHistory":[{"state":"PENDING"},{"state":"SUCCEEDED"}]}
                        """)
                .whenContains("gcloud storage ls --recursive --json gs://pulse-proof-output/generic-filter/run-0426", """
                        [
                          {
                            "uri":"gs://pulse-proof-output/generic-filter/run-0426/part-0000.json",
                            "generation":"789",
                            "metageneration":"1",
                            "size":"42",
                            "crc32c":"DDDDDD==",
                            "sha256":"output-sha"
                          }
                        ]
                        """);

        GcpComposerDataprocExecutionRunner executionRunner =
                new GcpComposerDataprocExecutionRunner(objectMapper, runner);
        Path evidenceRoot = tempDir.resolve("evidence");
        GcpComposerDataprocExecutionRunner.ExecutionResult result = executionRunner.execute(
                new GcpComposerDataprocExecutionRunner.ExecutionRequest(
                        request(runtimeRepoRoot, namespace),
                        evidenceRoot,
                        "PASS",
                        "pulse-proof-04261847",
                        "pulse-proof-composer",
                        "gs://pulse-proof-stage",
                        "semantic-runtime@pulse-proof-04261847.iam.gserviceaccount.com",
                        1
                )
        );

        assertFalse(result.blocked());
        assertTrue(Files.exists(evidenceRoot.resolve("composer-upload-evidence.json")));
        assertTrue(Files.exists(evidenceRoot.resolve("staged-artifact-manifest.json")));
        assertTrue(Files.exists(evidenceRoot.resolve("gcs-input-listing.json")));
        assertTrue(Files.exists(evidenceRoot.resolve("composer-trigger-request.json")));
        assertTrue(Files.exists(evidenceRoot.resolve("composer-import-errors.json")));
        assertTrue(Files.exists(evidenceRoot.resolve("composer-dag-state.json")));
        assertTrue(Files.exists(evidenceRoot.resolve("composer-task-state.json")));
        assertTrue(Files.exists(evidenceRoot.resolve("dataproc-batch-state.json")));
        assertTrue(Files.exists(evidenceRoot.resolve("gcs-output-probe.json")));

        Path runtimeDag = runtimeRepoRoot.resolve(namespace).resolve("dags/generic_filter_gcp_dag.py");
        Path runtimeSubmitRequest = runtimeRepoRoot.resolve(namespace).resolve("runtime/dataproc-submit-request.json");
        assertTrue(Files.exists(runtimeDag));
        assertTrue(Files.exists(runtimeSubmitRequest));
        assertTrue(Files.readString(runtimeDag).contains("DataprocCreateBatchOperator"));

        JsonNode trigger = objectMapper.readTree(evidenceRoot.resolve("composer-trigger-request.json").toFile());
        assertEquals("GCLOUD_SUBPROCESS", trigger.get("authMode").asText());
        assertEquals("GCLOUD_COMPOSER_RUN", trigger.get("controlPlaneMode").asText());
        assertEquals("aamer@aamer.net", trigger.get("callerPrincipal").asText());
        assertTrue(trigger.get("command").get("command").asText().contains("dags trigger"));

        JsonNode taskState = objectMapper.readTree(evidenceRoot.resolve("composer-task-state.json").toFile());
        assertEquals(2, taskState.get("taskStates").size());

        JsonNode uploadEvidence = objectMapper.readTree(evidenceRoot.resolve("composer-upload-evidence.json").toFile());
        assertEquals("GCLOUD_SUBPROCESS", uploadEvidence.get("authMode").asText());
        assertEquals("GCLOUD_COMPOSER_RUN", uploadEvidence.get("controlPlaneMode").asText());
        assertEquals("aamer@aamer.net", uploadEvidence.get("callerPrincipal").asText());
        assertFalse(uploadEvidence.get("uploadedObjects").isEmpty());

        JsonNode dataproc = objectMapper.readTree(evidenceRoot.resolve("dataproc-batch-state.json").toFile());
        assertEquals("GCLOUD_SUBPROCESS", dataproc.get("authMode").asText());
        assertEquals("GCLOUD_COMPOSER_RUN", dataproc.get("controlPlaneMode").asText());
        assertEquals("aamer@aamer.net", dataproc.get("callerPrincipal").asText());
        assertEquals("SUCCEEDED", dataproc.get("batchState").get("state").asText());

        JsonNode outputProbe = objectMapper.readTree(evidenceRoot.resolve("gcs-output-probe.json").toFile());
        assertEquals("GCLOUD_SUBPROCESS", outputProbe.get("authMode").asText());
        assertEquals("GCLOUD_COMPOSER_RUN", outputProbe.get("controlPlaneMode").asText());
        assertEquals("aamer@aamer.net", outputProbe.get("callerPrincipal").asText());
        assertEquals("gs://pulse-proof-output/generic-filter/run-0426/part-0000.json",
                outputProbe.get("objects").get(0).get("uri").asText());

        assertTrue(runner.commands().stream().anyMatch(command -> command.contains("gcloud storage cp --recursive")));
        assertTrue(runner.commands().stream().anyMatch(command -> command.contains("gcloud composer environments run pulse-proof-composer")));
        assertTrue(runner.commands().stream().anyMatch(command -> command.contains("gcloud dataproc batches describe semantic-generic-filter-batch")));
        assertTrue(runner.commands().stream().noneMatch(command ->
                        command.contains(" storage rm ")
                                || command.contains(" storage delete ")
                                || command.contains(" dataproc batches delete ")
                                || command.contains(" dags delete ")),
                "runner should stay non-destructive and avoid delete operations");
    }

    @Test
    void execute_blocksBeforeCloudWritesWhenSafetyEnvelopeDoesNotMatch() throws Exception {
        FakeRunner runner = new FakeRunner();
        GcpComposerDataprocExecutionRunner executionRunner =
                new GcpComposerDataprocExecutionRunner(objectMapper, runner);

        GcpComposerDataprocExecutionRunner.ExecutionResult result = executionRunner.execute(
                new GcpComposerDataprocExecutionRunner.ExecutionRequest(
                        request(tempDir.resolve("runtime"), "tenants/home-lending/pipelines/loan-master"),
                        tempDir.resolve("evidence-blocked"),
                        "PASS",
                        "wrong-project",
                        "pulse-proof-composer",
                        "gs://pulse-proof-stage",
                        "semantic-runtime@pulse-proof-04261847.iam.gserviceaccount.com",
                        1
                )
        );

        assertTrue(result.blocked());
        assertTrue(result.blockers().contains("project_mismatch"));
        assertTrue(Files.exists(tempDir.resolve("evidence-blocked/gcp-execution-runner-blocked.json")));
        assertTrue(runner.commands().isEmpty());
    }

    @Test
    void execute_blocksWhenReadinessFailsOrConcurrencyExceedsOne_withoutIssuingCloudCommands() throws Exception {
        FakeRunner runner = new FakeRunner();
        GcpComposerDataprocExecutionRunner executionRunner =
                new GcpComposerDataprocExecutionRunner(objectMapper, runner);

        Path evidenceRoot = tempDir.resolve("evidence-readiness-blocked");
        GcpComposerDataprocExecutionRunner.ExecutionResult result = executionRunner.execute(
                new GcpComposerDataprocExecutionRunner.ExecutionRequest(
                        request(tempDir.resolve("runtime"), "tenants/home-lending/pipelines/loan-master"),
                        evidenceRoot,
                        "FAIL",
                        "pulse-proof-04261847",
                        "pulse-proof-composer",
                        "gs://pulse-proof-stage",
                        "semantic-runtime@pulse-proof-04261847.iam.gserviceaccount.com",
                        2
                )
        );

        assertTrue(result.blocked());
        assertTrue(result.blockers().contains("readiness_verdict_not_pass:FAIL"));
        assertTrue(result.blockers().contains("max_concurrent_cloud_jobs_exceeded"));
        assertTrue(runner.commands().isEmpty());

        JsonNode blocked = objectMapper.readTree(evidenceRoot.resolve("gcp-execution-runner-blocked.json").toFile());
        assertTrue(blocked.get("blocked").asBoolean());
        assertEquals("GCLOUD_SUBPROCESS", blocked.get("authMode").asText());
        assertEquals("GCLOUD_COMPOSER_RUN", blocked.get("controlPlaneMode").asText());
        assertEquals("aamer@aamer.net", blocked.get("callerPrincipal").asText());
        assertEquals("generic-filter-gcp-golden", blocked.get("scenarioId").asText());
        assertEquals(List.of("readiness_verdict_not_pass:FAIL", "max_concurrent_cloud_jobs_exceeded"),
                objectMapper.convertValue(blocked.get("reasons"), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {}));
    }

    private GcpComposerDataprocBridgeAdapter.AdapterRequest request(Path runtimeRepoRoot, String namespace) {
        return new GcpComposerDataprocBridgeAdapter.AdapterRequest(
                "generic-filter-gcp-golden",
                "run-0426",
                "pulse-proof-04261847",
                "us-central1",
                "pulse-proof-composer",
                "gs://us-central1-pulse-proof-com-a7066110-bucket/dags",
                "us-central1",
                "semantic-runtime@pulse-proof-04261847.iam.gserviceaccount.com",
                "gs://pulse-proof-stage",
                "gs://pulse-proof-output/generic-filter/run-0426",
                namespace,
                "pulse_semantic_gcp_generic_filter",
                "generic_filter_gcp_dag.py",
                "pulse_semantic__run_0426",
                "2026-04-26T04:00:00+00:00",
                "semantic-generic-filter-batch",
                "jobs/filter/generic_filter.py",
                List.of("runtime/pulse_secret_resolver.py"),
                List.of(
                        "--input", "gs://pulse-proof-fixtures/generic-filter/input.json",
                        "--output", "gs://pulse-proof-output/generic-filter/run-0426"
                ),
                "GCLOUD_SUBPROCESS",
                "GCLOUD_COMPOSER_RUN",
                "aamer@aamer.net",
                runtimeRepoRoot
        );
    }

    private static final class FakeRunner implements GcpComposerDataprocExecutionRunner.CommandRunner {
        private final Map<String, String> responses = new LinkedHashMap<>();
        private final List<String> commands = new ArrayList<>();

        FakeRunner whenContains(String key, String output) {
            responses.put(key, output);
            return this;
        }

        @Override
        public String run(String command) throws IOException {
            commands.add(command);
            for (var entry : responses.entrySet()) {
                if (command.contains(entry.getKey())) {
                    return entry.getValue();
                }
            }
            throw new IOException("Unexpected command: " + command);
        }

        List<String> commands() {
            return commands;
        }
    }
}
