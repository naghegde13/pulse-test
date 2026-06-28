package com.pulse.e2e.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.codegen.model.GenerationRun;
import com.pulse.codegen.service.CodeGenerationService;
import com.pulse.e2e.contract.ScenarioDsl;
import com.pulse.e2e.support.RepresentativePipelineFixtureFactory;
import com.pulse.git.model.GitRepo;
import com.pulse.git.repository.GitRepoRepository;
import com.pulse.git.service.LocalGitService;
import com.pulse.pipeline.repository.PipelineRepository;
import com.pulse.pipeline.repository.PipelineVersionRepository;
import com.pulse.pipeline.service.CompositionService;
import com.pulse.sor.repository.ConnectorDefinitionRepository;
import com.pulse.sor.repository.ConnectorInstanceRepository;
import com.pulse.sor.repository.CredentialProfileRepository;
import com.pulse.sor.repository.DomainRepository;
import com.pulse.sor.repository.SystemOfRecordRepository;
import com.pulse.blueprint.repository.BlueprintRepository;
import com.pulse.codegen.repository.GeneratedArtifactRepository;
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
class LocalRuntimeBridgeIT {

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
    @Autowired private LocalGitService localGitService;
    @Autowired private ObjectMapper objectMapper;

    @TempDir Path tempDir;

    @Test
    void materialize_prefersTenantGitRepo_whenGeneratedRepoAlreadyCommitted() throws Exception {
        String tenantId = "tenant-proof";
        Path tenantRepo = tempDir.resolve("tenant-repo");
        localGitService.initRepo(tenantRepo.toString(), "main");

        GitRepo gitRepo = new GitRepo();
        gitRepo.setId("git-proof");
        gitRepo.setTenantId(tenantId);
        gitRepo.setScope("TENANT");
        gitRepo.setRepoType("LOCAL");
        gitRepo.setRepoUrl("file://" + tenantRepo);
        gitRepo.setLocalPath(tenantRepo.toString());
        gitRepo.setDefaultBranch("main");
        gitRepoRepository.save(gitRepo);

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
        var fixture = fixtureFactory.create(tenantId);

        GenerationRun run = codeGenerationService.generate(
                fixture.pipeline().getId(),
                fixture.version().getId(),
                tenantId,
                "tester"
        );

        assertEquals("COMPLETED", run.getStatus());
        assertNotNull(localGitService.getHeadSha(tenantRepo.toString()));

        LocalRuntimeBridge bridge = new LocalRuntimeBridge(
                generatedArtifactRepository,
                gitRepoRepository,
                objectMapper
        );
        Path runtimeRoot = tempDir.resolve("runtime-root");
        Path evidenceRoot = tempDir.resolve("evidence");

        LocalRuntimeBridge.RuntimeBridgeResult result = bridge.materialize(
                LocalRuntimeBridge.BridgeRequest.fromRun("loan-master-live-runtime", run, runtimeRoot, evidenceRoot)
        );

        assertEquals("tenant_git_repo", result.sourceStrategy());
        assertTrue(Files.exists(result.materializedRoot().resolve("dags")) || Files.exists(result.materializedRoot()));
        assertTrue(Files.exists(evidenceRoot.resolve("runtime-bridge.json")));
        assertTrue(Files.exists(evidenceRoot.resolve("evidence-index.json")));

        JsonNode bridgePacket = objectMapper.readTree(evidenceRoot.resolve("runtime-bridge.json").toFile());
        assertEquals("loan-master-live-runtime", bridgePacket.get("scenarioId").asText());
        assertEquals("tenant_git_repo", bridgePacket.get("sourceStrategy").asText());
        assertTrue(bridgePacket.get("artifactCount").asInt() > 0);
        assertTrue(bridgePacket.get("materializedFiles").isArray());
        assertTrue(bridgePacket.get("materializedFiles").size() > 0);

        ScenarioDsl.ScenarioDefinition scenario = new ScenarioDsl.ScenarioDefinition(
                "loan-master-live-runtime",
                "Loan master live runtime bridge",
                ScenarioDsl.ProofMode.LIVE_RUNTIME,
                ScenarioDsl.RuntimeAdapter.LOCAL_AIRFLOW_BRIDGE,
                List.of("phase1", "phase2", "runtime-bridge"),
                new ScenarioDsl.BuilderPlan(tenantId, "servicing", "loan_master", List.of("FileIngestion", "GenericFilter", "WarehouseWriter"), "loan_master.csv"),
                new ScenarioDsl.EvidenceExpectation(List.of("runtime-bridge.json", "evidence-index.json"), List.of("RUNTIME_BRIDGE_PACKET", "EVIDENCE_INDEX"), "verdict.json"),
                java.util.Map.of("fixture", "loan_master.csv")
        );
        assertEquals(ScenarioDsl.ProofMode.LIVE_RUNTIME, scenario.proofMode());
        assertEquals(ScenarioDsl.RuntimeAdapter.LOCAL_AIRFLOW_BRIDGE, scenario.runtimeAdapter());
    }

