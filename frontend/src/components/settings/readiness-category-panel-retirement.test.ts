/**
 * SU-7 / BUG-64 — Forward-compatibility floor for the
 * `ReadinessCategoryPanel<T>` STUB at
 * `frontend/src/components/settings/readiness-category-panel.tsx`.
 *
 * Goal: pin the public prop surface of the stub so that when Agent G's
 * real implementation replaces the file (see Retirement Criteria docblock
 * in readiness-category-panel.tsx), every existing callsite remains
 * assignable. This file deliberately performs *type-level* assertions —
 * if the stub's public surface ever narrows in a breaking way, or if a
 * callsite drifts away from the contract, this test fails to compile
 * (and therefore fails the `npm run build` + `npm run test:unit` gates).
 *
 * Why a runtime test wrapper too? Vitest needs at least one `it(...)`
 * block to surface compilation failures as a *failing* test rather than
 * silently skipping. The runtime assertions are intentionally trivial;
 * the load-bearing checks are the `satisfies` clauses below.
 */

import { describe, expect, it } from "vitest";
import type { ReactNode } from "react";
import type {
  ReadinessCategoryPanelProps,
  ReadinessCategoryPanelStatus,
} from "./readiness-category-panel";
import type {
  ReadinessCategoryKey,
  SecretManagerMode,
  TenantSecretManagerForm,
  TenantSecretManagerReadback,
} from "@/types";

/* ── Floor #1: stub's exported status values ─────────────────────────────── */

/**
 * The stub today supports four status values; Agent G's real panel must
 * accept at least these four (it may add more — superset is fine). We
 * enumerate them as constants so the type-level `satisfies` keeps the
 * stub honest.
 */
const REQUIRED_STATUSES = [
  "unknown",
  "pass",
  "fail",
  "blocked",
] as const satisfies ReadonlyArray<ReadinessCategoryPanelStatus>;

/* ── Floor #2: every readiness category key must be a legal `categoryKey` ── */

/**
 * Every key in `READINESS_CATEGORY_KEYS` must remain assignable to
 * `ReadinessCategoryPanelProps<unknown>["categoryKey"]`. If Agent G's
 * implementation tightens the categoryKey constraint (e.g. narrows to a
 * subset), this assignment fails to compile.
 */
type CategoryKeyFloor = ReadinessCategoryPanelProps<unknown>["categoryKey"];
const _categoryKeyIsReadinessCategoryKey: CategoryKeyFloor extends ReadinessCategoryKey
  ? true
  : false = true;
const _readinessCategoryKeyAssignableToCategoryKey: ReadinessCategoryKey extends CategoryKeyFloor
  ? true
  : false = true;
void _categoryKeyIsReadinessCategoryKey;
void _readinessCategoryKeyAssignableToCategoryKey;

/* ── Floor #3: callsite shape — TenantSecretManagerPanel ──────────────────
 *
 * Mirrors the props passed at
 * `frontend/src/components/settings/tenant-secret-manager-panel.tsx`
 * (currently line ~175). If the stub's prop shape ever drops `value`,
 * `onChange`, `onSubmit`, `validate`, `renderFields`, `readback`, or
 * tightens any of their types, the `satisfies` below fails to compile.
 */
const tenantSecretManagerCallsiteProps = {
  categoryKey: "secretManager" as const,
  title: "Tenant Secret Manager binding",
  description: "Controls how PULSE persists tenant secrets." as ReactNode,
  status: "pass" as ReadinessCategoryPanelStatus,
  value: {
    mode: "LOCAL_STUB" as SecretManagerMode,
    gsmProjectId: "",
    secretNamePrefix: "",
  } satisfies TenantSecretManagerForm,
  onChange: (_patch: Partial<TenantSecretManagerForm>) => undefined,
  validate: (_v: TenantSecretManagerForm) => null,
  onSubmit: async (_v: TenantSecretManagerForm) => undefined,
  renderFields: (_args: {
    value: TenantSecretManagerForm;
    invalid: boolean;
  }) => null as ReactNode,
  readback: null as ReactNode,
} satisfies ReadinessCategoryPanelProps<TenantSecretManagerForm>;

/* ── Floor #4: a representative readback fixture for the same callsite ──── */

const tenantSecretManagerReadbackFixture = {
  mode: "TENANT_GCP_SECRET_MANAGER",
  gsmProjectId: "pulse-tenant-prod-gsm",
  secretNamePrefix: "pulse-tenant-foo",
  computedStatus: "PASS",
  updatedAt: "2026-05-26T12:00:00Z",
} satisfies TenantSecretManagerReadback;

/* ── Floor #5: status-pill input domain still covers all four cases ──────
 *
 * Authors of Agent G's real panel are free to add more states, but the
 * stub's four must remain assignable. This guards against an accidental
 * `Pick<>` narrowing.
 */
const _statusFloor: ReadinessCategoryPanelStatus = "blocked";
void _statusFloor;

/* ── Runtime wrapper: makes vitest surface compile failures as test fails ─ */

describe("ReadinessCategoryPanel stub — retirement forward-compat (BUG-64)", () => {
  it("preserves every required status value for the stub contract", () => {
    expect(REQUIRED_STATUSES).toEqual(["unknown", "pass", "fail", "blocked"]);
  });

  it("accepts the TenantSecretManagerPanel callsite prop shape", () => {
    // The load-bearing check is the `satisfies` clause above; this runtime
    // assertion exists so the suite has at least one `expect`. If the
    // surface narrows, compilation fails before this line is reached.
    expect(tenantSecretManagerCallsiteProps.categoryKey).toBe("secretManager");
    expect(typeof tenantSecretManagerCallsiteProps.onSubmit).toBe("function");
    expect(typeof tenantSecretManagerCallsiteProps.onChange).toBe("function");
    expect(typeof tenantSecretManagerCallsiteProps.renderFields).toBe(
      "function",
    );
  });

  it("accepts a full TenantSecretManagerReadback fixture", () => {
    expect(tenantSecretManagerReadbackFixture.mode).toBe(
      "TENANT_GCP_SECRET_MANAGER",
    );
    expect(tenantSecretManagerReadbackFixture.computedStatus).toBe("PASS");
  });
});
