import { test } from "@playwright/test";
import { bootstrap, driveGitScaffold } from "./_shared";

test("07 — gitScaffold (isolated)", async ({ page }) => {
  await bootstrap(page);
  await driveGitScaffold(page);
});
