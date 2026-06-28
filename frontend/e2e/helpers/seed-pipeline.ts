import type { Page, Route } from "@playwright/test";

/**
 * In-memory composition store shared across page navigations in a single
 * Playwright test. The DAG round-trip test reloads the page after editing,
 * so the mocked composition (instances + wirings) must persist across
 * navigations — `page.route()` callbacks survive a reload, but local
 * fixture data must be held in a closed-over object so subsequent GETs
 * return what the prior POSTs wrote.
 *
 * Shape mirrors {@link CompositionView} from the frontend types module.
 */
export interface MockInstance {
  id: string;
  pipelineId: string;
  versionId: string;
  blueprintId: string;
  blueprintKey: string;
  blueprintVersion: string;
  name: string;
  executionOrder: number;
  params: Record<string, unknown>;
  inputDatasets: Array<Record<string, unknown>>;
  outputDatasets: Array<Record<string, unknown>>;
  schemaStatus?: string;
  createdAt: string;
  updatedAt: string;
}

export interface MockWiring {
  id: string;
  versionId: string;
  sourceInstanceId: string;
  sourcePortName: string;
  targetInstanceId: string;
  targetPortName: string;
  createdAt: string;
  updatedAt: string;
}

export interface MockBlueprint {
  id: string;
  blueprintKey: string;
  name: string;
  description: string;
  category:
    | "INGESTION"
    | "TRANSFORM"
    | "MODELING"
    | "DATA_QUALITY"
    | "ORCHESTRATION"
    | "DESTINATION";
  version: string;
  paramsSchema: Array<Record<string, unknown>>;
  inputPorts: Array<{ name: string }>;
  outputPorts: Array<{ name: string }>;
  runtimeRequirements: Record<string, unknown>;
  deferred: boolean;
  pipelineConfig: boolean;
  status: string;
  createdAt: string;
  updatedAt: string;
}

export interface SeededPipelineFixture {
  tenantId: string;
  tenantName: string;
  tenantSlug: string;
  domainId: string;
  pipelineId: string;
  versionId: string;
  pipelineName: string;
}

export interface CompositionStore {
  instances: MockInstance[];
  wirings: MockWiring[];
}

/** Stable IDs / names used by the helper and the spec assertions. */
export const FIXTURE: SeededPipelineFixture = {
  tenantId: "tenant-acme-dag",
  tenantName: "Acme Corp",
  tenantSlug: "acme",
  domainId: "domain-acme-payments",
  pipelineId: "pl-dag-roundtrip-01",
  versionId: "ver-dag-roundtrip-01",
  pipelineName: "DAG Round-Trip Pipeline",
};

const ISO_NOW = "2026-01-01T00:00:00Z";

const BLUEPRINTS: MockBlueprint[] = [
  {
    id: "bp-file-ingestion",
    blueprintKey: "FileIngestion",
    name: "File Ingestion",
    description:
      "Stage a file source into Bronze with schema discovery and audit columns.",
    category: "INGESTION",
    version: "1.0.0",
    paramsSchema: [],
    inputPorts: [],
    outputPorts: [{ name: "bronze_output" }],
    runtimeRequirements: {},
    deferred: false,
    pipelineConfig: false,
    status: "active",
    createdAt: ISO_NOW,
    updatedAt: ISO_NOW,
  },
  {
    id: "bp-bronze-silver-clean",
    blueprintKey: "BronzeSilverClean",
    name: "Bronze to Silver Cleaning",
    description:
      "Apply cleansing rules to a bronze dataset and write the silver variant.",
    category: "TRANSFORM",
    version: "1.0.0",
    paramsSchema: [],
    inputPorts: [{ name: "bronze_input" }],
    outputPorts: [{ name: "silver_output" }],
    runtimeRequirements: {},
    deferred: false,
    pipelineConfig: false,
    status: "active",
    createdAt: ISO_NOW,
    updatedAt: ISO_NOW,
  },
];

