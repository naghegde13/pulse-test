import { test } from "@playwright/test";
import { bootstrap, driveSecretManager } from "./_shared";

test("13 — secretManager (isolated)", async ({ page }) => {
  await bootstrap(page);
  await driveSecretManager(page);
});
