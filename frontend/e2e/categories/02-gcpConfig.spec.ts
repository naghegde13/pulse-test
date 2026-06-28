import { test } from "@playwright/test";
import { bootstrap, driveGcpConfig } from "./_shared";

test("02 — gcpConfig (isolated)", async ({ page }) => {
  await bootstrap(page);
  await driveGcpConfig(page);
});
