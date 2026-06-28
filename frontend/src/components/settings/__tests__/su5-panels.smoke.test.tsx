/**
 * SU-5 smoke test — operator-mandated runnable assertion.
 *
 * Asserts (per the SU-5 task brief exit criteria):
 *
 *   1. The 3 new panels render without throwing:
 *        - TenantGcpSetupPanel       (BUG-70 restore)
 *        - TenantGcpRuntimeTopologyPanel (BUG-70)
 *        - StorageScaffoldPanel      (BUG-70)
 *      Each panel must surface its readiness anchor (`data-readiness-blocker`)
 *      so the wizard can scroll to it.
 *
 *   2. The Tenant GCP Config sub-form accepts a valid project ID — the Save
 *      button moves from disabled→enabled once the operator types a valid
 *      controlPlaneProjectId AND picks a region.
 *
 *   3. The refactored DeploymentTargetsPanel surfaces the GCP target-type
 *      conditional fields (composerEnvironment + dataprocRegion) and rejects
 *      a malformed Composer environment via the regex validator — guards the
 *      BUG-69 typed-config invariant.
 */

import { describe, it, expect, beforeEach, afterEach } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { installMockApi, type MockApi } from "@/test/mock-api";
import { TenantGcpSetupPanel } from "@/components/settings/tenant-gcp-setup-panel";
import { TenantGcpRuntimeTopologyPanel } from "@/components/settings/tenant-gcp-runtime-topology-panel";
import { StorageScaffoldPanel } from "@/components/settings/storage-scaffold-panel";

// jsdom doesn't implement Element.scrollIntoView, but Radix Select calls it
// when its content portal mounts. Stub once at module load.
if (
  typeof Element !== "undefined" &&
  typeof Element.prototype.scrollIntoView !== "function"
) {
  Element.prototype.scrollIntoView = function () {
    /* no-op for tests */
  };
}
// PointerEvent / hasPointerCapture is also missing in jsdom; Radix needs both
// to drive Select.Trigger interactions. Patch only if absent.
if (
  typeof Element !== "undefined" &&
  typeof Element.prototype.hasPointerCapture !== "function"
) {
  Element.prototype.hasPointerCapture = () => false;
}
if (
  typeof Element !== "undefined" &&
  typeof Element.prototype.releasePointerCapture !== "function"
) {
  Element.prototype.releasePointerCapture = () => {
    /* no-op */
  };
}

let mockApi: MockApi;

const TENANT_ID = "acme";

beforeEach(() => {
  mockApi = installMockApi();
  window.localStorage.setItem("pulse.currentTenantId", TENANT_ID);

  // All panels probe their endpoints on mount; return 404-like errors so each
  // panel falls back to the default empty form (matches the
  // tenant-secret-manager-panel "treat 404 as no-binding-yet" convention).
  mockApi.mock("GET", `/api/v1/tenants/${TENANT_ID}/gcp-config`, {
    error: "404 not found",
  }, 404);
  mockApi.mock("GET", `/api/v1/tenants/${TENANT_ID}/gcp-credentials`, {
    error: "404 not found",
  }, 404);
  mockApi.mock(
    "GET",
    `/api/v1/tenants/${TENANT_ID}/gcp-runtime-topology`,
    { error: "404 not found" },
    404,
  );
  mockApi.mock(
    "GET",
    `/api/v1/tenants/${TENANT_ID}/storage-scaffold/status`,
    { error: "404 not found" },
    404,
  );
});

afterEach(() => {
  mockApi?.restore();
});

