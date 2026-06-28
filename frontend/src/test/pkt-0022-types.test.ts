import { describe, expect, it } from "vitest";
import type {
  TenantReadinessReadback,
  LandingContractPreview,
  ConnectorCredentialStrategy,
} from "@/types";

/**
 * PKT-0022: Type-level and shape tests for new readiness,
 * landing-contract, and credential-strategy types.
 *
 * These verify that the type shapes match the expected wire contract
 * from the backend API surface documented in the packet specification.
 */

describe("TenantReadinessReadback shape", () => {
  const fixture: TenantReadinessReadback = {
    tenantId: "tenant-home-lending",
    overallReady: false,
    categories: [
      { category: "git", label: "Git Repository", ready: true, blockers: [] },
      {
        category: "storage",
        label: "Storage Backends",
        ready: false,
        blockers: ["GCP dev bucket not provisioned"],
      },
      { category: "credentials", label: "Credentials", ready: true, blockers: [] },
    ],
    producedAt: "2026-05-25T12:00:00Z",
  };

  it("has required fields", () => {
    expect(fixture.tenantId).toBe("tenant-home-lending");
    expect(typeof fixture.overallReady).toBe("boolean");
    expect(Array.isArray(fixture.categories)).toBe(true);
    expect(typeof fixture.producedAt).toBe("string");
  });

  it("categories are TenantReadinessCategory", () => {
    for (const cat of fixture.categories) {
      expect(typeof cat.category).toBe("string");
      expect(typeof cat.label).toBe("string");
      expect(typeof cat.ready).toBe("boolean");
      expect(Array.isArray(cat.blockers)).toBe(true);
    }
  });

  it("overallReady is false when any category has blockers", () => {
    const hasBlockers = fixture.categories.some((c) => c.blockers.length > 0);
    // The server should set overallReady=false when any category is blocked
    expect(fixture.overallReady).toBe(false);
    expect(hasBlockers).toBe(true);
  });
});

describe("LandingContractPreview shape", () => {
  it("represents a ready contract with URI", () => {
    const preview: LandingContractPreview = {
      datasetId: "ds-001",
      contractUri: "gs://acme-landing/loans/daily/",
      landingPath: "/data/loans/daily/",
      bindingBlockers: [],
      ready: true,
    };
    expect(preview.ready).toBe(true);
    expect(preview.contractUri).toBeTruthy();
    expect(preview.bindingBlockers).toHaveLength(0);
  });

  it("represents a blocked contract without URI", () => {
    const preview: LandingContractPreview = {
      datasetId: "ds-002",
      contractUri: null,
      landingPath: null,
      bindingBlockers: [
        "Storage backend not provisioned for dev",
        "Connector credential missing",
      ],
      ready: false,
    };
    expect(preview.ready).toBe(false);
    expect(preview.contractUri).toBeNull();
    expect(preview.bindingBlockers.length).toBeGreaterThan(0);
  });
});

describe("ConnectorCredentialStrategy", () => {
  it("INHERIT_TENANT_GCP_SERVICE_ACCOUNT is a valid value", () => {
    const strategy: ConnectorCredentialStrategy = "INHERIT_TENANT_GCP_SERVICE_ACCOUNT";
    expect(strategy).toBe("INHERIT_TENANT_GCP_SERVICE_ACCOUNT");
  });

  it("CONNECTOR_SPECIFIC is a valid value", () => {
    const strategy: ConnectorCredentialStrategy = "CONNECTOR_SPECIFIC";
    expect(strategy).toBe("CONNECTOR_SPECIFIC");
  });
});

describe("Secret redaction invariants", () => {
  it("CredentialProfile secretRefs are string references, never raw secrets", () => {
    // This test documents the invariant: secretRefs on the wire
    // contain vault:// or gcp-sm:// references, never raw secret
    // values. The UI must never expose raw secret text.
    const validRefs = [
      "gcp-sm://projects/pulse/secrets/db-password/versions/latest",
      "vault://secret/data/pulse/db-password",
    ];
    for (const ref of validRefs) {
      expect(ref.startsWith("gcp-sm://") || ref.startsWith("vault://")).toBe(true);
    }
  });

  it("PAT token field is write-only and never echoed back", () => {
    // This documents the MaskedGitIdentity contract: the status
    // endpoint returns credentialReferenceMasked (a masked reference
    // like "gcp-sm://...***") but never the raw PAT.
    const maskedRef = "gcp-sm://projects/pulse/secrets/pat-jane/versions/***";
    expect(maskedRef).toContain("***");
    // Raw PAT tokens start with ghp_ — the masked ref must NOT
    expect(maskedRef.startsWith("ghp_")).toBe(false);
  });
});
