import { test } from "@playwright/test";
import { bootstrap, driveDeploymentTarget } from "./_shared";

test("15 — deploymentTarget (isolated)", async ({ page }) => {
  await bootstrap(page);
  await driveDeploymentTarget(page);
});
