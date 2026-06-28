package com.pulse.e2e.semantic;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class SemanticHardeningLaneLedger {

    public enum LaneState {
        PENDING,
        READY_TO_RUN,
        RUNNING,
        FAILED_RETRYABLE,
        FAILED_TERMINAL,
        QUARANTINED,
        PASS
    }

    public enum FailureClass {
        NONE,
        CREDENTIAL,
        IAM,
        COST_CONTROL,
        GCP_RESOURCE_STATE,
        GCS_IO,
        COMPOSER_IMPORT,
        COMPOSER_RUNTIME,
        DATAPROC_SUBMIT,
        DATAPROC_RUNTIME,
        COMPILER,
        COMPARATOR,
        DQ_POLICY,
        SCHEMA_CONTRACT,
        SEMANTIC_AMBIGUITY,
        CLEANUP,
        UNKNOWN
    }

    public InitialLedger initialLedger() {
        return new InitialLedger(
                "1",
                Instant.now().toString(),
                "docs/verification/blueprint-semantic-hardening-gcp-runtime-proof-plan.md",
                List.of(
                        readinessLane(),
                        nonCloudReadyLane("contract-adapter", List.of("adapter-model", "evidence-contract-split")),
                        nonCloudReadyLane("evidence-ledger", List.of("denominator-report", "lane-ledger-schema")),
                        pendingLane("semantic-pack", false, List.of("gcp-readiness-smoke"), List.of("semantic-pack-schema")),
                        pendingLane("comparator", false, List.of("gcp-readiness-smoke"), List.of("row-exact-comparator")),
                        pendingLane("semantic-oracle", false, List.of("gcp-readiness-smoke"), List.of("semantic-oracle-schema")),
                        pendingLane("ledger-checks", false, List.of("gcp-readiness-smoke"), List.of("proof-ledger-checks")),
                        pendingLane("gcp-golden", true, List.of("gcp-readiness-smoke", "contract-adapter"), List.of("gcp-golden-compile", "gcp-golden-composer-run")),
                        databaseDestinationLane()
                )
        );
    }

    public InitialLedger credentialBlockedLedger(String reasonCode, List<String> allowedContinuationLanes) {
        List<Lane> lanes = new ArrayList<>(initialLedger().lanes());
        lanes.set(0, new Lane(
                "gcp-readiness-smoke",
                LaneState.QUARANTINED.name(),
                true,
                FailureClass.CREDENTIAL.name(),
                reasonCode,
                List.of("GCP credential refresh failed in non-interactive execution."),
                List.of(new FailureHistoryEntry(
                        Instant.now().toString(),
                        FailureClass.CREDENTIAL.name(),
                        reasonCode,
                        SemanticHardeningEvidenceContracts.GCP_ENVIRONMENT_ROOT + "/gcp-readiness-report.json",
                        reasonCode
                )),
                List.of("phase0-readiness", "phase0-smoke", "phase0-verdict"),
                List.of(),
                List.of(SemanticHardeningEvidenceContracts.GCP_ENVIRONMENT_ROOT + "/"),
                List.of(SemanticHardeningEvidenceContracts.PROGRESS_LEDGER_PATH),
                allowedContinuationLanes,
                List.of("gcp-golden", "gcp-full"),
                null,
                0,
                1,
                SemanticHardeningEvidenceContracts.GCP_ENVIRONMENT_ROOT,
                "NOT_REQUIRED",
                reasonCode,
                "UNKNOWN"
        ));
        return new InitialLedger(
                "1",
                Instant.now().toString(),
                "docs/verification/blueprint-semantic-hardening-gcp-runtime-proof-plan.md",
                lanes
        );
    }

    public Path writeInitialLedger(ObjectMapper objectMapper, Path output) throws IOException {
        Files.createDirectories(output.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), initialLedger());
        return output;
    }

    public Path writeCredentialBlockedLedger(ObjectMapper objectMapper,
                                             Path output,
                                             String reasonCode,
                                             List<String> allowedContinuationLanes) throws IOException {
        Files.createDirectories(output.getParent());
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(output.toFile(), credentialBlockedLedger(reasonCode, allowedContinuationLanes));
        return output;
    }

    public Path writeComposerMissingLedger(ObjectMapper objectMapper,
                                           Path output,
                                           List<String> allowedContinuationLanes) throws IOException {
        Files.createDirectories(output.getParent());
        List<Lane> lanes = new ArrayList<>(initialLedger().lanes());
        lanes.set(0, new Lane(
                "gcp-readiness-smoke",
                LaneState.QUARANTINED.name(),
                true,
                FailureClass.GCP_RESOURCE_STATE.name(),
                "COMPOSER_ENVIRONMENT_NOT_FOUND",
                List.of("Expected Composer environment pulse-proof-composer was not found in us-central1."),
                List.of(new FailureHistoryEntry(
                        Instant.now().toString(),
                        FailureClass.GCP_RESOURCE_STATE.name(),
                        "COMPOSER_ENVIRONMENT_NOT_FOUND",
                        SemanticHardeningEvidenceContracts.GCP_ENVIRONMENT_ROOT + "/gcp-readiness-report.json",
                        "COMPOSER_ENVIRONMENT_NOT_FOUND"
                )),
                List.of("phase0-readiness", "phase0-smoke", "phase0-verdict"),
                List.of(),
                List.of(SemanticHardeningEvidenceContracts.GCP_ENVIRONMENT_ROOT + "/"),
                List.of(SemanticHardeningEvidenceContracts.PROGRESS_LEDGER_PATH),
                allowedContinuationLanes,
                List.of("gcp-golden", "gcp-full"),
                null,
                0,
                1,
                SemanticHardeningEvidenceContracts.GCP_ENVIRONMENT_ROOT,
                "NOT_REQUIRED",
                "COMPOSER_ENVIRONMENT_NOT_FOUND",
                "UNKNOWN"
        ));
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(output.toFile(), new InitialLedger(
                        "1",
                        Instant.now().toString(),
                        "docs/verification/blueprint-semantic-hardening-gcp-runtime-proof-plan.md",
                        lanes
                ));
        return output;
    }

    public InitialLedger phase0PassedLedger() {
        List<Lane> lanes = new ArrayList<>(initialLedger().lanes());
        lanes.set(0, new Lane(
                "gcp-readiness-smoke",
                LaneState.PASS.name(),
                true,
                FailureClass.NONE.name(),
                null,
                List.of(),
                List.of(),
                List.of("phase0-readiness", "phase0-smoke", "phase0-verdict"),
                List.of(),
                List.of(SemanticHardeningEvidenceContracts.GCP_ENVIRONMENT_ROOT + "/"),
                List.of(SemanticHardeningEvidenceContracts.PROGRESS_LEDGER_PATH),
                List.of(),
                List.of(),
                null,
                0,
                1,
                SemanticHardeningEvidenceContracts.GCP_ENVIRONMENT_ROOT,
                "PASS",
                null,
                "PASS"
        ));
        return new InitialLedger(
                "1",
                Instant.now().toString(),
                "docs/verification/blueprint-semantic-hardening-gcp-runtime-proof-plan.md",
                lanes
        );
    }

    public Path writePhase0PassedLedger(ObjectMapper objectMapper, Path output) throws IOException {
        Files.createDirectories(output.getParent());
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(output.toFile(), phase0PassedLedger());
        return output;
    }

    private Lane readinessLane() {
        return new Lane(
                "gcp-readiness-smoke",
                LaneState.READY_TO_RUN.name(),
                true,
                FailureClass.NONE.name(),
                null,
                List.of(),
                List.of(),
                List.of("phase0-readiness", "phase0-smoke", "phase0-verdict"),
                List.of(),
                List.of(SemanticHardeningEvidenceContracts.GCP_ENVIRONMENT_ROOT + "/"),
                List.of(SemanticHardeningEvidenceContracts.PROGRESS_LEDGER_PATH),
                List.of(),
                List.of("gcp-golden", "gcp-full"),
                null,
                0,
                1,
                SemanticHardeningEvidenceContracts.GCP_ENVIRONMENT_ROOT,
                "PENDING",
                null,
                "PASS"
        );
    }

    private Lane nonCloudReadyLane(String lane, List<String> taskIds) {
        return new Lane(
                lane,
                LaneState.READY_TO_RUN.name(),
                false,
                FailureClass.NONE.name(),
                null,
                List.of(),
                List.of(),
                taskIds,
                List.of(),
                List.of("backend/src/test/java/com/pulse/e2e/semantic/"),
                List.of(SemanticHardeningEvidenceContracts.PROGRESS_LEDGER_PATH),
                List.of(),
                List.of(),
                null,
                0,
                1,
                "backend/build/e2e-semantic-hardening/" + lane,
                "NOT_REQUIRED",
                null,
                "PASS"
        );
    }

    private Lane pendingLane(String lane, boolean requiresGcp, List<String> prerequisites, List<String> taskIds) {
        return new Lane(
                lane,
                LaneState.PENDING.name(),
                requiresGcp,
                FailureClass.NONE.name(),
                null,
                List.of(),
                List.of(),
                taskIds,
                prerequisites,
                List.of("backend/build/e2e-semantic-hardening/" + lane + "/"),
                List.of(SemanticHardeningEvidenceContracts.PROGRESS_LEDGER_PATH),
                List.of(),
                List.of(),
                null,
                0,
                1,
                "backend/build/e2e-semantic-hardening/" + lane,
                requiresGcp ? "PENDING" : "NOT_REQUIRED",
                null,
                "PASS"
        );
    }

    private Lane databaseDestinationLane() {
        return new Lane(
                "databasewriter-destination-selection",
                LaneState.PASS.name(),
                true,
                FailureClass.NONE.name(),
                null,
                List.of(),
                List.of(),
                List.of("select-databasewriter-gcp-destination"),
                List.of("gcp-readiness-smoke"),
                List.of("backend/src/test/resources/e2e/semantic/database-writer-gcp-destination-decision.json"),
                List.of(SemanticHardeningEvidenceContracts.PROGRESS_LEDGER_PATH),
                List.of(),
                List.of(),
                null,
                0,
                1,
                "backend/build/e2e-semantic-hardening/gcp-golden/database-writer",
                "PASS",
                null,
                "PASS"
        );
    }

    public record InitialLedger(
            String schemaVersion,
            String generatedAt,
            String planPath,
            List<Lane> lanes
    ) {
    }

    public record Lane(
            String lane,
            String laneState,
            boolean requiresGcp,
            String failureClass,
            String failureFingerprint,
            List<String> blockers,
            List<FailureHistoryEntry> failureHistory,
            List<String> taskIds,
            List<String> prerequisiteLanes,
            List<String> ownedPaths,
            List<String> sharedPaths,
            List<String> allowedContinuationLanes,
            List<String> blockedProofShapes,
            String nextRetryAt,
            int retryCount,
            int retryCeiling,
            String evidenceRoot,
            String cleanupStatus,
            String terminalStopReason,
            String promotionPrivilegeStatus
    ) {
        public Lane {
            blockers = blockers == null ? List.of() : List.copyOf(blockers);
            failureHistory = failureHistory == null ? List.of() : List.copyOf(failureHistory);
            taskIds = taskIds == null ? List.of() : List.copyOf(taskIds);
            prerequisiteLanes = prerequisiteLanes == null ? List.of() : List.copyOf(prerequisiteLanes);
            ownedPaths = ownedPaths == null ? List.of() : List.copyOf(ownedPaths);
            sharedPaths = sharedPaths == null ? List.of() : List.copyOf(sharedPaths);
            allowedContinuationLanes = allowedContinuationLanes == null ? List.of() : List.copyOf(allowedContinuationLanes);
            blockedProofShapes = blockedProofShapes == null ? List.of() : List.copyOf(blockedProofShapes);
        }
    }

    public record FailureHistoryEntry(
            String failedAt,
            String failureClass,
            String failureFingerprint,
            String primaryArtifactPath,
            String reasonCode
    ) {
    }
}