    @Test
    void materialize_fallsBackToArtifactExport_withoutTenantRepo() throws Exception {
        String tenantId = "tenant-no-repo";
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
        var fixture = fixtureFactory.create(tenantId);

        GenerationRun run = codeGenerationService.generate(
                fixture.pipeline().getId(),
                fixture.version().getId(),
                tenantId,
                "tester"
        );

        assertEquals("COMPLETED", run.getStatus());

        LocalRuntimeBridge bridge = new LocalRuntimeBridge(
                generatedArtifactRepository,
                gitRepoRepository,
                objectMapper
        );
        Path runtimeRoot = tempDir.resolve("runtime-root-fallback");
        Path evidenceRoot = tempDir.resolve("evidence-fallback");

        LocalRuntimeBridge.RuntimeBridgeResult result = bridge.materialize(
                LocalRuntimeBridge.BridgeRequest.fromRun("loan-master-fallback", run, runtimeRoot, evidenceRoot)
        );

        assertEquals("generated_artifact_export", result.sourceStrategy());
        assertTrue(Files.exists(result.materializedRoot()));
        assertTrue(Files.walk(result.materializedRoot()).anyMatch(Files::isRegularFile));
    }

    @Test
    void render_rewritesMaterializedArtifacts_forLocalAirflowRuntime() throws Exception {
        String tenantId = "tenant-render";
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
        var fixture = fixtureFactory.create(tenantId);

        GenerationRun run = codeGenerationService.generate(
                fixture.pipeline().getId(),
                fixture.version().getId(),
                tenantId,
                "tester"
        );
        assertEquals("COMPLETED", run.getStatus());

        LocalRuntimeBridge bridge = new LocalRuntimeBridge(
                generatedArtifactRepository,
                gitRepoRepository,
                objectMapper
        );
        Path tenantRepoRoot = tempDir.resolve("tenant-render-root");
        Path evidenceRoot = tempDir.resolve("tenant-render-evidence");
        LocalRuntimeBridge.RuntimeBridgeResult bridgeResult = bridge.materialize(
                LocalRuntimeBridge.BridgeRequest.fromRun("loan-master-render", run, tenantRepoRoot, evidenceRoot)
        );

        LocalRuntimeArtifactRenderer renderer = new LocalRuntimeArtifactRenderer(objectMapper);
        Path renderEvidenceRoot = evidenceRoot.resolve("rendered");
        LocalRuntimeArtifactRenderer.RenderResult renderResult = renderer.render(
                new LocalRuntimeArtifactRenderer.RenderRequest(
                        "loan-master-render",
                        "servicing/pipelines/representative_runtime_bridge_proof",
                        bridgeResult.materializedRoot(),
                        renderEvidenceRoot,
                        "s3a://pulse-dpc-home-lending-dev-lake/home-lending/servicing/loan-master-render",
                        "/opt/pulse/repo/home-lending/dbt_project",
                        Map.of("write_current_loans_to_lake", "filter_current_loans")
                )
        );

        Path sinkJob = bridgeResult.materializedRoot().resolve("jobs/sink/write_current_loans_to_lake_sink.py");
        Path dagFile = bridgeResult.materializedRoot().resolve("dags/representative_runtime_bridge_proof_dag.py");
        assertTrue(Files.readString(sinkJob).contains("filter_current_loans"));
        assertFalse(Files.readString(sinkJob).contains("${UPSTREAM_TASK}"));
        assertTrue(Files.readString(dagFile).contains("--profiles-dir /opt/pulse/repo/home-lending/dbt_project"));
        assertTrue(Files.exists(renderEvidenceRoot.resolve("runtime-render.json")));
        assertFalse(renderResult.patchedFiles().isEmpty());
    }
}
