import type { Page } from "@playwright/test";
import { openSettings, selectTenant, waitForCategoryGreen, captureCategoryEvidence, BACKEND_URL } from "../helpers/wizard";
import { signIn, registerPat } from "../helpers/auth";
import { HOME_LENDING_TENANT } from "../fixtures/tenant";

/**
 * PKT-FINAL-8 / SU-FINAL-3 — Q8 fallback: shared driving primitives so each
 * per-category isolated spec is a thin wrapper. Re-using these from a
 * single source keeps the isolated specs idempotent against partial
 * progress (each call sniffs the current readiness state first and skips
 * the drive if already GREEN).
 */
export const TENANT_ID = HOME_LENDING_TENANT.id;
export const GCP_PROJECT = HOME_LENDING_TENANT.gcpProject;
export const GCP_REGION = HOME_LENDING_TENANT.gcpRegion;
export const TENANT_SA_EMAIL =
  "pulse-home-lending@pulse-proof-04261847.iam.gserviceaccount.com";
export const REPO_URL = "https://github.com/zadam2008/pulse-acme-lending.git";
export const COMPOSER_ENV =
  `projects/${GCP_PROJECT}/locations/${GCP_REGION}/environments/pulse-home-lending-composer`;

export async function categoryStatus(
  page: Page,
  categoryKey: string,
): Promise<string> {
  const res = await page.request.get(
    `${BACKEND_URL}/api/v1/tenants/${TENANT_ID}/readiness/consolidated`,
  );
  if (!res.ok()) return `http-${res.status()}`;
  const body = (await res.json()) as {
    categories?: Record<string, { status?: string }>;
  };
  return body.categories?.[categoryKey]?.status ?? "missing";
}

export async function scrollToPanel(page: Page, anchorId: string): Promise<void> {
  await page
    .evaluate((id) => {
      const el = document.getElementById(id);
      if (el) el.scrollIntoView({ behavior: "instant" as ScrollBehavior, block: "start" });
    }, anchorId)
    .catch(() => {});
}

export async function bootstrap(page: Page): Promise<void> {
  await signIn(page);
  await openSettings(page);
  await selectTenant(page, TENANT_ID);
}

export async function driveTenantIdentity(page: Page): Promise<void> {
  await waitForCategoryGreen(page, "tenantIdentity", 15_000);
  await captureCategoryEvidence(page, "tenantIdentity");
}

export async function driveGcpConfig(page: Page): Promise<void> {
  if ((await categoryStatus(page, "gcpConfig")) !== "ready") {
    await scrollToPanel(page, "gcpConfig");
    await page
      .locator("#gcpConfig-controlPlaneProjectId")
      .waitFor({ state: "visible", timeout: 15_000 });
    await page.locator("#gcpConfig-controlPlaneProjectId").fill(GCP_PROJECT);
    await page.locator("#gcpConfig-region").click();
    await page
      .getByRole("option", { name: new RegExp(GCP_REGION, "i") })
      .first()
      .click();
    await page
      .locator("#gcpConfig")
      .getByRole("button", { name: /^Save$/ })
      .click();
  }
  await waitForCategoryGreen(page, "gcpConfig", 20_000);
  await scrollToPanel(page, "gcpConfig");
  await captureCategoryEvidence(page, "gcpConfig");
}

export async function driveGcpCredentials(page: Page): Promise<void> {
  if ((await categoryStatus(page, "gcpCredentials")) !== "ready") {
    await scrollToPanel(page, "gcpCredentials");
    const section = page.locator("#gcpCredentials");
    await section.getByRole("button", { name: /^Impersonation$/ }).click();
    const emailInput = page.locator(`#gcp-impersonate-email-${TENANT_ID}`);
    await emailInput.waitFor({ state: "visible", timeout: 10_000 });
    await emailInput.fill(TENANT_SA_EMAIL);
    await section.getByRole("button", { name: /Save Credentials/ }).click();
  }
  await waitForCategoryGreen(page, "gcpCredentials", 20_000);
  await scrollToPanel(page, "gcpCredentials");
  await captureCategoryEvidence(page, "gcpCredentials");
}

export async function driveGithubPat(page: Page): Promise<void> {
  if ((await categoryStatus(page, "githubPat")) !== "ready") {
    await registerPat(page);
  }
  await waitForCategoryGreen(page, "githubPat", 20_000);
  await scrollToPanel(page, "github-pat-identity");
  await captureCategoryEvidence(page, "githubPat");
}

