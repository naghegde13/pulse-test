"use client";

/**
 * Storage Backends panel — per-(tenant, environment, backend) rows (V96).
 * Values are embedded into generated config / packages so enterprise CI/CD
 * can promote the same artifact to integration, UAT, and production with
 * environment-specific roots and projects already filled in. PULSE typically
 * runs against dev while authoring; higher environments are not deployed
 * directly by this UI —Validate marks a row as platform-approved for those
 * stamped configs (backend preflight may still enforce this).
 *
 * Status badge per cell:
 *   pending     — values not yet confirmed for embedding / promotion
 *   validated   — platform team confirmed; safe to reference in packages
 *   failed      — provisioning_error visible on hover
 *   disabled    — tenant opted out of this backend for this env
 */

import { useCallback, useEffect, useMemo, useState } from "react";
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
import { useTenant } from "@/contexts/tenant-context";
import { useRuntimeAuthority } from "@/contexts/runtime-authority-context";
import type {
  StorageBackend,
  StorageBackendKind,
  StorageEnvironment,
} from "@/types";

const ENVS: StorageEnvironment[] = ["dev", "integration", "uat", "prod"];

/**
 * SU-6 / BUG-61: The legal set of storage-backend kinds is governed by
 * RuntimeAuthority (the deployment-wide persona configuration). What used
 * to be a hardcoded ["GCP", "DPC"] enum is now derived from
 * RuntimeAuthority.allowedStorageBackends so that, for example, a
 * GCP_PULSE-only deployment never offers DPC controls.
 *
 * The constant below remains the canonical superset PULSE knows about;
 * the panel filters this list at render time.
 */
const ALL_BACKENDS: StorageBackendKind[] = ["GCP", "DPC"];

function filterAllowedBackends(
  allowed: string[] | undefined | null,
): StorageBackendKind[] {
  if (!allowed || allowed.length === 0) {
    // No authority loaded yet (or persona empty) — render nothing rather
    // than leaking the full enumeration.
    return [];
  }
  const allowedSet = new Set(allowed.map((s) => s.toUpperCase()));
  return ALL_BACKENDS.filter((k) => allowedSet.has(k));
}

const STATUS_BADGE: Record<
  StorageBackend["provisioningStatus"],
  { label: string; className: string }
> = {
  validated: { label: "validated", className: "bg-green-500/15 text-green-700 dark:text-green-300 border-green-500/30" },
  pending: { label: "pending", className: "bg-amber-500/15 text-amber-700 dark:text-amber-300 border-amber-500/30" },
  failed: { label: "failed", className: "bg-red-500/15 text-red-700 dark:text-red-300 border-red-500/30" },
  disabled: { label: "disabled", className: "bg-zinc-500/15 text-zinc-600 dark:text-zinc-400 border-zinc-500/30" },
};

/** Shown under inputs — matches PathConventionService path shapes (roots only; domain/sor/pipeline are appended at codegen). */
const HELP_GCP_PROJECT =
  "GCP project ID on this row only. Drives gs:// paths for this cell and, when pipelines use BigQuery-native gold, the warehouse project for those tables (dataset/table names come from codegen — there is no separate “gold” field in the grid).";
const HELP_DPC_SCHEME =
  "URI scheme for Spark/Hadoop paths to object storage or HDFS: s3a (e.g. Cloudera Ozone S3) or hdfs.";
const HELP_DPC_CLUSTER =
  "Logical DPC / Cloudera realm name for operators (not pasted into path templates today). Align with how your platform names clusters.";
const HELP_STORAGE_ROOT_FILES =
  "Bucket or top-level container name only (no scheme). Below, Path preview shows the five lifecycle folders PULSE uses under each domain/SOR/pipeline. DPC uses the same folders with an s3a:// or hdfs:// prefix.";
const HELP_STORAGE_ROOT_LAKE =
  "Bucket/container root for medallion tables on object storage (bronze / silver / optional gold paths). Below, Path preview shows example bronze/silver/gold-on-GCS layouts; BigQuery-native gold uses the GCP project field instead.";

/** Illustrative domain/SOR/pipeline slugs — real segments come from the product at codegen. */
const EX_DOM = "originations";
const EX_SOR = "servicing-feed";
const EX_PIPE = "nightly-curate";

/** Canonical env for samples — matches “mostly authoring in dev” copy (integration/uat/prod swap the env segment). */
const EXAMPLE_ENV = "dev";

