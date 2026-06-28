package com.pulse.deploy.promotion;

import java.time.Instant;
import java.util.List;

/**
 * PKT-0008 — immutable readback of the current promotion proof state
 * for a pipeline version. Returned by the
 * {@code GET /api/v1/versions/{versionId}/promotion-proof-state}
 * endpoint.
 *
 * <p>Each evidence field is populated only when the corresponding
 * proof tier has been reached. A null field means that tier's
 * evidence has not been produced — the consumer must not infer
 * higher-tier proof from lower-tier evidence.
 */
public record PromotionProofReadback(
        String versionId,
        PromotionProofState currentState,

        // Tier 1: Draft workspace
        WorkspaceEvidence workspace,

        // Tier 2: Accepted artifact (PR merged)
        AcceptanceEvidence acceptance,

        // Tier 3: Static package proof
        StaticPackageEvidence staticPackage,

        // Tier 4: Deploy requested
        DeployRequestEvidence deployRequest,

        // Tier 5: Runtime proved
        RuntimeProofEvidence runtimeProof,

        // Tier 6: Promotion ready
        PromotionGateEvidence promotionGate,

        // Tier 7: Promotion complete
        PromotionCompleteEvidence promotionComplete
) {

    public record WorkspaceEvidence(
            String workspaceId,
            String lifecycleStatus,
            String workingTreeStatus,
            Instant observedAt
    ) {}

    public record AcceptanceEvidence(
            String acceptanceId,
            String acceptanceKind,
            String acceptedPackageId,
            String acceptedCommitSha,
            Instant acceptedAt
    ) {}

    public record StaticPackageEvidence(
            String packageId,
            String sourceKind,
            boolean promotable,
            String staticVerdict,
            List<String> blockers,
            Instant builtAt
    ) {}

    public record DeployRequestEvidence(
            String deploymentId,
            String targetEnvironment,
            String status,
            Instant requestedAt
    ) {}

    public record RuntimeProofEvidence(
            String deploymentRunId,
            String runState,
            Instant succeededAt
    ) {}

    public record PromotionGateEvidence(
            String approvalId,
            String approvalStatus,
            Instant decidedAt
    ) {}

    public record PromotionCompleteEvidence(
            String promotedDeploymentId,
            String targetEnvironment,
            Instant promotedAt
    ) {}
}
