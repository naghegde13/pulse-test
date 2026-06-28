import { test } from "@playwright/test";
import { bootstrap, driveComposer } from "./_shared";

test("10 — composer (isolated)", async ({ page }) => {
  await bootstrap(page);
  await driveComposer(page);
});
