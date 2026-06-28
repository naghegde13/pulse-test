package com.pulse.e2e.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.blueprint.model.Blueprint;
import com.pulse.blueprint.model.BlueprintCategory;
import com.pulse.blueprint.repository.BlueprintRepository;
import com.pulse.codegen.model.GenerationRun;
import com.pulse.codegen.repository.GeneratedArtifactRepository;
import com.pulse.codegen.service.CodeGenerationService;
import com.pulse.common.text.Slugify;
import com.pulse.e2e.LoanMasterFixture;
import com.pulse.git.repository.GitRepoRepository;
import com.pulse.pipeline.model.Pipeline;
import com.pulse.pipeline.model.PipelineStage;
import com.pulse.pipeline.model.PipelineVersion;
import com.pulse.pipeline.repository.PipelineRepository;
import com.pulse.pipeline.repository.PipelineVersionRepository;
import com.pulse.pipeline.service.CompositionService;
import com.pulse.sor.model.ConnectorDefinition;
import com.pulse.sor.model.ConnectorInstance;
import com.pulse.sor.model.ConnectorType;
import com.pulse.sor.model.CredentialProfile;
import com.pulse.sor.model.CredentialStatus;
import com.pulse.sor.model.Domain;
import com.pulse.sor.model.ReleaseStage;
import com.pulse.sor.model.SystemOfRecord;
import com.pulse.sor.repository.ConnectorDefinitionRepository;
import com.pulse.sor.repository.ConnectorInstanceRepository;
import com.pulse.sor.repository.CredentialProfileRepository;
import com.pulse.sor.repository.DomainRepository;
import com.pulse.sor.repository.SystemOfRecordRepository;
import com.pulse.storage.model.StorageBackend;
import com.pulse.storage.repository.StorageBackendRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
class AggregateBlueprintLiveRuntimeProofIT {

    private static final String SOR_NAME = "Loan Source";
    private static final String SOR_SLUG = "loan-source";
    private static final String STORAGE_BACKEND = "DPC";
    private static final String STORAGE_ROOT_FILES = "pulse-dpc-home-lending-dev-files";
    private static final String STORAGE_ROOT_LAKE = "pulse-dpc-home-lending-dev-lake";

    @Autowired private DomainRepository domainRepository;
    @Autowired private PipelineRepository pipelineRepository;
    @Autowired private PipelineVersionRepository pipelineVersionRepository;
    @Autowired private BlueprintRepository blueprintRepository;
    @Autowired private SystemOfRecordRepository systemOfRecordRepository;
    @Autowired private ConnectorDefinitionRepository connectorDefinitionRepository;
    @Autowired private ConnectorInstanceRepository connectorInstanceRepository;
    @Autowired private CredentialProfileRepository credentialProfileRepository;
    @Autowired private CompositionService compositionService;
    @Autowired private CodeGenerationService codeGenerationService;
    @Autowired private GeneratedArtifactRepository generatedArtifactRepository;
    @Autowired private GitRepoRepository gitRepoRepository;
    @Autowired private StorageBackendRepository storageBackendRepository;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void genericAggregate_passesLiveRuntimeOracleProof() throws Exception {
        ProofResult result = runAggregateProof(
                "generic-aggregate",
                "Generic Aggregate Runtime Proof",
                "GenericAggregate",
                List.of("property_state", "loan_status"),
                List.of(Map.of("column", "current_upb", "function", "sum", "alias", "total_current_upb")),
                "aggregated_output"
        );

        assertEquals("success", result.airflowState());
        assertEquals("PASS", result.oracleVerdict());
        assertTrue(result.oracleFailureCodes().isEmpty(), () -> "unexpected oracle failures: " + result.oracleFailureCodes());
        assertTrue(Files.exists(result.evidenceRoot().resolve("data/minio-output-probe.json")));
        assertTrue(Files.exists(result.evidenceRoot().resolve("data/data-oracle-comparison.json")));
    }

    @Test
    void schemaNormalization_passesLiveRuntimeOracleProof() throws Exception {
        ProofResult result = runSchemaNormalizationProof();

        assertEquals("success", result.airflowState());
        assertEquals("PASS", result.oracleVerdict());
        assertTrue(result.oracleFailureCodes().isEmpty(), () -> "unexpected oracle failures: " + result.oracleFailureCodes());
        assertTrue(Files.exists(result.evidenceRoot().resolve("data/minio-output-probe.json")));
        assertTrue(Files.exists(result.evidenceRoot().resolve("data/data-oracle-comparison.json")));
    }

    @Test
    void bronzeToSilverCleaning_passesLiveRuntimeOracleProof() throws Exception {
        ProofResult result = runAggregateProof(
                "bronze-to-silver-cleaning",
                "Bronze To Silver Cleaning Runtime Proof",
                "BronzeToSilverCleaning",
                List.of(),
                List.of(),
                "cleaned_output"
        );

        assertEquals("success", result.airflowState());
        assertEquals("PASS", result.oracleVerdict());
        assertTrue(result.oracleFailureCodes().isEmpty(), () -> "unexpected oracle failures: " + result.oracleFailureCodes());
        assertTrue(Files.exists(result.evidenceRoot().resolve("data/minio-output-probe.json")));
        assertTrue(Files.exists(result.evidenceRoot().resolve("data/data-oracle-comparison.json")));
    }

    @Test
    void genericJoin_passesLiveRuntimeOracleProof() throws Exception {
        ProofResult result = runAggregateProof(
                "generic-join",
                "Generic Join Runtime Proof",
                "GenericJoin",
                List.of(),
                List.of(),
                "joined_output"
        );

        assertEquals("success", result.airflowState());
        assertEquals("PASS", result.oracleVerdict());
        assertTrue(result.oracleFailureCodes().isEmpty(), () -> "unexpected oracle failures: " + result.oracleFailureCodes());
        assertTrue(Files.exists(result.evidenceRoot().resolve("data/minio-output-probe.json")));
        assertTrue(Files.exists(result.evidenceRoot().resolve("data/data-oracle-comparison.json")));
    }

    @Test
    void piiMasking_passesLiveRuntimeOracleProof() throws Exception {
        ProofResult result = runAggregateProof(
                "pii-masking",
                "PII Masking Runtime Proof",
                "PIIMasking",
                List.of(),
                List.of(),
                "masked_output"
        );

        assertEquals("success", result.airflowState());
        assertEquals("PASS", result.oracleVerdict());
        assertTrue(result.oracleFailureCodes().isEmpty(), () -> "unexpected oracle failures: " + result.oracleFailureCodes());
        assertTrue(Files.exists(result.evidenceRoot().resolve("data/minio-output-probe.json")));
        assertTrue(Files.exists(result.evidenceRoot().resolve("data/data-oracle-comparison.json")));
    }

    @Test
    void aggregateMaterialization_passesLiveRuntimeOracleProof() throws Exception {
        ProofResult result = runAggregateProof(
                "aggregate-materialization",
                "Aggregate Materialization Runtime Proof",
                "AggregateMaterialization",
                List.of("property_state", "loan_status"),
                List.of(Map.of("column", "current_upb", "function", "sum", "alias", "total_current_upb")),
                "aggregate_output"
        );

        assertEquals("success", result.airflowState());
        assertEquals("PASS", result.oracleVerdict());
        assertTrue(result.oracleFailureCodes().isEmpty(), () -> "unexpected oracle failures: " + result.oracleFailureCodes());
        assertTrue(Files.exists(result.evidenceRoot().resolve("data/minio-output-probe.json")));
        assertTrue(Files.exists(result.evidenceRoot().resolve("data/data-oracle-comparison.json")));
    }

    @Test
    void featureTablePublish_passesLiveRuntimeOracleProof() throws Exception {
        ProofResult result = runAggregateProof(
                "feature-table-publish",
                "Feature Table Publish Runtime Proof",
                "FeatureTablePublish",
                List.of(),
                List.of(),
                "feature_output"
        );

        assertEquals("success", result.airflowState(), () -> "dbt log tail:\n" + result.dbtLogTail());
        assertEquals("PASS", result.oracleVerdict(), () -> "oracle failures: " + result.oracleFailureCodes());
        assertTrue(result.oracleFailureCodes().isEmpty(), () -> "unexpected oracle failures: " + result.oracleFailureCodes());
        assertTrue(Files.exists(result.evidenceRoot().resolve("data/minio-output-probe.json")));
        assertTrue(Files.exists(result.evidenceRoot().resolve("data/data-oracle-comparison.json")));
    }

    @Test
    void referenceDataPublish_passesLiveRuntimeOracleProof() throws Exception {
        ProofResult result = runAggregateProof(
                "reference-data-publish",
                "Reference Data Publish Runtime Proof",
                "ReferenceDataPublish",
                List.of(),
                List.of(),
                "published_reference"
        );

        assertEquals("success", result.airflowState(), () -> "dbt log tail:\n" + result.dbtLogTail());
        assertEquals("PASS", result.oracleVerdict(), () -> "oracle failures: " + result.oracleFailureCodes());
        assertTrue(result.oracleFailureCodes().isEmpty(), () -> "unexpected oracle failures: " + result.oracleFailureCodes());
        assertTrue(Files.exists(result.evidenceRoot().resolve("data/minio-output-probe.json")));
        assertTrue(Files.exists(result.evidenceRoot().resolve("data/data-oracle-comparison.json")));
    }

    @Test
    void factBuild_passesLiveRuntimeOracleProof() throws Exception {
        ProofResult result = runAggregateProof(
                "fact-build",
                "Fact Build Runtime Proof",
                "FactBuild",
                List.of(),
                List.of(),
                "fact_output"
        );

        assertEquals("success", result.airflowState(), () -> "dbt log tail:\n" + result.dbtLogTail());
        assertEquals("PASS", result.oracleVerdict(), () -> "oracle failures: " + result.oracleFailureCodes());
        assertTrue(result.oracleFailureCodes().isEmpty(), () -> "unexpected oracle failures: " + result.oracleFailureCodes());
        assertTrue(Files.exists(result.evidenceRoot().resolve("data/minio-output-probe.json")));
        assertTrue(Files.exists(result.evidenceRoot().resolve("data/data-oracle-comparison.json")));
    }

    @Test
    void wideDenormalizedMart_passesLiveRuntimeOracleProof() throws Exception {
        ProofResult result = runAggregateProof(
                "wide-denormalized-mart",
                "Wide Denormalized Mart Runtime Proof",
                "WideDenormalizedMart",
                List.of(),
                List.of(),
                "mart_output"
        );

        assertEquals("success", result.airflowState(), () -> "dbt log tail:\n" + result.dbtLogTail());
        assertEquals("PASS", result.oracleVerdict(), () -> "oracle failures: " + result.oracleFailureCodes());
        assertTrue(result.oracleFailureCodes().isEmpty(), () -> "unexpected oracle failures: " + result.oracleFailureCodes());
        assertTrue(Files.exists(result.evidenceRoot().resolve("data/minio-output-probe.json")));
        assertTrue(Files.exists(result.evidenceRoot().resolve("data/data-oracle-comparison.json")));
    }

    @Test
    void incrementalMerge_passesLiveRuntimeOracleProof() throws Exception {
        ProofResult result = runAggregateProof(
                "incremental-merge",
                "Incremental Merge Runtime Proof",
                "IncrementalMerge",
                List.of(),
                List.of(),
                "merged_output"
        );

        assertEquals("success", result.airflowState(), () -> "dbt log tail:\n" + result.dbtLogTail());
        assertEquals("PASS", result.oracleVerdict(), () -> "oracle failures: " + result.oracleFailureCodes());
        assertTrue(result.oracleFailureCodes().isEmpty(), () -> "unexpected oracle failures: " + result.oracleFailureCodes());
        assertTrue(Files.exists(result.evidenceRoot().resolve("data/minio-output-probe.json")));
        assertTrue(Files.exists(result.evidenceRoot().resolve("data/data-oracle-comparison.json")));
    }

    @Test
    void fileArrivalSensor_passesLiveRuntimeOracleProof() throws Exception {
        ProofResult result = runFileArrivalSensorProof();

        assertEquals("success", result.airflowState(), () -> "dbt log tail:\n" + result.dbtLogTail());
        assertEquals("PASS", result.oracleVerdict(), () -> "oracle failures: " + result.oracleFailureCodes());
        assertTrue(result.oracleFailureCodes().isEmpty(), () -> "unexpected oracle failures: " + result.oracleFailureCodes());
        assertTrue(Files.exists(result.evidenceRoot().resolve("data/minio-output-probe.json")));
        assertTrue(Files.exists(result.evidenceRoot().resolve("data/data-oracle-comparison.json")));
    }

    @Test
    void dedupeAndMerge_passesLiveRuntimeOracleProof() throws Exception {
        ProofResult result = runDedupeProof();

        assertEquals("success", result.airflowState());
        assertEquals("PASS", result.oracleVerdict());
        assertTrue(result.oracleFailureCodes().isEmpty(), () -> "unexpected oracle failures: " + result.oracleFailureCodes());
        assertTrue(Files.exists(result.evidenceRoot().resolve("data/minio-output-probe.json")));
        assertTrue(Files.exists(result.evidenceRoot().resolve("data/data-oracle-comparison.json")));
    }

    @Test
    void genericRouter_passesLiveRuntimeOracleProof() throws Exception {
        ProofResult result = runRouterProof();

        assertEquals("success", result.airflowState());
        assertEquals("PASS", result.oracleVerdict());
        assertTrue(result.oracleFailureCodes().isEmpty(), () -> "unexpected oracle failures: " + result.oracleFailureCodes());
        assertTrue(Files.exists(result.evidenceRoot().resolve("data/minio-output-probe.json")));
        assertTrue(Files.exists(result.evidenceRoot().resolve("data/data-oracle-comparison.json")));
    }

    @Test
    void databaseWriter_passesLiveRuntimeOracleProof() throws Exception {
        ProofResult result = runDatabaseWriterProof();

        assertEquals("success", result.airflowState(), () -> "dbt log tail:\n" + result.dbtLogTail());
        assertEquals("PASS", result.oracleVerdict(), () -> "oracle failures: " + result.oracleFailureCodes());
        assertTrue(result.oracleFailureCodes().isEmpty(), () -> "unexpected oracle failures: " + result.oracleFailureCodes());
        assertTrue(Files.exists(result.evidenceRoot().resolve("data/minio-output-probe.json")));
        assertTrue(Files.exists(result.evidenceRoot().resolve("data/data-oracle-comparison.json")));
        assertTrue(Files.exists(result.evidenceRoot().resolve("data/postgres-output-probe.json")));
        assertTrue(Files.exists(result.evidenceRoot().resolve("data/postgres-data-oracle-comparison.json")));
    }

    @Test
    void dqValidator_passesLiveRuntimeOracleProof() throws Exception {
        ProofResult result = runAggregateProof(
                "dq-validator",
                "DQ Validator Runtime Proof",
                "DQValidator",
                List.of(),
                List.of(),
                "validated_output"
        );

        assertEquals("success", result.airflowState(), () -> "dbt log tail:\n" + result.dbtLogTail());
        assertEquals("PASS", result.oracleVerdict(), () -> "oracle failures: " + result.oracleFailureCodes());
        assertTrue(result.oracleFailureCodes().isEmpty(), () -> "unexpected oracle failures: " + result.oracleFailureCodes());
        assertTrue(Files.exists(result.evidenceRoot().resolve("data/minio-output-probe.json")));
        assertTrue(Files.exists(result.evidenceRoot().resolve("data/data-oracle-comparison.json")));
    }

    @Test
    void freshnessChecks_passesLiveRuntimeOracleProof() throws Exception {
        ProofResult result = runAggregateProof(
                "freshness-checks",
                "Freshness Checks Runtime Proof",
                "FreshnessChecks",
                List.of(),
                List.of(),
                "freshness_result"
        );

        assertEquals("success", result.airflowState(), () -> "dbt log tail:\n" + result.dbtLogTail());
        assertEquals("PASS", result.oracleVerdict(), () -> "oracle failures: " + result.oracleFailureCodes());
        assertTrue(result.oracleFailureCodes().isEmpty(), () -> "unexpected oracle failures: " + result.oracleFailureCodes());
        assertTrue(Files.exists(result.evidenceRoot().resolve("data/minio-output-probe.json")));
        assertTrue(Files.exists(result.evidenceRoot().resolve("data/data-oracle-comparison.json")));
    }

    @Test
    void schemaDriftDetection_passesLiveRuntimeOracleProof() throws Exception {
        ProofResult result = runAggregateProof(
                "schema-drift-detection",
                "Schema Drift Detection Runtime Proof",
                "SchemaDriftDetection",
                List.of(),
                List.of(),
                "drift_report"
        );

        assertEquals("success", result.airflowState(), () -> "dbt log tail:\n" + result.dbtLogTail());
        assertEquals("PASS", result.oracleVerdict(), () -> "oracle failures: " + result.oracleFailureCodes());
        assertTrue(result.oracleFailureCodes().isEmpty(), () -> "unexpected oracle failures: " + result.oracleFailureCodes());
        assertTrue(Files.exists(result.evidenceRoot().resolve("data/minio-output-probe.json")));
        assertTrue(Files.exists(result.evidenceRoot().resolve("data/data-oracle-comparison.json")));
    }

    @Test
    void anomalyDetection_passesLiveRuntimeOracleProof() throws Exception {
        ProofResult result = runAggregateProof(
                "anomaly-detection",
                "Anomaly Detection Runtime Proof",
                "AnomalyDetection",
                List.of(),
                List.of(),
                "anomaly_report"
        );

        assertEquals("success", result.airflowState(), () -> "dbt log tail:\n" + result.dbtLogTail());
        assertEquals("PASS", result.oracleVerdict(), () -> "oracle failures: " + result.oracleFailureCodes());
        assertTrue(result.oracleFailureCodes().isEmpty(), () -> "unexpected oracle failures: " + result.oracleFailureCodes());
        assertTrue(Files.exists(result.evidenceRoot().resolve("data/minio-output-probe.json")));
        assertTrue(Files.exists(result.evidenceRoot().resolve("data/data-oracle-comparison.json")));
    }

    @Test
    void scd2Dimension_passesLiveRuntimeOracleProof() throws Exception {
        ProofResult result = runAggregateProof(
                "scd2-dimension",
                "SCD2 Dimension Runtime Proof",
                "SCD2Dimension",
                List.of(),
                List.of(),
                "scd2_output"
        );

        assertEquals("success", result.airflowState(), () -> "dbt log tail:\n" + result.dbtLogTail());
        assertEquals("PASS", result.oracleVerdict(), () -> "oracle failures: " + result.oracleFailureCodes());
        assertTrue(result.oracleFailureCodes().isEmpty(), () -> "unexpected oracle failures: " + result.oracleFailureCodes());
        assertTrue(Files.exists(result.evidenceRoot().resolve("data/minio-output-probe.json")));
        assertTrue(Files.exists(result.evidenceRoot().resolve("data/data-oracle-comparison.json")));
    }

    @Test
    void snapshotIngestion_passesLiveRuntimeOracleProof() throws Exception {
        ProofResult result = runSnapshotIngestionProof();

        assertEquals("success", result.airflowState(), () -> "dbt log tail:\n" + result.dbtLogTail());
        assertEquals("PASS", result.oracleVerdict(), () -> "oracle failures: " + result.oracleFailureCodes());
        assertTrue(result.oracleFailureCodes().isEmpty(), () -> "unexpected oracle failures: " + result.oracleFailureCodes());
        assertTrue(Files.exists(result.evidenceRoot().resolve("data/minio-output-probe.json")));
        assertTrue(Files.exists(result.evidenceRoot().resolve("data/data-oracle-comparison.json")));
    }

    @Test
    void bulkBackfill_passesLiveRuntimeOracleProof() throws Exception {
        ProofResult result = runBulkBackfillProof();

        assertEquals("success", result.airflowState(), () -> "dbt log tail:\n" + result.dbtLogTail());
        assertEquals("PASS", result.oracleVerdict(), () -> "oracle failures: " + result.oracleFailureCodes());
        assertTrue(result.oracleFailureCodes().isEmpty(), () -> "unexpected oracle failures: " + result.oracleFailureCodes());
        assertTrue(Files.exists(result.evidenceRoot().resolve("data/minio-output-probe.json")));
        assertTrue(Files.exists(result.evidenceRoot().resolve("data/data-oracle-comparison.json")));
    }

