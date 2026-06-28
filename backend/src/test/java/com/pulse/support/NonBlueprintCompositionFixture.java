package com.pulse.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.blueprint.model.Blueprint;
import com.pulse.blueprint.model.BlueprintCategory;
import com.pulse.blueprint.repository.BlueprintRepository;
import com.pulse.pipeline.model.PortWiring;
import com.pulse.pipeline.model.SubPipelineInstance;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Wires a minimal source-then-transform composition for cross-module tests that do NOT need
 * to exercise blueprint-specific param shapes (those tests live under the e2e blueprint lane).
 *
 * <p>Stable blueprint keys used by this fixture (documented in
 * {@code docs/testing/shared-test-fixtures.md}):
 * <ul>
 *   <li><b>{@value #SOURCE_BLUEPRINT_KEY}</b> — source-shaped INGESTION blueprint with one
 *       output port {@code raw_output}. Reused by {@code RepresentativePipelineFixtureFactory}
 *       and {@code LoanMasterFixture}, so its key is unlikely to be retired.</li>
 *   <li><b>{@value #TRANSFORM_BLUEPRINT_KEY}</b> — transform-shaped TRANSFORM blueprint with
 *       input port {@code data_input} and output port {@code filtered_output}. Same provenance.</li>
 * </ul>
 *
 * <p>The fixture deliberately does NOT assert on blueprint param shape: it only persists
 * {@link SubPipelineInstance} rows with the keys above and a single {@link PortWiring} row
 * connecting them. Tests that need richer params build them via blueprint-specific fixtures.
 *
 * <p>Reference data (port names, instance display names) lives at
 * {@code backend/src/test/resources/test-fixtures/non-blueprint-pipeline.json} so the shape
 * can be tweaked without recompiling. The JSON is loaded at fixture construction.
 *
 * <p>Schema status remains {@code "unknown"} on both instances so a downstream propagation
 * test can be the first to recompute it — see assertions in TC_non_blueprint_composition_fixture.
 */
public final class NonBlueprintCompositionFixture {

    /** Stable source-shaped blueprint key. */
    public static final String SOURCE_BLUEPRINT_KEY = "FileIngestion";
    /** Stable transform-shaped blueprint key. */
    public static final String TRANSFORM_BLUEPRINT_KEY = "GenericFilter";

    private static final String FIXTURE_RESOURCE = "test-fixtures/non-blueprint-pipeline.json";

    private final SeedFixtures seedFixtures;
    private final BlueprintRepository blueprintRepository;
    private final ObjectMapper objectMapper;

    public NonBlueprintCompositionFixture(SeedFixtures seedFixtures, BlueprintRepository blueprintRepository) {
        this(seedFixtures, blueprintRepository, new ObjectMapper());
    }

    public NonBlueprintCompositionFixture(SeedFixtures seedFixtures,
                                          BlueprintRepository blueprintRepository,
                                          ObjectMapper objectMapper) {
        this.seedFixtures = seedFixtures;
        this.blueprintRepository = blueprintRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Wires source + transform instances + one wiring on the supplied versionId, returning the
     * ids the test can use. Blueprint rows for {@link #SOURCE_BLUEPRINT_KEY} and
     * {@link #TRANSFORM_BLUEPRINT_KEY} are created on demand if not already present.
     */
    public CompositionContext create(String pipelineId, String versionId) {
        FixtureSpec spec = loadFixture();
        Blueprint sourceBp = ensureBlueprint(SOURCE_BLUEPRINT_KEY, BlueprintCategory.INGESTION,
                List.of(),
                List.of(Map.of("name", spec.sourceOutputPort())));
        Blueprint transformBp = ensureBlueprint(TRANSFORM_BLUEPRINT_KEY, BlueprintCategory.TRANSFORM,
                List.of(Map.of("name", spec.transformInputPort())),
                List.of(Map.of("name", spec.transformOutputPort())));

        SubPipelineInstance source = seedFixtures.seedSubPipelineInstance(
                pipelineId,
                versionId,
                sourceBp.getId(),
                SOURCE_BLUEPRINT_KEY,
                spec.sourceInstanceName(),
                1,
                spec.sourceParams());

        SubPipelineInstance transform = seedFixtures.seedSubPipelineInstance(
                pipelineId,
                versionId,
                transformBp.getId(),
                TRANSFORM_BLUEPRINT_KEY,
                spec.transformInstanceName(),
                2,
                spec.transformParams());

        PortWiring wiring = seedFixtures.seedPortWiring(
                versionId,
                source.getId(),
                spec.sourceOutputPort(),
                transform.getId(),
                spec.transformInputPort());

        return new CompositionContext(
                pipelineId,
                versionId,
                source.getId(),
                transform.getId(),
                wiring.getId(),
                spec.sourceOutputPort(),
                spec.transformInputPort(),
                spec.transformOutputPort());
    }

    /**
     * Convenience: seed a full pipeline context AND apply this composition on top of it.
     * Returns the composition context whose pipelineId/versionId map to the seeded pipeline.
     */
    public Result createWithSeededPipeline() {
        SeedFixtures.Context seed = seedFixtures.seedFullPipelineContext();
        CompositionContext composition = create(seed.pipelineId(), seed.versionId());
        return new Result(seed, composition);
    }

    private Blueprint ensureBlueprint(String key, BlueprintCategory category,
                                      List<Map<String, Object>> inputPorts,
                                      List<Map<String, Object>> outputPorts) {
        Optional<Blueprint> existing = blueprintRepository.findAll().stream()
                .filter(b -> key.equals(b.getBlueprintKey()))
                .findFirst();
        return existing.orElseGet(() -> seedFixtures.seedBlueprint(key, category, inputPorts, outputPorts));
    }

    private FixtureSpec loadFixture() {
        try (InputStream in = NonBlueprintCompositionFixture.class.getClassLoader()
                .getResourceAsStream(FIXTURE_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing fixture resource: " + FIXTURE_RESOURCE);
            }
            Map<String, Object> raw = objectMapper.readValue(in, new TypeReference<>() {});
            @SuppressWarnings("unchecked")
            Map<String, Object> source = (Map<String, Object>) raw.get("source");
            @SuppressWarnings("unchecked")
            Map<String, Object> transform = (Map<String, Object>) raw.get("transform");
            @SuppressWarnings("unchecked")
            Map<String, Object> sourceParams = (Map<String, Object>) source.getOrDefault("params", Map.of());
            @SuppressWarnings("unchecked")
            Map<String, Object> transformParams = (Map<String, Object>) transform.getOrDefault("params", Map.of());
            return new FixtureSpec(
                    (String) source.getOrDefault("name", "Source Instance"),
                    (String) source.getOrDefault("outputPort", "raw_output"),
                    sourceParams,
                    (String) transform.getOrDefault("name", "Transform Instance"),
                    (String) transform.getOrDefault("inputPort", "data_input"),
                    (String) transform.getOrDefault("outputPort", "filtered_output"),
                    transformParams);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load " + FIXTURE_RESOURCE, e);
        }
    }

    private record FixtureSpec(String sourceInstanceName,
                               String sourceOutputPort,
                               Map<String, Object> sourceParams,
                               String transformInstanceName,
                               String transformInputPort,
                               String transformOutputPort,
                               Map<String, Object> transformParams) { }

    /**
     * The composition layer the fixture creates. The caller can stitch this onto any
     * pipelineId/versionId — it does not own the pipeline identity.
     */
    public record CompositionContext(String pipelineId,
                                     String versionId,
                                     String sourceInstanceId,
                                     String transformInstanceId,
                                     String wiringId,
                                     String sourceOutputPort,
                                     String transformInputPort,
                                     String transformOutputPort) { }

    /** End-to-end result when a test wants both seed identity AND composition in one call. */
    public record Result(SeedFixtures.Context seed, CompositionContext composition) { }
}
