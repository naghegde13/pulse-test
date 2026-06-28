/**
 * In-memory mock backend for the producer-onboarding Playwright spec.
 *
 * The frontend talks to `http://localhost:8080` (api.ts: API_BASE), so we
 * register `page.route("http://localhost:8080/api/v1/**", ...)` and serve
 * responses from a tiny mutable state object. Because the state lives on the
 * Node side of Playwright (not the browser), it survives `page.reload()` —
 * which is the whole point of the persistence assertions.
 *
 * NOTE: We deliberately avoid trying to be a full server. We implement only
 * the verbs/paths the producers pages and the four onboarding dialogs hit.
 */

import type { Page } from "@playwright/test";
import {
  CONNECTOR_DEFINITIONS,
  FAMILY_B_CONNECTOR_DEF,
  POSTGRES_DEFINITION_METHODS,
} from "./connectors";
import { DOMAIN_FIXTURE, STUB_USER, TENANT_FIXTURE, TENANT_ID } from "./tenant";

export interface MockSor {
  id: string;
  tenantId: string;
  name: string;
  description: string | null;
  domainId: string;
  domainName: string;
  ownerId: string;
  connectorCount: number;
  datasetCount: number;
  metadata: Record<string, unknown>;
  createdAt: string;
  updatedAt: string;
}

export interface MockConnectorInstance {
  id: string;
  sorId: string;
  connectorDefinitionId: string;
  name: string;
  description?: string;
  configTemplate: Record<string, unknown>;
  enabled: boolean;
  connectorTypeName?: string;
  dockerRepository?: string;
  connectorType?: "SOURCE" | "DESTINATION";
  releaseStage?: string;
  credentialStatuses?: Record<string, string>;
  datasetCount?: number;
  createdAt: string;
  updatedAt: string;
}

export interface MockDataset {
  id: string;
  connectorInstanceId: string;
  tenantId: string;
  name: string;
  qualifiedName: string;
  description?: string;
  schemaSnapshot?: Record<string, unknown>;
  schemaFormat?: string;
  classification?: string;
  definitionType?: string;
  definitionConfig?: Record<string, unknown>;
  customSql?: string;
  sourceTables?: string[];
  status?: string;
  createdAt: string;
  updatedAt: string;
}

export interface MockCredential {
  id: string;
  connectorInstanceId: string;
  environment: string;
  connectionConfig: Record<string, unknown>;
  metadataConfig: Record<string, unknown>;
  secretRefs: Record<string, string>;
  secretMetadata: Record<string, { configured: boolean; secretReference: boolean }>;
  status: string;
  createdAt: string;
  updatedAt: string;
}

export interface MockServerState {
  sors: MockSor[];
  connectors: MockConnectorInstance[];
  credentials: MockCredential[];
  datasets: MockDataset[];
  /**
   * Counters per resource so generated ids are deterministic AND unique
   * across runs in the same browser session.
   */
  counters: { sor: number; connector: number; credential: number; dataset: number };
}

export function createMockServerState(): MockServerState {
  return {
    sors: [],
    connectors: [],
    credentials: [],
    datasets: [],
    counters: { sor: 0, connector: 0, credential: 0, dataset: 0 },
  };
}

const NOW = () => new Date("2025-05-11T12:00:00Z").toISOString();

function jsonResponse(status: number, body: unknown) {
  return {
    status,
    contentType: "application/json",
    body: JSON.stringify(body),
  };
}

/**
 * Installs page.route handlers for every backend endpoint the producer
 * onboarding flow touches. Pass the shared mutable state object so the
 * same state persists across page reloads inside one test.
 */