    private ProofResult runAggregateProof(String scenarioSlug,
                                          String pipelineBaseName,
                                          String blueprintKey,
                                          List<String> groupByColumns,
                                          List<Map<String, Object>> aggregations,
                                          String outputPort) throws Exception {
        Path airflowRepoBase = Path.of("/Users/aameradam/pulse-repos");
        assertTrue(Files.isDirectory(airflowRepoBase), "Expected Airflow-mounted repo base to exist");

        LoanMasterFixture fixture = LoanMasterFixture.loadCanonical();
        String unique = UUID.randomUUID().toString().substring(0, 8);
        String pipelineName = pipelineBaseName + " " + unique;
        String codegenSlug = underscoreSlug(pipelineName);
        String pathPipelineSlug = Slugify.slugify(codegenSlug);
        String dagId = "pulse_" + codegenSlug + "_v1";
        String executionDate = "2026-04-26T04:00:00+00:00";
        String tenantId = "tenant-e2e-" + scenarioSlug + "-" + unique;
        Path runtimeRepoRoot = airflowRepoBase.resolve("omx-" + scenarioSlug + "-" + unique);
        Path evidenceRoot = Path.of("build/e2e-airflow-runtime").resolve(scenarioSlug + "-" + unique);
        boolean retainRuntimeRepo = Boolean.getBoolean("pulse.e2e.retainRuntimeRepo")
                || Boolean.parseBoolean(System.getenv("PULSE_E2E_RETAIN_RUNTIME_REPO"));

        Files.createDirectories(runtimeRepoRoot);
        Files.createDirectories(evidenceRoot);

        try {
            seedLocalDpcStorageBackend(tenantId);
            seedBlueprints(blueprintKey);
            PipelineFixture fixtureContext = createPipelineFixture(
                    tenantId,
                    pipelineName,
                    blueprintKey,
                    groupByColumns,
                    aggregations,
                    outputPort
            );

            Path sourceFixture = fixture.path();
            if ("PIIMasking".equals(blueprintKey)) {
                sourceFixture = evidenceRoot.resolve("source/loan_master_with_raw_ssn.csv");
                createRawSsnCsv(fixture.path(), sourceFixture);
            } else if ("SchemaDriftDetection".equals(blueprintKey)) {
                sourceFixture = evidenceRoot.resolve("source/loan_master_with_risk_band.csv");
                createRiskBandCsv(fixture.path(), sourceFixture);
            } else if ("AnomalyDetection".equals(blueprintKey)) {
                sourceFixture = evidenceRoot.resolve("source/loan_master_with_anomaly.csv");
                createAnomalyAugmentedCsv(fixture.path(), sourceFixture);
            }
            stageLoanMasterCsvForPipeline(pathPipelineSlug, sourceFixture);
            scaffoldTenantRuntimeRepo(runtimeRepoRoot, tenantId);

            GenerationRun run = codeGenerationService.generate(
                    fixtureContext.pipeline().getId(),
                    fixtureContext.version().getId(),
                    tenantId,
                    "worker-2"
            );
            assertEquals("COMPLETED", run.getStatus(), () -> "generation failed: " + run.getErrorMessage());
            assertFalse(generatedArtifactRepository.findByGenerationRunIdOrderByFilePathAsc(run.getId()).isEmpty());

            LocalRuntimeBridge bridge = new LocalRuntimeBridge(
                    generatedArtifactRepository,
                    gitRepoRepository,
                    objectMapper
            );
            LocalRuntimeBridge.RuntimeBridgeResult bridgeResult = bridge.materialize(
                    new LocalRuntimeBridge.BridgeRequest(
                            scenarioSlug + "-live-runtime",
                            run.getId(),
                            tenantId,
                            String.valueOf(run.getMetadata().get("compile_namespace")),
                            runtimeRepoRoot,
                            evidenceRoot.resolve("bridge")
                    )
            );

            String outputBaseUri = "s3a://" + STORAGE_ROOT_LAKE + "/home-lending/servicing/" + pathPipelineSlug;
            new LocalRuntimeArtifactRenderer(objectMapper).render(new LocalRuntimeArtifactRenderer.RenderRequest(
                    scenarioSlug + "-live-runtime",
                    String.valueOf(run.getMetadata().get("compile_namespace")),
                    bridgeResult.materializedRoot(),
                    evidenceRoot.resolve("render"),
                    outputBaseUri,
                    "/opt/pulse/repo/" + runtimeRepoRoot.getFileName() + "/dbt_project",
                    Map.of("write_loan_master_to_lake", underscoreSlug(defaultNameFor(blueprintKey)))
            ));

            String dagSubdir = "/opt/pulse/repo/" + runtimeRepoRoot.getFileName()
                    + "/" + run.getMetadata().get("compile_namespace")
                    + "/dags/" + codegenSlug + "_dag.py";

            LocalAirflowCliAdapter adapter = new LocalAirflowCliAdapter(objectMapper);
            boolean dagDiscovered = adapter.awaitDagExists(dagId, Duration.ofSeconds(90), Duration.ofSeconds(3));
            if (!dagDiscovered) {
                List<Map<String, Object>> importErrors = adapter.listImportErrors();
                adapter.writeImportErrorEvidence(
                        scenarioSlug + "-live-runtime",
                        evidenceRoot.resolve("airflow-import-errors"),
                        dagId,
                        importErrors
                );
            }
            assertTrue(dagDiscovered, "Airflow did not discover generated DAG for " + blueprintKey);
            adapter.reserializeDag(dagSubdir);
            adapter.unpauseDag(dagId, dagSubdir);

            LocalAirflowCliAdapter.DagRunHandle handle = adapter.triggerDag(
                    dagId,
                    executionDate,
                    Map.of(
                            "scenario_id", scenarioSlug + "-live-runtime",
                            "generation_run_id", run.getId()
                    ),
                    dagSubdir
            );
            assertNotNull(handle.runId());

            LocalAirflowCliAdapter.DagRunStatus status = adapter.awaitTerminalState(
                    dagId,
                    executionDate,
                    Duration.ofSeconds(120),
                    Duration.ofSeconds(5),
                    dagSubdir
            );

            List<Map<String, Object>> taskStates = adapter.taskStatesForDagRun(dagId, executionDate, dagSubdir);
            List<Map<String, Object>> taskLogs = adapter.taskLogsForDagRun(dagId, handle.runId());
            adapter.writeEvidence(
                    scenarioSlug + "-live-runtime",
                    evidenceRoot.resolve("airflow"),
                    handle,
                    status,
                    taskStates,
                    taskLogs
            );

            String dbtLogTail = readTail(runtimeRepoRoot.resolve("dbt_project/logs/dbt.log"), 4000);
            boolean taskChainSucceeded = taskStates.stream()
                    .map(task -> String.valueOf(task.get("state")))
                    .allMatch("success"::equals);
            String effectiveAirflowState = "success".equals(status.state()) || ("timeout".equals(status.state()) && taskChainSucceeded)
                    ? "success"
                    : status.state();
            if (!"success".equals(effectiveAirflowState)) {
                return new ProofResult(
                        evidenceRoot,
                        effectiveAirflowState,
                        null,
                        List.of(),
                        dbtLogTail
                );
            }
            Path validationRoot = runtimeRepoRoot.resolve("validation-output");
            Files.createDirectories(validationRoot);
            Path sourceCsvCopy = validationRoot.resolve("loan_master.csv");
            Files.copy(fixture.path(), sourceCsvCopy);

            String goldOutputUri = "s3a://" + STORAGE_ROOT_LAKE + "/servicing/" + SOR_SLUG + "/" + pathPipelineSlug
                    + "/gold/write-loan-master-to-lake/";
            Path actualCsvOutput = validationRoot.resolve("actual-" + scenarioSlug);
            Path expectedCsvOutput = validationRoot.resolve("expected-" + scenarioSlug);

            Path actualExportScript = validationRoot.resolve("export_actual_" + scenarioSlug + ".py");
            Path expectedExportScript = validationRoot.resolve("export_expected_" + scenarioSlug + ".py");
            String containerRepoRoot = "/opt/pulse/repo/" + runtimeRepoRoot.getFileName();

            if ("IncrementalMerge".equals(blueprintKey)) {
                Path secondFixture = evidenceRoot.resolve("source/loan_master_incremental_delta.csv");
                Path expectedMergedCsv = expectedCsvOutput.resolve("part-00000.csv");
                createIncrementalMergeDeltaCsv(fixture.path(), secondFixture, expectedMergedCsv);
                stageLoanMasterCsvForPipeline(pathPipelineSlug, secondFixture);

                String secondExecutionDate = "2026-04-26T05:00:00+00:00";
                assertTrue(adapter.awaitDagExists(dagId, Duration.ofSeconds(90), Duration.ofSeconds(3)),
                        "Airflow did not retain generated DAG for IncrementalMerge second run");
                adapter.reserializeDag(dagSubdir);
                adapter.unpauseDag(dagId, dagSubdir);
                LocalAirflowCliAdapter.DagRunHandle secondHandle = adapter.triggerDag(
                        dagId,
                        secondExecutionDate,
                        Map.of(
                                "scenario_id", scenarioSlug + "-live-runtime-second-run",
                                "generation_run_id", run.getId()
                        ),
                        dagSubdir
                );
                assertNotNull(secondHandle.runId());

                LocalAirflowCliAdapter.DagRunStatus secondStatus = adapter.awaitTerminalState(
                        dagId,
                        secondExecutionDate,
                        Duration.ofSeconds(180),
                        Duration.ofSeconds(5),
                        dagSubdir
                );

                List<Map<String, Object>> secondTaskStates = adapter.taskStatesForDagRun(dagId, secondExecutionDate, dagSubdir);
                List<Map<String, Object>> secondTaskLogs = adapter.taskLogsForDagRun(dagId, secondHandle.runId());
                adapter.writeEvidence(
                        scenarioSlug + "-live-runtime-second-run",
                        evidenceRoot.resolve("airflow-second-run"),
                        secondHandle,
                        secondStatus,
                        secondTaskStates,
                        secondTaskLogs
                );

                dbtLogTail = readTail(runtimeRepoRoot.resolve("dbt_project/logs/dbt.log"), 4000);
                boolean secondTaskChainSucceeded = secondTaskStates.stream()
                        .map(task -> String.valueOf(task.get("state")))
                        .allMatch("success"::equals);
                String effectiveSecondAirflowState = "success".equals(secondStatus.state()) || ("timeout".equals(secondStatus.state()) && secondTaskChainSucceeded)
                        ? "success"
                        : secondStatus.state();
                if (!"success".equals(effectiveSecondAirflowState)) {
                    return new ProofResult(
                            evidenceRoot,
                            effectiveSecondAirflowState,
                            null,
                            List.of(),
                            dbtLogTail
                    );
                }

                Path oraclePath = Path.of("src/test/resources/e2e/oracle/loan_master/data-oracle.json");
                writeDeltaProjectionScript(
                        actualExportScript,
                        codegenSlug,
                        goldOutputUri,
                        containerRepoRoot + "/validation-output/actual-" + scenarioSlug,
                        oracleColumns(oraclePath),
                        oracleColumnsByType(oraclePath, "boolean")
                );
                runCommand(List.of(
                        "docker", "exec", "pulse-airflow-1", "bash", "-lc",
                        "/home/airflow/.local/bin/python " + containerRepoRoot + "/validation-output/" + actualExportScript.getFileName()
                ));

                Map<String, Object> expectedOverrides = expectedOverridesFromCsv(expectedCsvOutput);
                expectedOverrides.put("business_keys", List.of("loan_id"));
                MinioOutputOracleValidator.ValidationResult outputValidation = new MinioOutputOracleValidator(objectMapper).validate(
                        new MinioOutputOracleValidator.ProbeRequest(
                                scenarioSlug + "-live-runtime",
                                run.getId(),
                                oraclePath,
                                actualCsvOutput.toUri().toString(),
                                null,
                                evidenceRoot.resolve("data"),
                                expectedOverrides
                        )
                );

                return new ProofResult(
                        evidenceRoot,
                        effectiveSecondAirflowState,
                        outputValidation.verdict(),
                        outputValidation.failureCodes(),
                        dbtLogTail
                );
            }

            if ("FeatureTablePublish".equals(blueprintKey)) {
                List<String> featureColumns = List.of(
                        "loan_id",
                        "current_upb",
                        "interest_rate",
                        "borrower_credit_score",
                        "origination_date"
                );
                Path expectedFeatureCsv = expectedCsvOutput.resolve("part-00000.csv");
                createFeatureTableExpectedCsv(fixture.path(), expectedFeatureCsv, featureColumns);
                writeDeltaProjectionScript(
                        actualExportScript,
                        codegenSlug,
                        goldOutputUri,
                        containerRepoRoot + "/validation-output/actual-" + scenarioSlug,
                        featureColumns,
                        List.of()
                );
                runCommand(List.of(
                        "docker", "exec", "pulse-airflow-1", "bash", "-lc",
                        "/home/airflow/.local/bin/python " + containerRepoRoot + "/validation-output/" + actualExportScript.getFileName()
                ));

                Map<String, Object> expectedOverrides = expectedOverridesFromCsv(expectedCsvOutput);
                expectedOverrides.put("business_keys", List.of("loan_id"));
                MinioOutputOracleValidator.ValidationResult outputValidation = new MinioOutputOracleValidator(objectMapper).validate(
                        new MinioOutputOracleValidator.ProbeRequest(
                                scenarioSlug + "-live-runtime",
                                run.getId(),
                                Path.of("src/test/resources/e2e/oracle/loan_master/data-oracle.json"),
                                actualCsvOutput.toUri().toString(),
                                null,
                                evidenceRoot.resolve("data"),
                                expectedOverrides
                        )
                );

                return new ProofResult(
                        evidenceRoot,
                        effectiveAirflowState,
                        outputValidation.verdict(),
                        outputValidation.failureCodes(),
                        dbtLogTail
                );
            }

            if ("ReferenceDataPublish".equals(blueprintKey)) {
                String referenceColumn = "property_state";
                Path expectedReferenceCsv = expectedCsvOutput.resolve("part-00000.csv");
                createDistinctColumnExpectedCsv(fixture.path(), expectedReferenceCsv, referenceColumn);
                writeDeltaDistinctColumnProjectionScript(
                        actualExportScript,
                        codegenSlug,
                        goldOutputUri,
                        containerRepoRoot + "/validation-output/actual-" + scenarioSlug,
                        referenceColumn
                );
                runCommand(List.of(
                        "docker", "exec", "pulse-airflow-1", "bash", "-lc",
                        "/home/airflow/.local/bin/python " + containerRepoRoot + "/validation-output/" + actualExportScript.getFileName()
                ));

                Map<String, Object> expectedOverrides = expectedOverridesFromCsv(expectedCsvOutput);
                MinioOutputOracleValidator.ValidationResult outputValidation = new MinioOutputOracleValidator(objectMapper).validate(
                        new MinioOutputOracleValidator.ProbeRequest(
                                scenarioSlug + "-live-runtime",
                                run.getId(),
                                Path.of("src/test/resources/e2e/oracle/loan_master/data-oracle.json"),
                                actualCsvOutput.toUri().toString(),
                                null,
                                evidenceRoot.resolve("data"),
                                expectedOverrides
                        )
                );

                return new ProofResult(
                        evidenceRoot,
                        effectiveAirflowState,
                        outputValidation.verdict(),
                        outputValidation.failureCodes(),
                        dbtLogTail
                );
            }

            if ("FactBuild".equals(blueprintKey)) {
                List<String> factColumns = List.of(
                        "loan_id",
                        "loan_status",
                        "property_state",
                        "current_upb",
                        "interest_rate"
                );
                Path expectedFactCsv = expectedCsvOutput.resolve("part-00000.csv");
                createProjectedExpectedCsv(fixture.path(), expectedFactCsv, factColumns);
                writeDeltaProjectionScript(
                        actualExportScript,
                        codegenSlug,
                        goldOutputUri,
                        containerRepoRoot + "/validation-output/actual-" + scenarioSlug,
                        factColumns,
                        List.of()
                );
                runCommand(List.of(
                        "docker", "exec", "pulse-airflow-1", "bash", "-lc",
                        "/home/airflow/.local/bin/python " + containerRepoRoot + "/validation-output/" + actualExportScript.getFileName()
                ));

                Map<String, Object> expectedOverrides = expectedOverridesFromCsv(expectedCsvOutput);
                expectedOverrides.put("business_keys", List.of("loan_id"));
                MinioOutputOracleValidator.ValidationResult outputValidation = new MinioOutputOracleValidator(objectMapper).validate(
                        new MinioOutputOracleValidator.ProbeRequest(
                                scenarioSlug + "-live-runtime",
                                run.getId(),
                                Path.of("src/test/resources/e2e/oracle/loan_master/data-oracle.json"),
                                actualCsvOutput.toUri().toString(),
                                null,
                                evidenceRoot.resolve("data"),
                                expectedOverrides
                        )
                );

                return new ProofResult(
                        evidenceRoot,
                        effectiveAirflowState,
                        outputValidation.verdict(),
                        outputValidation.failureCodes(),
                        dbtLogTail
                );
            }

            if ("WideDenormalizedMart".equals(blueprintKey)) {
                List<String> wideColumns = List.of(
                        "loan_id",
                        "current_upb",
                        "interest_rate",
                        "loan_status",
                        "property_state",
                        "borrower_credit_score"
                );
                Path expectedWideCsv = expectedCsvOutput.resolve("part-00000.csv");
                createProjectedExpectedCsv(fixture.path(), expectedWideCsv, wideColumns);
                writeDeltaProjectionScript(
                        actualExportScript,
                        codegenSlug,
                        goldOutputUri,
                        containerRepoRoot + "/validation-output/actual-" + scenarioSlug,
                        wideColumns,
                        List.of()
                );
                runCommand(List.of(
                        "docker", "exec", "pulse-airflow-1", "bash", "-lc",
                        "/home/airflow/.local/bin/python " + containerRepoRoot + "/validation-output/" + actualExportScript.getFileName()
                ));

                Map<String, Object> expectedOverrides = expectedOverridesFromCsv(expectedCsvOutput);
                expectedOverrides.put("business_keys", List.of("loan_id"));
                MinioOutputOracleValidator.ValidationResult outputValidation = new MinioOutputOracleValidator(objectMapper).validate(
                        new MinioOutputOracleValidator.ProbeRequest(
                                scenarioSlug + "-live-runtime",
                                run.getId(),
                                Path.of("src/test/resources/e2e/oracle/loan_master/data-oracle.json"),
                                actualCsvOutput.toUri().toString(),
                                null,
                                evidenceRoot.resolve("data"),
                                expectedOverrides
                        )
                );

                return new ProofResult(
                        evidenceRoot,
                        effectiveAirflowState,
                        outputValidation.verdict(),
                        outputValidation.failureCodes(),
                        dbtLogTail
                );
            }

            if ("BronzeToSilverCleaning".equals(blueprintKey) || "GenericJoin".equals(blueprintKey)
                    || "PIIMasking".equals(blueprintKey)) {
                Path oraclePath = Path.of("src/test/resources/e2e/oracle/loan_master/data-oracle.json");
                Path fixtureManifestPath = Path.of("src/test/resources/e2e/fixtures/loan_master/fixture-manifest.json");
                writeDeltaProjectionScript(
                        actualExportScript,
                        codegenSlug,
                        goldOutputUri,
                        containerRepoRoot + "/validation-output/actual-" + scenarioSlug,
                        oracleColumns(oraclePath),
                        oracleColumnsByType(oraclePath, "boolean")
                );
                runCommand(List.of(
                        "docker", "exec", "pulse-airflow-1", "bash", "-lc",
                        "/home/airflow/.local/bin/python " + containerRepoRoot + "/validation-output/" + actualExportScript.getFileName()
                ));

                Map<String, Object> expectedOverrides = "PIIMasking".equals(blueprintKey)
                        ? null
                        : fixtureManifestDerivativeOverrides(fixtureManifestPath, "investor_state_partitions");
                MinioOutputOracleValidator.ValidationResult outputValidation = new MinioOutputOracleValidator(objectMapper).validate(
                        new MinioOutputOracleValidator.ProbeRequest(
                                scenarioSlug + "-live-runtime",
                                run.getId(),
                                oraclePath,
                                actualCsvOutput.toUri().toString(),
                                null,
                                evidenceRoot.resolve("data"),
                                expectedOverrides
                        )
                );

                return new ProofResult(
                        evidenceRoot,
                        effectiveAirflowState,
                        outputValidation.verdict(),
                        outputValidation.failureCodes(),
                        dbtLogTail
                );
            }

            if ("DQValidator".equals(blueprintKey)) {
                Path oraclePath = Path.of("src/test/resources/e2e/oracle/loan_master/data-oracle.json");
                writeDeltaProjectionScript(
                        actualExportScript,
                        codegenSlug,
                        goldOutputUri,
                        containerRepoRoot + "/validation-output/actual-" + scenarioSlug,
                        oracleColumns(oraclePath),
                        oracleColumnsByType(oraclePath, "boolean")
                );
                runCommand(List.of(
                        "docker", "exec", "pulse-airflow-1", "bash", "-lc",
                        "/home/airflow/.local/bin/python " + containerRepoRoot + "/validation-output/" + actualExportScript.getFileName()
                ));

                MinioOutputOracleValidator.ValidationResult outputValidation = new MinioOutputOracleValidator(objectMapper).validate(
                        new MinioOutputOracleValidator.ProbeRequest(
                                scenarioSlug + "-live-runtime",
                                run.getId(),
                                oraclePath,
                                actualCsvOutput.toUri().toString(),
                                null,
                                evidenceRoot.resolve("data"),
                                null
                        )
                );

                return new ProofResult(
                        evidenceRoot,
                        effectiveAirflowState,
                        outputValidation.verdict(),
                        outputValidation.failureCodes(),
                        dbtLogTail
                );
            }

            if ("FreshnessChecks".equals(blueprintKey)) {
                List<String> reportColumns = List.of(
                        "check_name",
                        "timestamp_column",
                        "business_date",
                        "max_observed_date",
                        "max_age_minutes",
                        "actual_age_minutes",
                        "row_count",
                        "status"
                );
                Path expectedReportCsv = expectedCsvOutput.resolve("part-00000.csv");
                createFreshnessExpectedReportCsv(fixture.path(), expectedReportCsv, "boarding_date", "2026-04-26", 525600L);
                writeDeltaReportProjectionScript(
                        actualExportScript,
                        codegenSlug,
                        goldOutputUri,
                        containerRepoRoot + "/validation-output/actual-" + scenarioSlug,
                        reportColumns,
                        "check_name"
                );
                runCommand(List.of(
                        "docker", "exec", "pulse-airflow-1", "bash", "-lc",
                        "/home/airflow/.local/bin/python " + containerRepoRoot + "/validation-output/" + actualExportScript.getFileName()
                ));

                Map<String, Object> expectedOverrides = expectedOverridesFromCsv(expectedCsvOutput);
                MinioOutputOracleValidator.ValidationResult outputValidation = new MinioOutputOracleValidator(objectMapper).validate(
                        new MinioOutputOracleValidator.ProbeRequest(
                                scenarioSlug + "-live-runtime",
                                run.getId(),
                                Path.of("src/test/resources/e2e/oracle/loan_master/data-oracle.json"),
                                actualCsvOutput.toUri().toString(),
                                null,
                                evidenceRoot.resolve("data"),
                                expectedOverrides
                        )
                );

                return new ProofResult(
                        evidenceRoot,
                        effectiveAirflowState,
                        outputValidation.verdict(),
                        outputValidation.failureCodes(),
                        dbtLogTail
                );
            }

            if ("SchemaDriftDetection".equals(blueprintKey)) {
                List<String> reportColumns = List.of(
                        "check_name",
                        "expected_columns",
                        "actual_columns",
                        "missing_columns",
                        "added_columns",
                        "allow_extra_columns",
                        "expected_column_count",
                        "actual_column_count",
                        "row_count",
                        "status"
                );
                Path expectedReportCsv = expectedCsvOutput.resolve("part-00000.csv");
                createSchemaDriftExpectedReportCsv(sourceFixture, expectedReportCsv,
                        csvHeaders(fixture.path()), true);
                writeDeltaReportProjectionScript(
                        actualExportScript,
                        codegenSlug,
                        goldOutputUri,
                        containerRepoRoot + "/validation-output/actual-" + scenarioSlug,
                        reportColumns,
                        "check_name"
                );
                runCommand(List.of(
                        "docker", "exec", "pulse-airflow-1", "bash", "-lc",
                        "/home/airflow/.local/bin/python " + containerRepoRoot + "/validation-output/" + actualExportScript.getFileName()
                ));

                Map<String, Object> expectedOverrides = expectedOverridesFromCsv(expectedCsvOutput);
                MinioOutputOracleValidator.ValidationResult outputValidation = new MinioOutputOracleValidator(objectMapper).validate(
                        new MinioOutputOracleValidator.ProbeRequest(
                                scenarioSlug + "-live-runtime",
                                run.getId(),
                                Path.of("src/test/resources/e2e/oracle/loan_master/data-oracle.json"),
                                actualCsvOutput.toUri().toString(),
                                null,
                                evidenceRoot.resolve("data"),
                                expectedOverrides
                        )
                );

                return new ProofResult(
                        evidenceRoot,
                        effectiveAirflowState,
                        outputValidation.verdict(),
                        outputValidation.failureCodes(),
                        dbtLogTail
                );
            }

            if ("AnomalyDetection".equals(blueprintKey)) {
                List<String> reportColumns = List.of(
                        "check_name",
                        "monitored_column",
                        "row_count",
                        "mean_value",
                        "stddev_value",
                        "z_threshold",
                        "anomaly_count",
                        "status"
                );
                Path expectedReportCsv = expectedCsvOutput.resolve("part-00000.csv");
                Path anomalyFixture = evidenceRoot.resolve("source/loan_master_with_anomaly.csv");
                createAnomalyExpectedReportCsv(anomalyFixture, expectedReportCsv, List.of("current_upb", "interest_rate"), 3.0d);
                writeDeltaReportProjectionScript(
                        actualExportScript,
                        codegenSlug,
                        goldOutputUri,
                        containerRepoRoot + "/validation-output/actual-" + scenarioSlug,
                        reportColumns,
                        "monitored_column"
                );
                runCommand(List.of(
                        "docker", "exec", "pulse-airflow-1", "bash", "-lc",
                        "/home/airflow/.local/bin/python " + containerRepoRoot + "/validation-output/" + actualExportScript.getFileName()
                ));

                Map<String, Object> expectedOverrides = expectedOverridesFromCsv(expectedCsvOutput);
                MinioOutputOracleValidator.ValidationResult outputValidation = new MinioOutputOracleValidator(objectMapper).validate(
                        new MinioOutputOracleValidator.ProbeRequest(
                                scenarioSlug + "-live-runtime",
                                run.getId(),
                                Path.of("src/test/resources/e2e/oracle/loan_master/data-oracle.json"),
                                actualCsvOutput.toUri().toString(),
                                null,
                                evidenceRoot.resolve("data"),
                                expectedOverrides
                        )
                );

                return new ProofResult(
                        evidenceRoot,
                        effectiveAirflowState,
                        outputValidation.verdict(),
                        outputValidation.failureCodes(),
                        dbtLogTail
                );
            }

            if ("SCD2Dimension".equals(blueprintKey) || "SnapshotModel".equals(blueprintKey)) {
                Path oraclePath = Path.of("src/test/resources/e2e/oracle/loan_master/data-oracle.json");
                writeDeltaProjectionScript(
                        actualExportScript,
                        codegenSlug,
                        goldOutputUri,
                        containerRepoRoot + "/validation-output/actual-" + scenarioSlug,
                        oracleColumns(oraclePath),
                        oracleColumnsByType(oraclePath, "boolean")
                );
                runCommand(List.of(
                        "docker", "exec", "pulse-airflow-1", "bash", "-lc",
                        "/home/airflow/.local/bin/python " + containerRepoRoot + "/validation-output/" + actualExportScript.getFileName()
                ));

                MinioOutputOracleValidator.ValidationResult outputValidation = new MinioOutputOracleValidator(objectMapper).validate(
                        new MinioOutputOracleValidator.ProbeRequest(
                                scenarioSlug + "-live-runtime",
                                run.getId(),
                                oraclePath,
                                actualCsvOutput.toUri().toString(),
                                null,
                                evidenceRoot.resolve("data"),
                                null
                        )
                );

                return new ProofResult(
                        evidenceRoot,
                        effectiveAirflowState,
                        outputValidation.verdict(),
                        outputValidation.failureCodes(),
                        dbtLogTail
                );
            }

            writeActualAggregateProjectionScript(actualExportScript, codegenSlug, goldOutputUri,
                    containerRepoRoot + "/validation-output/actual-" + scenarioSlug);
            writeExpectedAggregateProjectionScript(expectedExportScript, codegenSlug,
                    containerRepoRoot + "/validation-output/loan_master.csv",
                    containerRepoRoot + "/validation-output/expected-" + scenarioSlug);

            runCommand(List.of(
                    "docker", "exec", "pulse-airflow-1", "bash", "-lc",
                    "/home/airflow/.local/bin/python " + containerRepoRoot + "/validation-output/" + actualExportScript.getFileName()
            ));
            runCommand(List.of(
                    "docker", "exec", "pulse-airflow-1", "bash", "-lc",
                    "/home/airflow/.local/bin/python " + containerRepoRoot + "/validation-output/" + expectedExportScript.getFileName()
            ));

            Map<String, Object> expectedOverrides = expectedOverridesFromCsv(expectedCsvOutput);
            MinioOutputOracleValidator.ValidationResult outputValidation = new MinioOutputOracleValidator(objectMapper).validate(
                    new MinioOutputOracleValidator.ProbeRequest(
                            scenarioSlug + "-live-runtime",
                            run.getId(),
                            Path.of("src/test/resources/e2e/oracle/loan_master/data-oracle.json"),
                            actualCsvOutput.toUri().toString(),
                            null,
                            evidenceRoot.resolve("data"),
                            expectedOverrides
                    )
            );

            return new ProofResult(
                    evidenceRoot,
                    effectiveAirflowState,
                    outputValidation.verdict(),
                    outputValidation.failureCodes(),
                    dbtLogTail
            );
        } finally {
            if (!retainRuntimeRepo) {
                deleteRecursively(runtimeRepoRoot);
            }
        }
    }

    private ProofResult runFileArrivalSensorProof() throws Exception {
        String scenarioSlug = "file-arrival-sensor";
        Path airflowRepoBase = Path.of("/Users/aameradam/pulse-repos");
        assertTrue(Files.isDirectory(airflowRepoBase), "Expected Airflow-mounted repo base to exist");

        LoanMasterFixture fixture = LoanMasterFixture.loadCanonical();
        String unique = UUID.randomUUID().toString().substring(0, 8);
        String pipelineName = "File Arrival Sensor Runtime Proof " + unique;
        String codegenSlug = underscoreSlug(pipelineName);
        String pathPipelineSlug = Slugify.slugify(codegenSlug);
        String dagId = "pulse_" + codegenSlug + "_v1";
        String executionDate = "2026-04-26T04:00:00+00:00";
        String tenantId = "tenant-e2e-" + scenarioSlug + "-" + unique;
        Path runtimeRepoRoot = airflowRepoBase.resolve("omx-" + scenarioSlug + "-" + unique);
        Path evidenceRoot = Path.of("build/e2e-airflow-runtime").resolve(scenarioSlug + "-" + unique);
        boolean retainRuntimeRepo = Boolean.getBoolean("pulse.e2e.retainRuntimeRepo")
                || Boolean.parseBoolean(System.getenv("PULSE_E2E_RETAIN_RUNTIME_REPO"));

        Files.createDirectories(runtimeRepoRoot);
        Files.createDirectories(evidenceRoot);

        try {
            seedLocalDpcStorageBackend(tenantId);
            seedFileArrivalSensorBlueprints();
            PipelineFixture fixtureContext = createFileArrivalSensorPipelineFixture(
                    tenantId,
                    pipelineName,
                    STORAGE_ROOT_FILES + "/servicing/" + SOR_SLUG + "/" + pathPipelineSlug + "/SRC/loan_master.csv"
            );

            stageLoanMasterCsvForPipeline(pathPipelineSlug, fixture.path());
            scaffoldTenantRuntimeRepo(runtimeRepoRoot, tenantId);
            ensureMinioAwsDefaultConnection();

            GenerationRun run = codeGenerationService.generate(
                    fixtureContext.pipeline().getId(),
                    fixtureContext.version().getId(),
                    tenantId,
                    "worker-2"
            );
            assertEquals("COMPLETED", run.getStatus(), () -> "generation failed: " + run.getErrorMessage());
            assertFalse(generatedArtifactRepository.findByGenerationRunIdOrderByFilePathAsc(run.getId()).isEmpty());

            LocalRuntimeBridge bridge = new LocalRuntimeBridge(
                    generatedArtifactRepository,
                    gitRepoRepository,
                    objectMapper
            );
            LocalRuntimeBridge.RuntimeBridgeResult bridgeResult = bridge.materialize(
                    new LocalRuntimeBridge.BridgeRequest(
                            scenarioSlug + "-live-runtime",
                            run.getId(),
                            tenantId,
                            String.valueOf(run.getMetadata().get("compile_namespace")),
                            runtimeRepoRoot,
                            evidenceRoot.resolve("bridge")
                    )
            );

            String outputBaseUri = "s3a://" + STORAGE_ROOT_LAKE + "/home-lending/servicing/" + pathPipelineSlug;
            new LocalRuntimeArtifactRenderer(objectMapper).render(new LocalRuntimeArtifactRenderer.RenderRequest(
                    scenarioSlug + "-live-runtime",
                    String.valueOf(run.getMetadata().get("compile_namespace")),
                    bridgeResult.materializedRoot(),
                    evidenceRoot.resolve("render"),
                    outputBaseUri,
                    "/opt/pulse/repo/" + runtimeRepoRoot.getFileName() + "/dbt_project",
                    Map.of("write_loan_master_to_lake", "pass_through_loan_master")
            ));

            String dagSubdir = "/opt/pulse/repo/" + runtimeRepoRoot.getFileName()
                    + "/" + run.getMetadata().get("compile_namespace")
                    + "/dags/" + codegenSlug + "_dag.py";

            LocalAirflowCliAdapter adapter = new LocalAirflowCliAdapter(objectMapper);
            boolean dagDiscovered = adapter.awaitDagExists(dagId, Duration.ofSeconds(90), Duration.ofSeconds(3));
            if (!dagDiscovered) {
                List<Map<String, Object>> importErrors = adapter.listImportErrors();
                adapter.writeImportErrorEvidence(
                        scenarioSlug + "-live-runtime",
                        evidenceRoot.resolve("airflow-import-errors"),
                        dagId,
                        importErrors
                );
            }
            assertTrue(dagDiscovered, "Airflow did not discover generated DAG for FileArrivalSensor");
            adapter.reserializeDag(dagSubdir);
            adapter.unpauseDag(dagId, dagSubdir);

            LocalAirflowCliAdapter.DagRunHandle handle = adapter.triggerDag(
                    dagId,
                    executionDate,
                    Map.of(
                            "scenario_id", scenarioSlug + "-live-runtime",
                            "generation_run_id", run.getId()
                    ),
                    dagSubdir
            );
            assertNotNull(handle.runId());

            LocalAirflowCliAdapter.DagRunStatus status = adapter.awaitTerminalState(
                    dagId,
                    executionDate,
                    Duration.ofSeconds(180),
                    Duration.ofSeconds(5),
                    dagSubdir
            );

            List<Map<String, Object>> taskStates = adapter.taskStatesForDagRun(dagId, executionDate, dagSubdir);
            List<Map<String, Object>> taskLogs = adapter.taskLogsForDagRun(dagId, handle.runId());
            adapter.writeEvidence(
                    scenarioSlug + "-live-runtime",
                    evidenceRoot.resolve("airflow"),
                    handle,
                    status,
                    taskStates,
                    taskLogs
            );

            String dbtLogTail = readTail(runtimeRepoRoot.resolve("dbt_project/logs/dbt.log"), 4000);
            boolean taskChainSucceeded = taskStates.stream()
                    .map(task -> String.valueOf(task.get("state")))
                    .allMatch("success"::equals);
            String effectiveAirflowState = "success".equals(status.state()) || ("timeout".equals(status.state()) && taskChainSucceeded)
                    ? "success"
                    : status.state();
            if (!"success".equals(effectiveAirflowState)) {
                return new ProofResult(
                        evidenceRoot,
                        effectiveAirflowState,
                        null,
                        List.of(),
                        dbtLogTail
                );
            }
            assertTrue(taskStates.stream()
                            .anyMatch(task -> String.valueOf(task.get("task_id")).contains("wait_for_loan_file")
                                    && "success".equals(String.valueOf(task.get("state")))),
                    "FileArrivalSensor task must succeed in the Airflow run");

            Path validationRoot = runtimeRepoRoot.resolve("validation-output");
            Files.createDirectories(validationRoot);
            String goldOutputUri = "s3a://" + STORAGE_ROOT_LAKE + "/servicing/" + SOR_SLUG + "/" + pathPipelineSlug
                    + "/gold/write-loan-master-to-lake/";
            Path actualCsvOutput = validationRoot.resolve("actual-" + scenarioSlug);
            Path actualExportScript = validationRoot.resolve("export_actual_" + scenarioSlug + ".py");
            String containerRepoRoot = "/opt/pulse/repo/" + runtimeRepoRoot.getFileName();
            Path oraclePath = Path.of("src/test/resources/e2e/oracle/loan_master/data-oracle.json");

            writeDeltaProjectionScript(
                    actualExportScript,
                    codegenSlug,
                    goldOutputUri,
                    containerRepoRoot + "/validation-output/actual-" + scenarioSlug,
                    oracleColumns(oraclePath),
                    oracleColumnsByType(oraclePath, "boolean")
            );
            runCommand(List.of(
                    "docker", "exec", "pulse-airflow-1", "bash", "-lc",
                    "/home/airflow/.local/bin/python " + containerRepoRoot + "/validation-output/" + actualExportScript.getFileName()
            ));

            MinioOutputOracleValidator.ValidationResult outputValidation = new MinioOutputOracleValidator(objectMapper).validate(
                    new MinioOutputOracleValidator.ProbeRequest(
                            scenarioSlug + "-live-runtime",
                            run.getId(),
                            oraclePath,
                            actualCsvOutput.toUri().toString(),
                            null,
                            evidenceRoot.resolve("data"),
                            null
                    )
            );

            return new ProofResult(
                    evidenceRoot,
                    effectiveAirflowState,
                    outputValidation.verdict(),
                    outputValidation.failureCodes(),
                    dbtLogTail
            );
        } finally {
            if (!retainRuntimeRepo) {
                deleteRecursively(runtimeRepoRoot);
            }
        }
    }

