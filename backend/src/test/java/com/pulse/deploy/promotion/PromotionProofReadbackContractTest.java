package com.pulse.deploy.promotion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * PKT-0008 — readback DTO contract tests.
 *
 * <p>Verifies that the readback correctly represents evidence tiers
 * and that higher-tier evidence fields are null when the tier has
 * not been reached. This is the structural negative evidence that
 * prevents a consumer from misinterpreting the proof state.
 */
class PromotionProofReadbackContractTest {

    @Test
    @DisplayName("Draft workspace readback has null for all tiers above workspace")
    void draftWorkspaceReadbackNullsHigherTiers() {
        var readback = new PromotionProofReadback(
                "v1",
                PromotionProofState.DRAFT_WORKSPACE,
                new PromotionProofReadback.WorkspaceEvidence("ws1", "ACTIVE", "dirty", Instant.now()),
                null, null, null, null, null, null);

        assertEquals(PromotionProofState.DRAFT_WORKSPACE, readback.currentState());
        assertNotNull(readback.workspace());
        assertNull(readback.acceptance());
        assertNull(readback.staticPackage());
        assertNull(readback.deployRequest());
        assertNull(readback.runtimeProof());
        assertNull(readback.promotionGate());
        assertNull(readback.promotionComplete());
        assertFalse(PromotionProofState.hasRuntimeEvidence(readback.currentState()));
    }

    @Test
    @DisplayName("Static package proof readback does not include runtime proof evidence")
    void staticPackageReadbackNoRuntimeProof() {
        var readback = new PromotionProofReadback(
                "v1",
                PromotionProofState.STATIC_PACKAGE_PROOF,
                new PromotionProofReadback.WorkspaceEvidence("ws1", "ACTIVE", "clean", Instant.now()),
                new PromotionProofReadback.AcceptanceEvidence("a1", "MERGED_PR_EXACT_HEAD", "pkg1", "abc123", Instant.now()),
                new PromotionProofReadback.StaticPackageEvidence("pkg1", "GIT_COMMIT", true, "LIKELY_DEPLOYABLE", List.of(), Instant.now()),
                null, null, null, null);

        assertEquals(PromotionProofState.STATIC_PACKAGE_PROOF, readback.currentState());
        assertNotNull(readback.staticPackage());
        assertNull(readback.deployRequest(), "deploy request must be null at static package proof tier");
        assertNull(readback.runtimeProof(), "runtime proof must be null at static package proof tier");
        assertNull(readback.promotionGate(), "promotion gate must be null at static package proof tier");
        assertNull(readback.promotionComplete(), "promotion complete must be null at static package proof tier");
        assertFalse(PromotionProofState.hasRuntimeEvidence(readback.currentState()),
                "static package proof state must not report runtime evidence");
    }

    @Test
    @DisplayName("Deploy requested readback does not include runtime proof evidence")
    void deployRequestedReadbackNoRuntimeProof() {
        var readback = new PromotionProofReadback(
                "v1",
                PromotionProofState.DEPLOY_REQUESTED,
                new PromotionProofReadback.WorkspaceEvidence("ws1", "ACTIVE", "clean", Instant.now()),
                new PromotionProofReadback.AcceptanceEvidence("a1", "MERGED_PR_EXACT_HEAD", "pkg1", "abc123", Instant.now()),
                new PromotionProofReadback.StaticPackageEvidence("pkg1", "GIT_COMMIT", true, "LIKELY_DEPLOYABLE", List.of(), Instant.now()),
                new PromotionProofReadback.DeployRequestEvidence("dep1", "dev", "RUNNING", Instant.now()),
                null, null, null);

        assertEquals(PromotionProofState.DEPLOY_REQUESTED, readback.currentState());
        assertNotNull(readback.deployRequest());
        assertNull(readback.runtimeProof(), "runtime proof must be null when only deploy was requested");
        assertNull(readback.promotionGate(), "promotion gate must be null when only deploy was requested");
        assertNull(readback.promotionComplete(), "promotion complete must be null when only deploy was requested");
        assertFalse(PromotionProofState.hasRuntimeEvidence(readback.currentState()),
                "deploy requested state must not report runtime evidence");
    }

