import { describe, expect, it } from "vitest";
import {
  hasRuntimeEvidence,
  isRuntimeProofLevel,
  isNonRuntimeProofLevel,
  nonRuntimeProofDisclaimer,
  PROMOTION_PROOF_STATES,
  EVIDENCE_PROOF_LEVELS,
  PROMOTION_PROOF_STATE_LABELS,
  EVIDENCE_PROOF_LEVEL_LABELS,
} from "@/types";
import type {
  PromotionProofState,
  EvidenceProofLevel,
} from "@/types";

/**
 * PKT-0022: Proof-state and evidence ladder unit tests.
 *
 * These tests verify that the proof model correctly distinguishes
 * static/local/preflight evidence from runtime proof, and that
 * non-runtime levels are never conflated with runtime success.
 */

describe("Promotion Proof State model", () => {
  it("defines seven tiers in order", () => {
    expect(PROMOTION_PROOF_STATES).toHaveLength(7);
    expect(PROMOTION_PROOF_STATES[0]).toBe("DRAFT_WORKSPACE");
    expect(PROMOTION_PROOF_STATES[6]).toBe("PROMOTION_COMPLETE");
  });

  it("has labels for every tier", () => {
    for (const state of PROMOTION_PROOF_STATES) {
      expect(PROMOTION_PROOF_STATE_LABELS[state]).toBeDefined();
      expect(typeof PROMOTION_PROOF_STATE_LABELS[state]).toBe("string");
    }
  });

  describe("hasRuntimeEvidence", () => {
    const noRuntimeStates: PromotionProofState[] = [
      "DRAFT_WORKSPACE",
      "ACCEPTED_ARTIFACT",
      "STATIC_PACKAGE_PROOF",
      "DEPLOY_REQUESTED",
    ];

    const runtimeStates: PromotionProofState[] = [
      "RUNTIME_PROVED",
      "PROMOTION_READY",
      "PROMOTION_COMPLETE",
    ];

    it.each(noRuntimeStates)(
      "returns false for %s (non-runtime tier)",
      (state) => {
        expect(hasRuntimeEvidence(state)).toBe(false);
      }
    );

    it.each(runtimeStates)(
      "returns true for %s (runtime tier)",
      (state) => {
        expect(hasRuntimeEvidence(state)).toBe(true);
      }
    );

    it("STATIC_PACKAGE_PROOF is explicitly non-runtime", () => {
      // This is the key invariant: static package proof must never
      // be treated as runtime proof or promotion-complete.
      expect(hasRuntimeEvidence("STATIC_PACKAGE_PROOF")).toBe(false);
    });

    it("DEPLOY_REQUESTED does not imply runtime success", () => {
      // A deploy request has been issued but runtime execution
      // has not completed — this is not runtime evidence.
      expect(hasRuntimeEvidence("DEPLOY_REQUESTED")).toBe(false);
    });
  });
});