    private void ensureMinioAwsDefaultConnection() throws Exception {
        runCommand(List.of(
                "docker", "exec", "pulse-airflow-1", "bash", "-lc",
                "/home/airflow/.local/bin/airflow connections delete aws_default >/dev/null 2>&1 || true; "
                        + "/home/airflow/.local/bin/airflow connections add aws_default "
                        + "--conn-type aws "
                        + "--conn-login minioadmin "
                        + "--conn-password minioadmin "
                        + "--conn-extra '{\"endpoint_url\":\"http://minio:9000\"}'"
        ));
    }

    private ProofResult runDatabaseWriterProof() throws Exception {
        String scenarioSlug = "database-writer";
        Path airflowRepoBase = Path.of("/Users/aameradam/pulse-repos");
        assertTrue(Files.isDirectory(airflowRepoBase), "Expected Airflow-mounted repo base to exist");

        LoanMasterFixture fixture = LoanMasterFixture.loadCanonical();
        String unique = UUID.randomUUID().toString().substring(0, 8);
        String pipelineName = "Database Writer Runtime Proof " + unique;
        String codegenSlug = underscoreSlug(pipelineName);
        String pathPipelineSlug = Slugify.slugify(codegenSlug);
        String dagId = "pulse_" + codegenSlug + "_v1";
        String executionDate = "2026-04-26T04:00:00+00:00";
        String tenantId = "tenant-e2e-" + scenarioSlug + "-" + unique;
        String tableName = "loan_master_e2e_" + unique;
        Path runtimeRepoRoot = airflowRepoBase.resolve("omx-" + scenarioSlug + "-" + unique);
        Path evidenceRoot = Path.of("build/e2e-airflow-runtime").resolve(scenarioSlug + "-" + unique);
        boolean retainRuntimeRepo = Boolean.getBoolean("pulse.e2e.retainRuntimeRepo")
                || Boolean.parseBoolean(System.getenv("PULSE_E2E_RETAIN_RUNTIME_REPO"));

        Files.createDirectories(runtimeRepoRoot);
        Files.createDirectories(evidenceRoot);

        try {
            dropPostgresTable(tableName);
            seedLocalDpcStorageBackend(tenantId);
            seedBlueprints("DatabaseWriter");
            PipelineFixture fixtureContext = createDatabaseWriterPipelineFixture(tenantId, pipelineName, tableName);

            stageLoanMasterCsvForPipeline(pathPipelineSlug, fixture.path());
            scaffoldTenantRuntimeRepo(runtimeRepoRoot, tenantId);

            GenerationRun run = codeGenerationService.generate(
                    fixtureContext.pipeline().getId(),
                    fixtureContext.version().getId(),
                    tenantId,
                    "worker-2"
            );
            assertEquals("COMPLETED", run.getStatus(), () -> "generation failed: " + run.getErrorMessage());
            assertFalse(generatedArtifactRepository.findByGenerationRunIdOrderByFilePathAsc(run.getId()).isEmpty());

            LocalRuntimeBridge bridge = new LocalRuntimeBridge(
                    generatedArtifactRepository,
                    gitRepoRepository,
                    objectMapper
            );
            LocalRuntimeBridge.RuntimeBridgeResult bridgeResult = bridge.materialize(
                    new LocalRuntimeBridge.BridgeRequest(
                            scenarioSlug + "-live-runtime",
                            run.getId(),
                            tenantId,
                            String.valueOf(run.getMetadata().get("compile_namespace")),
                            runtimeRepoRoot,
                            evidenceRoot.resolve("bridge")
                    )
            );

            String outputBaseUri = "s3a://" + STORAGE_ROOT_LAKE + "/home-lending/servicing/" + pathPipelineSlug;
            new LocalRuntimeArtifactRenderer(objectMapper).render(new LocalRuntimeArtifactRenderer.RenderRequest(
                    scenarioSlug + "-live-runtime",
                    String.valueOf(run.getMetadata().get("compile_namespace")),
                    bridgeResult.materializedRoot(),
                    evidenceRoot.resolve("render"),
                    outputBaseUri,
                    "/opt/pulse/repo/" + runtimeRepoRoot.getFileName() + "/dbt_project",
                    Map.of("write_delinquent_loans_to_postgres", "filter_delinquent_loans")
            ));

            String dagSubdir = "/opt/pulse/repo/" + runtimeRepoRoot.getFileName()
                    + "/" + run.getMetadata().get("compile_namespace")
                    + "/dags/" + codegenSlug + "_dag.py";

            LocalAirflowCliAdapter adapter = new LocalAirflowCliAdapter(objectMapper);
            boolean dagDiscovered = adapter.awaitDagExists(dagId, Duration.ofSeconds(90), Duration.ofSeconds(3));
            if (!dagDiscovered) {
                List<Map<String, Object>> importErrors = adapter.listImportErrors();
                adapter.writeImportErrorEvidence(
                        scenarioSlug + "-live-runtime",
                        evidenceRoot.resolve("airflow-import-errors"),
                        dagId,
                        importErrors
                );
            }
            assertTrue(dagDiscovered, "Airflow did not discover generated DAG for DatabaseWriter");
            adapter.reserializeDag(dagSubdir);
            adapter.unpauseDag(dagId, dagSubdir);

            LocalAirflowCliAdapter.DagRunHandle handle = adapter.triggerDag(
                    dagId,
                    executionDate,
                    Map.of(
                            "scenario_id", scenarioSlug + "-live-runtime",
                            "generation_run_id", run.getId()
                    ),
                    dagSubdir
            );
            assertNotNull(handle.runId());

            LocalAirflowCliAdapter.DagRunStatus status = adapter.awaitTerminalState(
                    dagId,
                    executionDate,
                    Duration.ofSeconds(180),
                    Duration.ofSeconds(5),
                    dagSubdir
            );

            List<Map<String, Object>> taskStates = adapter.taskStatesForDagRun(dagId, executionDate, dagSubdir);
            List<Map<String, Object>> taskLogs = adapter.taskLogsForDagRun(dagId, handle.runId());
            adapter.writeEvidence(
                    scenarioSlug + "-live-runtime",
                    evidenceRoot.resolve("airflow"),
                    handle,
                    status,
                    taskStates,
                    taskLogs
            );

            String dbtLogTail = readTail(runtimeRepoRoot.resolve("dbt_project/logs/dbt.log"), 4000);
            boolean taskChainSucceeded = taskStates.stream()
                    .map(task -> String.valueOf(task.get("state")))
                    .allMatch("success"::equals);
            String effectiveAirflowState = "success".equals(status.state()) || ("timeout".equals(status.state()) && taskChainSucceeded)
                    ? "success"
                    : status.state();
            if (!"success".equals(effectiveAirflowState)) {
                return new ProofResult(
                        evidenceRoot,
                        effectiveAirflowState,
                        null,
                        List.of(),
                        dbtLogTail
                );
            }

            Path validationRoot = runtimeRepoRoot.resolve("validation-output");
            Files.createDirectories(validationRoot);
            Path expectedCsvRoot = validationRoot.resolve("expected-database-writer");
            createDelinquentLoansExpectedCsv(fixture.path(), expectedCsvRoot.resolve("part-00000.csv"));
            Map<String, Object> expectedOverrides = expectedOverridesFromCsv(expectedCsvRoot);
            expectedOverrides.put("business_keys", List.of("loan_id"));

            PostgresOutputOracleValidator.ValidationResult outputValidation = new PostgresOutputOracleValidator(objectMapper).validate(
                    new PostgresOutputOracleValidator.ProbeRequest(
                            scenarioSlug + "-live-runtime",
                            run.getId(),
                            "jdbc:postgresql://localhost:5432/pulse",
                            "pulse",
                            "pulse",
                            "public",
                            tableName,
                            Path.of("src/test/resources/e2e/oracle/loan_master/data-oracle.json"),
                            evidenceRoot.resolve("data"),
                            expectedOverrides,
                            oracleColumns(Path.of("src/test/resources/e2e/oracle/loan_master/data-oracle.json"))
                    )
            );

            return new ProofResult(
                    evidenceRoot,
                    effectiveAirflowState,
                    outputValidation.verdict(),
                    outputValidation.failureCodes(),
                    dbtLogTail
            );
        } finally {
            dropPostgresTable(tableName);
            if (!retainRuntimeRepo) {
                deleteRecursively(runtimeRepoRoot);
            }
        }
    }

    private ProofResult runSnapshotIngestionProof() throws Exception {
        String scenarioSlug = "snapshot-ingestion";
        Path airflowRepoBase = Path.of("/Users/aameradam/pulse-repos");
        assertTrue(Files.isDirectory(airflowRepoBase), "Expected Airflow-mounted repo base to exist");

        LoanMasterFixture fixture = LoanMasterFixture.loadCanonical();
        String unique = UUID.randomUUID().toString().substring(0, 8);
        String pipelineName = "Snapshot Ingestion Runtime Proof " + unique;
        String codegenSlug = underscoreSlug(pipelineName);
        String pathPipelineSlug = Slugify.slugify(codegenSlug);
        String dagId = "pulse_" + codegenSlug + "_v1";
        String executionDate = "2026-04-26T04:00:00+00:00";
        String tenantId = "tenant-e2e-" + scenarioSlug + "-" + unique;
        String tableName = "loan_master_snapshot_src_" + unique;
        Path runtimeRepoRoot = airflowRepoBase.resolve("omx-" + scenarioSlug + "-" + unique);
        Path evidenceRoot = Path.of("build/e2e-airflow-runtime").resolve(scenarioSlug + "-" + unique);
        boolean retainRuntimeRepo = Boolean.getBoolean("pulse.e2e.retainRuntimeRepo")
                || Boolean.parseBoolean(System.getenv("PULSE_E2E_RETAIN_RUNTIME_REPO"));

        Files.createDirectories(runtimeRepoRoot);
        Files.createDirectories(evidenceRoot);

        try {
            seedCurrentLoansPostgresSource(tableName, fixture.path());
            seedLocalDpcStorageBackend(tenantId);
            seedBlueprints("SnapshotIngestion");
            PipelineFixture fixtureContext = createSnapshotIngestionPipelineFixture(tenantId, pipelineName, tableName);
            scaffoldTenantRuntimeRepo(runtimeRepoRoot, tenantId);

            GenerationRun run = codeGenerationService.generate(
                    fixtureContext.pipeline().getId(),
                    fixtureContext.version().getId(),
                    tenantId,
                    "worker-2"
            );
            assertEquals("COMPLETED", run.getStatus(), () -> "generation failed: " + run.getErrorMessage());
            assertFalse(generatedArtifactRepository.findByGenerationRunIdOrderByFilePathAsc(run.getId()).isEmpty());

            LocalRuntimeBridge bridge = new LocalRuntimeBridge(
                    generatedArtifactRepository,
                    gitRepoRepository,
                    objectMapper
            );
            LocalRuntimeBridge.RuntimeBridgeResult bridgeResult = bridge.materialize(
                    new LocalRuntimeBridge.BridgeRequest(
                            scenarioSlug + "-live-runtime",
                            run.getId(),
                            tenantId,
                            String.valueOf(run.getMetadata().get("compile_namespace")),
                            runtimeRepoRoot,
                            evidenceRoot.resolve("bridge")
                    )
            );

            String outputBaseUri = "s3a://" + STORAGE_ROOT_LAKE + "/home-lending/servicing/" + pathPipelineSlug;
            new LocalRuntimeArtifactRenderer(objectMapper).render(new LocalRuntimeArtifactRenderer.RenderRequest(
                    scenarioSlug + "-live-runtime",
                    String.valueOf(run.getMetadata().get("compile_namespace")),
                    bridgeResult.materializedRoot(),
                    evidenceRoot.resolve("render"),
                    outputBaseUri,
                    "/opt/pulse/repo/" + runtimeRepoRoot.getFileName() + "/dbt_project",
                    Map.of("write_loan_master_to_lake", "pass_through_current_loans")
            ));

            String dagSubdir = "/opt/pulse/repo/" + runtimeRepoRoot.getFileName()
                    + "/" + run.getMetadata().get("compile_namespace")
                    + "/dags/" + codegenSlug + "_dag.py";

            LocalAirflowCliAdapter adapter = new LocalAirflowCliAdapter(objectMapper);
            boolean dagDiscovered = adapter.awaitDagExists(dagId, Duration.ofSeconds(90), Duration.ofSeconds(3));
            if (!dagDiscovered) {
                List<Map<String, Object>> importErrors = adapter.listImportErrors();
                adapter.writeImportErrorEvidence(
                        scenarioSlug + "-live-runtime",
                        evidenceRoot.resolve("airflow-import-errors"),
                        dagId,
                        importErrors
                );
            }
            assertTrue(dagDiscovered, "Airflow did not discover generated DAG for SnapshotIngestion");
            adapter.reserializeDag(dagSubdir);
            adapter.unpauseDag(dagId, dagSubdir);

            LocalAirflowCliAdapter.DagRunHandle handle = adapter.triggerDag(
                    dagId,
                    executionDate,
                    Map.of(
                            "scenario_id", scenarioSlug + "-live-runtime",
                            "generation_run_id", run.getId()
                    ),
                    dagSubdir
            );
            assertNotNull(handle.runId());

            LocalAirflowCliAdapter.DagRunStatus status = adapter.awaitTerminalState(
                    dagId,
                    executionDate,
                    Duration.ofSeconds(180),
                    Duration.ofSeconds(5),
                    dagSubdir
            );

            List<Map<String, Object>> taskStates = adapter.taskStatesForDagRun(dagId, executionDate, dagSubdir);
            List<Map<String, Object>> taskLogs = adapter.taskLogsForDagRun(dagId, handle.runId());
            adapter.writeEvidence(
                    scenarioSlug + "-live-runtime",
                    evidenceRoot.resolve("airflow"),
                    handle,
                    status,
                    taskStates,
                    taskLogs
            );

            String dbtLogTail = readTail(runtimeRepoRoot.resolve("dbt_project/logs/dbt.log"), 4000);
            boolean taskChainSucceeded = taskStates.stream()
                    .map(task -> String.valueOf(task.get("state")))
                    .allMatch("success"::equals);
            String effectiveAirflowState = "success".equals(status.state()) || ("timeout".equals(status.state()) && taskChainSucceeded)
                    ? "success"
                    : status.state();
            if (!"success".equals(effectiveAirflowState)) {
                return new ProofResult(
                        evidenceRoot,
                        effectiveAirflowState,
                        null,
                        List.of(),
                        dbtLogTail
                );
            }

            Path validationRoot = runtimeRepoRoot.resolve("validation-output");
            Files.createDirectories(validationRoot);
            Path expectedCsvRoot = validationRoot.resolve("expected-" + scenarioSlug);
            createCurrentLoansExpectedCsv(fixture.path(), expectedCsvRoot.resolve("part-00000.csv"));

            String goldOutputUri = "s3a://" + STORAGE_ROOT_LAKE + "/servicing/" + SOR_SLUG + "/" + pathPipelineSlug
                    + "/gold/write-loan-master-to-lake/";
            Path actualCsvOutput = validationRoot.resolve("actual-" + scenarioSlug);
            Path actualExportScript = validationRoot.resolve("export_actual_" + scenarioSlug + ".py");
            String containerRepoRoot = "/opt/pulse/repo/" + runtimeRepoRoot.getFileName();
            Path oraclePath = Path.of("src/test/resources/e2e/oracle/loan_master/data-oracle.json");

            writeDeltaProjectionScript(
                    actualExportScript,
                    codegenSlug,
                    goldOutputUri,
                    containerRepoRoot + "/validation-output/actual-" + scenarioSlug,
                    oracleColumns(oraclePath),
                    oracleColumnsByType(oraclePath, "boolean")
            );
            runCommand(List.of(
                    "docker", "exec", "pulse-airflow-1", "bash", "-lc",
                    "/home/airflow/.local/bin/python " + containerRepoRoot + "/validation-output/" + actualExportScript.getFileName()
            ));

            Map<String, Object> expectedOverrides = expectedOverridesFromCsv(expectedCsvRoot);
            MinioOutputOracleValidator.ValidationResult outputValidation = new MinioOutputOracleValidator(objectMapper).validate(
                    new MinioOutputOracleValidator.ProbeRequest(
                            scenarioSlug + "-live-runtime",
                            run.getId(),
                            oraclePath,
                            actualCsvOutput.toUri().toString(),
                            null,
                            evidenceRoot.resolve("data"),
                            expectedOverrides
                    )
            );

            return new ProofResult(
                    evidenceRoot,
                    effectiveAirflowState,
                    outputValidation.verdict(),
                    outputValidation.failureCodes(),
                    dbtLogTail
            );
        } finally {
            dropPostgresTable(tableName);
            if (!retainRuntimeRepo) {
                deleteRecursively(runtimeRepoRoot);
            }
        }
    }

    private ProofResult runBulkBackfillProof() throws Exception {
        String scenarioSlug = "bulk-backfill";
        Path airflowRepoBase = Path.of("/Users/aameradam/pulse-repos");
        assertTrue(Files.isDirectory(airflowRepoBase), "Expected Airflow-mounted repo base to exist");

        LoanMasterFixture fixture = LoanMasterFixture.loadCanonical();
        String unique = UUID.randomUUID().toString().substring(0, 8);
        String pipelineName = "Bulk Backfill Runtime Proof " + unique;
        String codegenSlug = underscoreSlug(pipelineName);
        String pathPipelineSlug = Slugify.slugify(codegenSlug);
        String dagId = "pulse_" + codegenSlug + "_v1";
        String executionDate = "2026-04-26T04:00:00+00:00";
        String tenantId = "tenant-e2e-" + scenarioSlug + "-" + unique;
        String tableName = "loan_master_backfill_src_" + unique;
        Path runtimeRepoRoot = airflowRepoBase.resolve("omx-" + scenarioSlug + "-" + unique);
        Path evidenceRoot = Path.of("build/e2e-airflow-runtime").resolve(scenarioSlug + "-" + unique);
        boolean retainRuntimeRepo = Boolean.getBoolean("pulse.e2e.retainRuntimeRepo")
                || Boolean.parseBoolean(System.getenv("PULSE_E2E_RETAIN_RUNTIME_REPO"));

        Files.createDirectories(runtimeRepoRoot);
        Files.createDirectories(evidenceRoot);

        try {
            seedLoanMasterPostgresSource(tableName, fixture.path());
            seedLocalDpcStorageBackend(tenantId);
            seedBlueprints("BulkBackfill");
            PipelineFixture fixtureContext = createBulkBackfillPipelineFixture(tenantId, pipelineName, tableName);
            scaffoldTenantRuntimeRepo(runtimeRepoRoot, tenantId);

            GenerationRun run = codeGenerationService.generate(
                    fixtureContext.pipeline().getId(),
                    fixtureContext.version().getId(),
                    tenantId,
                    "worker-2"
            );
            assertEquals("COMPLETED", run.getStatus(), () -> "generation failed: " + run.getErrorMessage());
            assertFalse(generatedArtifactRepository.findByGenerationRunIdOrderByFilePathAsc(run.getId()).isEmpty());

            LocalRuntimeBridge bridge = new LocalRuntimeBridge(
                    generatedArtifactRepository,
                    gitRepoRepository,
                    objectMapper
            );
            LocalRuntimeBridge.RuntimeBridgeResult bridgeResult = bridge.materialize(
                    new LocalRuntimeBridge.BridgeRequest(
                            scenarioSlug + "-live-runtime",
                            run.getId(),
                            tenantId,
                            String.valueOf(run.getMetadata().get("compile_namespace")),
                            runtimeRepoRoot,
                            evidenceRoot.resolve("bridge")
                    )
            );

            String outputBaseUri = "s3a://" + STORAGE_ROOT_LAKE + "/home-lending/servicing/" + pathPipelineSlug;
            new LocalRuntimeArtifactRenderer(objectMapper).render(new LocalRuntimeArtifactRenderer.RenderRequest(
                    scenarioSlug + "-live-runtime",
                    String.valueOf(run.getMetadata().get("compile_namespace")),
                    bridgeResult.materializedRoot(),
                    evidenceRoot.resolve("render"),
                    outputBaseUri,
                    "/opt/pulse/repo/" + runtimeRepoRoot.getFileName() + "/dbt_project",
                    Map.of("write_loan_master_to_lake", "delinquent_loans")
            ));

            String dagSubdir = "/opt/pulse/repo/" + runtimeRepoRoot.getFileName()
                    + "/" + run.getMetadata().get("compile_namespace")
                    + "/dags/" + codegenSlug + "_dag.py";

            LocalAirflowCliAdapter adapter = new LocalAirflowCliAdapter(objectMapper);
            boolean dagDiscovered = adapter.awaitDagExists(dagId, Duration.ofSeconds(90), Duration.ofSeconds(3));
            if (!dagDiscovered) {
                List<Map<String, Object>> importErrors = adapter.listImportErrors();
                adapter.writeImportErrorEvidence(
                        scenarioSlug + "-live-runtime",
                        evidenceRoot.resolve("airflow-import-errors"),
                        dagId,
                        importErrors
                );
            }
            assertTrue(dagDiscovered, "Airflow did not discover generated DAG for BulkBackfill");
            adapter.reserializeDag(dagSubdir);
            adapter.unpauseDag(dagId, dagSubdir);

            LocalAirflowCliAdapter.DagRunHandle handle = adapter.triggerDag(
                    dagId,
                    executionDate,
                    Map.of(
                            "scenario_id", scenarioSlug + "-live-runtime",
                            "generation_run_id", run.getId()
                    ),
                    dagSubdir
            );
            assertNotNull(handle.runId());

            LocalAirflowCliAdapter.DagRunStatus status = adapter.awaitTerminalState(
                    dagId,
                    executionDate,
                    Duration.ofSeconds(180),
                    Duration.ofSeconds(5),
                    dagSubdir
            );

            List<Map<String, Object>> taskStates = adapter.taskStatesForDagRun(dagId, executionDate, dagSubdir);
            List<Map<String, Object>> taskLogs = adapter.taskLogsForDagRun(dagId, handle.runId());
            adapter.writeEvidence(
                    scenarioSlug + "-live-runtime",
                    evidenceRoot.resolve("airflow"),
                    handle,
                    status,
                    taskStates,
                    taskLogs
            );

            String dbtLogTail = readTail(runtimeRepoRoot.resolve("dbt_project/logs/dbt.log"), 4000);
            boolean taskChainSucceeded = taskStates.stream()
                    .map(task -> String.valueOf(task.get("state")))
                    .allMatch("success"::equals);
            String effectiveAirflowState = "success".equals(status.state()) || ("timeout".equals(status.state()) && taskChainSucceeded)
                    ? "success"
                    : status.state();
            if (!"success".equals(effectiveAirflowState)) {
                return new ProofResult(
                        evidenceRoot,
                        effectiveAirflowState,
                        null,
                        List.of(),
                        dbtLogTail
                );
            }

            Path validationRoot = runtimeRepoRoot.resolve("validation-output");
            Files.createDirectories(validationRoot);

            String goldOutputUri = "s3a://" + STORAGE_ROOT_LAKE + "/servicing/" + SOR_SLUG + "/" + pathPipelineSlug
                    + "/gold/write-loan-master-to-lake/";
            Path actualCsvOutput = validationRoot.resolve("actual-" + scenarioSlug);
            Path actualExportScript = validationRoot.resolve("export_actual_" + scenarioSlug + ".py");
            String containerRepoRoot = "/opt/pulse/repo/" + runtimeRepoRoot.getFileName();
            Path oraclePath = Path.of("src/test/resources/e2e/oracle/loan_master/data-oracle.json");

            writeDeltaProjectionScript(
                    actualExportScript,
                    codegenSlug,
                    goldOutputUri,
                    containerRepoRoot + "/validation-output/actual-" + scenarioSlug,
                    oracleColumns(oraclePath),
                    oracleColumnsByType(oraclePath, "boolean")
            );
            runCommand(List.of(
                    "docker", "exec", "pulse-airflow-1", "bash", "-lc",
                    "/home/airflow/.local/bin/python " + containerRepoRoot + "/validation-output/" + actualExportScript.getFileName()
            ));

            Path fixtureManifestPath = Path.of("src/test/resources/e2e/fixtures/loan_master/fixture-manifest.json");
            Map<String, Object> expectedOverrides = fixtureManifestDerivativeOverrides(fixtureManifestPath, "delinquent_loans");
            MinioOutputOracleValidator.ValidationResult outputValidation = new MinioOutputOracleValidator(objectMapper).validate(
                    new MinioOutputOracleValidator.ProbeRequest(
                            scenarioSlug + "-live-runtime",
                            run.getId(),
                            oraclePath,
                            actualCsvOutput.toUri().toString(),
                            null,
                            evidenceRoot.resolve("data"),
                            expectedOverrides
                    )
            );

            return new ProofResult(
                    evidenceRoot,
                    effectiveAirflowState,
                    outputValidation.verdict(),
                    outputValidation.failureCodes(),
                    dbtLogTail
            );
        } finally {
            dropPostgresTable(tableName);
            if (!retainRuntimeRepo) {
                deleteRecursively(runtimeRepoRoot);
            }
        }
    }

