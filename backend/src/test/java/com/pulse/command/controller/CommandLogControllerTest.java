package com.pulse.command.controller;

import com.pulse.auth.model.PulseUser;
import com.pulse.auth.model.Tenant;
import com.pulse.auth.repository.TenantRepository;
import com.pulse.auth.repository.UserRepository;
import com.pulse.blueprint.repository.BlueprintRepository;
import com.pulse.command.model.CommandLog;
import com.pulse.command.service.CommandService;
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
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller contract tests for {@link CommandLogController}. Asserts the HTTP shape the
 * frontend /commands page consumes: tenant-scoped listing, ordering, and aggregate-id filter.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CommandLogControllerTest {

    @Autowired private MockMvc mockMvc;
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
    private Tenant tenantA;
    private Tenant tenantB;
    private PulseUser actorA;
    private PulseUser actorB;

    @BeforeEach
    void seedIdentity() {
        seedFixtures = new SeedFixtures(
                tenantRepository, userRepository, domainRepository, sorRepository,
                connectorDefinitionRepository, connectorInstanceRepository, datasetRepository,
                blueprintRepository, pipelineRepository, pipelineVersionRepository,
                subPipelineInstanceRepository, portWiringRepository);
        tenantA = seedFixtures.seedTenant();
        tenantB = seedFixtures.seedTenant();
        actorA = seedFixtures.seedUser(tenantA.getId());
        actorB = seedFixtures.seedUser(tenantB.getId());
    }

    // ---------------------------------------------------------------------
    //  TC_command_log_controller_list_by_tenant
    // ---------------------------------------------------------------------

    @Test
    void listCommands_returnsOnlyCallingTenantsRows_orderedByCreatedAtDesc() throws Exception {
        String type = "TC.api.list." + SeedFixtures.nextSuffix();
        commandService.registerHandler(type, cmd -> Map.of("ok", true));

        // Two commands for tenant A, one for tenant B.
        CommandLog a1 = commandService.execute(type, "Pipeline", "agg-a-1",
                tenantA.getId(), actorA.getId(), null, Map.of());
        CommandLog a2 = commandService.execute(type, "Pipeline", "agg-a-2",
                tenantA.getId(), actorA.getId(), null, Map.of());
        CommandLog b1 = commandService.execute(type, "Pipeline", "agg-b-1",
                tenantB.getId(), actorB.getId(), null, Map.of());

        mockMvc.perform(get("/api/v1/tenants/{tenantId}/commands", tenantA.getId()))
                .andExpect(status().isOk())
                // No cross-tenant leak: exactly tenant A's two rows.
                .andExpect(jsonPath("$.length()", is(2)))
                // Newest first (createdAt desc).
                .andExpect(jsonPath("$[0].id", is(a2.getId())))
                .andExpect(jsonPath("$[1].id", is(a1.getId())))
                // Response shape — fields the /commands page renders.
                .andExpect(jsonPath("$[0].tenantId", is(tenantA.getId())))
                .andExpect(jsonPath("$[0].commandType", is(type)))
                .andExpect(jsonPath("$[0].status", is("SUCCEEDED")))
                .andExpect(jsonPath("$[0].aggregateType", is("Pipeline")))
                .andExpect(jsonPath("$[0].aggregateId", is("agg-a-2")));

        // Tenant B sees only its own row.
        mockMvc.perform(get("/api/v1/tenants/{tenantId}/commands", tenantB.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", is(1)))
                .andExpect(jsonPath("$[0].id", is(b1.getId())))
                .andExpect(jsonPath("$[0].tenantId", is(tenantB.getId())));
    }

    @Test
    void listCommands_withAggregateIdFilter_returnsOnlyMatchingRows() throws Exception {
        String type = "TC.api.aggregate." + SeedFixtures.nextSuffix();
        commandService.registerHandler(type, cmd -> Map.of());

        commandService.execute(type, "Pipeline", "agg-target",
                tenantA.getId(), actorA.getId(), null, Map.of());
        commandService.execute(type, "Pipeline", "agg-target",
                tenantA.getId(), actorA.getId(), null, Map.of());
        commandService.execute(type, "Pipeline", "agg-other",
                tenantA.getId(), actorA.getId(), null, Map.of());

        mockMvc.perform(get("/api/v1/tenants/{tenantId}/commands", tenantA.getId())
                        .param("aggregateId", "agg-target"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", is(2)))
                .andExpect(jsonPath("$[0].aggregateId", is("agg-target")))
                .andExpect(jsonPath("$[1].aggregateId", is("agg-target")));
    }

    @Test
    void listCommands_nonExistentTenantId_returnsEmptyListNotServerError() throws Exception {
        // Negative case from the packet: "Calling with non-existent tenantId returns empty list, not 500".
        // The query is just a SELECT WHERE tenant_id = ?, so a missing tenant simply returns no rows.
        mockMvc.perform(get("/api/v1/tenants/{tenantId}/commands", "tenant-does-not-exist"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", is(0)));
    }

    @Test
    void listCommands_withNoCommands_returnsEmptyArrayNotNull() throws Exception {
        // Edge case: brand new tenant with no commands — must return [] not null/500 so
        // the frontend can iterate safely.
        Tenant fresh = seedFixtures.seedTenant();
        mockMvc.perform(get("/api/v1/tenants/{tenantId}/commands", fresh.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", is(0)));
    }
}
