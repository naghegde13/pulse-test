import { readFileSync } from "node:fs";
import type { Page } from "@playwright/test";

/**
 * Inject a synthetic PULSE auth token into localStorage before any page
 * navigation runs. This must be called BEFORE `page.goto(...)` because the
 * frontend's `lib/api.ts` reads `pulse_token` from localStorage on first
 * request, and React state hooks may snapshot the value at mount.
 *
 * Backend auth is disabled in dev (`pulse.auth.enabled=false`), so any
 * non-empty string satisfies the Bearer header path. When auth is enabled
 * (e.g. in a future hardened CI lane), pass a real JWT.
 *
 * Retained from the legacy suite (login/dag-designer/producer-onboarding/
 * ebcdic-discovery specs still import it).
 */
export async function signIn(
  page: Page,
  token: string = "playwright-dev-token",
): Promise<void> {
  await page.addInitScript((t) => {
    window.localStorage.setItem("pulse_token", t);
  }, token);
}

/**
 * PKT-FINAL-8 / SU-FINAL-2 — register a GitHub Personal Access Token via the
 * settings page's PAT identity panel.
 *
 * When `pat` is omitted, the token is read from `/tmp/pulse-overnight-pat`
 * (the file written by the rehearsal bootstrap). This avoids embedding any
 * secret material into spec source or environment variables.
 *
 * Assumes the caller has already navigated to `/settings` and that the PAT
 * panel is rendered. If not on /settings, the helper navigates there first.
 */
export async function registerPat(page: Page, pat?: string): Promise<void> {
  const token = (pat ?? readFileSync("/tmp/pulse-overnight-pat", "utf8")).trim();
  if (!token) {
    throw new Error(
      "registerPat: empty PAT (provide a value or write /tmp/pulse-overnight-pat)",
    );
  }
  if (!page.url().includes("/settings")) {
    await page.goto("/settings");
  }
  const tokenInput = page.locator("#pat-token");
  await tokenInput.waitFor({ state: "visible", timeout: 15_000 });
  await tokenInput.fill(token);
  // The panel renders "Register" when no identity exists, or "Rotate" when
  // one is already present. Either click writes the token to GSM.
  const action = page
    .getByRole("button", { name: /^(Register|Rotate)$/ })
    .first();
  await action.click();
  // Wait for the busy state to clear (button label returns to idle text).
  await page
    .getByRole("button", { name: /^(Register|Rotate|Revoke)$/ })
    .first()
    .waitFor({ state: "visible", timeout: 20_000 });
}

/**
 * Actor object surfaced by GET /api/v1/auth/me. Mirrors the relevant subset
 * of the backend `User` shape — kept loose because the helper only needs to
 * confirm identity for assertion contexts.
 */
export interface CurrentActor {
  id?: string;
  email?: string;
  displayName?: string;
  role?: string;
  permissions?: string[];
  [key: string]: unknown;
}

/**
 * Returns the actor object reported by `/api/v1/auth/me`, using the page's
 * Bearer token (already injected by `signIn`). Throws on non-2xx so test
 * assertions fail loudly.
 */
export async function getCurrentActor(page: Page): Promise<CurrentActor> {
  const token = await page.evaluate(
    () => window.localStorage.getItem("pulse_token") ?? "",
  );
  const res = await page.request.get(`/api/v1/auth/me`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
  if (!res.ok()) {
    throw new Error(
      `getCurrentActor: /api/v1/auth/me ${res.status()} ${res.statusText()}`,
    );
  }
  return (await res.json()) as CurrentActor;
}