/**
 * Wire `page.route()` handlers so the frontend's lib/api calls resolve
 * against an in-memory composition store. Every endpoint the
 * `/pipelines/{pipelineId}` page exercises during the test is mocked
 * here; anything else 404s loudly so accidental coupling shows up.
 *
 * Returns the store so the test can assert on persisted state if it
 * wants. State survives `page.reload()` because the closure outlives
 * the navigation.
 */
export async function seedMockedPipeline(
  page: Page,
  fixture: SeededPipelineFixture = FIXTURE,
): Promise<CompositionStore> {
  const store: CompositionStore = {
    instances: [],
    wirings: [],
  };

  let instanceCounter = 0;
  let wiringCounter = 0;

  const json = (route: Route, status: number, body: unknown) =>
    route.fulfill({
      status,
      contentType: "application/json",
      body: JSON.stringify(body),
    });

  // Auth: stub DATA_ENGINEER user (auth is disabled by default in dev).
  await page.route("**/api/v1/auth/me", (route) =>
    json(route, 200, {
      id: "user-playwright",
      email: "playwright@pulse.local",
      displayName: "Playwright Tester",
      role: "DATA_ENGINEER",
      tenantId: fixture.tenantId,
      permissions: ["pipeline:read", "pipeline:write"],
    }),
  );

  // Tenants list (TenantProvider hydrates from here).
  await page.route("**/api/v1/tenants", (route) =>
    json(route, 200, [
      {
        id: fixture.tenantId,
        name: fixture.tenantName,
        slug: fixture.tenantSlug,
        domains: [fixture.domainId],
      },
    ]),
  );

  // Pipeline detail.
  await page.route(
    `**/api/v1/tenants/${fixture.tenantId}/pipelines/${fixture.pipelineId}`,
    (route) =>
      json(route, 200, {
        id: fixture.pipelineId,
        tenantId: fixture.tenantId,
        name: fixture.pipelineName,
        description: "Mocked pipeline for the DAG designer round-trip spec.",
        createdBy: "user-playwright",
        activeVersionId: fixture.versionId,
        domainId: fixture.domainId,
        createdAt: ISO_NOW,
        updatedAt: ISO_NOW,
      }),
  );

  // Pipeline versions.
  await page.route(
    `**/api/v1/tenants/${fixture.tenantId}/pipelines/${fixture.pipelineId}/versions`,
    (route) =>
      json(route, 200, [
        {
          id: fixture.versionId,
          pipelineId: fixture.pipelineId,
          revision: 1,
          lifecycleStage: "ENGINEERING",
          createdBy: "user-playwright",
          createdAt: ISO_NOW,
          updatedAt: ISO_NOW,
        },
      ]),
  );

  // Domains list for the pipeline detail header.
  await page.route(
    `**/api/v1/tenants/${fixture.tenantId}/domains`,
    (route) =>
      json(route, 200, [
        {
          id: fixture.domainId,
          tenantId: fixture.tenantId,
          name: "Payments",
          createdAt: ISO_NOW,
          updatedAt: ISO_NOW,
        },
      ]),
  );

  // Blueprints — both with and without the includeDeferred query string.
  await page.route("**/api/v1/blueprints**", (route) =>
    json(route, 200, BLUEPRINTS),
  );

  // Composition GET / POST / DELETE — instances and wirings.
  await page.route(
    `**/api/v1/versions/${fixture.versionId}/composition`,
    (route) => {
      if (route.request().method() === "GET") {
        return json(route, 200, {
          instances: store.instances,
          wirings: store.wirings,
        });
      }
      return route.fulfill({ status: 405, body: "method not allowed" });
    },
  );

  await page.route(
    `**/api/v1/versions/${fixture.versionId}/composition/instances`,
    async (route) => {
      const req = route.request();
      if (req.method() === "POST") {
        const body = JSON.parse(req.postData() || "{}") as {
          blueprintKey: string;
          name: string;
          params?: Record<string, unknown>;
        };
        const bp = BLUEPRINTS.find((b) => b.blueprintKey === body.blueprintKey);
        instanceCounter += 1;
        const inst: MockInstance = {
          id: `inst-${instanceCounter}-${body.blueprintKey}`,
          pipelineId: fixture.pipelineId,
          versionId: fixture.versionId,
          blueprintId: bp?.id ?? `bp-${body.blueprintKey}`,
          blueprintKey: body.blueprintKey,
          blueprintVersion: bp?.version ?? "1.0.0",
          name: body.name,
          executionOrder: store.instances.length + 1,
          params: body.params ?? {},
          inputDatasets: [],
          outputDatasets: [],
          schemaStatus: "unknown",
          createdAt: ISO_NOW,
          updatedAt: ISO_NOW,
        };
        store.instances.push(inst);
        return json(route, 201, inst);
      }
      return route.fulfill({ status: 405, body: "method not allowed" });
    },
  );

  await page.route(
    `**/api/v1/versions/${fixture.versionId}/composition/instances/*`,
    async (route) => {
      const url = route.request().url();
      const id = url.substring(url.lastIndexOf("/") + 1);
      if (route.request().method() === "DELETE") {
        store.instances = store.instances.filter((i) => i.id !== id);
        store.wirings = store.wirings.filter(
          (w) => w.sourceInstanceId !== id && w.targetInstanceId !== id,
        );
        return route.fulfill({ status: 204, body: "" });
      }
      return route.fulfill({ status: 405, body: "method not allowed" });
    },
  );

  await page.route(
    `**/api/v1/versions/${fixture.versionId}/composition/wirings`,
    async (route) => {
      const req = route.request();
      if (req.method() === "POST") {
        const body = JSON.parse(req.postData() || "{}") as {
          sourceInstanceId: string;
          sourcePortName: string;
          targetInstanceId: string;
          targetPortName: string;
        };
        wiringCounter += 1;
        const wiring: MockWiring = {
          id: `wire-${wiringCounter}`,
          versionId: fixture.versionId,
          sourceInstanceId: body.sourceInstanceId,
          sourcePortName: body.sourcePortName,
          targetInstanceId: body.targetInstanceId,
          targetPortName: body.targetPortName,
          createdAt: ISO_NOW,
          updatedAt: ISO_NOW,
        };
        store.wirings.push(wiring);
        return json(route, 201, wiring);
      }
      return route.fulfill({ status: 405, body: "method not allowed" });
    },
  );

  await page.route(
    `**/api/v1/versions/${fixture.versionId}/composition/wirings/*`,
    async (route) => {
      const url = route.request().url();
      const id = url.substring(url.lastIndexOf("/") + 1);
      if (route.request().method() === "DELETE") {
        store.wirings = store.wirings.filter((w) => w.id !== id);
        return route.fulfill({ status: 204, body: "" });
      }
      return route.fulfill({ status: 405, body: "method not allowed" });
    },
  );

  // Endpoints the pipeline page touches but the test does not care about.
  // Stub each so the page doesn't surface a spurious error toast.
  await page.route("**/api/v1/versions/*/dq-score", (route) =>
    json(route, 200, { score: null }),
  );
  await page.route("**/api/v1/versions/*/dq-recommendations*", (route) =>
    json(route, 200, []),
  );
  await page.route("**/api/v1/versions/*/git/status*", (route) =>
    json(route, 200, { status: "clean", branch: "main" }),
  );
  await page.route("**/api/v1/versions/*/artifacts*", (route) =>
    json(route, 200, []),
  );
  await page.route("**/api/v1/versions/*/deploy*", (route) =>
    json(route, 200, []),
  );
  await page.route("**/api/v1/versions/*/orchestration*", (route) =>
    json(route, 200, null),
  );
  await page.route("**/api/v1/versions/*/dbt-assets*", (route) =>
    json(route, 200, []),
  );
  await page.route("**/api/v1/versions/*/code*", (route) =>
    json(route, 200, { files: [] }),
  );

  return store;
}
