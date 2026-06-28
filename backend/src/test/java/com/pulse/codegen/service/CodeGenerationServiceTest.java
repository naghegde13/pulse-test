package com.pulse.codegen.service;

import com.pulse.blueprint.model.Blueprint;
import com.pulse.blueprint.model.BlueprintCategory;
import com.pulse.blueprint.repository.BlueprintRepository;
import com.pulse.codegen.GxCodeGenerator;
import com.pulse.codegen.opengine.CodegenOpEngine;
import com.pulse.codegen.opengine.ModeResolver;
import com.pulse.codegen.model.GeneratedArtifact;
import com.pulse.codegen.model.GenerationRun;
import com.pulse.codegen.repository.GeneratedArtifactRepository;
import com.pulse.codegen.repository.GenerationRunRepository;
import com.pulse.broker.mirror.RemoteTargetRuntimeMirror;
import com.pulse.broker.mirror.RemoteTargetRuntimeMirrorRepository;
import com.pulse.git.service.GitCommitService;
import com.pulse.pipeline.model.Pipeline;
import com.pulse.pipeline.model.PipelineVersion;
import com.pulse.pipeline.model.PortWiring;
import com.pulse.pipeline.model.SubPipelineInstance;
import com.pulse.pipeline.opengine.OpList;
import com.pulse.pipeline.repository.PipelineRepository;
import com.pulse.pipeline.repository.PipelineVersionRepository;
import com.pulse.pipeline.repository.PortWiringRepository;
import com.pulse.pipeline.repository.SubPipelineInstanceRepository;
import com.pulse.secret.service.SecretReferenceService;
import com.pulse.runtime.model.RuntimeAuthority;
import com.pulse.runtime.model.RuntimePersona;
import com.pulse.runtime.model.SecretAuthorityKind;
import com.pulse.runtime.service.RuntimeAuthorityService;
import com.pulse.sor.model.ConnectorInstance;
import com.pulse.sor.model.CredentialProfile;
import com.pulse.sor.model.Dataset;
import com.pulse.sor.repository.ConnectorInstanceRepository;
import com.pulse.sor.repository.CredentialProfileRepository;
import com.pulse.sor.repository.DatasetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CodeGenerationServiceTest {

    @Mock private GenerationRunRepository runRepo;
    @Mock private GeneratedArtifactRepository artifactRepo;
    @Mock private PipelineRepository pipelineRepo;
    @Mock private PipelineVersionRepository versionRepo;
    @Mock private SubPipelineInstanceRepository instanceRepo;
    @Mock private BlueprintRepository blueprintRepo;
    @Mock private CredentialProfileRepository credentialProfileRepo;
    @Mock private ConnectorInstanceRepository connectorInstanceRepo;
    @Mock private PortWiringRepository wiringRepo;
    @Mock private DatasetRepository datasetRepo;
    @Mock private CompilePlanService compilePlanService;
    @org.mockito.Spy private SecretReferenceService secretReferenceService = new SecretReferenceService();
    @org.mockito.Spy private GxCodeGenerator gxCodeGenerator = new GxCodeGenerator();
    @Mock private GitCommitService gitCommitService;
    @Mock private com.pulse.sor.repository.SystemOfRecordRepository sorRepo;
    @Mock private com.pulse.sor.repository.DomainRepository domainRepo;
    @Mock private com.pulse.storage.repository.StorageBackendRepository storageBackendRepo;
    @Mock private com.pulse.storage.PathConventionService pathConventionService;
    @Mock private RuntimeAuthorityService runtimeAuthorityService;
    @Mock private com.pulse.storage.contract.service.TableContractService tableContractService;
    @Mock private RemoteTargetRuntimeMirrorRepository remoteTargetRuntimeMirrorRepository;

    private CodegenOpEngine codegenOpEngine;
    private CodeGenerationService service;

    // --- Shared test fixtures ---

    private Pipeline testPipeline;
    private PipelineVersion testVersion;
    private GenerationRun savedRun;

    @BeforeEach
    void setUp() {
        lenient().when(runtimeAuthorityService.getAuthority()).thenReturn(testRuntimeAuthority());
        lenient().when(runtimeAuthorityService.getActivePersona()).thenReturn(RuntimePersona.GCP_PULSE);
        codegenOpEngine = spy(new CodegenOpEngine(new ModeResolver(runtimeAuthorityService)));
        service = new CodeGenerationService(
                runRepo,
                artifactRepo,
                pipelineRepo,
                versionRepo,
                instanceRepo,
                blueprintRepo,
                credentialProfileRepo,
                connectorInstanceRepo,
                wiringRepo,
                datasetRepo,
                compilePlanService,
                secretReferenceService,
                gxCodeGenerator,
                gitCommitService,
                sorRepo,
                domainRepo,
                storageBackendRepo,
                pathConventionService,
                runtimeAuthorityService,
                tableContractService,
                remoteTargetRuntimeMirrorRepository,
                codegenOpEngine);

        testPipeline = new Pipeline();
        testPipeline.setId("pipeline-1");
        testPipeline.setTenantId("tenant-1");
        testPipeline.setName("Test Pipeline");
        testPipeline.setDescription("A test pipeline");
        testPipeline.setDomainName("Servicing");
        testPipeline.setDomainId("domain-1");
        testPipeline.setCreatedBy("user-1");

        testVersion = new PipelineVersion();
        testVersion.setId("version-1");
        testVersion.setPipelineId("pipeline-1");
        testVersion.setRevision(1);

        savedRun = new GenerationRun();
        savedRun.setId("run-1");
        savedRun.setPipelineId("pipeline-1");
        savedRun.setVersionId("version-1");

        lenient().when(wiringRepo.findByVersionIdOrderByCreatedAtAsc(anyString()))
                .thenReturn(List.of());
        lenient().when(compilePlanService.build(any(), anyString(), anyList(), anyList()))
                .thenAnswer(inv -> buildCompilePlan(inv.getArgument(2)));
    }

    @Test
    void generate_emitsCompilePlanAndNamespacedArtifacts() {
        SubPipelineInstance inst = buildIngestionInstance("HR Files", "FileIngestion",
                Map.of("connector_name", "s3_source"));
        Blueprint bp = buildBlueprint("FileIngestion", BlueprintCategory.INGESTION);
        stubGenerate(List.of(inst), bp);

        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        assertEquals("COMPLETED", result.getStatus(), result::getErrorMessage);
        assertNotNull(result.getMetadata());
        assertEquals("servicing/pipelines/test_pipeline", result.getMetadata().get("compile_namespace"));
        verify(artifactRepo).saveAll(argThat(artifacts -> {
            var list = (List<GeneratedArtifact>) artifacts;
            return list.stream().anyMatch(a ->
                    "COMPILE_PLAN".equals(a.getFileType())
                            && a.getFilePath().startsWith("servicing/pipelines/test_pipeline/")
                            && a.getContent().contains("\"goldPublishBoundary\""));
        }));
    }

    @Test
    void generate_successfulRunCommitsGeneratedCodeToTenantRepo() {
        // Phase 2 §8a: after a successful generate() the service hands the run off to
        // GitCommitService. The call must use the tenantId and the persisted run's id.
        SubPipelineInstance inst = buildIngestionInstance("HR Files", "FileIngestion",
                Map.of("connector_name", "s3_source"));
        Blueprint bp = buildBlueprint("FileIngestion", BlueprintCategory.INGESTION);
        stubGenerate(List.of(inst), bp);

        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        assertEquals("COMPLETED", result.getStatus(), result::getErrorMessage);
        verify(gitCommitService).commitGeneratedCode("tenant-1", result.getId());
    }

    @Test
    void generate_failedRunDoesNotCommitToGit() {
        // §8a: git commit is skipped when the run ends in FAILED state — failed artifacts
        // must not be committed to the tenant repo. We set up only the stubs that execute
        // on the failure path (compilePlanService.build throws, which short-circuits the
        // rest of artifact generation) to avoid Mockito's strict-stubbing check.
        SubPipelineInstance inst = buildIngestionInstance("HR Files", "FileIngestion",
                Map.of("connector_name", "s3_source"));

        when(pipelineRepo.findById("pipeline-1")).thenReturn(Optional.of(testPipeline));
        when(versionRepo.findById("version-1")).thenReturn(Optional.of(testVersion));
        when(instanceRepo.findByVersionIdOrderByExecutionOrderAsc("version-1"))
                .thenReturn(List.of(inst));
        when(wiringRepo.findByVersionIdOrderByCreatedAtAsc("version-1")).thenReturn(List.of());
        when(runRepo.save(any(GenerationRun.class))).thenAnswer(inv -> {
            GenerationRun r = inv.getArgument(0);
            if (r.getId() == null) r.setId("run-1");
            return r;
        });
        when(compilePlanService.build(any(), eq("version-1"), anyList(), anyList()))
                .thenThrow(new RuntimeException("synthetic compile failure"));

        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        assertEquals("FAILED", result.getStatus());
        verify(gitCommitService, never()).commitGeneratedCode(anyString(), anyString());
    }

    @Test
    void generate_gitCommitFailureDoesNotBreakGeneration() {
        // §8a: the call is wrapped in try/catch so a git failure (e.g. tenant not onboarded,
        // IO error) does NOT fail the generation. The run stays COMPLETED.
        SubPipelineInstance inst = buildIngestionInstance("HR Files", "FileIngestion",
                Map.of("connector_name", "s3_source"));
        Blueprint bp = buildBlueprint("FileIngestion", BlueprintCategory.INGESTION);
        stubGenerate(List.of(inst), bp);
        doThrow(new RuntimeException("tenant not onboarded"))
                .when(gitCommitService).commitGeneratedCode(anyString(), anyString());

        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        assertEquals("COMPLETED", result.getStatus(), result::getErrorMessage);
        verify(gitCommitService).commitGeneratedCode("tenant-1", result.getId());
    }

    @Test
    void generateAirflowDag_databaseReadinessSensorBlueprint_generatesSqlSensorTask() {
        SubPipelineInstance sensor = buildTransformInstance("Orders Ready", "DatabaseReadinessSensor",
                Map.of(
                        "sql", "SELECT COUNT(*) FROM orders_ready WHERE ds = '{{ ds }}'",
                        "connection_id", "warehouse_conn",
                        "poke_interval_seconds", 120,
                        "timeout_seconds", 900
                ));
        sensor.setExecutionOrder(1);
        Blueprint bp = buildBlueprint("DatabaseReadinessSensor", BlueprintCategory.ORCHESTRATION);

        stubGenerate(List.of(sensor), bp);

        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        assertEquals("COMPLETED", result.getStatus(), result::getErrorMessage);
        verify(artifactRepo).saveAll(argThat(artifacts -> {
            var list = (List<GeneratedArtifact>) artifacts;
            return list.stream().anyMatch(a ->
                    "AIRFLOW_DAG".equals(a.getFileType())
                            && a.getContent().contains("from airflow.providers.common.sql.sensors.sql import SqlSensor")
                            && a.getContent().contains("orders_ready = SqlSensor(")
                            && a.getContent().contains("conn_id='warehouse_conn'")
                            && a.getContent().contains("SELECT COUNT(*) FROM orders_ready"));
        }));
    }

    @Test
    void generateAirflowDag_ingestionDatasetIds_resolveLegacySensingConfig() {
        SubPipelineInstance ingest = buildIngestionInstance("S3 Source", "FileIngestion",
                Map.of(
                        "dataset_ids", List.of("dataset-1"),
                        "connector_name", "s3_source"
                ));
        Blueprint bp = buildBlueprint("FileIngestion", BlueprintCategory.INGESTION);

        Dataset dataset = new Dataset();
        dataset.setId("dataset-1");
        dataset.setSensingStrategy("sql_query");
        dataset.setReadinessQuery("SELECT COUNT(*) FROM landing_files WHERE ds = '{{ ds }}'");

        stubGenerate(List.of(ingest), bp);
        when(datasetRepo.findById("dataset-1")).thenReturn(Optional.of(dataset));

        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        assertEquals("COMPLETED", result.getStatus());
        verify(artifactRepo).saveAll(argThat(artifacts -> {
            var list = (List<GeneratedArtifact>) artifacts;
            return list.stream().anyMatch(a ->
                    "AIRFLOW_DAG".equals(a.getFileType())
                            && a.getContent().contains("SqlSensor")
                            && a.getContent().contains("landing_files"));
        }));
    }

    @Test
    void generateRequirements_explicitSensors_includeProviderDependencies() {
        SubPipelineInstance sensor = buildTransformInstance("Orders Ready", "DatabaseReadinessSensor",
                Map.of("sql", "SELECT 1"));
        Blueprint bp = buildBlueprint("DatabaseReadinessSensor", BlueprintCategory.ORCHESTRATION);
        stubGenerate(List.of(sensor), bp);

        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        assertEquals("COMPLETED", result.getStatus());
        verify(artifactRepo).saveAll(argThat(artifacts -> {
            var list = (List<GeneratedArtifact>) artifacts;
            return list.stream().anyMatch(a ->
                    "REQUIREMENTS_TXT".equals(a.getFileType())
                            && a.getContent().contains("apache-airflow-providers-amazon")
                            && a.getContent().contains("apache-airflow-providers-sftp")
                            && a.getContent().contains("apache-airflow-providers-common-sql"));
        }));
    }

    @Test
    void generateDbtModels_reuseWrapper_emitsThinRefModel() {
        SubPipelineInstance model = buildTransformInstance("Current Employees", "WideDenormalizedMart",
                Map.of("business_concept", "employee"));
        model.setExecutionOrder(1);
        Blueprint bp = buildBlueprint("WideDenormalizedMart", BlueprintCategory.MODELING);
        bp.setSupportsReuse(true);

        stubGenerate(List.of(model), bp);
        when(compilePlanService.build(any(), eq("version-1"), anyList(), anyList()))
                .thenReturn(buildReuseCompilePlan(model.getId(), "employee_conformed"));

        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        assertEquals("COMPLETED", result.getStatus());
        verify(artifactRepo).saveAll(argThat(artifacts -> {
            var list = (List<GeneratedArtifact>) artifacts;
            return list.stream().anyMatch(a ->
                    "DBT_MODEL".equals(a.getFileType())
                            && a.getContent().contains("materialized='ephemeral'")
                            && a.getContent().contains("Reuse plan: wrap existing dbt asset employee_conformed")
                            && a.getContent().contains("Match score: 16")
                            && a.getContent().contains("WITH reused_asset AS")
                            && a.getContent().contains("ref('employee_conformed')"));
        }));
    }

    // -----------------------------------------------------------------------
    //  generatePySparkJobs tests
    // -----------------------------------------------------------------------

    @Test
    void generatePySparkJobs_snapshotIngestion_generatesJdbcCodeWithOracleDialect() {
        // Given
        SubPipelineInstance inst = buildIngestionInstance("Oracle Ingest", "SnapshotIngestion",
                Map.of("connector_instance_id", "ci-oracle-1", "connector_name", "oracle_source"));
        Blueprint bp = buildBlueprint("SnapshotIngestion", BlueprintCategory.INGESTION);

        ConnectorInstance oracleConnector = new ConnectorInstance();
        oracleConnector.setId("ci-oracle-1");
        oracleConnector.setName("Oracle LOS DB");

        CredentialProfile cred = buildCredentialProfile("ci-oracle-1", Map.of(
                "host", "oracle-host.db.com",
                "port", "1521",
                "sid", "LOSDB",
                "username", "dbuser",
                "password", "dbpass"));

        stubGenerate(List.of(inst), bp);
        when(credentialProfileRepo.findByConnectorInstanceIdAndEnvironment("ci-oracle-1", "dev"))
                .thenReturn(Optional.of(cred));
        when(connectorInstanceRepo.findById("ci-oracle-1"))
                .thenReturn(Optional.of(oracleConnector));

        // When
        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        // Then
        assertEquals("COMPLETED", result.getStatus());
        verify(artifactRepo).saveAll(argThat(artifacts -> {
            var list = (List<GeneratedArtifact>) artifacts;
            return list.stream().anyMatch(a ->
                    "PYSPARK_JOB".equals(a.getFileType())
                    && a.getContent().contains("jdbc:oracle:thin:@")
                    && a.getContent().contains("oracle.jdbc.OracleDriver")
                    && a.getContent().contains("LOSDB"));
        }));
    }

    @Test
    void generatePySparkJobs_fileIngestion_generatesObjectStorageCode() {
        // Per #80: object-storage connectors are identity-only at the chat layer.
        // PySpark codegen resolves the s3a:// path via PathConventionService at
        // codegen time using full pipeline context. PULSE-emitted code does NOT
        // set Hadoop S3 endpoint or credentials — those live in the cluster's
        // spark-defaults.conf (workload identity for GCP, Kerberos for DPC).
        SubPipelineInstance inst = buildIngestionInstance("S3 File Load", "FileIngestion",
                Map.of(
                        "connector_instance_id", "ci-s3-1",
                        "connector_name", "s3_source",
                        "file_format", "csv",
                        "header", "true",
                        "infer_schema", "true"
                ));
        Blueprint bp = buildBlueprint("FileIngestion", BlueprintCategory.INGESTION);

        com.pulse.sor.model.SystemOfRecord sor = new com.pulse.sor.model.SystemOfRecord();
        sor.setId("sor-msp-1");
        sor.setTenantId("tenant-1");
        sor.setName("MSP");
        sor.setDomainId("domain-1");

        ConnectorInstance s3Connector = new ConnectorInstance();
        s3Connector.setId("ci-s3-1");
        s3Connector.setSorId("sor-msp-1");
        s3Connector.setName("S3-compatible Object Storage");

        com.pulse.sor.model.Domain domain = new com.pulse.sor.model.Domain();
        domain.setId("domain-1");
        domain.setSlug("servicing");

        com.pulse.storage.model.StorageBackend sb = new com.pulse.storage.model.StorageBackend();
        sb.setTenantId("tenant-1");
        sb.setEnvironment("dev");
        sb.setBackend("DPC");
        sb.setStorageRootFiles("pulse-dpc-tenant-1-dev-files");
        sb.setStorageRootLake("pulse-dpc-tenant-1-dev-lake");
        sb.setDpcScheme("s3a");

        stubGenerate(List.of(inst), bp);
        when(connectorInstanceRepo.findById("ci-s3-1"))
                .thenReturn(Optional.of(s3Connector));
        when(sorRepo.findById("sor-msp-1")).thenReturn(Optional.of(sor));
        when(domainRepo.findById("domain-1")).thenReturn(Optional.of(domain));
        when(storageBackendRepo.findByTenantIdAndEnvironmentAndBackend("tenant-1", "dev", "DPC"))
                .thenReturn(Optional.of(sb));
        when(pathConventionService.filesPath(eq(sb), eq("servicing"), eq("msp"),
                anyString(), eq(com.pulse.storage.model.FileLifecycle.SRC)))
                .thenReturn("s3a://pulse-dpc-tenant-1-dev-files/servicing/msp/test_pipeline/SRC/");
        when(pathConventionService.tableLocation(eq(sb), eq(com.pulse.storage.model.LakeLayer.BRONZE),
                eq(com.pulse.storage.model.LakeFormat.DELTA), eq("servicing"), eq("msp"),
                anyString(), eq("s3-file-load")))
                .thenReturn(new com.pulse.storage.model.TableLocation.ObjectStorePath(
                        "s3a://pulse-dpc-tenant-1-dev-lake/servicing/msp/test-pipeline/bronze/s3-file-load/"));

        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        assertEquals("COMPLETED", result.getStatus());
        verify(artifactRepo).saveAll(argThat(artifacts -> {
            var list = (List<GeneratedArtifact>) artifacts;
            return list.stream().anyMatch(a ->
                    "PYSPARK_JOB".equals(a.getFileType())
                    // resolved s3a:// path appears
                    && a.getContent().contains("s3a://pulse-dpc-tenant-1-dev-files/servicing/msp/")
                    // CSV infer_schema=true uses PULSE guarded inference instead of Spark's
                    // weak inferSchema path, preserving leading-zero identifiers as strings.
                    && a.getContent().contains("def _pulse_infer_csv_schema")
                    && a.getContent().contains("leading_zero_integer")
                    && a.getContent().contains("schema = _pulse_infer_csv_schema(spark, source_path, has_header)")
                    && !a.getContent().contains(".option('inferSchema', 'true')")
                    && a.getContent().contains(
                            "output_path = 's3a://pulse-dpc-tenant-1-dev-lake/servicing/msp/test-pipeline/bronze/s3-file-load/'")
                    // Delta writes are location-first and then registered so reruns
                    // don't hit Delta's unsupported saveAsTable truncate path.
                    && a.getContent().contains(".save(output_path)")
                    && a.getContent().contains("CREATE TABLE {bronze_table} USING DELTA LOCATION")
                    && !a.getContent().contains(".saveAsTable(bronze_table)")
                    // NO inline AWS creds or Hadoop endpoint config — cluster handles it
                    && !a.getContent().contains("fs.s3a.access.key")
                    && !a.getContent().contains("aws_access_key"));
        }));
    }

    @Test
    void generatePySparkJobs_fileIngestion_generatesSftpCode() {
        // Given
        SubPipelineInstance inst = buildIngestionInstance("SFTP Load", "FileIngestion",
                Map.of("connector_instance_id", "ci-sftp-1", "connector_name", "sftp_source"));
        Blueprint bp = buildBlueprint("FileIngestion", BlueprintCategory.INGESTION);

        ConnectorInstance sftpConnector = new ConnectorInstance();
        sftpConnector.setId("ci-sftp-1");
        sftpConnector.setName("SFTP Server");

        CredentialProfile cred = buildCredentialProfile("ci-sftp-1", Map.of(
                "host", "sftp.example.com",
                "username", "sftpuser",
                "private_key", "-----BEGIN RSA PRIVATE KEY-----"));

        stubGenerate(List.of(inst), bp);
        when(credentialProfileRepo.findByConnectorInstanceIdAndEnvironment("ci-sftp-1", "dev"))
                .thenReturn(Optional.of(cred));
        when(connectorInstanceRepo.findById("ci-sftp-1"))
                .thenReturn(Optional.of(sftpConnector));

        // When
        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        // Then
        assertEquals("COMPLETED", result.getStatus());
        verify(artifactRepo).saveAll(argThat(artifacts -> {
            var list = (List<GeneratedArtifact>) artifacts;
            return list.stream().anyMatch(a ->
                    "PYSPARK_JOB".equals(a.getFileType())
                    && a.getContent().contains("paramiko")
                    && a.getContent().contains("sftp.example.com"));
        }));
    }

    // Per V99: GCS is no longer a separate connector type. The unified
    // "S3-compatible Object Storage" connector_definition covers it — a
    // GCP-backend storage_backend resolves to gs:// at runtime via the
    // cluster's Hadoop config, but PULSE-emitted code uses s3a:// paths
    // and lets the cluster handle scheme/endpoint translation. The
    // generatePySparkJobs_fileIngestion_generatesObjectStorageCode test
    // above covers the unified path; a separate GCS test is no longer
    // meaningful.

    @Test
    void generatePySparkJobs_streamIngestion_generatesKafkaCode() {
        // Given
        SubPipelineInstance inst = buildIngestionInstance("Kafka Ingest", "StreamIngestion",
                Map.of("connector_instance_id", "ci-kafka-1", "connector_name", "kafka_source"));
        Blueprint bp = buildBlueprint("StreamIngestion", BlueprintCategory.INGESTION);

        ConnectorInstance kafkaConnector = new ConnectorInstance();
        kafkaConnector.setId("ci-kafka-1");
        kafkaConnector.setName("Kafka Cluster");

        CredentialProfile cred = buildCredentialProfile("ci-kafka-1", Map.of(
                "bootstrap_servers", "kafka-broker:9092",
                "security_protocol", "PLAINTEXT"));

        stubGenerate(List.of(inst), bp);
        when(credentialProfileRepo.findByConnectorInstanceIdAndEnvironment("ci-kafka-1", "dev"))
                .thenReturn(Optional.of(cred));
        when(connectorInstanceRepo.findById("ci-kafka-1"))
                .thenReturn(Optional.of(kafkaConnector));

        // When
        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        // Then
        assertEquals("COMPLETED", result.getStatus());
        verify(artifactRepo).saveAll(argThat(artifacts -> {
            var list = (List<GeneratedArtifact>) artifacts;
            return list.stream().anyMatch(a ->
                    "PYSPARK_JOB".equals(a.getFileType())
                    && a.getContent().contains("readStream")
                    && a.getContent().contains("kafka")
                    && a.getContent().contains("kafka-broker:9092"));
        }));
    }

    @Test
    void generatePySparkJobs_apiIngestion_generatesRestApiCode() {
        // Given
        SubPipelineInstance inst = buildIngestionInstance("API Fetch", "ApiIngestion",
                Map.of("connector_instance_id", "ci-api-1", "connector_name", "api_source"));
        Blueprint bp = buildBlueprint("ApiIngestion", BlueprintCategory.INGESTION);

        ConnectorInstance apiConnector = new ConnectorInstance();
        apiConnector.setId("ci-api-1");
        apiConnector.setName("REST API");
        apiConnector.setConfigTemplate(Map.of("base_url", "https://api.example.com"));

        CredentialProfile cred = buildCredentialProfile("ci-api-1", Map.of(
                "api_key", "secret-api-key"));

        stubGenerate(List.of(inst), bp);
        when(credentialProfileRepo.findByConnectorInstanceIdAndEnvironment("ci-api-1", "dev"))
                .thenReturn(Optional.of(cred));
        when(connectorInstanceRepo.findById("ci-api-1"))
                .thenReturn(Optional.of(apiConnector));

        // When
        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        // Then
        assertEquals("COMPLETED", result.getStatus());
        verify(artifactRepo).saveAll(argThat(artifacts -> {
            var list = (List<GeneratedArtifact>) artifacts;
            return list.stream().anyMatch(a ->
                    "PYSPARK_JOB".equals(a.getFileType())
                    && a.getContent().contains("requests.get")
                    && a.getContent().contains("Bearer")
                    && a.getContent().contains("https://api.example.com"));
        }));
    }

    @Test
    void generatePySparkJobs_sourceSqlGeneratesJdbcQueryRead() {
        SubPipelineInstance inst = buildIngestionInstance("Loan Source SQL", "SourceSQL",
                Map.of(
                        "connector_instance_id", "ci-pg-source-1",
                        "connector_name", "postgres_source",
                        "source_query", "SELECT loan_id FROM public.loans WHERE as_of_date = [[ PBD ]]"));
        Blueprint bp = buildBlueprint("SourceSQL", BlueprintCategory.INGESTION);

        ConnectorInstance pgConnector = new ConnectorInstance();
        pgConnector.setId("ci-pg-source-1");
        pgConnector.setName("PostgreSQL Source");

        CredentialProfile cred = buildCredentialProfile("ci-pg-source-1", Map.of(
                "host", "pg-host",
                "port", "5432",
                "database", "lending",
                "username", "dbuser",
                "password", "dbpass"));

        stubGenerate(List.of(inst), bp);
        when(credentialProfileRepo.findByConnectorInstanceIdAndEnvironment("ci-pg-source-1", "dev"))
                .thenReturn(Optional.of(cred));
        when(connectorInstanceRepo.findById("ci-pg-source-1"))
                .thenReturn(Optional.of(pgConnector));

        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        assertEquals("COMPLETED", result.getStatus(), result::getErrorMessage);
        verify(artifactRepo).saveAll(argThat(artifacts -> {
            var list = (List<GeneratedArtifact>) artifacts;
            return list.stream().anyMatch(a ->
                    "PYSPARK_JOB".equals(a.getFileType())
                            && a.getContent().contains("jdbc:postgresql://pg-host:5432/lending")
                            && a.getContent().contains(".option('query',")
                            && a.getContent().contains("SELECT loan_id FROM public.loans")
                            && a.getContent().contains("pulse_resolve_mnemonic")
                            && !a.getContent().contains("[[ PBD ]]"));
        }));
    }

    // -----------------------------------------------------------------------
    //  generateSinkJobs tests
    // -----------------------------------------------------------------------

    @Test
    void generateSinkJobs_warehouseWriterWithSnowflake_generatesSnowflakeWriteCode() {
        // Given
        SubPipelineInstance inst = buildDestinationInstance("Snowflake Write", "WarehouseWriter",
                Map.of("connector_instance_id", "ci-sf-1", "connector_name", "snowflake_dest",
                        "target_table", "analytics.orders"));
        Blueprint bp = buildBlueprint("WarehouseWriter", BlueprintCategory.DESTINATION);

        ConnectorInstance sfConnector = new ConnectorInstance();
        sfConnector.setId("ci-sf-1");
        sfConnector.setName("Snowflake DWH");

        CredentialProfile cred = buildCredentialProfile("ci-sf-1", Map.of(
                "host", "account.snowflakecomputing.com",
                "username", "sfuser",
                "password", "sfpass",
                "database", "ANALYTICS",
                "schema", "PUBLIC",
                "warehouse", "COMPUTE_WH"));

        stubGenerate(List.of(inst), bp);
        when(credentialProfileRepo.findByConnectorInstanceIdAndEnvironment("ci-sf-1", "dev"))
                .thenReturn(Optional.of(cred));
        when(connectorInstanceRepo.findById("ci-sf-1"))
                .thenReturn(Optional.of(sfConnector));

        // When
        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        // Then
        assertEquals("COMPLETED", result.getStatus());
        verify(artifactRepo).saveAll(argThat(artifacts -> {
            var list = (List<GeneratedArtifact>) artifacts;
            return list.stream().anyMatch(a ->
                    "PYSPARK_JOB".equals(a.getFileType())
                    && a.getContent().contains("snowflake")
                    && a.getContent().contains("sfURL")
                    && a.getContent().contains("sfWarehouse")
                    && a.getContent().contains("COMPUTE_WH"));
        }));
    }

    @Test
    void generateSinkJobs_lakeWriter_generatesDeltaFormatWrite() {
        // Given
        SubPipelineInstance inst = buildDestinationInstance("Lake Write", "LakeWriter",
                Map.of("connector_name", "delta_lake", "output_path", "/data/lake/output"));
        Blueprint bp = buildBlueprint("LakeWriter", BlueprintCategory.DESTINATION);

        stubGenerate(List.of(inst), bp);

        // When
        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        // Then
        assertEquals("COMPLETED", result.getStatus());
        verify(artifactRepo).saveAll(argThat(artifacts -> {
            var list = (List<GeneratedArtifact>) artifacts;
            return list.stream().anyMatch(a ->
                    "PYSPARK_JOB".equals(a.getFileType())
                    && a.getContent().contains(".format('delta')"));
        }));
    }

    @Test
    void generateSinkJobs_streamWriter_generatesKafkaWrite() {
        // Given
        SubPipelineInstance inst = buildDestinationInstance("Kafka Write", "StreamWriter",
                Map.of("connector_name", "kafka_dest", "topic", "output-topic"));
        Blueprint bp = buildBlueprint("StreamWriter", BlueprintCategory.DESTINATION);

        stubGenerate(List.of(inst), bp);

        // When
        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        // Then
        assertEquals("COMPLETED", result.getStatus());
        verify(artifactRepo).saveAll(argThat(artifacts -> {
            var list = (List<GeneratedArtifact>) artifacts;
            return list.stream().anyMatch(a ->
                    "PYSPARK_JOB".equals(a.getFileType())
                    && a.getContent().contains("to_json")
                    && a.getContent().contains("kafka")
                    && a.getContent().contains("output-topic"));
        }));
    }

    @Test
    void generateSinkJobs_databaseWriter_generatesJdbcWrite() {
        // Given
        SubPipelineInstance inst = buildDestinationInstance("DB Write", "DatabaseWriter",
                Map.of("connector_instance_id", "ci-pg-1", "connector_name", "postgres_dest",
                        "target_table", "public.results"));
        Blueprint bp = buildBlueprint("DatabaseWriter", BlueprintCategory.DESTINATION);

        ConnectorInstance pgConnector = new ConnectorInstance();
        pgConnector.setId("ci-pg-1");
        pgConnector.setName("PostgreSQL Target");

        CredentialProfile cred = buildCredentialProfile("ci-pg-1", Map.of(
                "host", "pg-host", "port", "5432", "database", "mydb",
                "username", "pguser", "password", "pgpass"));

        stubGenerate(List.of(inst), bp);
        when(credentialProfileRepo.findByConnectorInstanceIdAndEnvironment("ci-pg-1", "dev"))
                .thenReturn(Optional.of(cred));
        when(connectorInstanceRepo.findById("ci-pg-1"))
                .thenReturn(Optional.of(pgConnector));

        // When
        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        // Then
        assertEquals("COMPLETED", result.getStatus());
        verify(artifactRepo).saveAll(argThat(artifacts -> {
            var list = (List<GeneratedArtifact>) artifacts;
            return list.stream().anyMatch(a ->
                    "PYSPARK_JOB".equals(a.getFileType())
                    && a.getContent().contains("jdbc")
                    && a.getContent().contains("org.postgresql.Driver")
                    && a.getContent().contains("pg-host")
                    && a.getContent().contains("public.results"));
        }));
    }

    // -----------------------------------------------------------------------
    //  generateDbtModels tests
    // -----------------------------------------------------------------------

    @Test
    void generateDbtModels_genericJoin_generatesProperSqlWithJoinKeys() {
        // Given
        SubPipelineInstance inst = buildTransformInstance("Join Customers Orders", "GenericJoin",
                Map.of(
                        "join_type", "inner",
                        "alias_left", "c",
                        "alias_right", "o",
                        "join_keys", List.of(
                                Map.of("left_column", "customer_id", "right_column", "cust_id")
                        ),
                        "select_columns", List.of("c.customer_id", "c.name", "o.order_total")));
        Blueprint bp = buildBlueprint("GenericJoin", BlueprintCategory.TRANSFORM);

        stubGenerate(List.of(inst), bp);
        when(wiringRepo.findByVersionIdAndTargetInstanceId(anyString(), anyString()))
                .thenReturn(List.of());

        // When
        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        // Then
        assertEquals("COMPLETED", result.getStatus());
        verify(artifactRepo).saveAll(argThat(artifacts -> {
            var list = (List<GeneratedArtifact>) artifacts;
            return list.stream().anyMatch(a ->
                    "DBT_MODEL".equals(a.getFileType())
                    && a.getContent().contains("INNER JOIN")
                    && a.getContent().contains("c.customer_id = o.cust_id")
                    && a.getContent().contains("c.customer_id")
                    && a.getContent().contains("c.name")
                    && a.getContent().contains("o.order_total"));
        }));
    }

    @Test
    void generateDbtModels_genericAggregate_generatesGroupBySql() {
        // Given
        SubPipelineInstance inst = buildTransformInstance("Aggregate Orders", "GenericAggregate",
                Map.of(
                        "group_by_columns", List.of("region", "product_category"),
                        "aggregations", List.of(
                                Map.of("column", "amount", "function", "SUM", "alias", "total_amount"),
                                Map.of("column", "order_id", "function", "COUNT", "alias", "order_count"))));
        Blueprint bp = buildBlueprint("GenericAggregate", BlueprintCategory.TRANSFORM);

        stubGenerate(List.of(inst), bp);
        when(wiringRepo.findByVersionIdAndTargetInstanceId(anyString(), anyString()))
                .thenReturn(List.of());

        // When
        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        // Then
        assertEquals("COMPLETED", result.getStatus());
        verify(artifactRepo).saveAll(argThat(artifacts -> {
            var list = (List<GeneratedArtifact>) artifacts;
            return list.stream().anyMatch(a ->
                    "DBT_MODEL".equals(a.getFileType())
                    && a.getContent().contains("GROUP BY region, product_category")
                    && a.getContent().contains("SUM(amount) AS total_amount")
                    && a.getContent().contains("COUNT(order_id) AS order_count"));
        }));
    }

    @Test
    void generateDbtModels_genericFilter_generatesWhereClauseFromVisualConditions() {
        // Given
        SubPipelineInstance inst = buildTransformInstance("Filter Active", "GenericFilter",
                Map.of(
                        "filter_mode", "visual",
                        "conditions", List.of(
                                Map.of("column", "status", "operator", "eq", "value", "ACTIVE"),
                                Map.of("column", "amount", "operator", "gt", "value", "100", "logic", "AND"))));
        Blueprint bp = buildBlueprint("GenericFilter", BlueprintCategory.TRANSFORM);

        stubGenerate(List.of(inst), bp);
        when(wiringRepo.findByVersionIdAndTargetInstanceId(anyString(), anyString()))
                .thenReturn(List.of());

        // When
        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        // Then
        assertEquals("COMPLETED", result.getStatus());
        verify(artifactRepo).saveAll(argThat(artifacts -> {
            var list = (List<GeneratedArtifact>) artifacts;
            return list.stream().anyMatch(a ->
                    "DBT_MODEL".equals(a.getFileType())
                    && a.getContent().contains("WHERE")
                    && a.getContent().contains("status = 'ACTIVE'")
                    && a.getContent().contains("amount > 100"));
        }));
    }

    @Test
    void generateDbtModels_genericFilter_withRawSqlMode() {
        // Given
        SubPipelineInstance inst = buildTransformInstance("Custom Filter", "GenericFilter",
                Map.of(
                        "filter_mode", "sql",
                        "raw_sql", "created_at >= '2024-01-01' AND deleted_at IS NULL"));
        Blueprint bp = buildBlueprint("GenericFilter", BlueprintCategory.TRANSFORM);

        stubGenerate(List.of(inst), bp);
        when(wiringRepo.findByVersionIdAndTargetInstanceId(anyString(), anyString()))
                .thenReturn(List.of());

        // When
        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        // Then
        assertEquals("COMPLETED", result.getStatus());
        verify(artifactRepo).saveAll(argThat(artifacts -> {
            var list = (List<GeneratedArtifact>) artifacts;
            return list.stream().anyMatch(a ->
                    "DBT_MODEL".equals(a.getFileType())
                    && a.getContent().contains("WHERE created_at >= '2024-01-01' AND deleted_at IS NULL"));
        }));
    }

    @Test
    void generateDbtModels_opListedTransformUsesCodegenOpEngineThroughDefaultGenerate() {
        SubPipelineInstance inst = buildTransformInstance("Active Loan Filter", "GenericFilter",
                Map.of("raw_sql", "status = 'ACTIVE'"));
        Blueprint bp = buildBlueprint("GenericFilter", BlueprintCategory.TRANSFORM);
        bp.setSchemaBehavior(Map.of(
                "version", 1,
                "ops", List.of(OpList.opEntryMap(
                        "filter-rows",
                        "Filter rows",
                        Map.of("raw_sql", Map.of("param", "raw_sql")))),
                "blueprint_params", List.of("storage_backend", "lake_layer", "lake_format"),
                "emission", Map.of("orchestration", "airflow", "compute", "dbt")
        ));
        bp.setParamsSchema(List.of(
                Map.of("name", "raw_sql", "type", "string", "tier", "user"),
                Map.of("name", "storage_backend", "type", "enum", "tier", "derived", "derivedFrom", "pipeline.storage"),
                Map.of("name", "lake_layer", "type", "enum", "tier", "derived", "derivedFrom", "pipeline.storage"),
                Map.of("name", "lake_format", "type", "enum", "tier", "derived", "derivedFrom", "pipeline.storage")
        ));

        stubGenerate(List.of(inst), bp);
        when(wiringRepo.findByVersionIdAndTargetInstanceId(anyString(), anyString()))
                .thenReturn(List.of());

        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        assertEquals("COMPLETED", result.getStatus(), result::getErrorMessage);
        assertTrue(((List<?>) result.getMetadata().get("codegenOpEngineInstances")).contains(inst.getId()));
        verify(codegenOpEngine, atLeastOnce()).dbtSql();
        verify(artifactRepo).saveAll(argThat(artifacts -> {
            var list = (List<GeneratedArtifact>) artifacts;
            return list.stream().anyMatch(a ->
                    "DBT_MODEL".equals(a.getFileType())
                            && "CodegenOpEngine".equals(a.getMetadata().get("codegenEngine"))
                            && a.getContent().contains("-- Codegen engine: CodegenOpEngine")
                            && a.getContent().contains("WITH step_1_filter_rows AS")
                            && a.getContent().contains("WHERE status = 'ACTIVE'"));
        }));
    }

    @Test
    void generateDbtModels_sqlModelStepsUseCodegenOpEngineThroughDefaultGenerate() {
        SubPipelineInstance inst = buildTransformInstance("Loan SQL Model", "SqlModel",
                Map.of("steps", List.of(
                        Map.of("name", "clean_loans",
                                "sql", "SELECT * FROM {{ ref('raw_loans') }} WHERE status IS NOT NULL"),
                        Map.of("name", "final_model",
                                "sql", "SELECT loan_id, status FROM clean_loans")
                )));
        Blueprint bp = buildBlueprint("SqlModel", BlueprintCategory.TRANSFORM);
        bp.setSchemaBehavior(Map.of(
                "version", 1,
                "ops", List.of(OpList.opEntryMap(
                        "sql-model",
                        "SQL model chain",
                        Map.of("steps", Map.of("param", "steps")))),
                "blueprint_params", List.of("declared_output_schema", "storage_backend", "lake_layer", "lake_format"),
                "emission", Map.of("orchestration", "airflow", "compute", "dbt")
        ));
        bp.setParamsSchema(List.of(
                Map.of("name", "steps", "type", "object[]", "tier", "user"),
                Map.of("name", "declared_output_schema", "type", "object[]", "tier", "user"),
                Map.of("name", "storage_backend", "type", "enum", "tier", "derived", "derivedFrom", "pipeline.storage"),
                Map.of("name", "lake_layer", "type", "enum", "tier", "derived", "derivedFrom", "pipeline.storage"),
                Map.of("name", "lake_format", "type", "enum", "tier", "derived", "derivedFrom", "pipeline.storage")
        ));

        stubGenerate(List.of(inst), bp);
        when(wiringRepo.findByVersionIdAndTargetInstanceId(anyString(), anyString()))
                .thenReturn(List.of());

        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        assertEquals("COMPLETED", result.getStatus(), result::getErrorMessage);
        assertTrue(((List<?>) result.getMetadata().get("codegenOpEngineInstances")).contains(inst.getId()));
        verify(artifactRepo).saveAll(argThat(artifacts -> {
            var list = (List<GeneratedArtifact>) artifacts;
            return list.stream().anyMatch(a ->
                    "DBT_MODEL".equals(a.getFileType())
                            && "CodegenOpEngine".equals(a.getMetadata().get("codegenEngine"))
                            && a.getContent().contains("-- Codegen engine: CodegenOpEngine")
                            && a.getContent().contains("WITH clean_loans AS")
                            && a.getContent().contains("SELECT loan_id, status FROM clean_loans")
                            && a.getContent().contains("FROM final_model"));
        }));
    }

    // -----------------------------------------------------------------------
    //  generateAirflowDag tests
    // -----------------------------------------------------------------------

    @Test
    void generateAirflowDag_ingestionTask_usesSparkSubmitOperator() {
        // Given
        SubPipelineInstance inst = buildIngestionInstance("Oracle Ingest", "SnapshotIngestion",
                Map.of("connector_name", "oracle_source"));
        Blueprint bp = buildBlueprint("SnapshotIngestion", BlueprintCategory.INGESTION);

        stubGenerate(List.of(inst), bp);

        // When
        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        // Then
        assertEquals("COMPLETED", result.getStatus());
        verify(artifactRepo).saveAll(argThat(artifacts -> {
            var list = (List<GeneratedArtifact>) artifacts;
            return list.stream().anyMatch(a ->
                    "AIRFLOW_DAG".equals(a.getFileType())
                    && a.getContent().contains("SparkSubmitOperator"));
        }));
    }

    @Test
    void generateAirflowDag_transformTask_usesBashOperatorWithDbt() {
        // Given
        SubPipelineInstance inst = buildTransformInstance("Transform Data", "GenericFilter",
                Map.of("filter_mode", "visual"));
        Blueprint bp = buildBlueprint("GenericFilter", BlueprintCategory.TRANSFORM);

        stubGenerate(List.of(inst), bp);
        when(wiringRepo.findByVersionIdAndTargetInstanceId(anyString(), anyString()))
                .thenReturn(List.of());

        // When
        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        // Then
        assertEquals("COMPLETED", result.getStatus());
        verify(artifactRepo).saveAll(argThat(artifacts -> {
            var list = (List<GeneratedArtifact>) artifacts;
            return list.stream().anyMatch(a ->
                    "AIRFLOW_DAG".equals(a.getFileType())
                    && a.getContent().contains("BashOperator")
                    && a.getContent().contains("dbt build"));
        }));
    }

    @Test
    void generateAirflowDag_destinationTask_usesSparkSubmitOperator() {
        // Given
        SubPipelineInstance inst = buildDestinationInstance("Load to DWH", "WarehouseWriter",
                Map.of("connector_name", "snowflake_dest"));
        Blueprint bp = buildBlueprint("WarehouseWriter", BlueprintCategory.DESTINATION);

        stubGenerate(List.of(inst), bp);

        // When
        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        // Then
        assertEquals("COMPLETED", result.getStatus());
        verify(artifactRepo).saveAll(argThat(artifacts -> {
            var list = (List<GeneratedArtifact>) artifacts;
            return list.stream().anyMatch(a ->
                    "AIRFLOW_DAG".equals(a.getFileType())
                    && a.getContent().contains("SparkSubmitOperator"));
        }));
    }

    @Test
    void generateAirflowDag_advanceTimeDimensionUsesStandaloneRuntimeStateHelper() {
        SubPipelineInstance inst = buildTransformInstance("Advance Loan As Of Date", "AdvanceTimeDimension",
                Map.ofEntries(
                        Map.entry("target_scope", "dataset"),
                        Map.entry("state_binding_ref", "time_state:dataset:dataset-loan-master"),
                        Map.entry("variable_key", "pulse.time_state.tenant_1.time_state_dataset_dataset_loan_master"),
                        Map.entry("calendar_binding_ref", "calendar:servicing"),
                        Map.entry("calendar_bundle_uri", "runtime/calendar/loan-master.json"),
                        Map.entry("calendar_bundle_hash", "sha256:test-bundle"),
                        Map.entry("calendar_id", "US-FED"),
                        Map.entry("advance_mode", "requested_asof"),
                        Map.entry("requested_asof_expr", "2026-04-30"),
                        Map.entry("initialization_policy", "require_existing"),
                        Map.entry("concurrency_policy", "serialized_airflow"),
                        Map.entry("evidence_prefix", "runtime-evidence/time-advances"),
                        Map.entry("advanced_by", "airflow:test"),
                        Map.entry("source", "semantic-proof"),
                        Map.entry("notes_template", "Advance after deterministic fixture processing")
                ));
        Blueprint bp = buildBlueprint("AdvanceTimeDimension", BlueprintCategory.ORCHESTRATION);

        stubGenerate(List.of(inst), bp);

        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        assertEquals("COMPLETED", result.getStatus());
        verify(artifactRepo).saveAll(argThat(artifacts -> {
            var list = (List<GeneratedArtifact>) artifacts;
            return list.stream().anyMatch(a ->
                    "AIRFLOW_DAG".equals(a.getFileType())
                            && a.getContent().contains("from pulse_airflow_runtime.time_state import AdvanceTimeDimensionOperator")
                            && a.getContent().contains("advance_loan_as_of_date = AdvanceTimeDimensionOperator(")
                            && a.getContent().contains("\"state_binding_ref\" : \"time_state:dataset:dataset-loan-master\"")
	                            && a.getContent().contains("\"variable_key\" : \"pulse.time_state.tenant_1.time_state_dataset_dataset_loan_master\"")
	                            && a.getContent().contains("\"calendar_bundle_uri\" : \"runtime/calendar/loan-master.json\"")
	                            && a.getContent().contains("\"calendar_bundle_hash\" : \"sha256:")
	                            && a.getContent().contains("\"requested_asof_expr\" : \"2026-04-30\"")
	                            && a.getContent().contains("\"advanced_by\" : \"airflow:test\"")
	                            && a.getContent().contains("pool='pulse_time_state_time_state_dataset_dataset_loan_master'")
	                            && !a.getContent().contains("PULSE_API_URL")
	                            && !a.getContent().contains("/api/v1/advance")
	                            && !a.getContent().contains("bash_command='echo \"Running advance_loan_as_of_date\"'"))
	                    && list.stream().anyMatch(a ->
	                    "CALENDAR_BUNDLE".equals(a.getFileType())
	                            && a.getFilePath().endsWith("runtime/calendar/loan-master.json")
	                            && a.getContent().contains("\"schemaVersion\" : \"pulse.calendar_bundle.v1\"")
	                            && a.getContent().contains("\"US-FED\"")
	                            && a.getContent().contains("2026-03-09")
	                            && !a.getContent().contains("2026-03-07"))
	                    && list.stream().anyMatch(a ->
	                    "DBT_MODEL".equals(a.getFileType())
	                            && a.getFilePath().endsWith("dbt_project/models/shared/date_dim.sql")
	                            && a.getContent().contains("alias='date_dim'")
	                            && a.getContent().contains("tenant_1")
	                            && a.getContent().contains("sequence(to_date('1900-01-01'), to_date('2100-12-31')"));
	        }));
	    }

    @Test
    void generateAirflowDag_remotePipelineInvocationCallsPeerAirflowWithoutPulseBrokerApi() {
        SubPipelineInstance inst = buildTransformInstance("Invoke DPC Pipeline", "RemotePipelineInvocation",
                Map.of(
                        "federated_tenant_key", "peer-a",
                        "remote_target_ref", "target-a",
                        "environment", "prod",
                        "airflow_connection_id", "dpc_peer_airflow",
                        "payload_template", Map.of("logical_date", "{{ ds }}")
                ));
        Blueprint bp = buildBlueprint("RemotePipelineInvocation", BlueprintCategory.ORCHESTRATION);
        RemoteTargetRuntimeMirror mirror = new RemoteTargetRuntimeMirror();
        mirror.setFederatedTenantKey("peer-a");
        mirror.setRemoteTargetRef("target-a");
        mirror.setEnvironment("prod");
        mirror.setPeerLogicalDagId("dpc_remote_pipeline");
        mirror.setPayload(Map.of("runtimeMetadata", Map.of("pollIntervalSeconds", 15, "timeoutSeconds", 1800)));
        when(remoteTargetRuntimeMirrorRepository.findFirstByFederatedTenantKeyAndRemoteTargetRefAndEnvironment(
                "peer-a", "target-a", "prod")).thenReturn(Optional.of(mirror));
        stubGenerate(List.of(inst), bp);

        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        assertEquals("COMPLETED", result.getStatus());
        verify(artifactRepo).saveAll(argThat(artifacts -> {
            var list = (List<GeneratedArtifact>) artifacts;
            boolean dagWired = list.stream().anyMatch(a ->
                    "AIRFLOW_DAG".equals(a.getFileType())
                            && a.getContent().contains("from pulse_remote_airflow_invoke import RemoteAirflowDagRunOperator")
                            && a.getContent().contains("invoke_dpc_pipeline = RemoteAirflowDagRunOperator(")
                            && a.getContent().contains("airflow_conn_id='dpc_peer_airflow'")
                            && a.getContent().contains("remote_dag_id='dpc_remote_pipeline'")
                            && !a.getContent().contains("PULSE_BROKER_API_URL")
                            && !a.getContent().contains("/api/v1/internal/broker"));
            boolean helperIsDirectAirflow = list.stream().anyMatch(a ->
                    "BROKER_OPERATOR".equals(a.getFileType())
                            && a.getFilePath().endsWith("airflow/plugins/pulse_remote_airflow_invoke.py")
                            && a.getContent().contains("BaseHook.get_connection")
                            && a.getContent().contains("/api/v1/dags/{self.remote_dag_id}/dagRuns")
                            && !a.getContent().contains("PULSE_BROKER_API_URL")
                            && !a.getContent().contains("/api/v1/internal/broker"));
            boolean manifestOnlyWhenBlueprintUsed = list.stream().anyMatch(a ->
                    "BROKER_INVOCATION_MANIFEST".equals(a.getFileType())
                            && a.getContent().contains("\"airflowConnectionId\" : \"dpc_peer_airflow\"")
                            && a.getContent().contains("\"remoteDagId\" : \"dpc_remote_pipeline\""));
            return dagWired && helperIsDirectAirflow && manifestOnlyWhenBlueprintUsed;
        }));
    }

    @Test
    void generate_runtimeArtifactsDoNotContainPulseBrokerOrPeerPulseTokens() {
        SubPipelineInstance remote = buildTransformInstance("Invoke DPC Pipeline", "RemotePipelineInvocation",
                Map.of(
                        "federated_tenant_key", "peer-a",
                        "remote_target_ref", "target-a",
                        "environment", "prod",
                        "airflow_connection_id", "dpc_peer_airflow",
                        "payload_template", Map.of("logical_date", "{{ ds }}")
                ));
        Blueprint bp = buildBlueprint("RemotePipelineInvocation", BlueprintCategory.ORCHESTRATION);
        RemoteTargetRuntimeMirror mirror = new RemoteTargetRuntimeMirror();
        mirror.setFederatedTenantKey("peer-a");
        mirror.setRemoteTargetRef("target-a");
        mirror.setEnvironment("prod");
        mirror.setPeerLogicalDagId("dpc_remote_pipeline");
        when(remoteTargetRuntimeMirrorRepository.findFirstByFederatedTenantKeyAndRemoteTargetRefAndEnvironment(
                "peer-a", "target-a", "prod")).thenReturn(Optional.of(mirror));
        stubGenerate(List.of(remote), bp);

        service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        verify(artifactRepo).saveAll(argThat(artifacts -> {
            var list = (List<GeneratedArtifact>) artifacts;
            List<String> forbidden = List.of(
                    "PULSE_AIRFLOW_CALLBACK_URL",
                    "/api/v1/callbacks/airflow",
                    "http://localhost:8080",
                    "on_success_callback",
                    "on_failure_callback",
                    "peer/v1",
                    "peer_pulse_url",
                    "peerPulseBaseUrl",
                    "/api/v1/internal/broker",
                    "PULSE_API_URL",
                    "PULSE_BROKER_API_URL",
                    "PULSE_BROKER_INTERNAL_TOKEN");
            return list.stream()
                    .filter(a -> Set.of("AIRFLOW_DAG", "BROKER_OPERATOR", "BROKER_INVOCATION_MANIFEST")
                            .contains(a.getFileType()))
                    .allMatch(a -> forbidden.stream().noneMatch(token -> a.getContent().contains(token)));
        }));
    }

    @Test
    void generateAirflowDag_taskDependencies_areChainedCorrectly() {
        // Given
        SubPipelineInstance inst1 = buildIngestionInstance("Ingest", "SnapshotIngestion",
                Map.of("connector_name", "source"));
        inst1.setExecutionOrder(1);
        SubPipelineInstance inst2 = buildTransformInstance("Transform", "GenericFilter",
                Map.of("filter_mode", "visual"));
        inst2.setExecutionOrder(2);
        SubPipelineInstance inst3 = buildDestinationInstance("Load", "WarehouseWriter",
                Map.of("connector_name", "dest"));
        inst3.setExecutionOrder(3);

        Blueprint bpIngest = buildBlueprint("SnapshotIngestion", BlueprintCategory.INGESTION);
        Blueprint bpTransform = buildBlueprint("GenericFilter", BlueprintCategory.TRANSFORM);
        Blueprint bpDest = buildBlueprint("WarehouseWriter", BlueprintCategory.DESTINATION);

        when(pipelineRepo.findById("pipeline-1")).thenReturn(Optional.of(testPipeline));
        when(versionRepo.findById("version-1")).thenReturn(Optional.of(testVersion));
        when(instanceRepo.findByVersionIdOrderByExecutionOrderAsc("version-1"))
                .thenReturn(List.of(inst1, inst2, inst3));
        when(runRepo.save(any(GenerationRun.class))).thenAnswer(inv -> {
            GenerationRun r = inv.getArgument(0);
            if (r.getId() == null) r.setId("run-1");
            return r;
        });
        when(runRepo.findTopByVersionIdOrderByCreatedAtDesc("version-1"))
                .thenReturn(Optional.empty());
        when(blueprintRepo.findByBlueprintKey("SnapshotIngestion")).thenReturn(Optional.of(bpIngest));
        when(blueprintRepo.findByBlueprintKey("GenericFilter")).thenReturn(Optional.of(bpTransform));
        when(blueprintRepo.findByBlueprintKey("WarehouseWriter")).thenReturn(Optional.of(bpDest));
        when(wiringRepo.findByVersionIdAndTargetInstanceId(anyString(), anyString()))
                .thenReturn(List.of());

        // When
        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        // Then
        assertEquals("COMPLETED", result.getStatus());
        verify(artifactRepo).saveAll(argThat(artifacts -> {
            var list = (List<GeneratedArtifact>) artifacts;
            return list.stream().anyMatch(a ->
                    "AIRFLOW_DAG".equals(a.getFileType())
                    && a.getContent().contains("task_id='ingest'")
                    && a.getContent().contains("task_id='transform'")
                    && a.getContent().contains("task_id='load'"));
        }));
    }

    // -----------------------------------------------------------------------
    //  Vault reference resolution tests
    // -----------------------------------------------------------------------

    @Test
    void vaultReferenceResolution_vaultRefsBecome_osEnvironLookups() {
        // Given
        SubPipelineInstance inst = buildIngestionInstance("Vault Test", "SnapshotIngestion",
                Map.of("connector_instance_id", "ci-vault-1", "connector_name", "oracle"));
        Blueprint bp = buildBlueprint("SnapshotIngestion", BlueprintCategory.INGESTION);

        ConnectorInstance oracleConn = new ConnectorInstance();
        oracleConn.setId("ci-vault-1");
        oracleConn.setName("Oracle Prod");

        CredentialProfile cred = buildCredentialProfile("ci-vault-1", Map.of(
                "host", "oracle-host.com",
                "port", "1521",
                "sid", "ORCL",
                "username", "vault://pulse/dev/oracle/username",
                "password", "vault://pulse/dev/oracle/password"));

        stubGenerate(List.of(inst), bp);
        when(credentialProfileRepo.findByConnectorInstanceIdAndEnvironment("ci-vault-1", "dev"))
                .thenReturn(Optional.of(cred));
        when(connectorInstanceRepo.findById("ci-vault-1"))
                .thenReturn(Optional.of(oracleConn));

        // When
        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        // Then
        assertEquals("COMPLETED", result.getStatus());
        verify(artifactRepo).saveAll(argThat(artifacts -> {
            var list = (List<GeneratedArtifact>) artifacts;
            return list.stream().anyMatch(a ->
                    "PYSPARK_JOB".equals(a.getFileType())
                    && a.getContent().contains("import os")
                    && a.getContent().contains("os.environ['ORACLE_USERNAME']")
                    && a.getContent().contains("os.environ['ORACLE_PASSWORD']"));
        }));
    }

    @Test
    void secretReferenceResolution_gcpSecretManagerRefsBecome_osEnvironLookups() {
        SubPipelineInstance inst = buildIngestionInstance("GCP Secret Test", "ApiIngestion",
                Map.of("connector_instance_id", "ci-api-1", "connector_name", "rest_api"));
        Blueprint bp = buildBlueprint("ApiIngestion", BlueprintCategory.INGESTION);

        ConnectorInstance apiConn = new ConnectorInstance();
        apiConn.setId("ci-api-1");
        apiConn.setName("Partner API");

        CredentialProfile cred = buildCredentialProfile("ci-api-1", Map.of(
                "url_base", "https://api.example.com",
                "api_key", "gcp-sm://projects/pulse-dev/secrets/partner-api-key/versions/latest"));

        stubGenerate(List.of(inst), bp);
        when(credentialProfileRepo.findByConnectorInstanceIdAndEnvironment("ci-api-1", "dev"))
                .thenReturn(Optional.of(cred));
        when(connectorInstanceRepo.findById("ci-api-1"))
                .thenReturn(Optional.of(apiConn));

        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        assertEquals("COMPLETED", result.getStatus());
        verify(artifactRepo).saveAll(argThat(artifacts -> {
            var list = (List<GeneratedArtifact>) artifacts;
            return list.stream().anyMatch(a ->
                    "PYSPARK_JOB".equals(a.getFileType())
                    && a.getContent().contains("import os")
                    && a.getContent().contains("os.environ['PARTNER_API_KEY']"));
        }));
    }

    @Test
    void secretBearingSparkTasks_useRuntimeResolverArtifactsAndPythonOperatorPath() {
        SubPipelineInstance inst = buildIngestionInstance("Partner API Sync", "ApiIngestion",
                Map.of("connector_instance_id", "ci-api-2", "connector_name", "rest_api"));
        Blueprint bp = buildBlueprint("ApiIngestion", BlueprintCategory.INGESTION);

        ConnectorInstance apiConn = new ConnectorInstance();
        apiConn.setId("ci-api-2");
        apiConn.setName("Partner API");

        CredentialProfile cred = buildCredentialProfile("ci-api-2", Map.of(
                "url_base", "https://api.example.com",
                "api_key", "gcp-sm://projects/pulse-dev/secrets/partner-api-key/versions/latest"));

        stubGenerate(List.of(inst), bp);
        when(credentialProfileRepo.findByConnectorInstanceIdAndEnvironment("ci-api-2", "dev"))
                .thenReturn(Optional.of(cred));
        when(connectorInstanceRepo.findById("ci-api-2"))
                .thenReturn(Optional.of(apiConn));

        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        assertEquals("COMPLETED", result.getStatus());
        verify(artifactRepo).saveAll(argThat(artifacts -> {
            var list = (List<GeneratedArtifact>) artifacts;
            return list.stream().anyMatch(a ->
                    "AIRFLOW_DAG".equals(a.getFileType())
                            && a.getContent().contains("from runtime.pulse_secret_resolver import cleanup_runtime_secret_files, resolve_runtime_secret_env")
                            && a.getContent().contains("partner_api_sync = PythonOperator(")
                            && a.getContent().contains("python_callable=pulse_run_spark_job")
                            && a.getContent().contains("gcp-sm://projects/pulse-dev/secrets/partner-api-key/versions/active"))
                    && list.stream().anyMatch(a ->
                    "RUNTIME_SUPPORT".equals(a.getFileType())
                            && a.getFilePath().endsWith("runtime/pulse_secret_resolver.py")
                            && a.getContent().contains("def resolve_runtime_secret_env(secret_bindings"))
                    && list.stream().anyMatch(a ->
                    "RUNTIME_SECRET_MANIFEST".equals(a.getFileType())
                            && a.getFilePath().endsWith("config/secret-manifest.json")
                            && a.getContent().contains("\"envVarName\" : \"PARTNER_API_KEY\""));
        }));
    }

    @Test
    void canonicalCredentialProfiles_driveRuntimeBindingGenerationWithoutFlattenedStorage() {
        SubPipelineInstance inst = buildIngestionInstance("Canonical Secret Test", "SnapshotIngestion",
                Map.of("connector_instance_id", "ci-canonical-1", "connector_name", "oracle"));
        Blueprint bp = buildBlueprint("SnapshotIngestion", BlueprintCategory.INGESTION);

        ConnectorInstance oracleConn = new ConnectorInstance();
        oracleConn.setId("ci-canonical-1");
        oracleConn.setName("Oracle Canonical");

        CredentialProfile cred = new CredentialProfile();
        cred.setConnectorInstanceId("ci-canonical-1");
        cred.setEnvironment("DEV");
        cred.setConnectionConfig(Map.of(
                CredentialProfile.CANONICAL_METADATA_KEY, Map.of(
                        "host", "oracle-host.com",
                        "port", "1521",
                        "sid", "ORCL"
                ),
                CredentialProfile.CANONICAL_SECRET_REFS_KEY, Map.of(
                        "username", "vault://pulse/dev/oracle/username",
                        "password", "vault://pulse/dev/oracle/password"
                )
        ));

        stubGenerate(List.of(inst), bp);
        when(credentialProfileRepo.findByConnectorInstanceIdAndEnvironment("ci-canonical-1", "dev"))
                .thenReturn(Optional.of(cred));
        when(connectorInstanceRepo.findById("ci-canonical-1"))
                .thenReturn(Optional.of(oracleConn));

        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        assertEquals("COMPLETED", result.getStatus());
        verify(artifactRepo).saveAll(argThat(artifacts -> {
            var list = (List<GeneratedArtifact>) artifacts;
            return list.stream().anyMatch(a ->
                    "PYSPARK_JOB".equals(a.getFileType())
                            && a.getContent().contains("jdbc:oracle:thin:@oracle-host.com:1521:ORCL")
                            && a.getContent().contains("os.environ['ORACLE_USERNAME']")
                            && a.getContent().contains("os.environ['ORACLE_PASSWORD']"))
                    && list.stream().anyMatch(a ->
                    "RUNTIME_SECRET_MANIFEST".equals(a.getFileType())
                            && a.getContent().contains("\"fieldName\" : \"username\"")
                            && a.getContent().contains("\"envVarName\" : \"ORACLE_PASSWORD\""));
        }));
    }

    @Test
    void sftpPrivateKeySecretRefs_areMaterializedAsFilePaths() {
        SubPipelineInstance inst = buildIngestionInstance("Secure SFTP", "FileIngestion",
                Map.of("connector_instance_id", "ci-sftp-secure", "connector_name", "sftp_source"));
        Blueprint bp = buildBlueprint("FileIngestion", BlueprintCategory.INGESTION);

        ConnectorInstance sftpConn = new ConnectorInstance();
        sftpConn.setId("ci-sftp-secure");
        sftpConn.setName("Vendor SFTP");

        CredentialProfile cred = buildCredentialProfile("ci-sftp-secure", Map.of(
                "host", "sftp.vendor.example",
                "username", "vault://pulse/dev/vendor-sftp/username",
                "private_key", "vault://pulse/dev/vendor-sftp/private-key"));

        stubGenerate(List.of(inst), bp);
        when(credentialProfileRepo.findByConnectorInstanceIdAndEnvironment("ci-sftp-secure", "dev"))
                .thenReturn(Optional.of(cred));
        when(connectorInstanceRepo.findById("ci-sftp-secure"))
                .thenReturn(Optional.of(sftpConn));

        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        assertEquals("COMPLETED", result.getStatus());
        verify(artifactRepo).saveAll(argThat(artifacts -> {
            var list = (List<GeneratedArtifact>) artifacts;
            return list.stream().anyMatch(a ->
                    "PYSPARK_JOB".equals(a.getFileType())
                            && a.getContent().contains("sftp_key_path = os.environ['VENDOR_SFTP_PRIVATE_KEY_FILE']")
                            && a.getContent().contains("paramiko.RSAKey.from_private_key_file(sftp_key_path)"));
        }));
    }

    @Test
    void credentialResolution_followsConnectorInstanceIdChain() {
        // Given
        SubPipelineInstance inst = buildIngestionInstance("Chain Test", "SnapshotIngestion",
                Map.of("connector_instance_id", "ci-chain-1", "connector_name", "postgres_source"));
        Blueprint bp = buildBlueprint("SnapshotIngestion", BlueprintCategory.INGESTION);

        ConnectorInstance connector = new ConnectorInstance();
        connector.setId("ci-chain-1");
        connector.setName("PostgreSQL Source");

        CredentialProfile cred = buildCredentialProfile("ci-chain-1", Map.of(
                "host", "pg-host.db.com",
                "port", "5432",
                "database", "sourcedb",
                "username", "pguser",
                "password", "pgpass"));

        stubGenerate(List.of(inst), bp);
        when(credentialProfileRepo.findByConnectorInstanceIdAndEnvironment("ci-chain-1", "dev"))
                .thenReturn(Optional.of(cred));
        when(connectorInstanceRepo.findById("ci-chain-1"))
                .thenReturn(Optional.of(connector));

        // When
        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        // Then
        assertEquals("COMPLETED", result.getStatus());
        // Verify the chain: connector_instance_id -> CredentialProfile -> connectionConfig used in code
        verify(credentialProfileRepo, atLeastOnce()).findByConnectorInstanceIdAndEnvironment("ci-chain-1", "dev");
        verify(connectorInstanceRepo, atLeastOnce()).findById("ci-chain-1");
        verify(artifactRepo).saveAll(argThat(artifacts -> {
            var list = (List<GeneratedArtifact>) artifacts;
            return list.stream().anyMatch(a ->
                    "PYSPARK_JOB".equals(a.getFileType())
                    && a.getContent().contains("jdbc:postgresql://pg-host.db.com:5432/sourcedb")
                    && a.getContent().contains("org.postgresql.Driver"));
        }));
    }

    // -----------------------------------------------------------------------
    //  v2 redesign: dbt path, materialization, GX, time-dimension tests
    // -----------------------------------------------------------------------

    @Test
    void generateDbtModels_stagingBlueprint_emitsCorrectPath() {
        SubPipelineInstance ingest = buildIngestionInstance("Raw Employees", "FileIngestion",
                Map.of("connector_instance_id", "ci-src-1", "connector_name", "hr_oracle"));
        SubPipelineInstance clean = buildTransformInstance("Clean Employees", "BronzeToSilverCleaning", Map.of());
        clean.setExecutionOrder(2);

        ConnectorInstance srcCi = new ConnectorInstance();
        srcCi.setId("ci-src-1");
        srcCi.setName("hr_oracle");
        srcCi.setSorId("sor-hr");

        com.pulse.sor.model.SystemOfRecord sor = new com.pulse.sor.model.SystemOfRecord();
        sor.setId("sor-hr");
        sor.setTenantId("tenant-1");
        sor.setName("HR Oracle");
        sor.setDomainId("domain-1");

        com.pulse.sor.model.Domain domain = new com.pulse.sor.model.Domain();
        domain.setId("domain-1");
        domain.setSlug("servicing");

        com.pulse.storage.model.StorageBackend sb = new com.pulse.storage.model.StorageBackend();
        sb.setTenantId("tenant-1");
        sb.setEnvironment("dev");
        sb.setBackend("DPC");
        sb.setStorageRootFiles("pulse-dpc-tenant-1-dev-files");
        sb.setStorageRootLake("pulse-dpc-tenant-1-dev-lake");
        sb.setDpcScheme("s3a");

        PortWiring wire = new PortWiring();
        wire.setVersionId("version-1");
        wire.setSourceInstanceId(ingest.getId());
        wire.setTargetInstanceId(clean.getId());
        wire.setSourcePortName("raw_output");
        wire.setTargetPortName("data_input");

        stubGenerate(List.of(ingest, clean), buildBlueprint("FileIngestion", BlueprintCategory.INGESTION));
        when(blueprintRepo.findByBlueprintKey("BronzeToSilverCleaning"))
                .thenReturn(Optional.of(buildBlueprint("BronzeToSilverCleaning", BlueprintCategory.TRANSFORM)));
        when(connectorInstanceRepo.findById("ci-src-1")).thenReturn(Optional.of(srcCi));
        when(instanceRepo.findById(ingest.getId())).thenReturn(Optional.of(ingest));
        when(sorRepo.findById("sor-hr")).thenReturn(Optional.of(sor));
        when(domainRepo.findById("domain-1")).thenReturn(Optional.of(domain));
        when(storageBackendRepo.findByTenantIdAndEnvironmentAndBackend("tenant-1", "dev", "DPC"))
                .thenReturn(Optional.of(sb));
        when(pathConventionService.tableLocation(eq(sb), eq(com.pulse.storage.model.LakeLayer.BRONZE),
                eq(com.pulse.storage.model.LakeFormat.DELTA), eq("servicing"), eq("hr-oracle"),
                anyString(), eq("raw-employees")))
                .thenReturn(new com.pulse.storage.model.TableLocation.ObjectStorePath(
                        "s3a://pulse-dpc-tenant-1-dev-lake/servicing/hr-oracle/test-pipeline/bronze/raw-employees/"));
        when(pathConventionService.lakeLayerRoot(eq(sb), eq(com.pulse.storage.model.LakeLayer.SILVER),
                eq("servicing"), eq("hr-oracle"), anyString()))
                .thenReturn("s3a://pulse-dpc-tenant-1-dev-lake/servicing/hr-oracle/test-pipeline/silver");
        when(wiringRepo.findByVersionIdAndTargetInstanceId("version-1", clean.getId()))
                .thenReturn(List.of(wire));

        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");
        assertEquals("COMPLETED", result.getStatus());
        verify(artifactRepo).saveAll(argThat(artifacts -> {
            var list = (List<GeneratedArtifact>) artifacts;
            return list.stream().anyMatch(a ->
                    "DBT_MODEL".equals(a.getFileType())
                            && a.getFilePath().equals("dbt_project/models/staging/hr_oracle/stg__hr_oracle__clean_employees.sql")
                            && a.getContent().contains("materialized='pulse_delta_table'")
                            && a.getContent().contains("location_root='s3a://pulse-dpc-tenant-1-dev-lake/servicing/hr-oracle/test-pipeline/silver'")
                            && a.getContent().contains("pre_hook=[")
                            && !a.getContent().contains("DROP TABLE IF EXISTS {{ this }}")
                            && a.getContent().contains("CREATE SCHEMA IF NOT EXISTS bronze_hr_oracle")
                            && a.getContent().contains("CREATE TABLE IF NOT EXISTS bronze_hr_oracle.raw_employees USING DELTA LOCATION 's3a://pulse-dpc-tenant-1-dev-lake/servicing/hr-oracle/test-pipeline/bronze/raw-employees/'"));
        }));
    }

    @Test
    void generateDbtModels_martsBlueprint_emitsCorrectPath() {
        SubPipelineInstance fact = buildTransformInstance("Headcount", "FactBuild", Map.of());
        Blueprint bp = buildBlueprint("FactBuild", BlueprintCategory.MODELING);
        stubGenerate(List.of(fact), bp);

        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");
        assertEquals("COMPLETED", result.getStatus());
        verify(artifactRepo).saveAll(argThat(artifacts -> {
            var list = (List<GeneratedArtifact>) artifacts;
            return list.stream().anyMatch(a ->
                    "DBT_MODEL".equals(a.getFileType())
                            && a.getFilePath().equals("dbt_project/models/marts/servicing/fct__headcount.sql"));
        }));
    }

    @Test
    void generateDbtModels_factBuild_emitsConfiguredFactProjection() {
        SubPipelineInstance fact = buildTransformInstance("Loan Fact", "FactBuild", Map.of(
                "grain", List.of("loan_id"),
                "dimension_keys", List.of("loan_status", "property_state"),
                "measures", List.of("current_upb", "interest_rate")
        ));
        Blueprint bp = buildBlueprint("FactBuild", BlueprintCategory.MODELING);
        stubGenerate(List.of(fact), bp);

        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        assertEquals("COMPLETED", result.getStatus());
        verify(artifactRepo).saveAll(argThat(artifacts -> {
            var list = (List<GeneratedArtifact>) artifacts;
            return list.stream().anyMatch(a ->
                    "DBT_MODEL".equals(a.getFileType())
                            && a.getFilePath().equals("dbt_project/models/marts/servicing/fct__loan_fact.sql")
                            && a.getContent().contains("`loan_id`,\n    `loan_status`,\n    `property_state`,\n    `current_upb`,\n    `interest_rate`")
                            && a.getContent().contains("FROM {{ source('bronze_test_pipeline', 'test_pipeline_input') }}"));
        }));
    }

    @Test
    void generateDbtModels_wideDenormalizedMart_emitsFactDimensionJoin() {
        SubPipelineInstance mart = buildTransformInstance("Wide Loan Mart", "WideDenormalizedMart", Map.of(
                "dimension_joins", List.of(Map.of("join_key", "loan_id")),
                "fact_columns", List.of("loan_id", "current_upb", "interest_rate"),
                "dimension_columns", List.of("loan_status", "property_state", "borrower_credit_score")
        ));
        Blueprint bp = buildBlueprint("WideDenormalizedMart", BlueprintCategory.MODELING);
        stubGenerate(List.of(mart), bp);

        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        assertEquals("COMPLETED", result.getStatus());
        verify(artifactRepo).saveAll(argThat(artifacts -> {
            var list = (List<GeneratedArtifact>) artifacts;
            return list.stream().anyMatch(a ->
                    "DBT_MODEL".equals(a.getFileType())
                            && a.getFilePath().equals("dbt_project/models/marts/servicing/mart__wide_loan_mart.sql")
                            && a.getContent().contains("FROM {{ source('bronze_test_pipeline', 'test_pipeline_input') }} AS f")
                            && a.getContent().contains("LEFT JOIN {{ source('bronze_test_pipeline', 'test_pipeline_input') }} AS d")
                            && a.getContent().contains("ON f.`loan_id` = d.`loan_id`")
                            && a.getContent().contains("d.`borrower_credit_score` AS `borrower_credit_score`"));
        }));
    }

    @Test
    void generateDbtModels_incrementalMerge_usesWiredSourceAndMergeConfig() {
        SubPipelineInstance merge = buildTransformInstance("Merge Loan Updates", "IncrementalMerge", Map.of(
                "unique_key", List.of("loan_id"),
                "watermark_column", "_pulse_processed_at",
                "lake_layer", "gold"
        ));
        Blueprint bp = buildBlueprint("IncrementalMerge", BlueprintCategory.MODELING);
        stubGenerate(List.of(merge), bp);

        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        assertEquals("COMPLETED", result.getStatus(), result::getErrorMessage);
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<GeneratedArtifact>> captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(artifactRepo).saveAll(captor.capture());
        GeneratedArtifact incrementalModel = captor.getValue().stream()
                .filter(a -> "DBT_MODEL".equals(a.getFileType()))
                .filter(a -> a.getFilePath().endsWith("incr__merge_loan_updates.sql"))
                .findFirst()
                .orElseThrow();
        assertEquals("dbt_project/models/marts/servicing/incr__merge_loan_updates.sql", incrementalModel.getFilePath());
        assertTrue(incrementalModel.getContent().contains("materialized='incremental'")
                || incrementalModel.getContent().contains("materialized='pulse_delta_incremental_merge'"));
        assertTrue(incrementalModel.getContent().contains("incremental_strategy='merge'"));
        assertTrue(incrementalModel.getContent().contains("unique_key=['loan_id']"));
        assertTrue(incrementalModel.getContent().contains("file_format='delta'"));
        assertFalse(incrementalModel.getContent().contains("partition_by="));
        assertTrue(incrementalModel.getContent().contains("FROM {{ source('bronze_test_pipeline', 'test_pipeline_input') }}"));
        assertTrue(incrementalModel.getContent().contains("current_timestamp() > (SELECT COALESCE(MAX(_pulse_processed_at), '1970-01-01') FROM {{ this }})"));
    }

    @Test
    void generateDbtSnapshots_scd2Dimension_declaresSnapshotCompatibleFileFormat() {
        SubPipelineInstance scd2 = buildTransformInstance("Loan SCD2 Dimension", "SCD2Dimension", Map.of(
                "unique_key", List.of("loan_id"),
                "strategy", "check",
                "check_cols", List.of("current_upb", "loan_status"),
                "lake_format", "delta"
        ));
        Blueprint bp = buildBlueprint("SCD2Dimension", BlueprintCategory.MODELING);
        stubGenerate(List.of(scd2), bp);

        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        assertEquals("COMPLETED", result.getStatus());
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<GeneratedArtifact>> captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(artifactRepo).saveAll(captor.capture());
        GeneratedArtifact snapshot = captor.getValue().stream()
                .filter(a -> "DBT_SNAPSHOT".equals(a.getFileType()))
                .filter(a -> a.getFilePath().endsWith("snp__loan_scd2_dimension.sql"))
                .findFirst()
                .orElseThrow();
        assertTrue(snapshot.getContent().contains("file_format='delta'"));
        assertTrue(snapshot.getContent().contains("unique_key='loan_id'"));
        assertTrue(snapshot.getContent().contains("check_cols=['current_upb', 'loan_status']"));
        assertFalse(snapshot.getContent().contains("file_format='parquet'"));
    }

    @Test
    void generateDbtModels_snapshotModel_isPointInTimeIncrementalModel() {
        SubPipelineInstance snapshotModel = buildTransformInstance("Loan State Snapshot", "SnapshotModel", Map.of(
                "unique_key", List.of("loan_id"),
                "snapshot_partition_column", "ds",
                "source_date_column", "business_effective_ts",
                "lake_format", "delta"
        ));
        Blueprint bp = buildBlueprint("SnapshotModel", BlueprintCategory.MODELING);
        stubGenerate(List.of(snapshotModel), bp);

        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        assertEquals("COMPLETED", result.getStatus(), result::getErrorMessage);
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<GeneratedArtifact>> captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(artifactRepo).saveAll(captor.capture());
        GeneratedArtifact model = captor.getValue().stream()
                .filter(a -> "DBT_MODEL".equals(a.getFileType()))
                .filter(a -> a.getFilePath().endsWith("snp__loan_state_snapshot.sql"))
                .findFirst()
                .orElseThrow();
        assertEquals("dbt_project/models/marts/servicing/snp__loan_state_snapshot.sql", model.getFilePath());
        assertTrue(model.getContent().contains("materialized='incremental'"));
        assertTrue(model.getContent().contains("partition_by=['ds']"));
        assertTrue(model.getContent().contains("unique_key=['loan_id', 'ds']"));
        assertTrue(model.getContent().contains("incremental_strategy='merge'"));
        assertTrue(model.getContent().contains("FROM {{ source('bronze_test_pipeline', 'test_pipeline_input') }}"));
        assertTrue(model.getContent().contains("CAST(`business_effective_ts` AS DATE) <= DATE('{{ var(\"pulse_business_date\") }}')"));
        assertTrue(model.getContent().contains("DATE('{{ var(\"pulse_business_date\") }}') AS `ds`"));
        assertFalse(model.getContent().contains("{% snapshot"));
        assertFalse(model.getContent().contains("{% endsnapshot %}"));
    }

    @Test
    void generateDbtSnapshots_scd2DimensionWithLakeRoot_emitsSnapshotLocationRoot() {
        SubPipelineInstance ingest = buildIngestionInstance("Ingest Loan Master", "FileIngestion",
                Map.of("connector_instance_id", "ci-src-1", "connector_name", "loan_drops"));
        SubPipelineInstance scd2 = buildTransformInstance("Build Loan SCD2 Dimension", "SCD2Dimension", Map.of(
                "unique_key", List.of("loan_id"),
                "strategy", "timestamp",
                "updated_at_column", "last_payment_date",
                "lake_layer", "gold",
                "lake_format", "delta"
        ));

        ConnectorInstance srcCi = new ConnectorInstance();
        srcCi.setId("ci-src-1");
        srcCi.setName("loan_drops");
        srcCi.setSorId("sor-loan");

        com.pulse.sor.model.SystemOfRecord sor = new com.pulse.sor.model.SystemOfRecord();
        sor.setId("sor-loan");
        sor.setTenantId("tenant-1");
        sor.setName("Loan Source");
        sor.setDomainId("domain-1");

        com.pulse.sor.model.Domain domain = new com.pulse.sor.model.Domain();
        domain.setId("domain-1");
        domain.setSlug("servicing");

        com.pulse.storage.model.StorageBackend sb = new com.pulse.storage.model.StorageBackend();
        sb.setTenantId("tenant-1");
        sb.setEnvironment("dev");
        sb.setBackend("DPC");
        sb.setStorageRootFiles("pulse-dpc-tenant-1-dev-files");
        sb.setStorageRootLake("pulse-dpc-tenant-1-dev-lake");
        sb.setDpcScheme("s3a");

        PortWiring wire = new PortWiring();
        wire.setVersionId("version-1");
        wire.setSourceInstanceId(ingest.getId());
        wire.setTargetInstanceId(scd2.getId());
        wire.setSourcePortName("raw_output");
        wire.setTargetPortName("data_input");

        stubGenerate(List.of(ingest, scd2), buildBlueprint("FileIngestion", BlueprintCategory.INGESTION));
        when(blueprintRepo.findByBlueprintKey("SCD2Dimension"))
                .thenReturn(Optional.of(buildBlueprint("SCD2Dimension", BlueprintCategory.MODELING)));
        when(connectorInstanceRepo.findById("ci-src-1")).thenReturn(Optional.of(srcCi));
        when(instanceRepo.findById(ingest.getId())).thenReturn(Optional.of(ingest));
        when(sorRepo.findById("sor-loan")).thenReturn(Optional.of(sor));
        when(domainRepo.findById("domain-1")).thenReturn(Optional.of(domain));
        when(storageBackendRepo.findByTenantIdAndEnvironmentAndBackend("tenant-1", "dev", "DPC"))
                .thenReturn(Optional.of(sb));
        when(pathConventionService.tableLocation(eq(sb), eq(com.pulse.storage.model.LakeLayer.BRONZE),
                eq(com.pulse.storage.model.LakeFormat.DELTA), eq("servicing"), eq("loan-source"),
                anyString(), eq("ingest-loan-master")))
                .thenReturn(new com.pulse.storage.model.TableLocation.ObjectStorePath(
                        "s3a://pulse-dpc-tenant-1-dev-lake/servicing/loan-source/test-pipeline/bronze/ingest-loan-master/"));
        when(pathConventionService.lakeLayerRoot(eq(sb), eq(com.pulse.storage.model.LakeLayer.GOLD),
                eq("servicing"), eq("loan-source"), anyString()))
                .thenReturn("s3a://pulse-dpc-tenant-1-dev-lake/servicing/loan-source/test-pipeline/gold");
        when(wiringRepo.findByVersionIdAndTargetInstanceId("version-1", scd2.getId()))
                .thenReturn(List.of(wire));

        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        assertEquals("COMPLETED", result.getStatus(), result::getErrorMessage);
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<GeneratedArtifact>> captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(artifactRepo).saveAll(captor.capture());
        GeneratedArtifact snapshot = captor.getValue().stream()
                .filter(a -> "DBT_SNAPSHOT".equals(a.getFileType()))
                .filter(a -> a.getFilePath().endsWith("snp__build_loan_scd2_dimension.sql"))
                .findFirst()
                .orElseThrow();
        assertTrue(snapshot.getContent().contains(
                "location_root='s3a://pulse-dpc-tenant-1-dev-lake/servicing/loan-source/test-pipeline/gold'"));
        assertTrue(snapshot.getContent().contains(
                "CREATE TABLE IF NOT EXISTS bronze_loan_drops.ingest_loan_master USING DELTA LOCATION 's3a://pulse-dpc-tenant-1-dev-lake/servicing/loan-source/test-pipeline/bronze/ingest-loan-master/'"));
    }

    @Test
    void generateDbtModels_silverMaterializationWithoutLakeRoot_isTable() {
        SubPipelineInstance clean = buildTransformInstance("Active Rows", "GenericFilter",
                Map.of("filter_mode", "sql", "raw_sql", "is_active = true"));
        Blueprint bp = buildBlueprint("GenericFilter", BlueprintCategory.TRANSFORM);
        stubGenerate(List.of(clean), bp);
        when(wiringRepo.findByVersionIdAndTargetInstanceId(anyString(), anyString())).thenReturn(List.of());

        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");
        assertEquals("COMPLETED", result.getStatus());
        verify(artifactRepo).saveAll(argThat(artifacts -> {
            var list = (List<GeneratedArtifact>) artifacts;
            return list.stream().anyMatch(a ->
                    "DBT_MODEL".equals(a.getFileType())
                            && a.getContent().contains("materialized='table'")
                            && a.getContent().contains("file_format='delta'"));
        }));
    }

    @Test
    void generateDbtModels_goldMaterialization_isTable() {
        SubPipelineInstance fact = buildTransformInstance("Headcount", "FactBuild", Map.of());
        Blueprint bp = buildBlueprint("FactBuild", BlueprintCategory.MODELING);
        stubGenerate(List.of(fact), bp);

        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");
        assertEquals("COMPLETED", result.getStatus());
        verify(artifactRepo).saveAll(argThat(artifacts -> {
            var list = (List<GeneratedArtifact>) artifacts;
            return list.stream().anyMatch(a ->
                    "DBT_MODEL".equals(a.getFileType())
                            && a.getFilePath().startsWith("dbt_project/models/marts/")
                            && a.getContent().contains("materialized='table'")
                            && !a.getContent().contains("file_format="));
        }));
    }

    @Test
    void generateDbtModels_jsonStructMapMappings_emitAliasedJsonFields() {
        SubPipelineInstance transform = buildTransformInstance("Build Borrower Struct", "JsonStruct",
                Map.of(
                        "output_format", "json_string",
                        "mappings", Map.of("borrower", List.of("borrower_first_name", "borrower_last_name", "risk_band")),
                        "passthrough_columns", List.of("loan_id", "loan_number")
                ));
        Blueprint bp = buildBlueprint("JsonStruct", BlueprintCategory.TRANSFORM);
        stubGenerate(List.of(transform), bp);

        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        assertEquals("COMPLETED", result.getStatus());
        verify(artifactRepo).saveAll(argThat(artifacts -> {
            var list = (List<GeneratedArtifact>) artifacts;
            return list.stream().anyMatch(a ->
                    "DBT_MODEL".equals(a.getFileType())
                            && a.getContent().contains("TO_JSON(NAMED_STRUCT(")
                            && a.getContent().contains("'first_name', borrower_first_name")
                            && a.getContent().contains("'last_name', borrower_last_name")
                            && a.getContent().contains("'risk_band', risk_band")
                            && a.getContent().contains("AS borrower"));
        }));
    }

    @Test
    void generateDbtModels_schemaNormalizationMappingRules_emitRenameProjection() {
        SubPipelineInstance transform = buildTransformInstance("Normalize Loan Schema", "SchemaNormalization",
                Map.of(
                        "target_schema", "loan_master_canonical",
                        "mapping_rules", Map.of("canonical_loan_id", "loan_id"),
                        "strict_mode", false
                ));
        Blueprint bp = buildBlueprint("SchemaNormalization", BlueprintCategory.TRANSFORM);
        stubGenerate(List.of(transform), bp);

        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        assertEquals("COMPLETED", result.getStatus());
        verify(artifactRepo).saveAll(argThat(artifacts -> {
            var list = (List<GeneratedArtifact>) artifacts;
            return list.stream().anyMatch(a ->
                    "DBT_MODEL".equals(a.getFileType())
                            && a.getContent().contains("dbt_utils.star")
                            && a.getContent().contains("\"loan_id\"")
                            && a.getContent().contains("loan_id AS canonical_loan_id"));
        }));
    }

    @Test
    void generateDbtModels_featureTablePublish_emitsConfiguredFeatureProjection() {
        SubPipelineInstance model = buildTransformInstance("Publish Loan Features", "FeatureTablePublish",
                Map.of(
                        "entity_key", "loan_id",
                        "features", List.of("current_upb", "interest_rate", "borrower_credit_score"),
                        "point_in_time_column", "origination_date"
                ));
        Blueprint bp = buildBlueprint("FeatureTablePublish", BlueprintCategory.MODELING);
        stubGenerate(List.of(model), bp);

        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        assertEquals("COMPLETED", result.getStatus());
        verify(artifactRepo).saveAll(argThat(artifacts -> {
            var list = (List<GeneratedArtifact>) artifacts;
            return list.stream().anyMatch(a ->
                    "DBT_MODEL".equals(a.getFileType())
                            && a.getFilePath().endsWith("feat__publish_loan_features.sql")
                            && a.getContent().contains("`loan_id`")
                            && a.getContent().contains("`current_upb`")
                            && a.getContent().contains("`interest_rate`")
                            && a.getContent().contains("`borrower_credit_score`")
                            && a.getContent().contains("`origination_date`"));
        }));
    }

    @Test
    void generateDbtModels_referenceDataPublish_emitsDistinctReferenceProjection() {
        SubPipelineInstance model = buildTransformInstance("Publish State Reference", "ReferenceDataPublish",
                Map.of(
                        "reference_type", "property_state",
                        "versioned", true
                ));
        Blueprint bp = buildBlueprint("ReferenceDataPublish", BlueprintCategory.MODELING);
        stubGenerate(List.of(model), bp);

        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        assertEquals("COMPLETED", result.getStatus());
        verify(artifactRepo).saveAll(argThat(artifacts -> {
            var list = (List<GeneratedArtifact>) artifacts;
            return list.stream().anyMatch(a ->
                    "DBT_MODEL".equals(a.getFileType())
                            && a.getFilePath().endsWith("ref__publish_state_reference.sql")
                            && a.getContent().contains("SELECT DISTINCT")
                            && a.getContent().contains("`property_state` AS `property_state`")
                            && a.getContent().contains("WHERE `property_state` IS NOT NULL"));
        }));
    }

    @Test
    void generateDbtModels_icebergEnabled_usesIcebergFormat() {
        SubPipelineInstance clean = buildTransformInstance("Active Rows", "GenericFilter",
                Map.of("filter_mode", "sql", "raw_sql", "is_active = true"));
        Blueprint bp = buildBlueprint("GenericFilter", BlueprintCategory.TRANSFORM);
        stubGenerate(List.of(clean), bp);
        when(compilePlanService.build(any(), anyString(), anyList(), anyList()))
                .thenAnswer(inv -> buildCompilePlan(inv.getArgument(2), "iceberg"));
        when(wiringRepo.findByVersionIdAndTargetInstanceId(anyString(), anyString())).thenReturn(List.of());

        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");
        assertEquals("COMPLETED", result.getStatus());
        verify(artifactRepo).saveAll(argThat(artifacts -> {
            var list = (List<GeneratedArtifact>) artifacts;
            return list.stream().anyMatch(a ->
                    "DBT_MODEL".equals(a.getFileType())
                            && a.getContent().contains("file_format='iceberg'"));
        }));
    }

    @Test
    void generateGxCheckpoints_withExpectations_emitsPythonFile() {
        SubPipelineInstance gate = buildTransformInstance("Employees Gate", "DQValidator", Map.of());
        gate.setDqExpectations(List.of(
                Map.of("type", "ExpectColumnValuesToNotBeNull",
                        "kwargs", Map.of("column", "employee_id"),
                        "severity", "critical")
        ));
        Blueprint bp = buildBlueprint("DQValidator", BlueprintCategory.DATA_QUALITY);
        stubGenerate(List.of(gate), bp);

        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");
        assertEquals("COMPLETED", result.getStatus());
        verify(artifactRepo).saveAll(argThat(artifacts -> {
            var list = (List<GeneratedArtifact>) artifacts;
            return list.stream().anyMatch(a ->
                    "GX_CHECKPOINT".equals(a.getFileType())
                            && a.getFilePath().endsWith("gx/checkpoints/employees_gate_checkpoint.py")
                            && a.getContent().contains("ExpectColumnValuesToNotBeNull"));
        }));
    }

    @Test
    void generateGxCheckpoints_dqValidatorParamsExpectations_writeValidatedOutput() {
        SubPipelineInstance gate = buildTransformInstance("Validate Loans", "DQValidator", Map.of(
                "expectations", List.of(Map.of(
                        "type", "ExpectColumnValuesToNotBeNull",
                        "kwargs", Map.of("column", "loan_id"),
                        "severity", "critical"
                )),
                "on_failure", "fail"
        ));
        Blueprint bp = buildBlueprint("DQValidator", BlueprintCategory.DATA_QUALITY);
        stubGenerate(List.of(gate), bp);

        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        assertEquals("COMPLETED", result.getStatus());
        verify(artifactRepo).saveAll(argThat(artifacts -> {
            var list = (List<GeneratedArtifact>) artifacts;
            return list.stream().anyMatch(a ->
                    "GX_CHECKPOINT".equals(a.getFileType())
                            && a.getFilePath().endsWith("gx/checkpoints/validate_loans_checkpoint.py")
                            && a.getContent().contains("ExpectColumnValuesToNotBeNull")
                            && a.getContent().contains("gx_stats = getattr(gx_result, \"statistics\", None)")
                            && !a.getContent().contains("gx_result.statistics")
                            && a.getContent().contains("input_path =")
                            && a.getContent().contains("output_path =")
                            && a.getContent().contains("df.write.mode('overwrite').format('delta')"));
        }));
    }

    @Test
    void generateAirflowDag_hasGxGatesBetweenLayers() {
        SubPipelineInstance ingest = buildIngestionInstance("Ingest", "FileIngestion",
                Map.of("connector_name", "source"));
        ingest.setExecutionOrder(1);
        SubPipelineInstance transform = buildTransformInstance("Transform", "GenericFilter",
                Map.of("filter_mode", "sql", "raw_sql", "1=1"));
        transform.setExecutionOrder(2);
        SubPipelineInstance sink = buildDestinationInstance("Write", "WarehouseWriter",
                Map.of("connector_name", "dest"));
        sink.setExecutionOrder(3);

        when(pipelineRepo.findById("pipeline-1")).thenReturn(Optional.of(testPipeline));
        when(versionRepo.findById("version-1")).thenReturn(Optional.of(testVersion));
        when(instanceRepo.findByVersionIdOrderByExecutionOrderAsc("version-1"))
                .thenReturn(List.of(ingest, transform, sink));
        when(runRepo.save(any(GenerationRun.class))).thenAnswer(inv -> {
            GenerationRun r = inv.getArgument(0);
            if (r.getId() == null) r.setId("run-1");
            return r;
        });
        when(runRepo.findTopByVersionIdOrderByCreatedAtDesc("version-1")).thenReturn(Optional.empty());
        when(blueprintRepo.findByBlueprintKey("FileIngestion"))
                .thenReturn(Optional.of(buildBlueprint("FileIngestion", BlueprintCategory.INGESTION)));
        when(blueprintRepo.findByBlueprintKey("GenericFilter"))
                .thenReturn(Optional.of(buildBlueprint("GenericFilter", BlueprintCategory.TRANSFORM)));
        when(blueprintRepo.findByBlueprintKey("WarehouseWriter"))
                .thenReturn(Optional.of(buildBlueprint("WarehouseWriter", BlueprintCategory.DESTINATION)));
        when(wiringRepo.findByVersionIdAndTargetInstanceId(anyString(), anyString()))
                .thenReturn(List.of());

        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");
        assertEquals("COMPLETED", result.getStatus());
        verify(artifactRepo).saveAll(argThat(artifacts -> {
            var list = (List<GeneratedArtifact>) artifacts;
            return list.stream().anyMatch(a ->
                    "AIRFLOW_DAG".equals(a.getFileType())
                            && a.getContent().contains("gx_bronze_silver_gate")
                            && a.getContent().contains("gx_silver_gold_gate")
                            && a.getContent().contains("do_xcom_push=False")
                            && !a.getContent().contains("dbt test"));
        }));
    }

    @Test
    void timeInjection_pysparkJob_hasPulseBusinessDate() {
        SubPipelineInstance ingest = buildIngestionInstance("S3 Load", "FileIngestion",
                Map.of("connector_name", "s3_source"));
        Blueprint bp = buildBlueprint("FileIngestion", BlueprintCategory.INGESTION);
        stubGenerate(List.of(ingest), bp);

        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");
        assertEquals("COMPLETED", result.getStatus());
        verify(artifactRepo).saveAll(argThat(artifacts -> {
            var list = (List<GeneratedArtifact>) artifacts;
            return list.stream().anyMatch(a ->
                    "PYSPARK_JOB".equals(a.getFileType())
                            && a.getContent().contains("PULSE_BUSINESS_DATE"));
        }));
    }

    // -----------------------------------------------------------------------
    //  PKT-0027: Secret manifest, forbidden-token scan, placeholder blocking,
    //  and Acme Lending scenario semantic tests
    // -----------------------------------------------------------------------

    @Test
    void pkt0027_secretManifestIncludesJdbcSourceCredentialRefs() {
        // PKT-0027 §1: Generated runtime secret manifest includes every connector
        // credential ref required by JDBC source.
        SubPipelineInstance inst = buildIngestionInstance("Postgres Ingest", "SnapshotIngestion",
                Map.of("connector_instance_id", "ci-pg-src", "connector_name", "postgres_source"));
        Blueprint bp = buildBlueprint("SnapshotIngestion", BlueprintCategory.INGESTION);

        ConnectorInstance pgConn = new ConnectorInstance();
        pgConn.setId("ci-pg-src");
        pgConn.setName("PostgreSQL Source");

        CredentialProfile cred = new CredentialProfile();
        cred.setConnectorInstanceId("ci-pg-src");
        cred.setEnvironment("DEV");
        cred.setConnectionConfig(Map.of(
                CredentialProfile.CANONICAL_METADATA_KEY, Map.of(
                        "host", "pg-host.acme.com", "port", "5432", "database", "lending_db"),
                CredentialProfile.CANONICAL_SECRET_REFS_KEY, Map.of(
                        "username", "gcp-sm://projects/pulse-prod/secrets/pg-username/versions/latest",
                        "password", "gcp-sm://projects/pulse-prod/secrets/pg-password/versions/latest")
        ));

        stubGenerate(List.of(inst), bp);
        when(credentialProfileRepo.findByConnectorInstanceIdAndEnvironment("ci-pg-src", "dev"))
                .thenReturn(Optional.of(cred));
        when(connectorInstanceRepo.findById("ci-pg-src"))
                .thenReturn(Optional.of(pgConn));

        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        assertEquals("COMPLETED", result.getStatus());
        // Verify secret manifest includes both credentials
        verify(artifactRepo).saveAll(argThat(artifacts -> {
            var list = (List<GeneratedArtifact>) artifacts;
            return list.stream().anyMatch(a ->
                    "RUNTIME_SECRET_MANIFEST".equals(a.getFileType())
                            && a.getContent().contains("\"fieldName\" : \"username\"")
                            && a.getContent().contains("\"fieldName\" : \"password\"")
                            && a.getContent().contains("gcp-sm://projects/pulse-prod/secrets/pg-username")
                            && a.getContent().contains("gcp-sm://projects/pulse-prod/secrets/pg-password"));
        }));
        // Verify run metadata includes secret manifest completeness
        assertTrue((Boolean) result.getMetadata().get("secretManifestComplete"),
                "secretManifestComplete should be true when secrets are present");
        assertEquals(1, result.getMetadata().get("secretManifestTaskCount"));
    }

    @Test
    void pkt0027_secretManifestIncludesTargetCredentialRefs() {
        // PKT-0027 §1: Generated runtime secret manifest includes target credentials.
        SubPipelineInstance dest = buildDestinationInstance("BQ Gold Write", "WarehouseWriter",
                Map.of("connector_instance_id", "ci-bq-1", "connector_name", "bigquery_dest",
                        "target_table", "acme_lending.gold_loans"));
        Blueprint bp = buildBlueprint("WarehouseWriter", BlueprintCategory.DESTINATION);

        ConnectorInstance bqConn = new ConnectorInstance();
        bqConn.setId("ci-bq-1");
        bqConn.setName("BigQuery Gold");

        CredentialProfile cred = new CredentialProfile();
        cred.setConnectorInstanceId("ci-bq-1");
        cred.setEnvironment("DEV");
        cred.setConnectionConfig(Map.of(
                CredentialProfile.CANONICAL_METADATA_KEY, Map.of(
                        "project", "acme-data-prod"),
                CredentialProfile.CANONICAL_SECRET_REFS_KEY, Map.of(
                        "credentials_json", "gcp-sm://projects/acme-data-prod/secrets/bq-sa-key/versions/latest")
        ));

        stubGenerate(List.of(dest), bp);
        when(credentialProfileRepo.findByConnectorInstanceIdAndEnvironment("ci-bq-1", "dev"))
                .thenReturn(Optional.of(cred));
        when(connectorInstanceRepo.findById("ci-bq-1"))
                .thenReturn(Optional.of(bqConn));

        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        assertEquals("COMPLETED", result.getStatus());
        verify(artifactRepo).saveAll(argThat(artifacts -> {
            var list = (List<GeneratedArtifact>) artifacts;
            return list.stream().anyMatch(a ->
                    "RUNTIME_SECRET_MANIFEST".equals(a.getFileType())
                            && a.getContent().contains("\"fieldName\" : \"credentials_json\"")
                            && a.getContent().contains("gcp-sm://projects/acme-data-prod/secrets/bq-sa-key")
                            // PKT-0027 §3.b: credentials_json uses FILE delivery mode
                            && a.getContent().contains("\"deliveryMode\" : \"FILE\""));
        }));
    }

    @Test
    void pkt0027_generatedCodeResolvesGcpSmSecretsViaRuntimeHelper() {
        // PKT-0027 §2: Generated Airflow/PySpark code resolves gcp-sm:// secrets
        // via runtime helper (pulse_secret_resolver).
        SubPipelineInstance inst = buildIngestionInstance("Secure JDBC", "SnapshotIngestion",
                Map.of("connector_instance_id", "ci-secure-1", "connector_name", "postgres_source"));
        Blueprint bp = buildBlueprint("SnapshotIngestion", BlueprintCategory.INGESTION);

        ConnectorInstance pgConn = new ConnectorInstance();
        pgConn.setId("ci-secure-1");
        pgConn.setName("PostgreSQL Secure");

        CredentialProfile cred = new CredentialProfile();
        cred.setConnectorInstanceId("ci-secure-1");
        cred.setEnvironment("DEV");
        cred.setConnectionConfig(Map.of(
                CredentialProfile.CANONICAL_METADATA_KEY, Map.of(
                        "host", "pg-secure.acme.com", "port", "5432", "database", "lending_db"),
                CredentialProfile.CANONICAL_SECRET_REFS_KEY, Map.of(
                        "password", "gcp-sm://projects/pulse-prod/secrets/pg-password/versions/latest")
        ));

        stubGenerate(List.of(inst), bp);
        when(credentialProfileRepo.findByConnectorInstanceIdAndEnvironment("ci-secure-1", "dev"))
                .thenReturn(Optional.of(cred));
        when(connectorInstanceRepo.findById("ci-secure-1"))
                .thenReturn(Optional.of(pgConn));

        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        assertEquals("COMPLETED", result.getStatus());
        verify(artifactRepo).saveAll(argThat(artifacts -> {
            var list = (List<GeneratedArtifact>) artifacts;
            // Runtime secret resolver module is generated
            boolean hasResolver = list.stream().anyMatch(a ->
                    "RUNTIME_SUPPORT".equals(a.getFileType())
                            && a.getFilePath().endsWith("runtime/pulse_secret_resolver.py")
                            && a.getContent().contains("gcp-sm://")
                            && a.getContent().contains("secretmanager"));
            // DAG imports the resolver
            boolean dagImportsResolver = list.stream().anyMatch(a ->
                    "AIRFLOW_DAG".equals(a.getFileType())
                            && a.getContent().contains("from runtime.pulse_secret_resolver import"));
            return hasResolver && dagImportsResolver;
        }));
    }

    @Test
    void pkt0027_placeholderOnlyJdbcCredentialsMarkedNotLiveRunnable() {
        // PKT-0027 §3: Generation blocks live-runnability when ${JDBC_*}
        // placeholders remain.
        SubPipelineInstance inst = buildIngestionInstance("Unconfigured JDBC", "SnapshotIngestion",
                Map.of("connector_name", "generic_source"));
        Blueprint bp = buildBlueprint("SnapshotIngestion", BlueprintCategory.INGESTION);

        stubGenerate(List.of(inst), bp);
        // No credential profile configured — will emit placeholders

        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        assertEquals("COMPLETED", result.getStatus());
        assertFalse((Boolean) result.getMetadata().get("liveRunnable"),
                "Should NOT be live-runnable when JDBC placeholders are unresolved");
        @SuppressWarnings("unchecked")
        List<String> placeholders = (List<String>) result.getMetadata().get("unresolvedPlaceholders");
        assertNotNull(placeholders, "unresolvedPlaceholders should be present in metadata");
        assertFalse(placeholders.isEmpty(), "Should have at least one unresolved placeholder");
    }

    @Test
    void pkt0027_missingSourceTablePlaceholderBlocksLiveRunnability() {
        // PKT-0027 §3: ${SOURCE_TABLE}-style placeholders block live-runnability.
        SubPipelineInstance inst = buildIngestionInstance("No Table JDBC", "SnapshotIngestion",
                Map.of("connector_instance_id", "ci-notbl-1", "connector_name", "oracle_source"));
        Blueprint bp = buildBlueprint("SnapshotIngestion", BlueprintCategory.INGESTION);

        ConnectorInstance oracleConn = new ConnectorInstance();
        oracleConn.setId("ci-notbl-1");
        oracleConn.setName("Oracle Partial");

        CredentialProfile cred = buildCredentialProfile("ci-notbl-1", Map.of(
                "host", "oracle-host", "port", "1521", "sid", "ORCL",
                "username", "dbuser", "password", "dbpass"));

        stubGenerate(List.of(inst), bp);
        when(credentialProfileRepo.findByConnectorInstanceIdAndEnvironment("ci-notbl-1", "dev"))
                .thenReturn(Optional.of(cred));
        when(connectorInstanceRepo.findById("ci-notbl-1"))
                .thenReturn(Optional.of(oracleConn));

        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        assertEquals("COMPLETED", result.getStatus());
        // The generated code will have ${SOURCE_TABLE} since no qualified_names param
        assertFalse((Boolean) result.getMetadata().get("liveRunnable"),
                "Should NOT be live-runnable when ${SOURCE_TABLE} placeholder remains");
    }

    @Test
    void pkt0027_fullyResolvedSecretRefsAreLiveRunnable() {
        // PKT-0027 §3: When all credential refs and table names are resolved,
        // the generation is live-runnable.
        SubPipelineInstance inst = buildIngestionInstance("Full Resolve JDBC", "SnapshotIngestion",
                Map.of("connector_instance_id", "ci-full-1", "connector_name", "postgres_source",
                        "qualified_names", List.of("public.loans")));
        Blueprint bp = buildBlueprint("SnapshotIngestion", BlueprintCategory.INGESTION);

        ConnectorInstance pgConn = new ConnectorInstance();
        pgConn.setId("ci-full-1");
        pgConn.setName("PostgreSQL Full");

        CredentialProfile cred = new CredentialProfile();
        cred.setConnectorInstanceId("ci-full-1");
        cred.setEnvironment("DEV");
        cred.setConnectionConfig(Map.of(
                CredentialProfile.CANONICAL_METADATA_KEY, Map.of(
                        "host", "pg-host.acme.com", "port", "5432", "database", "lending_db"),
                CredentialProfile.CANONICAL_SECRET_REFS_KEY, Map.of(
                        "username", "gcp-sm://projects/pulse-prod/secrets/pg-user/versions/latest",
                        "password", "gcp-sm://projects/pulse-prod/secrets/pg-pass/versions/latest")
        ));

        stubGenerate(List.of(inst), bp);
        when(credentialProfileRepo.findByConnectorInstanceIdAndEnvironment("ci-full-1", "dev"))
                .thenReturn(Optional.of(cred));
        when(connectorInstanceRepo.findById("ci-full-1"))
                .thenReturn(Optional.of(pgConn));

        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        assertEquals("COMPLETED", result.getStatus());
        assertTrue((Boolean) result.getMetadata().get("liveRunnable"),
                "Should be live-runnable when all credential refs and table names are resolved");
    }

    @Test
    void pkt0027_artifactScanProvesNoRawPasswordValues() {
        // PKT-0027 §4: Artifact scan proves no raw PAT, private key, password,
        // or service-account JSON values are emitted.
        SubPipelineInstance inst = buildIngestionInstance("Acme JDBC", "SnapshotIngestion",
                Map.of("connector_instance_id", "ci-acme-1", "connector_name", "postgres_source",
                        "qualified_names", List.of("public.loan_applications")));
        Blueprint bp = buildBlueprint("SnapshotIngestion", BlueprintCategory.INGESTION);

        ConnectorInstance pgConn = new ConnectorInstance();
        pgConn.setId("ci-acme-1");
        pgConn.setName("PostgreSQL Lending");

        CredentialProfile cred = new CredentialProfile();
        cred.setConnectorInstanceId("ci-acme-1");
        cred.setEnvironment("DEV");
        cred.setConnectionConfig(Map.of(
                CredentialProfile.CANONICAL_METADATA_KEY, Map.of(
                        "host", "pg-host.acme.com", "port", "5432", "database", "lending_db"),
                CredentialProfile.CANONICAL_SECRET_REFS_KEY, Map.of(
                        "password", "gcp-sm://projects/pulse-prod/secrets/lending-pg-password/versions/latest")
        ));

        stubGenerate(List.of(inst), bp);
        when(credentialProfileRepo.findByConnectorInstanceIdAndEnvironment("ci-acme-1", "dev"))
                .thenReturn(Optional.of(cred));
        when(connectorInstanceRepo.findById("ci-acme-1"))
                .thenReturn(Optional.of(pgConn));

        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        assertEquals("COMPLETED", result.getStatus());
        // Verify no forbidden tokens in any scannable artifact
        Object forbiddenTokens = result.getMetadata().get("forbiddenTokenViolations");
        assertNull(forbiddenTokens, "Should have no forbidden token violations");
    }

    @Test
    void pkt0027_generatedBronzeWriteIncludesCreatedAsTimestamp() {
        // PKT-0027 §5: Generated code includes created_as_timestamp on bronze write.
        SubPipelineInstance inst = buildIngestionInstance("Loan Ingest", "SnapshotIngestion",
                Map.of("connector_instance_id", "ci-loan-1", "connector_name", "postgres_source",
                        "qualified_names", List.of("public.loans")));
        Blueprint bp = buildBlueprint("SnapshotIngestion", BlueprintCategory.INGESTION);

        ConnectorInstance pgConn = new ConnectorInstance();
        pgConn.setId("ci-loan-1");
        pgConn.setName("PostgreSQL Lending");

        CredentialProfile cred = buildCredentialProfile("ci-loan-1", Map.of(
                "host", "pg-host", "port", "5432", "database", "lending",
                "username", "user", "password", "pass"));

        stubGenerate(List.of(inst), bp);
        when(credentialProfileRepo.findByConnectorInstanceIdAndEnvironment("ci-loan-1", "dev"))
                .thenReturn(Optional.of(cred));
        when(connectorInstanceRepo.findById("ci-loan-1"))
                .thenReturn(Optional.of(pgConn));

        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        assertEquals("COMPLETED", result.getStatus());
        verify(artifactRepo).saveAll(argThat(artifacts -> {
            var list = (List<GeneratedArtifact>) artifacts;
            return list.stream().anyMatch(a ->
                    "PYSPARK_JOB".equals(a.getFileType())
                            && a.getContent().contains("created_as_timestamp")
                            && a.getContent().contains("current_timestamp()"));
        }));
    }

    @Test
    void pkt0027_scd2DimensionIncludesEffectiveDates() {
        // PKT-0027 §5: SCD2 loan_id with automatic effective dates.
        SubPipelineInstance scd2 = buildTransformInstance("Loan SCD2", "SCD2Dimension",
                Map.of("unique_key", List.of("loan_id"),
                        "strategy", "timestamp",
                        "updated_at_column", "modified_date"));
        Blueprint bp = buildBlueprint("SCD2Dimension", BlueprintCategory.MODELING);
        bp.setValidLayers(List.of("gold"));

        stubGenerate(List.of(scd2), bp);
        when(wiringRepo.findByVersionIdAndTargetInstanceId(anyString(), anyString()))
                .thenReturn(List.of());

        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        assertEquals("COMPLETED", result.getStatus());
        verify(artifactRepo).saveAll(argThat(artifacts -> {
            var list = (List<GeneratedArtifact>) artifacts;
            return list.stream().anyMatch(a ->
                    "DBT_SNAPSHOT".equals(a.getFileType())
                            && a.getContent().contains("snp__loan_scd2")
                            && a.getContent().contains("unique_key='loan_id'")
                            && a.getContent().contains("strategy='timestamp'")
                            && a.getContent().contains("updated_at='modified_date'")
                            // PKT-0027: automatic effective dates
                            && a.getContent().contains("effective_from")
                            && a.getContent().contains("effective_to")
                            && a.getContent().contains("CAST(NULL AS TIMESTAMP) as effective_to"));
        }));
    }

    @Test
    void pkt0027_nativeBigQueryGoldWriteUsesStorageWriteApi() {
        // PKT-0027 §5: Native BigQuery gold write.
        SubPipelineInstance dest = buildDestinationInstance("BQ Gold", "WarehouseWriter",
                Map.of("connector_instance_id", "ci-bq-gold", "connector_name", "bigquery_dest",
                        "target_table", "acme_lending_gold.loan_summary"));
        Blueprint bp = buildBlueprint("WarehouseWriter", BlueprintCategory.DESTINATION);

        ConnectorInstance bqConn = new ConnectorInstance();
        bqConn.setId("ci-bq-gold");
        bqConn.setName("BigQuery Gold Target");

        CredentialProfile cred = buildCredentialProfile("ci-bq-gold", Map.of(
                "project", "acme-data-prod",
                "temporaryGcsBucket", "acme-bq-temp-bucket"));

        stubGenerate(List.of(dest), bp);
        when(credentialProfileRepo.findByConnectorInstanceIdAndEnvironment("ci-bq-gold", "dev"))
                .thenReturn(Optional.of(cred));
        when(connectorInstanceRepo.findById("ci-bq-gold"))
                .thenReturn(Optional.of(bqConn));

        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        assertEquals("COMPLETED", result.getStatus());
        verify(artifactRepo).saveAll(argThat(artifacts -> {
            var list = (List<GeneratedArtifact>) artifacts;
            return list.stream().anyMatch(a ->
                    "PYSPARK_JOB".equals(a.getFileType())
                            && a.getContent().contains(".format('bigquery')")
                            && a.getContent().contains("acme_lending_gold.loan_summary")
                            && a.getContent().contains("temporaryGcsBucket")
                            && a.getContent().contains("createDisposition")
                            && a.getContent().contains("writeDisposition")
                            && a.getContent().contains("WRITE_TRUNCATE"));
        }));
    }

    @Test
    void pkt0027_piiMaskingGeneratesCorrectMaskingDbtModel() {
        // PKT-0027 §5: PII masking with masking DQ.
        SubPipelineInstance piiMask = buildTransformInstance("Mask Borrower SSN", "PIIMasking",
                Map.of("columns_to_mask", List.of("borrower_ssn"),
                        "source_columns", List.of("loan_id", "borrower_ssn", "borrower_name")));
        Blueprint bp = buildBlueprint("PIIMasking", BlueprintCategory.TRANSFORM);

        stubGenerate(List.of(piiMask), bp);
        when(wiringRepo.findByVersionIdAndTargetInstanceId(anyString(), anyString()))
                .thenReturn(List.of());

        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        assertEquals("COMPLETED", result.getStatus());
        verify(artifactRepo).saveAll(argThat(artifacts -> {
            var list = (List<GeneratedArtifact>) artifacts;
            return list.stream().anyMatch(a ->
                    "DBT_MODEL".equals(a.getFileType())
                            && a.getContent().contains("borrower_ssn")
                            && a.getContent().contains("XXX-XX-")
                            && a.getContent().contains("regexp_extract")
                            && a.getContent().contains("_pulse_processed_at"));
        }));
    }

    @Test
    void pkt0027_compilePlanContractIncludesBronzeSilverGoldTargetRefs() {
        // PKT-0027 §5: Compile plan for bronze/silver/gold target topology.
        SubPipelineInstance ingest = buildIngestionInstance("Loan Ingest", "SnapshotIngestion",
                Map.of("connector_name", "postgres_source"));
        SubPipelineInstance clean = buildTransformInstance("Loan Clean", "BronzeToSilverCleaning", Map.of());
        clean.setExecutionOrder(2);
        SubPipelineInstance gold = buildDestinationInstance("Loan Gold", "WarehouseWriter",
                Map.of("connector_name", "bigquery_dest"));
        gold.setExecutionOrder(3);

        when(pipelineRepo.findById("pipeline-1")).thenReturn(Optional.of(testPipeline));
        when(versionRepo.findById("version-1")).thenReturn(Optional.of(testVersion));
        when(instanceRepo.findByVersionIdOrderByExecutionOrderAsc("version-1"))
                .thenReturn(List.of(ingest, clean, gold));
        when(runRepo.save(any(GenerationRun.class))).thenAnswer(inv -> {
            GenerationRun r = inv.getArgument(0);
            if (r.getId() == null) r.setId("run-1");
            return r;
        });
        when(runRepo.findTopByVersionIdOrderByCreatedAtDesc("version-1")).thenReturn(Optional.empty());
        when(blueprintRepo.findByBlueprintKey("SnapshotIngestion"))
                .thenReturn(Optional.of(buildBlueprint("SnapshotIngestion", BlueprintCategory.INGESTION)));
        when(blueprintRepo.findByBlueprintKey("BronzeToSilverCleaning"))
                .thenReturn(Optional.of(buildBlueprint("BronzeToSilverCleaning", BlueprintCategory.TRANSFORM)));
        when(blueprintRepo.findByBlueprintKey("WarehouseWriter"))
                .thenReturn(Optional.of(buildBlueprint("WarehouseWriter", BlueprintCategory.DESTINATION)));
        when(wiringRepo.findByVersionIdAndTargetInstanceId(anyString(), anyString()))
                .thenReturn(List.of());

        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        assertEquals("COMPLETED", result.getStatus());
        // Compile plan snapshot should have bronze, silver, and gold layers
        verify(artifactRepo).saveAll(argThat(artifacts -> {
            var list = (List<GeneratedArtifact>) artifacts;
            return list.stream().anyMatch(a ->
                    "COMPILE_PLAN".equals(a.getFileType())
                            && a.getContent().contains("\"goldPublishBoundary\"")
                            && a.getContent().contains("bigquery_input_publish"));
        }));
    }

    @Test
    void pkt0027_rawShortPasswordNeverAppearsInGeneratedArtifact() {
        // PKT-0027 REPAIR regression test: a credential profile with a raw short
        // password must NOT render that raw value into any PYSPARK_JOB or AIRFLOW_DAG
        // artifact. The password must be emitted as an env-var placeholder instead.
        SubPipelineInstance inst = buildIngestionInstance("Raw Cred JDBC", "SnapshotIngestion",
                Map.of("connector_instance_id", "ci-rawcred-1", "connector_name", "postgres_source",
                        "qualified_names", List.of("public.accounts")));
        Blueprint bp = buildBlueprint("SnapshotIngestion", BlueprintCategory.INGESTION);

        ConnectorInstance pgConn = new ConnectorInstance();
        pgConn.setId("ci-rawcred-1");
        pgConn.setName("PostgreSQL Raw");

        // Raw credentials — NOT gcp-sm:// or vault:// references
        CredentialProfile cred = buildCredentialProfile("ci-rawcred-1", Map.of(
                "host", "pg-host.acme.com",
                "port", "5432",
                "database", "lending_db",
                "username", "dbadmin",
                "password", "S3cretP@ss!"));

        stubGenerate(List.of(inst), bp);
        when(credentialProfileRepo.findByConnectorInstanceIdAndEnvironment("ci-rawcred-1", "dev"))
                .thenReturn(Optional.of(cred));
        when(connectorInstanceRepo.findById("ci-rawcred-1"))
                .thenReturn(Optional.of(pgConn));

        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        assertEquals("COMPLETED", result.getStatus());
        verify(artifactRepo).saveAll(argThat(artifacts -> {
            var list = (List<GeneratedArtifact>) artifacts;

            // The raw password value must NEVER appear in any artifact
            boolean noRawPassword = list.stream()
                    .filter(a -> a.getContent() != null)
                    .noneMatch(a -> a.getContent().contains("S3cretP@ss!"));

            // The PYSPARK_JOB must use os.environ for the password field
            boolean usesEnvVar = list.stream()
                    .filter(a -> "PYSPARK_JOB".equals(a.getFileType()))
                    .anyMatch(a -> a.getContent().contains("os.environ['PASSWORD']"));

            // The JDBC URL and non-secret metadata should still appear
            boolean hasJdbcUrl = list.stream()
                    .filter(a -> "PYSPARK_JOB".equals(a.getFileType()))
                    .anyMatch(a -> a.getContent().contains("jdbc:postgresql://pg-host.acme.com:5432/lending_db"));

            return noRawPassword && usesEnvVar && hasJdbcUrl;
        }));

        // liveRunnable must be false because password is a non-ref env placeholder
        assertFalse((Boolean) result.getMetadata().get("liveRunnable"),
                "Should NOT be live-runnable when secret-shaped field uses raw value placeholder");
    }

    @Test
    void pkt0027_rawApiKeyNeverAppearsInGeneratedArtifact() {
        // PKT-0027 REPAIR regression: raw api_key value must not appear in artifact
        SubPipelineInstance inst = buildIngestionInstance("Raw API Key", "ApiIngestion",
                Map.of("connector_instance_id", "ci-rawapi-1", "connector_name", "api_source"));
        Blueprint bp = buildBlueprint("ApiIngestion", BlueprintCategory.INGESTION);

        ConnectorInstance apiConn = new ConnectorInstance();
        apiConn.setId("ci-rawapi-1");
        apiConn.setName("REST API");
        apiConn.setConfigTemplate(Map.of("base_url", "https://api.example.com"));

        CredentialProfile cred = buildCredentialProfile("ci-rawapi-1", Map.of(
                "api_key", "sk-live-abc123xyz789"));

        stubGenerate(List.of(inst), bp);
        when(credentialProfileRepo.findByConnectorInstanceIdAndEnvironment("ci-rawapi-1", "dev"))
                .thenReturn(Optional.of(cred));
        when(connectorInstanceRepo.findById("ci-rawapi-1"))
                .thenReturn(Optional.of(apiConn));

        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        assertEquals("COMPLETED", result.getStatus());
        verify(artifactRepo).saveAll(argThat(artifacts -> {
            var list = (List<GeneratedArtifact>) artifacts;
            // Raw API key must NEVER appear
            boolean noRawKey = list.stream()
                    .filter(a -> a.getContent() != null)
                    .noneMatch(a -> a.getContent().contains("sk-live-abc123xyz789"));
            // Must use env var instead
            boolean usesEnvVar = list.stream()
                    .filter(a -> "PYSPARK_JOB".equals(a.getFileType()))
                    .anyMatch(a -> a.getContent().contains("os.environ['API_KEY']"));
            return noRawKey && usesEnvVar;
        }));
    }

    @Test
    void pkt0027_rawSaslPasswordNeverAppearsInGeneratedArtifact() {
        // PKT-0027 REPAIR regression: raw sasl_password value must not appear in artifact
        SubPipelineInstance inst = buildIngestionInstance("Raw Kafka", "StreamIngestion",
                Map.of("connector_instance_id", "ci-rawkafka-1", "connector_name", "kafka_source"));
        Blueprint bp = buildBlueprint("StreamIngestion", BlueprintCategory.INGESTION);

        ConnectorInstance kafkaConn = new ConnectorInstance();
        kafkaConn.setId("ci-rawkafka-1");
        kafkaConn.setName("Kafka Cluster");

        CredentialProfile cred = buildCredentialProfile("ci-rawkafka-1", Map.of(
                "bootstrap_servers", "kafka-broker:9092",
                "security_protocol", "SASL_SSL",
                "sasl_username", "kafka-user",
                "sasl_password", "kafka-secret-pass"));

        stubGenerate(List.of(inst), bp);
        when(credentialProfileRepo.findByConnectorInstanceIdAndEnvironment("ci-rawkafka-1", "dev"))
                .thenReturn(Optional.of(cred));
        when(connectorInstanceRepo.findById("ci-rawkafka-1"))
                .thenReturn(Optional.of(kafkaConn));

        GenerationRun result = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");

        assertEquals("COMPLETED", result.getStatus());
        verify(artifactRepo).saveAll(argThat(artifacts -> {
            var list = (List<GeneratedArtifact>) artifacts;
            // Raw sasl_password must NOT appear
            boolean noRawPass = list.stream()
                    .filter(a -> a.getContent() != null)
                    .noneMatch(a -> a.getContent().contains("kafka-secret-pass"));
            // Non-secret values (bootstrap_servers, security_protocol) should still appear
            boolean hasBootstrapServers = list.stream()
                    .filter(a -> "PYSPARK_JOB".equals(a.getFileType()))
                    .anyMatch(a -> a.getContent().contains("kafka-broker:9092"));
            return noRawPass && hasBootstrapServers;
        }));
    }

    @Test
    void pkt0027_redactedSecretFieldsDoNotLeakBetweenGenerationRuns() {
        // PKT-0027 CONCURRENCY REPAIR: Proves that redactedSecretFields from
        // one generation do not leak into a subsequent generation on the same
        // thread. First run uses raw password (redactedSecretFields present,
        // liveRunnable=false). Second run uses gcp-sm:// refs with fully
        // resolved table (no redactedSecretFields, liveRunnable=true).

        // --- Run 1: raw password ---
        SubPipelineInstance rawInst = buildIngestionInstance("Raw PG", "SnapshotIngestion",
                Map.of("connector_instance_id", "ci-raw-leak", "connector_name", "postgres_source",
                        "qualified_names", List.of("public.accounts")));
        Blueprint bp = buildBlueprint("SnapshotIngestion", BlueprintCategory.INGESTION);

        ConnectorInstance rawConn = new ConnectorInstance();
        rawConn.setId("ci-raw-leak");
        rawConn.setName("PostgreSQL Raw");

        CredentialProfile rawCred = buildCredentialProfile("ci-raw-leak", Map.of(
                "host", "pg-host", "port", "5432", "database", "db",
                "username", "user", "password", "leak-me-not"));

        stubGenerate(List.of(rawInst), bp);
        when(credentialProfileRepo.findByConnectorInstanceIdAndEnvironment("ci-raw-leak", "dev"))
                .thenReturn(Optional.of(rawCred));
        when(connectorInstanceRepo.findById("ci-raw-leak"))
                .thenReturn(Optional.of(rawConn));

        GenerationRun run1 = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");
        assertEquals("COMPLETED", run1.getStatus());
        assertFalse((Boolean) run1.getMetadata().get("liveRunnable"),
                "Run 1 should NOT be live-runnable (raw password)");
        assertNotNull(run1.getMetadata().get("redactedSecretFields"),
                "Run 1 should have redactedSecretFields");

        // --- Run 2: fully resolved gcp-sm:// refs ---
        SubPipelineInstance resolvedInst = buildIngestionInstance("Resolved PG", "SnapshotIngestion",
                Map.of("connector_instance_id", "ci-resolved-leak", "connector_name", "postgres_source",
                        "qualified_names", List.of("public.loans")));

        ConnectorInstance resolvedConn = new ConnectorInstance();
        resolvedConn.setId("ci-resolved-leak");
        resolvedConn.setName("PostgreSQL Resolved");

        CredentialProfile resolvedCred = new CredentialProfile();
        resolvedCred.setConnectorInstanceId("ci-resolved-leak");
        resolvedCred.setEnvironment("DEV");
        resolvedCred.setConnectionConfig(Map.of(
                CredentialProfile.CANONICAL_METADATA_KEY, Map.of(
                        "host", "pg-host", "port", "5432", "database", "db"),
                CredentialProfile.CANONICAL_SECRET_REFS_KEY, Map.of(
                        "username", "gcp-sm://projects/p/secrets/user/versions/latest",
                        "password", "gcp-sm://projects/p/secrets/pass/versions/latest")
        ));

        stubGenerate(List.of(resolvedInst), bp);
        when(credentialProfileRepo.findByConnectorInstanceIdAndEnvironment("ci-resolved-leak", "dev"))
                .thenReturn(Optional.of(resolvedCred));
        when(connectorInstanceRepo.findById("ci-resolved-leak"))
                .thenReturn(Optional.of(resolvedConn));

        GenerationRun run2 = service.generate("pipeline-1", "version-1", "tenant-1", "user-1");
        assertEquals("COMPLETED", run2.getStatus());
        assertTrue((Boolean) run2.getMetadata().get("liveRunnable"),
                "Run 2 should be live-runnable (gcp-sm refs, no stale redaction leakage)");
        assertNull(run2.getMetadata().get("redactedSecretFields"),
                "Run 2 must NOT have redactedSecretFields (no leakage from run 1)");
    }

    // -----------------------------------------------------------------------
    //  Helper methods
    // -----------------------------------------------------------------------

    private void stubGenerate(List<SubPipelineInstance> instances, Blueprint bp) {
        when(pipelineRepo.findById("pipeline-1")).thenReturn(Optional.of(testPipeline));
        when(versionRepo.findById("version-1")).thenReturn(Optional.of(testVersion));
        when(instanceRepo.findByVersionIdOrderByExecutionOrderAsc("version-1"))
                .thenReturn(instances);
        when(wiringRepo.findByVersionIdOrderByCreatedAtAsc("version-1"))
                .thenReturn(List.of());
        when(runRepo.save(any(GenerationRun.class))).thenAnswer(inv -> {
            GenerationRun r = inv.getArgument(0);
            if (r.getId() == null) r.setId("run-1");
            return r;
        });
        when(runRepo.findTopByVersionIdOrderByCreatedAtDesc("version-1"))
                .thenReturn(Optional.empty());
        when(blueprintRepo.findByBlueprintKey(bp.getBlueprintKey())).thenReturn(Optional.of(bp));
        when(blueprintRepo.findById(bp.getId())).thenReturn(Optional.of(bp));
    }

    private SubPipelineInstance buildIngestionInstance(String name, String bpKey, Map<String, Object> params) {
        SubPipelineInstance inst = new SubPipelineInstance();
        inst.setId("inst-" + name.toLowerCase().replace(" ", "-"));
        inst.setPipelineId("pipeline-1");
        inst.setVersionId("version-1");
        inst.setBlueprintId("bp-" + bpKey);
        inst.setBlueprintKey(bpKey);
        inst.setBlueprintVersion("1.0");
        inst.setName(name);
        inst.setExecutionOrder(1);
        inst.setParams(new HashMap<>(params));
        return inst;
    }

    private SubPipelineInstance buildTransformInstance(String name, String bpKey, Map<String, Object> params) {
        SubPipelineInstance inst = buildIngestionInstance(name, bpKey, params);
        inst.setExecutionOrder(2);
        return inst;
    }

    private SubPipelineInstance buildDestinationInstance(String name, String bpKey, Map<String, Object> params) {
        SubPipelineInstance inst = buildIngestionInstance(name, bpKey, params);
        inst.setExecutionOrder(3);
        return inst;
    }

    private Blueprint buildBlueprint(String key, BlueprintCategory category) {
        Blueprint bp = new Blueprint();
        bp.setId("bp-" + key);
        bp.setBlueprintKey(key);
        bp.setName(key + " Blueprint");
        bp.setDescription("Test blueprint for " + key);
        bp.setCategory(category);
        bp.setVersion("1.0");
        return bp;
    }

    private RuntimeAuthority testRuntimeAuthority() {
        return new RuntimeAuthority(
                RuntimePersona.GCP_PULSE,
                RuntimePersona.GCP_PULSE.displayName(),
                Set.of("GCP_COMPOSER_DATAPROC"),
                Set.of("GCP"),
                Set.of("COMPOSER"),
                Set.of("DATAPROC"),
                Set.of("GCS"),
                Set.of("BIGQUERY", "BIGLAKE_ICEBERG"),
                Set.of(RuntimePersona.DPC_PULSE),
                Map.of(
                        "bronze", List.of("iceberg_bq_managed"),
                        "silver", List.of("iceberg_bq_managed"),
                        "gold", List.of("bq_native")),
                SecretAuthorityKind.GCP_SECRET_MANAGER,
                "test");
    }

    private CredentialProfile buildCredentialProfile(String connectorInstanceId, Map<String, Object> config) {
        CredentialProfile cred = new CredentialProfile();
        cred.setId("cred-" + connectorInstanceId);
        cred.setConnectorInstanceId(connectorInstanceId);
        cred.setEnvironment("DEV");
        cred.setConnectionConfig(config);
        return cred;
    }

    private CompilePlanService.CompilePlanSnapshot buildCompilePlan(List<SubPipelineInstance> instances) {
        return buildCompilePlan(instances, "delta");
    }

    private CompilePlanService.CompilePlanSnapshot buildCompilePlan(List<SubPipelineInstance> instances,
                                                                    String fileFormat) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (SubPipelineInstance instance : instances) {
            String layer = layerForBlueprint(instance.getBlueprintKey());
            Map<String, Object> node = new HashMap<>();
            node.put("instanceId", instance.getId());
            node.put("instanceName", instance.getName());
            node.put("blueprintKey", instance.getBlueprintKey());
            node.put("resolvedLayer", layer);
            node.put("artifactHints", Map.of("emitStrategy", "generate", "fileFormat", fileFormat));
            nodes.add(node);
        }
        List<Map<String, Object>> advanceTimeContracts = new ArrayList<>();
        for (SubPipelineInstance instance : instances) {
            if (!"AdvanceTimeDimension".equals(instance.getBlueprintKey())) {
                continue;
            }
            Map<String, Object> params = instance.getParams() == null ? Map.of() : instance.getParams();
            String stateBindingRef = String.valueOf(params.getOrDefault(
                    "state_binding_ref", "time_state:dataset:" + instance.getId()));
            advanceTimeContracts.add(new LinkedHashMap<>(Map.ofEntries(
                    Map.entry("instanceId", instance.getId()),
                    Map.entry("instanceName", instance.getName()),
                    Map.entry("targetScope", String.valueOf(params.getOrDefault("target_scope", "dataset"))),
                    Map.entry("scopeId", String.valueOf(params.getOrDefault("dataset_id", instance.getId()))),
                    Map.entry("stateBindingRef", stateBindingRef),
                    Map.entry("variableKey", String.valueOf(params.getOrDefault(
                            "variable_key", "pulse.time_state.tenant_1." + stateBindingRef.replace(':', '_')))),
                    Map.entry("calendarBindingRef", String.valueOf(params.getOrDefault("calendar_binding_ref", "calendar:servicing"))),
                    Map.entry("calendarBundleUri", String.valueOf(params.getOrDefault(
                            "calendar_bundle_uri", "runtime/calendar/default-calendar-bundle.json"))),
                    Map.entry("calendarBundleHash", String.valueOf(params.getOrDefault("calendar_bundle_hash", ""))),
                    Map.entry("calendarId", String.valueOf(params.getOrDefault("calendar_id", "US-FED"))),
                    Map.entry("advanceMode", String.valueOf(params.getOrDefault("advance_mode", "next_interval"))),
                    Map.entry("requestedAsofExpr", String.valueOf(params.getOrDefault("requested_asof_expr", ""))),
                    Map.entry("replayPolicy", String.valueOf(params.getOrDefault("replay_policy", "reject_backward"))),
                    Map.entry("initializationPolicy", String.valueOf(params.getOrDefault("initialization_policy", "require_existing"))),
                    Map.entry("concurrencyPolicy", String.valueOf(params.getOrDefault("concurrency_policy", "serialized_airflow"))),
                    Map.entry("evidencePrefix", String.valueOf(params.getOrDefault("evidence_prefix", "runtime-evidence/time-advances"))),
                    Map.entry("evidenceRequired", params.getOrDefault("evidence_required", true)),
                    Map.entry("grain", String.valueOf(params.getOrDefault("grain", "DAILY_BUSINESS_DAY"))),
                    Map.entry("timezone", String.valueOf(params.getOrDefault("timezone", "America/New_York"))),
                    Map.entry("notesTemplate", String.valueOf(params.getOrDefault("notes_template", ""))),
                    Map.entry("source", String.valueOf(params.getOrDefault("source", "AdvanceTimeDimension"))),
                    Map.entry("advancedBy", String.valueOf(params.getOrDefault("advanced_by", "airflow:{{ dag.dag_id }}"))),
                    Map.entry("poolName", "pulse_time_state_" + stateBindingRef.replace(':', '_').replace('-', '_')),
                    Map.entry("requiredPoolSlots", 1)
            )));
        }

        return new CompilePlanService.CompilePlanSnapshot(
                "pipeline-1",
                "version-1",
                "domain-1",
                "Servicing",
                "servicing/pipelines/test_pipeline",
                nodes,
                Map.of(
                        "mode", "bigquery_input_publish",
                        "inputDatasetTemplate", "tenant_1_servicing_gold_input",
                        "servingDatasetTemplate", "tenant_1_servicing_gold"
                ),
                List.of(),
                null,
                advanceTimeContracts
        );
    }

    private String layerForBlueprint(String bpKey) {
        return switch (bpKey) {
            case "FileIngestion", "ApiIngestion", "StreamIngestion",
                 "SnapshotIngestion", "CDCIngestion", "BulkBackfill" -> "bronze";
            case "FactBuild", "WideDenormalizedMart", "AggregateMaterialization",
                 "IncrementalMerge",
                 "FeatureTablePublish", "ReferenceDataPublish",
                 "SCD2Dimension", "SnapshotModel" -> "gold";
            case "WarehouseWriter" -> "gold";
            case "LakeWriter" -> "silver";
            case "StreamWriter", "DatabaseWriter" -> "gold";
            case "DQValidator", "FreshnessChecks", "SchemaDriftDetection", "AnomalyDetection" -> "silver";
            default -> "silver";
        };
    }

    private CompilePlanService.CompilePlanSnapshot buildReuseCompilePlan(String instanceId, String assetName) {
        return new CompilePlanService.CompilePlanSnapshot(
                "pipeline-1",
                "version-1",
                "domain-1",
                "Servicing",
                "servicing/pipelines/test_pipeline",
                List.of(Map.of(
                        "instanceId", instanceId,
                        "instanceName", "Current Employees",
                        "blueprintKey", "WideDenormalizedMart",
                        "resolvedLayer", "gold",
                        "emitStrategy", "reuse_wrapper",
                        "reuseAsset", Map.of("assetName", assetName),
                        "reuseDecision", Map.of(
                                "emitStrategy", "reuse_wrapper",
                                "score", 16,
                                "reasons", List.of("Exact business concept match.", "Exact schema signature match."),
                                "warnings", List.of("Wrap to preserve local ownership semantics."),
                                "compatibility", Map.of("referenceSafe", false)
                        )
                )),
                Map.of(
                        "mode", "bigquery_input_publish",
                        "inputDatasetTemplate", "tenant_1_servicing_gold_input",
                        "servingDatasetTemplate", "tenant_1_servicing_gold"
                ),
                List.of(),
                null,
                List.of()
        );
    }
}
