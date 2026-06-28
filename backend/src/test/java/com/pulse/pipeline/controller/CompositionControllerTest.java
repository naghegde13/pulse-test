package com.pulse.pipeline.controller;

import com.pulse.auth.repository.TenantRepository;
import com.pulse.auth.repository.UserRepository;
import com.pulse.blueprint.repository.BlueprintRepository;
import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.pipeline.model.PortWiring;
import com.pulse.pipeline.model.SubPipelineInstance;
import com.pulse.pipeline.repository.PipelineRepository;
import com.pulse.pipeline.repository.PipelineVersionRepository;
import com.pulse.pipeline.repository.PortWiringRepository;
import com.pulse.pipeline.repository.SubPipelineInstanceRepository;
import com.pulse.pipeline.service.CompositionService.CompositionView;
import com.pulse.sor.repository.ConnectorDefinitionRepository;
import com.pulse.sor.repository.ConnectorInstanceRepository;
import com.pulse.sor.repository.DatasetRepository;
import com.pulse.sor.repository.DomainRepository;
import com.pulse.sor.repository.SystemOfRecordRepository;
import com.pulse.support.NonBlueprintCompositionFixture;
import com.pulse.support.NonBlueprintCompositionFixture.CompositionContext;
import com.pulse.support.NonBlueprintCompositionFixture.Result;
import com.pulse.support.SeedFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Controller contract tests for {@link CompositionController} — the entry point used by both
 * the DAG designer UI and every chat tool that mutates a pipeline composition. The service
 * layer is covered by {@code CompositionServiceTest}; this class locks the route shape
 * (request bodies, response shape / status code, persisted side effects, exception type for
 * negative cases) so a regression in either layer surfaces here before it reaches the UI.
 *
 * <p>Boundary: <b>api_to_database</b>. Each test boots the full Spring context against the H2
 * test profile and drives the controller as a plain Java object — matching the convention used
 * by other controller tests in this module (no MockMvc / HTTP layer). Exceptions thrown by the
 * service propagate up; the {@code GlobalExceptionHandler} (verified separately) maps
 * {@link ResourceNotFoundException} to HTTP 404 and {@code IllegalArgumentException} to HTTP 400
 * at the real HTTP boundary, so asserting on those exception types is equivalent to asserting
 * on the HTTP status code.
 *
 * <p>Routes covered (TASK_P0_composition_controller_contract):
 * <ul>
 *   <li>GET    /api/v1/versions/{versionId}/composition                          — getComposition</li>
 *   <li>POST   /api/v1/versions/{versionId}/composition/instances                — addInstance</li>
 *   <li>DELETE /api/v1/versions/{versionId}/composition/instances/{instanceId}   — removeInstance (cascade)</li>
 *   <li>PUT    /api/v1/versions/{versionId}/composition/instances/reorder        — reorder</li>
 *   <li>PUT    /api/v1/versions/{versionId}/composition/instances/{id}/params    — updateParams</li>
 *   <li>POST   /api/v1/versions/{versionId}/composition/wirings                  — wirePort (port validation, cross-version, self-wire)</li>
 *   <li>PUT    /api/v1/versions/{versionId}/composition/instances/{id}/schema    — updateInstanceSchema</li>
 * </ul>
 *
 * <p>The fixture shape is provided by {@link NonBlueprintCompositionFixture}: a stable
 * source-then-transform composition that does NOT pin blueprint params. Tests therefore assert
 * on the controller's contract — never on blueprint internals.
 */
@SpringBootTest
@ActiveProfiles("test")
class CompositionControllerTest {

    @Autowired private CompositionController controller;

    @Autowired private SubPipelineInstanceRepository instanceRepository;
    @Autowired private PortWiringRepository wiringRepository;

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

    private SeedFixtures seedFixtures() {
        return new SeedFixtures(
                tenantRepository, userRepository, domainRepository, sorRepository,
                connectorDefinitionRepository, connectorInstanceRepository, datasetRepository,
                blueprintRepository, pipelineRepository, pipelineVersionRepository,
                instanceRepository, wiringRepository);
    }

