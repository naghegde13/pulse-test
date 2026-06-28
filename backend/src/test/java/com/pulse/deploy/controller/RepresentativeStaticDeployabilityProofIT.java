package com.pulse.deploy.controller;

import com.pulse.blueprint.model.Blueprint;
import com.pulse.blueprint.model.BlueprintCategory;
import com.pulse.blueprint.repository.BlueprintRepository;
import com.pulse.codegen.model.GenerationRun;
import com.pulse.codegen.service.CodeGenerationService;
import com.pulse.deploy.model.Package;
import com.pulse.git.model.GitRepo;
import com.pulse.git.repository.GitRepoRepository;
import com.pulse.git.service.LocalGitService;
import com.pulse.pipeline.model.Pipeline;
import com.pulse.pipeline.model.PipelineStage;
import com.pulse.pipeline.model.PipelineVersion;
import com.pulse.pipeline.model.SubPipelineInstance;
import com.pulse.pipeline.service.CompositionService;
import com.pulse.pipeline.repository.PipelineRepository;
import com.pulse.pipeline.repository.PipelineVersionRepository;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RepresentativeStaticDeployabilityProofIT {

    @Autowired private DomainRepository domainRepository;
    @Autowired private PipelineRepository pipelineRepository;
    @Autowired private PipelineVersionRepository pipelineVersionRepository;
    @Autowired private BlueprintRepository blueprintRepository;
    @Autowired private ConnectorDefinitionRepository connectorDefinitionRepository;
    @Autowired private ConnectorInstanceRepository connectorInstanceRepository;
    @Autowired private CredentialProfileRepository credentialProfileRepository;
    @Autowired private SystemOfRecordRepository systemOfRecordRepository;
    @Autowired private CompositionService compositionService;
    @Autowired private CodeGenerationService codeGenerationService;
    @Autowired private DeployController deployController;
    @Autowired private GitRepoRepository gitRepoRepository;
    @Autowired private LocalGitService localGitService;

    @Test
    void representativePipelinePackage_isLikelyDeployableByStaticProof(@TempDir Path tenantRepoRoot) throws Exception {
        String tenantId = "tenant-proof";

        // Phase 2: representative proof must run against a tenant Git repo
        // so PackageService can capture provenance. We initialize a real
        // JGit repo in @TempDir, register it as a TENANT-scoped GitRepo,
        // and seed one commit so HEAD is born and the working tree is
        // clean. This keeps the proof aligned with the productized
        // package contract (provenance must be present + clean).
        Path repoPath = tenantRepoRoot.resolve("tenant-proof-repo");
        Files.createDirectories(repoPath);
        localGitService.initRepo(repoPath.toString(), "main");
        Files.writeString(repoPath.resolve("README.md"), "tenant-proof seed\n");
        localGitService.commitAll(repoPath.toString(), "tenant-proof seed commit");

        GitRepo tenantRepo = new GitRepo();
        tenantRepo.setId("git-repo-proof");
        tenantRepo.setTenantId(tenantId);
        tenantRepo.setScope("TENANT");
        tenantRepo.setRepoType("LOCAL");
        tenantRepo.setLocalPath(repoPath.toString());
        tenantRepo.setRepoUrl("file://" + repoPath);
        tenantRepo.setProvider("LOCAL");
        tenantRepo.setDefaultBranch("main");
        tenantRepo.setCurrentBranch("main");
        gitRepoRepository.save(tenantRepo);

        Domain domain = new Domain();
        domain.setId("domain-proof");
        domain.setTenantId(tenantId);
        domain.setName("Servicing");
        domain.setSlug("servicing");
        domainRepository.save(domain);

        Pipeline pipeline = new Pipeline();
        pipeline.setId("pipeline-proof");
        pipeline.setTenantId(tenantId);
        pipeline.setDomainId(domain.getId());
        pipeline.setDomainName(domain.getName());
        pipeline.setName("Representative Static Proof");
        pipeline.setCreatedBy("tester");
        pipelineRepository.save(pipeline);

        PipelineVersion version = new PipelineVersion();
        version.setId("version-proof");
        version.setPipelineId(pipeline.getId());
        version.setRevision(1);
        version.setLifecycleStage(PipelineStage.ENGINEERING);
        version.setCreatedBy("tester");
        version = pipelineVersionRepository.save(version);
        pipeline.setActiveVersionId(version.getId());
        pipelineRepository.save(pipeline);

        blueprintRepository.save(fileIngestionBlueprint());
        blueprintRepository.save(genericFilterBlueprint());
        blueprintRepository.save(warehouseWriterBlueprint());

        SystemOfRecord sor = new SystemOfRecord();
        sor.setId("sor-proof");
        sor.setTenantId(tenantId);
        sor.setName("HR Source");
        sor.setDescription("Representative source");
        sor.setDomainId(domain.getId());
        sor.setDomainName(domain.getName());
        sor.setOwnerId("tester");
        sor.setMetadata(Map.of());
        systemOfRecordRepository.save(sor);

        ConnectorDefinition sourceDef = new ConnectorDefinition();
        sourceDef.setId("conn-def-source");
        sourceDef.setName("S3-compatible Object Storage");
        sourceDef.setConnectorType(ConnectorType.SOURCE);
        sourceDef.setDockerRepository("pulse/source-s3");
        sourceDef.setDockerImageTag("1.0.0");
        sourceDef.setConnectionSpec(Map.of());
        sourceDef.setSupportedModes(List.of("full_refresh"));
        sourceDef.setReleaseStage(ReleaseStage.GENERALLY_AVAILABLE);
        connectorDefinitionRepository.save(sourceDef);

        ConnectorDefinition destDef = new ConnectorDefinition();
        destDef.setId("conn-def-dest");
        destDef.setName("Snowflake");
        destDef.setConnectorType(ConnectorType.DESTINATION);
        destDef.setDockerRepository("pulse/destination-snowflake");
        destDef.setDockerImageTag("1.0.0");
        destDef.setConnectionSpec(Map.of());
        destDef.setSupportedModes(List.of());
        destDef.setReleaseStage(ReleaseStage.GENERALLY_AVAILABLE);
        connectorDefinitionRepository.save(destDef);

        ConnectorInstance source = new ConnectorInstance();
        source.setId("conn-inst-source");
        source.setSorId(sor.getId());
        source.setConnectorDefinitionId(sourceDef.getId());
        source.setName("HR S3 Drops");
        source.setConfigTemplate(Map.of(
                "bucket", "hr-source-bucket",
                "path_prefix", "employees/"
        ));
        connectorInstanceRepository.save(source);

        ConnectorInstance destination = new ConnectorInstance();
        destination.setId("conn-inst-dest");
        destination.setSorId(sor.getId());
        destination.setConnectorDefinitionId(destDef.getId());
        destination.setName("Snowflake Analytics");
        destination.setConfigTemplate(Map.of(
                "host", "account.snowflakecomputing.com",
                "database", "ANALYTICS",
                "schema", "PUBLIC",
                "warehouse", "COMPUTE_WH"
        ));
        connectorInstanceRepository.save(destination);

        CredentialProfile sourceCreds = new CredentialProfile();
        sourceCreds.setId("cred-source");
        sourceCreds.setConnectorInstanceId(source.getId());
        sourceCreds.setEnvironment("DEV");
        sourceCreds.setConnectionConfig(Map.of(
                "aws_access_key_id", "vault://pulse/dev/aws/access_key",
                "aws_secret_access_key", "vault://pulse/dev/aws/secret_key",
                "region", "us-east-1"
        ));
        sourceCreds.setStatus(CredentialStatus.VALID);
        credentialProfileRepository.save(sourceCreds);

        CredentialProfile destCreds = new CredentialProfile();
        destCreds.setId("cred-dest");
        destCreds.setConnectorInstanceId(destination.getId());
        destCreds.setEnvironment("DEV");
        destCreds.setConnectionConfig(Map.of(
                "username", "vault://pulse/dev/snowflake/username",
                "password", "vault://pulse/dev/snowflake/password",
                "host", "account.snowflakecomputing.com",
                "database", "ANALYTICS",
                "schema", "PUBLIC",
                "warehouse", "COMPUTE_WH"
        ));
        destCreds.setStatus(CredentialStatus.VALID);
        credentialProfileRepository.save(destCreds);

        var ingest = compositionService.addInstance(
                pipeline.getId(),
                version.getId(),
                "FileIngestion",
                "Ingest HR Files",
                Map.of(
                        "connector_instance_id", source.getId(),
                        "connector_name", source.getName()
                )
        );
        var filter = compositionService.addInstance(
                pipeline.getId(),
                version.getId(),
                "GenericFilter",
                "Filter Active Employees",
                Map.of(
                        "filter_mode", "sql",
                        "raw_sql", "employment_status = 'ACTIVE'"
                )
        );
        var sink = compositionService.addInstance(
                pipeline.getId(),
                version.getId(),
                "WarehouseWriter",
                "Write To Snowflake",
                Map.of(
                        "connector_instance_id", destination.getId(),
                        "connector_name", destination.getName(),
                        "target_table", "analytics.active_employees"
                )
        );

        compositionService.wirePort(version.getId(), ingest.getId(), "raw_output", filter.getId(), "data_input");
        compositionService.wirePort(version.getId(), filter.getId(), "filtered_output", sink.getId(), "data_input");

        GenerationRun run = codeGenerationService.generate(
                pipeline.getId(),
                version.getId(),
                tenantId,
                "tester"
        );
        assertEquals("COMPLETED", run.getStatus());

        var response = deployController.buildPackage(
                version.getId(),
                new DeployController.BuildRequest(pipeline.getId(), tenantId, "tester", null)
        );

        assertEquals(200, response.getStatusCode().value());
        Package pkg = response.getBody();
        assertNotNull(pkg);
        assertEquals("ARTIFACT_BUNDLE", pkg.getPackageType());
        assertNotNull(pkg.getArtifactUrl());
        assertNotNull(pkg.getArtifactHash());
        @SuppressWarnings("unchecked")
        Map<String, Object> assessment = (Map<String, Object>) pkg.getMetadata().get("staticRuntimeAssessment");
        assertNotNull(assessment);
        assertEquals("LIKELY_DEPLOYABLE", assessment.get("verdict"));
        assertEquals(0, assessment.get("todoCount"));

        // Phase 2: the representative proof now also asserts that package
        // metadata carries a complete Git provenance block sourced from
        // the seeded tenant repo, plus a deterministic manifest hash.
        @SuppressWarnings("unchecked")
        Map<String, Object> git = (Map<String, Object>) pkg.getMetadata().get("git");
        assertNotNull(git, "metadata.git provenance block must be present");
        assertEquals("git-repo-proof", git.get("repoId"));
        assertEquals("main", git.get("branch"));
        assertEquals("clean", git.get("workingTreeStatus"));
        assertNotNull(git.get("commitSha"));
        assertNotNull(git.get("treeSha"));
        @SuppressWarnings("unchecked")
        Map<String, Object> manifest = (Map<String, Object>) pkg.getMetadata().get("packageManifest");
        assertNotNull(manifest);
        assertEquals("deployment-package-manifest.v1", manifest.get("schemaVersion"));
        String manifestHash = (String) pkg.getMetadata().get("packageManifestHash");
        assertNotNull(manifestHash);
        assertEquals(64, manifestHash.length(), "packageManifestHash must be a 64-char SHA-256 hex");

        // Phase 2 closeout: flat top-level provenance keys must mirror the
        // nested git block AND the manifest's git block exactly. This is
        // the same contract as PackageProvenanceContractTest, asserted
        // here against a fully-Spring-wired buildPackage call so a future
        // refactor that drops aliases for the unit test (mocked
        // PackageService) can't sneak past the integration boundary.
        assertEquals(git.get("repoId"),            pkg.getMetadata().get("gitRepoId"));
        assertEquals(git.get("branch"),            pkg.getMetadata().get("gitBranch"));
        assertEquals(git.get("commitSha"),         pkg.getMetadata().get("gitCommitSha"));
        assertEquals(git.get("treeSha"),           pkg.getMetadata().get("gitTreeSha"));
        assertEquals(git.get("workingTreeStatus"), pkg.getMetadata().get("workingTreeStatus"));
        assertEquals(pkg.getArtifactHash(),        pkg.getMetadata().get("artifactHash"));
        @SuppressWarnings("unchecked")
        Map<String, Object> manifestGit = (Map<String, Object>) manifest.get("git");
        assertEquals(git, manifestGit, "metadata.git must equal packageManifest.git");
        assertNotNull(pkg.getMetadata().get("generationRunId"));
    }

    private Blueprint fileIngestionBlueprint() {
        Blueprint bp = new Blueprint();
        bp.setId("bp-file");
        bp.setBlueprintKey("FileIngestion");
        bp.setName("File Ingestion");
        bp.setDescription("Ingest raw files");
        bp.setCategory(BlueprintCategory.INGESTION);
        bp.setVersion("1.0.0");
        bp.setParamsSchema(List.of());
        bp.setInputPorts(List.of());
        bp.setOutputPorts(List.of(Map.of("name", "raw_output")));
        bp.setRuntimeRequirements(Map.of());
        bp.setDeferred(false);
        bp.setPipelineConfig(false);
        bp.setValidLayers(List.of("bronze"));
        bp.setComputeBackend("spark");
        bp.setEmitStrategy("generate");
        return bp;
    }

    private Blueprint genericFilterBlueprint() {
        Blueprint bp = new Blueprint();
        bp.setId("bp-filter");
        bp.setBlueprintKey("GenericFilter");
        bp.setName("Generic Filter");
        bp.setDescription("Filter data");
        bp.setCategory(BlueprintCategory.TRANSFORM);
        bp.setVersion("1.0.0");
        bp.setParamsSchema(List.of());
        bp.setInputPorts(List.of(Map.of("name", "data_input")));
        bp.setOutputPorts(List.of(Map.of("name", "filtered_output")));
        bp.setRuntimeRequirements(Map.of());
        bp.setDeferred(false);
        bp.setPipelineConfig(false);
        bp.setValidLayers(List.of("silver"));
        bp.setComputeBackend("spark");
        bp.setEmitStrategy("generate");
        return bp;
    }

    private Blueprint warehouseWriterBlueprint() {
        Blueprint bp = new Blueprint();
        bp.setId("bp-sink");
        bp.setBlueprintKey("WarehouseWriter");
        bp.setName("Warehouse Writer");
        bp.setDescription("Write to warehouse");
        bp.setCategory(BlueprintCategory.DESTINATION);
        bp.setVersion("1.0.0");
        bp.setParamsSchema(List.of());
        bp.setInputPorts(List.of(Map.of("name", "data_input")));
        bp.setOutputPorts(List.of());
        bp.setRuntimeRequirements(Map.of());
        bp.setDeferred(false);
        bp.setPipelineConfig(false);
        bp.setValidLayers(List.of("gold"));
        bp.setComputeBackend("spark");
        bp.setEmitStrategy("generate");
        return bp;
    }
}
