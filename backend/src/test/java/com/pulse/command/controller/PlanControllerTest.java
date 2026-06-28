package com.pulse.command.controller;

import com.pulse.auth.model.PulseUser;
import com.pulse.auth.model.Tenant;
import com.pulse.auth.repository.TenantRepository;
import com.pulse.auth.repository.UserRepository;
import com.pulse.blueprint.repository.BlueprintRepository;
import com.pulse.command.model.Plan;
import com.pulse.command.model.PlanStatus;
import com.pulse.command.service.CommandService;
import com.pulse.command.service.PlanService;
import com.pulse.command.service.PlanService.PlannedCommand;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller contract tests for {@link PlanController}: list, get, getPlanCommands, and the
 * cancel endpoint.
 *
 * <p><strong>Behavioural note.</strong> The packet describes cancel as moving a plan to
 * {@code REJECTED}; the current implementation uses {@link PlanStatus#CANCELLED}. Per the
 * packet's risk-mitigation guidance these tests assert the implemented behaviour; the rename
 * is a separate fix ticket. Similarly the packet describes cancel as "idempotent on already-
 * cancelled plan" — today's implementation throws on a second cancel (since the second call
 * sees status != PREVIEW) → these tests assert today's 400 response and call out the gap.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlanControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private PlanService planService;
    @Autowired private CommandService commandService;

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
        seedFixtures = new SeedFixtures(
                tenantRepository, userRepository, domainRepository, sorRepository,
                connectorDefinitionRepository, connectorInstanceRepository, datasetRepository,
                blueprintRepository, pipelineRepository, pipelineVersionRepository,
                subPipelineInstanceRepository, portWiringRepository);
        tenant = seedFixtures.seedTenant();
        actor = seedFixtures.seedUser(tenant.getId());
    }

    // ---------------------------------------------------------------------
    //  list plans
    // ---------------------------------------------------------------------

    @Test
    void listPlans_returnsTenantScopedPlansOrderedByCreatedAtDesc() throws Exception {
        Plan first = planService.createPreview(tenant.getId(), "pipeline-1", actor.getId(),
                "first", List.of());
        Plan second = planService.createPreview(tenant.getId(), "pipeline-1", actor.getId(),
                "second", List.of());

        // Different tenant — must not leak.
        Tenant other = seedFixtures.seedTenant();
        PulseUser otherActor = seedFixtures.seedUser(other.getId());
        planService.createPreview(other.getId(), "pipeline-1", otherActor.getId(),
                "other tenant", List.of());

        mockMvc.perform(get("/api/v1/tenants/{tenantId}/plans", tenant.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", is(2)))
                .andExpect(jsonPath("$[0].id", is(second.getId())))
                .andExpect(jsonPath("$[1].id", is(first.getId())))
                .andExpect(jsonPath("$[0].tenantId", is(tenant.getId())))
                .andExpect(jsonPath("$[0].status", is("PREVIEW")));
    }

    @Test
    void listPlans_withPipelineFilter_returnsOnlyMatchingPipelinePlans() throws Exception {
        planService.createPreview(tenant.getId(), "pipeline-A", actor.getId(), "a-1", List.of());
        planService.createPreview(tenant.getId(), "pipeline-A", actor.getId(), "a-2", List.of());
        planService.createPreview(tenant.getId(), "pipeline-B", actor.getId(), "b-1", List.of());

        mockMvc.perform(get("/api/v1/tenants/{tenantId}/plans", tenant.getId())
                        .param("pipelineId", "pipeline-A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", is(2)))
                .andExpect(jsonPath("$[0].pipelineId", is("pipeline-A")))
                .andExpect(jsonPath("$[1].pipelineId", is("pipeline-A")));
    }

    // ---------------------------------------------------------------------
    //  get plan
    // ---------------------------------------------------------------------

    @Test
    void getPlan_existingId_returns200WithPlanBody() throws Exception {
        Plan plan = planService.createPreview(tenant.getId(), "pipeline-1", actor.getId(),
                "round-trip", List.of(
                        new PlannedCommand("Pipeline.Create", "Pipeline", "pipe-1",
                                "create", Map.of("name", "loan_master"))));

        mockMvc.perform(get("/api/v1/tenants/{tenantId}/plans/{planId}",
                        tenant.getId(), plan.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(plan.getId())))
                .andExpect(jsonPath("$.status", is("PREVIEW")))
                .andExpect(jsonPath("$.description", is("round-trip")))
                .andExpect(jsonPath("$.previewData.commandCount", is(1)));
    }

    @Test
    void getPlan_unknownId_returns404ViaGlobalExceptionHandler() throws Exception {
        mockMvc.perform(get("/api/v1/tenants/{tenantId}/plans/{planId}",
                        tenant.getId(), "plan-does-not-exist"))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------------
    //  get plan commands
    // ---------------------------------------------------------------------

    @Test
    void getPlanCommands_afterApply_returnsExecutedCommandsInOrder() throws Exception {
        String type = "PlanCtrl.Cmds." + SeedFixtures.nextSuffix();
        commandService.registerHandler(type, cmd -> Map.of());

        List<PlannedCommand> cmds = List.of(
                new PlannedCommand(type, "Pipeline", "pipe-1", "step 1", Map.of()),
                new PlannedCommand(type, "Pipeline", "pipe-2", "step 2", Map.of()));

        Plan plan = planService.createPreview(tenant.getId(), "pipeline-1", actor.getId(),
                "linked", cmds);
        planService.apply(plan.getId(), tenant.getId(), actor.getId(), cmds);

        mockMvc.perform(get("/api/v1/tenants/{tenantId}/plans/{planId}/commands",
                        tenant.getId(), plan.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", is(2)))
                // Ascending by createdAt — execution order.
                .andExpect(jsonPath("$[0].planId", is(plan.getId())))
                .andExpect(jsonPath("$[0].aggregateId", is("pipe-1")))
                .andExpect(jsonPath("$[1].aggregateId", is("pipe-2")))
                .andExpect(jsonPath("$[0].status", is("SUCCEEDED")));
    }

    @Test
    void getPlanCommands_planWithNoExecutedCommandsYet_returnsEmptyArray() throws Exception {
        // A PREVIEW plan has no executed commands yet — must return [] not null/500.
        Plan plan = planService.createPreview(tenant.getId(), "pipeline-1", actor.getId(),
                "preview only", List.of());

        mockMvc.perform(get("/api/v1/tenants/{tenantId}/plans/{planId}/commands",
                        tenant.getId(), plan.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", is(0)));
    }

    // ---------------------------------------------------------------------
    //  TC_plan_controller_cancel
    // ---------------------------------------------------------------------

    @Test
    void cancelPlan_previewPlan_returns200WithUpdatedStatus() throws Exception {
        Plan plan = planService.createPreview(tenant.getId(), "pipeline-1", actor.getId(),
                "to cancel", List.of());

        mockMvc.perform(post("/api/v1/tenants/{tenantId}/plans/{planId}/cancel",
                        tenant.getId(), plan.getId()))
                .andExpect(status().isOk())
                // Today's implementation uses CANCELLED; the packet's "REJECTED" naming
                // is a separate rename ticket called out in the class javadoc.
                .andExpect(jsonPath("$.status", is("CANCELLED")))
                .andExpect(jsonPath("$.id", is(plan.getId())));

        // Subsequent GET on the plan returns the cancelled state.
        mockMvc.perform(get("/api/v1/tenants/{tenantId}/plans/{planId}",
                        tenant.getId(), plan.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("CANCELLED")));
    }

    @Test
    void cancelPlan_alreadyCancelled_currentlyReturns400_documentsIdempotencyGap() throws Exception {
        // Packet wording: "Calling cancel again on the same plan returns 200 (idempotent)".
        // Today's implementation throws because the second call sees status != PREVIEW, which
        // GlobalExceptionHandler maps to HTTP 400. This regression test pins the current
        // contract so when idempotent cancel ships the assertion below must be updated to
        // expect 200 and assert the body still reports CANCELLED.
        Plan plan = planService.createPreview(tenant.getId(), "pipeline-1", actor.getId(),
                "to double cancel", List.of());

        mockMvc.perform(post("/api/v1/tenants/{tenantId}/plans/{planId}/cancel",
                        tenant.getId(), plan.getId()))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/tenants/{tenantId}/plans/{planId}/cancel",
                        tenant.getId(), plan.getId()))
                // Implemented behaviour today — IllegalArgumentException → 400 via GlobalExceptionHandler.
                .andExpect(status().isBadRequest());
    }

    @Test
    void cancelPlan_alreadyAppliedPlan_returns400_clearError() throws Exception {
        // Packet wording: "Cancel of SUCCEEDED plan returns 409 with a clear error".
        // Today's implementation throws IllegalArgumentException → 400 via GlobalExceptionHandler.
        // Documenting the implemented status code; the 409 upgrade is a separate fix ticket.
        Plan plan = planService.createPreview(tenant.getId(), "pipeline-1", actor.getId(),
                "applied then cancel", List.of());
        planService.apply(plan.getId(), tenant.getId(), actor.getId(), List.of());

        mockMvc.perform(post("/api/v1/tenants/{tenantId}/plans/{planId}/cancel",
                        tenant.getId(), plan.getId()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cancelPlan_unknownPlanId_returns404() throws Exception {
        mockMvc.perform(post("/api/v1/tenants/{tenantId}/plans/{planId}/cancel",
                        tenant.getId(), "plan-does-not-exist"))
                .andExpect(status().isNotFound());
    }
}
