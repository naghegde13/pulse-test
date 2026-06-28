import { test } from "@playwright/test";
import { bootstrap, driveRuntimeBinding } from "./_shared";

test("14 — runtimeBinding (isolated)", async ({ page }) => {
  await bootstrap(page);
  await driveRuntimeBinding(page);
});
