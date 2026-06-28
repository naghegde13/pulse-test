"use client";

/**
 * Deployment Targets panel (BUG-2026-05-25-55, refactored by SU-5 BUG-69).
 *
 * Backs the missing UI for the `deploymentTarget` readiness category. The
 * tenant-scoped CRUD surface was shipped by Agent C:
 *
 *   GET    /api/v1/tenants/{tenantId}/deployment-targets
 *   POST   /api/v1/tenants/{tenantId}/deployment-targets
 *   PATCH  /api/v1/tenants/{tenantId}/deployment-targets/{targetId}
 *   DELETE /api/v1/tenants/{tenantId}/deployment-targets/{targetId} (soft-delete)
 *
 * SU-5 BUG-69 changes (see docs/verification/artifacts/PKT-CAND-2026-05-26-69):
 *   - Removed `endpointUrl` from the create + edit forms — backend never reads
 *     it (confirmed in PKT-69 SECTION 1 Q3). All target adapters consume the
 *     typed `config: Record<string,unknown>` map instead.
 *   - Added discriminated-union config per `targetType`, modelled after
 *     `tenant-secret-manager-panel.tsx` (PKT-69 SECTION 1 Q4 recommendation):
 *       * GCP_COMPOSER_DATAPROC → composerEnvironment (regex-validated to
 *         `projects/{p}/locations/{r}/environments/{e}`) + dataprocRegion
 *         (dropdown of known GCP regions).
 *       * DPC_AIRFLOW_OPENSHIFT_SPARK → objectStoreEndpoint + dpcAirflowEndpoint
 *         + dpcSparkEndpoint (free-form URLs).
 *       * LOCAL_MATERIALIZATION → no type-specific fields.
 *   - POST body now sends `config: {...}` rather than top-level `endpointUrl`.
 *
 * UI shape:
 *   - List table with name / environment / targetType / status / lastUsedAt.
 *   - Inline-edit-in-row Edit → PATCH (mutable fields only: name + config;
 *     environment + targetType are immutable per Agent C's contract).
 *   - "Disable" → DELETE (soft-delete; Agent C preserves the row + audit trail).
 *
 * Readiness anchor: `id="deploymentTargets"` +
 * `data-readiness-blocker="deploymentTargets"`.
 *
 * Operator decisions honored:
 *   - Round 1 form pattern: hardcoded env enum, existing PULSE form pattern,
 *     inline + banner errors, toast + re-fetch + autoscroll on save.
 *   - Round 2 inline-edit-in-row (no modal).
 */

import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ChangeEvent,
} from "react";
import { toast } from "sonner";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { api } from "@/lib/api";
import { useRuntimeAuthority } from "@/contexts/runtime-authority-context";
import type { DeploymentTarget, DeploymentTargetCreateRequest } from "@/types";

const ENV_OPTIONS = ["dev", "integration", "uat", "prod"] as const;
type EnvOption = (typeof ENV_OPTIONS)[number];

/* ── Type-specific config (BUG-69) ────────────────────────────────────────── */

/**
 * Composer environment resource-name pattern. Matches the GCP API surface
 * (`projects/{project}/locations/{region}/environments/{env}`); rejecting
 * other shapes prevents the silent runtime-deploy failures documented in
 * PKT-CAND-2026-05-26-69 SECTION 1 Q5.
 */
const COMPOSER_ENV_RE =
  /^projects\/[^/]+\/locations\/[^/]+\/environments\/[^/]+$/;

/**
 * Curated Dataproc Serverless regions. Free-text was the source of typos
 * causing region-quota errors at deploy time; dropdown closes that gap.
 */
const DATAPROC_REGIONS = [
  "us-central1",
  "us-east1",
  "us-east4",
  "us-west1",
  "us-west2",
  "europe-west1",
  "europe-west2",
  "europe-west4",
  "asia-east1",
  "asia-northeast1",
  "asia-southeast1",
] as const;

/* ── Create form draft ────────────────────────────────────────────────────── */

/**
 * Discriminated-union-ish draft: every target-type's optional fields live on
 * the same flat object. `extractConfigForTargetType()` picks the relevant
 * subset when building the POST body so the backend stays type-safe.
 */
