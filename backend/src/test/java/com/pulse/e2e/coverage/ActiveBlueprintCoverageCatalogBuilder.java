package com.pulse.e2e.coverage;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.pulse.blueprint.model.Blueprint;
import com.pulse.blueprint.model.BlueprintCategory;

import java.security.MessageDigest;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static com.pulse.e2e.coverage.ActiveBlueprintCoverageArtifacts.*;

public class ActiveBlueprintCoverageCatalogBuilder {

    private final ScenarioMatrixGenerator scenarioMatrixGenerator = new ScenarioMatrixGenerator();
    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    public ActiveBlueprintCoverageArtifacts build(List<Blueprint> activeBlueprints, List<Blueprint> allBlueprints) {
        var activeRecords = activeBlueprints.stream()
                .sorted(Comparator.comparing(Blueprint::getBlueprintKey))
                .map(this::toRecord)
                .toList();

        var activeCatalogChecksum = checksum(Map.of(
                "totalActiveBlueprints", activeRecords.size(),
                "blueprints", activeRecords
        ));

        var unclassified = activeBlueprints.stream()
                .filter(this::isUnclassified)
                .map(Blueprint::getBlueprintKey)
                .sorted()
                .toList();

        var byCategory = activeBlueprints.stream()
                .collect(Collectors.groupingBy(bp -> bp.getCategory().name(), TreeMap::new, Collectors.summingInt(bp -> 1)));

        var byFamily = activeBlueprints.stream()
                .collect(Collectors.groupingBy(bp -> classify(bp).family().name(), TreeMap::new, Collectors.summingInt(bp -> 1)));

        var familyGroups = activeBlueprints.stream()
                .collect(Collectors.groupingBy(bp -> classify(bp).family().name(), TreeMap::new,
                        Collectors.mapping(Blueprint::getBlueprintKey,
                                Collectors.collectingAndThen(Collectors.toList(), list -> list.stream().sorted().toList()))));

        var primaryFamilies = familyGroups.entrySet().stream()
                .map(entry -> new FamilyCoverageRule(
                        entry.getKey(),
                        entry.getValue(),
                        "Primary pairwise/three-way matrix covers this active family directly"))
                .toList();

        var compatibilityOnlyBlueprints = allBlueprints.stream()
                .filter(this::isCompatibilityOnly)
                .sorted(Comparator.comparing(Blueprint::getBlueprintKey))
                .map(bp -> classificationRecord(bp, "compatibility_only", compatibilityReason(bp)))
                .toList();

        var excludedBlueprints = allBlueprints.stream()
                .filter(bp -> !isIncludedInPrimaryCoverage(bp) && !isCompatibilityOnly(bp))
                .sorted(Comparator.comparing(Blueprint::getBlueprintKey))
                .map(bp -> classificationRecord(bp, "excluded", exclusionReason(bp)))
                .toList();

        var classificationLog = allBlueprints.stream()
                .sorted(Comparator.comparing(Blueprint::getBlueprintKey))
                .map(bp -> {
                    if (isIncludedInPrimaryCoverage(bp)) {
                        return classificationRecord(bp, "primary", classify(bp).rationale());
                    }
                    if (isCompatibilityOnly(bp)) {
                        return classificationRecord(bp, "compatibility_only", compatibilityReason(bp));
                    }
                    return classificationRecord(bp, "excluded", exclusionReason(bp));
                })
                .toList();

        var matrixDimensions = defaultMatrixDimensions(primaryFamilies.stream().map(FamilyCoverageRule::family).toList());
        var pairwiseSeeds = scenarioMatrixGenerator.generate(matrixDimensions, 2);
        var threeWaySeeds = scenarioMatrixGenerator.generate(matrixDimensions, 3);
        var axisCardinality = matrixDimensions.stream()
                .collect(Collectors.toMap(ScenarioDimension::name, dim -> dim.values().size(), (a, b) -> a, LinkedHashMap::new));

        var includedBlueprintKeys = activeBlueprints.stream()
                .map(Blueprint::getBlueprintKey)
                .sorted()
                .toList();

        var denominatorChecksum = checksum(Map.of(
                "activeCatalogChecksum", activeCatalogChecksum,
                "includedBlueprintKeys", includedBlueprintKeys,
                "compatibilityOnlyBlueprints", compatibilityOnlyBlueprints,
                "excludedBlueprints", excludedBlueprints,
                "blueprintsByCategory", byCategory,
                "blueprintsByFamily", byFamily
        ));

        return new ActiveBlueprintCoverageArtifacts(
                new ActiveBlueprintCatalog(activeCatalogChecksum, activeRecords.size(), activeRecords),
                new CoverageDenominator(
                        activeCatalogChecksum,
                        denominatorChecksum,
                        activeRecords.size(),
                        familyGroups.size(),
                        true,
                        includedBlueprintKeys,
                        compatibilityOnlyBlueprints,
                        excludedBlueprints,
                        unclassified,
                        byCategory,
                        byFamily,
                        axisCardinality,
                        pairwiseSeeds.size(),
                        threeWaySeeds.size(),
                        scenarioMatrixGenerator.cartesianProductSize(matrixDimensions)),
                new BlueprintFamilyPruning(
                        activeCatalogChecksum,
                        primaryFamilies,
                        compatibilityOnlyBlueprints,
                        excludedBlueprints,
                        classificationLog),
                pairwiseSeeds,
                threeWaySeeds
        );
    }

