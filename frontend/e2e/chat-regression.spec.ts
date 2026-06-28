import { mkdirSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { expect, test, type Page, type Route } from "@playwright/test";
import { signIn } from "./helpers/auth";
import { HOME_LENDING_TENANT } from "./fixtures/tenant";

/**
 * PKT-FINAL-8 / SU-FINAL-6 — Chat regression Playwright spec.
 *
 * Proves that BOTH of the following bugfixes survive a real two-turn
 * tool-using chat conversation against the live backend + LLM provider:
 *
 *   - BUG-73 — ChatTools JSON-schema serialization bug that caused
 *     OpenRouter to reject the request with HTTP 400 on Turn 1.
 *
 *   - BUG-2026-05-27-CHAT-TURN2 — orphan tool message dropped on Turn 2 when
 *     the assistant's tool_call IDs didn't round-trip to the persisted
 *     message history. Fix lives in ChatService#sendMessage's
 *     `knownToolCallIds` reconciliation pass (SU-FINAL-1).
 *
 * The test drives the real UI (`/chat` route → ChatPanel → POST
 * `/api/v1/chat/sessions/{sessionId}/messages`) for the
 * `tenant-home-lending` tenant, with no mocking of the LLM. The SSE response
 * bodies for both turns are tee'd to disk so reviewers can see exactly which
 * events were emitted.
 *
 * Asserts per the §2.SU-FINAL-6 exit criteria:
 *   - Both streams complete with `event: done`.
 *   - Neither stream contains an `event: error` frame.
 *   - Neither stream surfaces the `CHAT_UPSTREAM_LLM_ERROR` envelope code.
 *   - The final UI shows a normal rendered assistant response (not the
 *     "Sorry, I encountered an error" toast / unhandled-exception state).
 *
 * Evidence captured into
 *   `docs/verification/artifacts/PKT-FINAL-8-evidence/CHAT-regression/`.
 */

const TURN1_PROMPT = "What blueprints do I have available for ingestion?";
const TURN2_PROMPT =
  "Use the postgres-to-gcs blueprint to set up an ingestion pipeline for the loans dataset";

const EVIDENCE_DIR = resolve(
  process.cwd(),
  "..",
  "docs",
  "verification",
  "artifacts",
  "PKT-FINAL-8-evidence",
  "CHAT-regression",
);

function writeEvidence(filename: string, body: string | Buffer): string {
  mkdirSync(EVIDENCE_DIR, { recursive: true });
  const path = join(EVIDENCE_DIR, filename);
  writeFileSync(path, body);
  return path;
}

/**
 * Install a route interceptor on the SSE POST endpoint that tees the full
 * response body into a string buffer keyed by sequence number. We forward
 * the response to the page so the UI continues to render normally.
 *
 * Playwright's `route.fetch()` buffers the body and returns the complete
 * payload; the UI's ReadableStream reader then consumes it as a single
 * chunk. The chat-panel.tsx SSE parser is event-boundary driven (split on
 * `\n`, dispatch on empty line) so a coalesced body parses identically to a
 * truly chunked one.
 */
async function teeChatSseRoute(
  page: Page,
  bodies: string[],
): Promise<void> {
  await page.route("**/api/v1/chat/sessions/*/messages", async (route: Route) => {
    if (route.request().method() !== "POST") {
      await route.fallback();
      return;
    }
    try {
      const upstream = await route.fetch();
      const bodyText = await upstream.text();
      bodies.push(bodyText);
      await route.fulfill({
        response: upstream,
        body: bodyText,
      });
    } catch (err) {
      // Best-effort tee: if the page is tearing down (browser closed mid-
      // stream during test cleanup) the fetch/fulfill can race. Swallow
      // those because the test body has already captured everything it
      // needs by this point; surfacing them would mask the real assertion
      // failure that triggered teardown.
      if (
        err instanceof Error &&
        /(closed|disposed|Target page)/i.test(err.message)
      ) {
        return;
      }
      throw err;
    }
  });
}

function assertSseStreamClean(label: string, body: string): void {
  expect(body, `${label}: stream must not be empty`).not.toBe("");
  expect(body, `${label}: stream must contain at least one event frame`)
    .toMatch(/event:\s*\w+/);
  // No error frames — covers both the legacy plain "Sorry..." path and the
  // BUG-2026-05-25-57 structured CHAT_UPSTREAM_LLM_ERROR envelope.
  expect(body, `${label}: stream must NOT contain event: error`)
    .not.toMatch(/event:\s*error/);
  expect(body, `${label}: stream must NOT contain CHAT_UPSTREAM_LLM_ERROR`)
    .not.toContain("CHAT_UPSTREAM_LLM_ERROR");
  // Must terminate cleanly.
  expect(body, `${label}: stream must end with event: done`)
    .toMatch(/event:\s*done/);
}

/**
 * Type a message into the chat textarea and submit it. Waits until streaming
 * completes — detected by the submit button's label flipping back from "..."
 * to "Send" (see chat-panel.tsx:541). The button is located by its form
 * `type=submit` role since the visible label changes mid-stream and a plain
 * text-name locator would unbind during the "..." phase.
 *
 * We intentionally do NOT also require the button to be enabled afterwards:
 * chat-panel.tsx disables the button whenever `!input.trim() && !attachedFile`,
 * and after a successful submit the textarea is cleared, so a disabled
 * "Send" button is the steady-state post-stream condition.
 */
async function sendChatTurn(page: Page, prompt: string): Promise<void> {
  const textarea = page.getByPlaceholder("Describe what you need...");
  await textarea.waitFor({ state: "visible", timeout: 20_000 });
  await textarea.click();
  await textarea.fill(prompt);
  // chat-panel.tsx puts the submit button inside the form with the textarea;
  // it's the only role=button with type=submit in the panel.
  const submitButton = page.locator('form button[type="submit"]');
  await expect(submitButton).toBeEnabled();
  await submitButton.click();

  // While streaming, the label flips to "..." and the button is disabled.
  // Wait for it to revert to "Send" (regardless of enabled/disabled state).
  await expect(submitButton).toHaveText(/^Send$/, { timeout: 180_000 });
}

test.describe("Chat regression — two-turn tool-using conversation", () => {
  // Both turns invoke the live LLM with tool-use, and turn 2 in particular
  // can run multiple tool-call cycles (the orphan-tool-message guard from
  // SU-FINAL-1 only exercises after the assistant has issued + closed at
  // least one tool call). Set a generous test timeout so a slow OpenRouter
  // response doesn't masquerade as a regression.
  test.setTimeout(360_000);

  test("turn 1 then turn 2 complete with no event:error", async ({ page }) => {
    // Seed dev auth token + persist tenant choice BEFORE first navigation,
    // so TenantProvider hydrates Home Lending on its initial mount rather
    // than defaulting to "Default Tenant".
    await signIn(page);
    await page.addInitScript((tenantId) => {
      window.localStorage.setItem("pulse.currentTenantId", tenantId);
    }, HOME_LENDING_TENANT.id);

    const sseBodies: string[] = [];
    await teeChatSseRoute(page, sseBodies);

    // Navigate to /chat — the page auto-opens the side panel via
    // ChatProvider.open() in a useEffect.
    await page.goto("/chat");

    // Wait for ChatPanel header to render (anchors that the side drawer
    // is open and the textarea will be reachable).
    await expect(
      page.getByText("PULSE Assistant"),
    ).toBeVisible({ timeout: 30_000 });

    // The fresh DB has no prior sessions, but the latest-session restore
    // path may have produced a stale message from a previous lane. Force a
    // brand-new session for this test so the two-turn boundary is clean.
    const newChatButton = page.getByRole("button", { name: /^New Chat$/ });
    await newChatButton.waitFor({ state: "visible", timeout: 10_000 });
    await newChatButton.click();
    // Brief settle so the resetChat() POST completes and the panel renders
    // its empty-state suggestion chips.
    await page.waitForTimeout(500);

    // -------- TURN 1 --------
    await sendChatTurn(page, TURN1_PROMPT);

    // Snapshot Turn 1 stream as soon as the interceptor has logged it.
    await expect.poll(() => sseBodies.length, { timeout: 60_000 }).toBeGreaterThanOrEqual(1);
    const turn1Body = sseBodies[0];
    writeEvidence("chat-turn1.sse", turn1Body);
    assertSseStreamClean("Turn 1", turn1Body);

    // Plan requirement — 2s settle between turns.
    await page.waitForTimeout(2_000);

    // -------- TURN 2 --------
    await sendChatTurn(page, TURN2_PROMPT);

    await expect.poll(() => sseBodies.length, { timeout: 60_000 }).toBeGreaterThanOrEqual(2);
    const turn2Body = sseBodies[1];
    writeEvidence("chat-turn2.sse", turn2Body);
    assertSseStreamClean("Turn 2", turn2Body);

    // -------- UI sanity --------
    // Both user prompts must be present, and the panel must NOT show the
    // legacy generic-error toast or the structured-error envelope text.
    await expect(page.getByText(TURN1_PROMPT, { exact: false })).toBeVisible();
    await expect(page.getByText(TURN2_PROMPT, { exact: false })).toBeVisible();
    await expect(
      page.getByText(/Sorry, I encountered an error/i),
    ).toHaveCount(0);
    await expect(
      page.getByText(/CHAT_UPSTREAM_LLM_ERROR/),
    ).toHaveCount(0);

    // Capture final UI state.
    const screenshotPath = join(EVIDENCE_DIR, "chat-final-screenshot.png");
    mkdirSync(EVIDENCE_DIR, { recursive: true });
    await page.screenshot({ path: screenshotPath, fullPage: true });
    const domHtml = await page.content();
    writeEvidence("dom-final.html", domHtml);
  });
});
