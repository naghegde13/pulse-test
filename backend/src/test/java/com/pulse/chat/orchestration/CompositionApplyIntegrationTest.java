package com.pulse.chat.orchestration;

import com.pulse.auth.model.PulseUser;
import com.pulse.auth.model.Tenant;
import com.pulse.auth.repository.TenantRepository;
import com.pulse.auth.repository.UserRepository;
import com.pulse.blueprint.model.BlueprintCategory;
import com.pulse.blueprint.repository.BlueprintRepository;
import com.pulse.chat.model.ChatMessage;
import com.pulse.chat.repository.ChatMessageRepository;
import com.pulse.chat.repository.ChatSessionRepository;
import com.pulse.chat.model.ChatSession;
import com.pulse.command.handler.CompositionCommandHandlers;
import com.pulse.command.model.CommandLog;
import com.pulse.command.model.Plan;
import com.pulse.command.model.PlanStatus;
import com.pulse.command.repository.PlanRepository;
import com.pulse.command.service.CommandService;
import com.pulse.command.service.PlanService;
import com.pulse.command.service.PlanService.PlannedCommand;
import com.pulse.pipeline.repository.PipelineRepository;
import com.pulse.pipeline.repository.PipelineVersionRepository;
import com.pulse.pipeline.repository.PortWiringRepository;
import com.pulse.pipeline.repository.SubPipelineInstanceRepository;
import com.pulse.pipeline.service.CompositionService;
import com.pulse.sor.model.Domain;
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
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P4 — Command-Logged Apply (H2 @SpringBootTest). Asserts {@code apply_plan}
 * writes the canonical composition AND one Command-Log row per staged op via the
 * six {@code composition.*} command types under one shared {@code planId}; and
 * that non-APPROVED / cross-session decisions are rejected without side effects.
 */
@SpringBootTest
@ActiveProfiles("test")
class CompositionApplyIntegrationTest {

    @Autowired private PlanService planService;
    @Autowired private CommandService commandService;
    @Autowired private CompositionService compositionService;
    @Autowired private PlanRepository planRepository;
    @Autowired private ChatMessageRepository chatMessageRepository;
    @Autowired private ChatSessionRepository chatSessionRepository;

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

    private SeedFixtures seed;
    private Tenant tenant;
    private PulseUser actor;
    private Domain domain;
    private String pipelineId;
    private String versionId;
    private String sessionId;

    @BeforeEach
    void setUp() {
        chatMessageRepository.deleteAll();
        planRepository.deleteAll();
        seed = new SeedFixtures(
                tenantRepository, userRepository, domainRepository, sorRepository,
                connectorDefinitionRepository, connectorInstanceRepository, datasetRepository,
                blueprintRepository, pipelineRepository, pipelineVersionRepository,
                subPipelineInstanceRepository, portWiringRepository);
        tenant = seed.seedTenant();
        actor = seed.seedUser(tenant.getId());
        domain = seed.seedDomain(tenant.getId());

        // Two blueprints with ports so wirePort validation passes.
        seed.seedBlueprint("SeedSource", BlueprintCategory.INGESTION,
                List.of(), List.of(Map.of("name", "out")));
        seed.seedBlueprint("SeedSink", BlueprintCategory.TRANSFORM,
                List.of(Map.of("name", "in")), List.of());

        var pwv = seed.seedPipelineWithVersion(tenant.getId(), domain.getId(), domain.getName());
        pipelineId = pwv.pipeline().getId();
        versionId = pwv.version().getId();

        ChatSession session = new ChatSession();
        session.setTenantId(tenant.getId());
        session.setUserId(actor.getId());
        session.setPipelineId(pipelineId);
        sessionId = chatSessionRepository.save(session).getId();
    }