    private ProofResult runRouterProof() throws Exception {
        String scenarioSlug = "generic-router";
        Path airflowRepoBase = Path.of("/Users/aameradam/pulse-repos");
        assertTrue(Files.isDirectory(airflowRepoBase), "Expected Airflow-mounted repo base to exist");

        LoanMasterFixture fixture = LoanMasterFixture.loadCanonical();
        String unique = UUID.randomUUID().toString().substring(0, 8);
        String pipelineName = "Generic Router Runtime Proof " + unique;
        String codegenSlug = underscoreSlug(pipelineName);
        String pathPipelineSlug = Slugify.slugify(codegenSlug);
        String dagId = "pulse_" + codegenSlug + "_v1";
        String executionDate = "2026-04-26T04:00:00+00:00";
        String tenantId = "tenant-e2e-" + scenarioSlug + "-" + unique;
        Path runtimeRepoRoot = airflowRepoBase.resolve("omx-" + scenarioSlug + "-" + unique);
        Path evidenceRoot = Path.of("build/e2e-airflow-runtime").resolve(scenarioSlug + "-" + unique);
        boolean retainRuntimeRepo = Boolean.getBoolean("pulse.e2e.retainRuntimeRepo")
                || Boolean.parseBoolean(System.getenv("PULSE_E2E_RETAIN_RUNTIME_REPO"));

        Files.createDirectories(runtimeRepoRoot);
        Files.createDirectories(evidenceRoot);

        try {
            seedLocalDpcStorageBackend(tenantId);
            seedBlueprints("GenericRouter");
            PipelineFixture fixtureContext = createPipelineFixture(
                    tenantId,
                    pipelineName,
                    "GenericRouter",
                    List.of(),
                    List.of(),
                    "default_output"
            );

            stageLoanMasterCsvForPipeline(pathPipelineSlug, fixture.path());
            scaffoldTenantRuntimeRepo(runtimeRepoRoot, tenantId);

            GenerationRun run = codeGenerationService.generate(
                    fixtureContext.pipeline().getId(),
                    fixtureContext.version().getId(),
                    tenantId,
                    "worker-2"
            );
            assertEquals("COMPLETED", run.getStatus(), () -> "generation failed: " + run.getErrorMessage());
            assertFalse(generatedArtifactRepository.findByGenerationRunIdOrderByFilePathAsc(run.getId()).isEmpty());

            LocalRuntimeBridge bridge = new LocalRuntimeBridge(
                    generatedArtifactRepository,
                    gitRepoRepository,
                    objectMapper
            );
            LocalRuntimeBridge.RuntimeBridgeResult bridgeResult = bridge.materialize(
                    new LocalRuntimeBridge.BridgeRequest(
                            scenarioSlug + "-live-runtime",
                            run.getId(),
                            tenantId,
                            String.valueOf(run.getMetadata().get("compile_namespace")),
                            runtimeRepoRoot,
                            evidenceRoot.resolve("bridge")
                    )
            );

            String outputBaseUri = "s3a://" + STORAGE_ROOT_LAKE + "/home-lending/servicing/" + pathPipelineSlug;
            new LocalRuntimeArtifactRenderer(objectMapper).render(new LocalRuntimeArtifactRenderer.RenderRequest(
                    scenarioSlug + "-live-runtime",
                    String.valueOf(run.getMetadata().get("compile_namespace")),
                    bridgeResult.materializedRoot(),
                    evidenceRoot.resolve("render"),
                    outputBaseUri,
                    "/opt/pulse/repo/" + runtimeRepoRoot.getFileName() + "/dbt_project",
                    Map.of("write_loan_master_to_lake", underscoreSlug(defaultNameFor("GenericRouter")) + "_default")
            ));

            String dagSubdir = "/opt/pulse/repo/" + runtimeRepoRoot.getFileName()
                    + "/" + run.getMetadata().get("compile_namespace")
                    + "/dags/" + codegenSlug + "_dag.py";

            LocalAirflowCliAdapter adapter = new LocalAirflowCliAdapter(objectMapper);
            boolean dagDiscovered = adapter.awaitDagExists(dagId, Duration.ofSeconds(90), Duration.ofSeconds(3));
            if (!dagDiscovered) {
                List<Map<String, Object>> importErrors = adapter.listImportErrors();
                adapter.writeImportErrorEvidence(
                        scenarioSlug + "-live-runtime",
                        evidenceRoot.resolve("airflow-import-errors"),
                        dagId,
                        importErrors
                );
            }
            assertTrue(dagDiscovered, () -> "Airflow did not discover generated DAG for " + scenarioSlug);
            adapter.reserializeDag(dagSubdir);
            adapter.unpauseDag(dagId, dagSubdir);

            var handle = adapter.triggerDag(
                    dagId,
                    executionDate,
                    Map.of(
                            "scenario_id", scenarioSlug + "-live-runtime",
                            "generation_run_id", run.getId()
                    ),
                    dagSubdir
            );
            assertNotNull(handle.runId());

            var status = adapter.awaitTerminalState(dagId, executionDate, Duration.ofSeconds(120), Duration.ofSeconds(5), dagSubdir);
            List<Map<String, Object>> taskStates = adapter.taskStatesForDagRun(dagId, executionDate, dagSubdir);
            adapter.writeEvidence(
                    scenarioSlug + "-live-runtime",
                    evidenceRoot.resolve("airflow"),
                    handle,
                    status,
                    taskStates,
                    adapter.taskLogsForDagRun(dagId, handle.runId())
            );

            String dbtLogTail = readTail(runtimeRepoRoot.resolve("dbt_project/logs/dbt.log"), 4000);
            boolean taskChainSucceeded = taskStates.stream()
                    .map(task -> String.valueOf(task.get("state")))
                    .allMatch("success"::equals);
            String effectiveAirflowState = "success".equals(status.state()) || ("timeout".equals(status.state()) && taskChainSucceeded)
                    ? "success"
                    : status.state();
            if (!"success".equals(effectiveAirflowState)) {
                return new ProofResult(
                        evidenceRoot,
                        effectiveAirflowState,
                        null,
                        List.of(),
                        dbtLogTail
                );
            }

            Path validationRoot = runtimeRepoRoot.resolve("validation-output");
            Files.createDirectories(validationRoot);
            Path projectedOutput = validationRoot.resolve("actual-" + scenarioSlug);
            Path exportScript = validationRoot.resolve("export_actual_" + scenarioSlug + ".py");
            Path oraclePath = Path.of("src/test/resources/e2e/oracle/loan_master/data-oracle.json");
            Path fixtureManifestPath = Path.of("src/test/resources/e2e/fixtures/loan_master/fixture-manifest.json");
            String goldOutputUri = "s3a://" + STORAGE_ROOT_LAKE + "/servicing/" + SOR_SLUG + "/" + pathPipelineSlug
                    + "/gold/write-loan-master-to-lake/";
            String containerRepoRoot = "/opt/pulse/repo/" + runtimeRepoRoot.getFileName();
            writeDeltaProjectionScript(
                    exportScript,
                    codegenSlug,
                    goldOutputUri,
                    containerRepoRoot + "/validation-output/actual-" + scenarioSlug,
                    oracleColumns(oraclePath),
                    oracleColumnsByType(oraclePath, "boolean")
            );
            runCommand(List.of(
                    "docker", "exec", "pulse-airflow-1", "bash", "-lc",
                    "/home/airflow/.local/bin/python " + containerRepoRoot + "/validation-output/" + exportScript.getFileName()
            ));

            MinioOutputOracleValidator.ValidationResult outputValidation = new MinioOutputOracleValidator(objectMapper).validate(
                    new MinioOutputOracleValidator.ProbeRequest(
                            scenarioSlug + "-live-runtime",
                            run.getId(),
                            oraclePath,
                            projectedOutput.toUri().toString(),
                            null,
                            evidenceRoot.resolve("data"),
                            fixtureManifestDerivativeOverrides(fixtureManifestPath, "investor_state_partitions")
                    )
            );

            return new ProofResult(
                    evidenceRoot,
                    effectiveAirflowState,
                    outputValidation.verdict(),
                    outputValidation.failureCodes(),
                    dbtLogTail
            );
        } finally {
            if (!retainRuntimeRepo) {
                deleteRecursively(runtimeRepoRoot);
            }
        }
    }

    private ProofResult runSchemaNormalizationProof() throws Exception {
        String scenarioSlug = "schema-normalization";
        Path airflowRepoBase = Path.of("/Users/aameradam/pulse-repos");
        assertTrue(Files.isDirectory(airflowRepoBase), "Expected Airflow-mounted repo base to exist");

        LoanMasterFixture fixture = LoanMasterFixture.loadCanonical();
        String unique = UUID.randomUUID().toString().substring(0, 8);
        String pipelineName = "Schema Normalization Runtime Proof " + unique;
        String codegenSlug = underscoreSlug(pipelineName);
        String pathPipelineSlug = Slugify.slugify(codegenSlug);
        String dagId = "pulse_" + codegenSlug + "_v1";
        String executionDate = "2026-04-26T04:00:00+00:00";
        String tenantId = "tenant-e2e-" + scenarioSlug + "-" + unique;
        Path runtimeRepoRoot = airflowRepoBase.resolve("omx-" + scenarioSlug + "-" + unique);
        Path evidenceRoot = Path.of("build/e2e-airflow-runtime").resolve(scenarioSlug + "-" + unique);
        boolean retainRuntimeRepo = Boolean.getBoolean("pulse.e2e.retainRuntimeRepo")
                || Boolean.parseBoolean(System.getenv("PULSE_E2E_RETAIN_RUNTIME_REPO"));

        Files.createDirectories(runtimeRepoRoot);
        Files.createDirectories(evidenceRoot);

        try {
            Path riskBandFixture = evidenceRoot.resolve("source/loan_master_with_risk_band.csv");
            createRiskBandCsv(fixture.path(), riskBandFixture);
            seedLocalDpcStorageBackend(tenantId);
            seedBlueprints("SchemaNormalization");
            PipelineFixture fixtureContext = createPipelineFixture(
                    tenantId,
                    pipelineName,
                    "SchemaNormalization",
                    List.of(),
                    List.of(),
                    "normalized_output"
            );

            stageLoanMasterCsvForPipeline(pathPipelineSlug, riskBandFixture);
            scaffoldTenantRuntimeRepo(runtimeRepoRoot, tenantId);

            GenerationRun run = codeGenerationService.generate(
                    fixtureContext.pipeline().getId(),
                    fixtureContext.version().getId(),
                    tenantId,
                    "worker-2"
            );
            assertEquals("COMPLETED", run.getStatus(), () -> "generation failed: " + run.getErrorMessage());
            assertFalse(generatedArtifactRepository.findByGenerationRunIdOrderByFilePathAsc(run.getId()).isEmpty());

            LocalRuntimeBridge bridge = new LocalRuntimeBridge(
                    generatedArtifactRepository,
                    gitRepoRepository,
                    objectMapper
            );
            LocalRuntimeBridge.RuntimeBridgeResult bridgeResult = bridge.materialize(
                    new LocalRuntimeBridge.BridgeRequest(
                            scenarioSlug + "-live-runtime",
                            run.getId(),
                            tenantId,
                            String.valueOf(run.getMetadata().get("compile_namespace")),
                            runtimeRepoRoot,
                            evidenceRoot.resolve("bridge")
                    )
            );

            String outputBaseUri = "s3a://" + STORAGE_ROOT_LAKE + "/home-lending/servicing/" + pathPipelineSlug;
            new LocalRuntimeArtifactRenderer(objectMapper).render(new LocalRuntimeArtifactRenderer.RenderRequest(
                    scenarioSlug + "-live-runtime",
                    String.valueOf(run.getMetadata().get("compile_namespace")),
                    bridgeResult.materializedRoot(),
                    evidenceRoot.resolve("render"),
                    outputBaseUri,
                    "/opt/pulse/repo/" + runtimeRepoRoot.getFileName() + "/dbt_project",
                    Map.of("write_loan_master_to_lake", underscoreSlug(defaultNameFor("SchemaNormalization")))
            ));

            String dagSubdir = "/opt/pulse/repo/" + runtimeRepoRoot.getFileName()
                    + "/" + run.getMetadata().get("compile_namespace")
                    + "/dags/" + codegenSlug + "_dag.py";

            LocalAirflowCliAdapter adapter = new LocalAirflowCliAdapter(objectMapper);
            boolean dagDiscovered = adapter.awaitDagExists(dagId, Duration.ofSeconds(90), Duration.ofSeconds(3));
            if (!dagDiscovered) {
                List<Map<String, Object>> importErrors = adapter.listImportErrors();
                adapter.writeImportErrorEvidence(
                        scenarioSlug + "-live-runtime",
                        evidenceRoot.resolve("airflow-import-errors"),
                        dagId,
                        importErrors
                );
            }
            assertTrue(dagDiscovered, () -> "Airflow did not discover generated DAG for " + scenarioSlug);
            adapter.reserializeDag(dagSubdir);
            adapter.unpauseDag(dagId, dagSubdir);

            var handle = adapter.triggerDag(
                    dagId,
                    executionDate,
                    Map.of(
                            "scenario_id", scenarioSlug + "-live-runtime",
                            "generation_run_id", run.getId()
                    ),
                    dagSubdir
            );
            assertNotNull(handle.runId());

            var status = adapter.awaitTerminalState(dagId, executionDate, Duration.ofSeconds(120), Duration.ofSeconds(5), dagSubdir);
            List<Map<String, Object>> taskStates = adapter.taskStatesForDagRun(dagId, executionDate, dagSubdir);
            adapter.writeEvidence(
                    scenarioSlug + "-live-runtime",
                    evidenceRoot.resolve("airflow"),
                    handle,
                    status,
                    taskStates,
                    adapter.taskLogsForDagRun(dagId, handle.runId())
            );

            String dbtLogTail = readTail(runtimeRepoRoot.resolve("dbt_project/logs/dbt.log"), 4000);
            boolean taskChainSucceeded = taskStates.stream()
                    .map(task -> String.valueOf(task.get("state")))
                    .allMatch("success"::equals);
            String effectiveAirflowState = "success".equals(status.state()) || ("timeout".equals(status.state()) && taskChainSucceeded)
                    ? "success"
                    : status.state();
            if (!"success".equals(effectiveAirflowState)) {
                return new ProofResult(
                        evidenceRoot,
                        effectiveAirflowState,
                        null,
                        List.of(),
                        dbtLogTail
                );
            }

            Path validationRoot = runtimeRepoRoot.resolve("validation-output");
            Files.createDirectories(validationRoot);
            Path projectedOutput = validationRoot.resolve("actual-" + scenarioSlug);
            Path exportScript = validationRoot.resolve("export_actual_" + scenarioSlug + ".py");
            Path fixtureManifestPath = Path.of("src/test/resources/e2e/fixtures/loan_master/fixture-manifest.json");
            String goldOutputUri = "s3a://" + STORAGE_ROOT_LAKE + "/servicing/" + SOR_SLUG + "/" + pathPipelineSlug
                    + "/gold/write-loan-master-to-lake/";
            String containerRepoRoot = "/opt/pulse/repo/" + runtimeRepoRoot.getFileName();
            writeDeltaProjectionScript(
                    exportScript,
                    codegenSlug,
                    goldOutputUri,
                    containerRepoRoot + "/validation-output/actual-" + scenarioSlug,
                    fixtureManifestDerivativeColumns(fixtureManifestPath, "schema_add_servicing_risk_band"),
                    fixtureManifestDerivativeColumnsByType(fixtureManifestPath, "schema_add_servicing_risk_band", "boolean")
            );
            runCommand(List.of(
                    "docker", "exec", "pulse-airflow-1", "bash", "-lc",
                    "/home/airflow/.local/bin/python " + containerRepoRoot + "/validation-output/" + exportScript.getFileName()
            ));

            MinioOutputOracleValidator.ValidationResult outputValidation = new MinioOutputOracleValidator(objectMapper).validate(
                    new MinioOutputOracleValidator.ProbeRequest(
                            scenarioSlug + "-live-runtime",
                            run.getId(),
                            Path.of("src/test/resources/e2e/oracle/loan_master/data-oracle.json"),
                            projectedOutput.toUri().toString(),
                            null,
                            evidenceRoot.resolve("data"),
                            fixtureManifestDerivativeOverrides(fixtureManifestPath, "schema_add_servicing_risk_band")
                    )
            );

            return new ProofResult(
                    evidenceRoot,
                    effectiveAirflowState,
                    outputValidation.verdict(),
                    outputValidation.failureCodes(),
                    dbtLogTail
            );
        } finally {
            if (!retainRuntimeRepo) {
                deleteRecursively(runtimeRepoRoot);
            }
        }
    }

    private ProofResult runDedupeProof() throws Exception {
        String scenarioSlug = "dedupe-and-merge";
        Path airflowRepoBase = Path.of("/Users/aameradam/pulse-repos");
        assertTrue(Files.isDirectory(airflowRepoBase), "Expected Airflow-mounted repo base to exist");

        LoanMasterFixture fixture = LoanMasterFixture.loadCanonical();
        String unique = UUID.randomUUID().toString().substring(0, 8);
        String pipelineName = "Dedupe And Merge Runtime Proof " + unique;
        String codegenSlug = underscoreSlug(pipelineName);
        String pathPipelineSlug = Slugify.slugify(codegenSlug);
        String dagId = "pulse_" + codegenSlug + "_v1";
        String executionDate = "2026-04-26T04:00:00+00:00";
        String tenantId = "tenant-e2e-" + scenarioSlug + "-" + unique;
        Path runtimeRepoRoot = airflowRepoBase.resolve("omx-" + scenarioSlug + "-" + unique);
        Path evidenceRoot = Path.of("build/e2e-airflow-runtime").resolve(scenarioSlug + "-" + unique);
        boolean retainRuntimeRepo = Boolean.getBoolean("pulse.e2e.retainRuntimeRepo")
                || Boolean.parseBoolean(System.getenv("PULSE_E2E_RETAIN_RUNTIME_REPO"));

        Files.createDirectories(runtimeRepoRoot);
        Files.createDirectories(evidenceRoot);

        try {
            Path duplicatedFixture = evidenceRoot.resolve("source/loan_master_with_duplicates.csv");
            createDuplicateAugmentedCsv(fixture.path(), duplicatedFixture);
            seedLocalDpcStorageBackend(tenantId);
            seedBlueprints("DedupeAndMerge");
            PipelineFixture fixtureContext = createPipelineFixture(
                    tenantId,
                    pipelineName,
                    "DedupeAndMerge",
                    List.of(),
                    List.of(),
                    "deduped_output"
            );

            stageLoanMasterCsvForPipeline(pathPipelineSlug, duplicatedFixture);
            scaffoldTenantRuntimeRepo(runtimeRepoRoot, tenantId);

            GenerationRun run = codeGenerationService.generate(
                    fixtureContext.pipeline().getId(),
                    fixtureContext.version().getId(),
                    tenantId,
                    "worker-2"
            );
            assertEquals("COMPLETED", run.getStatus(), () -> "generation failed: " + run.getErrorMessage());
            assertFalse(generatedArtifactRepository.findByGenerationRunIdOrderByFilePathAsc(run.getId()).isEmpty());

            LocalRuntimeBridge bridge = new LocalRuntimeBridge(
                    generatedArtifactRepository,
                    gitRepoRepository,
                    objectMapper
            );
            LocalRuntimeBridge.RuntimeBridgeResult bridgeResult = bridge.materialize(
                    new LocalRuntimeBridge.BridgeRequest(
                            scenarioSlug + "-live-runtime",
                            run.getId(),
                            tenantId,
                            String.valueOf(run.getMetadata().get("compile_namespace")),
                            runtimeRepoRoot,
                            evidenceRoot.resolve("bridge")
                    )
            );

            String outputBaseUri = "s3a://" + STORAGE_ROOT_LAKE + "/home-lending/servicing/" + pathPipelineSlug;
            new LocalRuntimeArtifactRenderer(objectMapper).render(new LocalRuntimeArtifactRenderer.RenderRequest(
                    scenarioSlug + "-live-runtime",
                    String.valueOf(run.getMetadata().get("compile_namespace")),
                    bridgeResult.materializedRoot(),
                    evidenceRoot.resolve("render"),
                    outputBaseUri,
                    "/opt/pulse/repo/" + runtimeRepoRoot.getFileName() + "/dbt_project",
                    Map.of("write_loan_master_to_lake", underscoreSlug(defaultNameFor("DedupeAndMerge")))
            ));

            String dagSubdir = "/opt/pulse/repo/" + runtimeRepoRoot.getFileName()
                    + "/" + run.getMetadata().get("compile_namespace")
                    + "/dags/" + codegenSlug + "_dag.py";

            LocalAirflowCliAdapter adapter = new LocalAirflowCliAdapter(objectMapper);
            boolean dagDiscovered = adapter.awaitDagExists(dagId, Duration.ofSeconds(90), Duration.ofSeconds(3));
            if (!dagDiscovered) {
                List<Map<String, Object>> importErrors = adapter.listImportErrors();
                adapter.writeImportErrorEvidence(
                        scenarioSlug + "-live-runtime",
                        evidenceRoot.resolve("airflow-import-errors"),
                        dagId,
                        importErrors
                );
            }
            assertTrue(dagDiscovered, "Airflow did not discover generated DAG for DedupeAndMerge");
            adapter.reserializeDag(dagSubdir);
            adapter.unpauseDag(dagId, dagSubdir);

            LocalAirflowCliAdapter.DagRunHandle handle = adapter.triggerDag(
                    dagId,
                    executionDate,
                    Map.of(
                            "scenario_id", scenarioSlug + "-live-runtime",
                            "generation_run_id", run.getId()
                    ),
                    dagSubdir
            );
            assertNotNull(handle.runId());

            LocalAirflowCliAdapter.DagRunStatus status = adapter.awaitTerminalState(
                    dagId,
                    executionDate,
                    Duration.ofSeconds(120),
                    Duration.ofSeconds(5),
                    dagSubdir
            );

            List<Map<String, Object>> taskStates = adapter.taskStatesForDagRun(dagId, executionDate, dagSubdir);
            List<Map<String, Object>> taskLogs = adapter.taskLogsForDagRun(dagId, handle.runId());
            adapter.writeEvidence(
                    scenarioSlug + "-live-runtime",
                    evidenceRoot.resolve("airflow"),
                    handle,
                    status,
                    taskStates,
                    taskLogs
            );

            String dbtLogTail = readTail(runtimeRepoRoot.resolve("dbt_project/logs/dbt.log"), 4000);
            boolean taskChainSucceeded = taskStates.stream()
                    .map(task -> String.valueOf(task.get("state")))
                    .allMatch("success"::equals);
            String effectiveAirflowState = "success".equals(status.state()) || ("timeout".equals(status.state()) && taskChainSucceeded)
                    ? "success"
                    : status.state();
            if (!"success".equals(effectiveAirflowState)) {
                return new ProofResult(
                        evidenceRoot,
                        effectiveAirflowState,
                        null,
                        List.of(),
                        dbtLogTail
                );
            }

            Path validationRoot = runtimeRepoRoot.resolve("validation-output");
            Files.createDirectories(validationRoot);
            Path sourceCsvCopy = validationRoot.resolve("loan_master_with_duplicates.csv");
            Files.copy(duplicatedFixture, sourceCsvCopy);

            List<String> projectionColumns = csvHeaders(fixture.path());
            String containerRepoRoot = "/opt/pulse/repo/" + runtimeRepoRoot.getFileName();
            String silverOutputUri = "s3a://" + STORAGE_ROOT_LAKE + "/servicing/" + SOR_SLUG + "/"
                    + pathPipelineSlug + "/gold/write-loan-master-to-lake/";
            Path actualCsvOutput = validationRoot.resolve("actual-" + scenarioSlug);
            Path expectedCsvOutput = validationRoot.resolve("expected-" + scenarioSlug);
            Path actualExportScript = validationRoot.resolve("export_actual_" + scenarioSlug + ".py");
            Path expectedExportScript = validationRoot.resolve("export_expected_" + scenarioSlug + ".py");

            writeActualRowProjectionScript(
                    actualExportScript,
                    codegenSlug,
                    silverOutputUri,
                    containerRepoRoot + "/validation-output/actual-" + scenarioSlug,
                    projectionColumns
            );
            writeExpectedDedupeProjectionScript(
                    expectedExportScript,
                    codegenSlug,
                    containerRepoRoot + "/validation-output/" + sourceCsvCopy.getFileName(),
                    containerRepoRoot + "/validation-output/expected-" + scenarioSlug,
                    projectionColumns
            );

            runCommand(List.of(
                    "docker", "exec", "pulse-airflow-1", "bash", "-lc",
                    "/home/airflow/.local/bin/python " + containerRepoRoot + "/validation-output/" + actualExportScript.getFileName()
            ));
            runCommand(List.of(
                    "docker", "exec", "pulse-airflow-1", "bash", "-lc",
                    "/home/airflow/.local/bin/python " + containerRepoRoot + "/validation-output/" + expectedExportScript.getFileName()
            ));

            Map<String, Object> expectedOverrides = expectedOverridesFromCsv(expectedCsvOutput);
            MinioOutputOracleValidator.ValidationResult outputValidation = new MinioOutputOracleValidator(objectMapper).validate(
                    new MinioOutputOracleValidator.ProbeRequest(
                            scenarioSlug + "-live-runtime",
                            run.getId(),
                            Path.of("src/test/resources/e2e/oracle/loan_master/data-oracle.json"),
                            actualCsvOutput.toUri().toString(),
                            null,
                            evidenceRoot.resolve("data"),
                            expectedOverrides
                    )
            );

            return new ProofResult(
                    evidenceRoot,
                    effectiveAirflowState,
                    outputValidation.verdict(),
                    outputValidation.failureCodes(),
                    dbtLogTail
            );
        } finally {
            if (!retainRuntimeRepo) {
                deleteRecursively(runtimeRepoRoot);
            }
        }
    }

    private PipelineFixture createPipelineFixture(String tenantId,
                                                 String pipelineName,
                                                 String blueprintKey,
                                                 List<String> groupByColumns,
                                                 List<Map<String, Object>> aggregations,
                                                 String outputPort) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        Domain domain = new Domain();
        domain.setId("domain-" + suffix);
        domain.setTenantId(tenantId);
        domain.setName("Servicing");
        domain.setSlug("servicing");
        domainRepository.save(domain);

        Pipeline pipeline = new Pipeline();
        pipeline.setId("pipeline-" + suffix);
        pipeline.setTenantId(tenantId);
        pipeline.setDomainId(domain.getId());
        pipeline.setDomainName(domain.getName());
        pipeline.setName(pipelineName);
        pipeline.setCreatedBy("worker-2");
        pipelineRepository.save(pipeline);

        PipelineVersion version = new PipelineVersion();
        version.setId("version-" + suffix);
        version.setPipelineId(pipeline.getId());
        version.setRevision(1);
        version.setLifecycleStage(PipelineStage.ENGINEERING);
        version.setCreatedBy("worker-2");
        version = pipelineVersionRepository.save(version);
        pipeline.setActiveVersionId(version.getId());
        pipelineRepository.save(pipeline);

        SystemOfRecord sor = new SystemOfRecord();
        sor.setId("sor-" + suffix);
        sor.setTenantId(tenantId);
        sor.setName(SOR_NAME);
        sor.setDescription("Aggregate blueprint live runtime proof source");
        sor.setDomainId(domain.getId());
        sor.setDomainName(domain.getName());
        sor.setOwnerId("worker-2");
        sor.setMetadata(Map.of());
        systemOfRecordRepository.save(sor);

        ConnectorDefinition sourceDef = new ConnectorDefinition();
        sourceDef.setId("conn-def-source-" + suffix);
        sourceDef.setName("S3-compatible Object Storage " + suffix);
        sourceDef.setConnectorType(ConnectorType.SOURCE);
        sourceDef.setDockerRepository("pulse/source-s3");
        sourceDef.setDockerImageTag("1.0.0");
        sourceDef.setConnectionSpec(Map.of());
        sourceDef.setSupportedModes(List.of("full_refresh"));
        sourceDef.setReleaseStage(ReleaseStage.GENERALLY_AVAILABLE);
        connectorDefinitionRepository.save(sourceDef);

