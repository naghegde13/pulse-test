package com.pulse.e2e.validation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.e2e.contract.EvidenceContracts.LayerVerdict;
import com.pulse.e2e.contract.EvidenceContracts.Verdict;
import com.pulse.e2e.contract.ScenarioDsl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LiveRuntimeScenarioValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void validate_emitsMachineVerifiableBundle_forPassingLiveRuntimeScenario() throws Exception {
        Path inputs = tempDir.resolve("inputs");
        Files.createDirectories(inputs);
        Path dagState = writeJson(inputs.resolve("dag-state.json"), Map.of("state", "success"));
        Path taskState = writeJson(inputs.resolve("task-state.json"), Map.of("taskStates", List.of(Map.of("taskId", "spark_job", "state", "success"))));
        Path runtimeLog = writeText(inputs.resolve("logs/runtime.log"), "runtime ok\n");
        Path sparkSummary = writeJson(inputs.resolve("collectors/spark-summary.json"), Map.of("status", "success"));
        Path dbtSummary = writeJson(inputs.resolve("collectors/dbt-run.json"), Map.of("status", "success"));
        Path gxSummary = writeJson(inputs.resolve("collectors/gx-checkpoint.json"), Map.of("status", "success"));
        Path minioProbe = writeJson(inputs.resolve("probes/minio-output-probe.json"), Map.of("exists", true));
        Path oracleComparison = writeJson(inputs.resolve("data-oracle-comparison.json"), Map.of("rowCountMatches", true));

        ScenarioDsl.ScenarioDefinition scenario = new ScenarioDsl.ScenarioDefinition(
                "loan-master-live-runtime",
                "Loan Master Live Runtime",
                ScenarioDsl.ProofMode.LIVE_RUNTIME,
                ScenarioDsl.RuntimeAdapter.LOCAL_AIRFLOW_BRIDGE,
                List.of("phase3", "loan_master", "live_runtime"),
                new ScenarioDsl.BuilderPlan("tenant-runtime", "servicing", "loan_master", List.of("FileIngestion"), "loan_master"),
                new ScenarioDsl.EvidenceExpectation(
                        List.of("dag-state.json", "task-state.json", "minio-output-probe.json", "data-oracle-comparison.json", "scenario-catalog.json", "scenario-coverage-plan.json", "coverage.json", "verdict.json", "evidence-index.json"),
                        List.of("AIRFLOW_DAG_STATE", "AIRFLOW_TASK_STATE", "RUNTIME_LOG", "SPARK_SUMMARY", "DBT_RUN_SUMMARY", "GX_CHECKPOINT_SUMMARY", "MINIO_OUTPUT_PROBE", "DATA_ORACLE_COMPARISON", "SCENARIO_CATALOG", "SCENARIO_COVERAGE_PLAN", "COVERAGE", "VERDICT", "EVIDENCE_INDEX"),
                        "verdict.json",
                        List.of("build", "runtime", "data")
                ),
                Map.of("fixture_manifest", "e2e/fixtures/loan_master/fixture-manifest.json")
        );

        LiveRuntimeScenarioValidator validator = new LiveRuntimeScenarioValidator(objectMapper);
        LiveRuntimeScenarioValidator.ValidationResult result = validator.validate(
                new LiveRuntimeScenarioValidator.RuntimeValidationRequest(
                        scenario,
                        "run-123",
                        "loan-master-canonical",
                        tempDir.resolve("evidence/local"),
                        List.of(
                                artifact("dag-state", "AIRFLOW_DAG_STATE", dagState),
                                artifact("task-state", "AIRFLOW_TASK_STATE", taskState),
                                artifact("runtime-log", "RUNTIME_LOG", runtimeLog),
                                artifact("spark-summary", "SPARK_SUMMARY", sparkSummary),
                                artifact("dbt-run", "DBT_RUN_SUMMARY", dbtSummary),
                                artifact("gx-checkpoint", "GX_CHECKPOINT_SUMMARY", gxSummary),
                                artifact("minio-probe", "MINIO_OUTPUT_PROBE", minioProbe),
                                artifact("oracle-comparison", "DATA_ORACLE_COMPARISON", oracleComparison)
                        ),
                        List.of(
                                new LayerVerdict("build", Verdict.PASS, List.of(), Map.of("generationRunId", "run-123")),
                                new LayerVerdict("runtime", Verdict.PASS, List.of(), Map.of("dagState", "success")),
                                new LayerVerdict("data", Verdict.PASS, List.of(), Map.of("oracleComparison", "matched"))
                        ),
                        List.of("phase3", "loan_master", "live_runtime", "airflow", "spark", "dbt", "gx", "minio"),
                        "abc1234",
                        "compose-sha-1",
                        0
                )
        );

        assertEquals(Verdict.PASS, result.verdict());
        assertTrue(result.failureCodes().isEmpty());
        assertTrue(Files.exists(result.verdictPath()));
        assertTrue(Files.exists(result.coveragePath()));
        assertTrue(Files.exists(tempDir.resolve("evidence/local/scenario-catalog.json")));
        assertTrue(Files.exists(tempDir.resolve("evidence/local/scenario-coverage-plan.json")));
        assertTrue(Files.exists(tempDir.resolve("evidence/local/evidence-index.json")));
        assertEquals(
                Set.of("AIRFLOW_DAG_STATE", "AIRFLOW_TASK_STATE", "RUNTIME_LOG", "SPARK_SUMMARY", "DBT_RUN_SUMMARY", "GX_CHECKPOINT_SUMMARY", "MINIO_OUTPUT_PROBE", "DATA_ORACLE_COMPARISON", "SCENARIO_CATALOG", "SCENARIO_COVERAGE_PLAN", "COVERAGE", "VERDICT", "EVIDENCE_INDEX"),
                result.evidenceBundle().artifacts().stream().map(artifact -> artifact.type()).collect(Collectors.toSet())
        );

        Map<String, Object> verdict = objectMapper.readValue(result.verdictPath().toFile(), new TypeReference<>() {});
        assertEquals("PASS", verdict.get("verdict"));
        assertEquals("LOCAL_AIRFLOW_BRIDGE", verdict.get("runtimeAdapter"));
        assertEquals("local", verdict.get("runtimeNamespace"));
        assertEquals("loan-master-canonical", verdict.get("scenarioVariantId"));
        assertEquals(List.of(), verdict.get("missingCoverageTags"));
        assertEquals(List.of("build", "runtime", "data"),
                ((List<Map<String, Object>>) verdict.get("layerVerdicts")).stream().map(entry -> String.valueOf(entry.get("layerId"))).toList());

        Map<String, Object> coverage = objectMapper.readValue(result.coveragePath().toFile(), new TypeReference<>() {});
        assertEquals(List.of("phase3", "loan_master", "live_runtime"), coverage.get("expectedTags"));
        assertEquals(List.of(), coverage.get("missingTags"));
        assertEquals("PASS", coverage.get("coverageVerdict"));

        Map<String, Object> scenarioCoveragePlan = objectMapper.readValue(
                tempDir.resolve("evidence/local/scenario-coverage-plan.json").toFile(),
                new TypeReference<>() {}
        );
        assertTrue(((List<?>) scenarioCoveragePlan.get("runtimePromotionCandidates")).size() > 0);
        assertTrue(((List<?>) scenarioCoveragePlan.get("liveRuntimeScenarioIds")).size() > 0);

        Map<String, Object> evidenceIndex = objectMapper.readValue(
                tempDir.resolve("evidence/local/evidence-index.json").toFile(),
                new TypeReference<>() {}
        );
        assertEquals("LOCAL_AIRFLOW_BRIDGE", evidenceIndex.get("runtimeAdapter"));
        assertEquals("local", evidenceIndex.get("runtimeNamespace"));
        assertTrue(((List<Map<String, Object>>) evidenceIndex.get("artifacts")).stream()
                .anyMatch(artifact -> "SCENARIO_COVERAGE_PLAN".equals(artifact.get("type"))
                        && String.valueOf(artifact.get("path")).endsWith("scenario-coverage-plan.json")));
    }

    @Test
    void validate_supportsNestedGcpContractPathsAndGlobbedLogArtifacts() throws Exception {
        Path inputs = tempDir.resolve("gcp-inputs");
        Files.createDirectories(inputs);

        ScenarioDsl.ScenarioDefinition scenario = new ScenarioDsl.ScenarioDefinition(
                "generic-filter-gcp-golden",
                "Generic Filter GCP Golden",
                ScenarioDsl.ProofMode.LIVE_RUNTIME,
                ScenarioDsl.RuntimeAdapter.GCP_COMPOSER_DATAPROC_BRIDGE,
                List.of("phase4", "gcp_golden", "live_runtime"),
                new ScenarioDsl.BuilderPlan("tenant-runtime", "servicing", "loan_master", List.of("GenericFilter"), "loan_master"),
                new ScenarioDsl.EvidenceExpectation(
                        List.of(
                                "gcp-readiness-report.json",
                                "gcp-environment-smoke-verdict.json",
                                "gcp-runtime-bridge.json",
                                "composer-upload-evidence.json",
                                "composer-import-errors.json",
                                "composer-dag-state.json",
                                "composer-task-state.json",
                                "gcs-input-listing.json",
                                "gcs-output-probe.json",
                                "cloud-data-oracle-comparison.json",
                                "runtime/dataproc-submit-request.json",
                                "dataproc-batch-state.json",
                                "logs/composer/*.log",
                                "logs/dataproc/*.log",
                                "scenario-catalog.json",
                                "scenario-coverage-plan.json",
                                "coverage.json",
                                "verdict.json",
                                "evidence-index.json"
                        ),
                        List.of(
                                "GCP_READINESS_REPORT",
                                "GCP_SMOKE_VERDICT",
                                "GCP_RUNTIME_BRIDGE",
                                "COMPOSER_UPLOAD_EVIDENCE",
                                "COMPOSER_IMPORT_ERRORS",
                                "COMPOSER_DAG_STATE",
                                "COMPOSER_TASK_STATE",
                                "GCS_INPUT_LISTING",
                                "GCS_OUTPUT_PROBE",
                                "CLOUD_DATA_ORACLE_COMPARISON",
                                "DATAPROC_SUBMIT_REQUEST",
                                "DATAPROC_BATCH_STATE",
                                "COMPOSER_LOG",
                                "DATAPROC_LOG",
                                "SCENARIO_CATALOG",
                                "SCENARIO_COVERAGE_PLAN",
                                "COVERAGE",
                                "VERDICT",
                                "EVIDENCE_INDEX"
                        ),
                        "verdict.json",
                        List.of("build", "runtime", "data")
                ),
                Map.of()
        );

        LiveRuntimeScenarioValidator.ValidationResult result = new LiveRuntimeScenarioValidator(objectMapper).validate(
                new LiveRuntimeScenarioValidator.RuntimeValidationRequest(
                        scenario,
                        "run-gcp-123",
                        "generic-filter-gcp",
                        tempDir.resolve("evidence/gcp"),
                        List.of(
                                artifact("gcp-readiness", "GCP_READINESS_REPORT", writeJson(inputs.resolve("gcp-readiness-report.json"), Map.of("verdict", "PASS"))),
                                artifact("gcp-smoke-verdict", "GCP_SMOKE_VERDICT", writeJson(inputs.resolve("gcp-environment-smoke-verdict.json"), Map.of("verdict", "PASS"))),
                                artifact("gcp-runtime-bridge", "GCP_RUNTIME_BRIDGE", writeJson(inputs.resolve("gcp-runtime-bridge.json"), Map.of("dagId", "pulse_semantic"))),
                                artifact("composer-upload", "COMPOSER_UPLOAD_EVIDENCE", writeJson(inputs.resolve("composer-upload-evidence.json"), Map.of("uploaded", true))),
                                artifact("composer-import-errors", "COMPOSER_IMPORT_ERRORS", writeJson(inputs.resolve("composer-import-errors.json"), Map.of("errors", List.of()))),
                                artifact("composer-dag-state", "COMPOSER_DAG_STATE", writeJson(inputs.resolve("composer-dag-state.json"), Map.of("state", "success"))),
                                artifact("composer-task-state", "COMPOSER_TASK_STATE", writeJson(inputs.resolve("composer-task-state.json"), Map.of("taskStates", List.of(Map.of("taskId", "submit", "state", "success"))))),
                                artifact("gcs-input-listing", "GCS_INPUT_LISTING", writeJson(inputs.resolve("gcs-input-listing.json"), Map.of("objects", List.of("input.json")))),
                                artifact("gcs-output-probe", "GCS_OUTPUT_PROBE", writeJson(inputs.resolve("gcs-output-probe.json"), Map.of("objects", List.of("output.json")))),
                                artifact("oracle-comparison", "CLOUD_DATA_ORACLE_COMPARISON", writeJson(inputs.resolve("cloud-data-oracle-comparison.json"), Map.of("rowCountMatches", true))),
                                artifact("dataproc-submit-request", "DATAPROC_SUBMIT_REQUEST", writeJson(inputs.resolve("runtime/dataproc-submit-request.json"), Map.of("batchId", "batch-123")), Map.of("contractPath", "runtime/dataproc-submit-request.json")),
                                artifact("dataproc-batch-state", "DATAPROC_BATCH_STATE", writeJson(inputs.resolve("dataproc-batch-state.json"), Map.of("state", "SUCCEEDED"))),
                                artifact("composer-log", "COMPOSER_LOG", writeText(inputs.resolve("logs/composer/trigger.log"), "composer ok\n"), Map.of("contractPath", "logs/composer/trigger.log")),
                                artifact("dataproc-log", "DATAPROC_LOG", writeText(inputs.resolve("logs/dataproc/driver.log"), "dataproc ok\n"), Map.of("contractPath", "logs/dataproc/driver.log"))
                        ),
                        List.of(
                                new LayerVerdict("build", Verdict.PASS, List.of(), Map.of("generationRunId", "run-gcp-123")),
                                new LayerVerdict("runtime", Verdict.PASS, List.of(), Map.of("composerDagState", "success", "dataprocState", "SUCCEEDED")),
                                new LayerVerdict("data", Verdict.PASS, List.of(), Map.of("oracleComparison", "matched"))
                        ),
                        List.of("phase4", "gcp_golden", "live_runtime"),
                        "abc-gcp-123",
                        "compose-gcp-sha",
                        0
                )
        );

        assertEquals(Verdict.PASS, result.verdict());
        Map<String, Object> verdict = objectMapper.readValue(result.verdictPath().toFile(), new TypeReference<>() {});
        assertEquals("GCP_COMPOSER_DATAPROC_BRIDGE", verdict.get("runtimeAdapter"));
        assertEquals("gcp", verdict.get("runtimeNamespace"));

        Map<String, Object> evidenceIndex = objectMapper.readValue(
                tempDir.resolve("evidence/gcp/evidence-index.json").toFile(),
                new TypeReference<>() {}
        );
        assertEquals("GCP_COMPOSER_DATAPROC_BRIDGE", evidenceIndex.get("runtimeAdapter"));
        assertEquals("gcp", evidenceIndex.get("runtimeNamespace"));
        assertTrue(((List<Map<String, Object>>) evidenceIndex.get("artifacts")).stream()
                .anyMatch(artifact -> "runtime/dataproc-submit-request.json".equals(artifact.get("contractPath"))));
        assertTrue(((List<Map<String, Object>>) evidenceIndex.get("artifacts")).stream()
                .anyMatch(artifact -> "logs/composer/trigger.log".equals(artifact.get("contractPath"))));
    }

    @Test
    void validate_returnsDeterministicFailureCodes_whenRequiredEvidenceIsMissing() throws Exception {
        Path inputs = tempDir.resolve("fail-inputs");
        Files.createDirectories(inputs);
        Path dagState = writeJson(inputs.resolve("dag-state.json"), Map.of("state", "failed"));

        ScenarioDsl.ScenarioDefinition scenario = new ScenarioDsl.ScenarioDefinition(
                "loan-master-live-runtime-fail",
                "Loan Master Live Runtime Failure",
                ScenarioDsl.ProofMode.LIVE_RUNTIME,
                ScenarioDsl.RuntimeAdapter.LOCAL_AIRFLOW_BRIDGE,
                List.of("phase3", "loan_master", "live_runtime"),
                new ScenarioDsl.BuilderPlan("tenant-runtime", "servicing", "loan_master", List.of("FileIngestion"), "loan_master"),
                new ScenarioDsl.EvidenceExpectation(
                        List.of("dag-state.json", "dbt-run.json", "verdict.json", "evidence-index.json"),
                        List.of("AIRFLOW_DAG_STATE", "DBT_RUN_SUMMARY", "VERDICT", "EVIDENCE_INDEX"),
                        "verdict.json",
                        List.of("runtime")
                ),
                Map.of()
        );

        LiveRuntimeScenarioValidator validator = new LiveRuntimeScenarioValidator(objectMapper);
        LiveRuntimeScenarioValidator.ValidationResult result = validator.validate(
                new LiveRuntimeScenarioValidator.RuntimeValidationRequest(
                        scenario,
                        "run-456",
                        null,
                        tempDir.resolve("fail-evidence"),
                        List.of(
                                artifact("dag-state", "AIRFLOW_DAG_STATE", dagState),
                                artifact("dbt-run", "DBT_RUN_SUMMARY", inputs.resolve("missing-dbt-run.json"))
                        ),
                        List.of(
                                new LayerVerdict("runtime", Verdict.FAIL, List.of("airflow_dag_failed"), Map.of("dagState", "failed"))
                        ),
                        List.of("phase3", "loan_master", "live_runtime"),
                        "def5678",
                        "compose-sha-2",
                        1
                )
        );

        assertEquals(Verdict.FAIL, result.verdict());
        assertTrue(result.failureCodes().contains("missing_artifact_file:dbt-run"));
        assertTrue(result.failureCodes().contains("missing_required_artifact:dbt-run-json"));
        assertTrue(result.failureCodes().contains("missing_required_evidence_type:dbt-run-summary"));
        assertTrue(result.failureCodes().contains("layer_failed:runtime"));
        assertTrue(result.failureCodes().contains("airflow_dag_failed"));
    }

    @Test
    void validate_failsWhenExpectedCoverageTagsAreMissing() throws Exception {
        Path inputs = tempDir.resolve("coverage-gap-inputs");
        Files.createDirectories(inputs);
        Path dagState = writeJson(inputs.resolve("dag-state.json"), Map.of("state", "success"));
        Path runtimeLog = writeText(inputs.resolve("runtime.log"), "runtime ok\n");

        ScenarioDsl.ScenarioDefinition scenario = new ScenarioDsl.ScenarioDefinition(
                "loan-master-live-runtime-gap",
                "Loan Master Live Runtime Gap",
                ScenarioDsl.ProofMode.LIVE_RUNTIME,
                ScenarioDsl.RuntimeAdapter.LOCAL_AIRFLOW_BRIDGE,
                List.of("phase3", "loan_master", "live_runtime", "spark"),
                new ScenarioDsl.BuilderPlan("tenant-runtime", "servicing", "loan_master", List.of("FileIngestion"), "loan_master"),
                new ScenarioDsl.EvidenceExpectation(
                        List.of("dag-state.json", "coverage.json", "verdict.json", "evidence-index.json"),
                        List.of("AIRFLOW_DAG_STATE", "RUNTIME_LOG", "COVERAGE", "VERDICT", "EVIDENCE_INDEX"),
                        "verdict.json",
                        List.of("runtime")
                ),
                Map.of()
        );

        LiveRuntimeScenarioValidator validator = new LiveRuntimeScenarioValidator(objectMapper);
        LiveRuntimeScenarioValidator.ValidationResult result = validator.validate(
                new LiveRuntimeScenarioValidator.RuntimeValidationRequest(
                        scenario,
                        "run-789",
                        "loan-master-gap",
                        tempDir.resolve("coverage-gap-evidence"),
                        List.of(
                                artifact("dag-state", "AIRFLOW_DAG_STATE", dagState),
                                artifact("runtime-log", "RUNTIME_LOG", runtimeLog)
                        ),
                        List.of(
                                new LayerVerdict("runtime", Verdict.PASS, List.of(), Map.of("dagState", "success"))
                        ),
                        List.of("phase3", "loan_master"),
                        "ghi9012",
                        "compose-sha-3",
                        0
                )
        );

        assertEquals(Verdict.FAIL, result.verdict());
        assertTrue(result.failureCodes().contains("missing_coverage_tag:live-runtime"));
        assertTrue(result.failureCodes().contains("missing_coverage_tag:spark"));

        Map<String, Object> coverage = objectMapper.readValue(result.coveragePath().toFile(), new TypeReference<>() {});
        assertEquals(List.of("live_runtime", "spark"), coverage.get("missingTags"));
        assertEquals(List.of(), coverage.get("unexpectedTags"));
        assertEquals(2, coverage.get("observedTagCount"));
        assertEquals("FAIL", coverage.get("coverageVerdict"));
        assertEquals(2, coverage.get("missingTagCount"));
    }

    @Test
    void validate_rejectsEvidenceRootsWhoseNamespaceConflictsWithRuntimeAdapter() {
        ScenarioDsl.ScenarioDefinition scenario = new ScenarioDsl.ScenarioDefinition(
                "loan-master-live-runtime-conflict",
                "Loan Master Live Runtime Conflict",
                ScenarioDsl.ProofMode.LIVE_RUNTIME,
                ScenarioDsl.RuntimeAdapter.LOCAL_AIRFLOW_BRIDGE,
                List.of("phase3", "loan_master", "live_runtime"),
                new ScenarioDsl.BuilderPlan("tenant-runtime", "servicing", "loan_master", List.of("FileIngestion"), "loan_master"),
                new ScenarioDsl.EvidenceExpectation(List.of("verdict.json", "evidence-index.json"), List.of("VERDICT", "EVIDENCE_INDEX"), "verdict.json", List.of()),
                Map.of()
        );

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new LiveRuntimeScenarioValidator(objectMapper).validate(
                        new LiveRuntimeScenarioValidator.RuntimeValidationRequest(
                                scenario,
                                "run-conflict",
                                null,
                                tempDir.resolve("evidence/gcp"),
                                List.of(),
                                List.of(),
                                List.of("phase3", "loan_master", "live_runtime"),
                                "sha-conflict",
                                "compose-conflict",
                                0
                        )
                ));

        assertTrue(error.getMessage().contains("runtime adapter"));
    }

    private LiveRuntimeScenarioValidator.ArtifactCandidate artifact(String artifactId, String type, Path path) {
        return artifact(artifactId, type, path, Map.of());
    }

    private LiveRuntimeScenarioValidator.ArtifactCandidate artifact(String artifactId, String type, Path path, Map<String, Object> metadata) {
        return new LiveRuntimeScenarioValidator.ArtifactCandidate(
                artifactId,
                type,
                path,
                "test-adapter",
                "test-run",
                metadata
        );
    }

    private Path writeJson(Path path, Map<String, Object> payload) throws Exception {
        Files.createDirectories(path.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), payload);
        return path;
    }

    private Path writeText(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }
}