    private List<PlannedCommand> stagedCommands() {
        List<PlanOperation> ops = List.of(
                new PlanOperation.AddInstances(List.of(
                        new PlanOperation.InstanceSpec("read", "SeedSource", "DPC", null, null),
                        new PlanOperation.InstanceSpec("clean", "SeedSink", "DPC", null, null)), "add two"),
                new PlanOperation.MergeWiring(List.of(
                        new PlanOperation.WireSpec("read", "out", "clean", "in")), "wire"),
                new PlanOperation.UpdateInstance("clean", Map.of("filter", "x>0"), null, null, null, "params"),
                new PlanOperation.Rename("clean", "cleaned", "rename"),
                new PlanOperation.RemoveWiring(new PlanOperation.WireSpec("read", "out", "cleaned", "in"), "unwire"),
                new PlanOperation.RemoveInstance("cleaned", "drop"));
        return OpToCommandMapper.toCommands(ops, pipelineId, versionId);
    }

    @Test
    void applyWritesCanonicalAndSixCompositionCommandTypesUnderOnePlanId() {
        Plan plan = planService.createForSession(tenant.getId(), pipelineId, sessionId,
                actor.getId(), "compose", stagedCommands());
        assertEquals(PlanStatus.PREVIEW, plan.getStatus());

        planService.approveForSession(plan.getId(), sessionId, actor.getId());
        Plan applied = planService.apply(plan.getId());
        assertEquals(PlanStatus.APPLIED, applied.getStatus(), "apply lands on APPLIED");

        // One Command-Log group under the single planId.
        List<CommandLog> rows = commandService.listByPlan(plan.getId());
        assertTrue(rows.stream().allMatch(r -> plan.getId().equals(r.getPlanId())),
                "every Command-Log row carries the same planId (one turn = one undo unit)");

        Set<String> types = rows.stream().map(CommandLog::getCommandType).collect(Collectors.toSet());
        // The 6 composition.* command types this plan exercised:
        assertTrue(types.contains(CompositionCommandHandlers.ADD_INSTANCE));
        assertTrue(types.contains(CompositionCommandHandlers.WIRE_PORTS));
        assertTrue(types.contains(CompositionCommandHandlers.UPDATE_INSTANCE));
        assertTrue(types.contains(CompositionCommandHandlers.RENAME_INSTANCE));
        assertTrue(types.contains(CompositionCommandHandlers.REMOVE_WIRING));
        assertTrue(types.contains(CompositionCommandHandlers.REMOVE_INSTANCE));

        // Canonical reflects the net result: read remains; cleaned removed; no wirings.
        var view = compositionService.getComposition(versionId);
        Set<String> names = view.instances().stream()
                .map(com.pulse.pipeline.model.SubPipelineInstance::getName)
                .collect(Collectors.toSet());
        assertTrue(names.contains("read"), "added 'read' persisted to canonical");
        assertTrue(!names.contains("cleaned"), "removed 'cleaned' is gone from canonical");
        assertTrue(view.wirings().isEmpty(), "the wiring was added then removed -> none remain");
    }

    @Test
    void nonApprovedApplyRejectedWithoutSideEffects() {
        Plan plan = planService.createForSession(tenant.getId(), pipelineId, sessionId,
                actor.getId(), "compose", stagedCommands());
        // Apply without approving (still PREVIEW): the session-scoped apply path
        // requires APPROVED.
        assertThrows(IllegalArgumentException.class, () -> planService.apply(plan.getId()));
        // No canonical instances written.
        assertTrue(compositionService.getComposition(versionId).instances().isEmpty(),
                "no canonical mutation on a non-APPROVED apply");
        assertTrue(commandService.listByPlan(plan.getId()).isEmpty(),
                "no Command-Log rows on a non-APPROVED apply");
    }

    @Test
    void crossSessionDecisionRejectedWithoutSideEffects() {
        Plan plan = planService.createForSession(tenant.getId(), pipelineId, sessionId,
                actor.getId(), "compose", stagedCommands());
        assertThrows(IllegalArgumentException.class,
                () -> planService.approveForSession(plan.getId(), "some-other-session", actor.getId()));
        assertEquals(PlanStatus.PREVIEW, planService.get(plan.getId()).getStatus(),
                "cross-session decision leaves the plan untouched");
        assertTrue(compositionService.getComposition(versionId).instances().isEmpty());
    }
}
