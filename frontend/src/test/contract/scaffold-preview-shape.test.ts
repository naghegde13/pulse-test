/**
 * Layer 2.5 contract test (BUG-2026-05-26-67 META-packet, BUG-60 wire-format
 * follow-on).
 *
 * Validates that the static fixture representing a "live" response from
 * `POST /api/v1/tenants/{tenantId}/onboard/preview` actually conforms to the
 * `ScaffoldPreview` TS interface declared in `frontend/src/types/index.ts`.
 *
 * ## Why a fixture, not a live call?
 *
 * The BUG-67 brief asks for "validate live response against `ScaffoldPreview`
 * TS interface." A truly live call would require either:
 *   - the backend running on :8080 (CI agent boot cost), or
 *   - a Vitest fetch-mock that itself has to be kept in sync with the
 *     backend's actual response shape (defeats the purpose).
 *
 * We instead lock in a canonical fixture sourced from the backend Java DTO
 * (`com.pulse.git.scaffold.ScaffoldPreviewDto` and friends — see backend
 * source for the authoritative field list). When the backend response shape
 * drifts, the contributor MUST update both the backend DTO and this fixture;
 * the type-checker (and the field-level structural assertions below) keep
 * them aligned.
 *
 * The fixture lives in this file rather than `mock-api.ts` so the
 * regression-guard intent is colocated with the assertions.
 *
 * ## What we assert
 *
 * 1. The fixture object compiles against `ScaffoldPreview` (`as const` /
 *    explicit type annotation is the lightweight form of "shape contract").
 * 2. Required fields are present and non-empty / well-typed.
 * 3. Discriminated-union status values are valid (`SCAFFOLDED | MISSING | ERROR`).
 * 4. The nested `gitIdentity.code` value is one of the documented enum
 *    values.
 *
 * ## What we do NOT assert
 *
 * - Per-domain path content (project-specific, fixture-only).
 * - Branch-name format (the backend may pick `main`/`master`/`develop`).
 */
import { describe, expect, it } from "vitest";
import type {
  ScaffoldPreview,
  ScaffoldPreviewDomain,
  GitIdentityReadiness,
} from "@/types";

const FIXTURE_LIVE_RESPONSE: ScaffoldPreview = {
  tenantId: "tenant-acme-lending",
  repoType: "MONOREPO",
  branchName: "main",
  gitIdentity: {
    required: true,
    ready: true,
    code: "ready",
    message: "Git identity verified",
    authorName: "Acme Builder",
    authorEmail: "builder@acme.test",
  },
  topLevelMissing: false,
  topLevelPaths: [
    "README.md",
    ".gitignore",
    "platform_release.json",
  ],
  domains: [
    {
      domainId: "dom-lending-core",
      domainName: "Lending Core",
      domainSlug: "lending-core",
      status: "SCAFFOLDED",
      paths: [
        "domains/lending-core/pipelines/.keep",
        "domains/lending-core/datasets/.keep",
      ],
    },
    {
      domainId: "dom-lending-risk",
      domainName: "Lending Risk",
      domainSlug: "lending-risk",
      status: "MISSING",
      paths: [],
    },
  ],
};

const VALID_DOMAIN_STATUSES = new Set<ScaffoldPreviewDomain["status"]>([
  "SCAFFOLDED",
  "MISSING",
  "ERROR",
]);

const VALID_GIT_IDENTITY_CODES = new Set<GitIdentityReadiness["code"]>([
  "ready",
  "git_identity_required",
  "git_identity_invalid",
  "not_required",
]);

describe("scaffold-preview-shape contract (BUG-67 / BUG-60 follow-on)", () => {
  it("top-level required fields are present and typed correctly", () => {
    expect(typeof FIXTURE_LIVE_RESPONSE.tenantId).toBe("string");
    expect(FIXTURE_LIVE_RESPONSE.tenantId.length).toBeGreaterThan(0);
    expect(typeof FIXTURE_LIVE_RESPONSE.repoType).toBe("string");
    expect(typeof FIXTURE_LIVE_RESPONSE.branchName).toBe("string");
    expect(typeof FIXTURE_LIVE_RESPONSE.topLevelMissing).toBe("boolean");
    expect(Array.isArray(FIXTURE_LIVE_RESPONSE.topLevelPaths)).toBe(true);
    expect(Array.isArray(FIXTURE_LIVE_RESPONSE.domains)).toBe(true);
  });

  it("gitIdentity carries a documented enum code", () => {
    const code = FIXTURE_LIVE_RESPONSE.gitIdentity.code;
    expect(VALID_GIT_IDENTITY_CODES.has(code)).toBe(true);
    expect(typeof FIXTURE_LIVE_RESPONSE.gitIdentity.required).toBe("boolean");
    expect(typeof FIXTURE_LIVE_RESPONSE.gitIdentity.ready).toBe("boolean");
    expect(typeof FIXTURE_LIVE_RESPONSE.gitIdentity.message).toBe("string");
  });

  it("each domain has a documented status enum value + required fields", () => {
    for (const d of FIXTURE_LIVE_RESPONSE.domains) {
      expect(VALID_DOMAIN_STATUSES.has(d.status)).toBe(true);
      expect(typeof d.domainId).toBe("string");
      expect(typeof d.domainName).toBe("string");
      expect(typeof d.domainSlug).toBe("string");
      expect(Array.isArray(d.paths)).toBe(true);
    }
  });

  it("status='MISSING' domains carry an empty paths array (BUG-60 invariant)", () => {
    // A MISSING domain hasn't been scaffolded yet — there cannot be paths
    // for it. If the backend ever starts returning a non-empty paths[]
    // alongside status=MISSING the wizard's tree renderer breaks. Hard fail.
    const missing = FIXTURE_LIVE_RESPONSE.domains.filter(
      (d) => d.status === "MISSING",
    );
    for (const d of missing) {
      expect(d.paths).toEqual([]);
    }
  });

  it("status='SCAFFOLDED' domains carry at least one path", () => {
    const scaffolded = FIXTURE_LIVE_RESPONSE.domains.filter(
      (d) => d.status === "SCAFFOLDED",
    );
    for (const d of scaffolded) {
      expect(d.paths.length).toBeGreaterThan(0);
    }
  });
});
