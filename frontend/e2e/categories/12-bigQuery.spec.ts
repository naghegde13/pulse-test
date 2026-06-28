import { test } from "@playwright/test";
import { bootstrap, driveBigQuery } from "./_shared";

test("12 — bigQuery (isolated)", async ({ page }) => {
  await bootstrap(page);
  await driveBigQuery(page);
});
