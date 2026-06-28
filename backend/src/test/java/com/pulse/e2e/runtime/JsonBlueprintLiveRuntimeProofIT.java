package com.pulse.e2e.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.blueprint.model.Blueprint;
import com.pulse.blueprint.model.BlueprintCategory;
import com.pulse.blueprint.repository.BlueprintRepository;
import com.pulse.codegen.model.GenerationRun;
import com.pulse.codegen.repository.GeneratedArtifactRepository;
import com.pulse.codegen.service.CodeGenerationService;
import com.pulse.common.text.Slugify;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
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
class JsonBlueprintLiveRuntimeProofIT {

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
    void jsonFlatten_passesLiveRuntimeOracleProof() throws Exception {
        Path fixture = Path.of("build/e2e-json-fixtures/json-flatten-source.jsonl");
        createJsonFlattenFixture(fixture);
        ProofResult result = runJsonProof(
                "json-flatten",
                "Json Flatten Runtime Proof",
                "JsonFlatten",
                "Flatten Servicing Payload",
                "json",
                fixture,
                "flat_output",
                Map.of(
                        "source_columns", List.of("servicing_payload"),
                        "separator", "_",
                        "max_depth", 2,
                        "explode_arrays", false,
                        "keep_original", true,
                        "storage_backend", STORAGE_BACKEND,
                        "lake_layer", "silver",
                        "lake_format", "delta"
                ),
                List.of("loan_id", "risk_band", "next_due_date", "delinquency_bucket"),
                List.of("loan_id"),
                "json",
                List.of(
                        "loan_id",
                        "servicing_payload.risk_band as risk_band",
                        "servicing_payload.next_due_date as next_due_date",
                        "servicing_payload.delinquency_bucket as delinquency_bucket"
                ),
                List.of("loan_id")
        );

        assertEquals("success", result.airflowState());
        assertEquals("PASS", result.oracleVerdict());
        assertTrue(result.oracleFailureCodes().isEmpty(), () -> "unexpected oracle failures: " + result.oracleFailureCodes());
        assertTrue(Files.exists(result.evidenceRoot().resolve("data/minio-output-probe.json")));
        assertTrue(Files.exists(result.evidenceRoot().resolve("data/data-oracle-comparison.json")));
    }

    @Test
    void jsonStruct_passesLiveRuntimeOracleProof() throws Exception {
        Path fixture = Path.of("src/test/resources/e2e/fixtures/json_runtime/struct_input.csv");
        ProofResult result = runJsonProof(
                "json-struct",
                "Json Struct Runtime Proof",
                "JsonStruct",
                "Build Borrower Struct",
                "csv",
                fixture,
                "struct_output",
                Map.of(
                        "output_format", "json_string",
                        "mappings", Map.of(
                                "borrower", List.of(
                                        "borrower_first_name",
                                        "borrower_last_name",
                                        "risk_band"
                                )
                        ),
                        "drop_source_columns", false,
                        "passthrough_columns", List.of("loan_id", "loan_number"),
                        "storage_backend", STORAGE_BACKEND,
                        "lake_layer", "silver",
                        "lake_format", "delta"
                ),
                List.of("loan_id", "loan_number", "borrower"),
                List.of("loan_id", "loan_number"),
                "csv",
                List.of(
                        "loan_id",
                        "loan_number",
                        "to_json(named_struct('first_name', borrower_first_name, 'last_name', borrower_last_name, 'risk_band', risk_band)) as borrower"
                ),
                List.of("loan_id", "loan_number")
        );

        assertEquals("success", result.airflowState());
        assertEquals("PASS", result.oracleVerdict());
        assertTrue(result.oracleFailureCodes().isEmpty(), () -> "unexpected oracle failures: " + result.oracleFailureCodes());
        assertTrue(Files.exists(result.evidenceRoot().resolve("data/minio-output-probe.json")));
        assertTrue(Files.exists(result.evidenceRoot().resolve("data/data-oracle-comparison.json")));
    }