        ConnectorDefinition destDef = new ConnectorDefinition();
        destDef.setId("conn-def-dest-" + suffix);
        destDef.setName("Delta Lake " + suffix);
        destDef.setConnectorType(ConnectorType.DESTINATION);
        destDef.setDockerRepository("pulse/destination-delta-lake");
        destDef.setDockerImageTag("1.0.0");
        destDef.setConnectionSpec(Map.of());
        destDef.setSupportedModes(List.of("overwrite"));
        destDef.setReleaseStage(ReleaseStage.GENERALLY_AVAILABLE);
        connectorDefinitionRepository.save(destDef);

        ConnectorInstance source = new ConnectorInstance();
        source.setId("conn-inst-source-" + suffix);
        source.setSorId(sor.getId());
        source.setConnectorDefinitionId(sourceDef.getId());
        source.setName("Loan Drops");
        source.setConfigTemplate(Map.of(
                "bucket", STORAGE_ROOT_FILES,
                "path_prefix", "servicing/" + SOR_SLUG + "/",
                "file_format", "csv",
                "header", "true",
                "infer_schema", "true"
        ));
        connectorInstanceRepository.save(source);

        ConnectorInstance destination = new ConnectorInstance();
        destination.setId("conn-inst-dest-" + suffix);
        destination.setSorId(sor.getId());
        destination.setConnectorDefinitionId(destDef.getId());
        destination.setName("Delta Lakehouse");
        destination.setConfigTemplate(Map.of(
                "lake_format", "delta",
                "write_mode", "overwrite"
        ));
        connectorInstanceRepository.save(destination);

        var ingest = compositionService.addInstance(
                pipeline.getId(),
                version.getId(),
                "FileIngestion",
                "Ingest Loan Master",
                Map.of(
                        "connector_instance_id", source.getId(),
                        "connector_name", source.getName(),
                        "file_format", "csv",
                        "header", "true",
                        "infer_schema", "true",
                        "storage_backend", STORAGE_BACKEND,
                        "lake_layer", "bronze",
                        "lake_format", "delta"
                )
        );
        var aggregate = compositionService.addInstance(
                pipeline.getId(),
                version.getId(),
                blueprintKey,
                defaultNameFor(blueprintKey),
                paramsFor(blueprintKey, groupByColumns, aggregations)
        );
        var sink = compositionService.addInstance(
                pipeline.getId(),
                version.getId(),
                "LakeWriter",
                "Write Loan Master To Lake",
                Map.of(
                        "connector_instance_id", destination.getId(),
                        "connector_name", destination.getName(),
                        "lake_format", "delta",
                        "lake_layer", "gold",
                        "write_mode", "overwrite",
                        "storage_backend", STORAGE_BACKEND
                )
        );

        if ("GenericRouter".equals(blueprintKey)) {
            var passthroughFilter = compositionService.addInstance(
                    pipeline.getId(),
                    version.getId(),
                    "GenericFilter",
                    "Pass Through Loan Master",
                    Map.of(
                            "filter_mode", "sql",
                            "raw_sql", "1 = 1",
                            "storage_backend", STORAGE_BACKEND,
                            "lake_layer", "silver",
                            "lake_format", "delta"
                    )
            );
            compositionService.wirePort(version.getId(), ingest.getId(), "raw_output", passthroughFilter.getId(), "data_input");
            compositionService.wirePort(version.getId(), passthroughFilter.getId(), "filtered_output", aggregate.getId(), "data_input");
        } else if ("GenericJoin".equals(blueprintKey)) {
            compositionService.wirePort(version.getId(), ingest.getId(), "raw_output", aggregate.getId(), "left_input");
            compositionService.wirePort(version.getId(), ingest.getId(), "raw_output", aggregate.getId(), "right_input");
        } else if ("WideDenormalizedMart".equals(blueprintKey)) {
            compositionService.wirePort(version.getId(), ingest.getId(), "raw_output", aggregate.getId(), "fact_data");
            compositionService.wirePort(version.getId(), ingest.getId(), "raw_output", aggregate.getId(), "dimension_data");
        } else {
            compositionService.wirePort(version.getId(), ingest.getId(), "raw_output", aggregate.getId(),
                    inputPortFor(blueprintKey));
        }
        compositionService.wirePort(version.getId(), aggregate.getId(), outputPort, sink.getId(), "data_input");

