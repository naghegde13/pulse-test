package com.pulse.support;

import com.pulse.auth.model.UserRole;
import com.pulse.auth.repository.TenantRepository;
import com.pulse.auth.repository.UserRepository;
import com.pulse.blueprint.repository.BlueprintRepository;
import com.pulse.pipeline.repository.PipelineRepository;
import com.pulse.pipeline.repository.PipelineVersionRepository;
import com.pulse.pipeline.repository.PortWiringRepository;
import com.pulse.pipeline.repository.SubPipelineInstanceRepository;
import com.pulse.sor.repository.ConnectorDefinitionRepository;
import com.pulse.sor.repository.ConnectorInstanceRepository;
import com.pulse.sor.repository.DatasetRepository;
import com.pulse.sor.repository.DomainRepository;
import com.pulse.sor.repository.SystemOfRecordRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TC_seed_fixtures_smoke — SeedFixtures populates a tenant with one user, one domain, one
 * dataset, one pipeline version, with deterministic non-null ids.
 */
@SpringBootTest
@ActiveProfiles("test")
class SeedFixturesTest {

    @Autowired TenantRepository tenantRepository;
    @Autowired UserRepository userRepository;
    @Autowired DomainRepository domainRepository;
    @Autowired SystemOfRecordRepository sorRepository;
    @Autowired ConnectorDefinitionRepository connectorDefinitionRepository;
    @Autowired ConnectorInstanceRepository connectorInstanceRepository;
    @Autowired DatasetRepository datasetRepository;
    @Autowired BlueprintRepository blueprintRepository;
    @Autowired PipelineRepository pipelineRepository;
    @Autowired PipelineVersionRepository pipelineVersionRepository;
    @Autowired SubPipelineInstanceRepository subPipelineInstanceRepository;
    @Autowired PortWiringRepository portWiringRepository;

    private SeedFixtures seedFixtures() {
        return new SeedFixtures(
                tenantRepository, userRepository, domainRepository, sorRepository,
                connectorDefinitionRepository, connectorInstanceRepository, datasetRepository,
                blueprintRepository, pipelineRepository, pipelineVersionRepository,
                subPipelineInstanceRepository, portWiringRepository);
    }

    @Test
    void seedFullPipelineContext_returnsContextWithPersistedRows() {
        SeedFixtures fx = seedFixtures();
        SeedFixtures.Context ctx = fx.seedFullPipelineContext();

        assertNotNull(ctx.tenantId(), "tenantId");
        assertNotNull(ctx.userId(), "userId");
        assertNotNull(ctx.domainId(), "domainId");
        assertNotNull(ctx.datasetId(), "datasetId");
        assertNotNull(ctx.pipelineId(), "pipelineId");
        assertNotNull(ctx.versionId(), "versionId");

        assertTrue(tenantRepository.findById(ctx.tenantId()).isPresent(), "tenant row");
        var user = userRepository.findById(ctx.userId()).orElseThrow();
        assertEquals(UserRole.DATA_ENGINEER, user.getRole(), "DATA_ENGINEER role");
        assertEquals(ctx.tenantId(), user.getTenantId(), "user belongs to tenant");

        var domain = domainRepository.findById(ctx.domainId()).orElseThrow();
        assertEquals(ctx.tenantId(), domain.getTenantId(), "domain linked to tenant");

        var dataset = datasetRepository.findById(ctx.datasetId()).orElseThrow();
        assertEquals(ctx.tenantId(), dataset.getTenantId(), "dataset linked to tenant");
        assertNotNull(dataset.getSorId(), "dataset linked to SOR");
        assertNotNull(dataset.getConnectorInstanceId(), "dataset linked to connector instance");

        var version = pipelineVersionRepository.findById(ctx.versionId()).orElseThrow();
        assertEquals(ctx.pipelineId(), version.getPipelineId(), "version linked to pipeline");
        // Composition is empty until NonBlueprintCompositionFixture or a blueprint factory layers it on.
        assertEquals(0L, subPipelineInstanceRepository.findByVersionIdOrderByExecutionOrderAsc(ctx.versionId()).size(),
                "no instances seeded by SeedFixtures");
        assertEquals(0L, portWiringRepository.findByVersionIdOrderByCreatedAtAsc(ctx.versionId()).size(),
                "no wirings seeded by SeedFixtures");
    }

    @Test
    void twoSeedsDoNotCollide() {
        SeedFixtures fx = seedFixtures();
        SeedFixtures.Context a = fx.seedFullPipelineContext();
        SeedFixtures.Context b = fx.seedFullPipelineContext();

        assertNotEquals(a.tenantId(), b.tenantId(), "fresh tenant id per call");
        assertNotEquals(a.domainId(), b.domainId(), "fresh domain id per call");
        assertNotEquals(a.pipelineId(), b.pipelineId(), "fresh pipeline id per call");
    }
}
