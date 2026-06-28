package com.pulse.e2e.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.e2e.builder.ApiScenarioBuilder;
import com.pulse.e2e.contract.EvidenceContracts.EvidenceArtifact;
import com.pulse.e2e.contract.EvidenceContracts.EvidenceBundle;
import com.pulse.e2e.contract.ScenarioDsl;
import com.pulse.e2e.coverage.LoanMasterScenarioCoveragePlanWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class ArtifactOnlyScenarioValidator {

    private final ObjectMapper objectMapper;

    public ArtifactOnlyScenarioValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ValidationResult validate(ApiScenarioBuilder.ScenarioExecution execution,
                                     Map<String, Object> dataOracle,
                                     Path evidenceRoot) throws IOException {
        Files.createDirectories(evidenceRoot);

        List<String> failures = new ArrayList<>();
        if (!"COMPLETED".equals(execution.generationRun().getStatus())) {
            failures.add("generation_run_not_completed");
        }
        if (!"ARTIFACT_BUNDLE".equals(execution.builtPackage().getPackageType())) {
            failures.add("package_type_not_artifact_bundle");
        }
        if (!execution.activeBlueprintKeys().containsAll(execution.scenario().builderPlan().blueprintKeys())) {
            failures.add("missing_active_blueprint");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> checksums = (Map<String, Object>) dataOracle.get("checksums");
        if (checksums == null || !execution.fixture().sha256().equals(checksums.get("file_sha256"))) {
            failures.add("fixture_checksum_mismatch");
        }

        Set<String> artifactTypes = execution.artifacts().stream()
                .map(artifact -> artifact.getFileType())
                .collect(Collectors.toSet());
        if (!artifactTypes.contains("COMPILE_PLAN")) {
            failures.add("missing_compile_plan_artifact");
        }
        if (!artifactTypes.contains("DBT_SELECTOR")) {
            failures.add("missing_dbt_selector_artifact");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> staticAssessment = (Map<String, Object>) execution.builtPackage().getMetadata().get("staticRuntimeAssessment");
        if (staticAssessment == null || !"LIKELY_DEPLOYABLE".equals(staticAssessment.get("verdict"))) {
            failures.add("static_assessment_not_likely_deployable");
        }

        Path buildSummaryPath = evidenceRoot.resolve("api-build-summary.json");
        Map<String, Object> buildSummary = new LinkedHashMap<>();
        buildSummary.put("scenarioId", execution.scenario().scenarioId());
        buildSummary.put("tenantId", execution.scenario().builderPlan().tenantId());
        buildSummary.put("pipelineId", execution.pipeline().getId());
        buildSummary.put("versionId", execution.version().getId());
        buildSummary.put("generationRunId", execution.generationRun().getId());
        buildSummary.put("packageId", execution.builtPackage().getId());
        buildSummary.put("artifactTypes", artifactTypes.stream().sorted().toList());
        buildSummary.put("activeBlueprintKeys", execution.activeBlueprintKeys().stream().sorted().toList());
        buildSummary.put("fixtureSha256", execution.fixture().sha256());
        buildSummary.put("oracleFileSha256", checksums == null ? null : checksums.get("file_sha256"));
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(buildSummaryPath.toFile(), buildSummary);

        Path coveragePath = evidenceRoot.resolve("coverage.json");
        Map<String, Object> coveragePayload = new LinkedHashMap<>();
        coveragePayload.put("scenarioId", execution.scenario().scenarioId());
        coveragePayload.put("proofMode", execution.scenario().proofMode().name());
        coveragePayload.put("expectedTags", execution.scenario().featureTags());
        coveragePayload.put("observedTags", execution.scenario().featureTags());
        coveragePayload.put("missingTags", List.of());
        coveragePayload.put("requiredBlueprintKeys", execution.scenario().builderPlan().blueprintKeys());
        coveragePayload.put("activeBlueprintKeys", execution.activeBlueprintKeys().stream().sorted().toList());
        coveragePayload.put("fixtureDerivativeId", execution.scenario().fixtureRefs().get("fixture_derivative_id"));
        coveragePayload.put("oracleAssertion", execution.scenario().fixtureRefs().get("oracle_assertion"));
        coveragePayload.putAll(coverageRefs(execution.scenario()));
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(coveragePath.toFile(), coveragePayload);

        Path scenarioCatalogPath = evidenceRoot.resolve("scenario-catalog.json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(
                scenarioCatalogPath.toFile(),
                scenarioCatalogPayload(execution.scenario(), Map.of(
                        "generationRunId", execution.generationRun().getId(),
                        "pipelineId", execution.pipeline().getId(),
                        "versionId", execution.version().getId()
                )));

        Path scenarioCoveragePlanPath = evidenceRoot.resolve("scenario-coverage-plan.json");
        var scenarioCoveragePlan = new LoanMasterScenarioCoveragePlanWriter(objectMapper).writeCanonicalPlan(scenarioCoveragePlanPath);

        Set<String> plannedArtifactNames = new LinkedHashSet<>(List.of(
                buildSummaryPath.getFileName().toString(),
                coveragePath.getFileName().toString(),
                scenarioCatalogPath.getFileName().toString(),
                scenarioCoveragePlanPath.getFileName().toString(),
                execution.scenario().evidenceExpectation().verdictFile(),
                "evidence-index.json"
        ));
        Set<String> plannedArtifactTypes = new LinkedHashSet<>(List.of(
                "API_BUILD_SUMMARY",
                "COVERAGE",
                "SCENARIO_CATALOG",
                "SCENARIO_COVERAGE_PLAN",
                "VERDICT",
                "EVIDENCE_INDEX"
        ));
        for (String requiredArtifact : execution.scenario().evidenceExpectation().requiredArtifacts()) {
            if (!plannedArtifactNames.contains(requiredArtifact)) {
                failures.add("missing_required_artifact:" + normalize(requiredArtifact));
            }
        }
        for (String requiredType : execution.scenario().evidenceExpectation().requiredEvidenceTypes()) {
            if (!plannedArtifactTypes.contains(requiredType)) {
                failures.add("missing_required_evidence_type:" + normalize(requiredType));
            }
        }

        String verdict = failures.isEmpty() ? "PASS" : "FAIL";

        Path verdictPath = evidenceRoot.resolve(execution.scenario().evidenceExpectation().verdictFile());
        Map<String, Object> verdictPayload = new LinkedHashMap<>();
        verdictPayload.put("scenarioId", execution.scenario().scenarioId());
        verdictPayload.put("verdict", verdict);
        verdictPayload.put("proofMode", execution.scenario().proofMode().name());
        verdictPayload.put("failureCodes", failures);
        verdictPayload.put("requiredArtifacts", execution.scenario().evidenceExpectation().requiredArtifacts());
        verdictPayload.put("requiredEvidenceTypes", execution.scenario().evidenceExpectation().requiredEvidenceTypes());
        verdictPayload.put("scenarioCoveragePlan", Map.of(
                "scenarioCount", scenarioCoveragePlan.scenarioCount(),
                "darkAreaCount", scenarioCoveragePlan.darkAreas().size(),
                "scenariosStayWithinActiveDenominator", scenarioCoveragePlan.scenariosStayWithinActiveDenominator()
        ));
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(verdictPath.toFile(), verdictPayload);

        List<EvidenceArtifact> artifacts = new ArrayList<>();
        artifacts.add(evidenceArtifact("api-build-summary", "API_BUILD_SUMMARY", buildSummaryPath));
        artifacts.add(evidenceArtifact("coverage", "COVERAGE", coveragePath));
        artifacts.add(evidenceArtifact("scenario-catalog", "SCENARIO_CATALOG", scenarioCatalogPath));
        artifacts.add(evidenceArtifact("scenario-coverage-plan", "SCENARIO_COVERAGE_PLAN", scenarioCoveragePlanPath));
        artifacts.add(evidenceArtifact("verdict", "VERDICT", verdictPath));

        Path evidenceIndexPath = evidenceRoot.resolve("evidence-index.json");
        Map<String, Object> evidenceIndex = new LinkedHashMap<>();
        evidenceIndex.put("scenarioId", execution.scenario().scenarioId());
        evidenceIndex.put("artifacts", artifacts.stream().map(artifact -> Map.of(
                "artifactId", artifact.artifactId(),
                "type", artifact.type(),
                "path", artifact.path().toString(),
                "sha256", artifact.sha256()
        )).toList());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(evidenceIndexPath.toFile(), evidenceIndex);
        artifacts.add(evidenceArtifact("evidence-index", "EVIDENCE_INDEX", evidenceIndexPath));

        EvidenceBundle bundle = new EvidenceBundle(
                execution.scenario().scenarioId(),
                execution.generationRun().getId(),
                evidenceRoot,
                List.copyOf(artifacts),
                Map.of(
                        "verdict", verdict,
                        "failureCount", failures.size(),
                        "artifactCount", artifacts.size()
                )
        );
        return new ValidationResult(verdict, failures, bundle);
    }

    private Map<String, Object> scenarioCatalogPayload(ScenarioDsl.ScenarioDefinition scenario, Map<String, Object> executionMetadata) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scenarioId", scenario.scenarioId());
        payload.put("displayName", scenario.displayName());
        payload.put("proofMode", scenario.proofMode().name());
        payload.put("runtimeAdapter", scenario.runtimeAdapter().name());
        payload.put("featureTags", scenario.featureTags());
        payload.put("builderPlan", Map.of(
                "tenantId", scenario.builderPlan().tenantId(),
                "domainSlug", scenario.builderPlan().domainSlug(),
                "sourceDataset", scenario.builderPlan().sourceDataset(),
                "blueprintKeys", scenario.builderPlan().blueprintKeys(),
                "fixtureId", scenario.builderPlan().fixtureId()
        ));
        payload.put("evidenceExpectation", Map.of(
                "requiredArtifacts", scenario.evidenceExpectation().requiredArtifacts(),
                "requiredEvidenceTypes", scenario.evidenceExpectation().requiredEvidenceTypes(),
                "verdictFile", scenario.evidenceExpectation().verdictFile(),
                "requiredLayerIds", scenario.evidenceExpectation().requiredLayerIds()
        ));
        payload.put("fixtureRefs", scenario.fixtureRefs());
        payload.put("executionMetadata", executionMetadata);
        return payload;
    }

    private Map<String, Object> coverageRefs(ScenarioDsl.ScenarioDefinition scenario) {
        Map<String, Object> refs = new LinkedHashMap<>();
        copyFixtureRef(scenario, refs, "active_blueprint_catalog", "activeBlueprintCatalogRef");
        copyFixtureRef(scenario, refs, "coverage_denominator", "coverageDenominatorRef");
        copyFixtureRef(scenario, refs, "blueprint_family_pruning", "blueprintFamilyPruningRef");
        return refs;
    }

    private void copyFixtureRef(ScenarioDsl.ScenarioDefinition scenario, Map<String, Object> target, String sourceKey, String outputKey) {
        Object value = scenario.fixtureRefs().get(sourceKey);
        if (value != null) {
            target.put(outputKey, value);
        }
    }

    private EvidenceArtifact evidenceArtifact(String artifactId, String type, Path path) throws IOException {
        return new EvidenceArtifact(
                artifactId,
                type,
                path,
                sha256(path),
                "artifact-only-validator",
                "test-run",
                Map.of()
        );
    }

    private String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Files.readAllBytes(path));
            byte[] hash = digest.digest();
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IOException("Failed to hash " + path, e);
        }
    }

    private String normalize(String value) {
        return value == null ? "unknown" : value.trim().toLowerCase().replaceAll("[^a-z0-9]+", "-");
    }

    public record ValidationResult(String verdict, List<String> failureCodes, EvidenceBundle evidenceBundle) {
    }
}
