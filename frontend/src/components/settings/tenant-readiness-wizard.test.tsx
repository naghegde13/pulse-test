import { describe, it, expect, afterEach } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { installMockApi, type MockApi } from "@/test/mock-api";
import { TenantReadinessWizard } from "./tenant-readiness-wizard";
import type { TenantReadiness, TenantReadinessStep, TenantReadinessBlocker } from "@/types";
import { READINESS_CATEGORY_KEYS, READINESS_CATEGORY_LABELS } from "@/types";

/* ── Helpers ────────────────────────────────────────────────── */

let mockApi: MockApi;

function setup() {
  mockApi = installMockApi();
  // Stub tenant header lookup (api.ts reads localStorage)
  window.localStorage.setItem("pulse.currentTenantId", "acme");
}

afterEach(() => {
  mockApi?.restore();
});

function readyStep(name: string, evidence: Record<string, unknown> = {}): TenantReadinessStep {
  return { name, status: "ready", blockers: [], ownership: null, evidence };
}

function blockedStep(
  name: string,
  blockers: TenantReadinessBlocker[],
  evidence: Record<string, unknown> = {},
  ownership: TenantReadinessStep["ownership"] = null,
): TenantReadinessStep {
  return { name, status: "blocked", blockers, ownership, evidence };
}

function notConfiguredStep(
  name: string,
  blockers: TenantReadinessBlocker[],
  evidence: Record<string, unknown> = {},
): TenantReadinessStep {
  return { name, status: "not_configured", blockers, ownership: null, evidence };
}

function makeBlocker(overrides: Partial<TenantReadinessBlocker> = {}): TenantReadinessBlocker {
  return {
    code: "TEST_BLOCKER",
    message: "Test blocker message",
    sourceSurface: "TestService",
    evidenceRef: "GET /api/v1/test",
    staleCheckTimestamp: "2026-05-25T10:00:00Z",
    safeNextAction: "Fix the thing",
    operatorRequired: false,
    ...overrides,
  };
}

function buildAllReadyVerdict(): TenantReadiness {
  const categories: Record<string, TenantReadinessStep> = {};
  for (const key of READINESS_CATEGORY_KEYS) {
    categories[key] = readyStep(key, { source: "test" });
  }
  return {
    tenantId: "acme",
    overallStatus: "ready",
    checkedAt: "2026-05-25T10:00:00Z",
    categories,
    blockerSummary: [],
    readyCategoryCount: 16,
    totalCategoryCount: 16,
  };
}

function buildNotReadyVerdict(): TenantReadiness {
  const categories: Record<string, TenantReadinessStep> = {};
  for (const key of READINESS_CATEGORY_KEYS) {
    categories[key] = readyStep(key);
  }
  // Override specific categories as blocked
  categories.gcpConfig = blockedStep("gcpConfig", [
    makeBlocker({
      code: "MISSING_GCP_CONFIG",
      message: "Tenant GCP config not set",
      safeNextAction: "PUT /api/v1/tenants/acme/gcp-config",
      operatorRequired: true,
    }),
  ]);
  categories.composer = notConfiguredStep("composer", [
    makeBlocker({
      code: "MISSING_COMPOSER_TOPOLOGY",
      message: "No GCP runtime topology configured",
      operatorRequired: true,
    }),
  ]);
  categories.iamManifest = blockedStep(
    "iamManifest",
    [
      makeBlocker({
        code: "IAM_MANIFEST_NOT_GENERATED",
        message: "IAM manifest cannot be generated",
      }),
    ],
    { manifestStatus: "not_generated" },
  );

  const allBlockers = Object.values(categories).flatMap((c) => c.blockers);

  return {
    tenantId: "acme",
    overallStatus: "blocked",
    checkedAt: "2026-05-25T10:00:00Z",
    categories,
    blockerSummary: allBlockers,
    readyCategoryCount: 13,
    totalCategoryCount: 16,
  };
}

/* ── Tests ──────────────────────────────────────────────────── */

