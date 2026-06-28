package com.pulse.command.service;

import com.pulse.auth.model.PulseUser;
import com.pulse.auth.model.Tenant;
import com.pulse.auth.repository.TenantRepository;
import com.pulse.auth.repository.UserRepository;
import com.pulse.blueprint.repository.BlueprintRepository;
import com.pulse.chat.model.ChatMessage;
import com.pulse.chat.repository.ChatMessageRepository;
import com.pulse.command.model.CommandLog;
import com.pulse.command.model.CommandStatus;
import com.pulse.command.model.Plan;
import com.pulse.command.model.PlanStatus;
import com.pulse.command.repository.PlanRepository;
import com.pulse.command.service.PlanService.PlannedCommand;
import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.pipeline.service.PipelineCommandHandlers;
import com.pulse.pipeline.repository.PipelineRepository;
import com.pulse.pipeline.repository.PipelineVersionRepository;
import com.pulse.pipeline.repository.PortWiringRepository;
import com.pulse.pipeline.repository.SubPipelineInstanceRepository;
import com.pulse.sor.repository.ConnectorDefinitionRepository;
import com.pulse.sor.repository.ConnectorInstanceRepository;
import com.pulse.sor.repository.DatasetRepository;
import com.pulse.sor.repository.DomainRepository;
import com.pulse.sor.repository.SystemOfRecordRepository;
import com.pulse.support.SeedFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Baseline coverage for {@link PlanService}: createPreview, apply lifecycle, listing order,
 * cancel semantics, and error paths.
 *
 * <p><strong>Behavioural note.</strong> The packet's status nomenclature ("PENDING → EXECUTING →
 * SUCCEEDED" and "cancel marks plan as REJECTED") describes desired semantics; the current
 * implementation uses {@link PlanStatus#PREVIEW} → {@link PlanStatus#APPLYING} →
 * {@link PlanStatus#APPLIED} and cancel sets {@link PlanStatus#CANCELLED}. Per the packet's
 * risk-mitigation guidance ("Drive tests against current behavior; if tests reveal bugs, file
 * them as separate fix tickets rather than blocking this baseline") these tests assert the
 * implemented behaviour. The naming gap is a separate fix ticket.
 */
@SpringBootTest
@ActiveProfiles("test")
class PlanServiceTest {

    @Autowired private PlanService planService;
    @Autowired private CommandService commandService;
    @Autowired private PlanRepository planRepository;
    @Autowired private ChatMessageRepository chatMessageRepository;

    @Autowired private TenantRepository tenantRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private DomainRepository domainRepository;
    @Autowired private SystemOfRecordRepository sorRepository;
    @Autowired private ConnectorDefinitionRepository connectorDefinitionRepository;
    @Autowired private ConnectorInstanceRepository connectorInstanceRepository;
    @Autowired private DatasetRepository datasetRepository;
    @Autowired private BlueprintRepository blueprintRepository;
    @Autowired private PipelineRepository pipelineRepository;
    @Autowired private PipelineVersionRepository pipelineVersionRepository;
    @Autowired private SubPipelineInstanceRepository subPipelineInstanceRepository;
    @Autowired private PortWiringRepository portWiringRepository;

    private SeedFixtures seedFixtures;
    private Tenant tenant;
    private PulseUser actor;

    @BeforeEach
    void seedIdentity() {
        chatMessageRepository.deleteAll();
        planRepository.deleteAll();
        seedFixtures = new SeedFixtures(
                tenantRepository, userRepository, domainRepository, sorRepository,
                connectorDefinitionRepository, connectorInstanceRepository, datasetRepository,
                blueprintRepository, pipelineRepository, pipelineVersionRepository,
                subPipelineInstanceRepository, portWiringRepository);
        tenant = seedFixtures.seedTenant();
        actor = seedFixtures.seedUser(tenant.getId());
    }

    // ---------------------------------------------------------------------
    //  createPreview
    // ---------------------------------------------------------------------

    @Test
    void createPreview_persistsPlanInPreviewStatusWithCommandSummary() {
        List<PlannedCommand> commands = List.of(
                new PlannedCommand("Pipeline.Create", "Pipeline", "pipe-1",
                        "Create loan_master pipeline", Map.of("name", "loan_master")),
                new PlannedCommand("Dataset.Bind", "Dataset", "ds-1",
                        "Bind input dataset", Map.of("dataset_id", "ds-1")));

        Plan plan = planService.createPreview(
                tenant.getId(), "pipeline-1", actor.getId(),
                "Initial plan", commands);

        assertNotNull(plan.getId(), "id populated on save");
        assertEquals(PlanStatus.PREVIEW, plan.getStatus(),
                "newly created plan must start in PREVIEW (chat UI relies on this)");
        assertEquals(tenant.getId(), plan.getTenantId());
        assertEquals("pipeline-1", plan.getPipelineId());
        assertEquals(actor.getId(), plan.getActorId());
        assertEquals("Initial plan", plan.getDescription());
        assertNotNull(plan.getCommandIds(), "commandIds initialised to empty list before apply");
        assertTrue(plan.getCommandIds().isEmpty(), "commandIds populated only on apply");
        assertNull(plan.getAppliedAt(), "appliedAt null until apply");

        Map<String, Object> preview = plan.getPreviewData();
        assertNotNull(preview, "previewData persisted");
        assertEquals(2, preview.get("commandCount"), "command count surfaced for chat UI");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> previewCommands = (List<Map<String, Object>>) preview.get("commands");
        assertEquals(2, previewCommands.size(), "preview entries match input commands");
        assertEquals("Pipeline.Create", previewCommands.get(0).get("type"));
    }

    @Test
    void createPreview_persistsDraftDeclarationsForPreviewPlans() {
        PlannedCommand create = createDraftPipelineCommand(tenant.getId(), "draft:pipeline:1");

        Plan plan = planService.createPreview(
                tenant.getId(), null, actor.getId(), "Draft preview", List.of(create));

        assertEquals(1, plan.getDraftRefDeclarations().size());
        assertEquals("draft:pipeline:1", plan.getDraftRefDeclarations().get(0).get("draftRef"));
        assertTrue(plan.getDraftRefBindings().isEmpty(), "preview starts with no bindings");
    }

    // ---------------------------------------------------------------------
    //  Lifecycle: createPreview → apply → APPLIED
    //
    //  Asserts the implemented PREVIEW → APPLYING → APPLIED flow. The packet
    //  describes desired naming as "PENDING → EXECUTING → SUCCEEDED"; that
    //  rename is a separate fix ticket (see class javadoc).
    // ---------------------------------------------------------------------

    @Test
    void apply_happyPath_movesPlanThroughLifecycleAndRecordsCommandIds() {
        String commandType = "Plan.Apply.Happy." + SeedFixtures.nextSuffix();
        commandService.registerHandler(commandType, cmd -> Map.of("ok", true));

        Plan plan = planService.createPreview(tenant.getId(), "pipeline-1", actor.getId(),
                "Apply happy path",
                List.of(new PlannedCommand(commandType, "Pipeline", "pipe-1", "step 1", Map.of())));
        assertEquals(PlanStatus.PREVIEW, plan.getStatus(), "starts PREVIEW");

        Plan applied = planService.apply(plan.getId(), tenant.getId(), actor.getId(),
                List.of(new PlannedCommand(commandType, "Pipeline", "pipe-1", "step 1", Map.of())));

        assertEquals(PlanStatus.APPLIED, applied.getStatus(),
                "success path lands on APPLIED (today's analogue of the packet's SUCCEEDED)");
        assertNotNull(applied.getAppliedAt(), "appliedAt timestamp set on terminal transition");
        assertEquals(1, applied.getCommandIds().size(), "executed command id captured");
    }

    @Test
    void apply_draftPipelineHappyPath_bindsRealIdAndSubstitutesLaterCommands() {
        AtomicReference<CommandLog> downstreamSeen = new AtomicReference<>();
        String inspectType = "Plan.Apply.Inspect." + SeedFixtures.nextSuffix();
        commandService.registerHandler(inspectType, cmd -> {
            downstreamSeen.set(cmd);
            return Map.of("ok", true);
        });

        PlannedCommand create = createDraftPipelineCommand(tenant.getId(), "draft:pipeline:1");
        PlannedCommand inspect = new PlannedCommand(
                inspectType,
                "Pipeline",
                "draft:pipeline:1",
                "Inspect created pipeline",
                Map.of(
                        "pipelineId", "draft:pipeline:1",
                        "nested", List.of("draft:pipeline:1", Map.of("pipelineId", "draft:pipeline:1"))));

        Plan plan = planService.createPreview(
                tenant.getId(), null, actor.getId(), "Draft happy path", List.of(create, inspect));

        Plan applied = planService.apply(plan.getId(), tenant.getId(), actor.getId(), List.of(create, inspect));

        assertEquals(PlanStatus.APPLIED, applied.getStatus());
        assertEquals(1, applied.getDraftRefBindings().size(), "binding persisted on the plan");
        String realPipelineId = String.valueOf(applied.getDraftRefBindings().get(0).get("realId"));

        CommandLog seen = downstreamSeen.get();
        assertNotNull(seen, "downstream handler must receive the substituted command");
        assertEquals(realPipelineId, seen.getAggregateId(), "aggregateId substituted before execution");
        assertEquals(realPipelineId, seen.getPayload().get("pipelineId"), "payload scalar substituted");
        assertEquals(realPipelineId,
                ((Map<?, ?>) ((List<?>) seen.getPayload().get("nested")).get(1)).get("pipelineId"),
                "nested payload values substituted");

        List<CommandLog> rows = commandService.listByPlan(plan.getId());
        assertEquals(realPipelineId, rows.get(0).getAggregateId(),
                "create command aggregateId is rewritten from draft ref to real id");
        assertEquals(realPipelineId, rows.get(0).getResultPayload().get("createdAggregateId"));
    }

    @Test
    void apply_draftConnectorUiIntent_resolvesToRealConnectorIdAfterBinding() throws Exception {
        String createType = "Plan.Apply.CreateConnector." + SeedFixtures.nextSuffix();
        String realConnectorId = "connector-real-" + SeedFixtures.nextSuffix();
        commandService.registerHandler(createType, cmd -> Map.of(
                "createdAggregateType", "connector",
                "createdAggregateId", realConnectorId));

        PlannedCommand create = new PlannedCommand(
                createType,
                "Connector",
                "draft:connector:1",
                "Create external connector",
                Map.of("name", "Loan DB", "config", Map.of("host", "db.internal")),
                List.of(new PlanService.CommandOutput("draft:connector:1", "aggregateId")),
                Map.of(
                        "kind", "credential_attach",
                        "connectorInstanceId", "draft:connector:1",
                        "environment", "DEV"));

        Plan plan = planService.createPreview(
                tenant.getId(), null, actor.getId(), "Connector draft intent", List.of(create));
        Plan applied = planService.apply(plan.getId(), tenant.getId(), actor.getId(), List.of(create));

        assertEquals(PlanStatus.APPLIED, applied.getStatus());
        String appliedIntents = String.valueOf(applied.getPreviewData().get("appliedUiIntents"));
        assertTrue(appliedIntents.contains("credential_attach"));
        assertTrue(appliedIntents.contains(realConnectorId));
        assertTrue(appliedIntents.contains("DEV"));
        assertTrue(!applied.getDraftRefBindings().toString().contains("password"),
                "draft bindings must not carry secrets");
    }

    @Test
    void apply_handlerFailureMidPlan_marksPlanFailedAndStopsExecution() {
        String okType = "Plan.Apply.OK." + SeedFixtures.nextSuffix();
        String failType = "Plan.Apply.Fail." + SeedFixtures.nextSuffix();
        commandService.registerHandler(okType, cmd -> Map.of());
        commandService.registerHandler(failType, cmd -> { throw new RuntimeException("nope"); });

        List<PlannedCommand> cmds = List.of(
                new PlannedCommand(okType, "Pipeline", "pipe-1", "step 1", Map.of()),
                new PlannedCommand(failType, "Pipeline", "pipe-1", "step 2", Map.of()),
                new PlannedCommand(okType, "Pipeline", "pipe-1", "step 3 — should be skipped", Map.of()));

        Plan plan = planService.createPreview(tenant.getId(), "pipeline-1", actor.getId(),
                "Mixed plan", cmds);
        Plan applied = planService.apply(plan.getId(), tenant.getId(), actor.getId(), cmds);

        assertEquals(PlanStatus.FAILED, applied.getStatus(), "failure mid-plan flips status to FAILED");
        // Only the first two commands ran — apply stops at the first FAILED step so the third
        // command never executes (chat UI relies on this to surface "rolled back here").
        assertEquals(2, applied.getCommandIds().size(),
                "apply halts at first failure — third command never executes");
    }

    @Test
    void apply_failureAfterBinding_persistsEarlierBindingAndRejectsResume() {
        String createType = "Plan.Apply.CreateForFailure." + SeedFixtures.nextSuffix();
        String failType = "Plan.Apply.FailAfterBinding." + SeedFixtures.nextSuffix();
        commandService.registerHandler(createType, cmd -> Map.of(
                "createdAggregateType", "pipeline",
                "createdAggregateId", "pipeline-real-" + SeedFixtures.nextSuffix()));
        commandService.registerHandler(failType, cmd -> { throw new RuntimeException("nope"); });

        PlannedCommand create = new PlannedCommand(
                createType,
                "Pipeline",
                "draft:pipeline:1",
                "Create draft pipeline",
                Map.of("name", "Loan Master"),
                List.of(new PlanService.CommandOutput("draft:pipeline:1", "aggregateId")),
                Map.of());
        PlannedCommand fail = new PlannedCommand(
                failType,
                "Pipeline",
                "draft:pipeline:1",
                "fail after create",
                Map.of("pipelineId", "draft:pipeline:1"));

        String sessionId = "session-" + SeedFixtures.nextSuffix();
        Plan preview = planService.createForSession(
                tenant.getId(), null, sessionId,
                actor.getId(), "Draft fail path", List.of(create, fail));
        ChatMessage approval = new ChatMessage();
        approval.setSessionId(sessionId);
        approval.setRole("USER");
        approval.setContent("approve");
        approval.setPlanId(preview.getId());
        approval = chatMessageRepository.save(approval);
        planService.approve(preview.getId(), approval.getId(), actor.getId());

        Plan failed = planService.apply(preview.getId());

        assertEquals(PlanStatus.FAILED, failed.getStatus());
        assertEquals(2, failed.getCommandIds().size(), "create and failing commands are both recorded");
        assertEquals(1, failed.getDraftRefBindings().size(), "successful create binding survives failure");

        IllegalArgumentException resumeEx = assertThrows(IllegalArgumentException.class,
                () -> planService.apply(preview.getId()));
        assertTrue(resumeEx.getMessage().contains("PLAN_DRAFT_REF_NON_RESUMABLE"));
    }

    @Test
    void apply_missingCreatedAggregateId_marksCommandAndPlanFailed() {
        String createType = "Plan.Apply.MissingResult." + SeedFixtures.nextSuffix();
        commandService.registerHandler(createType, cmd -> Map.of("createdAggregateType", "pipeline"));

        PlannedCommand create = new PlannedCommand(
                createType,
                "Pipeline",
                "draft:pipeline:1",
                "create without createdAggregateId",
                Map.of("name", "Loan Master"),
                List.of(new PlanService.CommandOutput("draft:pipeline:1", "aggregateId")),
                Map.of());

        Plan plan = planService.createPreview(tenant.getId(), null, actor.getId(), "Missing result", List.of(create));
        Plan failed = planService.apply(plan.getId(), tenant.getId(), actor.getId(), List.of(create));

        assertEquals(PlanStatus.FAILED, failed.getStatus());
        assertTrue(failed.getDraftRefBindings().isEmpty(), "missing result must not persist a binding");
        CommandLog row = commandService.listByPlan(plan.getId()).get(0);
        assertEquals(CommandStatus.FAILED, row.getStatus());
        assertTrue(row.getErrorMessage().contains("PLAN_DRAFT_REF_RESULT_MISSING"));
    }

    @Test
    void apply_existingBindingConflict_marksPlanFailed() {
        PlannedCommand create = createDraftPipelineCommand(tenant.getId(), "draft:pipeline:1");
        Plan plan = planService.createPreview(tenant.getId(), null, actor.getId(), "Conflict", List.of(create));
        plan.setDraftRefBindings(List.of(Map.of(
                "draftRef", "draft:pipeline:1",
                "aggregateType", "pipeline",
                "realId", "pipeline-existing",
                "boundByCommandIndex", 0,
                "boundByCommandId", "cmd-existing",
                "boundAt", "2026-05-14T12:00:00Z"
        )));
        planRepository.save(plan);

        Plan failed = planService.apply(plan.getId(), tenant.getId(), actor.getId(), List.of(create));

        assertEquals(PlanStatus.FAILED, failed.getStatus());
        CommandLog row = commandService.listByPlan(plan.getId()).get(0);
        assertEquals(CommandStatus.FAILED, row.getStatus());
        assertTrue(row.getErrorMessage().contains("PLAN_DRAFT_REF_BINDING_CONFLICT"));
    }

    @Test
    void apply_planNotInPreview_isRejectedWithClearError() {
        Plan plan = planService.createPreview(tenant.getId(), "pipeline-1", actor.getId(),
                "Initial", List.of());

        // First apply succeeds and moves to APPLIED.
        Plan applied = planService.apply(plan.getId(), tenant.getId(), actor.getId(), List.of());
        assertEquals(PlanStatus.APPLIED, applied.getStatus());

        // Second apply must be rejected — transitioning APPLIED back to APPLYING would corrupt the audit trail.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> planService.apply(plan.getId(), tenant.getId(), actor.getId(), List.of()),
                "applying a non-PREVIEW plan must throw");
        assertTrue(ex.getMessage().contains("PREVIEW"), "message names the required source state");
    }

    // ---------------------------------------------------------------------
    //  cancel — current impl uses CANCELLED, not REJECTED
    // ---------------------------------------------------------------------

    @Test
    void cancel_previewPlan_movesItToCancelled() {
        Plan plan = planService.createPreview(tenant.getId(), "pipeline-1", actor.getId(),
                "To be cancelled", List.of());

        Plan cancelled = planService.cancel(plan.getId());

        assertEquals(PlanStatus.CANCELLED, cancelled.getStatus(),
                "cancel from PREVIEW lands on CANCELLED (today's analogue of REJECTED)");
        // Repository read back confirms persistence (defensive — service returns the saved row).
        Plan reloaded = planRepository.findById(plan.getId()).orElseThrow();
        assertEquals(PlanStatus.CANCELLED, reloaded.getStatus(), "persisted CANCELLED state survives reload");
    }

    @Test
    void cancel_alreadyAppliedPlan_throwsRatherThanSilentlyOverwriting() {
        // Negative case from the packet: "Cancel from SUCCEEDED state should be a no-op or throw,
        // not silent overwrite". Today's implementation throws — assert that.
        Plan plan = planService.createPreview(tenant.getId(), "pipeline-1", actor.getId(),
                "To be applied then cancelled", List.of());
        Plan applied = planService.apply(plan.getId(), tenant.getId(), actor.getId(), List.of());
        assertEquals(PlanStatus.APPLIED, applied.getStatus());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> planService.cancel(plan.getId()),
                "cancel of APPLIED plan must throw — silent overwrite would corrupt audit trail");
        assertTrue(ex.getMessage().contains("PREVIEW"), "error names the required source state");
    }

    @Test
    void cancel_unknownPlanId_throwsResourceNotFound() {
        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> planService.cancel("plan-does-not-exist"),
                "unknown planId surfaces 404 (resource-not-found) via global handler");
        assertTrue(ex.getMessage().contains("Plan"), "message names the resource type");
        assertTrue(ex.getMessage().contains("plan-does-not-exist"), "message includes the requested id");
    }

    // ---------------------------------------------------------------------
    //  list ordering & tenant isolation
    // ---------------------------------------------------------------------

    @Test
    void listByTenant_ordersByCreatedAtDescAndScopesToTenant() {
        // Two plans for this tenant.
        Plan first = planService.createPreview(tenant.getId(), "pipeline-1", actor.getId(),
                "first", List.of());
        Plan second = planService.createPreview(tenant.getId(), "pipeline-1", actor.getId(),
                "second", List.of());
        // One plan for a different tenant — must not leak.
        Tenant other = seedFixtures.seedTenant();
        PulseUser otherActor = seedFixtures.seedUser(other.getId());
        Plan otherPlan = planService.createPreview(other.getId(), "pipeline-99", otherActor.getId(),
                "other tenant", List.of());

        List<Plan> rows = planService.listByTenant(tenant.getId());

        assertEquals(2, rows.size(), "only the calling tenant's plans");
        assertEquals(second.getId(), rows.get(0).getId(), "newest first (createdAt desc)");
        assertEquals(first.getId(), rows.get(1).getId(), "oldest last");
        for (Plan p : rows) {
            assertEquals(tenant.getId(), p.getTenantId(), "no cross-tenant leak");
            assertTrue(!p.getId().equals(otherPlan.getId()),
                    "other tenant's plan must not appear");
        }
    }

    @Test
    void listByPipeline_returnsPlansForGivenPipelineOnly() {
        Plan p1 = planService.createPreview(tenant.getId(), "pipeline-A", actor.getId(),
                "first", List.of());
        Plan p2 = planService.createPreview(tenant.getId(), "pipeline-A", actor.getId(),
                "second", List.of());
        Plan p3 = planService.createPreview(tenant.getId(), "pipeline-B", actor.getId(),
                "other pipeline", List.of());

        List<Plan> aPlans = planService.listByPipeline("pipeline-A");
        assertEquals(2, aPlans.size(), "only plans for pipeline-A");
        // Ordered desc.
        assertEquals(p2.getId(), aPlans.get(0).getId(), "newest first");
        assertEquals(p1.getId(), aPlans.get(1).getId(), "oldest last");
        for (Plan p : aPlans) {
            assertTrue(!p.getId().equals(p3.getId()), "pipeline-B plan never leaks");
        }
    }

    // ---------------------------------------------------------------------
    //  get(planId)
    // ---------------------------------------------------------------------

    @Test
    void get_unknownPlanId_throwsResourceNotFound() {
        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> planService.get("missing"),
                "unknown id throws ResourceNotFoundException — drives 404 at the controller");
        assertTrue(ex.getMessage().contains("Plan"));
    }

    @Test
    void get_persistedPlanId_returnsThePersistedRow() {
        Plan plan = planService.createPreview(tenant.getId(), "pipeline-1", actor.getId(),
                "round-trip", List.of());

        Plan loaded = planService.get(plan.getId());
        assertEquals(plan.getId(), loaded.getId());
        assertEquals(PlanStatus.PREVIEW, loaded.getStatus());
    }

    // ---------------------------------------------------------------------
    //  Integration with CommandService — apply persists CommandLog rows
    //  with the correct plan id, so PlanController#getPlanCommands works.
    // ---------------------------------------------------------------------

    @Test
    void apply_persistsCommandLogRowsLinkedToPlan() {
        String type = "Plan.LinkedCommands." + SeedFixtures.nextSuffix();
        commandService.registerHandler(type, cmd -> Map.of());

        List<PlannedCommand> cmds = List.of(
                new PlannedCommand(type, "Pipeline", "pipe-1", "step 1", Map.of()),
                new PlannedCommand(type, "Pipeline", "pipe-2", "step 2", Map.of()));

        Plan plan = planService.createPreview(tenant.getId(), "pipeline-1", actor.getId(),
                "linked", cmds);
        Plan applied = planService.apply(plan.getId(), tenant.getId(), actor.getId(), cmds);
        assertEquals(PlanStatus.APPLIED, applied.getStatus());
        assertEquals(2, applied.getCommandIds().size());

        // Audit trail: each CommandLog row carries the plan id and SUCCEEDED status.
        var rows = commandService.listByPlan(plan.getId());
        assertEquals(2, rows.size(), "two command rows tied to plan");
        rows.forEach(row -> {
            assertEquals(plan.getId(), row.getPlanId(), "plan id stamped on each command");
            assertEquals(CommandStatus.SUCCEEDED, row.getStatus());
        });
    }

    @Test
    void pipelineCreate_commandResultPayloadCarriesCreatedAggregateId() {
        var domain = seedFixtures.seedDomain(tenant.getId());

        CommandLog cmd = commandService.execute(
                PipelineCommandHandlers.CREATE_PIPELINE,
                "Pipeline",
                "draft:pipeline:1",
                tenant.getId(),
                actor.getId(),
                null,
                Map.of(
                        "name", "Loan Master",
                        "description", "test",
                        "domainName", domain.getName(),
                        "domainId", domain.getId(),
                        "defaultStorageBackend", "DPC"));

        assertEquals(CommandStatus.SUCCEEDED, cmd.getStatus());
        assertNotNull(cmd.getResultPayload());
        assertEquals("pipeline", cmd.getResultPayload().get("createdAggregateType"));
        assertNotNull(cmd.getResultPayload().get("createdAggregateId"));
    }

    private PlannedCommand createDraftPipelineCommand(String tenantId, String draftRef) {
        var domain = seedFixtures.seedDomain(tenantId);
        return new PlannedCommand(
                PipelineCommandHandlers.CREATE_PIPELINE,
                "Pipeline",
                draftRef,
                "Create draft pipeline",
                Map.of(
                        "name", "Loan Master " + SeedFixtures.nextSuffix(),
                        "description", "draft pipeline",
                        "domainName", domain.getName(),
                        "domainId", domain.getId(),
                        "defaultStorageBackend", "DPC"),
                List.of(new PlanService.CommandOutput(draftRef, "aggregateId")),
                Map.of());
    }
}