    public List<ScenarioDimension> defaultMatrixDimensions(List<String> activeFamilies) {
        return List.of(
                new ScenarioDimension("blueprint_family", activeFamilies),
                new ScenarioDimension("storage_backend", List.of("DPC", "GCP")),
                new ScenarioDimension("lake_layer", List.of("bronze", "silver", "gold")),
                new ScenarioDimension("orchestration_mode", List.of("manual", "cron", "event")),
                new ScenarioDimension("runtime_proof_mode", List.of("artifact_only", "static_deployability", "live_runtime"))
        );
    }

    private ActiveBlueprintRecord toRecord(Blueprint blueprint) {
        var classification = classify(blueprint);
        return new ActiveBlueprintRecord(
                blueprint.getBlueprintKey(),
                blueprint.getName(),
                blueprint.getCategory().name(),
                classification.family().name(),
                blueprint.isPipelineConfig(),
                blueprint.getStatus(),
                blueprint.isDeferred(),
                blueprint.getReplacementBlueprintKey(),
                blueprint.getComputeBackend(),
                blueprint.getCompositionRole(),
                blueprint.getEmitStrategy(),
                blueprint.isSupportsReuse(),
                sortedCopy(blueprint.getValidLayers()),
                sortedCopy(blueprint.getArtifactTypes())
        );
    }

    private boolean isUnclassified(Blueprint blueprint) {
        try {
            classify(blueprint);
            return false;
        } catch (IllegalArgumentException ex) {
            return true;
        }
    }

    private BlueprintClassification classify(Blueprint blueprint) {
        BlueprintCategory category = blueprint.getCategory();
        if (category == null) {
            throw new IllegalArgumentException("Blueprint category is required for " + blueprint.getBlueprintKey());
        }

        if (blueprint.isSupportsReuse()) {
            return new BlueprintClassification(
                    BlueprintFamily.REUSE_CAPABLE,
                    "supports_reuse=true routes this blueprint into reuse-capable coverage");
        }
        if (blueprint.isPipelineConfig()) {
            return new BlueprintClassification(
                    BlueprintFamily.ORCHESTRATION_POLICY,
                    "pipeline_config=true routes this blueprint into orchestration policy coverage");
        }

        var compositionRole = normalize(blueprint.getCompositionRole());
        var artifactTypes = sortedCopy(blueprint.getArtifactTypes()).stream()
                .map(this::normalize)
                .filter(Objects::nonNull)
                .toList();

        if ("orchestration_sensor".equals(compositionRole) || artifactTypes.contains("airflow_sensor")) {
            return new BlueprintClassification(
                    BlueprintFamily.ORCHESTRATION_SENSOR,
                    "composition_role/artifact_types indicate an orchestration sensor");
        }
        if ("orchestration_policy".equals(compositionRole) || artifactTypes.contains("airflow_policy")) {
            return new BlueprintClassification(
                    BlueprintFamily.ORCHESTRATION_POLICY,
                    "composition_role/artifact_types indicate an orchestration policy");
        }
        if (isRuntimeOnly(blueprint, artifactTypes)) {
            return new BlueprintClassification(
                    BlueprintFamily.RUNTIME_ONLY,
                    "runtime-only signals (emit strategy / backend / artifacts) exclude this blueprint from compile-path families");
        }

        return switch (category) {
            case INGESTION -> new BlueprintClassification(BlueprintFamily.INGESTION, "category=INGESTION");
            case TRANSFORM -> new BlueprintClassification(BlueprintFamily.TRANSFORM, "category=TRANSFORM");
            case MODELING -> new BlueprintClassification(BlueprintFamily.MODELING, "category=MODELING");
            case DATA_QUALITY -> new BlueprintClassification(BlueprintFamily.DATA_QUALITY, "category=DATA_QUALITY");
            case DESTINATION -> new BlueprintClassification(BlueprintFamily.DESTINATION, "category=DESTINATION");
            case ORCHESTRATION -> throw new IllegalArgumentException(
                    "BLUEPRINT_CLASSIFICATION_AMBIGUOUS for " + blueprint.getBlueprintKey()
                            + ": ORCHESTRATION blueprints must resolve to sensor, policy, or runtime_only via composition role / artifacts");
        };
    }

