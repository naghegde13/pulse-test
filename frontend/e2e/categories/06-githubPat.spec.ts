import { test } from "@playwright/test";
import { bootstrap, driveGithubPat } from "./_shared";

test("06 — githubPat (isolated)", async ({ page }) => {
  await bootstrap(page);
  await driveGithubPat(page);
});