    private NonBlueprintCompositionFixture composition(SeedFixtures fx) {
        return new NonBlueprintCompositionFixture(fx, blueprintRepository);
    }

    // ------------------------------------------------------------------------
    //  TC_composition_get_returns_view
    // ------------------------------------------------------------------------

    /**
     * GET composition returns the two seeded instances + one wiring. The frontend
     * {@code dag-view.tsx} renders the DAG directly from this payload, so the shape must
     * include {@code blueprintKey}, {@code params}, and {@code schemaStatus} on each instance,
     * and {@code sourceInstanceId/sourcePortName/targetInstanceId/targetPortName} on each
     * wiring. The fixture seeds {@code schemaStatus = "unknown"} on both instances; this test
     * also pins that contract for the API view.
     */
    @Test
    void getComposition_returnsInstancesAndWirings() {
        SeedFixtures fx = seedFixtures();
        Result result = composition(fx).createWithSeededPipeline();
        CompositionContext ctx = result.composition();

        ResponseEntity<CompositionView> response = controller.getComposition(ctx.versionId());

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        CompositionView view = response.getBody();

        assertEquals(2, view.instances().size(), "two seeded instances visible to the API");
        assertEquals(1, view.wirings().size(), "single seeded wiring visible to the API");

        for (SubPipelineInstance inst : view.instances()) {
            assertNotNull(inst.getBlueprintKey(), "blueprintKey populated for DAG render");
            assertNotNull(inst.getParams(), "params populated (shape not asserted)");
            assertNotNull(inst.getSchemaStatus(), "schemaStatus populated for DAG render");
        }
        PortWiring w = view.wirings().get(0);
        assertEquals(ctx.sourceInstanceId(), w.getSourceInstanceId());
        assertEquals(ctx.sourceOutputPort(), w.getSourcePortName());
        assertEquals(ctx.transformInstanceId(), w.getTargetInstanceId());
        assertEquals(ctx.transformInputPort(), w.getTargetPortName());
    }

    /**
     * Unknown {@code versionId} returns an empty view (NOT 404). Today the read path is best-
     * effort — the JPA query returns an empty list for an unknown version and the controller
     * passes that through. This test pins the current behavior so a switch to 404 surfaces
     * deliberately. The arrays must be empty lists, never {@code null}, so the frontend can
     * iterate without a null check.
     */
    @Test
    void getComposition_unknownVersion_returnsEmptyArraysNotNull() {
        ResponseEntity<CompositionView> response = controller.getComposition("nonexistent-version-id");

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertNotNull(response.getBody().instances(), "instances array is non-null");
        assertNotNull(response.getBody().wirings(), "wirings array is non-null");
        assertTrue(response.getBody().instances().isEmpty());
        assertTrue(response.getBody().wirings().isEmpty());
    }

    // ------------------------------------------------------------------------
    //  TC_composition_post_instance_creates_row
    // ------------------------------------------------------------------------

    /**
     * POST instance with a valid blueprintKey persists a row, returns it with a non-null id,
     * and the subsequent GET composition surfaces the new instance. Add-instance is the
     * most common DAG mutation in chat tools and the wizard.
     */
    @Test
    void addInstance_persistsRowAndIsVisibleInSubsequentGet() {
        SeedFixtures fx = seedFixtures();
        Result result = composition(fx).createWithSeededPipeline();
        CompositionContext ctx = result.composition();
        int beforeCount = controller.getComposition(ctx.versionId()).getBody().instances().size();

        CompositionController.AddInstanceRequest request = new CompositionController.AddInstanceRequest(
                ctx.pipelineId(),
                NonBlueprintCompositionFixture.TRANSFORM_BLUEPRINT_KEY,
                "Extra Filter",
                Map.of("filter_expression", "amount > 0"),
                null, null, null);

        ResponseEntity<CompositionController.SubPipelineInstanceResponse> response =
                controller.addInstance(ctx.versionId(), request);

        assertEquals(200, response.getStatusCode().value(),
                "controller returns 200 OK on add (Spring default for ResponseEntity.ok)");
        assertNotNull(response.getBody());
        SubPipelineInstance saved = response.getBody().instance();
        assertNotNull(saved);
        assertNotNull(saved.getId(), "returned instance has a non-null id");
        assertEquals(NonBlueprintCompositionFixture.TRANSFORM_BLUEPRINT_KEY, saved.getBlueprintKey());
        assertEquals("Extra Filter", saved.getName());

        // Subsequent GET reflects the new instance.
        CompositionView reread = controller.getComposition(ctx.versionId()).getBody();
        assertEquals(beforeCount + 1, reread.instances().size());
        assertTrue(reread.instances().stream().anyMatch(i -> saved.getId().equals(i.getId())));
    }

