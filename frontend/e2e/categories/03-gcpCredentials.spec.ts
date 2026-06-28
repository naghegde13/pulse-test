import { test } from "@playwright/test";
import { bootstrap, driveGcpCredentials } from "./_shared";

test("03 — gcpCredentials (isolated)", async ({ page }) => {
  await bootstrap(page);
  await driveGcpCredentials(page);
});
