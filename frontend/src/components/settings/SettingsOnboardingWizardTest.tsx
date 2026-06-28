/**
 * SU-6 / BUG-59: Re-fetch path assertion for the OnboardingWizard <->
 * GitHubPatIdentityPanel seam.
 *
 * One sub-assertion (BUG-59): the panel invokes its onIdentityChanged
 * callback after a successful register/rotate/revoke, with the masked
 * identity payload. The parent (settings/page.tsx) uses this signal to
 * re-derive identityStatus instead of relying on a useEffect that only
 * runs at mount. Re-using the parent wiring is owned by the Integrator;
 * here we assert the contract the parent depends on.
 */

import { describe, expect, it, afterEach, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { GitHubPatIdentityPanel } from "./github-pat-identity-panel";
import { installMockApi, type MockApi } from "@/test/mock-api";

const NEW_IDENTITY = {
  id: "uid-1",
  provider: "GITHUB",
  credentialType: "PAT",
  credentialReferenceMasked: "secret://pulse-acme-user-…/github-pat",
  githubUsername: "acmedev",
  authorName: "ACME Dev",
  authorEmail: "dev@acme.test",
  scopes: "repo",
  status: "VALID",
  verifiedAt: "2026-05-26T00:00:00Z",
  lastRotatedAt: null,
  revokedAt: null,
  lastValidationError: null,
};

const REVOKED_IDENTITY = {
  ...NEW_IDENTITY,
  status: "REVOKED",
  credentialReferenceMasked: null,
  revokedAt: "2026-05-26T00:01:00Z",
};

describe("GitHubPatIdentityPanel re-fetch contract (BUG-59)", () => {
  let api: MockApi;

  afterEach(() => {
    api?.restore();
  });

  it("calls onIdentityChanged with the masked payload after a successful register", async () => {
    api = installMockApi();
    // Mount: no identity yet (404 -> panel treats as missing).
    api.mock("GET", "/api/v1/users/me/git-identity", { detail: "not found" }, 404);
    // Register POST returns the masked VALID identity.
    api.mock("POST", "/api/v1/users/me/git-identity", NEW_IDENTITY);

    const onIdentityChanged = vi.fn();
    render(<GitHubPatIdentityPanel onIdentityChanged={onIdentityChanged} />);

    // Wait for initial load (handles the 404).
    await waitFor(() => {
      expect(screen.getByLabelText(/Personal Access Token/i)).toBeInTheDocument();
    });

    const input = screen.getByLabelText(/Personal Access Token/i);
    await userEvent.type(input, "ghp_test_token_x");
    await userEvent.click(screen.getByRole("button", { name: /^Register$/i }));

    await waitFor(() => {
      expect(onIdentityChanged).toHaveBeenCalledTimes(1);
    });
    expect(onIdentityChanged).toHaveBeenCalledWith(
      expect.objectContaining({ status: "VALID", id: NEW_IDENTITY.id }),
    );
  });

  it("calls onIdentityChanged with the REVOKED payload after a successful revoke", async () => {
    api = installMockApi();
    api.mock("GET", "/api/v1/users/me/git-identity", NEW_IDENTITY);
    api.mock("DELETE", "/api/v1/users/me/git-identity", REVOKED_IDENTITY);

    const onIdentityChanged = vi.fn();
    render(<GitHubPatIdentityPanel onIdentityChanged={onIdentityChanged} />);

    await waitFor(() => {
      expect(screen.getByText(/@acmedev/i)).toBeInTheDocument();
    });

    await userEvent.click(screen.getByRole("button", { name: /^Revoke$/i }));

    await waitFor(() => {
      expect(onIdentityChanged).toHaveBeenCalledTimes(1);
    });
    expect(onIdentityChanged).toHaveBeenCalledWith(
      expect.objectContaining({ status: "REVOKED" }),
    );
  });

  it("does NOT call onIdentityChanged when a register request fails", async () => {
    api = installMockApi();
    api.mock("GET", "/api/v1/users/me/git-identity", { detail: "not found" }, 404);
    api.mock(
      "POST",
      "/api/v1/users/me/git-identity",
      { detail: "invalid token" },
      400,
    );

    const onIdentityChanged = vi.fn();
    render(<GitHubPatIdentityPanel onIdentityChanged={onIdentityChanged} />);

    const input = await screen.findByLabelText(/Personal Access Token/i);
    await userEvent.type(input, "ghp_bad_token_x");
    await userEvent.click(screen.getByRole("button", { name: /^Register$/i }));

    // Give React a tick to settle, then assert no callback.
    await waitFor(() => {
      expect(
        screen.getByText(/Failed to register PAT|invalid token/i),
      ).toBeInTheDocument();
    });
    expect(onIdentityChanged).not.toHaveBeenCalled();
  });
});