    /**
     * POST instance with an unknown blueprintKey throws {@link ResourceNotFoundException},
     * which the {@code GlobalExceptionHandler} maps to HTTP 404 at the wire (the service
     * looks up the blueprint via {@code blueprintRepo.findByBlueprintKey(...)} and treats a
     * miss as "blueprint not found", not "request malformed").
     */
    @Test
    void addInstance_unknownBlueprintKey_throws() {
        SeedFixtures fx = seedFixtures();
        Result result = composition(fx).createWithSeededPipeline();
        CompositionContext ctx = result.composition();

        CompositionController.AddInstanceRequest request = new CompositionController.AddInstanceRequest(
                ctx.pipelineId(), "DoesNotExistBlueprint", "Bad", Map.of(),
                null, null, null);

        assertThrows(ResourceNotFoundException.class,
                () -> controller.addInstance(ctx.versionId(), request));
    }

    /**
     * Adding two instances with the same blueprintKey is allowed — they get independent ids
     * and adjacent execution orders. This is the "duplicate sub-pipeline" pattern (e.g., two
     * filters in series) the wizard must support.
     */
    @Test
    void addInstance_sameBlueprintKeyTwice_yieldsIndependentIds() {
        SeedFixtures fx = seedFixtures();
        Result result = composition(fx).createWithSeededPipeline();
        CompositionContext ctx = result.composition();

        CompositionController.AddInstanceRequest req = new CompositionController.AddInstanceRequest(
                ctx.pipelineId(),
                NonBlueprintCompositionFixture.TRANSFORM_BLUEPRINT_KEY,
                "First Extra", Map.of(),
                null, null, null);

        SubPipelineInstance first = controller.addInstance(ctx.versionId(), req).getBody().instance();
        SubPipelineInstance second = controller.addInstance(ctx.versionId(),
                new CompositionController.AddInstanceRequest(
                        ctx.pipelineId(),
                        NonBlueprintCompositionFixture.TRANSFORM_BLUEPRINT_KEY,
                        "Second Extra", Map.of(),
                        null, null, null)).getBody().instance();

        assertNotNull(first);
        assertNotNull(second);
        assertNotNull(first.getId());
        assertNotNull(second.getId());
        assertFalse(first.getId().equals(second.getId()), "two adds produce two distinct ids");
        assertTrue(second.getExecutionOrder() > first.getExecutionOrder(),
                "second add gets a higher execution order");
    }

    // ------------------------------------------------------------------------
    //  TC_composition_delete_instance_removes_outgoing_wirings
    // ------------------------------------------------------------------------

    /**
     * Removing an instance removes the row and returns 204 No Content. The instance is no
     * longer surfaced via the GET composition view.
     *
     * <p><b>Wiring cascade lives at the DB layer.</b> The service body of
     * {@code CompositionService.removeInstance} does not explicitly delete wirings; the
     * cascade is enforced via {@code ON DELETE CASCADE} on the
     * {@code port_wirings.source_instance_id} / {@code .target_instance_id} foreign keys in
     * the Flyway migrations. Those migrations DO run in the Postgres integration lane
     * ({@code backendIntegrationTest}) but DO NOT run in the H2 fastPrTest lane (Flyway is
     * disabled and JPA's {@code create-drop} generates schemas without the cascade clauses).
     * The cascade regression is therefore covered by the integration lane; this test asserts
     * the controller contract observable in the fast lane: a successful 204 and removal of
     * the instance from the API view.
     */
    @Test
    void removeInstance_returns204AndDropsInstanceFromView() {
        SeedFixtures fx = seedFixtures();
        Result result = composition(fx).createWithSeededPipeline();
        CompositionContext ctx = result.composition();

        // Sanity: instance + wiring exist before the delete.
        CompositionView before = controller.getComposition(ctx.versionId()).getBody();
        assertEquals(2, before.instances().size());
        assertEquals(1, before.wirings().size());

        ResponseEntity<Void> response = controller.removeInstance(ctx.versionId(), ctx.sourceInstanceId());

        assertEquals(204, response.getStatusCode().value(),
                "controller returns 204 No Content on delete");

        CompositionView reread = controller.getComposition(ctx.versionId()).getBody();
        assertEquals(1, reread.instances().size(), "only the transform remains");
        assertFalse(reread.instances().stream().anyMatch(i -> ctx.sourceInstanceId().equals(i.getId())));
    }