describe("Evidence Proof Level model", () => {
  it("defines six levels in order", () => {
    expect(EVIDENCE_PROOF_LEVELS).toHaveLength(6);
    expect(EVIDENCE_PROOF_LEVELS[0]).toBe("STATIC_PACKAGE");
    expect(EVIDENCE_PROOF_LEVELS[5]).toBe("PROMOTION_READINESS");
  });

  it("has labels for every level", () => {
    for (const level of EVIDENCE_PROOF_LEVELS) {
      expect(EVIDENCE_PROOF_LEVEL_LABELS[level]).toBeDefined();
      expect(typeof EVIDENCE_PROOF_LEVEL_LABELS[level]).toBe("string");
    }
  });

  describe("isRuntimeProofLevel", () => {
    const nonRuntimeLevels: EvidenceProofLevel[] = [
      "STATIC_PACKAGE",
      "PREFLIGHT",
      "LOCAL_SYNTHETIC",
    ];

    const runtimeLevels: EvidenceProofLevel[] = [
      "LIVE_RUNTIME",
      "ORACLE_VERDICT",
      "PROMOTION_READINESS",
    ];

    it.each(nonRuntimeLevels)(
      "returns false for %s",
      (level) => {
        expect(isRuntimeProofLevel(level)).toBe(false);
      }
    );

    it.each(runtimeLevels)(
      "returns true for %s",
      (level) => {
        expect(isRuntimeProofLevel(level)).toBe(true);
      }
    );

    it("LOCAL_SYNTHETIC is explicitly non-runtime", () => {
      // Local synthetic evidence must never satisfy runtime gates.
      expect(isRuntimeProofLevel("LOCAL_SYNTHETIC")).toBe(false);
    });
  });

  describe("isNonRuntimeProofLevel", () => {
    it("is the complement of isRuntimeProofLevel", () => {
      for (const level of EVIDENCE_PROOF_LEVELS) {
        expect(isNonRuntimeProofLevel(level)).toBe(!isRuntimeProofLevel(level));
      }
    });
  });

  describe("nonRuntimeProofDisclaimer", () => {
    it("returns a non-empty disclaimer for STATIC_PACKAGE", () => {
      const msg = nonRuntimeProofDisclaimer("STATIC_PACKAGE");
      expect(msg.length).toBeGreaterThan(0);
      expect(msg).toContain("runtime");
    });

    it("returns a non-empty disclaimer for PREFLIGHT", () => {
      const msg = nonRuntimeProofDisclaimer("PREFLIGHT");
      expect(msg.length).toBeGreaterThan(0);
      expect(msg).toContain("runtime");
    });

    it("returns a non-empty disclaimer for LOCAL_SYNTHETIC", () => {
      const msg = nonRuntimeProofDisclaimer("LOCAL_SYNTHETIC");
      expect(msg.length).toBeGreaterThan(0);
      expect(msg.toLowerCase()).toContain("local");
      expect(msg.toLowerCase()).toContain("synthetic");
    });

    it("returns empty string for runtime levels", () => {
      expect(nonRuntimeProofDisclaimer("LIVE_RUNTIME")).toBe("");
      expect(nonRuntimeProofDisclaimer("ORACLE_VERDICT")).toBe("");
      expect(nonRuntimeProofDisclaimer("PROMOTION_READINESS")).toBe("");
    });

    it("every non-runtime disclaimer mentions it cannot satisfy runtime gates", () => {
      const nonRuntime: EvidenceProofLevel[] = [
        "STATIC_PACKAGE",
        "PREFLIGHT",
        "LOCAL_SYNTHETIC",
      ];
      for (const level of nonRuntime) {
        const msg = nonRuntimeProofDisclaimer(level);
        expect(msg.toLowerCase()).toContain("runtime");
      }
    });
  });
});

describe("Negative evidence: static/local never masquerade as runtime", () => {
  it("STATIC_PACKAGE is not runtime proof at any tier", () => {
    expect(isRuntimeProofLevel("STATIC_PACKAGE")).toBe(false);
    expect(hasRuntimeEvidence("STATIC_PACKAGE_PROOF")).toBe(false);
  });

  it("LOCAL_SYNTHETIC is not runtime proof", () => {
    expect(isRuntimeProofLevel("LOCAL_SYNTHETIC")).toBe(false);
  });

  it("PREFLIGHT is not runtime proof", () => {
    expect(isRuntimeProofLevel("PREFLIGHT")).toBe(false);
  });

  it("only LIVE_RUNTIME and above qualify as runtime proof", () => {
    const runtimeIndex = EVIDENCE_PROOF_LEVELS.indexOf("LIVE_RUNTIME");
    for (let i = 0; i < EVIDENCE_PROOF_LEVELS.length; i++) {
      const level = EVIDENCE_PROOF_LEVELS[i];
      if (i >= runtimeIndex) {
        expect(isRuntimeProofLevel(level)).toBe(true);
      } else {
        expect(isRuntimeProofLevel(level)).toBe(false);
      }
    }
  });

  it("only RUNTIME_PROVED and above have runtime evidence in promotion model", () => {
    const runtimeIndex = PROMOTION_PROOF_STATES.indexOf("RUNTIME_PROVED");
    for (let i = 0; i < PROMOTION_PROOF_STATES.length; i++) {
      const state = PROMOTION_PROOF_STATES[i];
      if (i >= runtimeIndex) {
        expect(hasRuntimeEvidence(state)).toBe(true);
      } else {
        expect(hasRuntimeEvidence(state)).toBe(false);
      }
    }
  });
});