/**
 * V96-style seeded bucket/project stems: pulse-{slug}-{env}-files / -lake / pulse-dpc-…
 */
function buildPathExampleBodies(slug: string) {
  const env = EXAMPLE_ENV;
  const gcpFiles = `pulse-${slug}-${env}-files`;
  const gcpLake = `pulse-${slug}-${env}-lake`;
  const dpcFiles = `pulse-dpc-${slug}-${env}-files`;
  const dpcLake = `pulse-dpc-${slug}-${env}-lake`;
  const illustGcpProject = `pulse-${slug}-${env}`;

  const fileBase = (scheme: string, root: string) =>
    `${scheme}://${root}/${EX_DOM}/${EX_SOR}/${EX_PIPE}`;

  const filesTreeGcp = [
    `${fileBase("gs", gcpFiles)}/SRC/`,
    `${fileBase("gs", gcpFiles)}/Processing/`,
    `${fileBase("gs", gcpFiles)}/Archive/`,
    `${fileBase("gs", gcpFiles)}/bad_files/`,
    `${fileBase("gs", gcpFiles)}/outgoing_extracts/`,
  ].join("\n");

  const lakeTreeGcp = [
    `${fileBase("gs", gcpLake)}/bronze/stg_vendor_events/`,
    `${fileBase("gs", gcpLake)}/silver/int_vendor_events/`,
    `${fileBase("gs", gcpLake)}/gold/fct_loan_daily/`,
    "",
    "BigQuery-native gold (catalog — uses GCP project from grid, not gs://):",
    `  ${illustGcpProject}.originations_gold.fct_loan_daily`,
  ].join("\n");

  const lakeTreeDpc = [
    `${fileBase("s3a", dpcLake)}/bronze/stg_vendor_events/`,
    `${fileBase("s3a", dpcLake)}/silver/int_vendor_events/`,
    `${fileBase("s3a", dpcLake)}/gold/fct_loan_daily/`,
  ].join("\n");

  const filesTreeDpc = [
    `${fileBase("s3a", dpcFiles)}/SRC/`,
    `${fileBase("s3a", dpcFiles)}/Processing/`,
    `${fileBase("s3a", dpcFiles)}/Archive/`,
    `${fileBase("s3a", dpcFiles)}/bad_files/`,
    `${fileBase("s3a", dpcFiles)}/outgoing_extracts/`,
    "",
    "hdfs:// — same folder tree; only the scheme prefix changes (select hdfs in DPC scheme).",
  ].join("\n");

  return { filesTreeGcp, lakeTreeGcp, lakeTreeDpc, filesTreeDpc, gcpFiles, gcpLake };
}

function PathExamplesPre({ label, body }: { label: string; body: string }) {
  return (
    <div className="mt-3 space-y-1">
      <p className="text-[11px] font-medium text-foreground">{label}</p>
      <pre className="max-h-64 overflow-auto rounded-md border bg-background/80 p-2 font-mono text-[10px] leading-snug text-muted-foreground whitespace-pre-wrap break-all">
        {body}
      </pre>
    </div>
  );
}

