package com.pulse.e2e.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.codegen.model.GenerationRun;
import com.pulse.codegen.repository.GeneratedArtifactRepository;
import com.pulse.codegen.service.CodeGenerationService;
import com.pulse.e2e.contract.EvidenceContracts.LayerVerdict;
import com.pulse.e2e.contract.EvidenceContracts.Verdict;
import com.pulse.e2e.contract.ScenarioDsl;
import com.pulse.e2e.support.RepresentativePipelineFixtureFactory;
import com.pulse.e2e.validation.LiveRuntimeScenarioValidator;
import com.pulse.git.model.GitRepo;
import com.pulse.git.repository.GitRepoRepository;
import com.pulse.git.service.LocalGitService;
import com.pulse.git.service.RepoScaffoldService;
import com.pulse.pipeline.repository.PipelineRepository;
import com.pulse.pipeline.repository.PipelineVersionRepository;
import com.pulse.pipeline.service.CompositionService;
import com.pulse.sor.repository.ConnectorDefinitionRepository;
import com.pulse.sor.repository.ConnectorInstanceRepository;
import com.pulse.sor.repository.CredentialProfileRepository;
import com.pulse.sor.repository.DomainRepository;
import com.pulse.sor.repository.SystemOfRecordRepository;
import com.pulse.blueprint.repository.BlueprintRepository;
import com.pulse.storage.model.StorageBackend;
import com.pulse.storage.repository.StorageBackendRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Tag("runtime")
class CanonicalLoanMasterAirflowRuntimeIT {

    private static final String TENANT_ID = "tenant-home-lending";