interface CreateDraft {
  name: string;
  environment: EnvOption;
  targetType: string;
  // GCP_COMPOSER_DATAPROC
  gcpProject: string;
  gcsBucket: string;
  composerEnvironment: string;
  dataprocRegion: string;
  dataprocCluster: string;
  // DPC_AIRFLOW_OPENSHIFT_SPARK
  objectStoreEndpoint: string;
  objectStoreBucket: string;
  dpcAirflowEndpoint: string;
  dpcSparkEndpoint: string;
  sparkApp: string;
  // Package/runtime artifact pointers shared by remote adapters.
  mainPyFile: string;
  dagFilePaths: string;
  pythonFiles: string;
  jarFiles: string;
  tokenReference: string;
}

const EMPTY_CREATE: CreateDraft = {
  name: "",
  environment: "dev",
  targetType: "",
  gcpProject: "",
  gcsBucket: "",
  composerEnvironment: "",
  dataprocRegion: "",
  dataprocCluster: "",
  objectStoreEndpoint: "",
  objectStoreBucket: "",
  dpcAirflowEndpoint: "",
  dpcSparkEndpoint: "",
  sparkApp: "",
  mainPyFile: "",
  dagFilePaths: "",
  pythonFiles: "",
  jarFiles: "",
  tokenReference: "",
};

function csvList(value: string): string[] {
  return value
    .split(",")
    .map((item) => item.trim())
    .filter(Boolean);
}

function extractConfigForTargetType(d: CreateDraft): Record<string, unknown> {
  switch (d.targetType) {
    case "GCP_COMPOSER_DATAPROC":
      return {
        "gcpProject": d.gcpProject.trim(),
        "gcsBucket": d.gcsBucket.trim(),
        "composerEnvironment": d.composerEnvironment.trim(),
        "dataprocRegion": d.dataprocRegion.trim(),
        "dataprocCluster": d.dataprocCluster.trim(),
        "mainPyFile": d.mainPyFile.trim(),
        "dagFilePaths": csvList(d.dagFilePaths),
        "pythonFiles": csvList(d.pythonFiles),
        "jarFiles": csvList(d.jarFiles),
        "tokenReference": d.tokenReference.trim(),
      };
    case "DPC_AIRFLOW_OPENSHIFT_SPARK":
      return {
        "objectStoreEndpoint": d.objectStoreEndpoint.trim(),
        "objectStoreBucket": d.objectStoreBucket.trim(),
        "dpcAirflowEndpoint": d.dpcAirflowEndpoint.trim(),
        "dpcSparkEndpoint": d.dpcSparkEndpoint.trim(),
        "sparkApp": d.sparkApp.trim(),
        "mainPyFile": d.mainPyFile.trim(),
        "dagFilePaths": csvList(d.dagFilePaths),
        "pythonFiles": csvList(d.pythonFiles),
        "jarFiles": csvList(d.jarFiles),
        "tokenReference": d.tokenReference.trim(),
      };
    case "LOCAL_MATERIALIZATION":
      return {};
    default:
      return {};
  }
}

function validateCreateForTargetType(
  d: CreateDraft,
  allowedTypes: string[],
): string | null {
  if (!d.name.trim()) return "Name is required.";
  if (!d.targetType) return "Target type is required.";
  if (allowedTypes.length > 0 && !allowedTypes.includes(d.targetType)) {
    return `Target type "${d.targetType}" is not allowed by the active runtime persona.`;
  }
  // Type-specific validation
  switch (d.targetType) {
    case "GCP_COMPOSER_DATAPROC":
      if (!d.composerEnvironment.trim()) {
        return "Composer environment is required.";
      }
      if (!COMPOSER_ENV_RE.test(d.composerEnvironment.trim())) {
        return "Composer environment must match format: projects/{project}/locations/{region}/environments/{env}";
      }
      if (!d.dataprocRegion.trim()) {
        return "Dataproc region is required.";
      }
      break;
    case "DPC_AIRFLOW_OPENSHIFT_SPARK":
      if (!d.objectStoreEndpoint.trim()) {
        return "Object store endpoint is required.";
      }
      if (!d.dpcAirflowEndpoint.trim()) {
        return "DPC Airflow endpoint is required.";
      }
      if (!d.dpcSparkEndpoint.trim()) {
        return "DPC Spark endpoint is required.";
      }
      break;
    case "LOCAL_MATERIALIZATION":
      // No type-specific fields.
      break;
  }
  return null;
}