export function StorageBackendsPanel({ tenantId }: { tenantId: string }) {
  const { currentTenant } = useTenant();
  const { authority, loading: authorityLoading } = useRuntimeAuthority();
  const allowedBackends = useMemo(
    () => filterAllowedBackends(authority?.allowedStorageBackends),
    [authority?.allowedStorageBackends],
  );
  const [rows, setRows] = useState<StorageBackend[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [reloadToken, setReloadToken] = useState(0);

  const exampleSlug = useMemo(() => {
    if (currentTenant?.id === tenantId && currentTenant.slug?.trim()) {
      return currentTenant.slug.trim();
    }
    const stripped = tenantId.replace(/^tenant-/, "").replace(/_/g, "-");
    return stripped || "your-tenant";
  }, [currentTenant, tenantId]);

  const pathExamples = useMemo(() => buildPathExampleBodies(exampleSlug), [exampleSlug]);

  const reload = useCallback(() => setReloadToken((n) => n + 1), []);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      setLoading(true);
      try {
        const data = await api.get<StorageBackend[]>(
          `/api/v1/tenants/${tenantId}/storage-backends`
        );
        if (!cancelled) {
          setRows(data);
          setError(null);
        }
      } catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : "Failed to load");
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [tenantId, reloadToken]);

  const get = (env: StorageEnvironment, backend: StorageBackendKind) =>
    rows.find((r) => r.environment === env && r.backend === backend) ?? null;

  return (
    <Card>
      <CardHeader>
        <CardTitle>Storage backends</CardTitle>
        <CardDescription className="space-y-2">
          <span className="block">
            Per-environment, per-backend <strong>roots and IDs</strong> that PULSE stamps into generated config and
            deployment packages. You mostly author against <strong>dev</strong>; integration / UAT / prod columns hold
            the values enterprise CI/CD should use when the same package is promoted — not something this app deploys
            for you. Edit what your platform provisioned, then <span className="font-mono text-xs">Validate</span> to
            mark a row ready for those stamped configs.
          </span>
          <span className="block text-muted-foreground">
            <strong className="text-foreground">Files root</strong> ({""}
            <span className="font-mono text-[11px]">storage_root_files</span>) = working file areas (ingest, quarantine,
            extracts). <strong className="text-foreground">Lake root</strong> ({""}
            <span className="font-mono text-[11px]">storage_root_lake</span>) = lake tables on object storage.
            <strong className="text-foreground"> GCP project</strong> on each GCP cell covers gs:// layout and
            BigQuery-native tables for that environment — there is no extra grid column for “gold”.
            Domain / SOR / pipeline path segments are appended at codegen.
          </span>
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {error && (
          <p className="rounded-md border border-destructive/30 bg-destructive/10 text-destructive text-xs px-3 py-2">
            {error}
          </p>
        )}
          <details className="rounded-md border bg-muted/20 px-3 py-2 text-xs text-muted-foreground">
            <summary className="cursor-pointer font-medium text-foreground">
              Path preview — templates and example trees
            </summary>
            <p className="mt-2 text-[11px] leading-relaxed text-muted-foreground">
              Examples use tenant slug <span className="font-mono text-foreground">{exampleSlug}</span> and the{" "}
              <span className="font-mono">{EXAMPLE_ENV}</span> column naming pattern{" "}
              <span className="font-mono">pulse-{exampleSlug}-{EXAMPLE_ENV}-…</span>
              {" "}(swap <span className="font-mono">{EXAMPLE_ENV}</span> for integration/uat/prod in real configs).
            </p>
            <ul className="mt-2 list-disc space-y-1 pl-4 font-mono text-[11px] leading-relaxed">
              <li>
                Files template:{" "}
                <span className="text-muted-foreground">
                  {"{scheme}"}://{"{storage_root_files}"}/{"{domain}"}/{"{sor}"}/{"{pipeline}"}/{"{lifecycle}"}/
                </span>
              </li>
              <li>
                Lake template (object storage):{" "}
                <span className="text-muted-foreground">
                  {"{scheme}"}://{"{storage_root_lake}"}/{"{domain}"}/{"{sor}"}/{"{pipeline}"}/{"{layer}"}/{"{table}"}/
                </span>
              </li>
            </ul>

            <PathExamplesPre
              label={`Example — files bucket (GCP): SRC, Processing, Archive, bad_files, outgoing_extracts (FileLifecycle). Roots like "${pathExamples.gcpFiles}".`}
              body={pathExamples.filesTreeGcp}
            />
            <PathExamplesPre
              label={`Example — lake bucket (GCP), roots like "${pathExamples.gcpLake}"`}
              body={pathExamples.lakeTreeGcp}
            />
            <PathExamplesPre
              label="Example — lake bucket (DPC, s3a). Same bronze/silver/gold folders; hdfs:// keeps identical path suffixes."
              body={pathExamples.lakeTreeDpc}
            />
            <PathExamplesPre
              label="Example — files bucket (DPC, s3a). Same five lifecycle folders as GCP; hdfs:// only changes the scheme."
              body={pathExamples.filesTreeDpc}
            />

            <p className="mt-3 pl-4 border-l-2 border-muted text-[11px] leading-relaxed">
              <span className="font-medium text-foreground">GCP warehouse gold:</span> when a model is{" "}
              <span className="font-mono">bq_native</span>, only the{" "}
              <span className="font-mono">GCP project</span> field on that environment&apos;s GCP cell applies — no extra
              grid row. Dataset/table identifiers are produced by codegen from domain + pipeline naming rules.
            </p>
          </details>
        {loading || authorityLoading ? (
          <p className="text-sm text-muted-foreground">Loading backend rows…</p>
        ) : allowedBackends.length === 0 ? (
          <p className="text-sm text-muted-foreground">
            No storage backends are enabled for the active runtime persona
            (<span className="font-mono text-xs">{authority?.activePersona ?? "unknown"}</span>).
          </p>
        ) : (
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-3">
            {ENVS.flatMap((env) =>
              allowedBackends.map((backend) => {
                const row = get(env, backend);
                return (
                  <BackendCell
                    key={`${env}-${backend}`}
                    tenantId={tenantId}
                    env={env}
                    backend={backend}
                    row={row}
                    onChanged={reload}
                  />
                );
              })
            )}
          </div>
        )}
      </CardContent>
    </Card>
  );
}

