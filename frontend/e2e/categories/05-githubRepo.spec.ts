import { test } from "@playwright/test";
import { bootstrap, driveGithubPat, driveGithubRepo } from "./_shared";

test("05 — githubRepo (isolated, drives PAT first)", async ({ page }) => {
  await bootstrap(page);
  // Initialize repo requires identityStatus = valid. Drive PAT first so a
  // fresh fallback run is self-contained.
  await driveGithubPat(page);
  await driveGithubRepo(page);
});
