package com.pulse.e2e.coverage;

import java.util.List;
import java.util.Map;

public record ActiveBlueprintCoverageArtifacts(
        ActiveBlueprintCatalog activeBlueprintCatalog,
        CoverageDenominator coverageDenominator,
        BlueprintFamilyPruning blueprintFamilyPruning,
        List<ScenarioMatrixGenerator.ScenarioCase> pairwiseScenarioSeeds,
        List<ScenarioMatrixGenerator.ScenarioCase> threeWayScenarioSeeds
) {

    public record ActiveBlueprintCatalog(
            String activeCatalogChecksum,
            int totalActiveBlueprints,
            List<ActiveBlueprintRecord> blueprints
    ) {}

    public record ActiveBlueprintRecord(
            String blueprintKey,
            String name,
            String category,
            String family,
            boolean pipelineConfig,
            String status,
            boolean deferred,
            String replacementBlueprintKey,
            String computeBackend,
            String compositionRole,
            String emitStrategy,
            boolean supportsReuse,
            List<String> validLayers,
            List<String> artifactTypes
    ) {}

    public record CoverageDenominator(
            String activeCatalogChecksum,
            String denominatorChecksum,
            int totalActiveBlueprints,
            int totalCoverageFamilies,
            boolean failOnUnclassified,
            List<String> includedBlueprintKeys,
            List<BlueprintClassificationRecord> compatibilityOnlyBlueprints,
            List<BlueprintClassificationRecord> excludedBlueprints,
            List<String> unclassifiedBlueprintKeys,
            Map<String, Integer> blueprintsByCategory,
            Map<String, Integer> blueprintsByFamily,
            Map<String, Integer> primaryPairwiseAxisCardinality,
            int primaryPairwiseSeedCount,
            int primaryThreeWaySeedCount,
            int primaryCartesianProductSize
    ) {}

    public record BlueprintFamilyPruning(
            String activeCatalogChecksum,
            List<FamilyCoverageRule> primaryCoverageFamilies,
            List<BlueprintClassificationRecord> compatibilityOnlyBlueprints,
            List<BlueprintClassificationRecord> excludedBlueprints,
            List<BlueprintClassificationRecord> classifications
    ) {}

    public record FamilyCoverageRule(
            String family,
            List<String> blueprintKeys,
            String rationale
    ) {}

    public record BlueprintClassificationRecord(
            String blueprintKey,
            String category,
            String compositionRole,
            List<String> artifactTypes,
            String computeBackend,
            String emitStrategy,
            boolean supportsReuse,
            boolean pipelineConfig,
            String status,
            boolean deferred,
            String replacementBlueprintKey,
            String scenarioFamily,
            String disposition,
            String reason
    ) {}
}