    /**
     * Deleting an unknown instance throws {@link ResourceNotFoundException} (mapped to 404).
     */
    @Test
    void removeInstance_unknownInstance_throws() {
        SeedFixtures fx = seedFixtures();
        Result result = composition(fx).createWithSeededPipeline();
        CompositionContext ctx = result.composition();

        assertThrows(ResourceNotFoundException.class,
                () -> controller.removeInstance(ctx.versionId(), "nonexistent-instance-id"));
    }

    /**
     * Deleting an instance that belongs to a different version is rejected (the route
     * scopes by {@code versionId} in the URL, so passing a mismatched id is a 400, not a
     * silent cross-version delete).
     */
    @Test
    void removeInstance_crossVersion_rejected() {
        SeedFixtures fx = seedFixtures();
        Result other = composition(fx).createWithSeededPipeline();
        Result mine = composition(fx).createWithSeededPipeline();

        assertThrows(IllegalArgumentException.class,
                () -> controller.removeInstance(mine.composition().versionId(),
                        other.composition().sourceInstanceId()));
    }

    // ------------------------------------------------------------------------
    //  TC_composition_reorder_persists_order
    // ------------------------------------------------------------------------

    /**
     * PUT reorder applies the new sequence so GET composition returns instances in the
     * supplied order. The frontend list-view renders directly from this order, so a drift
     * silently shuffles the DAG.
     */
    @Test
    void reorder_persistsNewOrderInSubsequentGet() {
        SeedFixtures fx = seedFixtures();
        Result result = composition(fx).createWithSeededPipeline();
        CompositionContext ctx = result.composition();

        // Fixture seeds source=1, transform=2. Flip them.
        List<String> reversed = List.of(ctx.transformInstanceId(), ctx.sourceInstanceId());

        ResponseEntity<List<SubPipelineInstance>> response = controller.reorder(
                ctx.versionId(), new CompositionController.ReorderRequest(reversed));

        assertEquals(200, response.getStatusCode().value());

        CompositionView reread = controller.getComposition(ctx.versionId()).getBody();
        assertEquals(2, reread.instances().size());
        assertEquals(ctx.transformInstanceId(), reread.instances().get(0).getId(),
                "transform now first in order");
        assertEquals(ctx.sourceInstanceId(), reread.instances().get(1).getId(),
                "source now second in order");
    }

    /**
     * Reorder with an id that is not in the version is rejected with
     * {@link ResourceNotFoundException} — the service walks {@code byId} and throws on the
     * first missing entry.
     */
    @Test
    void reorder_withMissingId_throws() {
        SeedFixtures fx = seedFixtures();
        Result result = composition(fx).createWithSeededPipeline();
        CompositionContext ctx = result.composition();

        assertThrows(ResourceNotFoundException.class,
                () -> controller.reorder(ctx.versionId(),
                        new CompositionController.ReorderRequest(List.of("not-in-this-version"))));
    }

    // ------------------------------------------------------------------------
    //  TC_composition_post_wiring_validates_ports
    // ------------------------------------------------------------------------