/* ── Edit row draft (PATCH name only; config not yet editable in row) ────── */

interface EditDraft {
  name: string;
}

function validateEdit(d: EditDraft): string | null {
  if (!d.name.trim()) return "Name is required.";
  return null;
}

/* ── Panel ────────────────────────────────────────────────────────────────── */

export function DeploymentTargetsPanel({ tenantId }: { tenantId: string }) {
  const { authority, loading: authorityLoading } = useRuntimeAuthority();
  const [targets, setTargets] = useState<DeploymentTarget[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [reloadToken, setReloadToken] = useState(0);

  const [createOpen, setCreateOpen] = useState(false);
  const [createDraft, setCreateDraft] = useState<CreateDraft>(EMPTY_CREATE);
  const [createBusy, setCreateBusy] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);

  const [editingId, setEditingId] = useState<string | null>(null);
  const [editDraft, setEditDraft] = useState<EditDraft>({
    name: "",
  });

  const sectionRef = useRef<HTMLElement | null>(null);

  const allowedTargetTypes = useMemo(
    () => authority?.allowedTargetTypes ?? [],
    [authority],
  );

  const reload = useCallback(() => setReloadToken((n) => n + 1), []);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      setLoading(true);
      try {
        const data = await api.get<DeploymentTarget[]>(
          `/api/v1/tenants/${tenantId}/deployment-targets`,
        );
        if (!cancelled) {
          setTargets(data);
          setError(null);
        }
      } catch (e) {
        if (!cancelled)
          setError(e instanceof Error ? e.message : "Failed to load");
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [tenantId, reloadToken]);

  /* ── Create ──────────────────────────────────────────────────────────── */

  const submitCreate = useCallback(async () => {
    const v = validateCreateForTargetType(createDraft, allowedTargetTypes);
    if (v) {
      setCreateError(v);
      return;
    }
    setCreateBusy(true);
    setCreateError(null);
    try {
      const body: DeploymentTargetCreateRequest = {
        name: createDraft.name.trim(),
        environment: createDraft.environment,
        targetType: createDraft.targetType,
        config: extractConfigForTargetType(createDraft),
      };
      await api.post(`/api/v1/tenants/${tenantId}/deployment-targets`, body);
      toast.success("Deployment target created.");
      setCreateDraft(EMPTY_CREATE);
      setCreateOpen(false);
      reload();
      sectionRef.current?.scrollIntoView({
        behavior: "smooth",
        block: "start",
      });
    } catch (e) {
      const msg = e instanceof Error ? e.message : "Create failed";
      setCreateError(msg);
      toast.error(msg);
    } finally {
      setCreateBusy(false);
    }
  }, [tenantId, createDraft, allowedTargetTypes, reload]);

  /* ── Edit / Disable per row ──────────────────────────────────────────── */

  const startEdit = useCallback((t: DeploymentTarget) => {
    setEditDraft({ name: t.name });
    setEditingId(t.id);
  }, []);

  const cancelEdit = useCallback(() => {
    setEditingId(null);
    setEditDraft({ name: "" });
  }, []);

  return (
    <section
      id="deploymentTargets"
      data-readiness-blocker="deploymentTargets"
      ref={sectionRef}
      className="scroll-mt-6"
    >
      <Card>
        <CardHeader>
          <CardTitle>Deployment Targets</CardTitle>
          <CardDescription>
            Per-tenant deployment targets PULSE uses to deploy packages to
            Airflow / Composer. Each target is environment-scoped and bound to
            a target type allowed by the active runtime persona.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-3">
          {error && (
            <p
              role="alert"
              className="rounded-md border border-destructive/30 bg-destructive/10 text-destructive text-xs px-3 py-2"
            >
              {error}
            </p>
          )}

          {/* Create form (collapsible) ──────────────────────────────────── */}
          {createOpen ? (
            <div className="rounded-md border border-blue-500/30 bg-blue-500/5 p-3 space-y-3 text-xs">
              <div className="flex items-center justify-between">
                <h3 className="text-sm font-medium">Create deployment target</h3>
                <Button
                  size="sm"
                  variant="outline"
                  className="h-6 text-[11px]"
                  onClick={() => {
                    setCreateOpen(false);
                    setCreateDraft(EMPTY_CREATE);
                    setCreateError(null);
                  }}
                  disabled={createBusy}
                >
                  Cancel
                </Button>
              </div>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <div className="space-y-1">
                  <Label htmlFor="dt-name">
                    Name <span className="text-destructive">*</span>
                  </Label>
                  <Input
                    id="dt-name"
                    value={createDraft.name}
                    onChange={(e: ChangeEvent<HTMLInputElement>) =>
                      setCreateDraft({ ...createDraft, name: e.target.value })
                    }
                    className="h-8 text-xs"
                    placeholder="acme-dev-composer"
                    aria-required
                  />
                </div>
                <div className="space-y-1">
                  <Label htmlFor="dt-env">
                    Environment <span className="text-destructive">*</span>
                  </Label>
                  <Select
                    value={createDraft.environment}
                    onValueChange={(v) =>
                      setCreateDraft({
                        ...createDraft,
                        environment: v as EnvOption,
                      })
                    }
                  >
                    <SelectTrigger id="dt-env" className="h-8 text-xs">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      {ENV_OPTIONS.map((env) => (
                        <SelectItem key={env} value={env}>
                          {env}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>
                <div className="space-y-1">
                  <Label htmlFor="dt-type">
                    Target type <span className="text-destructive">*</span>
                  </Label>
                  <Select
                    value={createDraft.targetType}
                    onValueChange={(v) =>
                      setCreateDraft({ ...createDraft, targetType: v })
                    }
                    disabled={authorityLoading || allowedTargetTypes.length === 0}
                  >
                    <SelectTrigger id="dt-type" className="h-8 text-xs">
                      <SelectValue
                        placeholder={
                          authorityLoading
                            ? "Loading…"
                            : allowedTargetTypes.length === 0
                              ? "No types from active persona"
                              : "Pick a target type"
                        }
                      />
                    </SelectTrigger>
                    <SelectContent>
                      {allowedTargetTypes.map((t) => (
                        <SelectItem key={t} value={t}>
                          {t}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                  <p className="text-[10px] text-muted-foreground">
                    Restricted to the active persona&apos;s{" "}
                    <span className="font-mono">allowedTargetTypes</span>.
                  </p>
                </div>
              </div>

              {/* Type-specific config (BUG-69) ─────────────────────────── */}
              <TypeSpecificCreateFields
                draft={createDraft}
                onDraftChange={setCreateDraft}
              />

              {createError && (
                <p
                  role="alert"
                  className="rounded-md border border-destructive/30 bg-destructive/10 text-destructive text-[11px] px-2 py-1.5"
                >
                  {createError}
                </p>
              )}
              <div className="flex justify-end gap-2">
                <Button
                  size="sm"
                  onClick={submitCreate}
                  disabled={
                    createBusy ||
                    Boolean(
                      validateCreateForTargetType(
                        createDraft,
                        allowedTargetTypes,
                      ),
                    )
                  }
                  title={
                    validateCreateForTargetType(
                      createDraft,
                      allowedTargetTypes,
                    ) ?? undefined
                  }
                >
                  {createBusy ? "Creating…" : "Create target"}
                </Button>
              </div>
            </div>
          ) : (
            <div className="flex justify-end">
              <Button
                size="sm"
                variant="outline"
                onClick={() => {
                  setCreateDraft(EMPTY_CREATE);
                  setCreateError(null);
                  setCreateOpen(true);
                }}
                disabled={editingId !== null}
                title={
                  editingId !== null
                    ? "Finish editing the current row first."
                    : "Create a new deployment target."
                }
              >
                + Create target
              </Button>
            </div>
          )}

          {/* List ────────────────────────────────────────────────────── */}
          {loading ? (
            <p className="text-sm text-muted-foreground">
              Loading deployment targets…
            </p>
          ) : (
            <div className="overflow-x-auto rounded-md border">
              <table className="w-full text-xs">
                <thead className="bg-muted/40 text-muted-foreground">
                  <tr>
                    <th className="px-2 py-1.5 text-left font-medium">name</th>
                    <th className="px-2 py-1.5 text-left font-medium">env</th>
                    <th className="px-2 py-1.5 text-left font-medium">type</th>
                    <th className="px-2 py-1.5 text-left font-medium">
                      status
                    </th>
                    <th className="px-2 py-1.5 text-left font-medium">
                      last used
                    </th>
                    <th className="px-2 py-1.5 text-right font-medium">
                      actions
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {targets.length === 0 && (
                    <tr>
                      <td
                        colSpan={6}
                        className="px-3 py-3 text-center text-muted-foreground"
                      >
                        No deployment targets configured for this tenant.
                      </td>
                    </tr>
                  )}
                  {targets.map((t) =>
                    editingId === t.id ? (
                      <TargetEditRow
                        key={t.id}
                        tenantId={tenantId}
                        target={t}
                        draft={editDraft}
                        onDraftChange={setEditDraft}
                        onCancel={cancelEdit}
                        onSaved={() => {
                          toast.success("Deployment target updated.");
                          cancelEdit();
                          reload();
                        }}
                      />
                    ) : (
                      <TargetReadRow
                        key={t.id}
                        tenantId={tenantId}
                        target={t}
                        disabledByOther={editingId !== null || createOpen}
                        onEdit={() => startEdit(t)}
                        onDisabled={() => {
                          toast.success("Deployment target disabled.");
                          reload();
                        }}
                      />
                    ),
                  )}
                </tbody>
              </table>
            </div>
          )}
        </CardContent>
      </Card>
    </section>
  );
}

/* ── Read row ─────────────────────────────────────────────────────────────── */

function TargetReadRow({
  tenantId,
  target,
  disabledByOther,
  onEdit,
  onDisabled,
}: {
  tenantId: string;
  target: DeploymentTarget;
  disabledByOther: boolean;
  onEdit: () => void;
  onDisabled: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [busyError, setBusyError] = useState<string | null>(null);

  const disable = useCallback(async () => {
    setBusy(true);
    setBusyError(null);
    try {
      await api.delete(
        `/api/v1/tenants/${tenantId}/deployment-targets/${target.id}`,
      );
      onDisabled();
    } catch (e) {
      const msg = e instanceof Error ? e.message : "Disable failed";
      setBusyError(msg);
      toast.error(msg);
    } finally {
      setBusy(false);
    }
  }, [tenantId, target.id, onDisabled]);

  /* DeploymentTarget DTO lacks an explicit `lastUsedAt` today; fall back to
   * updatedAt with the operator-friendly label. */
  const lastUsed = target.updatedAt ?? target.createdAt;

  return (
    <tr className="border-t">
      <td className="px-2 py-1.5 font-mono break-all">{target.name}</td>
      <td className="px-2 py-1.5 font-mono">{target.environment}</td>
      <td className="px-2 py-1.5 font-mono">{target.targetType}</td>
      <td className="px-2 py-1.5">
        <Badge
          variant="outline"
          className={
            target.enabled
              ? "text-[10px] bg-green-500/15 text-green-700 dark:text-green-300 border-green-500/30"
              : "text-[10px] bg-zinc-500/15 text-zinc-600 dark:text-zinc-400 border-zinc-500/30"
          }
        >
          {target.enabled ? "enabled" : "disabled"}
        </Badge>
      </td>
      <td className="px-2 py-1.5 text-muted-foreground">
        {lastUsed ? new Date(lastUsed).toLocaleString() : "—"}
      </td>
      <td className="px-2 py-1.5">
        <div className="flex items-center justify-end gap-1">
          {busyError && (
            <span className="text-destructive text-[10px]" title={busyError}>
              ⚠
            </span>
          )}
          <Button
            size="sm"
            variant="outline"
            className="h-6 text-[11px]"
            onClick={onEdit}
            disabled={busy || disabledByOther}
          >
            Edit
          </Button>
          <Button
            size="sm"
            variant="outline"
            className="h-6 text-[11px] text-muted-foreground"
            onClick={disable}
            disabled={busy || !target.enabled || disabledByOther}
            title={
              !target.enabled
                ? "Already disabled."
                : "Soft-delete (sets enabled=false). Preserves audit trail."
            }
          >
            Disable
          </Button>
        </div>
      </td>
    </tr>
  );
}

/* ── Edit row (inline) ────────────────────────────────────────────────────── */

function TargetEditRow({
  tenantId,
  target,
  draft,
  onDraftChange,
  onCancel,
  onSaved,
}: {
  tenantId: string;
  target: DeploymentTarget;
  draft: EditDraft;
  onDraftChange: (d: EditDraft) => void;
  onCancel: () => void;
  onSaved: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [busyError, setBusyError] = useState<string | null>(null);

  const validationError = validateEdit(draft);

  const save = useCallback(async () => {
    if (validationError) {
      setBusyError(validationError);
      return;
    }
    setBusy(true);
    setBusyError(null);
    try {
      // BUG-69: PATCH only the mutable fields the backend still reads.
      // `endpointUrl` was removed because no adapter consumes it; renaming a
      // target keeps the existing config map intact server-side.
      await api.patch(
        `/api/v1/tenants/${tenantId}/deployment-targets/${target.id}`,
        {
          name: draft.name.trim(),
        },
      );
      onSaved();
    } catch (e) {
      const msg = e instanceof Error ? e.message : "Save failed";
      setBusyError(msg);
      toast.error(msg);
    } finally {
      setBusy(false);
    }
  }, [tenantId, target.id, draft, onSaved, validationError]);

  return (
    <tr className="border-t bg-blue-500/5">
      <td className="px-2 py-1.5">
        <Input
          value={draft.name}
          onChange={(e) => onDraftChange({ ...draft, name: e.target.value })}
          className="h-7 text-xs font-mono"
          placeholder="target name"
          aria-invalid={Boolean(validationError)}
        />
      </td>
      <td className="px-2 py-1.5 font-mono text-muted-foreground">
        {target.environment}
        <span className="block text-[10px]">immutable</span>
      </td>
      <td className="px-2 py-1.5 font-mono text-muted-foreground">
        {target.targetType}
        <span className="block text-[10px]">immutable</span>
      </td>
      <td className="px-2 py-1.5 text-[10px] text-muted-foreground" colSpan={2}>
        <span className="italic">
          config is immutable from this row — use the create form to replace
          target.
        </span>
        {(busyError || validationError) && (
          <p className="mt-1 text-destructive text-[11px]">
            {busyError ?? validationError}
          </p>
        )}
      </td>
      <td className="px-2 py-1.5">
        <div className="flex items-center justify-end gap-1">
          <Button
            size="sm"
            className="h-6 text-[11px]"
            onClick={save}
            disabled={busy || Boolean(validationError)}
            title={validationError ?? undefined}
          >
            {busy ? "Saving…" : "Save"}
          </Button>
          <Button
            size="sm"
            variant="outline"
            className="h-6 text-[11px]"
            onClick={onCancel}
            disabled={busy}
          >
            Cancel
          </Button>
        </div>
      </td>
    </tr>
  );
}

/* ── Type-specific create fields (BUG-69) ─────────────────────────────────── */

/**
 * Renders the right field set for the currently selected target type. Each
 * target type's fields live on the flat `CreateDraft` and are only consumed
 * when `extractConfigForTargetType()` builds the POST body.
 */
function TypeSpecificCreateFields({
  draft,
  onDraftChange,
}: {
  draft: CreateDraft;
  onDraftChange: (next: CreateDraft) => void;
}) {
  if (!draft.targetType) {
    return (
      <p className="text-[11px] text-muted-foreground italic">
        Pick a target type above to see its required configuration fields.
      </p>
    );
  }

  if (draft.targetType === "LOCAL_MATERIALIZATION") {
    return (
      <p className="text-[11px] text-muted-foreground italic">
        LOCAL_MATERIALIZATION targets need no additional configuration.
      </p>
    );
  }

  if (draft.targetType === "GCP_COMPOSER_DATAPROC") {
    const composerInvalid =
      draft.composerEnvironment.length > 0 &&
      !COMPOSER_ENV_RE.test(draft.composerEnvironment.trim());
    return (
      <fieldset className="space-y-3 rounded-md border bg-card p-3">
        <legend className="px-1 text-[11px] font-medium uppercase tracking-wide text-muted-foreground">
          GCP Composer + Dataproc config
        </legend>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
          <div className="space-y-1">
            <Label htmlFor="dt-gcpProject" className="text-xs">
              GCP project
            </Label>
            <Input
              id="dt-gcpProject"
              value={draft.gcpProject}
              onChange={(e: ChangeEvent<HTMLInputElement>) =>
                onDraftChange({
                  ...draft,
                  gcpProject: e.target.value,
                })
              }
              className="h-8 text-xs font-mono"
              placeholder="pulse-dev"
            />
          </div>
          <div className="space-y-1">
            <Label htmlFor="dt-gcsBucket" className="text-xs">
              GCS package bucket
            </Label>
            <Input
              id="dt-gcsBucket"
              value={draft.gcsBucket}
              onChange={(e: ChangeEvent<HTMLInputElement>) =>
                onDraftChange({
                  ...draft,
                  gcsBucket: e.target.value,
                })
              }
              className="h-8 text-xs font-mono"
              placeholder="pulse-dev-pulse-packages"
            />
          </div>
        </div>
        <div className="space-y-1">
          <Label htmlFor="dt-composerEnv" className="text-xs">
            Composer environment <span className="text-destructive">*</span>
          </Label>
          <Input
            id="dt-composerEnv"
            value={draft.composerEnvironment}
            onChange={(e: ChangeEvent<HTMLInputElement>) =>
              onDraftChange({
                ...draft,
                composerEnvironment: e.target.value,
              })
            }
            className="h-8 text-xs font-mono"
            placeholder="projects/my-project/locations/us-central1/environments/pulse-composer"
            aria-required
            aria-invalid={composerInvalid}
          />
          <p className="text-[10px] text-muted-foreground">
            Format:{" "}
            <code className="font-mono">
              projects/{"{project}"}/locations/{"{region}"}/environments/
              {"{env}"}
            </code>
          </p>
        </div>
        <div className="space-y-1">
          <Label htmlFor="dt-dataprocRegion" className="text-xs">
            Dataproc region <span className="text-destructive">*</span>
          </Label>
          <Select
            value={draft.dataprocRegion}
            onValueChange={(v) =>
              onDraftChange({ ...draft, dataprocRegion: v })
            }
          >
            <SelectTrigger id="dt-dataprocRegion" className="h-8 text-xs">
              <SelectValue placeholder="Pick a region" />
            </SelectTrigger>
            <SelectContent>
              {DATAPROC_REGIONS.map((r) => (
                <SelectItem key={r} value={r}>
                  {r}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        <div className="space-y-1">
          <Label htmlFor="dt-dataprocCluster" className="text-xs">
            Dataproc cluster
          </Label>
          <Input
            id="dt-dataprocCluster"
            value={draft.dataprocCluster}
            onChange={(e: ChangeEvent<HTMLInputElement>) =>
              onDraftChange({
                ...draft,
                dataprocCluster: e.target.value,
              })
            }
            className="h-8 text-xs font-mono"
            placeholder="pulse-dataproc"
          />
        </div>
        <SharedPackageCreateFields draft={draft} onDraftChange={onDraftChange} />
      </fieldset>
    );
  }

  if (draft.targetType === "DPC_AIRFLOW_OPENSHIFT_SPARK") {
    return (
      <fieldset className="space-y-3 rounded-md border bg-card p-3">
        <legend className="px-1 text-[11px] font-medium uppercase tracking-wide text-muted-foreground">
          DPC Airflow + OpenShift Spark config
        </legend>
        <div className="space-y-1">
          <Label htmlFor="dt-objectStoreEndpoint" className="text-xs">
            Object store endpoint{" "}
            <span className="text-destructive">*</span>
          </Label>
          <Input
            id="dt-objectStoreEndpoint"
            value={draft.objectStoreEndpoint}
            onChange={(e: ChangeEvent<HTMLInputElement>) =>
              onDraftChange({
                ...draft,
                objectStoreEndpoint: e.target.value,
              })
            }
            className="h-8 text-xs font-mono"
            placeholder="https://dpc-objectstore.example.com"
            aria-required
          />
        </div>
        <div className="space-y-1">
          <Label htmlFor="dt-objectStoreBucket" className="text-xs">
            Object store bucket
          </Label>
          <Input
            id="dt-objectStoreBucket"
            value={draft.objectStoreBucket}
            onChange={(e: ChangeEvent<HTMLInputElement>) =>
              onDraftChange({
                ...draft,
                objectStoreBucket: e.target.value,
              })
            }
            className="h-8 text-xs font-mono"
            placeholder="pulse-dpc-packages"
          />
        </div>
        <div className="space-y-1">
          <Label htmlFor="dt-dpcAirflowEndpoint" className="text-xs">
            DPC Airflow endpoint{" "}
            <span className="text-destructive">*</span>
          </Label>
          <Input
            id="dt-dpcAirflowEndpoint"
            value={draft.dpcAirflowEndpoint}
            onChange={(e: ChangeEvent<HTMLInputElement>) =>
              onDraftChange({
                ...draft,
                dpcAirflowEndpoint: e.target.value,
              })
            }
            className="h-8 text-xs font-mono"
            placeholder="https://dpc-airflow.example.com"
            aria-required
          />
        </div>
        <div className="space-y-1">
          <Label htmlFor="dt-dpcSparkEndpoint" className="text-xs">
            DPC Spark endpoint <span className="text-destructive">*</span>
          </Label>
          <Input
            id="dt-dpcSparkEndpoint"
            value={draft.dpcSparkEndpoint}
            onChange={(e: ChangeEvent<HTMLInputElement>) =>
              onDraftChange({
                ...draft,
                dpcSparkEndpoint: e.target.value,
              })
            }
            className="h-8 text-xs font-mono"
            placeholder="https://dpc-spark.example.com"
            aria-required
          />
        </div>
        <div className="space-y-1">
          <Label htmlFor="dt-sparkApp" className="text-xs">
            Spark app
          </Label>
          <Input
            id="dt-sparkApp"
            value={draft.sparkApp}
            onChange={(e: ChangeEvent<HTMLInputElement>) =>
              onDraftChange({
                ...draft,
                sparkApp: e.target.value,
              })
            }
            className="h-8 text-xs font-mono"
            placeholder="pulse-pipeline"
          />
        </div>
        <SharedPackageCreateFields draft={draft} onDraftChange={onDraftChange} />
      </fieldset>
    );
  }

  // Unknown target type — render nothing; top-level validator will surface
  // the unknown-type error.
  return null;
}

function SharedPackageCreateFields({
  draft,
  onDraftChange,
}: {
  draft: CreateDraft;
  onDraftChange: (draft: CreateDraft) => void;
}) {
  return (
    <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
      <div className="space-y-1">
        <Label htmlFor="dt-mainPyFile" className="text-xs">
          Main Python file
        </Label>
        <Input
          id="dt-mainPyFile"
          value={draft.mainPyFile}
          onChange={(e: ChangeEvent<HTMLInputElement>) =>
            onDraftChange({ ...draft, mainPyFile: e.target.value })
          }
          className="h-8 text-xs font-mono"
          placeholder="package/main.py"
        />
      </div>
      <div className="space-y-1">
        <Label htmlFor="dt-dagFilePaths" className="text-xs">
          DAG file paths
        </Label>
        <Input
          id="dt-dagFilePaths"
          value={draft.dagFilePaths}
          onChange={(e: ChangeEvent<HTMLInputElement>) =>
            onDraftChange({ ...draft, dagFilePaths: e.target.value })
          }
          className="h-8 text-xs font-mono"
          placeholder="dags/pipeline.py"
        />
      </div>
      <div className="space-y-1">
        <Label htmlFor="dt-pythonFiles" className="text-xs">
          Python files
        </Label>
        <Input
          id="dt-pythonFiles"
          value={draft.pythonFiles}
          onChange={(e: ChangeEvent<HTMLInputElement>) =>
            onDraftChange({ ...draft, pythonFiles: e.target.value })
          }
          className="h-8 text-xs font-mono"
          placeholder="runtime/helpers.py, package/lib.py"
        />
      </div>
      <div className="space-y-1">
        <Label htmlFor="dt-jarFiles" className="text-xs">
          JAR files
        </Label>
        <Input
          id="dt-jarFiles"
          value={draft.jarFiles}
          onChange={(e: ChangeEvent<HTMLInputElement>) =>
            onDraftChange({ ...draft, jarFiles: e.target.value })
          }
          className="h-8 text-xs font-mono"
          placeholder="libs/custom.jar"
        />
      </div>
      <div className="space-y-1 sm:col-span-2">
        <Label htmlFor="dt-tokenReference" className="text-xs">
          Token reference
        </Label>
        <Input
          id="dt-tokenReference"
          value={draft.tokenReference}
          onChange={(e: ChangeEvent<HTMLInputElement>) =>
            onDraftChange({ ...draft, tokenReference: e.target.value })
          }
          className="h-8 text-xs font-mono"
          placeholder="secret://deployment-token"
        />
      </div>
    </div>
  );
}
