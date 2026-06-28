import type { Page } from "@playwright/test";
import { captureCategoryEvidence as evidenceCapture } from "./evidence-capture";

/**
 * PKT-FINAL-8 / SU-FINAL-2 — high-level wizard interactions for the tenant
 * onboarding rehearsal. Each function is intentionally small and tolerant of
 * the UI shape (text/role queries rather than data-testids) so the helpers
 * survive incidental refactors of the settings page.
 *
 * SU-FINAL-3 fix (round-2 retry): backend API calls now use the absolute
 * backend URL (PLAYWRIGHT_BACKEND_URL or http://localhost:8080) because
 * Playwright's `baseURL` is the frontend dev server (port 3000) and Next
 * does not proxy `/api/*` to the backend in this project. Without this,
 * every `waitForCategoryGreen` poll returns a 404 HTML page from Next.
 */
export const BACKEND_URL =
  process.env.PLAYWRIGHT_BACKEND_URL || "http://localhost:8080";

/** Navigate to /settings and wait for the wizard card to render. */
export async function openSettings(page: Page): Promise<void> {
  await page.goto("/settings");
  // The page renders either "Tenant Settings" (tenant chosen) or a "Select a
  // tenant" placeholder. Either way the heading anchors the page mount.
  await page
    .getByRole("heading", { name: /Tenant Settings|Select a tenant/i })
    .waitFor({ state: "visible", timeout: 20_000 });
}

/**
 * Select a tenant in the sidebar tenant switcher. The switcher is a Radix
 * Select; we bypass the dropdown by writing `pulse.currentTenantId` directly
 * to localStorage (the same key the TenantProvider hydrates from on mount)
 * and reloading. This is the same contract the app exposes for deep links.
 */
export async function selectTenant(
  page: Page,
  tenantId: string,
): Promise<void> {
  await page.evaluate((id) => {
    window.localStorage.setItem("pulse.currentTenantId", id);
  }, tenantId);
  await page.reload();
  await page
    .getByRole("heading", { name: /Tenant Settings/i })
    .waitFor({ state: "visible", timeout: 20_000 });
}

/**
 * Fill the TenantGcpSetupPanel's "GCP Configuration" form (project ID +
 * region) and submit. Does NOT click "Initialize repository" — that lives in
 * the onboarding wizard further down the page (see `clickInitialize`).
 */
export async function fillGcpConfigForm(
  page: Page,
  opts: { project: string; region: string },
): Promise<void> {
  const projectInput = page.locator("#gcpConfig-controlPlaneProjectId");
  await projectInput.waitFor({ state: "visible", timeout: 15_000 });
  await projectInput.fill(opts.project);
  const regionTrigger = page.locator("#gcpConfig-region");
  await regionTrigger.click();
  await page
    .getByRole("option", { name: new RegExp(opts.region, "i") })
    .first()
    .click();
  // The panel's Save button lives inside the gcpConfig ReadinessCategoryPanel.
  await page.getByRole("button", { name: /^Save$/ }).first().click();
}

/**
 * Click the "Initialize repository" action that finalises the onboarding
 * wizard. The button label changes to "Initializing…" while in flight; we
 * wait for it to settle back before returning.
 */
export async function clickInitialize(page: Page): Promise<void> {
  const btn = page.getByRole("button", { name: /Initialize repository/i });
  await btn.waitFor({ state: "visible", timeout: 15_000 });
  await btn.click();
  await page
    .getByRole("button", { name: /Initialize repository|Initializing…/i })
    .waitFor({ state: "visible", timeout: 30_000 });
}

/**
 * Poll the consolidated readiness API until the given category reports
 * `status === "ready"`. We hit the API directly rather than scraping the
 * wizard DOM so the helper is robust against label/badge wording drift; the
 * wizard component reads from the same endpoint and re-renders accordingly.
 */
export async function waitForCategoryGreen(
  page: Page,
  categoryId: string,
  timeoutMs = 30_000,
): Promise<void> {
  const tenantId = await page.evaluate(
    () => window.localStorage.getItem("pulse.currentTenantId") ?? "",
  );
  if (!tenantId) {
    throw new Error("waitForCategoryGreen: no tenant selected in localStorage");
  }
  const deadline = Date.now() + timeoutMs;
  let lastStatus = "unknown";
  while (Date.now() < deadline) {
    const res = await page.request.get(
      `${BACKEND_URL}/api/v1/tenants/${tenantId}/readiness/consolidated`,
    );
    if (res.ok()) {
      const body = (await res.json()) as {
        categories?: Record<string, { status?: string }>;
      };
      lastStatus = body.categories?.[categoryId]?.status ?? "missing";
      if (lastStatus === "ready") return;
    }
    await page.waitForTimeout(1_000);
  }
  throw new Error(
    `waitForCategoryGreen: ${categoryId} not green within ${timeoutMs}ms (last status=${lastStatus})`,
  );
}

/** Delegates to evidence-capture.ts. Re-exported here as a convenience so
 *  specs only need to import from `helpers/wizard.ts`. */
export async function captureCategoryEvidence(
  page: Page,
  categoryId: string,
): Promise<string> {
  return evidenceCapture(page, categoryId);
}