    private boolean isRuntimeOnly(Blueprint blueprint, List<String> artifactTypes) {
        var computeBackend = normalize(blueprint.getComputeBackend());
        var emitStrategy = normalize(blueprint.getEmitStrategy());
        var validLayers = sortedCopy(blueprint.getValidLayers()).stream()
                .map(this::normalize)
                .filter(Objects::nonNull)
                .toList();
        return "runtime".equals(computeBackend)
                || "runtime_only".equals(emitStrategy)
                || artifactTypes.stream().anyMatch(type -> type.startsWith("runtime_"))
                || (validLayers.contains("runtime") && artifactTypes.isEmpty());
    }

    private boolean isIncludedInPrimaryCoverage(Blueprint blueprint) {
        return "active".equalsIgnoreCase(blueprint.getStatus()) && !blueprint.isDeferred();
    }

    private boolean isCompatibilityOnly(Blueprint blueprint) {
        return !isIncludedInPrimaryCoverage(blueprint)
                && blueprint.getReplacementBlueprintKey() != null
                && !blueprint.getReplacementBlueprintKey().isBlank();
    }

    private BlueprintClassificationRecord classificationRecord(Blueprint blueprint, String disposition, String reason) {
        var classification = classify(blueprint);
        return new BlueprintClassificationRecord(
                blueprint.getBlueprintKey(),
                blueprint.getCategory().name(),
                blueprint.getCompositionRole(),
                sortedCopy(blueprint.getArtifactTypes()),
                blueprint.getComputeBackend(),
                blueprint.getEmitStrategy(),
                blueprint.isSupportsReuse(),
                blueprint.isPipelineConfig(),
                blueprint.getStatus(),
                blueprint.isDeferred(),
                blueprint.getReplacementBlueprintKey(),
                classification.family().name(),
                disposition,
                reason
        );
    }

    private String compatibilityReason(Blueprint blueprint) {
        return "Excluded from primary coverage because blueprint is deprecated/deferred, but replacement_blueprint_key="
                + blueprint.getReplacementBlueprintKey()
                + " allows deterministic legacy compatibility scenarios";
    }

    private String exclusionReason(Blueprint blueprint) {
        if (blueprint.isDeferred() && !"active".equalsIgnoreCase(blueprint.getStatus())) {
            return "Excluded from primary coverage because blueprint is deferred and not active, and no replacement mapping enables compatibility coverage";
        }
        if (blueprint.isDeferred()) {
            return "Excluded from primary coverage because blueprint is deferred and no compatibility mapping is declared";
        }
        return "Excluded from primary coverage because blueprint status is " + blueprint.getStatus()
                + " and no compatibility mapping is declared";
    }

    private List<String> sortedCopy(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().filter(Objects::nonNull).sorted().toList();
    }

    private String normalize(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private String checksum(Object value) {
        try {
            return hexDigest(objectMapper.writeValueAsBytes(value));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize coverage artifact for checksum", e);
        }
    }

    private String hexDigest(byte[] bytes) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var hashed = digest.digest(bytes);
            var builder = new StringBuilder(hashed.length * 2);
            for (byte value : hashed) {
                builder.append("%02x".formatted(value));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute coverage artifact checksum", e);
        }
    }

    private record BlueprintClassification(BlueprintFamily family, String rationale) {
    }
}