export async function driveGithubRepo(page: Page): Promise<void> {
  if ((await categoryStatus(page, "githubRepo")) !== "ready") {
    const repoInput = page.locator("#repo-url");
    if (await repoInput.isVisible().catch(() => false)) {
      await repoInput.fill(REPO_URL);
      await page.getByRole("button", { name: /^Next$/ }).click();
      await page.getByRole("button", { name: /^Next$/ }).click();
      const initButton = page.getByRole("button", {
        name: /^Initialize repository$/,
      });
      await initButton.waitFor({ state: "visible", timeout: 15_000 });
      await initButton.click();
    }
  }
  await waitForCategoryGreen(page, "githubRepo", 60_000);
  await captureCategoryEvidence(page, "githubRepo");
}

export async function driveGitScaffold(page: Page): Promise<void> {
  await waitForCategoryGreen(page, "gitScaffold", 60_000);
  await captureCategoryEvidence(page, "gitScaffold");
}

export async function driveStorageScaffold(page: Page): Promise<void> {
  if ((await categoryStatus(page, "storageScaffold")) !== "ready") {
    await scrollToPanel(page, "storageScaffold");
    const section = page.locator("#storageScaffold");
    // Skip Preview — see wizard.spec.ts comment for the known wire mismatch.
    await section.getByRole("button", { name: /Execute scaffold/ }).click();
    await page
      .getByText(/Storage scaffold complete\.|finished with .* failure/, {
        exact: false,
      })
      .first()
      .waitFor({ state: "visible", timeout: 180_000 })
      .catch(() => {});
  }
  await waitForCategoryGreen(page, "storageScaffold", 60_000);
  await scrollToPanel(page, "storageScaffold");
  await captureCategoryEvidence(page, "storageScaffold");
}

export async function driveDomainScaffold(page: Page): Promise<void> {
  await waitForCategoryGreen(page, "domainScaffold", 60_000);
  await captureCategoryEvidence(page, "domainScaffold");
}

export async function driveComposer(page: Page): Promise<void> {
  if ((await categoryStatus(page, "composer")) !== "ready") {
    await scrollToPanel(page, "composer");
    const section = page.locator("#composer");
    await page
      .locator("#topology-composerEnv")
      .waitFor({ state: "visible", timeout: 10_000 });
    await page.locator("#topology-composerEnv").fill(COMPOSER_ENV);
    await page.locator("#topology-composerRegion").click();
    await page
      .getByRole("option", { name: new RegExp(`^${GCP_REGION}$`, "i") })
      .first()
      .click();
    await page.locator("#topology-dataprocRegion").click();
    await page
      .getByRole("option", { name: new RegExp(`^${GCP_REGION}$`, "i") })
      .first()
      .click();
    await page.locator("#topology-bqLocation").click();
    await page.getByRole("option", { name: /^US \(multi-region\)$/ }).first().click();
    await page
      .locator("#topology-bronze")
      .fill("pulse_home_lending_bronze_dev");
    await page
      .locator("#topology-silver")
      .fill("pulse_home_lending_silver_dev");
    await page.locator("#topology-gold").fill("pulse_home_lending_gold_dev");
    await page
      .locator("#topology-evidence")
      .fill("pulse_home_lending_evidence_dev");
    await page.locator("#topology-sm").fill(GCP_PROJECT);
    await page.locator("#topology-controlPlaneSa").fill(TENANT_SA_EMAIL);
    await page
      .locator("#topology-evBucket")
      .fill(`pulse-home-lending-evidence-dev`);
    await page.locator("#topology-evPrefix").fill("logs/pulse/");
    await section.getByRole("button", { name: /^Save$/ }).click();
  }
  await waitForCategoryGreen(page, "composer", 30_000);
  await scrollToPanel(page, "composer");
  await captureCategoryEvidence(page, "composer");
}

export async function driveDataproc(page: Page): Promise<void> {
  await waitForCategoryGreen(page, "dataproc", 20_000);
  await captureCategoryEvidence(page, "dataproc");
}

export async function driveBigQuery(page: Page): Promise<void> {
  await waitForCategoryGreen(page, "bigQuery", 20_000);
  await captureCategoryEvidence(page, "bigQuery");
}