    /**
     * POST wiring with valid source/target ports persists a wiring row that is then visible
     * via GET composition. The fixture wires source.raw_output -> transform.data_input, so
     * adding a second wiring between two new instances exercises the happy path without
     * fighting the existing wiring.
     */
    @Test
    void wirePort_validPorts_persistsWiring() {
        SeedFixtures fx = seedFixtures();
        Result result = composition(fx).createWithSeededPipeline();
        CompositionContext ctx = result.composition();

        // Add a second transform so we can wire transform[1].filtered_output -> transform[2].data_input.
        SubPipelineInstance secondTransform = controller.addInstance(ctx.versionId(),
                new CompositionController.AddInstanceRequest(
                        ctx.pipelineId(),
                        NonBlueprintCompositionFixture.TRANSFORM_BLUEPRINT_KEY,
                        "Second Transform",
                        Map.of(),
                        null, null, null)).getBody().instance();

        ResponseEntity<PortWiring> response = controller.wirePort(ctx.versionId(),
                new CompositionController.WireRequest(
                        ctx.transformInstanceId(),
                        ctx.transformOutputPort(),
                        secondTransform.getId(),
                        ctx.transformInputPort()));

        assertEquals(200, response.getStatusCode().value());
        PortWiring saved = response.getBody();
        assertNotNull(saved);
        assertNotNull(saved.getId());

        CompositionView reread = controller.getComposition(ctx.versionId()).getBody();
        assertEquals(2, reread.wirings().size(),
                "now two wirings: fixture's plus the one we just added");
        assertTrue(reread.wirings().stream().anyMatch(w -> saved.getId().equals(w.getId())));
    }

    /**
     * Source port name that is not declared on the source blueprint is rejected
     * with {@link IllegalArgumentException} (HTTP 400). This is the contract that prevents
     * invalid wirings from bypassing the schema propagation rules.
     */
    @Test
    void wirePort_invalidSourcePort_throws() {
        SeedFixtures fx = seedFixtures();
        Result result = composition(fx).createWithSeededPipeline();
        CompositionContext ctx = result.composition();

        SubPipelineInstance secondTransform = controller.addInstance(ctx.versionId(),
                new CompositionController.AddInstanceRequest(
                        ctx.pipelineId(),
                        NonBlueprintCompositionFixture.TRANSFORM_BLUEPRINT_KEY,
                        "Second Transform",
                        Map.of(),
                        null, null, null)).getBody().instance();

        assertThrows(IllegalArgumentException.class,
                () -> controller.wirePort(ctx.versionId(),
                        new CompositionController.WireRequest(
                                ctx.sourceInstanceId(),
                                "not_a_real_output_port",
                                secondTransform.getId(),
                                ctx.transformInputPort())));
    }

    /**
     * Target port name that is not declared on the target blueprint is rejected
     * with {@link IllegalArgumentException} (HTTP 400).
     */
    @Test
    void wirePort_invalidTargetPort_throws() {
        SeedFixtures fx = seedFixtures();
        Result result = composition(fx).createWithSeededPipeline();
        CompositionContext ctx = result.composition();

        SubPipelineInstance secondTransform = controller.addInstance(ctx.versionId(),
                new CompositionController.AddInstanceRequest(
                        ctx.pipelineId(),
                        NonBlueprintCompositionFixture.TRANSFORM_BLUEPRINT_KEY,
                        "Second Transform",
                        Map.of(),
                        null, null, null)).getBody().instance();

        assertThrows(IllegalArgumentException.class,
                () -> controller.wirePort(ctx.versionId(),
                        new CompositionController.WireRequest(
                                ctx.sourceInstanceId(),
                                ctx.sourceOutputPort(),
                                secondTransform.getId(),
                                "not_a_real_input_port")));
    }

    /**
     * Self-wiring (a single instance wired into itself) is rejected: the source instance has
     * only an {@code output} port (raw_output) and the target side of the wire demands an
     * {@code input} port; the service's port-name validator therefore throws
     * {@link IllegalArgumentException}. This locks the no-self-loop contract.
     */
    @Test
    void wirePort_selfWiring_throws() {
        SeedFixtures fx = seedFixtures();
        Result result = composition(fx).createWithSeededPipeline();
        CompositionContext ctx = result.composition();

        assertThrows(IllegalArgumentException.class,
                () -> controller.wirePort(ctx.versionId(),
                        new CompositionController.WireRequest(
                                ctx.sourceInstanceId(),
                                ctx.sourceOutputPort(),
                                ctx.sourceInstanceId(),
                                ctx.transformInputPort())));
    }

