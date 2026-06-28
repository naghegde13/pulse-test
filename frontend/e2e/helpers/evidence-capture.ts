import { mkdirSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";
import type { Page } from "@playwright/test";
import { HOME_LENDING_TENANT } from "../fixtures/tenant";

/**
 * Absolute backend URL — see `helpers/wizard.ts` comment for the rationale.
 * Playwright `baseURL` points at the frontend dev server, but the readiness
 * / topology readback endpoints live on the backend (port 8080) and Next
 * does not proxy.
 */
const BACKEND_URL =
  process.env.PLAYWRIGHT_BACKEND_URL || "http://localhost:8080";

/**
 * PKT-FINAL-8 / SU-FINAL-3 — evidence capture for each of the 16 readiness
 * categories.
 *
 * RECONCILIATION (SU-FINAL-3 / SU-FINAL-2 DEVIATIONS §6): the §2.SU-FINAL-2
 * brief enumerated a brief-IDs set that diverged from the authoritative
 * `READINESS_CATEGORY_KEYS` declared in `frontend/src/types/index.ts` and the
 * backend's `ConsolidatedTenantReadinessService` (which emits these exact
 * keys in `result.put(...)`). Backend keys win — they are the source of
 * truth the wizard renders and the readiness API returns. SU-FINAL-3 updated
 * this bucket map to the 16 authoritative keys, in the order they appear in
 * `READINESS_CATEGORY_KEYS`. This change is documented in SU-FINAL-3
 * DEVIATIONS.md.
 *
 * Any unknown categoryId fails loudly — we do NOT silently re-bucket into a
 * generic folder.
 */
const CATEGORY_BUCKETS: Record<string, string> = {
  tenantIdentity: "01-tenantIdentity",
  gcpConfig: "02-gcpConfig",
  gcpCredentials: "03-gcpCredentials",
  iamManifest: "04-iamManifest",
  githubRepo: "05-githubRepo",
  githubPat: "06-githubPat",
  gitScaffold: "07-gitScaffold",
  storageScaffold: "08-storageScaffold",
  domainScaffold: "09-domainScaffold",
  composer: "10-composer",
  dataproc: "11-dataproc",
  bigQuery: "12-bigQuery",
  secretManager: "13-secretManager",
  runtimeBinding: "14-runtimeBinding",
  deploymentTarget: "15-deploymentTarget",
  evidenceLogging: "16-evidenceLogging",
};

/**
 * Captures four artifacts for `categoryId` into
 *   `docs/verification/artifacts/PKT-FINAL-8-evidence/<NN>-<categoryId>/`:
 *
 *   - `screenshot.png` — full-page screenshot of the current viewport
 *   - `readiness.json` — consolidated readiness verdict for the tenant
 *   - `gcp-readback.json` — GCP runtime topology readback for the tenant
 *   - `dom-snapshot.html` — `page.content()` at capture time
 *
 * Returns the absolute directory path that was written.
 */
export async function captureCategoryEvidence(
  page: Page,
  categoryId: string,
): Promise<string> {
  const bucket = CATEGORY_BUCKETS[categoryId];
  if (!bucket) {
    throw new Error(
      `captureCategoryEvidence: unknown categoryId="${categoryId}" — refusing to silently re-bucket. ` +
        `Known IDs: ${Object.keys(CATEGORY_BUCKETS).join(", ")}`,
    );
  }

  // Resolve the artifacts root relative to the frontend cwd. Playwright runs
  // with cwd = frontend/, so we step one level up to reach docs/.
  const baseDir = resolve(
    process.cwd(),
    "..",
    "docs",
    "verification",
    "artifacts",
    "PKT-FINAL-8-evidence",
    bucket,
  );
  mkdirSync(baseDir, { recursive: true });

  // 1. Screenshot.
  await page.screenshot({
    path: join(baseDir, "screenshot.png"),
    fullPage: true,
  });

  // 2. Readiness verdict.
  const tenantId = HOME_LENDING_TENANT.id;
  const readinessRes = await page.request.get(
    `${BACKEND_URL}/api/v1/tenants/${tenantId}/readiness/consolidated`,
  );
  writeFileSync(
    join(baseDir, "readiness.json"),
    JSON.stringify(
      {
        status: readinessRes.status(),
        ok: readinessRes.ok(),
        body: readinessRes.ok() ? await readinessRes.json() : await readinessRes.text(),
      },
      null,
      2,
    ),
  );

  // 3. GCP runtime topology readback.
  const topoRes = await page.request.get(
    `${BACKEND_URL}/api/v1/tenants/${tenantId}/gcp-runtime-topology`,
  );
  writeFileSync(
    join(baseDir, "gcp-readback.json"),
    JSON.stringify(
      {
        status: topoRes.status(),
        ok: topoRes.ok(),
        body: topoRes.ok() ? await topoRes.json() : await topoRes.text(),
      },
      null,
      2,
    ),
  );

  // 4. DOM snapshot.
  writeFileSync(join(baseDir, "dom-snapshot.html"), await page.content());

  return baseDir;
}
