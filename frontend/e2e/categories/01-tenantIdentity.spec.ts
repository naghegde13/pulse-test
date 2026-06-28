import { test } from "@playwright/test";
import { bootstrap, driveTenantIdentity } from "./_shared";

test("01 — tenantIdentity (isolated)", async ({ page }) => {
  await bootstrap(page);
  await driveTenantIdentity(page);
});