    /**
     * Cross-version wiring (source from version A, target from version B) is rejected by the
     * service's "instances do not belong to this version" check.
     */
    @Test
    void wirePort_crossVersion_throws() {
        SeedFixtures fx = seedFixtures();
        Result a = composition(fx).createWithSeededPipeline();
        Result b = composition(fx).createWithSeededPipeline();

        assertThrows(IllegalArgumentException.class,
                () -> controller.wirePort(a.composition().versionId(),
                        new CompositionController.WireRequest(
                                a.composition().sourceInstanceId(),
                                a.composition().sourceOutputPort(),
                                b.composition().transformInstanceId(),
                                b.composition().transformInputPort())));
    }

    // ------------------------------------------------------------------------
    //  TC_composition_put_params_persists
    // ------------------------------------------------------------------------

    /**
     * PUT params on an instance persists the new params and the next GET composition reflects
     * them. The Configure dialog in the UI saves via this route — drift here silently drops
     * user-edited params.
     */
    @Test
    void updateParams_persistsParamsForInstance() {
        SeedFixtures fx = seedFixtures();
        Result result = composition(fx).createWithSeededPipeline();
        CompositionContext ctx = result.composition();

        Map<String, Object> newParams = Map.of(
                "filter_expression", "amount > 100",
                "drop_nulls", true);

        ResponseEntity<SubPipelineInstance> response = controller.updateParams(
                ctx.versionId(), ctx.transformInstanceId(), newParams);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());