        return new PipelineFixture(pipeline, version);
    }

    private PipelineFixture createFileArrivalSensorPipelineFixture(String tenantId,
                                                                   String pipelineName,
                                                                   String watchedObjectPath) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        Domain domain = new Domain();
        domain.setId("domain-" + suffix);
        domain.setTenantId(tenantId);
        domain.setName("Servicing");
        domain.setSlug("servicing");
        domainRepository.save(domain);

        Pipeline pipeline = new Pipeline();
        pipeline.setId("pipeline-" + suffix);
        pipeline.setTenantId(tenantId);
        pipeline.setDomainId(domain.getId());
        pipeline.setDomainName(domain.getName());
        pipeline.setName(pipelineName);
        pipeline.setCreatedBy("worker-2");
        pipelineRepository.save(pipeline);

        PipelineVersion version = new PipelineVersion();
        version.setId("version-" + suffix);
        version.setPipelineId(pipeline.getId());
        version.setRevision(1);
        version.setLifecycleStage(PipelineStage.ENGINEERING);
        version.setCreatedBy("worker-2");
        version = pipelineVersionRepository.save(version);
        pipeline.setActiveVersionId(version.getId());
        pipelineRepository.save(pipeline);

        SystemOfRecord sor = new SystemOfRecord();
        sor.setId("sor-" + suffix);
        sor.setTenantId(tenantId);
        sor.setName(SOR_NAME);
        sor.setDescription("File arrival sensor live runtime proof source");
        sor.setDomainId(domain.getId());
        sor.setDomainName(domain.getName());
        sor.setOwnerId("worker-2");
        sor.setMetadata(Map.of());
        systemOfRecordRepository.save(sor);

        ConnectorDefinition sourceDef = new ConnectorDefinition();
        sourceDef.setId("conn-def-source-" + suffix);
        sourceDef.setName("S3-compatible Object Storage " + suffix);
        sourceDef.setConnectorType(ConnectorType.SOURCE);
        sourceDef.setDockerRepository("pulse/source-s3");
        sourceDef.setDockerImageTag("1.0.0");
        sourceDef.setConnectionSpec(Map.of());
        sourceDef.setSupportedModes(List.of("full_refresh"));
        sourceDef.setReleaseStage(ReleaseStage.GENERALLY_AVAILABLE);
        connectorDefinitionRepository.save(sourceDef);

        ConnectorDefinition destDef = new ConnectorDefinition();
        destDef.setId("conn-def-dest-" + suffix);
        destDef.setName("Delta Lake " + suffix);
        destDef.setConnectorType(ConnectorType.DESTINATION);
        destDef.setDockerRepository("pulse/destination-delta-lake");
        destDef.setDockerImageTag("1.0.0");
        destDef.setConnectionSpec(Map.of());
        destDef.setSupportedModes(List.of("overwrite"));
        destDef.setReleaseStage(ReleaseStage.GENERALLY_AVAILABLE);
        connectorDefinitionRepository.save(destDef);

        ConnectorInstance source = new ConnectorInstance();
        source.setId("conn-inst-source-" + suffix);
        source.setSorId(sor.getId());
        source.setConnectorDefinitionId(sourceDef.getId());
        source.setName("Loan Drops");
        source.setConfigTemplate(Map.of(
                "bucket", STORAGE_ROOT_FILES,
                "path_prefix", "servicing/" + SOR_SLUG + "/",
                "file_format", "csv",
                "header", "true",
                "infer_schema", "true"
        ));
        connectorInstanceRepository.save(source);

        ConnectorInstance destination = new ConnectorInstance();
        destination.setId("conn-inst-dest-" + suffix);
        destination.setSorId(sor.getId());
        destination.setConnectorDefinitionId(destDef.getId());
        destination.setName("Delta Lakehouse");
        destination.setConfigTemplate(Map.of(
                "lake_format", "delta",
                "write_mode", "overwrite"
        ));
        connectorInstanceRepository.save(destination);

        compositionService.addInstance(
                pipeline.getId(),
                version.getId(),
                "FileArrivalSensor",
                "Wait For Loan File",
                Map.of(
                        "storage_kind", "s3",
                        "path", watchedObjectPath,
                        "poke_interval_seconds", 5,
                        "timeout_seconds", 60,
                        "mode", "poke",
                        "storage_backend", STORAGE_BACKEND,
                        "lake_layer", "control_plane"
                )
        );
        var ingest = compositionService.addInstance(
                pipeline.getId(),
                version.getId(),
                "FileIngestion",
                "Ingest Loan Master",
                Map.of(
                        "connector_instance_id", source.getId(),
                        "connector_name", source.getName(),
                        "file_format", "csv",
                        "header", "true",
                        "infer_schema", "true",
                        "storage_backend", STORAGE_BACKEND,
                        "lake_layer", "bronze",
                        "lake_format", "delta"
                )
        );
        var filter = compositionService.addInstance(
                pipeline.getId(),
                version.getId(),
                "GenericFilter",
                "Pass Through Loan Master",
                Map.of(
                        "filter_mode", "sql",
                        "raw_sql", "1 = 1",
                        "storage_backend", STORAGE_BACKEND,
                        "lake_layer", "silver",
                        "lake_format", "delta"
                )
        );
        var sink = compositionService.addInstance(
                pipeline.getId(),
                version.getId(),
                "LakeWriter",
                "Write Loan Master To Lake",
                Map.of(
                        "connector_instance_id", destination.getId(),
                        "connector_name", destination.getName(),
                        "lake_format", "delta",
                        "lake_layer", "gold",
                        "write_mode", "overwrite",
                        "storage_backend", STORAGE_BACKEND
                )
        );

        compositionService.wirePort(version.getId(), ingest.getId(), "raw_output", filter.getId(), "data_input");
        compositionService.wirePort(version.getId(), filter.getId(), "filtered_output", sink.getId(), "data_input");

        return new PipelineFixture(pipeline, version);
    }

    private PipelineFixture createDatabaseWriterPipelineFixture(String tenantId,
                                                               String pipelineName,
                                                               String tableName) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        Domain domain = new Domain();
        domain.setId("domain-" + suffix);
        domain.setTenantId(tenantId);
        domain.setName("Servicing");
        domain.setSlug("servicing");
        domainRepository.save(domain);

        Pipeline pipeline = new Pipeline();
        pipeline.setId("pipeline-" + suffix);
        pipeline.setTenantId(tenantId);
        pipeline.setDomainId(domain.getId());
        pipeline.setDomainName(domain.getName());
        pipeline.setName(pipelineName);
        pipeline.setCreatedBy("worker-2");
        pipelineRepository.save(pipeline);

        PipelineVersion version = new PipelineVersion();
        version.setId("version-" + suffix);
        version.setPipelineId(pipeline.getId());
        version.setRevision(1);
        version.setLifecycleStage(PipelineStage.ENGINEERING);
        version.setCreatedBy("worker-2");
        version = pipelineVersionRepository.save(version);
        pipeline.setActiveVersionId(version.getId());
        pipelineRepository.save(pipeline);

        SystemOfRecord sor = new SystemOfRecord();
        sor.setId("sor-" + suffix);
        sor.setTenantId(tenantId);
        sor.setName(SOR_NAME);
        sor.setDescription("Database writer live runtime proof source");
        sor.setDomainId(domain.getId());
        sor.setDomainName(domain.getName());
        sor.setOwnerId("worker-2");
        sor.setMetadata(Map.of());
        systemOfRecordRepository.save(sor);

        ConnectorDefinition sourceDef = new ConnectorDefinition();
        sourceDef.setId("conn-def-source-" + suffix);
        sourceDef.setName("S3-compatible Object Storage " + suffix);
        sourceDef.setConnectorType(ConnectorType.SOURCE);
        sourceDef.setDockerRepository("pulse/source-s3");
        sourceDef.setDockerImageTag("1.0.0");
        sourceDef.setConnectionSpec(Map.of());
        sourceDef.setSupportedModes(List.of("full_refresh"));
        sourceDef.setReleaseStage(ReleaseStage.GENERALLY_AVAILABLE);
        connectorDefinitionRepository.save(sourceDef);

        ConnectorDefinition destDef = new ConnectorDefinition();
        destDef.setId("conn-def-postgres-" + suffix);
        destDef.setName("PostgreSQL Destination " + suffix);
        destDef.setConnectorType(ConnectorType.DESTINATION);
        destDef.setDockerRepository("pulse/destination-postgres");
        destDef.setDockerImageTag("1.0.0");
        destDef.setConnectionSpec(Map.of());
        destDef.setSupportedModes(List.of("append"));
        destDef.setReleaseStage(ReleaseStage.GENERALLY_AVAILABLE);
        connectorDefinitionRepository.save(destDef);

        ConnectorInstance source = new ConnectorInstance();
        source.setId("conn-inst-source-" + suffix);
        source.setSorId(sor.getId());
        source.setConnectorDefinitionId(sourceDef.getId());
        source.setName("Loan Drops");
        source.setConfigTemplate(Map.of(
                "bucket", STORAGE_ROOT_FILES,
                "path_prefix", "servicing/" + SOR_SLUG + "/",
                "file_format", "csv",
                "header", "true",
                "infer_schema", "true"
        ));
        connectorInstanceRepository.save(source);

        ConnectorInstance destination = new ConnectorInstance();
        destination.setId("conn-inst-pg-" + suffix);
        destination.setSorId(sor.getId());
        destination.setConnectorDefinitionId(destDef.getId());
        destination.setName("PostgreSQL Destination");
        destination.setConfigTemplate(Map.of(
                "database_type", "postgresql",
                "host", "postgres",
                "port", "5432",
                "database", "pulse",
                "schema", "public"
        ));
        connectorInstanceRepository.save(destination);

        CredentialProfile credential = new CredentialProfile();
        credential.setId("cred-postgres-" + suffix);
        credential.setConnectorInstanceId(destination.getId());
        credential.setEnvironment("DEV");
        credential.setStatus(CredentialStatus.VALID);
        credential.setConnectionConfig(Map.of(
                "database_type", "postgresql",
                "host", "postgres",
                "port", "5432",
                "database", "pulse",
                "schema", "public",
                "driver", "org.postgresql.Driver",
                "username", "pulse",
                "password", "pulse"
        ));
        credentialProfileRepository.save(credential);

        var ingest = compositionService.addInstance(
                pipeline.getId(),
                version.getId(),
                "FileIngestion",
                "Ingest Loan Master",
                Map.of(
                        "connector_instance_id", source.getId(),
                        "connector_name", source.getName(),
                        "file_format", "csv",
                        "header", "true",
                        "infer_schema", "true",
                        "storage_backend", STORAGE_BACKEND,
                        "lake_layer", "bronze",
                        "lake_format", "delta"
                )
        );
        var filter = compositionService.addInstance(
                pipeline.getId(),
                version.getId(),
                "GenericFilter",
                "Filter Delinquent Loans",
                Map.of(
                        "filter_mode", "sql",
                        "raw_sql", "months_delinquent > 0",
                        "storage_backend", STORAGE_BACKEND,
                        "lake_layer", "silver",
                        "lake_format", "delta"
                )
        );
        var sink = compositionService.addInstance(
                pipeline.getId(),
                version.getId(),
                "DatabaseWriter",
                "Write Delinquent Loans To Postgres",
                Map.of(
                        "connector_instance_id", destination.getId(),
                        "connector_name", destination.getName(),
                        "target_id", "loan-master-postgres-target",
                        "target_table", "public." + tableName,
                        "write_mode", "append",
                        "batch_size", 5000
                )
        );

        compositionService.wirePort(version.getId(), ingest.getId(), "raw_output", filter.getId(), "data_input");
        compositionService.wirePort(version.getId(), filter.getId(), "filtered_output", sink.getId(), "data_input");

        return new PipelineFixture(pipeline, version);
    }

    private PipelineFixture createSnapshotIngestionPipelineFixture(String tenantId,
                                                                   String pipelineName,
                                                                   String tableName) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        Domain domain = new Domain();
        domain.setId("domain-" + suffix);
        domain.setTenantId(tenantId);
        domain.setName("Servicing");
        domain.setSlug("servicing");
        domain.setDescription("Mortgage servicing domain");
        domainRepository.save(domain);

        Pipeline pipeline = new Pipeline();
        pipeline.setId("pipeline-" + suffix);
        pipeline.setTenantId(tenantId);
        pipeline.setDomainId(domain.getId());
        pipeline.setDomainName(domain.getName());
        pipeline.setName(pipelineName);
        pipeline.setCreatedBy("worker-2");
        pipelineRepository.save(pipeline);

        PipelineVersion version = new PipelineVersion();
        version.setId("version-" + suffix);
        version.setPipelineId(pipeline.getId());
        version.setRevision(1);
        version.setLifecycleStage(PipelineStage.ENGINEERING);
        version.setCreatedBy("worker-2");
        version = pipelineVersionRepository.save(version);
        pipeline.setActiveVersionId(version.getId());
        pipelineRepository.save(pipeline);

        SystemOfRecord sor = new SystemOfRecord();
        sor.setId("sor-" + suffix);
        sor.setTenantId(tenantId);
        sor.setName(SOR_NAME);
        sor.setDescription("Snapshot ingestion live runtime proof source");
        sor.setDomainId(domain.getId());
        sor.setDomainName(domain.getName());
        sor.setOwnerId("worker-2");
        sor.setMetadata(Map.of());
        systemOfRecordRepository.save(sor);

        ConnectorDefinition sourceDef = new ConnectorDefinition();
        sourceDef.setId("conn-def-pgsrc-" + suffix);
        sourceDef.setName("PostgreSQL Source " + suffix);
        sourceDef.setConnectorType(ConnectorType.SOURCE);
        sourceDef.setDockerRepository("pulse/source-postgres");
        sourceDef.setDockerImageTag("1.0.0");
        sourceDef.setConnectionSpec(Map.of());
        sourceDef.setSupportedModes(List.of("snapshot"));
        sourceDef.setReleaseStage(ReleaseStage.GENERALLY_AVAILABLE);
        connectorDefinitionRepository.save(sourceDef);

        ConnectorDefinition destDef = new ConnectorDefinition();
        destDef.setId("conn-def-delta-" + suffix);
        destDef.setName("Delta Lake Destination " + suffix);
        destDef.setConnectorType(ConnectorType.DESTINATION);
        destDef.setDockerRepository("pulse/destination-delta-lake");
        destDef.setDockerImageTag("1.0.0");
        destDef.setConnectionSpec(Map.of());
        destDef.setSupportedModes(List.of("overwrite"));
        destDef.setReleaseStage(ReleaseStage.GENERALLY_AVAILABLE);
        connectorDefinitionRepository.save(destDef);

        ConnectorInstance source = new ConnectorInstance();
        source.setId("conn-inst-pgsrc-" + suffix);
        source.setSorId(sor.getId());
        source.setConnectorDefinitionId(sourceDef.getId());
        source.setName("PostgreSQL Source");
        source.setConfigTemplate(Map.of(
                "database_type", "postgresql",
                "host", "postgres",
                "port", "5432",
                "database", "pulse",
                "schema", "public"
        ));
        connectorInstanceRepository.save(source);

        ConnectorInstance destination = new ConnectorInstance();
        destination.setId("conn-inst-delta-" + suffix);
        destination.setSorId(sor.getId());
        destination.setConnectorDefinitionId(destDef.getId());
        destination.setName("DPC Lake");
        destination.setConfigTemplate(Map.of(
                "lake_format", "delta"
        ));
        connectorInstanceRepository.save(destination);

        CredentialProfile credential = new CredentialProfile();
        credential.setId("cred-pg-source-" + suffix);
        credential.setConnectorInstanceId(source.getId());
        credential.setEnvironment("DEV");
        credential.setStatus(CredentialStatus.VALID);
        credential.setConnectionConfig(Map.of(
                "database_type", "postgresql",
                "host", "postgres",
                "port", "5432",
                "database", "pulse",
                "schema", "public",
                "driver", "org.postgresql.Driver",
                "username", "pulse",
                "password", "pulse"
        ));
        credentialProfileRepository.save(credential);

        var ingest = compositionService.addInstance(
                pipeline.getId(),
                version.getId(),
                "SnapshotIngestion",
                "Snapshot Loan Master",
                Map.of(
                        "connector_instance_id", source.getId(),
                        "connector_name", source.getName(),
                        "qualified_names", List.of("public.\"" + tableName + "\""),
                        "storage_backend", STORAGE_BACKEND,
                        "lake_layer", "bronze",
                        "lake_format", "delta"
                )
        );
        var filter = compositionService.addInstance(
                pipeline.getId(),
                version.getId(),
                "GenericFilter",
                "Pass Through Current Loans",
                Map.of(
                        "filter_mode", "sql",
                        "raw_sql", "1 = 1",
                        "storage_backend", STORAGE_BACKEND,
                        "lake_layer", "silver",
                        "lake_format", "delta"
                )
        );
        var sink = compositionService.addInstance(
                pipeline.getId(),
                version.getId(),
                "LakeWriter",
                "Write Loan Master To Lake",
                Map.of(
                        "connector_instance_id", destination.getId(),
                        "connector_name", destination.getName(),
                        "lake_format", "delta",
                        "lake_layer", "gold",
                        "write_mode", "overwrite",
                        "storage_backend", STORAGE_BACKEND
                )
        );

        compositionService.wirePort(version.getId(), ingest.getId(), "raw_output", filter.getId(), "data_input");
        compositionService.wirePort(version.getId(), filter.getId(), "filtered_output", sink.getId(), "data_input");

        return new PipelineFixture(pipeline, version);
    }

    private PipelineFixture createBulkBackfillPipelineFixture(String tenantId,
                                                              String pipelineName,
                                                              String tableName) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        Domain domain = new Domain();
        domain.setId("domain-" + suffix);
        domain.setTenantId(tenantId);
        domain.setName("Servicing");
        domain.setSlug("servicing");
        domain.setDescription("Mortgage servicing domain");
        domainRepository.save(domain);

        Pipeline pipeline = new Pipeline();
        pipeline.setId("pipeline-" + suffix);
        pipeline.setTenantId(tenantId);
        pipeline.setDomainId(domain.getId());
        pipeline.setDomainName(domain.getName());
        pipeline.setName(pipelineName);
        pipeline.setCreatedBy("worker-2");
        pipelineRepository.save(pipeline);

        PipelineVersion version = new PipelineVersion();
        version.setId("version-" + suffix);
        version.setPipelineId(pipeline.getId());
        version.setRevision(1);
        version.setLifecycleStage(PipelineStage.ENGINEERING);
        version.setCreatedBy("worker-2");
        version = pipelineVersionRepository.save(version);
        pipeline.setActiveVersionId(version.getId());
        pipelineRepository.save(pipeline);

        SystemOfRecord sor = new SystemOfRecord();
        sor.setId("sor-" + suffix);
        sor.setTenantId(tenantId);
        sor.setName(SOR_NAME);
        sor.setDescription("Bulk backfill live runtime proof source");
        sor.setDomainId(domain.getId());
        sor.setDomainName(domain.getName());
        sor.setOwnerId("worker-2");
        sor.setMetadata(Map.of());
        systemOfRecordRepository.save(sor);

        ConnectorDefinition sourceDef = new ConnectorDefinition();
        sourceDef.setId("conn-def-pgbkf-" + suffix);
        sourceDef.setName("PostgreSQL Backfill Source " + suffix);
        sourceDef.setConnectorType(ConnectorType.SOURCE);
        sourceDef.setDockerRepository("pulse/source-postgres");
        sourceDef.setDockerImageTag("1.0.0");
        sourceDef.setConnectionSpec(Map.of());
        sourceDef.setSupportedModes(List.of("backfill"));
        sourceDef.setReleaseStage(ReleaseStage.GENERALLY_AVAILABLE);
        connectorDefinitionRepository.save(sourceDef);

        ConnectorDefinition destDef = new ConnectorDefinition();
        destDef.setId("conn-def-dlbkf-" + suffix);
        destDef.setName("Delta Lake Destination " + suffix);
        destDef.setConnectorType(ConnectorType.DESTINATION);
        destDef.setDockerRepository("pulse/destination-delta-lake");
        destDef.setDockerImageTag("1.0.0");
        destDef.setConnectionSpec(Map.of());
        destDef.setSupportedModes(List.of("overwrite"));
        destDef.setReleaseStage(ReleaseStage.GENERALLY_AVAILABLE);
        connectorDefinitionRepository.save(destDef);

        ConnectorInstance source = new ConnectorInstance();
        source.setId("conn-inst-pgbkf-" + suffix);
        source.setSorId(sor.getId());
        source.setConnectorDefinitionId(sourceDef.getId());
        source.setName("PostgreSQL Backfill Source");
        source.setConfigTemplate(Map.of(
                "database_type", "postgresql",
                "host", "postgres",
                "port", "5432",
                "database", "pulse",
                "schema", "public"
        ));
        connectorInstanceRepository.save(source);

        ConnectorInstance destination = new ConnectorInstance();
        destination.setId("conn-inst-dlbkf-" + suffix);
        destination.setSorId(sor.getId());
        destination.setConnectorDefinitionId(destDef.getId());
        destination.setName("DPC Lake");
        destination.setConfigTemplate(Map.of(
                "lake_format", "delta"
        ));
        connectorInstanceRepository.save(destination);

        CredentialProfile credential = new CredentialProfile();
        credential.setId("cred-pg-backfill-" + suffix);
        credential.setConnectorInstanceId(source.getId());
        credential.setEnvironment("DEV");
        credential.setStatus(CredentialStatus.VALID);
        credential.setConnectionConfig(Map.of(
                "database_type", "postgresql",
                "host", "postgres",
                "port", "5432",
                "database", "pulse",
                "schema", "public",
                "driver", "org.postgresql.Driver",
                "username", "pulse",
                "password", "pulse"
        ));
        credentialProfileRepository.save(credential);

        var ingest = compositionService.addInstance(
                pipeline.getId(),
                version.getId(),
                "BulkBackfill",
                "Backfill Loan Master",
                Map.ofEntries(
                        Map.entry("connector_instance_id", source.getId()),
                        Map.entry("connector_name", source.getName()),
                        Map.entry("qualified_names", List.of("public.\"" + tableName + "\"")),
                        Map.entry("partition_column", "months_delinquent"),
                        Map.entry("lower_bound", "0"),
                        Map.entry("upper_bound", "12"),
                        Map.entry("num_partitions", "4"),
                        Map.entry("fetch_size", "10000"),
                        Map.entry("storage_backend", STORAGE_BACKEND),
                        Map.entry("lake_layer", "bronze"),
                        Map.entry("lake_format", "delta")
                )
        );
        var filter = compositionService.addInstance(
                pipeline.getId(),
                version.getId(),
                "GenericFilter",
                "Delinquent Loans",
                Map.of(
                        "filter_mode", "sql",
                        "raw_sql", "CAST(months_delinquent AS INT) > 0",
                        "storage_backend", STORAGE_BACKEND,
                        "lake_layer", "silver",
                        "lake_format", "delta"
                )
        );
        var sink = compositionService.addInstance(
                pipeline.getId(),
                version.getId(),
                "LakeWriter",
                "Write Loan Master To Lake",
                Map.of(
                        "connector_instance_id", destination.getId(),
                        "connector_name", destination.getName(),
                        "lake_format", "delta",
                        "lake_layer", "gold",
                        "write_mode", "overwrite",
                        "storage_backend", STORAGE_BACKEND
                )
        );

        compositionService.wirePort(version.getId(), ingest.getId(), "backfill_output", filter.getId(), "data_input");
        compositionService.wirePort(version.getId(), filter.getId(), "filtered_output", sink.getId(), "data_input");

        return new PipelineFixture(pipeline, version);
    }

    private Map<String, Object> aggregateParamsFor(String blueprintKey,
                                                   List<String> groupByColumns,
                                                   List<Map<String, Object>> aggregations) {
        if ("GenericAggregate".equals(blueprintKey)) {
            return Map.of(
                    "group_by_columns", groupByColumns,
                    "aggregations", aggregations,
                    "storage_backend", STORAGE_BACKEND,
                    "lake_layer", "gold",
                    "lake_format", "delta"
            );
        }
        return Map.of(
                "group_by", groupByColumns,
                "aggregations", aggregations,
                "refresh_strategy", "full_refresh",
                "storage_backend", STORAGE_BACKEND,
                "lake_layer", "gold",
                "lake_format", "delta"
        );
    }

    private Map<String, Object> paramsFor(String blueprintKey,
                                          List<String> groupByColumns,
                                          List<Map<String, Object>> aggregations) throws Exception {
        if ("DedupeAndMerge".equals(blueprintKey)) {
            return Map.of(
                    "match_keys", List.of("loan_id"),
                    "order_by_columns", List.of(
                            Map.of("column", "last_payment_date", "direction", "desc"),
                            Map.of("column", "loan_number", "direction", "asc")
                    ),
                    "match_strategy", "exact",
                    "merge_priority", "latest",
                    "dedup_method", "row_number",
                    "storage_backend", STORAGE_BACKEND,
                    "lake_layer", "silver",
                    "lake_format", "delta"
            );
        }
        if ("SchemaNormalization".equals(blueprintKey)) {
            return Map.of(
                    "target_schema", "loan_master_canonical",
                    "mapping_rules", Map.of("loan_id", "loan_id", "loan_number", "loan_number"),
                    "strict_mode", false,
                    "storage_backend", STORAGE_BACKEND,
                    "lake_layer", "silver",
                    "lake_format", "delta"
            );
        }
        if ("BronzeToSilverCleaning".equals(blueprintKey)) {
            return Map.of(
                    "storage_backend", STORAGE_BACKEND,
                    "lake_layer", "silver",
                    "lake_format", "delta"
            );
        }
        if ("GenericJoin".equals(blueprintKey)) {
            return Map.of(
                    "join_type", "inner",
                    "alias_left", "l",
                    "alias_right", "r",
                    "join_keys", List.of(Map.of("left_column", "loan_id", "right_column", "loan_id")),
                    "storage_backend", STORAGE_BACKEND,
                    "lake_layer", "silver",
                    "lake_format", "delta"
            );
        }
        if ("PIIMasking".equals(blueprintKey)) {
            return Map.of(
                    "columns_to_mask", List.of("borrower_ssn_masked"),
                    "source_columns", oracleColumns(Path.of("src/test/resources/e2e/oracle/loan_master/data-oracle.json")),
                    "masking_strategy", "preserve_last4",
                    "storage_backend", STORAGE_BACKEND,
                    "lake_layer", "silver",
                    "lake_format", "delta"
            );
        }
        if ("FeatureTablePublish".equals(blueprintKey)) {
            return Map.of(
                    "entity_key", "loan_id",
                    "features", List.of("current_upb", "interest_rate", "borrower_credit_score"),
                    "point_in_time_column", "origination_date",
                    "output_format", "delta",
                    "storage_backend", STORAGE_BACKEND,
                    "lake_layer", "gold",
                    "lake_format", "delta"
            );
        }
        if ("ReferenceDataPublish".equals(blueprintKey)) {
            return Map.of(
                    "reference_type", "property_state",
                    "publish_frequency", "daily",
                    "versioned", true,
                    "storage_backend", STORAGE_BACKEND,
                    "lake_layer", "gold",
                    "lake_format", "delta"
            );
        }
        if ("FactBuild".equals(blueprintKey)) {
            return Map.of(
                    "grain", List.of("loan_id"),
                    "dimension_keys", List.of("loan_status", "property_state"),
                    "measures", List.of("current_upb", "interest_rate"),
                    "business_concept", "loan_fact",
                    "storage_backend", STORAGE_BACKEND,
                    "lake_layer", "gold",
                    "lake_format", "delta"
            );
        }
        if ("WideDenormalizedMart".equals(blueprintKey)) {
            return Map.of(
                    "dimension_joins", List.of(Map.of("dimension", "loan_master_dimensions", "join_key", "loan_id")),
                    "fact_columns", List.of("loan_id", "current_upb", "interest_rate"),
                    "dimension_columns", List.of("loan_status", "property_state", "borrower_credit_score"),
                    "business_concept", "loan_wide_mart",
                    "storage_backend", STORAGE_BACKEND,
                    "lake_layer", "gold",
                    "lake_format", "delta"
            );
        }
        if ("IncrementalMerge".equals(blueprintKey)) {
            return Map.of(
                    "unique_key", List.of("loan_id"),
                    "watermark_column", "_pulse_processed_at",
                    "storage_backend", STORAGE_BACKEND,
                    "lake_layer", "gold",
                    "lake_format", "delta"
            );
        }
        if ("SCD2Dimension".equals(blueprintKey)) {
            return Map.of(
                    "unique_key", List.of("loan_id"),
                    "strategy", "timestamp",
                    "updated_at_column", "last_payment_date",
                    "storage_backend", STORAGE_BACKEND,
                    "lake_layer", "gold",
                    "lake_format", "delta"
            );
        }
        if ("SnapshotModel".equals(blueprintKey)) {
            return Map.of(
                    "unique_key", List.of("loan_id"),
                    "strategy", "timestamp",
                    "updated_at_column", "last_payment_date",
                    "storage_backend", STORAGE_BACKEND,
                    "lake_layer", "gold",
                    "lake_format", "delta"
            );
        }
        if ("DQValidator".equals(blueprintKey)) {
            return Map.of(
                    "expectations", List.of(Map.of(
                            "type", "ExpectColumnValuesToNotBeNull",
                            "kwargs", Map.of("column", "loan_id"),
                            "severity", "critical"
                    )),
                    "on_failure", "fail",
                    "storage_backend", STORAGE_BACKEND,
                    "lake_layer", "silver",
                    "lake_format", "delta"
            );
        }
        if ("FreshnessChecks".equals(blueprintKey)) {
            return Map.of(
                    "timestamp_column", "boarding_date",
                    "max_age_minutes", 525600,
                    "storage_backend", STORAGE_BACKEND,
                    "lake_layer", "silver",
                    "lake_format", "delta"
            );
        }
        if ("SchemaDriftDetection".equals(blueprintKey)) {
            return Map.of(
                    "expected_columns", csvHeaders(LoanMasterFixture.loadCanonical().path()),
                    "allow_extra_columns", true,
                    "strict_order", false,
                    "drift_policy", "warn",
                    "storage_backend", STORAGE_BACKEND,
                    "lake_layer", "silver",
                    "lake_format", "delta"
            );
        }
        if ("AnomalyDetection".equals(blueprintKey)) {
            return Map.of(
                    "monitored_columns", List.of("current_upb", "interest_rate"),
                    "detection_method", "z_score",
                    "sensitivity_percent", 99.7,
                    "storage_backend", STORAGE_BACKEND,
                    "lake_layer", "silver",
                    "lake_format", "delta"
            );
        }
        return aggregateParamsFor(blueprintKey, groupByColumns, aggregations);
    }

    private String inputPortFor(String blueprintKey) {
        return switch (blueprintKey) {
            case "GenericAggregate", "GenericRouter" -> "data_input";
            case "DedupeAndMerge" -> "input_data";
            case "FeatureTablePublish" -> "source_data";
            case "ReferenceDataPublish" -> "reference_source";
            case "FactBuild" -> "transaction_data";
            case "IncrementalMerge" -> "incremental_data";
            case "SCD2Dimension" -> "data_input";
            case "SnapshotModel" -> "source_data";
            case "DQValidator" -> "data_to_validate";
            case "FreshnessChecks" -> "monitored_dataset";
            case "SchemaDriftDetection" -> "incoming_data";
            case "AnomalyDetection" -> "data_to_monitor";
            case "SchemaNormalization" -> "source_data";
            case "BronzeToSilverCleaning" -> "raw_input";
            case "PIIMasking" -> "data_input";
            default -> "detail_data";
        };
    }

    private void seedBlueprints(String aggregateBlueprintKey) {
        String suffix = shortId(aggregateBlueprintKey);
        blueprintRepository.save(fileIngestionBlueprint("bpf-" + suffix));
        blueprintRepository.save(lakeWriterBlueprint("bps-" + suffix));
        if ("GenericAggregate".equals(aggregateBlueprintKey)) {
            blueprintRepository.save(genericAggregateBlueprint("bpa-" + suffix));
        } else if ("AggregateMaterialization".equals(aggregateBlueprintKey)) {
            blueprintRepository.save(aggregateMaterializationBlueprint("bpa-" + suffix));
        } else if ("DedupeAndMerge".equals(aggregateBlueprintKey)) {
            blueprintRepository.save(dedupeAndMergeBlueprint("bpa-" + suffix));
        } else if ("BronzeToSilverCleaning".equals(aggregateBlueprintKey)) {
            blueprintRepository.save(bronzeToSilverCleaningBlueprint("bpa-" + suffix));
        } else if ("GenericRouter".equals(aggregateBlueprintKey)) {
            blueprintRepository.save(genericFilterBlueprint("bpfilt-" + suffix));
            blueprintRepository.save(genericRouterBlueprint("bpa-" + suffix));
        } else if ("GenericJoin".equals(aggregateBlueprintKey)) {
            blueprintRepository.save(genericJoinBlueprint("bpa-" + suffix));
        } else if ("PIIMasking".equals(aggregateBlueprintKey)) {
            blueprintRepository.save(piiMaskingBlueprint("bpa-" + suffix));
        } else if ("SchemaNormalization".equals(aggregateBlueprintKey)) {
            blueprintRepository.save(schemaNormalizationBlueprint("bpa-" + suffix));
        } else if ("FeatureTablePublish".equals(aggregateBlueprintKey)) {
            blueprintRepository.save(featureTablePublishBlueprint("bpa-" + suffix));
        } else if ("ReferenceDataPublish".equals(aggregateBlueprintKey)) {
            blueprintRepository.save(referenceDataPublishBlueprint("bpa-" + suffix));
        } else if ("FactBuild".equals(aggregateBlueprintKey)) {
            blueprintRepository.save(factBuildBlueprint("bpa-" + suffix));
        } else if ("WideDenormalizedMart".equals(aggregateBlueprintKey)) {
            blueprintRepository.save(wideDenormalizedMartBlueprint("bpa-" + suffix));
        } else if ("IncrementalMerge".equals(aggregateBlueprintKey)) {
            blueprintRepository.save(incrementalMergeBlueprint("bpa-" + suffix));
        } else if ("SCD2Dimension".equals(aggregateBlueprintKey)) {
            blueprintRepository.save(scd2DimensionBlueprint("bpscd-" + suffix));
        } else if ("SnapshotModel".equals(aggregateBlueprintKey)) {
            blueprintRepository.save(snapshotModelBlueprint("bpsnp-" + suffix));
        } else if ("DQValidator".equals(aggregateBlueprintKey)) {
            blueprintRepository.save(dqValidatorBlueprint("bpdq-" + suffix));
        } else if ("FreshnessChecks".equals(aggregateBlueprintKey)) {
            blueprintRepository.save(freshnessChecksBlueprint("bpfresh-" + suffix));
        } else if ("SchemaDriftDetection".equals(aggregateBlueprintKey)) {
            blueprintRepository.save(schemaDriftDetectionBlueprint("bpsdrift-" + suffix));
        } else if ("AnomalyDetection".equals(aggregateBlueprintKey)) {
            blueprintRepository.save(anomalyDetectionBlueprint("bpanom-" + suffix));
        } else if ("DatabaseWriter".equals(aggregateBlueprintKey)) {
            blueprintRepository.save(genericFilterBlueprint("bpfilt-" + suffix));
            blueprintRepository.save(databaseWriterBlueprint("bpd-" + suffix));
        } else if ("SnapshotIngestion".equals(aggregateBlueprintKey)) {
            blueprintRepository.save(snapshotIngestionBlueprint("bpi-" + suffix));
            blueprintRepository.save(genericFilterBlueprint("bpfilt-" + suffix));
        } else if ("BulkBackfill".equals(aggregateBlueprintKey)) {
            blueprintRepository.save(bulkBackfillBlueprint("bpbkf-" + suffix));
            blueprintRepository.save(genericFilterBlueprint("bpfilt-" + suffix));
        } else {
            throw new IllegalArgumentException("Unsupported aggregate blueprint: " + aggregateBlueprintKey);
        }
    }

    private void seedFileArrivalSensorBlueprints() {
        String suffix = shortId("FileArrivalSensor");
        blueprintRepository.save(fileArrivalSensorBlueprint("bpas-" + suffix));
        blueprintRepository.save(fileIngestionBlueprint("bpf-" + suffix));
        blueprintRepository.save(genericFilterBlueprint("bpfilt-" + suffix));
        blueprintRepository.save(lakeWriterBlueprint("bps-" + suffix));
    }

    private Blueprint fileIngestionBlueprint(String id) {
        Blueprint bp = new Blueprint();
        bp.setId(id);
        bp.setBlueprintKey("FileIngestion");
        bp.setName("File Ingestion");
        bp.setCategory(BlueprintCategory.INGESTION);
        bp.setStatus("active");
        bp.setDeferred(false);
        bp.setDescription("Ingest files from storage");
        bp.setVersion("1.0.0");
        bp.setParamsSchema(List.of());
        bp.setInputPorts(List.of());
        bp.setOutputPorts(List.of(Map.of("name", "raw_output")));
        bp.setRuntimeRequirements(Map.of());
        bp.setPipelineConfig(false);
        bp.setValidLayers(List.of("bronze"));
        bp.setComputeBackend("spark");
        bp.setEmitStrategy("generate");
        return bp;
    }

    private Blueprint fileArrivalSensorBlueprint(String id) {
        Blueprint bp = new Blueprint();
        bp.setId(id);
        bp.setBlueprintKey("FileArrivalSensor");
        bp.setName("File Arrival Sensor");
        bp.setCategory(BlueprintCategory.ORCHESTRATION);
        bp.setStatus("active");
        bp.setDeferred(false);
        bp.setDescription("Wait for an expected source file to arrive");
        bp.setVersion("1.0.0");
        bp.setParamsSchema(List.of());
        bp.setInputPorts(List.of());
        bp.setOutputPorts(List.of(Map.of("name", "ready_signal")));
        bp.setRuntimeRequirements(Map.of());
        bp.setPipelineConfig(false);
        bp.setValidLayers(List.of("control_plane"));
        bp.setComputeBackend("airflow");
        bp.setCompositionRole("orchestration_sensor");
        bp.setEmitStrategy("generate");
        return bp;
    }

    private Blueprint snapshotIngestionBlueprint(String id) {
        Blueprint bp = new Blueprint();
        bp.setId(id);
        bp.setBlueprintKey("SnapshotIngestion");
        bp.setName("Snapshot Ingestion");
        bp.setCategory(BlueprintCategory.INGESTION);
        bp.setStatus("active");
        bp.setDeferred(false);
        bp.setDescription("Read a full source snapshot over JDBC");
        bp.setVersion("1.0.0");
        bp.setParamsSchema(List.of());
        bp.setInputPorts(List.of());
        bp.setOutputPorts(List.of(Map.of("name", "raw_output")));
        bp.setRuntimeRequirements(Map.of());
        bp.setPipelineConfig(false);
        bp.setValidLayers(List.of("bronze"));
        bp.setComputeBackend("spark");
        bp.setEmitStrategy("generate");
        return bp;
    }

    private Blueprint bulkBackfillBlueprint(String id) {
        Blueprint bp = new Blueprint();
        bp.setId(id);
        bp.setBlueprintKey("BulkBackfill");
        bp.setName("Bulk Backfill");
        bp.setCategory(BlueprintCategory.INGESTION);
        bp.setStatus("active");
        bp.setDeferred(false);
        bp.setDescription("Read a bounded JDBC backfill from a source system");
        bp.setVersion("1.0.0");
        bp.setParamsSchema(List.of());
        bp.setInputPorts(List.of());
        bp.setOutputPorts(List.of(Map.of("name", "backfill_output")));
        bp.setRuntimeRequirements(Map.of());
        bp.setPipelineConfig(false);
        bp.setValidLayers(List.of("bronze"));
        bp.setComputeBackend("spark");
        bp.setEmitStrategy("generate");
        return bp;
    }

    private Blueprint genericAggregateBlueprint(String id) {
        Blueprint bp = new Blueprint();
        bp.setId(id);
        bp.setBlueprintKey("GenericAggregate");
        bp.setName("Generic Aggregate");
        bp.setCategory(BlueprintCategory.TRANSFORM);
        bp.setStatus("active");
        bp.setDeferred(false);
        bp.setDescription("Group-by aggregate");
        bp.setVersion("1.0.0");
        bp.setParamsSchema(List.of());
        bp.setInputPorts(List.of(Map.of("name", "data_input")));
        bp.setOutputPorts(List.of(Map.of("name", "aggregated_output")));
        bp.setRuntimeRequirements(Map.of());
        bp.setPipelineConfig(false);
        bp.setValidLayers(List.of("silver", "gold"));
        bp.setComputeBackend("spark");
        bp.setEmitStrategy("generate");
        return bp;
    }

    private Blueprint aggregateMaterializationBlueprint(String id) {
        Blueprint bp = new Blueprint();
        bp.setId(id);
        bp.setBlueprintKey("AggregateMaterialization");
        bp.setName("Aggregate Materialization");
        bp.setCategory(BlueprintCategory.MODELING);
        bp.setStatus("active");
        bp.setDeferred(false);
        bp.setDescription("Materialize aggregate mart");
        bp.setVersion("1.0.0");
        bp.setParamsSchema(List.of());
        bp.setInputPorts(List.of(Map.of("name", "detail_data")));
        bp.setOutputPorts(List.of(Map.of("name", "aggregate_output")));
        bp.setRuntimeRequirements(Map.of());
        bp.setPipelineConfig(false);
        bp.setValidLayers(List.of("gold"));
        bp.setComputeBackend("spark");
        bp.setEmitStrategy("generate");
        return bp;
    }

    private Blueprint genericRouterBlueprint(String id) {
        Blueprint bp = new Blueprint();
        bp.setId(id);
        bp.setBlueprintKey("GenericRouter");
        bp.setName("Generic Router");
        bp.setCategory(BlueprintCategory.TRANSFORM);
        bp.setStatus("active");
        bp.setDeferred(false);
        bp.setDescription("Route rows to multiple outputs by predicate");
        bp.setVersion("1.0.0");
        bp.setParamsSchema(List.of());
        bp.setInputPorts(List.of(Map.of("name", "data_input")));
        bp.setOutputPorts(List.of(Map.of("name", "default_output")));
        bp.setRuntimeRequirements(Map.of());
        bp.setPipelineConfig(false);
        bp.setValidLayers(List.of("silver"));
        bp.setComputeBackend("spark");
        bp.setEmitStrategy("generate");
        return bp;
    }

    private Blueprint genericFilterBlueprint(String id) {
        Blueprint bp = new Blueprint();
        bp.setId(id);
        bp.setBlueprintKey("GenericFilter");
        bp.setName("Generic Filter");
        bp.setCategory(BlueprintCategory.TRANSFORM);
        bp.setStatus("active");
        bp.setDeferred(false);
        bp.setDescription("Filter rows by predicate");
        bp.setVersion("1.0.0");
        bp.setParamsSchema(List.of());
        bp.setInputPorts(List.of(Map.of("name", "data_input")));
        bp.setOutputPorts(List.of(Map.of("name", "filtered_output")));
        bp.setRuntimeRequirements(Map.of());
        bp.setPipelineConfig(false);
        bp.setValidLayers(List.of("silver"));
        bp.setComputeBackend("spark");
        bp.setEmitStrategy("generate");
        return bp;
    }

    private Blueprint genericJoinBlueprint(String id) {
        Blueprint bp = new Blueprint();
        bp.setId(id);
        bp.setBlueprintKey("GenericJoin");
        bp.setName("Generic Join");
        bp.setCategory(BlueprintCategory.TRANSFORM);
        bp.setStatus("active");
        bp.setDeferred(false);
        bp.setDescription("Join two inputs by configured keys");
        bp.setVersion("1.0.0");
        bp.setParamsSchema(List.of());
        bp.setInputPorts(List.of(Map.of("name", "left_input"), Map.of("name", "right_input")));
        bp.setOutputPorts(List.of(Map.of("name", "joined_output")));
        bp.setRuntimeRequirements(Map.of());
        bp.setPipelineConfig(false);
        bp.setValidLayers(List.of("silver"));
        bp.setComputeBackend("spark");
        bp.setEmitStrategy("generate");
        return bp;
    }

    private Blueprint piiMaskingBlueprint(String id) {
        Blueprint bp = new Blueprint();
        bp.setId(id);
        bp.setBlueprintKey("PIIMasking");
        bp.setName("PII Masking");
        bp.setCategory(BlueprintCategory.TRANSFORM);
        bp.setStatus("active");
        bp.setDeferred(false);
        bp.setDescription("Mask personal identifiers");
        bp.setVersion("1.0.0");
        bp.setParamsSchema(List.of());
        bp.setInputPorts(List.of(Map.of("name", "data_input")));
        bp.setOutputPorts(List.of(Map.of("name", "masked_output")));
        bp.setRuntimeRequirements(Map.of());
        bp.setPipelineConfig(false);
        bp.setValidLayers(List.of("silver"));
        bp.setComputeBackend("spark");
        bp.setEmitStrategy("generate");
        return bp;
    }


    private Blueprint bronzeToSilverCleaningBlueprint(String id) {
        Blueprint bp = new Blueprint();
        bp.setId(id);
        bp.setBlueprintKey("BronzeToSilverCleaning");
        bp.setName("Bronze To Silver Cleaning");
        bp.setCategory(BlueprintCategory.TRANSFORM);
        bp.setStatus("active");
        bp.setDeferred(false);
        bp.setDescription("Clean bronze data into silver");
        bp.setVersion("1.0.0");
        bp.setParamsSchema(List.of());
        bp.setInputPorts(List.of(Map.of("name", "raw_input")));
        bp.setOutputPorts(List.of(Map.of("name", "cleaned_output")));
        bp.setRuntimeRequirements(Map.of());
        bp.setPipelineConfig(false);
        bp.setValidLayers(List.of("silver"));
        bp.setComputeBackend("spark");
        bp.setEmitStrategy("generate");
        return bp;
    }

    private Blueprint dedupeAndMergeBlueprint(String id) {
        Blueprint bp = new Blueprint();
        bp.setId(id);
        bp.setBlueprintKey("DedupeAndMerge");
        bp.setName("Dedupe And Merge");
        bp.setCategory(BlueprintCategory.TRANSFORM);
        bp.setStatus("active");
        bp.setDeferred(false);
        bp.setDescription("Deduplicate records by business key");
        bp.setVersion("1.0.0");
        bp.setParamsSchema(List.of());
        bp.setInputPorts(List.of(Map.of("name", "input_data")));
        bp.setOutputPorts(List.of(Map.of("name", "deduped_output")));
        bp.setRuntimeRequirements(Map.of());
        bp.setPipelineConfig(false);
        bp.setValidLayers(List.of("silver"));
        bp.setComputeBackend("spark");
        bp.setEmitStrategy("generate");
        return bp;
    }

    private Blueprint schemaNormalizationBlueprint(String id) {
        Blueprint bp = new Blueprint();
        bp.setId(id);
        bp.setBlueprintKey("SchemaNormalization");
        bp.setName("Schema Normalization");
        bp.setCategory(BlueprintCategory.TRANSFORM);
        bp.setStatus("active");
        bp.setDeferred(false);
        bp.setDescription("Normalize source data into a canonical schema");
        bp.setVersion("1.0.0");
        bp.setParamsSchema(List.of());
        bp.setInputPorts(List.of(Map.of("name", "source_data")));
        bp.setOutputPorts(List.of(Map.of("name", "normalized_output")));
        bp.setRuntimeRequirements(Map.of());
        bp.setPipelineConfig(false);
        bp.setValidLayers(List.of("silver"));
        bp.setComputeBackend("spark");
        bp.setEmitStrategy("generate");
        return bp;
    }

    private Blueprint featureTablePublishBlueprint(String id) {
        Blueprint bp = new Blueprint();
        bp.setId(id);
        bp.setBlueprintKey("FeatureTablePublish");
        bp.setName("Feature Table Publish");
        bp.setCategory(BlueprintCategory.MODELING);
        bp.setStatus("active");
        bp.setDeferred(false);
        bp.setDescription("Publish a point-in-time feature table");
        bp.setVersion("1.0.0");
        bp.setParamsSchema(List.of());
        bp.setInputPorts(List.of(Map.of("name", "source_data")));
        bp.setOutputPorts(List.of(Map.of("name", "feature_output")));
        bp.setRuntimeRequirements(Map.of());
        bp.setPipelineConfig(false);
        bp.setValidLayers(List.of("gold"));
        bp.setComputeBackend("spark");
        bp.setEmitStrategy("generate");
        return bp;
    }

    private Blueprint referenceDataPublishBlueprint(String id) {
        Blueprint bp = new Blueprint();
        bp.setId(id);
        bp.setBlueprintKey("ReferenceDataPublish");
        bp.setName("Reference Data Publish");
        bp.setCategory(BlueprintCategory.MODELING);
        bp.setStatus("active");
        bp.setDeferred(false);
        bp.setDescription("Publish distinct reference data values");
        bp.setVersion("1.0.0");
        bp.setParamsSchema(List.of());
        bp.setInputPorts(List.of(Map.of("name", "reference_source")));
        bp.setOutputPorts(List.of(Map.of("name", "published_reference")));
        bp.setRuntimeRequirements(Map.of());
        bp.setPipelineConfig(false);
        bp.setValidLayers(List.of("gold"));
        bp.setComputeBackend("spark");
        bp.setEmitStrategy("generate");
        return bp;
    }

    private Blueprint factBuildBlueprint(String id) {
        Blueprint bp = new Blueprint();
        bp.setId(id);
        bp.setBlueprintKey("FactBuild");
        bp.setName("Fact Build");
        bp.setCategory(BlueprintCategory.MODELING);
        bp.setStatus("active");
        bp.setDeferred(false);
        bp.setDescription("Build a fact table at the configured business grain");
        bp.setVersion("1.0.0");
        bp.setParamsSchema(List.of());
        bp.setInputPorts(List.of(Map.of("name", "transaction_data"), Map.of("name", "dimension_refs")));
        bp.setOutputPorts(List.of(Map.of("name", "fact_output")));
        bp.setRuntimeRequirements(Map.of());
        bp.setPipelineConfig(false);
        bp.setValidLayers(List.of("gold"));
        bp.setComputeBackend("spark");
        bp.setSupportsReuse(true);
        bp.setEmitStrategy("generate");
        return bp;
    }

    private Blueprint scd2DimensionBlueprint(String id) {
        Blueprint bp = new Blueprint();
        bp.setId(id);
        bp.setBlueprintKey("SCD2Dimension");
        bp.setName("SCD2 Dimension");
        bp.setCategory(BlueprintCategory.MODELING);
        bp.setStatus("active");
        bp.setDeferred(false);
        bp.setDescription("Track a slowly changing dimension with full row history");
        bp.setVersion("1.0.0");
        bp.setParamsSchema(List.of());
        bp.setInputPorts(List.of(Map.of("name", "data_input")));
        bp.setOutputPorts(List.of(Map.of("name", "scd2_output")));
        bp.setRuntimeRequirements(Map.of());
        bp.setPipelineConfig(false);
        bp.setValidLayers(List.of("gold"));
        bp.setComputeBackend("spark");
        bp.setSupportsReuse(true);
        bp.setEmitStrategy("generate");
        return bp;
    }

    private Blueprint wideDenormalizedMartBlueprint(String id) {
        Blueprint bp = new Blueprint();
        bp.setId(id);
        bp.setBlueprintKey("WideDenormalizedMart");
        bp.setName("Wide Denormalized Mart");
        bp.setCategory(BlueprintCategory.MODELING);
        bp.setStatus("active");
        bp.setDeferred(false);
        bp.setDescription("Build a wide denormalized mart from fact and dimension inputs");
        bp.setVersion("1.0.0");
        bp.setParamsSchema(List.of());
        bp.setInputPorts(List.of(Map.of("name", "fact_data"), Map.of("name", "dimension_data")));
        bp.setOutputPorts(List.of(Map.of("name", "mart_output")));
        bp.setRuntimeRequirements(Map.of());
        bp.setPipelineConfig(false);
        bp.setValidLayers(List.of("gold"));
        bp.setComputeBackend("spark");
        bp.setSupportsReuse(true);
        bp.setEmitStrategy("generate");
        return bp;
    }

    private Blueprint incrementalMergeBlueprint(String id) {
        Blueprint bp = new Blueprint();
        bp.setId(id);
        bp.setBlueprintKey("IncrementalMerge");
        bp.setName("Incremental Merge");
        bp.setCategory(BlueprintCategory.MODELING);
        bp.setStatus("active");
        bp.setDeferred(false);
        bp.setDescription("Merge incremental changes into a durable target keyed by business identifier");
        bp.setVersion("1.0.0");
        bp.setParamsSchema(List.of());
        bp.setInputPorts(List.of(Map.of("name", "incremental_data")));
        bp.setOutputPorts(List.of(Map.of("name", "merged_output")));
        bp.setRuntimeRequirements(Map.of());
        bp.setPipelineConfig(false);
        bp.setValidLayers(List.of("silver", "gold"));
        bp.setComputeBackend("spark");
        bp.setEmitStrategy("generate");
        return bp;
    }

    private Blueprint dqValidatorBlueprint(String id) {
        Blueprint bp = new Blueprint();
        bp.setId(id);
        bp.setBlueprintKey("DQValidator");
        bp.setName("DQ Validator");
        bp.setCategory(BlueprintCategory.DATA_QUALITY);
        bp.setStatus("active");
        bp.setDeferred(false);
        bp.setDescription("Validate rows with Great Expectations and publish passing data");
        bp.setVersion("1.0.0");
        bp.setParamsSchema(List.of());
        bp.setInputPorts(List.of(Map.of("name", "data_to_validate")));
        bp.setOutputPorts(List.of(Map.of("name", "validated_output"), Map.of("name", "quarantine_output")));
        bp.setRuntimeRequirements(Map.of());
        bp.setPipelineConfig(false);
        bp.setValidLayers(List.of("silver"));
        bp.setComputeBackend("spark");
        bp.setEmitStrategy("generate");
        return bp;
    }

    private Blueprint anomalyDetectionBlueprint(String id) {
        Blueprint bp = new Blueprint();
        bp.setId(id);
        bp.setBlueprintKey("AnomalyDetection");
        bp.setName("Anomaly Detection");
        bp.setCategory(BlueprintCategory.DATA_QUALITY);
        bp.setStatus("active");
        bp.setDeferred(false);
        bp.setDescription("Publish deterministic anomaly statistics");
        bp.setVersion("1.0.0");
        bp.setParamsSchema(List.of());
        bp.setInputPorts(List.of(Map.of("name", "data_to_monitor")));
        bp.setOutputPorts(List.of(Map.of("name", "anomaly_report")));
        bp.setRuntimeRequirements(Map.of());
        bp.setPipelineConfig(false);
        bp.setValidLayers(List.of("silver"));
        bp.setComputeBackend("spark");
        bp.setEmitStrategy("generate");
        return bp;
    }

    private Blueprint freshnessChecksBlueprint(String id) {
        Blueprint bp = new Blueprint();
        bp.setId(id);
        bp.setBlueprintKey("FreshnessChecks");
        bp.setName("Freshness Checks");
        bp.setCategory(BlueprintCategory.DATA_QUALITY);
        bp.setStatus("active");
        bp.setDeferred(false);
        bp.setDescription("Publish a deterministic data freshness report");
        bp.setVersion("1.0.0");
        bp.setParamsSchema(List.of());
        bp.setInputPorts(List.of(Map.of("name", "monitored_dataset")));
        bp.setOutputPorts(List.of(Map.of("name", "freshness_result")));
        bp.setRuntimeRequirements(Map.of());
        bp.setPipelineConfig(false);
        bp.setValidLayers(List.of("silver"));
        bp.setComputeBackend("spark");
        bp.setEmitStrategy("generate");
        return bp;
    }

    private Blueprint snapshotModelBlueprint(String id) {
        Blueprint bp = new Blueprint();
        bp.setId(id);
        bp.setBlueprintKey("SnapshotModel");
        bp.setName("Snapshot Model");
        bp.setCategory(BlueprintCategory.MODELING);
        bp.setStatus("active");
        bp.setDeferred(false);
        bp.setDescription("Materialize point-in-time snapshots for source data");
        bp.setVersion("1.0.0");
        bp.setParamsSchema(List.of());
        bp.setInputPorts(List.of(Map.of("name", "source_data")));
        bp.setOutputPorts(List.of(Map.of("name", "snapshot_output")));
        bp.setRuntimeRequirements(Map.of());
        bp.setPipelineConfig(false);
        bp.setValidLayers(List.of("gold"));
        bp.setComputeBackend("spark");
        bp.setSupportsReuse(true);
        bp.setEmitStrategy("generate");
        return bp;
    }

    private Blueprint schemaDriftDetectionBlueprint(String id) {
        Blueprint bp = new Blueprint();
        bp.setId(id);
        bp.setBlueprintKey("SchemaDriftDetection");
        bp.setName("Schema Drift Detection");
        bp.setCategory(BlueprintCategory.DATA_QUALITY);
        bp.setStatus("active");
        bp.setDeferred(false);
        bp.setDescription("Publish a deterministic schema drift report");
        bp.setVersion("1.0.0");
        bp.setParamsSchema(List.of());
        bp.setInputPorts(List.of(Map.of("name", "incoming_data")));
        bp.setOutputPorts(List.of(Map.of("name", "drift_report")));
        bp.setRuntimeRequirements(Map.of());
        bp.setPipelineConfig(false);
        bp.setValidLayers(List.of("silver"));
        bp.setComputeBackend("spark");
        bp.setEmitStrategy("generate");
        return bp;
    }

    private Blueprint lakeWriterBlueprint(String id) {
        Blueprint bp = new Blueprint();
        bp.setId(id);
        bp.setBlueprintKey("LakeWriter");
        bp.setName("Lake Writer");
        bp.setCategory(BlueprintCategory.DESTINATION);
        bp.setStatus("active");
        bp.setDeferred(false);
        bp.setDescription("Write rows into a Delta lake target");
        bp.setVersion("1.0.0");
        bp.setParamsSchema(List.of());
        bp.setInputPorts(List.of(Map.of("name", "data_input")));
        bp.setOutputPorts(List.of());
        bp.setRuntimeRequirements(Map.of());
        bp.setPipelineConfig(false);
        bp.setValidLayers(List.of("silver", "gold"));
        bp.setComputeBackend("spark");
        bp.setEmitStrategy("generate");
        return bp;
    }

    private Blueprint databaseWriterBlueprint(String id) {
        Blueprint bp = new Blueprint();
        bp.setId(id);
        bp.setBlueprintKey("DatabaseWriter");
        bp.setName("Database Writer");
        bp.setCategory(BlueprintCategory.DESTINATION);
        bp.setStatus("active");
        bp.setDeferred(false);
        bp.setDescription("Write rows into a JDBC destination");
        bp.setVersion("1.0.0");
        bp.setParamsSchema(List.of());
        bp.setInputPorts(List.of(Map.of("name", "data_input")));
        bp.setOutputPorts(List.of());
        bp.setRuntimeRequirements(Map.of());
        bp.setPipelineConfig(false);
        bp.setValidLayers(List.of("gold"));
        bp.setComputeBackend("spark");
        bp.setEmitStrategy("generate");
        return bp;
    }

    private void seedLocalDpcStorageBackend(String tenantId) {
        StorageBackend storageBackend = new StorageBackend();
        storageBackend.setId("stg-proof-" + shortId(tenantId));
        storageBackend.setTenantId(tenantId);
        storageBackend.setEnvironment("dev");
        storageBackend.setBackend(STORAGE_BACKEND);
        storageBackend.setStorageRootFiles(STORAGE_ROOT_FILES);
        storageBackend.setStorageRootLake(STORAGE_ROOT_LAKE);
        storageBackend.setDpcScheme("s3a");
        storageBackend.setDpcCluster("pulse-local-spark");
        storageBackend.setProvisioningStatus("validated");
        storageBackendRepository.save(storageBackend);
    }

    private void stageLoanMasterCsvForPipeline(String pathPipelineSlug, Path loanMasterCsv) throws Exception {
        assertTrue(Files.isRegularFile(loanMasterCsv), "Expected canonical loan_master.csv at " + loanMasterCsv);

        runCommand(List.of("docker", "cp", loanMasterCsv.toString(), "pulse-minio-1:/tmp/loan_master.csv"));

        String objectPath = STORAGE_ROOT_FILES + "/servicing/" + SOR_SLUG + "/" + pathPipelineSlug + "/SRC/loan_master.csv";
        runCommand(List.of(
                "docker", "exec", "pulse-minio-1", "sh", "-lc",
                "mc alias set local http://localhost:9000 minioadmin minioadmin >/dev/null"
                        + " && mc mb --ignore-existing local/" + STORAGE_ROOT_FILES + " >/dev/null"
                        + " && mc cp /tmp/loan_master.csv local/" + objectPath
        ));
    }

    private void seedCurrentLoansPostgresSource(String tableName, Path sourceCsv) throws Exception {
        MinioOutputOracleValidator.CsvTable table = MinioOutputOracleValidator.CsvTable.load(List.of(sourceCsv));
        List<Map<String, String>> currentRows = table.rows().stream()
                .filter(row -> "Current".equals(row.get("loan_status")))
                .toList();
        assertFalse(currentRows.isEmpty(), "Expected current-loan rows in " + sourceCsv);

        try (var connection = DriverManager.getConnection("jdbc:postgresql://localhost:5432/pulse", "pulse", "pulse");
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS public.\"" + tableName.replace("\"", "\"\"") + "\"");
            String columns = table.headers().stream()
                    .map(column -> "\"" + column.replace("\"", "\"\"") + "\" "
                            + ("months_delinquent".equals(column) ? "integer" : "text"))
                    .collect(Collectors.joining(", "));
            statement.execute("CREATE TABLE public.\"" + tableName.replace("\"", "\"\"") + "\" (" + columns + ")");

            String placeholders = table.headers().stream().map(column -> "?").collect(Collectors.joining(", "));
            String columnNames = table.headers().stream()
                    .map(column -> "\"" + column.replace("\"", "\"\"") + "\"")
                    .collect(Collectors.joining(", "));
            String insertSql = "INSERT INTO public.\"" + tableName.replace("\"", "\"\"") + "\" ("
                    + columnNames + ") VALUES (" + placeholders + ")";
            try (PreparedStatement insert = connection.prepareStatement(insertSql)) {
                for (Map<String, String> row : currentRows) {
                    for (int i = 0; i < table.headers().size(); i++) {
                        String header = table.headers().get(i);
                        if ("months_delinquent".equals(header)) {
                            insert.setInt(i + 1, Integer.parseInt(row.get(header)));
                        } else {
                            insert.setString(i + 1, row.get(header));
                        }
                    }
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        }
    }

    private void createCurrentLoansExpectedCsv(Path sourceCsv, Path targetCsv) throws Exception {
        MinioOutputOracleValidator.CsvTable table = MinioOutputOracleValidator.CsvTable.load(List.of(sourceCsv));
        List<Map<String, String>> currentRows = table.rows().stream()
                .filter(row -> "Current".equals(row.get("loan_status")))
                .toList();
        Files.createDirectories(targetCsv.getParent());
        MinioOutputOracleValidator.CsvTable expected = new MinioOutputOracleValidator.CsvTable(table.headers(), currentRows);
        Files.writeString(targetCsv, expected.toCanonicalCsv(), StandardCharsets.UTF_8);
    }

    private void createFeatureTableExpectedCsv(Path sourceCsv, Path targetCsv, List<String> featureColumns) throws Exception {
        createProjectedExpectedCsv(sourceCsv, targetCsv, featureColumns);
    }

    private void createProjectedExpectedCsv(Path sourceCsv, Path targetCsv, List<String> projectionColumns) throws Exception {
        MinioOutputOracleValidator.CsvTable table = MinioOutputOracleValidator.CsvTable.load(List.of(sourceCsv));
        for (String column : projectionColumns) {
            assertTrue(table.headers().contains(column), () -> "source column missing: " + column);
        }
        List<Map<String, String>> projectedRows = table.rows().stream()
                .sorted(Comparator.comparing(row -> row.getOrDefault("loan_id", "")))
                .map(row -> {
                    Map<String, String> projected = new LinkedHashMap<>();
                    for (String column : projectionColumns) {
                        projected.put(column, row.get(column));
                    }
                    return projected;
                })
                .toList();
        Files.createDirectories(targetCsv.getParent());
        MinioOutputOracleValidator.CsvTable expected = new MinioOutputOracleValidator.CsvTable(projectionColumns, projectedRows);
        Files.writeString(targetCsv, expected.toCanonicalCsv(), StandardCharsets.UTF_8);
    }

    private void createDistinctColumnExpectedCsv(Path sourceCsv, Path targetCsv, String columnName) throws Exception {
        MinioOutputOracleValidator.CsvTable table = MinioOutputOracleValidator.CsvTable.load(List.of(sourceCsv));
        assertTrue(table.headers().contains(columnName), () -> "reference source column missing: " + columnName);
        List<Map<String, String>> referenceRows = table.rows().stream()
                .map(row -> row.get(columnName))
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .sorted()
                .map(value -> Map.of(columnName, value))
                .toList();
        Files.createDirectories(targetCsv.getParent());
        MinioOutputOracleValidator.CsvTable expected = new MinioOutputOracleValidator.CsvTable(
                List.of(columnName),
                referenceRows
        );
        Files.writeString(targetCsv, expected.toCanonicalCsv(), StandardCharsets.UTF_8);
    }

    private void createFreshnessExpectedReportCsv(Path sourceCsv,
                                                  Path targetCsv,
                                                  String timestampColumn,
                                                  String businessDate,
                                                  long maxAgeMinutes) throws Exception {
        MinioOutputOracleValidator.CsvTable table = MinioOutputOracleValidator.CsvTable.load(List.of(sourceCsv));
        assertTrue(table.headers().contains(timestampColumn), () -> "freshness timestamp column missing: " + timestampColumn);
        LocalDate observed = table.rows().stream()
                .map(row -> row.get(timestampColumn))
                .filter(value -> value != null && !value.isBlank())
                .map(LocalDate::parse)
                .max(LocalDate::compareTo)
                .orElseThrow(() -> new IllegalStateException("No observed freshness values for " + timestampColumn));
        long actualAgeMinutes = ChronoUnit.MINUTES.between(observed.atStartOfDay(), LocalDate.parse(businessDate).atStartOfDay());
        List<String> headers = List.of(
                "check_name",
                "timestamp_column",
                "business_date",
                "max_observed_date",
                "max_age_minutes",
                "actual_age_minutes",
                "row_count",
                "status"
        );
        Map<String, String> row = new LinkedHashMap<>();
        row.put("check_name", "freshness");
        row.put("timestamp_column", timestampColumn);
        row.put("business_date", businessDate);
        row.put("max_observed_date", observed.toString());
        row.put("max_age_minutes", String.valueOf(maxAgeMinutes));
        row.put("actual_age_minutes", String.valueOf(actualAgeMinutes));
        row.put("row_count", String.valueOf(table.rows().size()));
        row.put("status", actualAgeMinutes <= maxAgeMinutes ? "PASS" : "FAIL");
        Files.createDirectories(targetCsv.getParent());
        Files.writeString(targetCsv, new MinioOutputOracleValidator.CsvTable(headers, List.of(row)).toCanonicalCsv(), StandardCharsets.UTF_8);
    }

    private void createSchemaDriftExpectedReportCsv(Path sourceCsv,
                                                    Path targetCsv,
                                                    List<String> expectedColumns,
                                                    boolean allowExtraColumns) throws Exception {
        MinioOutputOracleValidator.CsvTable table = MinioOutputOracleValidator.CsvTable.load(List.of(sourceCsv));
        List<String> actualColumns = table.headers().stream()
                .filter(column -> !column.startsWith("_pulse_"))
                .toList();
        List<String> missingColumns = expectedColumns.stream()
                .filter(column -> !actualColumns.contains(column))
                .sorted()
                .toList();
        List<String> addedColumns = actualColumns.stream()
                .filter(column -> !expectedColumns.contains(column))
                .sorted()
                .toList();
        List<String> headers = List.of(
                "check_name",
                "expected_columns",
                "actual_columns",
                "missing_columns",
                "added_columns",
                "allow_extra_columns",
                "expected_column_count",
                "actual_column_count",
                "row_count",
                "status"
        );
        Map<String, String> row = new LinkedHashMap<>();
        row.put("check_name", "schema_drift");
        row.put("expected_columns", String.join("|", expectedColumns));
        row.put("actual_columns", String.join("|", actualColumns));
        row.put("missing_columns", String.join("|", missingColumns));
        row.put("added_columns", String.join("|", addedColumns));
        row.put("allow_extra_columns", String.valueOf(allowExtraColumns));
        row.put("expected_column_count", String.valueOf(expectedColumns.size()));
        row.put("actual_column_count", String.valueOf(actualColumns.size()));
        row.put("row_count", String.valueOf(table.rows().size()));
        row.put("status", missingColumns.isEmpty() && (allowExtraColumns || addedColumns.isEmpty()) ? "PASS" : "FAIL");
        Files.createDirectories(targetCsv.getParent());
        Files.writeString(targetCsv, new MinioOutputOracleValidator.CsvTable(headers, List.of(row)).toCanonicalCsv(), StandardCharsets.UTF_8);
    }

    private void createAnomalyExpectedReportCsv(Path sourceCsv,
                                                Path targetCsv,
                                                List<String> monitoredColumns,
                                                double zThreshold) throws Exception {
        MinioOutputOracleValidator.CsvTable table = MinioOutputOracleValidator.CsvTable.load(List.of(sourceCsv));
        List<String> headers = List.of(
                "check_name",
                "monitored_column",
                "row_count",
                "mean_value",
                "stddev_value",
                "z_threshold",
                "anomaly_count",
                "status"
        );
        List<Map<String, String>> rows = new ArrayList<>();
        for (String column : monitoredColumns) {
            assertTrue(table.headers().contains(column), () -> "anomaly monitored column missing: " + column);
            List<Double> values = table.rows().stream()
                    .map(row -> doubleValue(row.get(column)))
                    .filter(value -> value != null)
                    .toList();
            double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0d);
            double variance = values.stream()
                    .mapToDouble(value -> Math.pow(value - mean, 2))
                    .average()
                    .orElse(0.0d);
            double stddev = Math.sqrt(variance);
            long anomalyCount = stddev == 0.0d
                    ? 0L
                    : values.stream()
                    .filter(value -> Math.abs((value - mean) / stddev) > zThreshold)
                    .count();
            Map<String, String> row = new LinkedHashMap<>();
            row.put("check_name", "anomaly_detection");
            row.put("monitored_column", column);
            row.put("row_count", String.valueOf(table.rows().size()));
            row.put("mean_value", String.format(Locale.ROOT, "%.4f", mean));
            row.put("stddev_value", String.format(Locale.ROOT, "%.4f", stddev));
            row.put("z_threshold", String.format(Locale.ROOT, "%.1f", zThreshold));
            row.put("anomaly_count", String.valueOf(anomalyCount));
            row.put("status", "PASS");
            rows.add(row);
        }
        Files.createDirectories(targetCsv.getParent());
        Files.writeString(targetCsv, new MinioOutputOracleValidator.CsvTable(headers, rows).toCanonicalCsv(), StandardCharsets.UTF_8);
    }

    private Double doubleValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void seedLoanMasterPostgresSource(String tableName, Path sourceCsv) throws Exception {
        MinioOutputOracleValidator.CsvTable table = MinioOutputOracleValidator.CsvTable.load(List.of(sourceCsv));
        assertFalse(table.rows().isEmpty(), "Expected source rows in " + sourceCsv);

        try (var connection = DriverManager.getConnection("jdbc:postgresql://localhost:5432/pulse", "pulse", "pulse");
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS public.\"" + tableName.replace("\"", "\"\"") + "\"");
            String columns = table.headers().stream()
                    .map(column -> "\"" + column.replace("\"", "\"\"") + "\" "
                            + ("months_delinquent".equals(column) ? "integer" : "text"))
                    .collect(Collectors.joining(", "));
            statement.execute("CREATE TABLE public.\"" + tableName.replace("\"", "\"\"") + "\" (" + columns + ")");

            String placeholders = table.headers().stream().map(column -> "?").collect(Collectors.joining(", "));
            String columnNames = table.headers().stream()
                    .map(column -> "\"" + column.replace("\"", "\"\"") + "\"")
                    .collect(Collectors.joining(", "));
            String insertSql = "INSERT INTO public.\"" + tableName.replace("\"", "\"\"") + "\" ("
                    + columnNames + ") VALUES (" + placeholders + ")";
            try (PreparedStatement insert = connection.prepareStatement(insertSql)) {
                for (Map<String, String> row : table.rows()) {
                    for (int i = 0; i < table.headers().size(); i++) {
                        String header = table.headers().get(i);
                        if ("months_delinquent".equals(header)) {
                            insert.setInt(i + 1, Integer.parseInt(row.get(header)));
                        } else {
                            insert.setString(i + 1, row.get(header));
                        }
                    }
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        }
    }

    private void writeActualAggregateProjectionScript(Path scriptPath,
                                                      String codegenSlug,
                                                      String deltaOutputUri,
                                                      String containerCsvOutputPath) throws Exception {
        Files.createDirectories(scriptPath.getParent());
        String script = """
                from pyspark.sql import SparkSession

                spark = SparkSession.builder \\
                    .appName('%s_actual_aggregate_probe') \\
                    .config('spark.sql.extensions', 'io.delta.sql.DeltaSparkSessionExtension') \\
                    .config('spark.sql.catalog.spark_catalog', 'org.apache.spark.sql.delta.catalog.DeltaCatalog') \\
                    .getOrCreate()

                df = spark.read.format('delta').load('%s')
                df.select('property_state', 'loan_status', 'total_current_upb') \\
                    .orderBy('property_state', 'loan_status') \\
                    .coalesce(1) \\
                    .write \\
                    .mode('overwrite') \\
                    .option('header', 'true') \\
                    .csv('%s')
                spark.stop()
                """.formatted(pyString(codegenSlug), pyString(deltaOutputUri), pyString(containerCsvOutputPath));
        Files.writeString(scriptPath, script);
    }

    private void writeExpectedAggregateProjectionScript(Path scriptPath,
                                                        String codegenSlug,
                                                        String containerSourceCsvPath,
                                                        String containerCsvOutputPath) throws Exception {
        Files.createDirectories(scriptPath.getParent());
        String script = """
                from pyspark.sql import SparkSession
                from pyspark.sql import functions as F

                spark = SparkSession.builder \\
                    .appName('%s_expected_aggregate_probe') \\
                    .getOrCreate()

                df = spark.read.option('header', 'true').option('inferSchema', 'true').csv('%s')
                df.groupBy('property_state', 'loan_status') \\
                    .agg(F.sum('current_upb').alias('total_current_upb')) \\
                    .orderBy('property_state', 'loan_status') \\
                    .coalesce(1) \\
                    .write \\
                    .mode('overwrite') \\
                    .option('header', 'true') \\
                    .csv('%s')
                spark.stop()
                """.formatted(pyString(codegenSlug), pyString(containerSourceCsvPath), pyString(containerCsvOutputPath));
        Files.writeString(scriptPath, script);
    }

    private void writeActualRowProjectionScript(Path scriptPath,
                                                String codegenSlug,
                                                String deltaOutputUri,
                                                String containerCsvOutputPath,
                                                List<String> projectionColumns) throws Exception {
        Files.createDirectories(scriptPath.getParent());
        String script = """
                from pyspark.sql import SparkSession

                spark = SparkSession.builder \\
                    .appName('%s_actual_row_probe') \\
                    .config('spark.sql.extensions', 'io.delta.sql.DeltaSparkSessionExtension') \\
                    .config('spark.sql.catalog.spark_catalog', 'org.apache.spark.sql.delta.catalog.DeltaCatalog') \\
                    .getOrCreate()

                columns = %s
                df = spark.read.format('delta').load('%s')
                df.select(*columns) \\
                    .orderBy('loan_id', 'loan_number') \\
                    .coalesce(1) \\
                    .write \\
                    .mode('overwrite') \\
                    .option('header', 'true') \\
                    .option('escape', '"') \\
                    .csv('%s')
                spark.stop()
                """.formatted(
                pyString(codegenSlug),
                pythonListLiteral(projectionColumns),
                pyString(deltaOutputUri),
                pyString(containerCsvOutputPath)
        );
        Files.writeString(scriptPath, script);
    }

    private void writeExpectedDedupeProjectionScript(Path scriptPath,
                                                     String codegenSlug,
                                                     String containerSourceCsvPath,
                                                     String containerCsvOutputPath,
                                                     List<String> projectionColumns) throws Exception {
        Files.createDirectories(scriptPath.getParent());
        String script = """
                from pyspark.sql import SparkSession
                from pyspark.sql import functions as F
                from pyspark.sql.window import Window
                from pyspark.sql.types import StructType, StructField, StringType, LongType, DoubleType, BooleanType, DateType

                spark = SparkSession.builder \\
                    .appName('%s_expected_dedupe_probe') \\
                    .getOrCreate()

                def _pulse_quote_column(column_name):
                    return "`" + column_name.replace("`", "``") + "`"

                def _pulse_infer_csv_schema(spark, source_path):
                    raw_df = spark.read \\
                        .format('csv') \\
                        .option('header', 'true') \\
                        .option('inferSchema', 'false') \\
                        .load(source_path)
                    aggregations = []
                    aliases_by_column = []
                    for idx, column_name in enumerate(raw_df.columns):
                        value = F.trim(F.col(_pulse_quote_column(column_name)))
                        present = value.isNotNull() & (value != '')
                        aliases = {
                            'present': f'c{idx}_present',
                            'leading_zero_integer': f'c{idx}_leading_zero_integer',
                            'boolean': f'c{idx}_boolean',
                            'integer': f'c{idx}_integer',
                            'decimal': f'c{idx}_decimal',
                            'date': f'c{idx}_date',
                        }
                        aggregations.extend([
                            F.sum(F.when(present, 1).otherwise(0)).alias(aliases['present']),
                            F.sum(F.when(present & value.rlike(r'^0[0-9]+$'), 1).otherwise(0)).alias(aliases['leading_zero_integer']),
                            F.sum(F.when(present & F.lower(value).isin('true', 'false'), 1).otherwise(0)).alias(aliases['boolean']),
                            F.sum(F.when(present & value.rlike(r'^-?[0-9]+$'), 1).otherwise(0)).alias(aliases['integer']),
                            F.sum(F.when(present & value.rlike(r'^-?([0-9]+\\.[0-9]+|[0-9]+)$'), 1).otherwise(0)).alias(aliases['decimal']),
                            F.sum(F.when(present & value.rlike(r'^[0-9]{4}-[0-9]{2}-[0-9]{2}$'), 1).otherwise(0)).alias(aliases['date']),
                        ])
                        aliases_by_column.append((column_name, aliases))

                    metrics = raw_df.agg(*aggregations).collect()[0].asDict() if aggregations else {}
                    fields = []
                    for column_name, aliases in aliases_by_column:
                        present_count = int(metrics.get(aliases['present']) or 0)
                        leading_zero_integer_count = int(metrics.get(aliases['leading_zero_integer']) or 0)
                        boolean_count = int(metrics.get(aliases['boolean']) or 0)
                        integer_count = int(metrics.get(aliases['integer']) or 0)
                        decimal_count = int(metrics.get(aliases['decimal']) or 0)
                        date_count = int(metrics.get(aliases['date']) or 0)

                        if present_count == 0 or leading_zero_integer_count > 0:
                            data_type = StringType()
                        elif boolean_count == present_count:
                            data_type = BooleanType()
                        elif integer_count == present_count:
                            data_type = LongType()
                        elif decimal_count == present_count:
                            data_type = DoubleType()
                        elif date_count == present_count:
                            data_type = DateType()
                        else:
                            data_type = StringType()
                        fields.append(StructField(column_name, data_type, True))
                    return StructType(fields)

                columns = %s
                source_path = '%s'
                schema = _pulse_infer_csv_schema(spark, source_path)
                df = spark.read.option('header', 'true').schema(schema).csv(source_path)
                window = Window.partitionBy('loan_id').orderBy(F.col('last_payment_date').desc(), F.col('loan_number').asc())
                df.withColumn('_dedup_rank', F.row_number().over(window)) \\
                    .filter(F.col('_dedup_rank') == 1) \\
                    .select(*columns) \\
                    .orderBy('loan_id', 'loan_number') \\
                    .coalesce(1) \\
                    .write \\
                    .mode('overwrite') \\
                    .option('header', 'true') \\
                    .csv('%s')
                spark.stop()
                """.formatted(
                pyString(codegenSlug),
                pythonListLiteral(projectionColumns),
                pyString(containerSourceCsvPath),
                pyString(containerCsvOutputPath)
        );
        Files.writeString(scriptPath, script);
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

                spark = SparkSession.builder \
                    .appName('%s_output_oracle_probe') \
                    .config('spark.sql.extensions', 'io.delta.sql.DeltaSparkSessionExtension') \
                    .config('spark.sql.catalog.spark_catalog', 'org.apache.spark.sql.delta.catalog.DeltaCatalog') \
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
                df.select(*columns) \
                    .orderBy('loan_id') \
                    .coalesce(1) \
                    .write \
                    .mode('overwrite') \
                    .option('header', 'true') \
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

    private void writeDeltaDistinctColumnProjectionScript(Path scriptPath,
                                                          String codegenSlug,
                                                          String deltaOutputUri,
                                                          String containerCsvOutputPath,
                                                          String columnName) throws Exception {
        Files.createDirectories(scriptPath.getParent());
        String script = """
                from pyspark.sql import SparkSession
                from pyspark.sql import functions as F

                spark = SparkSession.builder \\
                    .appName('%s_reference_output_oracle_probe') \\
                    .config('spark.sql.extensions', 'io.delta.sql.DeltaSparkSessionExtension') \\
                    .config('spark.sql.catalog.spark_catalog', 'org.apache.spark.sql.delta.catalog.DeltaCatalog') \\
                    .getOrCreate()

                column_name = '%s'
                df = spark.read.format('delta').load('%s')
                df.select(F.col(column_name).cast('string').alias(column_name)) \\
                    .where(F.col(column_name).isNotNull()) \\
                    .distinct() \\
                    .orderBy(column_name) \\
                    .coalesce(1) \\
                    .write \\
                    .mode('overwrite') \\
                    .option('header', 'true') \\
                    .csv('%s')
                spark.stop()
                """.formatted(
                pyString(codegenSlug),
                pyString(columnName),
                pyString(deltaOutputUri),
                pyString(containerCsvOutputPath)
        );
        Files.writeString(scriptPath, script);
    }

    private void writeDeltaReportProjectionScript(Path scriptPath,
                                                  String codegenSlug,
                                                  String deltaOutputUri,
                                                  String containerCsvOutputPath,
                                                  List<String> columns,
                                                  String orderColumn) throws Exception {
        Files.createDirectories(scriptPath.getParent());
        String columnList = columns.stream()
                .map(column -> "'" + pyString(column) + "'")
                .collect(Collectors.joining(", "));
        String script = """
                from pyspark.sql import SparkSession

                spark = SparkSession.builder \\
                    .appName('%s_report_oracle_probe') \\
                    .config('spark.sql.extensions', 'io.delta.sql.DeltaSparkSessionExtension') \\
                    .config('spark.sql.catalog.spark_catalog', 'org.apache.spark.sql.delta.catalog.DeltaCatalog') \\
                    .getOrCreate()

                columns = [%s]
                df = spark.read.format('delta').load('%s')
                df.select(*columns) \\
                    .orderBy('%s') \\
                    .coalesce(1) \\
                    .write \\
                    .mode('overwrite') \\
                    .option('header', 'true') \\
                    .csv('%s')
                spark.stop()
                """.formatted(
                pyString(codegenSlug),
                columnList,
                pyString(deltaOutputUri),
                pyString(orderColumn),
                pyString(containerCsvOutputPath)
        );
        Files.writeString(scriptPath, script);
    }

    private Map<String, Object> fixtureManifestDerivativeOverrides(Path fixtureManifestPath, String derivativeId) throws Exception {
        JsonNode root = objectMapper.readTree(fixtureManifestPath.toFile());
        for (JsonNode derivative : root.path("derivatives")) {
            if (!derivativeId.equals(derivative.path("derivative_id").asText())) {
                continue;
            }
            Map<String, Object> overrides = new LinkedHashMap<>();
            overrides.put("row_count", derivative.path("row_count").asInt());
            overrides.put("column_count", derivative.path("column_count").asInt());
            overrides.put("canonical_csv_sha256", derivative.path("canonical_csv_sha256").asText());
            overrides.put("schema_signature", derivative.path("schema_signature").asText());
            if (!derivative.path("schema").path("column_order_sha256").isMissingNode()) {
                overrides.put("column_order_sha256", derivative.path("schema").path("column_order_sha256").asText());
            }
            overrides.put("required_field_nulls", objectMapper.convertValue(
                    derivative.path("required_field_nulls"),
                    new TypeReference<Map<String, Object>>() {}
            ));
            overrides.put("business_keys", objectMapper.convertValue(
                    derivative.path("business_keys"),
                    new TypeReference<List<String>>() {}
            ));
            if (!derivative.path("partition_expectations").isMissingNode()) {
                overrides.put("partition_expectations", objectMapper.convertValue(
                        derivative.path("partition_expectations"),
                        new TypeReference<Map<String, Object>>() {}
                ));
            }
            return overrides;
        }
        throw new IllegalStateException("fixture manifest missing derivative " + derivativeId + ": " + fixtureManifestPath);
    }

    private List<String> fixtureManifestDerivativeColumns(Path fixtureManifestPath, String derivativeId) throws Exception {
        JsonNode root = objectMapper.readTree(fixtureManifestPath.toFile());
        for (JsonNode derivative : root.path("derivatives")) {
            if (!derivativeId.equals(derivative.path("derivative_id").asText())) {
                continue;
            }
            List<String> columns = new ArrayList<>();
            for (JsonNode column : derivative.path("schema").path("columns")) {
                columns.add(column.path("name").asText());
            }
            return columns;
        }
        throw new IllegalStateException("fixture manifest missing derivative " + derivativeId + ": " + fixtureManifestPath);
    }

    private List<String> fixtureManifestDerivativeColumnsByType(Path fixtureManifestPath,
                                                                String derivativeId,
                                                                String type) throws Exception {
        JsonNode root = objectMapper.readTree(fixtureManifestPath.toFile());
        for (JsonNode derivative : root.path("derivatives")) {
            if (!derivativeId.equals(derivative.path("derivative_id").asText())) {
                continue;
            }
            List<String> columns = new ArrayList<>();
            for (JsonNode column : derivative.path("schema").path("columns")) {
                if (type.equals(column.path("type").asText())) {
                    columns.add(column.path("name").asText());
                }
            }
            return columns;
        }
        throw new IllegalStateException("fixture manifest missing derivative " + derivativeId + ": " + fixtureManifestPath);
    }

    private Map<String, Object> expectedOverridesFromCsv(Path csvRoot) throws Exception {
        List<Path> csvFiles;
        try (var stream = Files.walk(csvRoot)) {
            csvFiles = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".csv"))
                    .sorted()
                    .toList();
        }
        MinioOutputOracleValidator.CsvTable table = MinioOutputOracleValidator.CsvTable.load(csvFiles);
        Map<String, String> inferredTypes = inferColumnTypes(table);

        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("row_count", table.rows().size());
        overrides.put("column_count", table.headers().size());
        overrides.put("canonical_csv_sha256", sha256(table.toCanonicalCsv().getBytes(StandardCharsets.UTF_8)));
        overrides.put("schema_signature", schemaSignature(table.headers(), inferredTypes));
        overrides.put("column_order_sha256", sha256(String.join("|", table.headers()).getBytes(StandardCharsets.UTF_8)));
        overrides.put("required_field_nulls", Map.of());
        overrides.put("business_keys", List.of());
        return overrides;
    }

    private Map<String, String> inferColumnTypes(MinioOutputOracleValidator.CsvTable table) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String header : table.headers()) {
            boolean allInteger = true;
            boolean allDecimalOrInteger = true;
            boolean allDate = true;
            boolean allBoolean = true;
            boolean hasLeadingZeroInteger = false;
            for (Map<String, String> row : table.rows()) {
                String value = row.get(header);
                if (value == null || value.isBlank()) {
                    continue;
                }
                hasLeadingZeroInteger |= value.matches("^0[0-9]+$");
                allBoolean &= value.matches("(?i:true|false)");
                allInteger &= value.matches("-?\\d+");
                allDecimalOrInteger &= value.matches("-?\\d+") || value.matches("-?\\d+\\.\\d+");
                allDate &= value.matches("\\d{4}-\\d{2}-\\d{2}");
            }
            if (hasLeadingZeroInteger) {
                out.put(header, "string");
            } else if (allBoolean) {
                out.put(header, "boolean");
            } else if (allInteger) {
                out.put(header, "integer");
            } else if (allDecimalOrInteger) {
                out.put(header, "decimal");
            } else if (allDate) {
                out.put(header, "date");
            } else {
                out.put(header, "string");
            }
        }
        return out;
    }

    private String schemaSignature(List<String> headers, Map<String, String> types) {
        String payload = headers.stream()
                .map(header -> header + ":" + types.get(header))
                .collect(Collectors.joining("|"));
        try {
            return sha256(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash schema signature payload", e);
        }
    }

    private String runCommand(List<String> command) throws Exception {
        Process process = new ProcessBuilder(new ArrayList<>(command))
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("Command failed (" + exitCode + "): "
                    + String.join(" ", command) + "\n" + output);
        }
        return output;
    }

    private void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.comparingInt(Path::getNameCount).reversed())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception ignored) {
                        }
                    });
        }
    }

    private String readTail(Path path, int maxChars) throws Exception {
        if (!Files.exists(path)) {
            return "";
        }
        String text = Files.readString(path);
        return text.length() <= maxChars ? text : text.substring(text.length() - maxChars);
    }

    private void scaffoldTenantRuntimeRepo(Path runtimeRepoRoot, String tenantId) {
        Path dbtProjectRoot = runtimeRepoRoot.resolve("dbt_project");
        try {
            Files.createDirectories(dbtProjectRoot.resolve("models/staging"));
            Files.createDirectories(dbtProjectRoot.resolve("models/intermediate"));
            Files.createDirectories(dbtProjectRoot.resolve("models/marts"));
            Files.createDirectories(dbtProjectRoot.resolve("snapshots"));
            Files.createDirectories(dbtProjectRoot.resolve("tests"));
            Files.createDirectories(dbtProjectRoot.resolve("macros"));
            Files.createDirectories(dbtProjectRoot.resolve("seeds"));
            Files.createDirectories(dbtProjectRoot.resolve("analyses"));

            Files.writeString(dbtProjectRoot.resolve("dbt_project.yml"), """
                    name: 'home_lending'
                    version: '0.1.0'
                    config-version: 2
                    profile: 'home_lending'
                    require-dbt-version: ">=1.7.0,<2.0.0"
                    model-paths: ["models"]
                    seed-paths: ["seeds"]
                    snapshot-paths: ["snapshots"]
                    test-paths: ["tests"]
                    macro-paths: ["macros"]
                    analysis-paths: ["analyses"]
                    target-path: "target"
                    clean-targets:
                      - target
                      - dbt_packages

                    quoting:
                      database: false
                      schema: false
                      identifier: false

                    models:
                      home_lending:
                        staging:
                          +materialized: table
                        intermediate:
                          +materialized: table
                        marts:
                          +materialized: table

                    snapshots:
                      home_lending:
                        +target_schema: snapshots
                    """);

            Files.writeString(dbtProjectRoot.resolve("profiles.yml"), """
                    home_lending:
                      target: "{{ env_var('DBT_TARGET', 'local') }}"
                      outputs:
                        local:
                          type: spark
                          method: session
                          host: NA
                          schema: "{{ env_var('DBT_SCHEMA', 'pulse_dev') }}"
                          threads: 2
                          server_side_parameters:
                            spark.master: "{{ env_var('SPARK_MASTER_URL', 'local[*]') }}"
                            spark.sql.extensions: io.delta.sql.DeltaSparkSessionExtension
                            spark.sql.catalog.spark_catalog: org.apache.spark.sql.delta.catalog.DeltaCatalog
                            spark.jars.packages: org.apache.hadoop:hadoop-aws:3.3.4,com.amazonaws:aws-java-sdk-bundle:1.12.262,io.delta:delta-spark_2.12:3.3.2
                            spark.hadoop.fs.s3a.impl: org.apache.hadoop.fs.s3a.S3AFileSystem
                            spark.hadoop.fs.s3a.endpoint: "{{ env_var('MINIO_ENDPOINT', 'http://minio:9000') }}"
                            spark.hadoop.fs.s3a.access.key: "{{ env_var('AWS_ACCESS_KEY_ID', env_var('MINIO_ACCESS_KEY', 'minioadmin')) }}"
                            spark.hadoop.fs.s3a.secret.key: "{{ env_var('AWS_SECRET_ACCESS_KEY', env_var('MINIO_SECRET_KEY', 'minioadmin')) }}"
                            spark.hadoop.fs.s3a.path.style.access: 'true'
                            spark.hadoop.fs.s3a.connection.ssl.enabled: 'false'
                    """);

            Files.writeString(dbtProjectRoot.resolve("packages.yml"), """
                    packages:
                      - package: dbt-labs/dbt_utils
                        version: [">=1.1.0", "<2.0.0"]
                    """);

            Files.writeString(dbtProjectRoot.resolve("macros/pulse_delta_table.sql"), """
                    {% materialization pulse_delta_table, adapter='spark' %}
                        {%- set identifier = model['alias'] -%}
                        {%- set location_root = config.get('location_root') -%}
                        {%- if location_root is none or location_root | trim == '' -%}
                            {{ exceptions.raise_compiler_error('pulse_delta_table requires config.location_root') }}
                        {%- endif -%}
                        {%- set target_location = location_root.rstrip('/') ~ '/' ~ identifier -%}
                        {%- set target_relation = api.Relation.create(
                            identifier=identifier,
                            schema=schema,
                            database=database,
                            type='table'
                        ) -%}

                        {{ run_hooks(pre_hooks) }}

                        {%- call statement('drop_relation') -%}
                            DROP TABLE IF EXISTS {{ target_relation }}
                        {%- endcall -%}

                        {%- call statement('main') -%}
                            CREATE TABLE {{ target_relation }}
                            USING DELTA
                            LOCATION '{{ target_location }}'
                            AS
                            {{ compiled_code }}
                        {%- endcall -%}

                        {{ run_hooks(post_hooks) }}
                        {{ return({'relations': [target_relation]}) }}
                    {% endmaterialization %}
                    """);

            Files.writeString(dbtProjectRoot.resolve("macros/pulse_delta_incremental_merge.sql"), """
                    {% materialization pulse_delta_incremental_merge, adapter='spark' %}
                        {%- set identifier = model['alias'] -%}
                        {%- set location_root = config.get('location_root') -%}
                        {%- set unique_key = config.get('unique_key') -%}
                        {%- if location_root is none or location_root | trim == '' -%}
                            {{ exceptions.raise_compiler_error('pulse_delta_incremental_merge requires config.location_root') }}
                        {%- endif -%}
                        {%- if unique_key is none or unique_key | length == 0 -%}
                            {{ exceptions.raise_compiler_error('pulse_delta_incremental_merge requires config.unique_key') }}
                        {%- endif -%}
                        {%- if unique_key is string -%}
                            {%- set unique_keys = [unique_key] -%}
                        {%- else -%}
                            {%- set unique_keys = unique_key -%}
                        {%- endif -%}
                        {%- set predicates = [] -%}
                        {%- for key in unique_keys -%}
                            {%- do predicates.append('target.`' ~ key ~ '` = source.`' ~ key ~ '`') -%}
                        {%- endfor -%}
                        {%- set target_location = location_root.rstrip('/') ~ '/' ~ identifier -%}
                        {%- set target_relation = api.Relation.create(
                            identifier=identifier,
                            schema=schema,
                            database=database,
                            type='table'
                        ) -%}
                        {%- set existing_relation = adapter.get_relation(database=database, schema=schema, identifier=identifier) -%}
                        {%- set source_view = identifier ~ '__pulse_incremental_source' -%}

                        {{ run_hooks(pre_hooks) }}

                        {%- if existing_relation is none -%}
                            {%- call statement('main') -%}
                                CREATE TABLE {{ target_relation }}
                                USING DELTA
                                LOCATION '{{ target_location }}'
                                AS
                                {{ compiled_code }}
                            {%- endcall -%}
                        {%- else -%}
                            {%- call statement('stage_incremental_source') -%}
                                CREATE OR REPLACE TEMP VIEW {{ source_view }} AS
                                {{ compiled_code }}
                            {%- endcall -%}

                            {%- call statement('main') -%}
                                MERGE INTO {{ target_relation }} AS target
                                USING {{ source_view }} AS source
                                ON {{ predicates | join(' AND ') }}
                                WHEN MATCHED THEN UPDATE SET *
                                WHEN NOT MATCHED THEN INSERT *
                            {%- endcall -%}
                        {%- endif -%}

                        {{ run_hooks(post_hooks) }}
                        {{ return({'relations': [target_relation]}) }}
                    {% endmaterialization %}
                    """);

            Files.writeString(dbtProjectRoot.resolve("macros/spark_create_table_as.sql"), """
                    {%- macro spark__create_table_as(temporary, relation, compiled_code, language='sql') -%}
                      {%- if language == 'sql' -%}
                        {%- if temporary -%}
                          {{ create_temporary_view(relation, compiled_code) }}
                        {%- else -%}
                          {%- set file_format = config.get('file_format', validator=validation.any[basestring]) -%}
                          {%- set is_snapshot = model.resource_type == 'snapshot' -%}
                          {% if file_format in ['delta', 'iceberg'] and not is_snapshot %}
                            create or replace table {{ relation }}
                          {% else %}
                            create table {{ relation }}
                          {% endif %}
                          {%- set contract_config = config.get('contract') -%}
                          {%- if contract_config.enforced -%}
                            {{ get_assert_columns_equivalent(compiled_code) }}
                            {%- set compiled_code = get_select_subquery(compiled_code) %}
                          {% endif %}
                          {{ file_format_clause() }}
                          {{ options_clause() }}
                          {{ tblproperties_clause() }}
                          {{ partition_cols(label="partitioned by") }}
                          {{ clustered_cols(label="clustered by") }}
                          {{ location_clause() }}
                          {{ comment_clause() }}

                          as
                          {{ compiled_code }}
                        {%- endif -%}
                      {%- elif language == 'python' -%}
                        {{ py_write_table(compiled_code=compiled_code, target_relation=relation) }}
                      {%- endif -%}
                    {%- endmacro -%}
                    """);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to scaffold runtime dbt project for " + tenantId, e);
        }
    }

    private String defaultNameFor(String blueprintKey) {
        return switch (blueprintKey) {
            case "GenericAggregate" -> "Aggregate Loan Balances";
            case "AggregateMaterialization" -> "Materialize Loan Aggregates";
            case "BronzeToSilverCleaning" -> "Clean Loan Master";
            case "DedupeAndMerge" -> "Dedupe Loan Records";
            case "GenericJoin" -> "Join Loan Master";
            case "PIIMasking" -> "Mask Loan PII";
            case "SchemaNormalization" -> "Normalize Loan Schema";
            case "FeatureTablePublish" -> "Publish Loan Features";
            case "ReferenceDataPublish" -> "Publish State Reference";
            case "FactBuild" -> "Build Loan Fact";
            case "WideDenormalizedMart" -> "Build Wide Loan Mart";
            case "IncrementalMerge" -> "Merge Loan Updates";
            case "SCD2Dimension" -> "Build Loan SCD2 Dimension";
            default -> blueprintKey;
        };
    }

    private void createIncrementalMergeDeltaCsv(Path sourceCsv, Path deltaCsv, Path expectedMergedCsv) throws Exception {
        MinioOutputOracleValidator.CsvTable table = MinioOutputOracleValidator.CsvTable.load(List.of(sourceCsv));
        if (table.rows().size() < 2) {
            throw new IllegalStateException("IncrementalMerge proof requires at least two source rows");
        }

        Map<String, Map<String, String>> expectedByLoanId = new LinkedHashMap<>();
        for (Map<String, String> row : table.rows()) {
            expectedByLoanId.put(row.get("loan_id"), new LinkedHashMap<>(row));
        }

        Map<String, String> updated = new LinkedHashMap<>(table.rows().get(0));
        updated.put("current_upb", "999999.99");
        updated.put("loan_status", "Modified");
        updated.put("last_payment_date", "2026-12-31");

        Map<String, String> inserted = new LinkedHashMap<>(table.rows().get(1));
        inserted.put("loan_id", "LN199999");
        inserted.put("loan_number", "9999999999");
        inserted.put("current_upb", "123456.78");
        inserted.put("loan_status", "New");
        inserted.put("last_payment_date", "2026-12-30");

        expectedByLoanId.put(updated.get("loan_id"), updated);
        expectedByLoanId.put(inserted.get("loan_id"), inserted);

        writeCsv(deltaCsv, table.headers(), List.of(updated, inserted));
        writeCsv(expectedMergedCsv, table.headers(), new ArrayList<>(expectedByLoanId.values()));
    }

    private void writeCsv(Path targetCsv, List<String> headers, List<Map<String, String>> rows) throws Exception {
        Files.createDirectories(targetCsv.getParent());
        Files.writeString(
                targetCsv,
                new MinioOutputOracleValidator.CsvTable(headers, rows).toCanonicalCsv(),
                StandardCharsets.UTF_8
        );
    }

    private void createRawSsnCsv(Path sourceCsv, Path targetCsv) throws Exception {
        Files.createDirectories(targetCsv.getParent());
        List<String> lines = Files.readAllLines(sourceCsv, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            throw new IllegalStateException("CSV is empty: " + sourceCsv);
        }
        List<String> headers = List.of(lines.get(0).split(",", -1));
        int ssnIndex = headers.indexOf("borrower_ssn_masked");
        if (ssnIndex < 0) {
            throw new IllegalStateException("borrower_ssn_masked column not found in " + sourceCsv);
        }
        List<String> rewritten = new ArrayList<>();
        rewritten.add(lines.get(0));
        for (int i = 1; i < lines.size(); i++) {
            String[] cells = lines.get(i).split(",", -1);
            if (ssnIndex < cells.length) {
                String value = cells[ssnIndex];
                String last4 = value.length() >= 4 ? value.substring(value.length() - 4) : String.format("%04d", i % 10000);
                cells[ssnIndex] = "123-45-" + last4;
            }
            rewritten.add(String.join(",", cells));
        }
        Files.write(targetCsv, rewritten, StandardCharsets.UTF_8);
    }

    private void createDuplicateAugmentedCsv(Path sourceCsv, Path targetCsv) throws Exception {
        Files.createDirectories(targetCsv.getParent());
        List<String> lines = Files.readAllLines(sourceCsv, StandardCharsets.UTF_8);
        if (lines.size() < 3) {
            throw new IllegalStateException("Expected loan_master fixture with at least two data rows: " + sourceCsv);
        }
        List<String> duplicated = new ArrayList<>(lines);
        duplicated.add(lines.get(1));
        duplicated.add(lines.get(2));
        Files.write(targetCsv, duplicated, StandardCharsets.UTF_8);
    }

    private void createAnomalyAugmentedCsv(Path sourceCsv, Path targetCsv) throws Exception {
        Files.createDirectories(targetCsv.getParent());
        List<String> lines = Files.readAllLines(sourceCsv, StandardCharsets.UTF_8);
        if (lines.size() < 2) {
            throw new IllegalStateException("Expected loan_master fixture with data rows: " + sourceCsv);
        }
        List<String> headers = List.of(lines.get(0).split(",", -1));
        int upbIndex = headers.indexOf("current_upb");
        int rateIndex = headers.indexOf("interest_rate");
        if (upbIndex < 0 || rateIndex < 0) {
            throw new IllegalStateException("current_upb/interest_rate columns missing from " + sourceCsv);
        }
        List<String> output = new ArrayList<>();
        output.add(lines.get(0));
        for (int i = 1; i < lines.size(); i++) {
            String[] values = lines.get(i).split(",", -1);
            if (i == 1) {
                values[upbIndex] = "999999999.99";
                values[rateIndex] = "99.99";
            }
            output.add(String.join(",", values));
        }
        Files.write(targetCsv, output, StandardCharsets.UTF_8);
    }

    private void createDelinquentLoansExpectedCsv(Path sourceCsv, Path targetCsv) throws Exception {
        Files.createDirectories(targetCsv.getParent());
        List<String> lines = Files.readAllLines(sourceCsv, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            throw new IllegalStateException("CSV is empty: " + sourceCsv);
        }
        List<String> headers = List.of(lines.get(0).split(",", -1));
        int loanIdIndex = headers.indexOf("loan_id");
        int delinquencyIndex = headers.indexOf("months_delinquent");
        if (loanIdIndex < 0 || delinquencyIndex < 0) {
            throw new IllegalStateException("loan_id/months_delinquent columns missing from " + sourceCsv);
        }
        List<String> output = new ArrayList<>();
        output.add(lines.get(0));
        lines.stream()
                .skip(1)
                .filter(line -> {
                    String[] values = line.split(",", -1);
                    return Integer.parseInt(values[delinquencyIndex]) > 0;
                })
                .sorted(Comparator.comparing(line -> line.split(",", -1)[loanIdIndex]))
                .map(this::normalizeRuntimeBooleanCsvCells)
                .forEach(output::add);
        Files.write(targetCsv, output, StandardCharsets.UTF_8);
    }

    private String normalizeRuntimeBooleanCsvCells(String line) {
        String[] values = line.split(",", -1);
        for (int i = 0; i < values.length; i++) {
            if ("True".equals(values[i])) {
                values[i] = "true";
            } else if ("False".equals(values[i])) {
                values[i] = "false";
            }
        }
        return String.join(",", values);
    }

    private void createRiskBandCsv(Path sourceCsv, Path targetCsv) throws Exception {
        Files.createDirectories(targetCsv.getParent());
        List<String> lines = Files.readAllLines(sourceCsv, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            throw new IllegalStateException("CSV is empty: " + sourceCsv);
        }
        List<String> headers = List.of(lines.get(0).split(",", -1));
        int delinquencyIndex = headers.indexOf("months_delinquent");
        if (delinquencyIndex < 0) {
            throw new IllegalStateException("months_delinquent column missing from " + sourceCsv);
        }
        List<String> output = new ArrayList<>();
        output.add(lines.get(0) + ",servicing_risk_band");
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            String[] values = line.split(",", -1);
            int monthsDelinquent = Integer.parseInt(values[delinquencyIndex]);
            String riskBand = monthsDelinquent >= 12 ? "critical" : monthsDelinquent >= 3 ? "watch" : "stable";
            output.add(line + "," + riskBand);
        }
        Files.write(targetCsv, output, StandardCharsets.UTF_8);
    }

    private List<String> csvHeaders(Path csvPath) throws Exception {
        List<String> lines = Files.readAllLines(csvPath, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            throw new IllegalStateException("CSV is empty: " + csvPath);
        }
        return List.of(lines.get(0).split(",", -1));
    }

    private String shortId(String input) {
        return Integer.toHexString(Math.abs(input.hashCode()));
    }

    private void dropPostgresTable(String tableName) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:postgresql://localhost:5432/pulse", "pulse", "pulse");
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS public.\"" + tableName.replace("\"", "\"\"") + "\"");
        }
    }

    private String underscoreSlug(String input) {
        return input.toLowerCase()
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_|_$", "");
    }

    private String pyString(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    private String pythonListLiteral(List<String> values) {
        return values.stream()
                .map(value -> "'" + pyString(value) + "'")
                .collect(Collectors.joining(", ", "[", "]"));
    }

    private String sha256(byte[] payload) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(payload));
    }

    private record PipelineFixture(Pipeline pipeline, PipelineVersion version) {
    }

    private record ProofResult(
            Path evidenceRoot,
            String airflowState,
            String oracleVerdict,
            List<String> oracleFailureCodes,
            String dbtLogTail
    ) {
    }
}