    private ProofResult runJsonProof(String scenarioSlug,
                                     String pipelineBaseName,
                                     String blueprintKey,
                                     String instanceName,
                                     String sourceFormat,
                                     Path sourceFixture,
                                     String outputPort,
                                     Map<String, Object> blueprintParams,
                                     List<String> actualSelectExprs,
                                     List<String> actualOrderBy,
                                     String expectedReadFormat,
                                     List<String> expectedSelectExprs,
                                     List<String> expectedOrderBy) throws Exception {
        Path airflowRepoBase = Path.of("/Users/aameradam/pulse-repos");
        assertTrue(Files.isDirectory(airflowRepoBase), "Expected Airflow-mounted repo base to exist");
        assertTrue(Files.isRegularFile(sourceFixture), "Expected source fixture at " + sourceFixture);

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
            PipelineFixture fixture = createPipelineFixture(
                    tenantId,
                    pipelineName,
                    blueprintKey,
                    instanceName,
                    sourceFormat,
                    blueprintParams,
                    outputPort
            );

            stageSourceFileForPipeline(pathPipelineSlug, sourceFixture);
            scaffoldTenantRuntimeRepo(runtimeRepoRoot, tenantId);

            GenerationRun run = codeGenerationService.generate(
                    fixture.pipeline().getId(),
                    fixture.version().getId(),
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
                    Map.of("write_loan_master_to_lake", underscoreSlug(instanceName))
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
                    Duration.ofSeconds(240),
                    Duration.ofSeconds(5),
                    dagSubdir
            );

            List<Map<String, Object>> taskStates = adapter.taskStatesForDagRun(dagId, executionDate, dagSubdir);
            if ("timeout".equals(status.state())
                    && !taskStates.isEmpty()
                    && taskStates.stream().allMatch(task -> "success".equals(String.valueOf(task.get("state"))))) {
                List<String> observedStates = new ArrayList<>(status.observedStates());
                observedStates.add("postcheck_task_states:success");
                status = new LocalAirflowCliAdapter.DagRunStatus(dagId, executionDate, "success", observedStates);
            }
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
            if (!"success".equals(status.state())) {
                return new ProofResult(evidenceRoot, status.state(), null, List.of(), dbtLogTail);
            }

            Path validationRoot = runtimeRepoRoot.resolve("validation-output");
            Files.createDirectories(validationRoot);
            Path sourceCopy = validationRoot.resolve(sourceFixture.getFileName().toString());
            Files.copy(sourceFixture, sourceCopy);

            String containerRepoRoot = "/opt/pulse/repo/" + runtimeRepoRoot.getFileName();
            String lakeWriterOutputUri = "s3a://" + STORAGE_ROOT_LAKE + "/servicing/" + SOR_SLUG + "/"
                    + pathPipelineSlug + "/gold/write-loan-master-to-lake/";
            Path actualCsvOutput = validationRoot.resolve("actual-" + scenarioSlug);
            Path expectedCsvOutput = validationRoot.resolve("expected-" + scenarioSlug);
            Path actualExportScript = validationRoot.resolve("export_actual_" + scenarioSlug + ".py");
            Path expectedExportScript = validationRoot.resolve("export_expected_" + scenarioSlug + ".py");

            writeProjectionScript(
                    actualExportScript,
                    codegenSlug + "_actual",
                    "delta",
                    lakeWriterOutputUri,
                    containerRepoRoot + "/validation-output/actual-" + scenarioSlug,
                    actualSelectExprs,
                    actualOrderBy
            );
            writeProjectionScript(
                    expectedExportScript,
                    codegenSlug + "_expected",
                    expectedReadFormat,
                    containerRepoRoot + "/validation-output/" + sourceCopy.getFileName(),
                    containerRepoRoot + "/validation-output/expected-" + scenarioSlug,
                    expectedSelectExprs,
                    expectedOrderBy
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
                    status.state(),
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
                                                  String instanceName,
                                                  String sourceFormat,
                                                  Map<String, Object> blueprintParams,
                                                  String outputPort) {
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
        sor.setDescription("JSON blueprint live runtime proof source");
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
                "file_format", sourceFormat,
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

        Map<String, Object> ingestionParams = new LinkedHashMap<>();
        ingestionParams.put("connector_instance_id", source.getId());
        ingestionParams.put("connector_name", source.getName());
        ingestionParams.put("file_format", sourceFormat);
        ingestionParams.put("infer_schema", "true");
        ingestionParams.put("storage_backend", STORAGE_BACKEND);
        ingestionParams.put("lake_layer", "bronze");
        ingestionParams.put("lake_format", "delta");
        if ("csv".equals(sourceFormat)) {
            ingestionParams.put("header", "true");
        }

        var ingest = compositionService.addInstance(
                pipeline.getId(),
                version.getId(),
                "FileIngestion",
                "Ingest Loan Master",
                ingestionParams
        );
        var transform = compositionService.addInstance(
                pipeline.getId(),
                version.getId(),
                blueprintKey,
                instanceName,
                blueprintParams
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
                        "write_mode", "overwrite",
                        "storage_backend", STORAGE_BACKEND
                )
        );

        compositionService.wirePort(version.getId(), ingest.getId(), "raw_output", transform.getId(), inputPortFor(blueprintKey));
        compositionService.wirePort(version.getId(), transform.getId(), outputPort, sink.getId(), "data_input");

        return new PipelineFixture(pipeline, version);
    }

    private String inputPortFor(String blueprintKey) {
        return switch (blueprintKey) {
            case "JsonFlatten", "JsonStruct" -> "data_input";
            default -> throw new IllegalArgumentException("Unsupported json blueprint: " + blueprintKey);
        };
    }

