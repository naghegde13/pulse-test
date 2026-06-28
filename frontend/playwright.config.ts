import { defineConfig, devices } from "@playwright/test";

/**
 * PKT-FINAL-8 / SU-FINAL-2 Playwright config.
 *
 * This config is owned by SU-FINAL-2 (Playwright infra + helpers). Specs
 * are added by SU-FINAL-3 / SU-FINAL-6 against a shared backend + frontend
 * dev server, so:
 *   - `workers: 1` — DB is shared across tests, no parallelism
 *   - `retries: 0` — loud failures, no retry-masked flakes
 *   - HTML + list reporters for both local debug and CI artifact capture
 *
 * The legacy `webServer` autostart block from the pre-PKT-FINAL-8 config has
 * been removed because the overnight rehearsal lane drives an already-running
 * dev server (managed outside Playwright). Set `PLAYWRIGHT_AUTO_WEB_SERVER=1`
 * to opt back in to Playwright managing `npm run dev` for ad-hoc local runs.
 */
export default defineConfig({
  testDir: "./e2e",
  timeout: 60_000,
  expect: {
    timeout: 20_000,
  },
  fullyParallel: false,
  forbidOnly: false,
  retries: 0,
  workers: 1,
  reporter: [
    ["html", { outputFolder: "playwright-report", open: "never" }],
    ["list"],
  ],
  use: {
    baseURL: process.env.PLAYWRIGHT_BASE_URL || "http://localhost:3000",
    headless: true,
    trace: "on-first-retry",
    screenshot: "only-on-failure",
    video: "retain-on-failure",
  },
  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"] },
    },
  ],
  webServer: process.env.PLAYWRIGHT_AUTO_WEB_SERVER
    ? {
        command: "npm run dev",
        url: "http://localhost:3000",
        reuseExistingServer: !process.env.CI,
        timeout: 120_000,
        stdout: "ignore",
        stderr: "pipe",
      }
    : undefined,
});