    @Autowired private DomainRepository domainRepository;
    @Autowired private PipelineRepository pipelineRepository;
    @Autowired private PipelineVersionRepository pipelineVersionRepository;
    @Autowired private BlueprintRepository blueprintRepository;
    @Autowired private SystemOfRecordRepository systemOfRecordRepository;
    @Autowired private ConnectorDefinitionRepository connectorDefinitionRepository;
    @Autowired private ConnectorInstanceRepository connectorInstanceRepository;
    @Autowired private CredentialProfileRepository credentialProfileRepository;
    @Autowired private StorageBackendRepository storageBackendRepository;
    @Autowired private CompositionService compositionService;
    @Autowired private CodeGenerationService codeGenerationService;
    @Autowired private GeneratedArtifactRepository generatedArtifactRepository;
    @Autowired private GitRepoRepository gitRepoRepository;
    @Autowired private LocalGitService localGitService;
    @Autowired private RepoScaffoldService repoScaffoldService;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void canonicalLoanMasterScenario_materializesDiscoversTriggersAndCapturesRuntimeEvidence() throws Exception {
        Path airflowRepoBase = Path.of("/Users/aameradam/pulse-repos");
        assertTrue(Files.isDirectory(airflowRepoBase), "Expected Airflow-mounted repo base to exist");

        String unique = UUID.randomUUID().toString().substring(0, 8);
        String pipelineName = "Representative Runtime Bridge Proof " + unique;
        String codegenSlug = "representative_runtime_bridge_proof_" + unique;
        String pathPipelineSlug = "representative-runtime-bridge-proof-" + unique;
        String dagId = "pulse_" + codegenSlug + "_v1";
        String executionDate = "2026-04-26T04:00:00+00:00";
        Path runtimeRepoRoot = airflowRepoBase.resolve("omx-task3-" + unique);
        Path evidenceRoot = Path.of("build/e2e-airflow-runtime").resolve(unique);
        boolean retainRuntimeRepo = Boolean.getBoolean("pulse.e2e.retainRuntimeRepo")
                || Boolean.parseBoolean(System.getenv("PULSE_E2E_RETAIN_RUNTIME_REPO"));

        Files.createDirectories(runtimeRepoRoot);
        Files.createDirectories(evidenceRoot);

        try {
            seedLocalDpcStorageBackend();
            stageLoanMasterCsvForPipeline(pathPipelineSlug);

            RepresentativePipelineFixtureFactory fixtureFactory = new RepresentativePipelineFixtureFactory(
                    domainRepository,
                    pipelineRepository,
                    pipelineVersionRepository,
                    blueprintRepository,
                    systemOfRecordRepository,
                    connectorDefinitionRepository,
                    connectorInstanceRepository,
                    credentialProfileRepository,
                    compositionService
            );
            var fixture = fixtureFactory.create(TENANT_ID);
            fixture.pipeline().setName(pipelineName);
            pipelineRepository.save(fixture.pipeline());
            scaffoldTenantRuntimeRepo(runtimeRepoRoot);

            GenerationRun run = codeGenerationService.generate(
                    fixture.pipeline().getId(),
                    fixture.version().getId(),
                    TENANT_ID,
                    "worker-1"
            );
            assertTrue("COMPLETED".equals(run.getStatus()),
                    () -> "Expected generation run to complete, status="
                            + run.getStatus()
                            + ", error="
                            + run.getErrorMessage());
            assertFalse(generatedArtifactRepository.findByGenerationRunIdOrderByFilePathAsc(run.getId()).isEmpty());

            LocalRuntimeBridge bridge = new LocalRuntimeBridge(
                    generatedArtifactRepository,
                    gitRepoRepository,
                    objectMapper
            );
            LocalRuntimeBridge.RuntimeBridgeResult bridgeResult = bridge.materialize(
                    new LocalRuntimeBridge.BridgeRequest(
                            "canonical-loan-master-airflow-runtime",
                            run.getId(),
                            TENANT_ID,
                            String.valueOf(run.getMetadata().get("compile_namespace")),
                            runtimeRepoRoot,
                            evidenceRoot.resolve("bridge")
                    )
            );

            String outputBaseUri = "s3a://pulse-dpc-home-lending-dev-lake/home-lending/servicing/" + pathPipelineSlug;
            LocalRuntimeArtifactRenderer renderer = new LocalRuntimeArtifactRenderer(objectMapper);
            renderer.render(new LocalRuntimeArtifactRenderer.RenderRequest(
                    "canonical-loan-master-airflow-runtime",
                    String.valueOf(run.getMetadata().get("compile_namespace")),
                    bridgeResult.materializedRoot(),
                    evidenceRoot.resolve("render"),
                    outputBaseUri,
                    "/opt/pulse/repo/" + runtimeRepoRoot.getFileName() + "/dbt_project",
                    Map.of("write_current_loans_to_lake", "filter_current_loans")
            ));
            String dagSubdir = "/opt/pulse/repo/" + runtimeRepoRoot.getFileName()
                    + "/" + run.getMetadata().get("compile_namespace")
                    + "/dags/" + codegenSlug + "_dag.py";

            LocalAirflowCliAdapter adapter = new LocalAirflowCliAdapter(objectMapper);
            boolean dagDiscovered = adapter.awaitDagExists(dagId, Duration.ofSeconds(90), Duration.ofSeconds(3));
            if (!dagDiscovered) {
                List<Map<String, Object>> importErrors = adapter.listImportErrors();
                var errorEvidence = adapter.writeImportErrorEvidence(
                        "canonical-loan-master-airflow-runtime",
                        evidenceRoot.resolve("airflow-import-errors"),
                        dagId,
                        importErrors
                );
                Path importErrorPacket = evidenceRoot.resolve("airflow-import-errors").resolve("airflow-import-errors.json");
                assertTrue(Files.exists(importErrorPacket));
                assertFalse(errorEvidence.artifacts().isEmpty());
                assertTrue(false, "Airflow did not discover the generated DAG; import-error evidence captured at " + importErrorPacket);
            }
            adapter.reserializeDag(dagSubdir);
            adapter.unpauseDag(dagId, dagSubdir);

            var handle = adapter.triggerDag(
                    dagId,
                    executionDate,
                    Map.of(
                            "scenario_id", "canonical-loan-master-airflow-runtime",
                            "generation_run_id", run.getId()
                    ),
                    dagSubdir
            );
            assertNotNull(handle.runId());

            var status = adapter.awaitTerminalState(dagId, executionDate, Duration.ofSeconds(120), Duration.ofSeconds(5), dagSubdir);
            List<Map<String, Object>> taskStates = adapter.taskStatesForDagRun(dagId, executionDate, dagSubdir);
            List<Map<String, Object>> taskLogs = adapter.taskLogsForDagRun(dagId, handle.runId());
            var evidence = adapter.writeEvidence(
                    "canonical-loan-master-airflow-runtime",
                    evidenceRoot.resolve("airflow"),
                    handle,
                    status,
                    taskStates,
                    taskLogs
            );

            Path evidencePacket = evidenceRoot.resolve("airflow").resolve("airflow-runtime.json");
            assertTrue(Files.exists(evidencePacket));

            JsonNode json = objectMapper.readTree(evidencePacket.toFile());
            assertTrue(json.has("state"));
            assertTrue(json.has("taskStates"));
            assertTrue(json.has("taskLogs"));
            assertFalse(evidence.artifacts().isEmpty());
            assertTrue("success".equals(status.state()),
                    "Expected successful live DAG run; evidence captured at " + evidencePacket
                            + " with state " + status.state());

            Path oraclePath = Path.of("src/test/resources/e2e/oracle/loan_master/data-oracle.json");
            Path fixtureManifestPath = Path.of("src/test/resources/e2e/fixtures/loan_master/fixture-manifest.json");
            Path projectedOutput = runtimeRepoRoot.resolve("validation-output/current-loans-csv");
            String goldOutputUri = "s3a://pulse-dpc-home-lending-dev-lake/servicing/loan-source/"
                    + pathPipelineSlug + "/gold/write-current-loans-to-lake/";
            String containerRepoRoot = "/opt/pulse/repo/" + runtimeRepoRoot.getFileName();
            Path exportScript = runtimeRepoRoot.resolve("validation-output/export_current_loans.py");
            writeDeltaProjectionScript(
                    exportScript,
                    codegenSlug,
                    goldOutputUri,
                    containerRepoRoot + "/validation-output/current-loans-csv",
                    oracleColumns(oraclePath),
                    oracleColumnsByType(oraclePath, "boolean")
            );
            runCommand(List.of(
                    "docker", "exec", "pulse-airflow-1", "bash", "-lc",
                    "/home/airflow/.local/bin/python "
                            + containerRepoRoot + "/validation-output/export_current_loans.py"
            ));

            var outputValidation = new MinioOutputOracleValidator(objectMapper).validate(
                    new MinioOutputOracleValidator.ProbeRequest(
                            "canonical-loan-master-airflow-runtime",
                            run.getId(),
                            oraclePath,
                            projectedOutput.toUri().toString(),
                            null,
                            evidenceRoot.resolve("data"),
                            currentLoansOracleOverrides(fixtureManifestPath)
                    )
            );
            assertEquals("PASS", outputValidation.verdict(),
                    "Expected current-loans output to match data oracle; failures="
                            + outputValidation.failureCodes()
                            + ", evidence="
                            + evidenceRoot.resolve("data/data-oracle-comparison.json"));

            ScenarioDsl.ScenarioDefinition liveRuntimeScenario = new ScenarioDsl.ScenarioDefinition(
                    "canonical-loan-master-airflow-runtime",
                    "Canonical loan_master live runtime",
                    ScenarioDsl.ProofMode.LIVE_RUNTIME,
                    ScenarioDsl.RuntimeAdapter.LOCAL_AIRFLOW_BRIDGE,
                    List.of("phase3", "loan_master", "live_runtime", "airflow", "spark", "dbt", "gx", "minio"),
                    new ScenarioDsl.BuilderPlan(
                            TENANT_ID,
                            "servicing",
                            "loan_master",
                            List.of("FileIngestion", "GenericFilter", "LakeWriter"),
                            "loan_master"
                    ),
                    new ScenarioDsl.EvidenceExpectation(
                            List.of(
                                    "runtime-bridge.json",
                                    "runtime-render.json",
                                    "airflow-dag-state.json",
                                    "airflow-task-states.json",
                                    "airflow-task-logs.json",
                                    "airflow-runtime.json",
                                    "minio-output-probe.json",
                                    "data-oracle-comparison.json",
                                    "scenario-catalog.json",
                                    "scenario-coverage-plan.json",
                                    "coverage.json",
                                    "verdict.json",
                                    "evidence-index.json"
                            ),
                            List.of(
                                    "RUNTIME_BRIDGE_PACKET",
                                    "RUNTIME_RENDER_PACKET",
                                    "AIRFLOW_DAG_STATE",
                                    "AIRFLOW_TASK_STATE",
                                    "AIRFLOW_TASK_LOG",
                                    "AIRFLOW_RUNTIME_PACKET",
                                    "MINIO_OUTPUT_PROBE",
                                    "DATA_ORACLE_COMPARISON",
                                    "SCENARIO_CATALOG",
                                    "SCENARIO_COVERAGE_PLAN",
                                    "COVERAGE",
                                    "VERDICT",
                                    "EVIDENCE_INDEX"
                            ),
                            "verdict.json",
                            List.of("build", "runtime", "data")
                    ),
                    Map.of(
                            "fixture_manifest", "e2e/fixtures/loan_master/fixture-manifest.json",
                            "data_oracle", "e2e/oracle/loan_master/data-oracle.json"
                    )
            );
            List<LiveRuntimeScenarioValidator.ArtifactCandidate> validatorArtifacts = artifactCandidates(
                    evidenceRoot.resolve("bridge/runtime-bridge.json"), "runtime-bridge", "RUNTIME_BRIDGE_PACKET",
                    evidenceRoot.resolve("render/runtime-render.json"), "runtime-render", "RUNTIME_RENDER_PACKET",
                    evidenceRoot.resolve("airflow/airflow-dag-state.json"), "airflow-dag-state", "AIRFLOW_DAG_STATE",
                    evidenceRoot.resolve("airflow/airflow-task-states.json"), "airflow-task-state", "AIRFLOW_TASK_STATE",
                    evidenceRoot.resolve("airflow/airflow-task-logs.json"), "airflow-task-log", "AIRFLOW_TASK_LOG",
                    evidenceRoot.resolve("airflow/airflow-runtime.json"), "airflow-runtime", "AIRFLOW_RUNTIME_PACKET",
                    evidenceRoot.resolve("data/minio-output-probe.json"), "minio-output-probe", "MINIO_OUTPUT_PROBE",
                    evidenceRoot.resolve("data/data-oracle-comparison.json"), "data-oracle-comparison", "DATA_ORACLE_COMPARISON"
            );
            LiveRuntimeScenarioValidator.ValidationResult validation = new LiveRuntimeScenarioValidator(objectMapper).validate(
                    new LiveRuntimeScenarioValidator.RuntimeValidationRequest(
                            liveRuntimeScenario,
                            run.getId(),
                            pathPipelineSlug,
                            evidenceRoot.resolve("verdict"),
                            validatorArtifacts,
                            List.of(
                                    new LayerVerdict("build", Verdict.PASS, List.of(), Map.of(
                                            "generationRunId", run.getId(),
                                            "compileNamespace", String.valueOf(run.getMetadata().get("compile_namespace"))
                                    )),
                                    new LayerVerdict("runtime", Verdict.PASS, List.of(), Map.of(
                                            "dagId", dagId,
                                            "state", status.state(),
                                            "taskCount", taskStates.size()
                                    )),
                                    new LayerVerdict("data", Verdict.PASS, List.of(), Map.of(
                                            "oracleVerdict", outputValidation.verdict(),
                                            "materializedCsvCount", outputValidation.materializedCsvFiles().size()
                                    ))
                            ),
                            List.of("phase3", "loan_master", "live_runtime", "airflow", "spark", "dbt", "gx", "minio"),
                            localGitService.getHeadSha(runtimeRepoRoot.toString()),
                            dagId,
                            0
                    )
            );
            assertEquals(Verdict.PASS, validation.verdict());
            assertTrue(Files.exists(evidenceRoot.resolve("verdict/verdict.json")));
            assertTrue(Files.exists(evidenceRoot.resolve("verdict/coverage.json")));
            assertTrue(Files.exists(evidenceRoot.resolve("verdict/scenario-catalog.json")));
            assertTrue(Files.exists(evidenceRoot.resolve("verdict/scenario-coverage-plan.json")));
            assertTrue(Files.exists(evidenceRoot.resolve("verdict/evidence-index.json")));
        } finally {
            if (!retainRuntimeRepo) {
                deleteRecursively(runtimeRepoRoot);
            }
        }
    }

