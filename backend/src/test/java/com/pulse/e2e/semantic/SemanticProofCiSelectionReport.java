package com.pulse.e2e.semantic;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

public class SemanticProofCiSelectionReport {

    private static final String RERUN_COVERAGE_MISSING = "FAIL_MISSING_RERUN_COVERAGE";
    private static final Set<String> OUTPUT_PRODUCING_REPRESENTATIVE_BLUEPRINTS = Set.of(
            "FileIngestion",
            "BulkBackfill",
            "SnapshotIngestion",
            "GenericFilter",
            "SchemaNormalization",
            "BronzeToSilverCleaning",
            "GenericAggregate",
            "GenericJoin",
            "GenericRouter",
            "JsonFlatten",
            "JsonStruct",
            "PIIMasking",
            "DedupeAndMerge",
            "SnapshotModel",
            "AggregateMaterialization",
            "FeatureTablePublish",
            "IncrementalMerge",
            "ReferenceDataPublish",
            "FactBuild",
            "SCD2Dimension",
            "WideDenormalizedMart",
            "DQValidator",
            "AnomalyDetection",
            "FreshnessChecks",
            "SchemaDriftDetection",
            "LakeWriter",
            "DatabaseWriter"
    );
    private static final Set<String> FAILURE_RETRY_BLUEPRINTS = Set.of(
            "FileIngestion",
            "BulkBackfill",
            "LakeWriter",
            "DatabaseWriter",
            "DQValidator",
            "IncrementalMerge",
            "DedupeAndMerge"
    );
    private static final Set<String> BATCH_ORDER_BLUEPRINTS = Set.of(
            "IncrementalMerge",
            "SCD2Dimension",
            "SnapshotModel"
    );
    private static final Set<String> LATE_CORRECTION_BLUEPRINTS = Set.of(
            "SCD2Dimension",
            "SnapshotModel",
            "IncrementalMerge"
    );
    private static final Set<String> QUARANTINE_RERUN_BLUEPRINTS = Set.of(
            "DQValidator",
            "SchemaDriftDetection",
            "AnomalyDetection",
            "FreshnessChecks"
    );
    private static final Set<String> COMPOSER_RERUN_BLUEPRINTS = Set.of(
            "FileArrivalSensor",
            "AdvanceTimeDimension",
            "LakeWriter",
            "DatabaseWriter"
    );
    private static final Set<String> DATAPROC_FAILURE_GUARD_BLUEPRINTS = Set.of(
            "GenericFilter",
            "SchemaNormalization",
            "BronzeToSilverCleaning",
            "GenericAggregate",
            "GenericJoin",
            "GenericRouter",
            "JsonFlatten",
            "JsonStruct",
            "PIIMasking",
            "DedupeAndMerge",
            "SnapshotModel",
            "AggregateMaterialization",
            "FeatureTablePublish",
            "IncrementalMerge",
            "ReferenceDataPublish",
            "FactBuild",
            "SCD2Dimension",
            "WideDenormalizedMart",
            "DQValidator",
            "AnomalyDetection",
            "FreshnessChecks",
            "SchemaDriftDetection",
            "LakeWriter"
    );
    private static final Set<String> PROOF_SHAPES_REQUIRING_FAILURE_RETRY = Set.of("gcp-golden", "gcp-full");
    private static final Set<String> PROOF_SHAPES_REQUIRING_COMPOSER_RERUN = Set.of("gcp-golden", "gcp-full");
    private static final Set<String> PROOF_SHAPES_REQUIRING_DATAPROC_FAILURE_GUARD = Set.of("gcp-golden", "gcp-full");
    private static final String GCP_MULTI_FIXTURE_SCOPE = "MULTI_FIXTURE_SCOPE";

    public SelectionReport build(List<SemanticProofCatalog.SemanticProofTarget> targets) {
        boolean blockedSemanticDevUnlocked = blockedSemanticDevExitPredicatePassed(targets);
        List<RerunSequenceRow> rerunSequences = buildRerunSequences(targets);
        List<TargetSelectionRow> rows = new ArrayList<>();
        for (String proofShape : SemanticHardeningEvidenceContracts.PROOF_SHAPES.stream().sorted().toList()) {
            rows.add(proofShapeRow(proofShape, blockedSemanticDevUnlocked));
        }
        for (SemanticProofCatalog.SemanticProofTarget target : targets) {
            rows.add(blueprintRow(target, blockedSemanticDevUnlocked));
        }
        Map<String, Long> rerunSequenceKindCounts = rerunSequences.stream()
                .collect(Collectors.groupingBy(
                        RerunSequenceRow::sequenceKind,
                        TreeMap::new,
                        Collectors.counting()
                ));

        return new SelectionReport(
                "1",
                Instant.now().toString(),
                "docs/verification/blueprint-semantic-hardening-gcp-runtime-proof-plan.md",
                rows,
                rerunSequences,
                summary(rows, rerunSequences, targets, rerunSequenceKindCounts)
        );
    }

