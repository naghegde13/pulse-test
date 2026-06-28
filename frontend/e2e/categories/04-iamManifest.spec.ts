import { test } from "@playwright/test";
import { bootstrap, driveIamManifest } from "./_shared";

test("04 — iamManifest (isolated)", async ({ page }) => {
  await bootstrap(page);
  await driveIamManifest(page);
});