describe("TenantReadinessWizard", () => {
  describe("READY payload", () => {
    it("displays READY badge when all categories pass", async () => {
      setup();
      mockApi.mock("GET", "/readiness/consolidated", buildAllReadyVerdict());

      render(<TenantReadinessWizard tenantId="acme" />);

      const readyBadge = await screen.findByText("READY");
      expect(readyBadge).toBeInTheDocument();
    });

    it("shows 16/16 categories ready", async () => {
      setup();
      mockApi.mock("GET", "/readiness/consolidated", buildAllReadyVerdict());

      render(<TenantReadinessWizard tenantId="acme" />);

      await screen.findByText("READY");
      expect(screen.getByText("16 / 16 categories ready")).toBeInTheDocument();
    });

    it("does not show blocker summary when ready", async () => {
      setup();
      mockApi.mock("GET", "/readiness/consolidated", buildAllReadyVerdict());

      render(<TenantReadinessWizard tenantId="acme" />);

      await screen.findByText("READY");
      expect(screen.queryByText(/blocker.*preventing readiness/)).not.toBeInTheDocument();
    });
  });

  describe("NOT_READY payload", () => {
    it("displays NOT READY badge when some categories fail", async () => {
      setup();
      mockApi.mock("GET", "/readiness/consolidated", buildNotReadyVerdict());

      render(<TenantReadinessWizard tenantId="acme" />);

      const badge = await screen.findByText("NOT READY");
      expect(badge).toBeInTheDocument();
    });

    it("shows correct ready/total count", async () => {
      setup();
      mockApi.mock("GET", "/readiness/consolidated", buildNotReadyVerdict());

      render(<TenantReadinessWizard tenantId="acme" />);

      await screen.findByText("NOT READY");
      expect(screen.getByText("13 / 16 categories ready")).toBeInTheDocument();
    });

    it("shows blocker summary", async () => {
      setup();
      mockApi.mock("GET", "/readiness/consolidated", buildNotReadyVerdict());

      render(<TenantReadinessWizard tenantId="acme" />);

      await screen.findByText("NOT READY");
      expect(screen.getByText(/blocker.*preventing readiness/)).toBeInTheDocument();
    });

    it("shows FAIL badge for blocked categories", async () => {
      setup();
      mockApi.mock("GET", "/readiness/consolidated", buildNotReadyVerdict());

      render(<TenantReadinessWizard tenantId="acme" />);

      await screen.findByText("NOT READY");
      const failBadges = screen.getAllByText("FAIL");
      expect(failBadges.length).toBe(2); // gcpConfig, iamManifest
    });

    it("shows BLOCKED badge for not_configured categories", async () => {
      setup();
      mockApi.mock("GET", "/readiness/consolidated", buildNotReadyVerdict());

      render(<TenantReadinessWizard tenantId="acme" />);

      await screen.findByText("NOT READY");
      const blockedBadges = screen.getAllByText("BLOCKED");
      expect(blockedBadges.length).toBe(1); // composer
    });
  });

  describe("All 16 category labels", () => {
    it("renders all 16 readiness category labels", async () => {
      setup();
      mockApi.mock("GET", "/readiness/consolidated", buildAllReadyVerdict());

      render(<TenantReadinessWizard tenantId="acme" />);

      await screen.findByText("READY");

      for (const key of READINESS_CATEGORY_KEYS) {
        const label = READINESS_CATEGORY_LABELS[key];
        expect(screen.getByText(label)).toBeInTheDocument();
      }
    });
  });

  describe("Stale/missing data", () => {
    it("renders NOT READY when API fails", async () => {
      setup();
      mockApi.mock("GET", "/readiness/consolidated", { detail: "Server error" }, 500);

      render(<TenantReadinessWizard tenantId="acme" />);

      const badge = await screen.findByText("NOT READY");
      expect(badge).toBeInTheDocument();
      expect(screen.getByText("Unable to determine readiness")).toBeInTheDocument();
    });

    it("shows MISSING badge when a category is absent from the response", async () => {
      setup();
      const verdict = buildAllReadyVerdict();
      // Remove one category to simulate missing data
      delete verdict.categories.evidenceLogging;
      mockApi.mock("GET", "/readiness/consolidated", verdict);

      render(<TenantReadinessWizard tenantId="acme" />);

      await screen.findByText("READY");
      expect(screen.getByText("MISSING")).toBeInTheDocument();
    });

    it("renders stale-check timestamps in blocker details", async () => {
      setup();
      mockApi.mock("GET", "/readiness/consolidated", buildNotReadyVerdict());

      const user = userEvent.setup();
      render(<TenantReadinessWizard tenantId="acme" />);

      await screen.findByText("NOT READY");

      // Expand the GCP Config category
      const gcpConfigRow = screen.getByText("GCP Configuration");
      await user.click(gcpConfigRow);

      // Should show stale check timestamp — look for lowercase "checked" in blocker rows
      // (the summary has "Checked" with capital C)
      const checkedElements = screen.getAllByText(/checked/i);
      expect(checkedElements.length).toBeGreaterThanOrEqual(2); // summary + blocker
    });
  });

  describe("PKT-FINAL-3 BUG-05: LOCAL repos removed", () => {
    it("no LOCAL warning is rendered even when an evidence row claims repoType=LOCAL", async () => {
      // After PKT-FINAL-3 the only possible repoType is REMOTE. Defensive
      // check: even if a stale evidence payload sneaks LOCAL through, the
      // wizard must not display the old amber warning.
      setup();
      const verdict = buildAllReadyVerdict();
      verdict.categories.githubRepo = readyStep("githubRepo", {
        repoUrl: "https://github.com/placeholder/pulse-acme.git",
        repoType: "LOCAL",
        provider: "GITHUB",
      });
      mockApi.mock("GET", "/readiness/consolidated", verdict);

      render(<TenantReadinessWizard tenantId="acme" />);

      await screen.findByText("READY");
      expect(screen.queryByText("LOCAL — not cloud-backed")).not.toBeInTheDocument();
    });
  });

  describe("GCP topology blockers", () => {
    it("renders composer, dataproc, bigQuery, secretManager, evidenceLogging blockers as step-level failures", async () => {
      setup();
      const verdict = buildAllReadyVerdict();
      verdict.overallStatus = "blocked";

      const gcpCategories = ["composer", "dataproc", "bigQuery", "secretManager", "evidenceLogging"] as const;
      for (const key of gcpCategories) {
        verdict.categories[key] = notConfiguredStep(key, [
          makeBlocker({ code: `MISSING_${key.toUpperCase()}_TOPOLOGY` }),
        ]);
      }

      verdict.readyCategoryCount = 11;
      verdict.blockerSummary = Object.values(verdict.categories).flatMap((c) => c.blockers);

      mockApi.mock("GET", "/readiness/consolidated", verdict);

      render(<TenantReadinessWizard tenantId="acme" />);

      await screen.findByText("NOT READY");

      // All 5 GCP categories should show BLOCKED
      const blockedBadges = screen.getAllByText("BLOCKED");
      expect(blockedBadges.length).toBe(5);
    });
  });

  describe("Ownership and timestamp rendering", () => {
    it("renders create-vs-validate ownership when present", async () => {
      setup();
      const verdict = buildAllReadyVerdict();
      verdict.categories.composer = readyStep("composer", {
        projectId: "my-project",
        environment: "us-central1-composer",
      });
      verdict.categories.composer.ownership = {
        resourceKind: "Composer Environment",
        createOwner: "operator",
        validateOwner: "pulse",
      };
      mockApi.mock("GET", "/readiness/consolidated", verdict);

      const user = userEvent.setup();
      render(<TenantReadinessWizard tenantId="acme" />);

      await screen.findByText("READY");

      // Expand Cloud Composer
      await user.click(screen.getByText("Cloud Composer"));

      expect(screen.getByText("create: operator")).toBeInTheDocument();
      expect(screen.getByText("validate: pulse")).toBeInTheDocument();
      expect(screen.getByText("(Composer Environment)")).toBeInTheDocument();
    });

    it("renders checked-at timestamp", async () => {
      setup();
      mockApi.mock("GET", "/readiness/consolidated", buildAllReadyVerdict());

      render(<TenantReadinessWizard tenantId="acme" />);

      await screen.findByText("READY");
      expect(screen.getByText(/Checked/)).toBeInTheDocument();
    });
  });

  describe("Redaction", () => {
    it("never renders privateKey in evidence", async () => {
      setup();
      const verdict = buildAllReadyVerdict();
      verdict.categories.gcpCredentials = readyStep("gcpCredentials", {
        status: "active",
        serviceAccountEmail: "sa@project.iam",
        privateKey: "-----BEGIN RSA PRIVATE KEY-----SHOULD_NOT_APPEAR",
        privateKeyRedacted: true,
      });
      mockApi.mock("GET", "/readiness/consolidated", verdict);

      const user = userEvent.setup();
      render(<TenantReadinessWizard tenantId="acme" />);

      await screen.findByText("READY");

      // Expand GCP Credentials
      await user.click(screen.getByText("GCP Credentials"));

      // privateKey should NOT appear
      expect(screen.queryByText(/BEGIN RSA PRIVATE KEY/)).not.toBeInTheDocument();
      expect(screen.queryByText(/SHOULD_NOT_APPEAR/)).not.toBeInTheDocument();

      // Safe fields should appear
      expect(screen.getByText("sa@project.iam")).toBeInTheDocument();
    });

    it("never renders pat or token in evidence", async () => {
      setup();
      const verdict = buildAllReadyVerdict();
      verdict.categories.githubPat = readyStep("githubPat", {
        identityCount: 1,
        anyValid: true,
        pat: "ghp_SUPER_SECRET_TOKEN_SHOULD_NOT_APPEAR",
        token: "ghp_ANOTHER_SECRET",
        patRedacted: true,
      });
      mockApi.mock("GET", "/readiness/consolidated", verdict);

      const user = userEvent.setup();
      render(<TenantReadinessWizard tenantId="acme" />);

      await screen.findByText("READY");

      // Expand GitHub PAT
      await user.click(screen.getByText("GitHub PAT"));

      expect(screen.queryByText(/ghp_SUPER_SECRET/)).not.toBeInTheDocument();
      expect(screen.queryByText(/ghp_ANOTHER_SECRET/)).not.toBeInTheDocument();
    });

    it("never renders password or jdbcPassword in evidence", async () => {
      setup();
      const verdict = buildAllReadyVerdict();
      verdict.categories.runtimeBinding = readyStep("runtimeBinding", {
        activePrimaryCount: 1,
        password: "s3cret_password_SHOULD_NOT_APPEAR",
        jdbcPassword: "jdbc_secret_SHOULD_NOT_APPEAR",
      });
      mockApi.mock("GET", "/readiness/consolidated", verdict);

      const user = userEvent.setup();
      render(<TenantReadinessWizard tenantId="acme" />);

      await screen.findByText("READY");

      // Expand Runtime Binding
      await user.click(screen.getByText("Runtime Binding"));

      expect(screen.queryByText(/s3cret_password/)).not.toBeInTheDocument();
      expect(screen.queryByText(/jdbc_secret/)).not.toBeInTheDocument();
    });

    it("never renders credentialJson or service_account_key in evidence", async () => {
      setup();
      const verdict = buildAllReadyVerdict();
      verdict.categories.gcpCredentials = readyStep("gcpCredentials", {
        status: "active",
        credentialJson: '{"type":"service_account","private_key":"SECRET"}',
        service_account_key: "ANOTHER_SECRET_KEY",
      });
      mockApi.mock("GET", "/readiness/consolidated", verdict);

      const user = userEvent.setup();
      render(<TenantReadinessWizard tenantId="acme" />);

      await screen.findByText("READY");

      await user.click(screen.getByText("GCP Credentials"));

      expect(screen.queryByText(/service_account/)).not.toBeInTheDocument();
      expect(screen.queryByText(/ANOTHER_SECRET_KEY/)).not.toBeInTheDocument();
    });
  });

  describe("IAM manifest disclaimer", () => {
    it("shows manifest-only disclaimer when iamManifest is ready", async () => {
      setup();
      const verdict = buildAllReadyVerdict();
      verdict.categories.iamManifest = readyStep("iamManifest", {
        manifestStatus: "generated",
        iamBindingExecution: "operator_manual",
        serviceAccountCount: 3,
      });
      verdict.categories.iamManifest.ownership = {
        resourceKind: "IAM Bindings",
        createOwner: "operator",
        validateOwner: "pulse",
      };
      mockApi.mock("GET", "/readiness/consolidated", verdict);

      render(<TenantReadinessWizard tenantId="acme" />);

      await screen.findByText("READY");
      expect(screen.getByText("manifest only — no live IAM execution")).toBeInTheDocument();
    });
  });

  describe("Negative evidence: isolated green cannot make overall ready", () => {
    it("overall NOT READY even when most categories are ready", async () => {
      setup();
      const verdict = buildNotReadyVerdict();
      // 13 ready, 3 blocked — verify overall stays NOT_READY
      expect(verdict.overallStatus).toBe("blocked");
      mockApi.mock("GET", "/readiness/consolidated", verdict);

      render(<TenantReadinessWizard tenantId="acme" />);

      const badge = await screen.findByText("NOT READY");
      expect(badge).toBeInTheDocument();

      // Verify PASS badges exist for passing categories
      const passBadges = screen.getAllByText("PASS");
      expect(passBadges.length).toBe(13);

      // But overall is still NOT READY
      expect(screen.getByText("NOT READY")).toBeInTheDocument();
    });
  });

  describe("Category expand/collapse", () => {
    it("expands a blocked category to show blocker details", async () => {
      setup();
      mockApi.mock("GET", "/readiness/consolidated", buildNotReadyVerdict());

      const user = userEvent.setup();
      render(<TenantReadinessWizard tenantId="acme" />);

      await screen.findByText("NOT READY");

      // Click on GCP Configuration to expand
      await user.click(screen.getByText("GCP Configuration"));

      // Blocker code may appear in summary AND in the expanded detail
      const codeElements = screen.getAllByText("MISSING_GCP_CONFIG");
      expect(codeElements.length).toBeGreaterThanOrEqual(1);
      const msgElements = screen.getAllByText("Tenant GCP config not set");
      expect(msgElements.length).toBeGreaterThanOrEqual(1);
      expect(screen.getByText(/operator required/)).toBeInTheDocument();
    });

    it("collapses a category when clicked again", async () => {
      setup();
      mockApi.mock("GET", "/readiness/consolidated", buildNotReadyVerdict());

      const user = userEvent.setup();
      render(<TenantReadinessWizard tenantId="acme" />);

      await screen.findByText("NOT READY");

      // Click to expand — look for action text which is only in expanded detail
      await user.click(screen.getByText("GCP Configuration"));
      expect(screen.getByText(/PUT \/api\/v1\/tenants\/acme\/gcp-config/)).toBeInTheDocument();

      // Click to collapse
      await user.click(screen.getByText("GCP Configuration"));
      expect(screen.queryByText(/PUT \/api\/v1\/tenants\/acme\/gcp-config/)).not.toBeInTheDocument();
    });
  });

  describe("Refresh", () => {
    it("calls the readiness endpoint again on refresh click", async () => {
      setup();
      mockApi.mock("GET", "/readiness/consolidated", buildNotReadyVerdict());

      const user = userEvent.setup();
      render(<TenantReadinessWizard tenantId="acme" />);

      await screen.findByText("NOT READY");

      const callsBefore = mockApi.calls().filter((c) =>
        c.url.includes("/readiness/consolidated"),
      ).length;

      await user.click(screen.getByText("Refresh"));

      // Wait for the re-fetch to complete
      await screen.findByText("NOT READY");

      const callsAfter = mockApi.calls().filter((c) =>
        c.url.includes("/readiness/consolidated"),
      ).length;
      expect(callsAfter).toBe(callsBefore + 1);
    });
  });

  describe("API call shape", () => {
    it("calls the correct consolidated readiness endpoint", async () => {
      setup();
      mockApi.mock("GET", "/readiness/consolidated", buildAllReadyVerdict());

      render(<TenantReadinessWizard tenantId="acme" />);

      await screen.findByText("READY");

      const calls = mockApi.calls();
      const readinessCall = calls.find((c) =>
        c.url.includes("/readiness/consolidated"),
      );
      expect(readinessCall).toBeDefined();
      expect(readinessCall!.method).toBe("GET");
      expect(readinessCall!.url).toContain("/api/v1/tenants/acme/readiness/consolidated");
    });
  });
});