export async function driveIamManifest(page: Page): Promise<void> {
  await waitForCategoryGreen(page, "iamManifest", 20_000);
  await captureCategoryEvidence(page, "iamManifest");
}

export async function driveSecretManager(page: Page): Promise<void> {
  if ((await categoryStatus(page, "secretManager")) !== "ready") {
    await scrollToPanel(page, "secretManager");
    const section = page.locator("#secretManager");
    await page.locator("#secretManagerMode").click();
    await page
      .getByRole("option", { name: /TENANT_GCP_SECRET_MANAGER/ })
      .click();
    await page
      .locator("#gsmProjectId")
      .waitFor({ state: "visible", timeout: 10_000 });
    await page.locator("#gsmProjectId").fill(GCP_PROJECT);
    await section.getByRole("button", { name: /^Save$/ }).click();
  }
  await waitForCategoryGreen(page, "secretManager", 20_000);
  await scrollToPanel(page, "secretManager");
  await captureCategoryEvidence(page, "secretManager");
}

export async function driveRuntimeBinding(page: Page): Promise<void> {
  if ((await categoryStatus(page, "runtimeBinding")) !== "ready") {
    await scrollToPanel(page, "runtimeBindings");
    const section = page.locator("#runtimeBindings");
    const existing = await page.request.get(
      `${BACKEND_URL}/api/v1/runtime-bindings`,
    );
    const haveExistingPrimary = existing.ok()
      ? ((await existing.json()) as Array<{
          primary: boolean;
          active: boolean;
        }>).some((b) => b.primary && b.active)
      : false;
    if (!haveExistingPrimary) {
      await section.getByRole("button", { name: /\+ Add binding/ }).click();
      await section
        .locator("input[placeholder='files-bucket']")
        .last()
        .fill(`pulse-home-lending-dev-files`);
      await section
        .locator("input[placeholder='lake-bucket']")
        .last()
        .fill(`pulse-home-lending-dev-lake`);
      await section
        .locator("input[placeholder='ops-bucket (optional)']")
        .last()
        .fill(`pulse-home-lending-dev-ops`);
      await section.getByRole("button", { name: /^Save$/ }).last().click();
    }
    const bindingsRes = await page.request.get(
      `${BACKEND_URL}/api/v1/runtime-bindings`,
    );
    if (bindingsRes.ok()) {
      const bindings = (await bindingsRes.json()) as Array<{
        id: string;
        primary: boolean;
        active: boolean;
      }>;
      const primary = bindings.find((b) => b.primary && b.active);
      if (primary) {
        await page.request.post(
          `${BACKEND_URL}/api/v1/runtime-bindings/${primary.id}/validate`,
          {},
        );
      }
    }
  }
  await waitForCategoryGreen(page, "runtimeBinding", 30_000);
  await scrollToPanel(page, "runtimeBindings");
  await captureCategoryEvidence(page, "runtimeBinding");
}

export async function driveDeploymentTarget(page: Page): Promise<void> {
  if ((await categoryStatus(page, "deploymentTarget")) !== "ready") {
    await scrollToPanel(page, "deploymentTargets");
    const section = page.locator("#deploymentTargets");
    await section.getByRole("button", { name: /^\+ Create target/ }).click();
    await page.locator("#dt-name").fill(`home-lending-dev-composer`);
    await page.locator("#dt-type").click();
    await page
      .getByRole("option", { name: /GCP_COMPOSER_DATAPROC/ })
      .first()
      .click();
    await page.locator("#dt-composerEnv").fill(COMPOSER_ENV);
    await page.locator("#dt-dataprocRegion").click();
    await page
      .getByRole("option", { name: new RegExp(`^${GCP_REGION}$`, "i") })
      .first()
      .click();
    await section.getByRole("button", { name: /Create target/ }).click();
  }
  await waitForCategoryGreen(page, "deploymentTarget", 30_000);
  await scrollToPanel(page, "deploymentTargets");
  await captureCategoryEvidence(page, "deploymentTarget");
}

export async function driveEvidenceLogging(page: Page): Promise<void> {
  await waitForCategoryGreen(page, "evidenceLogging", 20_000);
  await captureCategoryEvidence(page, "evidenceLogging");
}
