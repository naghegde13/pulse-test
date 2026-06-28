package com.pulse.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.auth.policy.ActorResolverService;
import com.pulse.auth.policy.AuthorizationPolicyService;
import com.pulse.blueprint.model.Blueprint;
import com.pulse.blueprint.model.BlueprintCategory;
import com.pulse.blueprint.repository.BlueprintRepository;
import com.pulse.chat.plan.ChatPlanErrorCodes;
import com.pulse.codegen.service.CodegenExampleService;
import com.pulse.codegen.service.DbtAssetRegistryService;
import com.pulse.command.service.PlanService;
import com.pulse.pipeline.repository.PipelineRepository;
import com.pulse.pipeline.repository.SubPipelineInstanceRepository;
import com.pulse.pipeline.service.CompositionService;
import com.pulse.pipeline.service.DqExpectationService;
import com.pulse.pipeline.service.DqReadinessService;
import com.pulse.pipeline.service.PipelineService;
import com.pulse.sor.repository.ConnectorDefinitionRepository;
import com.pulse.sor.repository.ConnectorInstanceRepository;
import com.pulse.sor.repository.CredentialProfileRepository;
import com.pulse.sor.repository.DatasetRepository;
import com.pulse.sor.repository.DomainRepository;
import com.pulse.sor.repository.SystemOfRecordRepository;
import com.pulse.storage.repository.StorageBackendRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Proof matrix entry for ARCH-018 plan_add_step policy-blueprint rejection.
 *
 * <p>Mocks the executor's dependencies so we can drive plan_add_step in
 * isolation. The orchestration-policy blueprints (pipelineConfig=true,
 * add_surface=orchestration_policy) must be rejected at the chat tool layer
 * with the {@code STEP_REQUIRES_PIPELINE_ORCHESTRATION} code so the LLM
 * routes to update_pipeline_orchestration. Composition blueprints pass
 * through to {@link CompositionService}.</p>
 */
@ExtendWith(MockitoExtension.class)
class PlanAddStepPolicyRejectionTest {

    @Mock private SystemOfRecordRepository sorRepo;
    @Mock private ConnectorInstanceRepository ciRepo;
    @Mock private DomainRepository domainRepo;
    @Mock private ConnectorDefinitionRepository connDefRepo;
    @Mock private CredentialProfileRepository credRepo;
    @Mock private DatasetRepository datasetRepo;
    @Mock private BlueprintRepository blueprintRepo;
    @Mock private PipelineRepository pipelineRepo;
    @Mock private CompositionService compositionService;
    @Mock private DqReadinessService dqReadinessService;
    @Mock private PipelineService pipelineService;
    @Mock private DbtAssetRegistryService dbtAssetRegistryService;
    @Mock private CodegenExampleService codegenExampleService;
    @Mock private StorageBackendRepository storageBackendRepo;
    @Mock private SubPipelineInstanceRepository instanceRepo;
    @Mock private ChatService chatService;
    @Mock private PlanService planService;
    @Mock private DqExpectationService dqExpectationService;
    @Mock private ChatReadToolHandler readToolHandler;
    @Mock private com.pulse.sor.service.SchemaDiscoveryService schemaDiscoveryService;
    @Mock private ChatValidationToolService validationToolService;

    private ChatToolExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new ChatToolExecutor(
                sorRepo, ciRepo, domainRepo, connDefRepo, credRepo, datasetRepo,
                blueprintRepo, pipelineRepo, compositionService, dqReadinessService,
                new ObjectMapper(), pipelineService, dbtAssetRegistryService,
                codegenExampleService, storageBackendRepo, instanceRepo, chatService,
                new AuthorizationPolicyService(),
                new ActorResolverService(),
                planService, dqExpectationService, readToolHandler,
                schemaDiscoveryService, validationToolService);
    }

    @Test
    void planAddStep_policyBlueprintByAddSurface_rejectsWithStableCode() {
        Blueprint policy = new Blueprint();
        policy.setId("bp-policy");
        policy.setBlueprintKey("ScheduleAndTriggers");
        policy.setName("Schedule And Triggers");
        policy.setDescription("test");
        policy.setCategory(BlueprintCategory.ORCHESTRATION);
        policy.setVersion("1.0");
        policy.setPipelineConfig(true);
        policy.setAddSurface("orchestration_policy");
        policy.setStatus("active");
        when(blueprintRepo.findByBlueprintKey("ScheduleAndTriggers")).thenReturn(Optional.of(policy));

        String result = executor.execute("plan_add_step", Map.of(
                "pipeline_id", "pipe-1",
                "blueprint_key", "ScheduleAndTriggers",
                "instance_name", "schedule"), "tenant-1");

        assertTrue(result.contains(ChatPlanErrorCodes.STEP_REQUIRES_PIPELINE_ORCHESTRATION),
                "expected stable code, got: " + result);
        assertTrue(result.contains("update_pipeline_orchestration"),
                "routing hint must mention the canonical alternative tool");
        // Crucially: CompositionService is NOT invoked because the chat layer
        // short-circuited.
        verifyNoInteractions(compositionService);
    }

    @Test
    void planAddStep_policyBlueprintByPipelineConfigOnly_alsoRejects() {
        // add_surface might be null on legacy rows that only carry
        // pipelineConfig=true; the chat guard must catch both.
        Blueprint policy = new Blueprint();
        policy.setId("bp-policy");
        policy.setBlueprintKey("BackfillAndReplay");
        policy.setName("Backfill And Replay");
        policy.setDescription("test");
        policy.setCategory(BlueprintCategory.ORCHESTRATION);
        policy.setVersion("1.0");
        policy.setPipelineConfig(true);
        policy.setAddSurface(null);
        policy.setStatus("active");
        when(blueprintRepo.findByBlueprintKey("BackfillAndReplay")).thenReturn(Optional.of(policy));

        String result = executor.execute("plan_add_step", Map.of(
                "pipeline_id", "pipe-1",
                "blueprint_key", "BackfillAndReplay",
                "instance_name", "backfill"), "tenant-1");

        assertTrue(result.contains(ChatPlanErrorCodes.STEP_REQUIRES_PIPELINE_ORCHESTRATION),
                "pipelineConfig=true alone must trigger the rejection: " + result);
        verifyNoInteractions(compositionService);
    }

    @Test
    void planAddStep_unknownBlueprintKey_passesThroughToProposeHandler() {
        // The chat-level policy guard is name-only; an unknown blueprint key
        // falls through to proposeAddInstance which produces its own "not
        // found" error from the pipeline lookup. We just check we did not
        // short-circuit with the policy code.
        when(blueprintRepo.findByBlueprintKey("MysteryBP")).thenReturn(Optional.empty());
        when(pipelineRepo.findById("pipe-1")).thenReturn(Optional.empty());

        String result = executor.execute("plan_add_step", Map.of(
                "pipeline_id", "pipe-1",
                "blueprint_key", "MysteryBP",
                "instance_name", "x"), "tenant-1");

        assertFalse(result.contains(ChatPlanErrorCodes.STEP_REQUIRES_PIPELINE_ORCHESTRATION),
                "unknown blueprint must not be falsely tagged policy: " + result);
    }
}
