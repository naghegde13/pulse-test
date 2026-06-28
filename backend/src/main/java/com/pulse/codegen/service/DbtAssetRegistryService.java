package com.pulse.codegen.service;

import com.pulse.codegen.model.DbtAsset;
import com.pulse.codegen.repository.DbtAssetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class DbtAssetRegistryService {

    private static final Set<String> REUSE_METADATA_KEYS = Set.of(
            "semantic_terms",
            "semantic_keys",
            "contract_keys",
            "contract_columns",
            "source_models",
            "source_datasets",
            "lineage_inputs",
            "lineage_outputs",
            "owners",
            "dimensions",
            "measures"
    );

    private static final Set<String> REUSE_CONTEXT_KEYS = Set.of(
            "semantic_terms",
            "semantic_keys",
            "contract_keys",
            "contract_columns",
            "source_models",
            "source_datasets",
            "lineage_inputs",
            "lineage_outputs",
            "group_by_columns",
            "dimensions",
            "measures",
            "join_keys",
            "select_columns",
            "output_columns"
    );

    private final DbtAssetRepository dbtAssetRepository;

    public DbtAssetRegistryService(DbtAssetRepository dbtAssetRepository) {
        this.dbtAssetRepository = dbtAssetRepository;
    }

    public List<DbtAsset> listDomainAssets(String domainId) {
        return dbtAssetRepository.findByDomainIdOrderByAssetTypeAscAssetNameAsc(domainId);
    }

    @Transactional
    public List<DbtAsset> refreshFromManifest(String domainId,
                                             String projectName,
                                             List<ManifestAssetInput> assets) {
        return refreshFromManifest(domainId, projectName, assets, "main", null);
    }

    @Transactional
    public List<DbtAsset> refreshFromManifest(String domainId,
                                             String projectName,
                                             List<ManifestAssetInput> assets,
                                             String branch,
                                             String gitSha) {
        String resolvedBranch = (branch == null || branch.isBlank()) ? "main" : branch;
        List<DbtAsset> saved = new ArrayList<>();
        for (ManifestAssetInput asset : assets) {
            Optional<DbtAsset> existing = dbtAssetRepository.findByDomainIdAndBranchAndAssetNameAndAssetType(
                    domainId, resolvedBranch, asset.assetName(), asset.assetType());
            DbtAsset dbtAsset = existing.orElseGet(DbtAsset::new);
            dbtAsset.setDomainId(domainId);
            dbtAsset.setBranch(resolvedBranch);
            dbtAsset.setGitSha(gitSha);
            dbtAsset.setProjectName(projectName);
            dbtAsset.setAssetName(asset.assetName());
            dbtAsset.setAssetType(asset.assetType());
            dbtAsset.setPath(asset.path());
            dbtAsset.setTags(asset.tags() != null ? asset.tags() : List.of());
            dbtAsset.setGroupName(asset.groupName());
            dbtAsset.setAccessLevel(asset.accessLevel());
            dbtAsset.setGrain(asset.grain());
            dbtAsset.setBusinessConcept(asset.businessConcept());
            dbtAsset.setSchemaSignature(asset.schemaSignature());
            dbtAsset.setDescription(asset.description());
            dbtAsset.setMetadata(asset.metadata() != null ? asset.metadata() : Map.of());
            saved.add(dbtAssetRepository.save(dbtAsset));
        }
        return saved;
    }

    public Optional<ReuseMatch> findReuseCandidate(String domainId,
                                                   String businessConcept,
                                                   String assetType,
                                                   String grain,
                                                   String accessLevel,
                                                   String schemaSignature,
                                                   String requestedEmitStrategy) {
        return findReuseCandidate(
                domainId,
                businessConcept,
                assetType,
                grain,
                accessLevel,
                schemaSignature,
                requestedEmitStrategy,
                Map.of(),
                "main");
    }

    public Optional<ReuseMatch> findReuseCandidate(String domainId,
                                                   String businessConcept,
                                                   String assetType,
                                                   String grain,
                                                   String accessLevel,
                                                   String schemaSignature,
                                                   String requestedEmitStrategy,
                                                   Map<String, Object> planningContext) {
        return findReuseCandidate(
                domainId,
                businessConcept,
                assetType,
                grain,
                accessLevel,
                schemaSignature,
                requestedEmitStrategy,
                planningContext,
                "main");
    }

    public Optional<ReuseMatch> findReuseCandidate(String domainId,
                                                   String businessConcept,
                                                   String assetType,
                                                   String grain,
                                                   String accessLevel,
                                                   String schemaSignature,
                                                   String requestedEmitStrategy,
                                                   Map<String, Object> planningContext,
                                                   String branch) {
        if (domainId == null || domainId.isBlank() || businessConcept == null || businessConcept.isBlank()
                || assetType == null || assetType.isBlank()) {
            return Optional.empty();
        }

        ReuseRequest request = new ReuseRequest(
                normalize(businessConcept),
                assetType,
                normalize(grain),
                normalize(accessLevel),
                normalize(schemaSignature),
                normalizeReuseStrategy(requestedEmitStrategy),
                planningContext != null ? planningContext : Map.of());

        String resolvedBranch = (branch == null || branch.isBlank()) ? "main" : branch;
        List<DbtAsset> candidates = dbtAssetRepository
                .findByDomainIdAndBranchOrderByAssetTypeAscAssetNameAsc(domainId, resolvedBranch);

        return candidates.stream()
                .filter(candidate -> assetType.equalsIgnoreCase(candidate.getAssetType()))
                .map(candidate -> scoreCandidate(candidate, request))
                .filter(ReuseCandidateScore::viable)
                .sorted((left, right) -> {
                    int byScore = Integer.compare(right.score(), left.score());
                    if (byScore != 0) {
                        return byScore;
                    }
                    return left.asset().getAssetName().compareToIgnoreCase(right.asset().getAssetName());
                })
                .findFirst()
                .map(ReuseCandidateScore::toMatch);
    }

    public Map<String, Object> toApiPayload(DbtAsset asset) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", asset.getId());
        payload.put("domainId", asset.getDomainId());
        payload.put("projectName", asset.getProjectName());
        payload.put("assetName", asset.getAssetName());
        payload.put("assetType", asset.getAssetType());
        payload.put("path", asset.getPath());
        payload.put("tags", asset.getTags());
        payload.put("groupName", asset.getGroupName());
        payload.put("accessLevel", asset.getAccessLevel());
        payload.put("grain", asset.getGrain());
        payload.put("businessConcept", asset.getBusinessConcept());
        payload.put("schemaSignature", asset.getSchemaSignature());
        payload.put("description", asset.getDescription());
        payload.put("metadata", asset.getMetadata());
        payload.put("branch", asset.getBranch());
        payload.put("gitSha", asset.getGitSha());
        return payload;
    }

    public Map<String, Object> toDecisionPayload(ReuseMatch match) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("emitStrategy", match.emitStrategy());
        payload.put("score", match.score());
        payload.put("reasons", match.reasons());
        payload.put("warnings", match.warnings());
        payload.put("compatibility", match.compatibility());
        return payload;
    }

    private String normalizeReuseStrategy(String requestedEmitStrategy) {
        if (requestedEmitStrategy == null || requestedEmitStrategy.isBlank()) {
            return "reuse_wrapper";
        }
        return switch (requestedEmitStrategy.trim()) {
            case "reference_only", "reuse_wrapper" -> requestedEmitStrategy.trim();
            default -> "reuse_wrapper";
        };
    }

    private ReuseCandidateScore scoreCandidate(DbtAsset candidate, ReuseRequest request) {
        List<String> reasons = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, Object> compatibility = new LinkedHashMap<>();
        int score = 0;

        Similarity businessConcept = compareSimilarity(primaryBusinessConcept(candidate), request.businessConcept());
        compatibility.put("businessConcept", labelFor(businessConcept));
        if (businessConcept.exact()) {
            score += 8;
            reasons.add("Exact business concept match.");
        } else if (businessConcept.hasOverlap()) {
            score += businessConcept.sharedCount() >= 2 ? 6 : 4;
            reasons.add("Business concept overlaps on " + joinTokens(businessConcept.sharedTokens()) + ".");
        } else {
            warnings.add("Business concept alignment is weak.");
        }

        AccessCompatibility accessCompatibility = evaluateAccessCompatibility(candidate.getAccessLevel(), request.accessLevel());
        compatibility.put("accessLevel", accessCompatibility.label());
        if (!accessCompatibility.compatible()) {
            warnings.add("Requested access level is incompatible with the candidate asset.");
            compatibility.put("referenceSafe", false);
            return new ReuseCandidateScore(candidate, 0, reasons, warnings, compatibility, "generate", false);
        }
        score += accessCompatibility.scoreBoost();
        if (accessCompatibility.reason() != null) {
            reasons.add(accessCompatibility.reason());
        }

        Similarity grain = compareSimilarity(candidate.getGrain(), request.grain());
        compatibility.put("grain", labelFor(grain));
        if (!request.grain().isBlank()) {
            if (grain.exact()) {
                score += 4;
                reasons.add("Exact analytical grain match.");
            } else if (grain.hasOverlap()) {
                score += 2;
                warnings.add("Analytical grain is close but not identical.");
            } else {
                warnings.add("Analytical grain is not aligned.");
            }
        }

        Similarity schema = compareSimilarity(candidate.getSchemaSignature(), request.schemaSignature());
        compatibility.put("schemaSignature", labelFor(schema));
        if (!request.schemaSignature().isBlank()) {
            if (schema.exact()) {
                score += 6;
                reasons.add("Exact schema signature match.");
            } else if (schema.hasStrongOverlap()) {
                score += 4;
                warnings.add("Schema signature is highly compatible but not identical.");
            } else if (schema.hasOverlap()) {
                score += 2;
                warnings.add("Schema signature only partially overlaps.");
            } else {
                warnings.add("Schema signature does not overlap.");
            }
        }

        double semanticOverlap = semanticOverlap(candidate, request);
        compatibility.put("semanticOverlap", semanticOverlap);
        if (semanticOverlap >= 0.60d) {
            score += 3;
            reasons.add("Semantic metadata strongly overlaps with the requested plan context.");
        } else if (semanticOverlap >= 0.30d) {
            score += 1;
            reasons.add("Semantic metadata partially overlaps with the requested plan context.");
        }

        boolean hardMismatch = (!request.grain().isBlank() && !grain.exact() && !grain.hasOverlap())
                || (!request.schemaSignature().isBlank() && !schema.exact() && !schema.hasOverlap());
        boolean partialMismatch = (!request.grain().isBlank() && !grain.exact() && grain.hasOverlap())
                || (!request.schemaSignature().isBlank() && !schema.exact() && schema.hasOverlap());
        boolean referenceSafe = accessCompatibility.compatible() && !hardMismatch && !partialMismatch;
        compatibility.put("referenceSafe", referenceSafe);

        boolean viable = score >= 7 && (businessConcept.exact() || businessConcept.hasOverlap());
        String emitStrategy = resolveEmitStrategy(request.requestedEmitStrategy(), partialMismatch, hardMismatch, warnings);
        if (hardMismatch && score < 10) {
            viable = false;
        }
        return new ReuseCandidateScore(candidate, score, reasons, warnings, compatibility, emitStrategy, viable);
    }

    private String resolveEmitStrategy(String requestedEmitStrategy,
                                       boolean partialMismatch,
                                       boolean hardMismatch,
                                       List<String> warnings) {
        if (!"reference_only".equals(requestedEmitStrategy)) {
            return requestedEmitStrategy;
        }
        if (hardMismatch || partialMismatch) {
            warnings.add("Requested reference_only was downgraded to reuse_wrapper because compatibility is partial.");
            return "reuse_wrapper";
        }
        return "reference_only";
    }

    private AccessCompatibility evaluateAccessCompatibility(String candidateAccessLevel, String requestedAccessLevel) {
        if (requestedAccessLevel == null || requestedAccessLevel.isBlank()) {
            return new AccessCompatibility(true, "compatible", 1, "No access constraint was requested.");
        }
        if (candidateAccessLevel == null || candidateAccessLevel.isBlank()) {
            return new AccessCompatibility(true, "unknown", 1, "Candidate access level is not declared, so reuse remains provisional.");
        }
        if (requestedAccessLevel.equalsIgnoreCase(candidateAccessLevel)) {
            return new AccessCompatibility(true, "exact", 3, "Access level matches exactly.");
        }
        int requestedRank = accessRank(requestedAccessLevel);
        int candidateRank = accessRank(candidateAccessLevel);
        if (requestedRank > 0 && candidateRank >= requestedRank) {
            return new AccessCompatibility(true, "broader", 2, "Candidate access level is broader than requested but still safe to consume.");
        }
        return new AccessCompatibility(false, "incompatible", 0, null);
    }

    private int accessRank(String accessLevel) {
        if (accessLevel == null) {
            return -1;
        }
        return switch (accessLevel.trim().toLowerCase()) {
            case "private", "restricted" -> 1;
            case "internal", "protected" -> 2;
            case "public", "shared" -> 3;
            default -> -1;
        };
    }

    private double semanticOverlap(DbtAsset candidate, ReuseRequest request) {
        Set<String> candidateTokens = extractAssetSemanticTokens(candidate);
        Set<String> requestTokens = extractRequestSemanticTokens(request);
        if (candidateTokens.isEmpty() || requestTokens.isEmpty()) {
            return 0d;
        }
        Set<String> shared = new LinkedHashSet<>(candidateTokens);
        shared.retainAll(requestTokens);
        if (shared.isEmpty()) {
            return 0d;
        }
        Set<String> union = new LinkedHashSet<>(candidateTokens);
        union.addAll(requestTokens);
        return (double) shared.size() / (double) union.size();
    }

    private Set<String> extractAssetSemanticTokens(DbtAsset asset) {
        Set<String> tokens = new LinkedHashSet<>();
        addTokens(tokens, asset.getAssetName());
        addTokens(tokens, asset.getBusinessConcept());
        addTokens(tokens, asset.getGrain());
        addTokens(tokens, asset.getDescription());
        addTokens(tokens, asset.getPath());
        if (asset.getTags() != null) {
            asset.getTags().forEach(tag -> addTokens(tokens, tag));
        }
        if (asset.getMetadata() != null) {
            asset.getMetadata().forEach((key, value) -> {
                if (isSemanticKey(key)) {
                    addNestedTokens(tokens, value);
                }
            });
        }
        return tokens;
    }

    private Set<String> extractRequestSemanticTokens(ReuseRequest request) {
        Set<String> tokens = new LinkedHashSet<>();
        addTokens(tokens, request.businessConcept());
        addTokens(tokens, request.grain());
        addTokens(tokens, request.schemaSignature());
        request.planningContext().forEach((key, value) -> {
            if (isContextSemanticKey(key)) {
                addNestedTokens(tokens, value);
            }
        });
        return tokens;
    }

    private boolean isSemanticKey(String key) {
        if (key == null) {
            return false;
        }
        String normalizedKey = key.trim().toLowerCase();
        return REUSE_METADATA_KEYS.contains(normalizedKey)
                || normalizedKey.contains("semantic")
                || normalizedKey.contains("contract")
                || normalizedKey.contains("lineage");
    }

    private boolean isContextSemanticKey(String key) {
        if (key == null) {
            return false;
        }
        String normalizedKey = key.trim().toLowerCase();
        return REUSE_CONTEXT_KEYS.contains(normalizedKey)
                || normalizedKey.contains("semantic")
                || normalizedKey.contains("contract")
                || normalizedKey.contains("lineage");
    }

    private void addNestedTokens(Set<String> tokens, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            map.values().forEach(entry -> addNestedTokens(tokens, entry));
            return;
        }
        if (value instanceof Collection<?> collection) {
            collection.forEach(entry -> addNestedTokens(tokens, entry));
            return;
        }
        addTokens(tokens, value.toString());
    }

    private Similarity compareSimilarity(String candidateValue, String requestedValue) {
        String normalizedCandidate = normalize(candidateValue);
        String normalizedRequested = normalize(requestedValue);
        if (normalizedRequested.isBlank() || normalizedCandidate.isBlank()) {
            return Similarity.none();
        }
        if (normalizedRequested.equals(normalizedCandidate)) {
            return Similarity.exact(tokens(normalizedRequested));
        }
        Set<String> candidateTokens = tokens(normalizedCandidate);
        Set<String> requestedTokens = tokens(normalizedRequested);
        Set<String> shared = new LinkedHashSet<>(candidateTokens);
        shared.retainAll(requestedTokens);
        if (shared.isEmpty()) {
            return Similarity.none();
        }
        int maxCardinality = Math.max(candidateTokens.size(), requestedTokens.size());
        double overlapRatio = maxCardinality == 0 ? 0d : (double) shared.size() / (double) maxCardinality;
        return new Similarity(false, overlapRatio, List.copyOf(shared));
    }

    private String labelFor(Similarity similarity) {
        if (similarity.exact()) {
            return "exact";
        }
        if (similarity.hasStrongOverlap()) {
            return "strong_overlap";
        }
        if (similarity.hasOverlap()) {
            return "partial_overlap";
        }
        return "none";
    }

    private String primaryBusinessConcept(DbtAsset asset) {
        if (asset.getBusinessConcept() != null && !asset.getBusinessConcept().isBlank()) {
            return asset.getBusinessConcept();
        }
        return asset.getAssetName();
    }

    private void addTokens(Set<String> tokens, String value) {
        tokens.addAll(tokens(normalize(value)));
    }

    private Set<String> tokens(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return List.of(value.split("\\s+")).stream()
                .map(String::trim)
                .filter(token -> !token.isBlank())
                .filter(token -> token.length() > 1 || Character.isDigit(token.charAt(0)))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private String joinTokens(List<String> tokens) {
        if (tokens.isEmpty()) {
            return "shared semantic terms";
        }
        return String.join(", ", tokens);
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase().replaceAll("[^a-z0-9]+", " ").replaceAll("\\s+", " ").trim();
    }

    public record ManifestAssetInput(
            String assetName,
            String assetType,
            String path,
            List<String> tags,
            String groupName,
            String accessLevel,
            String grain,
            String businessConcept,
            String schemaSignature,
            String description,
            Map<String, Object> metadata
    ) {}

    public record ReuseMatch(
            DbtAsset asset,
            String emitStrategy,
            int score,
            List<String> reasons,
            List<String> warnings,
            Map<String, Object> compatibility
    ) {
        public ReuseMatch {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            compatibility = compatibility == null ? Map.of() : Map.copyOf(compatibility);
        }
    }

    private record ReuseRequest(
            String businessConcept,
            String assetType,
            String grain,
            String accessLevel,
            String schemaSignature,
            String requestedEmitStrategy,
            Map<String, Object> planningContext
    ) {}

    private record ReuseCandidateScore(
            DbtAsset asset,
            int score,
            List<String> reasons,
            List<String> warnings,
            Map<String, Object> compatibility,
            String emitStrategy,
            boolean viable
    ) {
        private ReuseMatch toMatch() {
            return new ReuseMatch(asset, emitStrategy, score, reasons, warnings, compatibility);
        }
    }

    private record Similarity(boolean exact, double overlapRatio, List<String> sharedTokens) {
        private static Similarity exact(Set<String> sharedTokens) {
            return new Similarity(true, 1d, List.copyOf(sharedTokens));
        }

        private static Similarity none() {
            return new Similarity(false, 0d, List.of());
        }

        private boolean hasOverlap() {
            return !sharedTokens.isEmpty() && overlapRatio >= 0.25d;
        }

        private boolean hasStrongOverlap() {
            return !sharedTokens.isEmpty() && overlapRatio >= 0.50d;
        }

        private int sharedCount() {
            return sharedTokens.size();
        }
    }

    private record AccessCompatibility(boolean compatible, String label, int scoreBoost, String reason) {}
}