    public Path write(ObjectMapper objectMapper,
                      List<SemanticProofCatalog.SemanticProofTarget> targets,
                      Path output) throws IOException {
        Files.createDirectories(output.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), build(targets));
        return output;
    }

    private TargetSelectionRow proofShapeRow(String proofShape, boolean blockedSemanticDevUnlocked) {
        return switch (proofShape) {
            case "generator", "adapter", "evidence-contract", "semantic-oracle", "ledger-checks" ->
                    new TargetSelectionRow("proofShape", proofShape, null, proofShape, true, true, false, null, null, "PASS");
            case "blocked-semantic-dev" ->
                    new TargetSelectionRow(
                            "proofShape",
                            proofShape,
                            null,
                            proofShape,
                            true,
                            blockedSemanticDevUnlocked,
                            !blockedSemanticDevUnlocked,
                            blockedSemanticDevUnlocked ? null : "BLOCKED_SEMANTIC_DEV_DEFAULT_EXCLUDED",
                            null,
                            blockedSemanticDevUnlocked ? "PASS" : "PENDING"
                    );
            case "docker-runtime" ->
                    new TargetSelectionRow("proofShape", proofShape, null, proofShape, true, false, true,
                            "DOCKER_RUNTIME_DISABLED", null, "PASS");
            case "gcp-environment-smoke", "gcp-golden", "gcp-full" ->
                    new TargetSelectionRow("proofShape", proofShape, null, proofShape, true, false, true,
                            "GCP_PROOF_DISABLED", null, "PASS");
            default -> throw new IllegalArgumentException("Unexpected proof shape: " + proofShape);
        };
    }

    private TargetSelectionRow blueprintRow(SemanticProofCatalog.SemanticProofTarget target, boolean blockedSemanticDevUnlocked) {
        boolean selected = !"blocked-semantic-dev".equals(target.proofShape()) || blockedSemanticDevUnlocked;
        boolean skipped = !selected;
        String skipReason = skipped ? "BLOCKED_SEMANTIC_DEV_DEFAULT_EXCLUDED" : null;
        String status = requiresRerunCoverage(target)
                ? RERUN_COVERAGE_MISSING
                : skipped ? "PENDING" : "PASS";
        return new TargetSelectionRow(
                "blueprint",
                target.blueprintKey() + ":" + target.proofShape(),
                target.blueprintKey(),
                target.proofShape(),
                true,
                selected,
                skipped,
                skipReason,
                target.evidenceIndexPath(),
                status
        );
    }

    private boolean blockedSemanticDevExitPredicatePassed(List<SemanticProofCatalog.SemanticProofTarget> targets) {
        return hasApprovedBlockedSemanticDevTarget(targets, "SnapshotModel")
                && hasApprovedBlockedSemanticDevTarget(targets, "AdvanceTimeDimension");
    }

    private boolean hasApprovedBlockedSemanticDevTarget(List<SemanticProofCatalog.SemanticProofTarget> targets, String blueprintKey) {
        return targets.stream().anyMatch(target ->
                blueprintKey.equals(target.blueprintKey())
                        && "blocked-semantic-dev".equals(target.proofShape())
                        && "approved".equals(target.comparatorApprovalStatus())
                        && target.promotionReceiptPath() != null
                        && target.promotionReceiptSha256() != null
                        && "approved".equals(target.promotionStatus()));
    }

    private boolean requiresRerunCoverage(SemanticProofCatalog.SemanticProofTarget target) {
        String blueprintKey = target.blueprintKey();
        return OUTPUT_PRODUCING_REPRESENTATIVE_BLUEPRINTS.contains(blueprintKey)
                || FAILURE_RETRY_BLUEPRINTS.contains(blueprintKey)
                || BATCH_ORDER_BLUEPRINTS.contains(blueprintKey)
                || LATE_CORRECTION_BLUEPRINTS.contains(blueprintKey)
                || QUARANTINE_RERUN_BLUEPRINTS.contains(blueprintKey)
                || COMPOSER_RERUN_BLUEPRINTS.contains(blueprintKey)
                || DATAPROC_FAILURE_GUARD_BLUEPRINTS.contains(blueprintKey);
    }

    private List<RerunSequenceRow> buildRerunSequences(List<SemanticProofCatalog.SemanticProofTarget> targets) {
        List<RerunSequenceRow> rows = new ArrayList<>();

        for (SemanticProofCatalog.SemanticProofTarget target : targets) {
            String blueprintKey = target.blueprintKey();
            if (OUTPUT_PRODUCING_REPRESENTATIVE_BLUEPRINTS.contains(blueprintKey)) {
                rows.add(sequenceRow(target, "same_input_rerun_after_pass"));
            }
            if (FAILURE_RETRY_BLUEPRINTS.contains(blueprintKey)) {
                rows.add(sequenceRow(target, "failure_retry_after_partial_failure"));
            }
            if (BATCH_ORDER_BLUEPRINTS.contains(blueprintKey)) {
                rows.add(sequenceRow(target, "batch_order_replay"));
            }
            if (LATE_CORRECTION_BLUEPRINTS.contains(blueprintKey)) {
                rows.add(sequenceRow(target, "late_arriving_correction"));
            }
            if (QUARANTINE_RERUN_BLUEPRINTS.contains(blueprintKey)) {
                rows.add(sequenceRow(target, "quarantine_rerun_determinism"));
            }
            if (COMPOSER_RERUN_BLUEPRINTS.contains(blueprintKey)) {
                rows.add(sequenceRow(target, "composer_rerun_same_logical_date"));
            }
            if (DATAPROC_FAILURE_GUARD_BLUEPRINTS.contains(blueprintKey)) {
                rows.add(sequenceRow(target, "dataproc_failed_job_guard"));
            }
        }

        for (String proofShape : PROOF_SHAPES_REQUIRING_FAILURE_RETRY) {
            rows.add(proofShapeSequenceRow(proofShape, "failure_retry_after_partial_failure"));
        }
        for (String proofShape : PROOF_SHAPES_REQUIRING_COMPOSER_RERUN) {
            rows.add(proofShapeSequenceRow(proofShape, "composer_rerun_same_logical_date"));
        }
        for (String proofShape : PROOF_SHAPES_REQUIRING_DATAPROC_FAILURE_GUARD) {
            rows.add(proofShapeSequenceRow(proofShape, "dataproc_failed_job_guard"));
        }

        return dedupe(rows);
    }

    private RerunSequenceRow sequenceRow(SemanticProofCatalog.SemanticProofTarget target, String sequenceKind) {
        return new RerunSequenceRow(
                target.blueprintKey() + ":" + sequenceKind + ":" + target.fixtureSha256(),
                sequenceKind,
                target.blueprintKey() + ":" + target.proofShape(),
                target.scenarioGroupId(),
                target.fixtureSha256(),
                List.of(),
                RERUN_COVERAGE_MISSING
        );
    }

    private RerunSequenceRow proofShapeSequenceRow(String targetId, String sequenceKind) {
        return new RerunSequenceRow(
                targetId + ":" + sequenceKind + ":" + GCP_MULTI_FIXTURE_SCOPE,
                sequenceKind,
                targetId,
                "semantic-" + targetId,
                GCP_MULTI_FIXTURE_SCOPE,
                List.of(),
                RERUN_COVERAGE_MISSING
        );
    }

    private List<RerunSequenceRow> dedupe(List<RerunSequenceRow> rows) {
        Map<String, RerunSequenceRow> deduped = new LinkedHashMap<>();
        for (RerunSequenceRow row : rows) {
            deduped.putIfAbsent(row.sequenceId(), row);
        }
        return List.copyOf(deduped.values());
    }

    private Map<String, Object> summary(List<TargetSelectionRow> rows,
                                        List<RerunSequenceRow> rerunSequences,
                                        List<SemanticProofCatalog.SemanticProofTarget> targets,
                                        Map<String, Long> rerunSequenceKindCounts) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("proofShapeCount", SemanticHardeningEvidenceContracts.PROOF_SHAPES.size());
        summary.put("representativeTargetCount", targets.size());
        summary.put("targetRowCount", rows.size());
        summary.put("rerunSequenceCount", rerunSequences.size());
        summary.put("rerunSequenceKindCounts", rerunSequenceKindCounts);
        return summary;
    }

    public record SelectionReport(
            String schemaVersion,
            String generatedAt,
            String sourcePlan,
            List<TargetSelectionRow> targets,
            List<RerunSequenceRow> rerunSequences,
            Map<String, Object> summary
    ) {
    }

    public record TargetSelectionRow(
            String targetType,
            String targetId,
            String blueprintKey,
            String proofShape,
            boolean expected,
            boolean selected,
            boolean skipped,
            String skipReason,
            String evidenceIndexPath,
            String status
    ) {
    }

    public record RerunSequenceRow(
            String sequenceId,
            String sequenceKind,
            String targetId,
            String scenarioGroupId,
            String fixtureSha256,
            List<String> runIds,
            String status
    ) {
    }
}