    @Test
    @DisplayName("Runtime proved readback includes runtime evidence but not promotion complete")
    void runtimeProvedReadbackNoPromotionComplete() {
        var readback = new PromotionProofReadback(
                "v1",
                PromotionProofState.RUNTIME_PROVED,
                new PromotionProofReadback.WorkspaceEvidence("ws1", "ACTIVE", "clean", Instant.now()),
                new PromotionProofReadback.AcceptanceEvidence("a1", "MERGED_PR_EXACT_HEAD", "pkg1", "abc123", Instant.now()),
                new PromotionProofReadback.StaticPackageEvidence("pkg1", "GIT_COMMIT", true, "LIKELY_DEPLOYABLE", List.of(), Instant.now()),
                new PromotionProofReadback.DeployRequestEvidence("dep1", "dev", "RUNNING", Instant.now()),
                new PromotionProofReadback.RuntimeProofEvidence("run1", "SUCCEEDED", Instant.now()),
                null, null);

        assertEquals(PromotionProofState.RUNTIME_PROVED, readback.currentState());
        assertNotNull(readback.runtimeProof());
        assertNull(readback.promotionGate(), "promotion gate must be null when only runtime proof exists");
        assertNull(readback.promotionComplete(), "promotion complete must be null when only runtime proof exists");
    }

    @Test
    @DisplayName("Promotion complete readback has all tiers populated")
    void promotionCompleteReadbackAllTiersPopulated() {
        var now = Instant.now();
        var readback = new PromotionProofReadback(
                "v1",
                PromotionProofState.PROMOTION_COMPLETE,
                new PromotionProofReadback.WorkspaceEvidence("ws1", "ACTIVE", "clean", now),
                new PromotionProofReadback.AcceptanceEvidence("a1", "MERGED_PR_EXACT_HEAD", "pkg1", "abc123", now),
                new PromotionProofReadback.StaticPackageEvidence("pkg1", "GIT_COMMIT", true, "LIKELY_DEPLOYABLE", List.of(), now),
                new PromotionProofReadback.DeployRequestEvidence("dep1", "integration", "ACTIVE", now),
                new PromotionProofReadback.RuntimeProofEvidence("run1", "SUCCEEDED", now),
                new PromotionProofReadback.PromotionGateEvidence("appr1", "APPROVED", now),
                new PromotionProofReadback.PromotionCompleteEvidence("dep1", "integration", now));

        assertEquals(PromotionProofState.PROMOTION_COMPLETE, readback.currentState());
        assertNotNull(readback.workspace());
        assertNotNull(readback.acceptance());
        assertNotNull(readback.staticPackage());
        assertNotNull(readback.deployRequest());
        assertNotNull(readback.runtimeProof());
        assertNotNull(readback.promotionGate());
        assertNotNull(readback.promotionComplete());
    }

    @Test
    @DisplayName("NEGATIVE: static deployability cannot be rendered as runtimeProved or promotionComplete")
    void staticDeployabilityNegativeEvidence() {
        // A package with static LIKELY_DEPLOYABLE verdict but no deployment run
        var readback = new PromotionProofReadback(
                "v1",
                PromotionProofState.STATIC_PACKAGE_PROOF,
                new PromotionProofReadback.WorkspaceEvidence("ws1", "ACTIVE", "clean", Instant.now()),
                new PromotionProofReadback.AcceptanceEvidence("a1", "MERGED_PR_EXACT_HEAD", "pkg1", "abc", Instant.now()),
                new PromotionProofReadback.StaticPackageEvidence("pkg1", "GIT_COMMIT", true, "LIKELY_DEPLOYABLE", List.of(), Instant.now()),
                null, null, null, null);

        // The readback explicitly communicates that no runtime proof exists
        assertNull(readback.runtimeProof());
        assertNull(readback.promotionComplete());
        // The state machine prevents treating this as runtime-proved
        assertFalse(PromotionProofState.canTransition(readback.currentState(), PromotionProofState.RUNTIME_PROVED));
        assertFalse(PromotionProofState.canTransition(readback.currentState(), PromotionProofState.PROMOTION_COMPLETE));
    }

    @Test
    @DisplayName("NEGATIVE: deployRequested cannot be rendered as runtime output proof without Stage 14 evidence envelope")
    void deployRequestedNegativeRuntimeEvidence() {
        var readback = new PromotionProofReadback(
                "v1",
                PromotionProofState.DEPLOY_REQUESTED,
                new PromotionProofReadback.WorkspaceEvidence("ws1", "ACTIVE", "clean", Instant.now()),
                new PromotionProofReadback.AcceptanceEvidence("a1", "MERGED_PR_EXACT_HEAD", "pkg1", "abc", Instant.now()),
                new PromotionProofReadback.StaticPackageEvidence("pkg1", "GIT_COMMIT", true, "LIKELY_DEPLOYABLE", List.of(), Instant.now()),
                new PromotionProofReadback.DeployRequestEvidence("dep1", "dev", "RUNNING", Instant.now()),
                null, null, null);

        // The runtime proof field is null — no Stage 14 evidence envelope exists
        assertNull(readback.runtimeProof(),
                "deploy requested readback must not populate runtime proof without SUCCEEDED deployment run");
        // The state itself does not constitute runtime evidence
        assertFalse(PromotionProofState.hasRuntimeEvidence(readback.currentState()),
                "DEPLOY_REQUESTED does not constitute runtime evidence");
    }
}
