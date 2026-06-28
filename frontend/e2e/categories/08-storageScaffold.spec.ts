import { test } from "@playwright/test";
import { bootstrap, driveStorageScaffold } from "./_shared";

test("08 — storageScaffold (isolated)", async ({ page }) => {
  await bootstrap(page);
  await driveStorageScaffold(page);
});