    private void seedBlueprints(String blueprintKey) {
        String suffix = shortId(blueprintKey);
        blueprintRepository.save(fileIngestionBlueprint("bpf-" + suffix));
        blueprintRepository.save(lakeWriterBlueprint("bps-" + suffix));
        if ("JsonFlatten".equals(blueprintKey)) {
            blueprintRepository.save(jsonFlattenBlueprint("bpj-" + suffix));
        } else if ("JsonStruct".equals(blueprintKey)) {
            blueprintRepository.save(jsonStructBlueprint("bpj-" + suffix));
        } else {
            throw new IllegalArgumentException("Unsupported json blueprint: " + blueprintKey);
        }
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

    private Blueprint jsonFlattenBlueprint(String id) {
        Blueprint bp = new Blueprint();
        bp.setId(id);
        bp.setBlueprintKey("JsonFlatten");
        bp.setName("JSON Flatten");
        bp.setCategory(BlueprintCategory.TRANSFORM);
        bp.setStatus("active");
        bp.setDeferred(false);
        bp.setDescription("Flatten JSON fields");
        bp.setVersion("1.0.0");
        bp.setParamsSchema(List.of());
        bp.setInputPorts(List.of(Map.of("name", "data_input")));
        bp.setOutputPorts(List.of(Map.of("name", "flat_output")));
        bp.setRuntimeRequirements(Map.of());
        bp.setPipelineConfig(false);
        bp.setValidLayers(List.of("silver"));
        bp.setComputeBackend("spark");
        bp.setEmitStrategy("generate");
        return bp;
    }

    private Blueprint jsonStructBlueprint(String id) {
        Blueprint bp = new Blueprint();
        bp.setId(id);
        bp.setBlueprintKey("JsonStruct");
        bp.setName("Build JSON/Struct");
        bp.setCategory(BlueprintCategory.TRANSFORM);
        bp.setStatus("active");
        bp.setDeferred(false);
        bp.setDescription("Build nested structs from flat columns");
        bp.setVersion("1.0.0");
        bp.setParamsSchema(List.of());
        bp.setInputPorts(List.of(Map.of("name", "data_input")));
        bp.setOutputPorts(List.of(Map.of("name", "struct_output")));
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

    private void stageSourceFileForPipeline(String pathPipelineSlug, Path sourceFile) throws Exception {
        String fileName = sourceFile.getFileName().toString();
        runCommand(List.of("docker", "cp", sourceFile.toString(), "pulse-minio-1:/tmp/" + fileName));
        String objectPath = STORAGE_ROOT_FILES + "/servicing/" + SOR_SLUG + "/" + pathPipelineSlug + "/SRC/" + fileName;
        runCommand(List.of(
                "docker", "exec", "pulse-minio-1", "sh", "-lc",
                "mc alias set local http://localhost:9000 minioadmin minioadmin >/dev/null"
                        + " && mc mb --ignore-existing local/" + STORAGE_ROOT_FILES + " >/dev/null"
                        + " && mc cp /tmp/" + fileName + " local/" + objectPath
        ));
    }

    private void createJsonFlattenFixture(Path fixture) throws Exception {
        Files.createDirectories(fixture.getParent());
        Files.writeString(fixture, """
                {"loan_id":"LN-001","servicing_payload":{"risk_band":"A","next_due_date":"2026-05-01","delinquency_bucket":"current"}}
                {"loan_id":"LN-002","servicing_payload":{"risk_band":"B","next_due_date":"2026-05-15","delinquency_bucket":"late"}}
                """, StandardCharsets.UTF_8);
    }

    private void writeProjectionScript(Path scriptPath,
                                       String appName,
                                       String readFormat,
                                       String inputPath,
                                       String containerCsvOutputPath,
                                       List<String> selectExprs,
                                       List<String> orderBy) throws Exception {
        Files.createDirectories(scriptPath.getParent());
        String builder = "delta".equals(readFormat)
                ? """
                    spark = SparkSession.builder \\
                        .appName('%s') \\
                        .config('spark.sql.extensions', 'io.delta.sql.DeltaSparkSessionExtension') \\
                        .config('spark.sql.catalog.spark_catalog', 'org.apache.spark.sql.delta.catalog.DeltaCatalog') \\
                        .getOrCreate()
                    """.formatted(pyString(appName))
                : """
                    spark = SparkSession.builder \\
                        .appName('%s') \\
                        .getOrCreate()
                    """.formatted(pyString(appName));
        String reader = switch (readFormat) {
            case "delta" -> "df = spark.read.format('delta').load('%s')".formatted(pyString(inputPath));
            case "csv" -> "df = spark.read.option('header', 'true').option('inferSchema', 'true').csv('%s')".formatted(pyString(inputPath));
            case "json" -> "df = spark.read.json('%s')".formatted(pyString(inputPath));
            default -> throw new IllegalArgumentException("Unsupported readFormat: " + readFormat);
        };
        String script = """
                from pyspark.sql import SparkSession
                from pyspark.sql import functions as F

                %s
                %s
                df.selectExpr(%s) \\
                    .orderBy(%s) \\
                    .coalesce(1) \\
                    .write \\
                    .mode('overwrite') \\
                    .option('header', 'true') \\
                    .csv('%s')
                spark.stop()
                """.formatted(
                builder,
                reader,
                pythonListLiteral(selectExprs),
                pythonListLiteral(orderBy),
                pyString(containerCsvOutputPath)
        );
        Files.writeString(scriptPath, script);
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
        } catch (Exception e) {
            throw new IllegalStateException("Failed to scaffold runtime dbt project for " + tenantId, e);
        }
    }

    private String shortId(String input) {
        return Integer.toHexString(Math.abs(input.hashCode()));
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
