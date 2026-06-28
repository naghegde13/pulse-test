import { test } from "@playwright/test";
import { bootstrap, driveDomainScaffold } from "./_shared";

test("09 — domainScaffold (isolated)", async ({ page }) => {
  await bootstrap(page);
  await driveDomainScaffold(page);
});