/**
 * SU-6 / BUG-62: Shape returned by
 *   GET /api/v1/tenants/{tenantId}/storage-backends/conventional
 *
 * Mirrors {@code StorageRootConventionService.ConventionDefaults} on the
 * backend. Kept local to the panel since this is the only caller today.
 */
interface ConventionDefaults {
  tenantSlug: string;
  env: string;
  backend: StorageBackendKind;
  files: string;
  lake: string;
  gcpProject: string | null;
  dpcScheme: string | null;
  dpcCluster: string | null;
}

type DpcScheme = "s3a" | "hdfs";

interface BackendCellDraft {
  gcpProject: string;
  dpcScheme: DpcScheme;
  dpcCluster: string;
  storageRootFiles: string;
  storageRootLake: string;
}

function normalizeDpcScheme(value: string | null | undefined): DpcScheme {
  return value === "hdfs" ? "hdfs" : "s3a";
}

function BackendCell({
  tenantId,
  env,
  backend,
  row,
  onChanged,
}: {
  tenantId: string;
  env: StorageEnvironment;
  backend: StorageBackendKind;
  row: StorageBackend | null;
  onChanged: () => void;
}) {
  const [editing, setEditing] = useState(false);
  const [busy, setBusy] = useState(false);
  const [busyError, setBusyError] = useState<string | null>(null);
  const [draft, setDraft] = useState<BackendCellDraft>({
    gcpProject: row?.gcpProject ?? "",
    dpcScheme: normalizeDpcScheme(row?.dpcScheme),
    dpcCluster: row?.dpcCluster ?? "",
    storageRootFiles: row?.storageRootFiles ?? "",
    storageRootLake: row?.storageRootLake ?? "",
  });

  // SU-6 / BUG-62: Cached convention defaults so the Edit dialog can
  // pre-populate blank fields and offer a one-click "Reset to convention"
  // affordance. Loaded lazily the first time Edit opens for this cell.
  const [conventionDefaults, setConventionDefaults] =
    useState<ConventionDefaults | null>(null);
  const [conventionError, setConventionError] = useState<string | null>(null);

  useEffect(() => {
    setDraft({
      gcpProject: row?.gcpProject ?? "",
      dpcScheme: normalizeDpcScheme(row?.dpcScheme),
      dpcCluster: row?.dpcCluster ?? "",
      storageRootFiles: row?.storageRootFiles ?? "",
      storageRootLake: row?.storageRootLake ?? "",
    });
  }, [row]);

  // BUG-62 fetch: only fire when Edit opens, only once per cell-edit, and
  // only auto-populate fields that are currently blank so we never
  // overwrite values the operator already typed or that the row already
  // had.
  useEffect(() => {
    if (!editing) return;
    let cancelled = false;
    (async () => {
      try {
        const defaults = await api.get<ConventionDefaults>(
          `/api/v1/tenants/${tenantId}/storage-backends/conventional?` +
            `env=${encodeURIComponent(env)}&backend=${encodeURIComponent(backend)}`
        );
        if (cancelled) return;
        setConventionDefaults(defaults);
        setConventionError(null);
        setDraft((prev) => ({
          gcpProject:
            prev.gcpProject.trim().length > 0
              ? prev.gcpProject
              : defaults.gcpProject ?? "",
          dpcScheme:
            prev.dpcScheme && prev.dpcScheme.trim().length > 0
              ? prev.dpcScheme
              : normalizeDpcScheme(defaults.dpcScheme),
          dpcCluster:
            prev.dpcCluster.trim().length > 0
              ? prev.dpcCluster
              : defaults.dpcCluster ?? "",
          storageRootFiles:
            prev.storageRootFiles.trim().length > 0
              ? prev.storageRootFiles
              : defaults.files,
          storageRootLake:
            prev.storageRootLake.trim().length > 0
              ? prev.storageRootLake
              : defaults.lake,
        }));
      } catch (e) {
        if (!cancelled) {
          setConventionError(
            e instanceof Error
              ? e.message
              : "Failed to load convention defaults"
          );
        }
      }
    })();
    return () => {
      cancelled = true;
    };
    // Intentional: fetch on every Edit-open so a freshly-renamed tenant
    // (rare, but possible mid-session) re-derives. tenantId/env/backend
    // are stable for the lifetime of the cell.
  }, [editing, tenantId, env, backend]);

  const resetToConvention = useCallback(() => {
    if (!conventionDefaults) return;
    setDraft({
      gcpProject: conventionDefaults.gcpProject ?? "",
      dpcScheme: normalizeDpcScheme(conventionDefaults.dpcScheme),
      dpcCluster: conventionDefaults.dpcCluster ?? "",
      storageRootFiles: conventionDefaults.files,
      storageRootLake: conventionDefaults.lake,
    });
  }, [conventionDefaults]);

  const headerClass =
    backend === "GCP"
      ? "border-blue-500/30 bg-blue-500/5"
      : "border-purple-500/30 bg-purple-500/5";

  const action = useCallback(
    async (path: string, body?: object) => {
      if (!row) return;
      setBusy(true);
      setBusyError(null);
      try {
        await api.post(
          `/api/v1/tenants/${tenantId}/storage-backends/${row.id}/${path}`,
          body ?? {}
        );
        onChanged();
      } catch (e) {
        setBusyError(e instanceof Error ? e.message : "Action failed");
      } finally {
        setBusy(false);
      }
    },
    [tenantId, row, onChanged]
  );

  const save = useCallback(async () => {
    if (!row) return;
    setBusy(true);
    setBusyError(null);
    try {
      await api.patch(
        `/api/v1/tenants/${tenantId}/storage-backends/${row.id}`,
        draft
      );
      setEditing(false);
      onChanged();
    } catch (e) {
      setBusyError(e instanceof Error ? e.message : "Save failed");
    } finally {
      setBusy(false);
    }
  }, [tenantId, row, draft, onChanged]);

  if (!row) {
    return (
      <div className={`rounded-md border p-3 text-xs space-y-1 ${headerClass}`}>
        <div className="flex items-center justify-between">
          <span className="font-mono text-[10px] uppercase tracking-wide">
            {env} · {backend}
          </span>
          <Badge variant="outline" className="text-[10px]">
            missing
          </Badge>
        </div>
        <p className="text-muted-foreground">
          No storage_backends row found. Re-run tenant onboarding to create it.
        </p>
      </div>
    );
  }

  const status = STATUS_BADGE[row.provisioningStatus];

  return (
    <div className={`rounded-md border p-3 text-xs space-y-2 ${headerClass}`}>
      <div className="flex items-center justify-between">
        <span className="font-mono text-[10px] uppercase tracking-wide">
          {env} · {backend}
        </span>
        <Badge
          variant="outline"
          className={`text-[10px] ${status.className}`}
          title={row.provisioningError ?? undefined}
        >
          {status.label}
        </Badge>
      </div>

      {editing ? (
        <div className="space-y-2">
          {backend === "GCP" ? (
            <div>
              <Label className="text-[10px]">GCP project</Label>
              <Input
                value={draft.gcpProject}
                onChange={(e) => setDraft({ ...draft, gcpProject: e.target.value })}
                className="h-7 text-xs font-mono"
              />
              <p className="text-[10px] text-muted-foreground leading-snug pt-1">
                {HELP_GCP_PROJECT}
              </p>
            </div>
          ) : (
            <>
              <div>
                <Label className="text-[10px]">DPC scheme</Label>
                <Select
                  value={draft.dpcScheme}
                  onValueChange={(v) => setDraft({ ...draft, dpcScheme: v as "s3a" | "hdfs" })}
                >
                  <SelectTrigger className="h-7 text-xs">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="s3a">s3a (Cloudera Ozone S3)</SelectItem>
                    <SelectItem value="hdfs">hdfs (HDFS native)</SelectItem>
                  </SelectContent>
                </Select>
                <p className="text-[10px] text-muted-foreground leading-snug pt-1">
                  {HELP_DPC_SCHEME}
                </p>
              </div>
              <div>
                <Label className="text-[10px]">DPC cluster</Label>
                <Input
                  value={draft.dpcCluster}
                  onChange={(e) => setDraft({ ...draft, dpcCluster: e.target.value })}
                  className="h-7 text-xs font-mono"
                />
                <p className="text-[10px] text-muted-foreground leading-snug pt-1">
                  {HELP_DPC_CLUSTER}
                </p>
              </div>
            </>
          )}
          <div>
            <Label className="text-[10px]">Files root ({""}
              <span className="font-mono">storage_root_files</span>)</Label>
            <Input
              value={draft.storageRootFiles}
              onChange={(e) => setDraft({ ...draft, storageRootFiles: e.target.value })}
              className="h-7 text-xs font-mono"
            />
            <p className="text-[10px] text-muted-foreground leading-snug pt-1">
              {HELP_STORAGE_ROOT_FILES}
            </p>
          </div>
          <div>
            <Label className="text-[10px]">Lake root ({""}
              <span className="font-mono">storage_root_lake</span>)</Label>
            <Input
              value={draft.storageRootLake}
              onChange={(e) => setDraft({ ...draft, storageRootLake: e.target.value })}
              className="h-7 text-xs font-mono"
            />
            <p className="text-[10px] text-muted-foreground leading-snug pt-1">
              {HELP_STORAGE_ROOT_LAKE}
            </p>
          </div>
          {busyError && <p className="text-destructive text-[11px]">{busyError}</p>}
          {conventionError && (
            <p className="text-amber-700 dark:text-amber-300 text-[11px]" data-testid="convention-error">
              Couldn&apos;t load convention defaults: {conventionError}
            </p>
          )}
          <div className="flex gap-1 flex-wrap">
            <Button size="sm" className="h-7 text-[11px]" onClick={save} disabled={busy}>
              Save
            </Button>
            <Button
              size="sm"
              variant="outline"
              className="h-7 text-[11px]"
              onClick={() => setEditing(false)}
              disabled={busy}
            >
              Cancel
            </Button>
            {/* SU-6 / BUG-62 */}
            <Button
              size="sm"
              variant="outline"
              className="h-7 text-[11px]"
              onClick={resetToConvention}
              disabled={busy || !conventionDefaults}
              title="Reset all fields to the V96 convention defaults for this cell."
              data-testid="reset-to-convention"
            >
              Reset to convention
            </Button>
          </div>
        </div>
      ) : (
        <>
          <dl className="grid grid-cols-3 gap-x-2 gap-y-0.5 font-mono text-[11px]">
            {backend === "GCP" && (
              <>
                <dt className="text-muted-foreground">project</dt>
                <dd className="col-span-2 break-all">{row.gcpProject ?? "—"}</dd>
              </>
            )}
            {backend === "DPC" && (
              <>
                <dt className="text-muted-foreground">cluster</dt>
                <dd className="col-span-2 break-all">{row.dpcCluster ?? "—"}</dd>
                <dt className="text-muted-foreground">scheme</dt>
                <dd className="col-span-2">{row.dpcScheme ?? "—"}</dd>
              </>
            )}
            <dt className="text-muted-foreground">files</dt>
            <dd className="col-span-2 break-all">{row.storageRootFiles}</dd>
            <dt className="text-muted-foreground">lake</dt>
            <dd className="col-span-2 break-all">{row.storageRootLake}</dd>
          </dl>
          {row.provisioningError && (
            <p className="text-destructive text-[11px] truncate" title={row.provisioningError}>
              ⚠ {row.provisioningError}
            </p>
          )}
          {busyError && <p className="text-destructive text-[11px]">{busyError}</p>}
          <div className="flex gap-1 pt-1">
            <Button
              size="sm"
              variant="outline"
              className="h-6 text-[11px]"
              onClick={() => setEditing(true)}
              disabled={busy}
            >
              Edit
            </Button>
            <Button
              size="sm"
              className="h-6 text-[11px]"
              onClick={() => action("validate")}
              disabled={busy || row.provisioningStatus === "validated" || row.disabled}
              title="Mark this row confirmed so generated packages may embed it for CI/CD promotion."
            >
              Validate
            </Button>
            {row.provisioningStatus === "disabled" || row.disabled ? (
              <Button
                size="sm"
                variant="outline"
                className="h-6 text-[11px]"
                onClick={() => action("enable")}
                disabled={busy}
              >
                Enable
              </Button>
            ) : (
              <Button
                size="sm"
                variant="outline"
                className="h-6 text-[11px] text-muted-foreground"
                onClick={() => action("disable")}
                disabled={busy}
              >
                Disable
              </Button>
            )}
          </div>
        </>
      )}
    </div>
  );
}