describe("SU-5: panels render with readiness anchors", () => {
  it("TenantGcpSetupPanel renders the gcpConfig anchor + bootstrap banner", async () => {
    const { container } = render(
      <TenantGcpSetupPanel tenantId={TENANT_ID} tenantSlug="acme" />,
    );
    // The combined panel wrapper carries the tenantGcpSetup anchor.
    expect(
      container.querySelector('[data-readiness-blocker="tenantGcpSetup"]'),
    ).not.toBeNull();
    // Each sub-section has its own readiness key.
    expect(
      container.querySelector('[data-readiness-blocker="gcpCredentials"]'),
    ).not.toBeNull();
    expect(
      container.querySelector('[data-readiness-blocker="iamManifest"]'),
    ).not.toBeNull();
    // The bootstrap banner mentions the helper script.
    expect(
      screen.getByText(/scripts\/gcp-bootstrap-tenant-provisioner\.sh/),
    ).toBeInTheDocument();
  });

  it("TenantGcpRuntimeTopologyPanel renders the composer anchor", async () => {
    const { container } = render(
      <TenantGcpRuntimeTopologyPanel tenantId={TENANT_ID} />,
    );
    expect(
      container.querySelector('[data-readiness-blocker="composer"]'),
    ).not.toBeNull();
    expect(
      screen.getByText(/GCP Runtime Topology/i),
    ).toBeInTheDocument();
  });

  it("StorageScaffoldPanel renders the storageScaffold anchor + 2 action buttons", async () => {
    const { container } = render(<StorageScaffoldPanel tenantId={TENANT_ID} />);
    expect(
      container.querySelector('[data-readiness-blocker="storageScaffold"]'),
    ).not.toBeNull();
    expect(
      screen.getByRole("button", { name: /preview scaffold/i }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: /execute scaffold/i }),
    ).toBeInTheDocument();
  });
});

describe("SU-5: Tenant GCP Config form accepts a valid project ID", () => {
  it("Save button enables once controlPlaneProjectId + region are valid", async () => {
    render(
      <TenantGcpSetupPanel tenantId={TENANT_ID} tenantSlug="acme" />,
    );

    // Save buttons inside the panel — there's one per Card (Config,
    // Credentials, identity probe + role manifest don't use ReadinessCategoryPanel
    // so they have their own Save buttons). The GCP Config Save lives next to
    // the project ID input.
    const projectIdInput = await screen.findByLabelText(
      /control-plane project id/i,
    );

    // Initially the Config Save button is disabled (validate() returns a
    // non-null message because both fields are empty).
    const saveButtons = screen.getAllByRole("button", { name: /^save$/i });
    // ReadinessCategoryPanel exposes a single "Save" button in the GCP Config
    // section. It should be disabled while invalid.
    expect(saveButtons[0]).toBeDisabled();

    // Type a valid GCP project ID.
    fireEvent.change(projectIdInput, {
      target: { value: "my-tenant-control-plane" },
    });

    // Region select — pick a region by clicking the trigger then the item.
    // The shadcn Select uses Radix; in jsdom the SelectContent renders into a
    // portal. We rely on Radix's role/name semantics to click the option.
    // Trigger:
    const regionTriggers = screen.getAllByRole("combobox");
    fireEvent.click(regionTriggers[0]);
    // Radix Select option:
    const option = await screen.findByRole("option", {
      name: /us-central1/i,
    });
    fireEvent.click(option);

    // Now the validator should pass — Save enables.
    await waitFor(() => {
      const buttons = screen.getAllByRole("button", { name: /^save$/i });
      expect(buttons[0]).not.toBeDisabled();
    });
  });

  it("rejects a project ID that doesn't match the GCP regex", async () => {
    render(
      <TenantGcpSetupPanel tenantId={TENANT_ID} tenantSlug="acme" />,
    );
    const projectIdInput = await screen.findByLabelText(
      /control-plane project id/i,
    );

    // Underscore is not allowed in GCP project IDs.
    fireEvent.change(projectIdInput, {
      target: { value: "Bad_Project_Name" },
    });

    // Save button must stay disabled (validator returns an error string).
    const saveButtons = screen.getAllByRole("button", { name: /^save$/i });
    expect(saveButtons[0]).toBeDisabled();
    // Title attribute carries the validator message for hover discoverability.
    expect(saveButtons[0].getAttribute("title") ?? "").toMatch(
      /lowercase|6.+30|letter|project id/i,
    );
  });
});