        // Re-fetch via GET composition to confirm the params round-trip through the DB.
        CompositionView reread = controller.getComposition(ctx.versionId()).getBody();
        SubPipelineInstance transform = reread.instances().stream()
                .filter(i -> ctx.transformInstanceId().equals(i.getId()))
                .findFirst()
                .orElseThrow();
        assertEquals("amount > 100", transform.getParams().get("filter_expression"));
        assertEquals(true, transform.getParams().get("drop_nulls"));
    }

    /**
     * PUT params on an unknown instance throws {@link ResourceNotFoundException} (HTTP 404).
     */
    @Test
    void updateParams_unknownInstance_throws() {
        SeedFixtures fx = seedFixtures();
        Result result = composition(fx).createWithSeededPipeline();
        CompositionContext ctx = result.composition();

        assertThrows(ResourceNotFoundException.class,
                () -> controller.updateParams(ctx.versionId(),
                        "nonexistent-instance-id",
                        Map.of("x", "y")));
    }

    /**
     * Empty params object replaces the existing params (cleared, not merged). This is the
     * documented behavior — the wizard sometimes saves a cleared form, and the server must
     * mirror that intent rather than retain stale params.
     */
    @Test
    void updateParams_emptyMapClearsExistingParams() {
        SeedFixtures fx = seedFixtures();
        Result result = composition(fx).createWithSeededPipeline();
        CompositionContext ctx = result.composition();

        // Seed some params first.
        controller.updateParams(ctx.versionId(), ctx.transformInstanceId(),
                Map.of("filter_expression", "amount > 0"));
        // Then clear them.
        controller.updateParams(ctx.versionId(), ctx.transformInstanceId(), Map.of());

        CompositionView reread = controller.getComposition(ctx.versionId()).getBody();
        SubPipelineInstance transform = reread.instances().stream()
                .filter(i -> ctx.transformInstanceId().equals(i.getId()))
                .findFirst()
                .orElseThrow();
        assertTrue(transform.getParams() == null || transform.getParams().isEmpty(),
                "empty PUT clears the params map");
    }

    // ------------------------------------------------------------------------
    //  PUT instance schema (route in scope for this packet)
    // ------------------------------------------------------------------------

    /**
     * PUT instance schema persists the supplied outputSchema on the instance. The propagation
     * service runs immediately after the save and may inject additional audit columns
     * (e.g. {@code _pulse_run_id}, {@code _pulse_business_date}); that enrichment is covered
     * in {@code SchemaPropagationServiceTest}. This test asserts the controller contract:
     * the user-supplied columns are present in the persisted output schema and the route
     * returns 200 OK with the saved instance.
     */
    @Test
    void updateInstanceSchema_persistsOutputSchema() {
        SeedFixtures fx = seedFixtures();
        Result result = composition(fx).createWithSeededPipeline();
        CompositionContext ctx = result.composition();

        Map<String, Object> schema = Map.of(
                "columns", List.of(
                        Map.of("name", "id", "type", "string"),
                        Map.of("name", "amount", "type", "double")));

        ResponseEntity<SubPipelineInstance> response = controller.updateSchema(
                ctx.versionId(), ctx.sourceInstanceId(), schema);

        assertEquals(200, response.getStatusCode().value());
        SubPipelineInstance saved = response.getBody();
        assertNotNull(saved);
        assertNotNull(saved.getOutputSchema());
        // The returned instance reflects what the API hands back to the caller: the
        // user-supplied columns must survive the round trip through the propagation pass.
        // (Propagation may append audit columns; the helper tolerates that.)
        assertColumnsContain(saved.getOutputSchema(), "id", "amount");
    }

    /**
     * Helper: assert the {@code columns} entry of an output schema map contains an entry for
     * each supplied column name. Tolerates additional injected columns (audit fields) added by
     * {@code SchemaPropagationService} so this test isolates the controller's persistence
     * contract from propagation's enrichment policy. Accepts any {@link Iterable} so the helper
     * is robust to the Jackson-module-scala collection types that the Spark classpath
     * occasionally produces during JSONB deserialization in tests.
     */
    private static void assertColumnsContain(Map<String, Object> outputSchema, String... expectedNames) {
        Object columnsObj = outputSchema.get("columns");
        assertNotNull(columnsObj, "columns key present on output schema");
        assertTrue(columnsObj instanceof Iterable<?>, "columns is iterable; got " + columnsObj.getClass());

        List<String> names = new java.util.ArrayList<>();
        for (Object c : (Iterable<?>) columnsObj) {
            if (c instanceof Map<?, ?> m) {
                Object name = m.get("name");
                names.add(name == null ? null : name.toString());
            }
        }
        for (String expected : expectedNames) {
            assertTrue(names.contains(expected),
                    "expected column '" + expected + "' in output schema; got names " + names);
        }
    }

    /**
     * PUT instance schema on an unknown instance throws {@link ResourceNotFoundException}
     * (HTTP 404), and cross-version PUT is rejected with {@link IllegalArgumentException}
     * (HTTP 400).
     */
    @Test
    void updateInstanceSchema_unknownInstance_throws() {
        SeedFixtures fx = seedFixtures();
        Result result = composition(fx).createWithSeededPipeline();
        CompositionContext ctx = result.composition();

        assertThrows(ResourceNotFoundException.class,
                () -> controller.updateSchema(ctx.versionId(),
                        "nonexistent-instance-id", Map.of("columns", List.of())));

        Result other = composition(fx).createWithSeededPipeline();
        assertThrows(IllegalArgumentException.class,
                () -> controller.updateSchema(ctx.versionId(),
                        other.composition().sourceInstanceId(),
                        Map.of("columns", List.of())));
    }

    // ------------------------------------------------------------------------
    //  Sanity: getComposition body type is structurally non-null
    // ------------------------------------------------------------------------

    /**
     * Belt-and-suspenders: a freshly-seeded pipeline with no composition layer returns a view
     * whose arrays are non-null. The DAG-view frontend iterates these directly and assumes
     * non-null collections, so this contract must be locked.
     */
    @Test
    void getComposition_freshVersion_returnsEmptyView() {
        SeedFixtures fx = seedFixtures();
        SeedFixtures.Context seed = fx.seedFullPipelineContext();

        CompositionView view = controller.getComposition(seed.versionId()).getBody();

        assertNotNull(view);
        assertNotNull(view.instances());
        assertNotNull(view.wirings());
        assertTrue(view.instances().isEmpty());
        assertTrue(view.wirings().isEmpty());
    }
}
