package com.pulse.e2e.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.e2e.contract.EvidenceContracts.EvidenceArtifact;
import com.pulse.e2e.contract.EvidenceContracts.EvidenceBundle;
import com.pulse.e2e.contract.EvidenceContracts.LayerVerdict;
import com.pulse.e2e.contract.EvidenceContracts.Verdict;
import com.pulse.e2e.contract.ScenarioDsl;
import com.pulse.e2e.semantic.SemanticHardeningEvidenceContracts;
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
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class LiveRuntimeScenarioValidator {

    private final ObjectMapper objectMapper;

    public LiveRuntimeScenarioValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ValidationResult validate(RuntimeValidationRequest request) throws IOException {
        Files.createDirectories(request.evidenceRoot());
        String runtimeNamespace = resolveRuntimeNamespace(request);

        List<String> failureCodes = new ArrayList<>();
        List<EvidenceArtifact> artifacts = new ArrayList<>();
        for (ArtifactCandidate candidate : request.artifacts()) {
            if (candidate.path() == null || !Files.exists(candidate.path())) {
                failureCodes.add("missing_artifact_file:" + normalize(candidate.artifactId()));
                continue;
            }
            artifacts.add(evidenceArtifact(candidate));
        }

        Path coveragePath = request.evidenceRoot().resolve("coverage.json");
        Set<String> expectedCoverageTags = new LinkedHashSet<>(request.scenario().featureTags());
        Set<String> observedCoverageTags = new LinkedHashSet<>(request.observedCoverageTags());
        List<String> missingCoverageTags = expectedCoverageTags.stream()
                .filter(tag -> !observedCoverageTags.contains(tag))
                .toList();
        List<String> unexpectedCoverageTags = observedCoverageTags.stream()
                .filter(tag -> !expectedCoverageTags.contains(tag))
                .toList();
        Map<String, Object> coveragePayload = new LinkedHashMap<>();
        coveragePayload.put("scenarioId", request.scenario().scenarioId());
        coveragePayload.put("scenarioVariantId", request.scenarioVariantId());
        coveragePayload.put("expectedTags", List.copyOf(expectedCoverageTags));
        coveragePayload.put("observedTags", List.copyOf(observedCoverageTags));
        coveragePayload.put("missingTags", missingCoverageTags);
        coveragePayload.put("unexpectedTags", unexpectedCoverageTags);
        coveragePayload.put("expectedTagCount", expectedCoverageTags.size());
        coveragePayload.put("observedTagCount", observedCoverageTags.size());
        coveragePayload.put("missingTagCount", missingCoverageTags.size());
        coveragePayload.put("coverageVerdict", missingCoverageTags.isEmpty() ? Verdict.PASS.name() : Verdict.FAIL.name());
        writeJson(coveragePath, coveragePayload);
        artifacts.add(evidenceArtifact(new ArtifactCandidate(
                "coverage",
                "COVERAGE",
                coveragePath,
                "live-runtime-validator",
                "test-run",
                Map.of("tagCount", observedCoverageTags.size())
        )));
        for (String tag : missingCoverageTags) {
            failureCodes.add("missing_coverage_tag:" + normalize(tag));
        }

        Path scenarioCatalogPath = request.evidenceRoot().resolve("scenario-catalog.json");
        writeJson(scenarioCatalogPath, scenarioCatalogPayload(request.scenario(), runtimeNamespace, Map.of(
                "scenarioVariantId", request.scenarioVariantId(),
                "generationRunId", request.generationRunId(),
                "gitSha", request.gitSha(),
                "composeSignature", request.composeSignature(),
                "retryIndex", request.retryIndex()
        )));
        artifacts.add(evidenceArtifact(new ArtifactCandidate(
                "scenario-catalog",
                "SCENARIO_CATALOG",
                scenarioCatalogPath,
                "live-runtime-validator",
                "test-run",
                Map.of("blueprintCount", request.scenario().builderPlan().blueprintKeys().size())
        )));

        Path scenarioCoveragePlanPath = request.evidenceRoot().resolve("scenario-coverage-plan.json");
        var scenarioCoveragePlan = new LoanMasterScenarioCoveragePlanWriter(objectMapper).writeCanonicalPlan(scenarioCoveragePlanPath);
        artifacts.add(evidenceArtifact(new ArtifactCandidate(
                "scenario-coverage-plan",
                "SCENARIO_COVERAGE_PLAN",
                scenarioCoveragePlanPath,
                "live-runtime-validator",
                "test-run",
                Map.of("darkAreaCount", scenarioCoveragePlan.darkAreas().size())
        )));

        Set<String> plannedArtifactPaths = artifacts.stream()
                .map(this::artifactContractPath)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        plannedArtifactPaths.add(normalizeContractPath(request.scenario().evidenceExpectation().verdictFile()));
        plannedArtifactPaths.add("evidence-index.json");

        Set<String> plannedArtifactTypes = artifacts.stream()
                .map(EvidenceArtifact::type)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        plannedArtifactTypes.add("VERDICT");
        plannedArtifactTypes.add("EVIDENCE_INDEX");

        Set<String> observedLayerIds = request.layerVerdicts().stream()
                .map(LayerVerdict::layerId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        for (String requiredArtifact : request.scenario().evidenceExpectation().requiredArtifacts()) {
            if (!matchesRequiredArtifact(requiredArtifact, plannedArtifactPaths)) {
                failureCodes.add("missing_required_artifact:" + normalize(requiredArtifact));
            }
        }
        for (String requiredType : request.scenario().evidenceExpectation().requiredEvidenceTypes()) {
            if (!plannedArtifactTypes.contains(requiredType)) {
                failureCodes.add("missing_required_evidence_type:" + normalize(requiredType));
            }
        }
        for (String requiredLayer : request.scenario().evidenceExpectation().requiredLayerIds()) {
            if (!observedLayerIds.contains(requiredLayer)) {
                failureCodes.add("missing_required_layer:" + normalize(requiredLayer));
            }
        }

        for (LayerVerdict layerVerdict : request.layerVerdicts()) {
            switch (layerVerdict.verdict()) {
                case FAIL -> failureCodes.add("layer_failed:" + normalize(layerVerdict.layerId()));
                case FLAKY -> failureCodes.add("layer_flaky:" + normalize(layerVerdict.layerId()));
                case INFRA_BLOCKED -> failureCodes.add("infra_blocked:" + normalize(layerVerdict.layerId()));
                case PASS -> {
                }
            }
            failureCodes.addAll(layerVerdict.failureCodes());
        }
        failureCodes = failureCodes.stream().distinct().toList();

        Verdict verdict = deriveVerdict(request.layerVerdicts(), failureCodes);

        Path verdictPath = request.evidenceRoot().resolve(request.scenario().evidenceExpectation().verdictFile());
        Map<String, Object> verdictPayload = new LinkedHashMap<>();
        verdictPayload.put("scenarioId", request.scenario().scenarioId());
        verdictPayload.put("scenarioVariantId", request.scenarioVariantId());
        verdictPayload.put("generationRunId", request.generationRunId());
        verdictPayload.put("proofMode", request.scenario().proofMode().name());
        verdictPayload.put("runtimeAdapter", request.scenario().runtimeAdapter().name());
        verdictPayload.put("runtimeNamespace", runtimeNamespace);
        verdictPayload.put("verdict", verdict.name());
        verdictPayload.put("gitSha", request.gitSha());
        verdictPayload.put("composeSignature", request.composeSignature());
        verdictPayload.put("retryIndex", request.retryIndex());
        verdictPayload.put("coverageTags", List.copyOf(observedCoverageTags));
        verdictPayload.put("missingCoverageTags", missingCoverageTags);
        verdictPayload.put("failureCodes", failureCodes);
        verdictPayload.put("requiredArtifacts", request.scenario().evidenceExpectation().requiredArtifacts());
        verdictPayload.put("requiredEvidenceTypes", request.scenario().evidenceExpectation().requiredEvidenceTypes());
        verdictPayload.put("requiredLayerIds", request.scenario().evidenceExpectation().requiredLayerIds());
        verdictPayload.put("layerVerdicts", request.layerVerdicts().stream().map(layer -> Map.of(
                "layerId", layer.layerId(),
                "verdict", layer.verdict().name(),
                "failureCodes", layer.failureCodes(),
                "details", layer.details()
        )).toList());
        writeJson(verdictPath, verdictPayload);
        artifacts.add(evidenceArtifact(new ArtifactCandidate(
                "verdict",
                "VERDICT",
                verdictPath,
                "live-runtime-validator",
                "test-run",
                Map.of("failureCount", failureCodes.size())
        )));

        Path evidenceIndexPath = request.evidenceRoot().resolve("evidence-index.json");
        Map<String, Object> evidenceIndex = new LinkedHashMap<>();
        evidenceIndex.put("scenarioId", request.scenario().scenarioId());
        evidenceIndex.put("scenarioVariantId", request.scenarioVariantId());
        evidenceIndex.put("generationRunId", request.generationRunId());
        evidenceIndex.put("proofMode", request.scenario().proofMode().name());
        evidenceIndex.put("runtimeAdapter", request.scenario().runtimeAdapter().name());
        evidenceIndex.put("runtimeNamespace", runtimeNamespace);
        evidenceIndex.put("artifacts", artifacts.stream().map(artifact -> Map.of(
                "artifactId", artifact.artifactId(),
                "type", artifact.type(),
                "path", artifact.path().toString(),
                "contractPath", artifactContractPath(artifact),
                "sha256", artifact.sha256(),
                "producingAdapter", artifact.producingAdapter(),
                "retentionPolicy", artifact.retentionPolicy(),
                "metadata", artifact.metadata()
        )).toList());
        writeJson(evidenceIndexPath, evidenceIndex);
        artifacts.add(evidenceArtifact(new ArtifactCandidate(
                "evidence-index",
                "EVIDENCE_INDEX",
                evidenceIndexPath,
                "live-runtime-validator",
                "test-run",
                Map.of("entries", artifacts.size())
        )));

        EvidenceBundle bundle = new EvidenceBundle(
                request.scenario().scenarioId(),
                request.generationRunId(),
                request.evidenceRoot(),
                List.copyOf(artifacts),
                Map.of(
                        "scenarioVariantId", request.scenarioVariantId(),
                        "verdict", verdict.name(),
                        "runtimeAdapter", request.scenario().runtimeAdapter().name(),
                        "runtimeNamespace", runtimeNamespace,
                        "failureCount", failureCodes.size(),
                        "artifactCount", artifacts.size(),
                        "layerCount", request.layerVerdicts().size()
                )
        );
        return new ValidationResult(verdict, failureCodes, bundle, verdictPath, coveragePath);
    }

    private Map<String, Object> scenarioCatalogPayload(ScenarioDsl.ScenarioDefinition scenario,
                                                       String runtimeNamespace,
                                                       Map<String, Object> executionMetadata) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scenarioId", scenario.scenarioId());
        payload.put("displayName", scenario.displayName());
        payload.put("proofMode", scenario.proofMode().name());
        payload.put("runtimeAdapter", scenario.runtimeAdapter().name());
        payload.put("runtimeNamespace", runtimeNamespace);
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

    private String resolveRuntimeNamespace(RuntimeValidationRequest request) {
        String runtimeNamespace = SemanticHardeningEvidenceContracts.runtimeNamespaceFor(request.scenario().runtimeAdapter());
        if (!SemanticHardeningEvidenceContracts.evidenceRootMatchesRuntimeNamespace(request.evidenceRoot(), runtimeNamespace)) {
            throw new IllegalArgumentException("Evidence root namespace does not match runtime adapter: "
                    + request.evidenceRoot() + " vs " + runtimeNamespace);
        }
        return runtimeNamespace;
    }

    private Verdict deriveVerdict(List<LayerVerdict> layerVerdicts, List<String> failureCodes) {
        boolean hasInfraBlocked = layerVerdicts.stream().anyMatch(layer -> layer.verdict() == Verdict.INFRA_BLOCKED)
                || failureCodes.stream().anyMatch(code -> code.startsWith("infra_blocked:"));
        if (hasInfraBlocked) {
            return Verdict.INFRA_BLOCKED;
        }
        boolean hasFailure = layerVerdicts.stream().anyMatch(layer -> layer.verdict() == Verdict.FAIL)
                || failureCodes.stream().anyMatch(code -> code.startsWith("missing_") || code.startsWith("layer_failed:"));
        if (hasFailure) {
            return Verdict.FAIL;
        }
        boolean hasFlaky = layerVerdicts.stream().anyMatch(layer -> layer.verdict() == Verdict.FLAKY)
                || failureCodes.stream().anyMatch(code -> code.startsWith("layer_flaky:"));
        if (hasFlaky) {
            return Verdict.FLAKY;
        }
        return Verdict.PASS;
    }

    private EvidenceArtifact evidenceArtifact(ArtifactCandidate candidate) throws IOException {
        return new EvidenceArtifact(
                candidate.artifactId(),
                candidate.type(),
                candidate.path(),
                sha256(candidate.path()),
                candidate.producingAdapter(),
                candidate.retentionPolicy(),
                candidate.metadata()
        );
    }

    private String artifactContractPath(EvidenceArtifact artifact) {
        Object contractPath = artifact.metadata().get("contractPath");
        if (contractPath != null && !String.valueOf(contractPath).isBlank()) {
            return normalizeContractPath(String.valueOf(contractPath));
        }
        return normalizeContractPath(artifact.path().getFileName().toString());
    }

    private boolean matchesRequiredArtifact(String requiredArtifact, Set<String> plannedArtifactPaths) {
        String normalizedRequiredArtifact = normalizeContractPath(requiredArtifact);
        if (!normalizedRequiredArtifact.contains("*")) {
            return plannedArtifactPaths.contains(normalizedRequiredArtifact);
        }
        Pattern pattern = Pattern.compile(globToRegex(normalizedRequiredArtifact));
        return plannedArtifactPaths.stream().anyMatch(path -> pattern.matcher(path).matches());
    }

    private String normalizeContractPath(String value) {
        return value == null ? "" : value.replace('\\', '/').replaceFirst("^\\./", "");
    }

    private String globToRegex(String glob) {
        StringBuilder regex = new StringBuilder("^");
        for (int index = 0; index < glob.length(); index++) {
            char current = glob.charAt(index);
            if (current == '*') {
                regex.append("[^/]+");
            } else {
                if ("\\.[]{}()+-^$?|".indexOf(current) >= 0) {
                    regex.append('\\');
                }
                regex.append(current);
            }
        }
        regex.append('$');
        return regex.toString();
    }

    private void writeJson(Path target, Map<String, Object> payload) throws IOException {
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), payload);
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

    public record ArtifactCandidate(
            String artifactId,
            String type,
            Path path,
            String producingAdapter,
            String retentionPolicy,
            Map<String, Object> metadata
    ) {
        public ArtifactCandidate {
            producingAdapter = (producingAdapter == null || producingAdapter.isBlank())
                    ? "live-runtime-adapter"
                    : producingAdapter;
            retentionPolicy = (retentionPolicy == null || retentionPolicy.isBlank())
                    ? "test-run"
                    : retentionPolicy;
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    public record RuntimeValidationRequest(
            ScenarioDsl.ScenarioDefinition scenario,
            String generationRunId,
            String scenarioVariantId,
            Path evidenceRoot,
            List<ArtifactCandidate> artifacts,
            List<LayerVerdict> layerVerdicts,
            List<String> observedCoverageTags,
            String gitSha,
            String composeSignature,
            int retryIndex
    ) {
        public RuntimeValidationRequest {
            if (scenario == null) {
                throw new IllegalArgumentException("scenario is required");
            }
            if (evidenceRoot == null) {
                throw new IllegalArgumentException("evidenceRoot is required");
            }
            scenarioVariantId = (scenarioVariantId == null || scenarioVariantId.isBlank())
                    ? scenario.scenarioId()
                    : scenarioVariantId;
            artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
            layerVerdicts = layerVerdicts == null ? List.of() : List.copyOf(layerVerdicts);
            observedCoverageTags = observedCoverageTags == null ? List.of() : List.copyOf(observedCoverageTags);
            gitSha = gitSha == null ? "unknown" : gitSha;
            composeSignature = composeSignature == null ? "unknown" : composeSignature;
        }
    }

    public record ValidationResult(
            Verdict verdict,
            List<String> failureCodes,
            EvidenceBundle evidenceBundle,
            Path verdictPath,
            Path coveragePath
    ) {
    }
}
