package com.pulse.e2e.coverage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.pulse.blueprint.model.Blueprint;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class ActiveBlueprintCoverageCatalogIT {

    private static final Path RESOURCE_ROOT = Path.of("src/test/resources/e2e/coverage");

    private final ActiveBlueprintCoverageCatalogBuilder builder = new ActiveBlueprintCoverageCatalogBuilder();
    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    @Test
    void coverageArtifactsMatchCheckedInSnapshotsAndHaveNoUnclassifiedActiveBlueprints() throws Exception {
        var activeBlueprints = loadBlueprints("api-active-blueprints.json");
        var allBlueprints = loadBlueprints("api-all-blueprints.json");
        var artifacts = builder.build(activeBlueprints, allBlueprints);

        assertFalse(activeBlueprints.isEmpty(), "Active blueprint API snapshot should not be empty");
        assertTrue(activeBlueprints.stream().noneMatch(Blueprint::isDeferred),
                "Active blueprint API snapshot should exclude deferred blueprints");
        assertTrue(artifacts.coverageDenominator().unclassifiedBlueprintKeys().isEmpty(),
                () -> "Unclassified active blueprints: " + artifacts.coverageDenominator().unclassifiedBlueprintKeys());
        assertEquals(
                artifacts.activeBlueprintCatalog().activeCatalogChecksum(),
                artifacts.coverageDenominator().activeCatalogChecksum(),
                "Coverage denominator should be tied to the exact active catalog snapshot checksum");
        assertEquals(
                artifacts.activeBlueprintCatalog().activeCatalogChecksum(),
                artifacts.blueprintFamilyPruning().activeCatalogChecksum(),
                "Pruning report should be tied to the exact active catalog snapshot checksum");
        assertEquals(
                activeBlueprints.stream().map(Blueprint::getBlueprintKey).sorted().toList(),
                artifacts.coverageDenominator().includedBlueprintKeys(),
                "Primary denominator should only contain active, non-deferred blueprint keys");
        assertTrue(
                artifacts.coverageDenominator().compatibilityOnlyBlueprints().stream()
                        .allMatch(record -> record.replacementBlueprintKey() != null && !record.replacementBlueprintKey().isBlank()),
                "Compatibility-only blueprints should carry an explicit replacement mapping");
        assertFalse(artifacts.pairwiseScenarioSeeds().isEmpty());
        assertFalse(artifacts.threeWayScenarioSeeds().isEmpty());

        assertSnapshot("active-blueprint-catalog.json", artifacts.activeBlueprintCatalog());
        assertSnapshot("coverage-denominator.json", artifacts.coverageDenominator());
        assertSnapshot("blueprint-family-pruning.json", artifacts.blueprintFamilyPruning());
    }

    @Test
    void defaultScenarioSeedsCoverPrimaryPairsAndTriplesForActiveFamilies() throws Exception {
        var activeBlueprints = loadBlueprints("api-active-blueprints.json");
        var allBlueprints = loadBlueprints("api-all-blueprints.json");
        var artifacts = builder.build(activeBlueprints, allBlueprints);

        var primaryFamilies = artifacts.blueprintFamilyPruning().primaryCoverageFamilies().stream()
                .map(ActiveBlueprintCoverageArtifacts.FamilyCoverageRule::family)
                .toList();
        var matrixDimensions = builder.defaultMatrixDimensions(primaryFamilies);
        var generator = new ScenarioMatrixGenerator();

        var exhaustive = generator.generate(matrixDimensions, matrixDimensions.size());
        assertEquals(generator.coveredTuples(exhaustive, 2), generator.coveredTuples(artifacts.pairwiseScenarioSeeds(), 2));
        assertEquals(generator.coveredTuples(exhaustive, 3), generator.coveredTuples(artifacts.threeWayScenarioSeeds(), 3));
        assertTrue(artifacts.pairwiseScenarioSeeds().size() < exhaustive.size());
        assertTrue(artifacts.threeWayScenarioSeeds().size() <= exhaustive.size());
        assertEquals(artifacts.coverageDenominator().primaryPairwiseSeedCount(), artifacts.pairwiseScenarioSeeds().size());
        assertEquals(artifacts.coverageDenominator().primaryThreeWaySeedCount(), artifacts.threeWayScenarioSeeds().size());
        assertEquals(Set.copyOf(primaryFamilies), Set.copyOf(matrixDimensions.get(0).values()));
        assertEquals(primaryFamilies.size(), artifacts.coverageDenominator().primaryPairwiseAxisCardinality().get("blueprint_family"));
    }

    @Test
    void activeBlueprintsAreClassifiedExactlyOnceAndExcludedSetContainsOnlyNonPrimaryRows() throws Exception {
        var activeBlueprints = loadBlueprints("api-active-blueprints.json");
        var allBlueprints = loadBlueprints("api-all-blueprints.json");
        var artifacts = builder.build(activeBlueprints, allBlueprints);

        Set<String> activeKeys = artifacts.activeBlueprintCatalog().blueprints().stream()
                .map(ActiveBlueprintCoverageArtifacts.ActiveBlueprintRecord::blueprintKey)
                .collect(Collectors.toSet());
        Set<String> primaryKeys = artifacts.blueprintFamilyPruning().primaryCoverageFamilies().stream()
                .flatMap(rule -> rule.blueprintKeys().stream())
                .collect(Collectors.toSet());
        Set<String> classificationKeys = artifacts.blueprintFamilyPruning().classifications().stream()
                .map(ActiveBlueprintCoverageArtifacts.BlueprintClassificationRecord::blueprintKey)
                .collect(Collectors.toSet());

        assertEquals(activeKeys, Set.copyOf(artifacts.coverageDenominator().includedBlueprintKeys()));
        assertEquals(activeKeys, primaryKeys);
        assertEquals(allBlueprints.size(), artifacts.blueprintFamilyPruning().classifications().size());
        assertEquals(classificationKeys.size(), artifacts.blueprintFamilyPruning().classifications().size());
        assertTrue(artifacts.coverageDenominator().compatibilityOnlyBlueprints().stream()
                .allMatch(record -> record.replacementBlueprintKey() != null && !record.replacementBlueprintKey().isBlank()));
        assertTrue(artifacts.blueprintFamilyPruning().excludedBlueprints().stream()
                .allMatch(record -> record.deferred() || !"active".equalsIgnoreCase(record.status())));
        assertTrue(artifacts.blueprintFamilyPruning().excludedBlueprints().stream()
                .map(ActiveBlueprintCoverageArtifacts.BlueprintClassificationRecord::blueprintKey)
                .noneMatch(activeKeys::contains));
    }

    private List<Blueprint> loadBlueprints(String resourceName) throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("e2e/coverage/" + resourceName)) {
            assertNotNull(in, () -> "Missing resource: " + resourceName);
            return objectMapper.readValue(in, new TypeReference<List<Blueprint>>() {});
        }
    }

    private void assertSnapshot(String fileName, Object value) throws IOException {
        var actual = objectMapper.writeValueAsString(value) + System.lineSeparator();
        var snapshot = RESOURCE_ROOT.resolve(fileName);
        if ("true".equalsIgnoreCase(System.getenv("UPDATE_E2E_COVERAGE_SNAPSHOTS"))) {
            Files.createDirectories(snapshot.getParent());
            Files.writeString(snapshot, actual);
        }
        assertTrue(Files.exists(snapshot), () -> "Missing snapshot file: " + snapshot);
                assertEquals(normalizeNewlines(Files.readString(snapshot)), normalizeNewlines(actual),
                                () -> "Snapshot mismatch for " + fileName);
    }

        private static String normalizeNewlines(String value) {
                return value.replace("\r\n", "\n");
        }
}
