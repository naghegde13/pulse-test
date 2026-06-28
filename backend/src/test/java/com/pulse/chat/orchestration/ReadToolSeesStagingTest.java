package com.pulse.chat.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.chat.service.ChatToolExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * P3 read-tools-see-staging invariant (06 §B.3): once ops are queued mid-turn, a
 * composition read tool answers from the STAGING view (applyOps(clone(canonical),
 * pending)), NOT from canonical — and it does NOT touch the canonical
 * CompositionService.
 */
@ExtendWith(MockitoExtension.class)
class ReadToolSeesStagingTest {

    @Mock private com.pulse.sor.repository.SystemOfRecordRepository sorRepo;
    @Mock private com.pulse.sor.repository.ConnectorInstanceRepository ciRepo;
    @Mock private com.pulse.sor.repository.DomainRepository domainRepo;
    @Mock private com.pulse.sor.repository.ConnectorDefinitionRepository connDefRepo;
    @Mock private com.pulse.sor.repository.CredentialProfileRepository credRepo;
    @Mock private com.pulse.sor.repository.DatasetRepository datasetRepo;
    @Mock private com.pulse.blueprint.repository.BlueprintRepository blueprintRepo;
    @Mock private com.pulse.pipeline.repository.PipelineRepository pipelineRepo;
    @Mock private com.pulse.pipeline.service.CompositionService compositionService;
    @Mock private com.pulse.pipeline.service.DqReadinessService dqReadinessService;
    @Mock private com.pulse.pipeline.service.PipelineService pipelineService;
    @Mock private com.pulse.codegen.service.DbtAssetRegistryService dbtAssetRegistryService;
    @Mock private com.pulse.codegen.service.CodegenExampleService codegenExampleService;
    @Mock private com.pulse.storage.repository.StorageBackendRepository storageBackendRepo;
    @Mock private com.pulse.pipeline.repository.SubPipelineInstanceRepository instanceRepo;
    @Mock private com.pulse.chat.service.ChatService chatService;
    @Mock private com.pulse.command.service.PlanService planService;
    @Mock private com.pulse.pipeline.service.DqExpectationService dqExpectationService;
    @Mock private com.pulse.chat.service.ChatReadToolHandler readToolHandler;
    @Mock private com.pulse.sor.service.SchemaDiscoveryService schemaDiscoveryService;
    @Mock private com.pulse.chat.service.ChatValidationToolService validationToolService;

    private ChatToolExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new ChatToolExecutor(
                sorRepo, ciRepo, domainRepo, connDefRepo, credRepo, datasetRepo, blueprintRepo,
                pipelineRepo, compositionService, dqReadinessService, new ObjectMapper(),
                pipelineService, dbtAssetRegistryService, codegenExampleService,
                storageBackendRepo, instanceRepo, chatService, /*authPolicy*/ null,
                /*actorResolver*/ null, planService, dqExpectationService, readToolHandler,
                schemaDiscoveryService, validationToolService);
    }

    @Test
    void getCompositionReadsStagingNotCanonicalWhenOpsQueued() {
        StagingGraph staging = StagingGraph.applyOps(StagingGraph.empty(), List.of(
                new PlanOperation.AddInstances(List.of(
                        new PlanOperation.InstanceSpec("read", "SourceSQL", null, null, null),
                        new PlanOperation.InstanceSpec("clean", "BronzeToSilverCleaning", null, null, null)), "add"),
                new PlanOperation.MergeWiring(List.of(
                        new PlanOperation.WireSpec("read", "out", "clean", "in")), "wire")));

        String out = executor.executeWithStaging("get_composition",
                Map.of("pipeline_id", "p1"), "tenant-1", staging);

        assertTrue(out.contains("STAGING"), "the read answers from the staging view");
        assertTrue(out.contains("read") && out.contains("clean"), "staged instances surfaced");
        // Crucially: it did NOT consult the canonical CompositionService.
        verifyNoInteractions(compositionService);
    }
}
