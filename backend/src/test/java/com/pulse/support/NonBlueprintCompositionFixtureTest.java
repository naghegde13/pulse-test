package com.pulse.support;

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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TC_non_blueprint_composition_fixture — fixture wires source + transform + 1 wiring without
 * asserting on blueprint param shape; the result context exposes ids needed by integration
 * tests.
 */
@SpringBootTest
@ActiveProfiles("test")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.BEFORE_CLASS)
class NonBlueprintCompositionFixtureTest {

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
    void create_insertsTwoInstancesAndOneWiring() {
        SeedFixtures fx = seedFixtures();
        NonBlueprintCompositionFixture composition = new NonBlueprintCompositionFixture(fx, blueprintRepository);

        SeedFixtures.Context seed = fx.seedFullPipelineContext();
        NonBlueprintCompositionFixture.CompositionContext ctx = composition.create(seed.pipelineId(), seed.versionId());

        assertEquals(seed.pipelineId(), ctx.pipelineId());
        assertEquals(seed.versionId(), ctx.versionId());
        assertNotNull(ctx.sourceInstanceId(), "source instance id present");
        assertNotNull(ctx.transformInstanceId(), "transform instance id present");
        assertNotNull(ctx.wiringId(), "wiring id present");

        var instances = subPipelineInstanceRepository.findByVersionIdOrderByExecutionOrderAsc(seed.versionId());
        assertEquals(2, instances.size(), "exactly two instances inserted");
        assertEquals(NonBlueprintCompositionFixture.SOURCE_BLUEPRINT_KEY, instances.get(0).getBlueprintKey());
        assertEquals(NonBlueprintCompositionFixture.TRANSFORM_BLUEPRINT_KEY, instances.get(1).getBlueprintKey());

        var wirings = portWiringRepository.findByVersionIdOrderByCreatedAtAsc(seed.versionId());
        assertEquals(1, wirings.size(), "exactly one wiring inserted");
        assertEquals(ctx.sourceInstanceId(), wirings.get(0).getSourceInstanceId());
        assertEquals(ctx.transformInstanceId(), wirings.get(0).getTargetInstanceId());
    }

    @Test
    void instances_carryUnknownSchemaStatusUntilPropagation() {
        SeedFixtures fx = seedFixtures();
        NonBlueprintCompositionFixture composition = new NonBlueprintCompositionFixture(fx, blueprintRepository);
        NonBlueprintCompositionFixture.Result result = composition.createWithSeededPipeline();

        var instances = subPipelineInstanceRepository.findByVersionIdOrderByExecutionOrderAsc(result.seed().versionId());
        for (var inst : instances) {
            assertEquals("unknown", inst.getSchemaStatus(),
                    "schema_status remains 'unknown' until a propagation test triggers recompute");
        }
    }

    @Test
    void portsExposed_butParamsNotAsserted() {
        SeedFixtures fx = seedFixtures();
        NonBlueprintCompositionFixture composition = new NonBlueprintCompositionFixture(fx, blueprintRepository);
        SeedFixtures.Context seed = fx.seedFullPipelineContext();
        NonBlueprintCompositionFixture.CompositionContext ctx = composition.create(seed.pipelineId(), seed.versionId());

        // Fixture exposes port names so cross-module tests can reason about the wiring without
        // touching blueprint internals.
        assertEquals("raw_output", ctx.sourceOutputPort());
        assertEquals("data_input", ctx.transformInputPort());
        assertEquals("filtered_output", ctx.transformOutputPort());

        // Params are populated but the fixture does NOT pin a particular shape — the test
        // simply confirms presence so a downstream test can swap params without breaking us.
        var instances = subPipelineInstanceRepository.findByVersionIdOrderByExecutionOrderAsc(seed.versionId());
        assertTrue(instances.get(0).getParams() != null, "source has params map (shape not asserted)");
        assertTrue(instances.get(1).getParams() != null, "transform has params map (shape not asserted)");
    }
}