    private void seedLocalDpcStorageBackend() {
        StorageBackend storageBackend = new StorageBackend();
        storageBackend.setId("stg-proof-dpc-dev");
        storageBackend.setTenantId(TENANT_ID);
        storageBackend.setEnvironment("dev");
        storageBackend.setBackend("DPC");
        storageBackend.setStorageRootFiles("pulse-dpc-home-lending-dev-files");
        storageBackend.setStorageRootLake("pulse-dpc-home-lending-dev-lake");
        storageBackend.setDpcScheme("s3a");
        storageBackend.setDpcCluster("pulse-local-spark");
        storageBackend.setProvisioningStatus("validated");
        storageBackendRepository.save(storageBackend);
    }

    private void stageLoanMasterCsvForPipeline(String pipelineSlug) throws Exception {
        Path loanMasterCsv = Path.of(System.getProperty(
                "pulse.e2e.loanMasterCsv",
                "/Users/aameradam/projects/dev/PULSE/data/loan_master.csv"
        ));
        assertTrue(Files.isRegularFile(loanMasterCsv), "Expected canonical loan_master.csv at " + loanMasterCsv);

        runCommand(List.of("docker", "cp", loanMasterCsv.toString(), "pulse-minio-1:/tmp/loan_master.csv"));

        String objectPath = "pulse-dpc-home-lending-dev-files/servicing/loan-source/"
                + pipelineSlug + "/SRC/loan_master.csv";
        runCommand(List.of(
                "docker", "exec", "pulse-minio-1", "sh", "-lc",
                "mc alias set local http://localhost:9000 minioadmin minioadmin >/dev/null"
                        + " && mc mb --ignore-existing local/pulse-dpc-home-lending-dev-files >/dev/null"
                        + " && mc cp /tmp/loan_master.csv local/" + objectPath
        ));
    }

