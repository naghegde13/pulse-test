package com.pulse.e2e.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.e2e.scenarios.LoanMasterRuntimeProofLedger;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public class SemanticProofCatalog {

    private static final String ACTIVE_CATALOG_RESOURCE = "e2e/coverage/active-blueprint-catalog.json";
    private static final String SCENARIO_CATALOG_RESOURCE = "e2e/scenarios/loan-master-scenario-families.json";
    private static final String DATABASE_WRITER_DESTINATION_RESOURCE =
            "e2e/semantic/database-writer-gcp-destination-decision.json";
    private static final Path DEFAULT_SEMANTIC_HARDENING_ROOT = Path.of("backend/build/e2e-semantic-hardening");
    private static final Set<String> NEGATIVE_SCOPE_BLUEPRINTS = Set.of(
            "FileIngestion",
            "BulkBackfill",
            "GenericJoin",
            "GenericRouter",
            "DedupeAndMerge",
            "SnapshotModel",
            "IncrementalMerge",
            "SCD2Dimension",
            "DQValidator",
            "AnomalyDetection",
            "FreshnessChecks",
            "SchemaDriftDetection",
            "FileArrivalSensor",
            "AdvanceTimeDimension",
            "LakeWriter",
            "DatabaseWriter"
    );
    private static final Set<String> STATEFUL_BLUEPRINTS = Set.of(
            "BulkBackfill",
            "SnapshotIngestion",
            "DedupeAndMerge",
            "SnapshotModel",
            "IncrementalMerge",
            "SCD2Dimension",
            "FileArrivalSensor",
            "AdvanceTimeDimension",
            "LakeWriter",
            "DatabaseWriter",
            "DQValidator",
            "SchemaDriftDetection",
            "AnomalyDetection",
            "FreshnessChecks"
    );
    private static final Set<String> COMPARATOR_APPROVAL_REQUIRED_BLUEPRINTS = Set.of(
            "SnapshotModel",
            "AdvanceTimeDimension"
    );

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final Path semanticHardeningRoot;

    public SemanticProofCatalog() {
        this(DEFAULT_SEMANTIC_HARDENING_ROOT);
    }

    SemanticProofCatalog(Path semanticHardeningRoot) {
        this.semanticHardeningRoot = semanticHardeningRoot;
    }

    public List<SemanticProofTarget> buildRepresentativeTargets(LoanMasterRuntimeProofLedger.ProofLedger proofLedger) {
        Set<String> activeBlueprintKeys = loadActiveBlueprintKeys();
        Map<String, ScenarioBinding> scenarioBindings = loadScenarioBindings();
        return proofLedger.entries().stream()
                .map(entry -> toSemanticProofTarget(entry, activeBlueprintKeys, scenarioBindings))
                .toList();
    }

    public Path writeRepresentativeTargets(ObjectMapper objectMapper,
                                           LoanMasterRuntimeProofLedger.ProofLedger proofLedger,
                                           Path output) throws IOException {
        Files.createDirectories(output.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), buildRepresentativeTargets(proofLedger));
        return output;
    }

    private Set<String> loadActiveBlueprintKeys() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(ACTIVE_CATALOG_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource: " + ACTIVE_CATALOG_RESOURCE);
            }
            return new LinkedHashSet<>(new ObjectMapper().readTree(in).path("blueprints").findValuesAsText("blueprintKey"));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load active blueprint catalog", e);
        }
    }

    private Map<String, ScenarioBinding> loadScenarioBindings() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(SCENARIO_CATALOG_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource: " + SCENARIO_CATALOG_RESOURCE);
            }
            ObjectMapper mapper = new ObjectMapper();
            JsonNode scenarios = mapper.readTree(in).path("scenarios");
            Map<String, ScenarioBinding> bindings = new LinkedHashMap<>();
            scenarios.forEach(scenario -> {
                String blueprintKey = scenario.path("representativeBlueprintKey").asText(null);
                String fixtureDerivativeId = scenario.path("fixtureDerivativeId").asText(null);
                String fixtureSha256 = scenario.path("scenario")
                        .path("fixtureRefs")
                        .path("data_oracle_overrides")
                        .path("canonical_csv_sha256")
                        .asText(null);
                if (blueprintKey != null) {
                    bindings.put(blueprintKey, new ScenarioBinding(
                            "semantic-" + blueprintKey,
                            fixtureDerivativeId,
                            fixtureSha256
                    ));
                }
            });
            return bindings;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load scenario bindings", e);
        }
    }

    private SemanticProofTarget toSemanticProofTarget(LoanMasterRuntimeProofLedger.ProofLedgerEntry entry,
                                                      Set<String> activeBlueprintKeys,
                                                      Map<String, ScenarioBinding> scenarioBindings) {
        ScenarioBinding binding = scenarioBindings.get(entry.representativeBlueprintKey());
        if (binding == null) {
            throw new IllegalStateException("Missing scenario binding for " + entry.representativeBlueprintKey());
        }
        PromotionArtifacts artifacts = loadPromotionArtifacts(entry.representativeBlueprintKey());
        DestinationDecision destinationDecision = loadDestinationDecision(entry.representativeBlueprintKey());
        String promotionStatus = promotionStatus(entry, artifacts, destinationDecision);
        return new SemanticProofTarget(
                entry.representativeBlueprintKey(),
                entry.scenarioId(),
                binding.scenarioGroupId(),
                binding.fixtureDerivativeId(),
                binding.fixtureSha256(),
                "representative-ledger",
                semanticHardeningStatus(entry, promotionStatus, destinationDecision),
                entry.hardProofStatus(),
                "local",
                "LOCAL_AIRFLOW_BRIDGE",
                "blocked-semantic-dev",
                true,
                activeBlueprintKeys.contains(entry.representativeBlueprintKey()),
                false,
                false,
                STATEFUL_BLUEPRINTS.contains(entry.representativeBlueprintKey()),
                NEGATIVE_SCOPE_BLUEPRINTS.contains(entry.representativeBlueprintKey()),
                true,
                NEGATIVE_SCOPE_BLUEPRINTS.contains(entry.representativeBlueprintKey()),
                comparatorApprovalStatus(entry.representativeBlueprintKey(), artifacts),
                artifacts.evidenceIndexPath(),
                artifacts.promotionReceiptPath(),
                artifacts.promotionReceiptSha256(),
                promotionStatus
        );
    }

    private String comparatorApprovalStatus(String blueprintKey, PromotionArtifacts artifacts) {
        if (artifacts.comparatorApproved()) {
            return "approved";
        }
        return COMPARATOR_APPROVAL_REQUIRED_BLUEPRINTS.contains(blueprintKey) ? "missing" : "not_required";
    }

    private String promotionStatus(LoanMasterRuntimeProofLedger.ProofLedgerEntry entry,
                                   PromotionArtifacts artifacts,
                                   DestinationDecision destinationDecision) {
        if ("DatabaseWriter".equals(entry.representativeBlueprintKey()) && !destinationDecision.selected()) {
            return "blocked";
        }
        if (artifacts.promotionApproved()) {
            return "approved";
        }
        if (artifacts.hasCandidateArtifacts()) {
            return "candidate";
        }
        if (COMPARATOR_APPROVAL_REQUIRED_BLUEPRINTS.contains(entry.representativeBlueprintKey())) {
            return "blocked";
        }
        return "draft";
    }

    private String semanticHardeningStatus(LoanMasterRuntimeProofLedger.ProofLedgerEntry entry,
                                           String promotionStatus,
                                           DestinationDecision destinationDecision) {
        if ("DatabaseWriter".equals(entry.representativeBlueprintKey()) && !destinationDecision.selected()) {
            return "BLOCKED_DESTINATION_SELECTION";
        }
        return switch (promotionStatus) {
            case "approved" -> "PROMOTED_SEMANTIC_HARDENING";
            case "candidate" -> "CANDIDATE_SEMANTIC_HARDENING";
            default -> "PENDING_SEMANTIC_HARDENING";
        };
    }

    private PromotionArtifacts loadPromotionArtifacts(String blueprintKey) {
        try {
            Path evidenceIndex = findBlueprintArtifact(blueprintKey, "evidence-index.json");
            Path critiqueVerdict = findBlueprintArtifact(blueprintKey, "critique-verdict.json");
            Path promotionReceipt = findBlueprintArtifact(blueprintKey, "promotion-receipt.json");

            JsonNode critiqueVerdictJson = readJsonIfExists(critiqueVerdict);
            JsonNode promotionReceiptJson = readJsonIfExists(promotionReceipt);

            boolean critiqueApproved = critiqueVerdictJson != null
                    && "APPROVE".equalsIgnoreCase(critiqueVerdictJson.path("verdict").asText())
                    && critiqueVerdictJson.path("promotionAllowed").asBoolean(false);
            boolean comparatorApproved = critiqueApproved
                    && critiqueVerdictJson.path("confirmedComparatorFitness").asBoolean(false);
            boolean promotionApproved = critiqueApproved
                    && promotionReceiptJson != null
                    && blueprintKey.equals(promotionReceiptJson.path("blueprintKey").asText())
                    && "PROMOTED".equalsIgnoreCase(promotionReceiptJson.path("verdict").asText());

            return new PromotionArtifacts(
                    relativizeToRepo(evidenceIndex),
                    relativizeToRepo(promotionReceipt),
                    sha256(promotionReceipt),
                    comparatorApproved,
                    promotionApproved,
                    evidenceIndex != null || critiqueVerdict != null || promotionReceipt != null
            );
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read semantic proof promotion artifacts for " + blueprintKey, e);
        }
    }

    private DestinationDecision loadDestinationDecision(String blueprintKey) {
        if (!"DatabaseWriter".equals(blueprintKey)) {
            return DestinationDecision.notRequired();
        }
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(DATABASE_WRITER_DESTINATION_RESOURCE)) {
            if (in == null) {
                return DestinationDecision.missing();
            }
            JsonNode decision = objectMapper.readTree(in);
            boolean selected = "DatabaseWriter".equals(decision.path("blueprintKey").asText())
                    && "selected".equalsIgnoreCase(decision.path("decisionStatus").asText())
                    && "bigquery".equalsIgnoreCase(decision.path("canonicalGcpDestination").asText());
            return new DestinationDecision(selected);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read DatabaseWriter destination decision", e);
        }
    }

    private Path findBlueprintArtifact(String blueprintKey, String fileName) throws IOException {
        Path directPath = semanticHardeningRoot.resolve(blueprintKey).resolve("verdict").resolve(fileName);
        if (Files.exists(directPath)) {
            return directPath;
        }
        if (!Files.isDirectory(semanticHardeningRoot)) {
            return null;
        }
        try (Stream<Path> paths = Files.find(
                semanticHardeningRoot,
                8,
                (path, attrs) -> attrs.isRegularFile()
                        && fileName.equals(path.getFileName().toString())
                        && containsPathSegment(path, blueprintKey)
        )) {
            return paths.sorted().findFirst().orElse(null);
        }
    }

    private boolean containsPathSegment(Path path, String segment) {
        String normalizedSegment = normalizePathSegment(segment);
        for (Path part : path) {
            String pathSegment = part.toString();
            if (segment.equals(pathSegment) || normalizedSegment.equals(normalizePathSegment(pathSegment))) {
                return true;
            }
        }
        return false;
    }

    private String normalizePathSegment(String segment) {
        return segment.replaceAll("[^A-Za-z0-9]", "").toLowerCase(java.util.Locale.ROOT);
    }

    private JsonNode readJsonIfExists(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return null;
        }
        return objectMapper.readTree(path.toFile());
    }

    private String relativizeToRepo(Path path) {
        if (path == null) {
            return null;
        }
        return path.normalize().toString().replace('\\', '/');
    }

    private String sha256(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(path)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest unavailable", e);
        }
    }

    public record SemanticProofTarget(
            String blueprintKey,
            String scenarioId,
            String scenarioGroupId,
            String fixtureDerivativeId,
            String fixtureSha256,
            String denominator,
            String semanticHardeningStatus,
            String legacyHardProofStatus,
            String runtimeNamespace,
            String runtimeAdapter,
            String proofShape,
            boolean representativeLedgerMember,
            boolean activeCatalogMember,
            boolean requiresGcp,
            boolean requiresDocker,
            boolean stateful,
            boolean negativeScope,
            boolean edgeScope,
            boolean failureScope,
            String comparatorApprovalStatus,
            String evidenceIndexPath,
            String promotionReceiptPath,
            String promotionReceiptSha256,
            String promotionStatus
    ) {
    }

    private record ScenarioBinding(
            String scenarioGroupId,
            String fixtureDerivativeId,
            String fixtureSha256
    ) {
    }

    private record PromotionArtifacts(
            String evidenceIndexPath,
            String promotionReceiptPath,
            String promotionReceiptSha256,
            boolean comparatorApproved,
            boolean promotionApproved,
            boolean hasCandidateArtifacts
    ) {
    }

    private record DestinationDecision(boolean selected) {
        static DestinationDecision notRequired() {
            return new DestinationDecision(true);
        }

        static DestinationDecision missing() {
            return new DestinationDecision(false);
        }
    }
}
