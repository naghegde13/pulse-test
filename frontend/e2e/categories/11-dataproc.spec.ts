import { test } from "@playwright/test";
import { bootstrap, driveDataproc } from "./_shared";

test("11 — dataproc (isolated)", async ({ page }) => {
  await bootstrap(page);
  await driveDataproc(page);
});
