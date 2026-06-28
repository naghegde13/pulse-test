package com.pulse.e2e.coverage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.blueprint.model.Blueprint;
import com.pulse.e2e.scenarios.LoanMasterScenarioFamilyCatalog;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class LoanMasterScenarioCoveragePlanWriter {

    private final ObjectMapper objectMapper;
    private final ActiveBlueprintCoverageCatalogBuilder coverageCatalogBuilder = new ActiveBlueprintCoverageCatalogBuilder();
    private final LoanMasterScenarioCoveragePlanBuilder coveragePlanBuilder = new LoanMasterScenarioCoveragePlanBuilder();
    private final LoanMasterScenarioFamilyCatalog scenarioCatalogBuilder = new LoanMasterScenarioFamilyCatalog();

    public LoanMasterScenarioCoveragePlanWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy().findAndRegisterModules();
    }

    public LoanMasterScenarioCoveragePlanBuilder.LoanMasterScenarioCoveragePlan writeCanonicalPlan(Path outputPath) throws IOException {
        var activeBlueprints = loadBlueprints("e2e/coverage/api-active-blueprints.json");
        var allBlueprints = loadBlueprints("e2e/coverage/api-all-blueprints.json");
        var coverageArtifacts = coverageCatalogBuilder.build(activeBlueprints, allBlueprints);
        var scenarios = scenarioCatalogBuilder.build(loadActiveCatalogRecords(), loadFixtureDerivativeIds()).scenarios().stream()
                .map(LoanMasterScenarioFamilyCatalog.ScenarioFamilySpec::scenario)
                .toList();
        var plan = coveragePlanBuilder.build(scenarios, coverageArtifacts);
        Files.createDirectories(outputPath.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputPath.toFile(), plan);
        return plan;
    }

    private List<Blueprint> loadBlueprints(String resourcePath) throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("Missing resource: " + resourcePath);
            }
            return objectMapper.readValue(in, new TypeReference<>() {});
        }
    }

    private List<LoanMasterScenarioFamilyCatalog.ActiveBlueprintCatalogRecord> loadActiveCatalogRecords() throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("e2e/coverage/active-blueprint-catalog.json")) {
            if (in == null) {
                throw new IOException("Missing resource: e2e/coverage/active-blueprint-catalog.json");
            }
            var root = objectMapper.readTree(in);
            return objectMapper.convertValue(root.path("blueprints"), new TypeReference<>() {});
        }
    }

    private Set<String> loadFixtureDerivativeIds() throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("e2e/fixtures/loan_master/fixture-manifest.json")) {
            if (in == null) {
                throw new IOException("Missing resource: e2e/fixtures/loan_master/fixture-manifest.json");
            }
            var payload = objectMapper.readTree(in);
            var derivatives = payload.get("derivatives");
            if (derivatives == null) {
                throw new IOException("fixture-manifest.json missing derivatives array");
            }
            return StreamSupport.stream(derivatives.spliterator(), false)
                    .map(entry -> entry.get("derivative_id").asText())
                    .collect(Collectors.toSet());
        }
    }
}