export async function installMockBackend(
  page: Page,
  state: MockServerState
): Promise<void> {
  await page.route("http://localhost:8080/api/v1/**", async (route) => {
    const req = route.request();
    const url = new URL(req.url());
    const method = req.method();
    const path = url.pathname;

    // --- Auth & tenant context -----------------------------------------
    if (method === "GET" && path === "/api/v1/auth/me") {
      return route.fulfill(jsonResponse(200, STUB_USER));
    }
    if (method === "GET" && path === "/api/v1/tenants") {
      return route.fulfill(jsonResponse(200, [TENANT_FIXTURE]));
    }
    if (
      method === "GET" &&
      path === `/api/v1/tenants/${TENANT_ID}/domains`
    ) {
      return route.fulfill(jsonResponse(200, [DOMAIN_FIXTURE]));
    }

    // --- Connector catalog ---------------------------------------------
    if (method === "GET" && path === "/api/v1/connectors") {
      return route.fulfill(jsonResponse(200, CONNECTOR_DEFINITIONS));
    }
    const connDefMatch = path.match(/^\/api\/v1\/connectors\/([^/]+)$/);
    if (method === "GET" && connDefMatch) {
      const def = CONNECTOR_DEFINITIONS.find((c) => c.id === connDefMatch[1]);
      if (!def) return route.fulfill(jsonResponse(404, { detail: "not found" }));
      return route.fulfill(jsonResponse(200, def));
    }
    const defMethodsMatch = path.match(
      /^\/api\/v1\/connectors\/([^/]+)\/definition-methods$/
    );
    if (method === "GET" && defMethodsMatch) {
      if (defMethodsMatch[1] === FAMILY_B_CONNECTOR_DEF.id) {
        return route.fulfill(jsonResponse(200, POSTGRES_DEFINITION_METHODS));
      }
      return route.fulfill(jsonResponse(200, []));
    }

    // --- SORs -----------------------------------------------------------
    if (
      method === "GET" &&
      path === `/api/v1/tenants/${TENANT_ID}/sors`
    ) {
      return route.fulfill(jsonResponse(200, state.sors));
    }
    if (
      method === "POST" &&
      path === `/api/v1/tenants/${TENANT_ID}/sors`
    ) {
      const body = JSON.parse(req.postData() || "{}") as {
        name?: string;
        description?: string | null;
        domainId?: string;
      };
      if (!body.name || !body.name.trim()) {
        return route.fulfill(
          jsonResponse(400, { detail: "Name is required" })
        );
      }
      state.counters.sor += 1;
      const sor: MockSor = {
        id: `sor-${state.counters.sor}`,
        tenantId: TENANT_ID,
        name: body.name.trim(),
        description: body.description ?? null,
        domainId: body.domainId ?? DOMAIN_FIXTURE.id,
        domainName: DOMAIN_FIXTURE.name,
        ownerId: STUB_USER.id,
        connectorCount: 0,
        datasetCount: 0,
        metadata: {},
        createdAt: NOW(),
        updatedAt: NOW(),
      };
      state.sors.push(sor);
      return route.fulfill(jsonResponse(201, sor));
    }
    const sorDetailMatch = path.match(
      new RegExp(`^/api/v1/tenants/${TENANT_ID}/sors/([^/]+)$`)
    );
    if (method === "GET" && sorDetailMatch) {
      const sor = state.sors.find((s) => s.id === sorDetailMatch[1]);
      if (!sor) return route.fulfill(jsonResponse(404, { detail: "not found" }));
      // Recompute counts so the badge stays accurate after reload.
      const connectorCount = state.connectors.filter(
        (c) => c.sorId === sor.id
      ).length;
      const datasetCount = state.datasets.filter((d) => {
        const c = state.connectors.find((ci) => ci.id === d.connectorInstanceId);
        return c?.sorId === sor.id;
      }).length;
      return route.fulfill(
        jsonResponse(200, { ...sor, connectorCount, datasetCount })
      );
    }

    // --- Connector instances on a SOR ----------------------------------
    const sorConnsMatch = path.match(/^\/api\/v1\/sors\/([^/]+)\/connectors$/);
    if (method === "GET" && sorConnsMatch) {
      const sorId = sorConnsMatch[1];
      const conns = state.connectors
        .filter((c) => c.sorId === sorId)
        .map((c) => ({
          ...c,
          credentialStatuses: buildCredentialStatusMap(state, c.id),
        }));
      return route.fulfill(jsonResponse(200, conns));
    }
    if (method === "POST" && sorConnsMatch) {
      const sorId = sorConnsMatch[1];
      const body = JSON.parse(req.postData() || "{}") as {
        connectorDefinitionId?: string;
        name?: string;
        enabled?: boolean;
        configTemplate?: Record<string, unknown>;
      };
      const def = CONNECTOR_DEFINITIONS.find(
        (d) => d.id === body.connectorDefinitionId
      );
      state.counters.connector += 1;
      const ci: MockConnectorInstance = {
        id: `ci-${state.counters.connector}`,
        sorId,
        connectorDefinitionId: body.connectorDefinitionId ?? "",
        name: (body.name ?? "Connector").trim(),
        configTemplate: body.configTemplate ?? {},
        enabled: body.enabled ?? true,
        connectorTypeName: def?.name,
        dockerRepository: def?.dockerRepository,
        connectorType: def?.connectorType,
        releaseStage: def?.releaseStage,
        credentialStatuses: {},
        datasetCount: 0,
        createdAt: NOW(),
        updatedAt: NOW(),
      };
      state.connectors.push(ci);
      return route.fulfill(jsonResponse(201, ci));
    }

    // --- Per-connector credentials ------------------------------------
    const credListMatch = path.match(
      /^\/api\/v1\/connector-instances\/([^/]+)\/credentials$/
    );
    if (method === "GET" && credListMatch) {
      const ciId = credListMatch[1];
      const list = state.credentials.filter(
        (c) => c.connectorInstanceId === ciId
      );
      return route.fulfill(jsonResponse(200, list));
    }
    const credUpsertMatch = path.match(
      /^\/api\/v1\/connector-instances\/([^/]+)\/credentials\/([^/]+)$/
    );
    if (method === "PUT" && credUpsertMatch) {
      const ciId = credUpsertMatch[1];
      const env = credUpsertMatch[2];
      const body = JSON.parse(req.postData() || "{}") as {
        metadata?: Record<string, unknown>;
        secretRefs?: Record<string, string>;
        secretValues?: Record<string, string>;
      };
      const existingIdx = state.credentials.findIndex(
        (c) => c.connectorInstanceId === ciId && c.environment === env
      );
      const secretMetadata: Record<
        string,
        { configured: boolean; secretReference: boolean }
      > = {};
      for (const k of Object.keys(body.secretRefs ?? {})) {
        secretMetadata[k] = { configured: true, secretReference: true };
      }
      for (const k of Object.keys(body.secretValues ?? {})) {
        secretMetadata[k] = { configured: true, secretReference: false };
      }
      const credential: MockCredential = {
        id:
          existingIdx >= 0
            ? state.credentials[existingIdx].id
            : `cred-${++state.counters.credential}`,
        connectorInstanceId: ciId,
        environment: env,
        connectionConfig: { ...(body.metadata ?? {}) },
        metadataConfig: body.metadata ?? {},
        secretRefs: body.secretRefs ?? {},
        secretMetadata,
        status: "VALID",
        createdAt:
          existingIdx >= 0
            ? state.credentials[existingIdx].createdAt
            : NOW(),
        updatedAt: NOW(),
      };
      if (existingIdx >= 0) {
        state.credentials[existingIdx] = credential;
      } else {
        state.credentials.push(credential);
      }
      return route.fulfill(jsonResponse(200, credential));
    }
    const credSkipMatch = path.match(
      /^\/api\/v1\/connector-instances\/([^/]+)\/credentials\/([^/]+)\/skip$/
    );
    if (method === "POST" && credSkipMatch) {
      const ciId = credSkipMatch[1];
      const env = credSkipMatch[2];
      const credential: MockCredential = {
        id: `cred-${++state.counters.credential}`,
        connectorInstanceId: ciId,
        environment: env,
        connectionConfig: {},
        metadataConfig: {},
        secretRefs: {},
        secretMetadata: {},
        status: "SKIPPED",
        createdAt: NOW(),
        updatedAt: NOW(),
      };
      state.credentials.push(credential);
      return route.fulfill(jsonResponse(200, credential));
    }

    // --- Datasets ------------------------------------------------------
    if (method === "GET" && path === `/api/v1/tenants/${TENANT_ID}/datasets`) {
      const sorId = url.searchParams.get("sorId");
      const ciId = url.searchParams.get("connectorInstanceId");
      let datasets = state.datasets;
      if (sorId) {
        const connectorIds = state.connectors
          .filter((c) => c.sorId === sorId)
          .map((c) => c.id);
        datasets = datasets.filter((d) =>
          connectorIds.includes(d.connectorInstanceId)
        );
      }
      if (ciId) {
        datasets = datasets.filter((d) => d.connectorInstanceId === ciId);
      }
      return route.fulfill(jsonResponse(200, datasets));
    }
    const ciDatasetCreateMatch = path.match(
      /^\/api\/v1\/connector-instances\/([^/]+)\/datasets$/
    );
    if (method === "POST" && ciDatasetCreateMatch) {
      const ciId = ciDatasetCreateMatch[1];
      const body = JSON.parse(req.postData() || "{}") as Record<string, unknown>;
      state.counters.dataset += 1;
      const ds: MockDataset = {
        id: `ds-${state.counters.dataset}`,
        connectorInstanceId: ciId,
        tenantId: TENANT_ID,
        name: String(body.name ?? `dataset-${state.counters.dataset}`),
        qualifiedName: `${TENANT_ID}.${String(body.name ?? "dataset")}`,
        description: (body.description as string) || undefined,
        schemaSnapshot: body.schemaSnapshot as Record<string, unknown>,
        schemaFormat: body.schemaFormat as string,
        classification: body.classification as string,
        definitionType: body.definitionType as string,
        definitionConfig: body.definitionConfig as Record<string, unknown>,
        customSql: body.customSql as string,
        sourceTables: body.sourceTables as string[],
        status: "SCHEMA_DEFINED",
        createdAt: NOW(),
        updatedAt: NOW(),
      };
      state.datasets.push(ds);
      return route.fulfill(jsonResponse(201, ds));
    }

    // --- Catch-all: empty list for unknown GETs, 404 otherwise ---------
    if (method === "GET") {
      return route.fulfill(jsonResponse(200, []));
    }
    return route.fulfill(
      jsonResponse(404, { detail: `mock missing for ${method} ${path}` })
    );
  });
}

function buildCredentialStatusMap(
  state: MockServerState,
  ciId: string
): Record<string, string> {
  const map: Record<string, string> = {};
  for (const c of state.credentials) {
    if (c.connectorInstanceId === ciId) {
      map[c.environment] = c.status;
    }
  }
  return map;
}