    private String runCommand(List<String> command) throws Exception {
        Process process = new ProcessBuilder(new ArrayList<>(command))
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("Command failed (" + exitCode + "): "
                    + String.join(" ", command) + "\n" + output);
        }
        return output;
    }

    private List<String> oracleColumns(Path oraclePath) throws Exception {
        JsonNode root = objectMapper.readTree(oraclePath.toFile());
        List<String> columns = new ArrayList<>();
        for (JsonNode column : root.path("schema").path("columns")) {
            columns.add(column.path("name").asText());
        }
        return columns;
    }

    private List<String> oracleColumnsByType(Path oraclePath, String type) throws Exception {
        JsonNode root = objectMapper.readTree(oraclePath.toFile());
        List<String> columns = new ArrayList<>();
        for (JsonNode column : root.path("schema").path("columns")) {
            if (type.equals(column.path("type").asText())) {
                columns.add(column.path("name").asText());
            }
        }
        return columns;
    }

    private Map<String, Object> currentLoansOracleOverrides(Path fixtureManifestPath) throws Exception {
        JsonNode root = objectMapper.readTree(fixtureManifestPath.toFile());
        for (JsonNode derivative : root.path("derivatives")) {
            if (!"current_loans".equals(derivative.path("derivative_id").asText())) {
                continue;
            }
            Map<String, Object> overrides = new LinkedHashMap<>();
            overrides.put("row_count", derivative.path("row_count").asInt());
            overrides.put("column_count", derivative.path("column_count").asInt());
            overrides.put("canonical_csv_sha256", derivative.path("canonical_csv_sha256").asText());
            overrides.put("schema_signature", derivative.path("schema_signature").asText());
            overrides.put("required_field_nulls", objectMapper.convertValue(
                    derivative.path("required_field_nulls"),
                    new TypeReference<Map<String, Object>>() {}
            ));
            return overrides;
        }
        throw new IllegalStateException("fixture manifest missing current_loans derivative: " + fixtureManifestPath);
    }

    private void writeDeltaProjectionScript(Path scriptPath,
                                            String codegenSlug,
                                            String deltaOutputUri,
                                            String containerCsvOutputPath,
                                            List<String> columns,
                                            List<String> booleanColumns) throws Exception {
        Files.createDirectories(scriptPath.getParent());
        String columnList = columns.stream()
                .map(column -> "'" + pyString(column) + "'")
                .collect(Collectors.joining(", "));
        String booleanColumnList = booleanColumns.stream()
                .map(column -> "'" + pyString(column) + "'")
                .collect(Collectors.joining(", "));
        String script = """
                from pyspark.sql import SparkSession
                from pyspark.sql import functions as F

                spark = SparkSession.builder \\
                    .appName('%s_output_oracle_probe') \\
                    .config('spark.sql.extensions', 'io.delta.sql.DeltaSparkSessionExtension') \\
                    .config('spark.sql.catalog.spark_catalog', 'org.apache.spark.sql.delta.catalog.DeltaCatalog') \\
                    .getOrCreate()

                columns = [%s]
                boolean_columns = [%s]
                df = spark.read.format('delta').load('%s')
                for boolean_column in boolean_columns:
                    df = df.withColumn(
                        boolean_column,
                        F.when(F.lower(F.col(boolean_column).cast('string')) == F.lit('true'), F.lit('True'))
                         .when(F.lower(F.col(boolean_column).cast('string')) == F.lit('false'), F.lit('False'))
                         .otherwise(F.lit(None))
                    )
                df.select(*columns) \\
                    .orderBy('loan_id') \\
                    .coalesce(1) \\
                    .write \\
                    .mode('overwrite') \\
                    .option('header', 'true') \\
                    .csv('%s')
                spark.stop()
                """.formatted(
                pyString(codegenSlug),
                columnList,
                booleanColumnList,
                pyString(deltaOutputUri),
                pyString(containerCsvOutputPath)
        );
        Files.writeString(scriptPath, script);
    }

    private String pyString(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    private void scaffoldTenantRuntimeRepo(Path runtimeRepoRoot) {
        localGitService.initRepo(runtimeRepoRoot.toString(), "main");

        GitRepo repo = new GitRepo();
        repo.setId("git-" + runtimeRepoRoot.getFileName());
        repo.setTenantId(TENANT_ID);
        repo.setScope("TENANT");
        repo.setRepoType("LOCAL");
        repo.setProvider("LOCAL");
        repo.setRepoUrl("file://" + runtimeRepoRoot);
        repo.setDefaultBranch("main");
        repo.setLocalPath(runtimeRepoRoot.toString());
        repo = gitRepoRepository.saveAndFlush(repo);

        localGitService.checkoutBranch(repo.getLocalPath(), repo.getDefaultBranch());
        repo.setCurrentBranch(localGitService.getCurrentBranch(repo.getLocalPath()));
        gitRepoRepository.saveAndFlush(repo);

        repoScaffoldService.scaffold(TENANT_ID);
    }

    private void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception ignored) {
                        }
                    });
        }
    }

    private List<LiveRuntimeScenarioValidator.ArtifactCandidate> artifactCandidates(Object... triples) {
        if (triples.length % 3 != 0) {
            throw new IllegalArgumentException("artifact candidates require path/id/type triples");
        }
        List<LiveRuntimeScenarioValidator.ArtifactCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < triples.length; i += 3) {
            Path path = (Path) triples[i];
            String artifactId = (String) triples[i + 1];
            String type = (String) triples[i + 2];
            candidates.add(new LiveRuntimeScenarioValidator.ArtifactCandidate(
                    artifactId,
                    type,
                    path,
                    "canonical-loan-master-airflow-runtime",
                    "test-run",
                    Map.of()
            ));
        }
        return candidates.stream()
                .sorted(Comparator.comparing(candidate -> candidate.path().toString()))
                .toList();
    }
}
