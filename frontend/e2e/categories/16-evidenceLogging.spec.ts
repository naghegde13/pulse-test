import { test } from "@playwright/test";
import { bootstrap, driveEvidenceLogging } from "./_shared";

test("16 — evidenceLogging (isolated)", async ({ page }) => {
  await bootstrap(page);
  await driveEvidenceLogging(page);
});
