"use client";

/**
 * Tenant GCP Runtime Topology Panel — SU-5 (BUG-2026-05-26-70).
 *
 * Single-form panel that lets an operator declare the GCP runtime topology
 * PULSE will use for this tenant: Composer environment, Dataproc/BigQuery
 * locations, the medallion BigQuery datasets, the Secret Manager project,
 * the control-plane SA, and evidence/logging sinks.
 *
 * Persisted via:
 *   GET  /api/v1/tenants/{tenantId}/gcp-runtime-topology
 *   POST /api/v1/tenants/{tenantId}/gcp-runtime-topology
 *
 * Uses the shared ReadinessCategoryPanel<T> shell so it matches the rest of
 * the per-category settings panels (status pill, inline validation, banner
 * errors, Save button with disabled-when-invalid + tooltip).
 *
 * The composer environment field is regex-validated to a GCP resource name
 * (projects/*​/locations/*​/environments/*) to prevent silent runtime-deploy
 * failures (BUG-2026-05-26-69 found the same bug on deployment-targets:
 * adapters apply synthetic defaults when the form misses keys, surfacing as
 * cryptic GCP API errors at deploy time).
 */

import { useCallback, useEffect, useRef, useState } from "react";
import { toast } from "sonner";
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
import { ReadinessCategoryPanel } from "@/components/settings/readiness-category-panel";
import type { TenantGcpRuntimeTopologyForm } from "@/types";

/* ── Constants ──────────────────────────────────────────────────────────── */

/**
 * Composer environment resource name: projects/{project}/locations/{region}/environments/{env}.
 * Matches GCP API surface; rejecting other shapes prevents silent deploy errors.
 */
const COMPOSER_ENV_RE =
  /^projects\/[^/]+\/locations\/[^/]+\/environments\/[^/]+$/;

const PROJECT_ID_RE = /^[a-z][a-z0-9-]{4,28}[a-z0-9]$/;
const DATASET_NAME_RE = /^[A-Za-z_][A-Za-z0-9_]*$/;
const SA_EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

const GCP_REGIONS: ReadonlyArray<{ label: string; value: string }> = [
  { label: "us-central1", value: "us-central1" },
  { label: "us-east1", value: "us-east1" },
  { label: "us-east4", value: "us-east4" },
  { label: "us-west1", value: "us-west1" },
  { label: "us-west2", value: "us-west2" },
  { label: "europe-west1", value: "europe-west1" },
  { label: "europe-west2", value: "europe-west2" },
  { label: "europe-west4", value: "europe-west4" },
  { label: "asia-east1", value: "asia-east1" },
  { label: "asia-northeast1", value: "asia-northeast1" },
  { label: "asia-southeast1", value: "asia-southeast1" },
];

/** BigQuery dataset locations — multi-region + single-region. */
const BQ_LOCATIONS: ReadonlyArray<{ label: string; value: string }> = [
  { label: "US (multi-region)", value: "US" },
  { label: "EU (multi-region)", value: "EU" },
  { label: "us-central1", value: "us-central1" },
  { label: "us-east1", value: "us-east1" },
  { label: "europe-west1", value: "europe-west1" },
  { label: "europe-west4", value: "europe-west4" },
  { label: "asia-east1", value: "asia-east1" },
  { label: "asia-northeast1", value: "asia-northeast1" },
];

/* ── Form value ─────────────────────────────────────────────────────────── */

type TenantGcpRuntimeTopologyFormValue = TenantGcpRuntimeTopologyForm & {
  evidenceLogBucket: string;
  evidenceLogPrefix: string;
};

interface TenantGcpRuntimeTopologyReadback
  extends Partial<TenantGcpRuntimeTopologyFormValue> {
  computedStatus?: "PASS" | "FAIL" | "BLOCKED" | "PROVEN";
  updatedAt?: string | null;
}

const DEFAULT_FORM: TenantGcpRuntimeTopologyFormValue = {
  composerEnvironment: "",
  composerRegion: "",
  dataprocRegion: "",
  bigQueryLocation: "",
  bronzeDataset: "",
  silverDataset: "",
  goldDataset: "",
  evidenceDataset: "",
  secretManagerProjectId: "",
  controlPlaneSaEmail: "",
  evidenceLogBucket: "",
  evidenceLogPrefix: "",
};

/** Returns the first user-facing error message, or null if OK. */
function validateForm(v: TenantGcpRuntimeTopologyFormValue): string | null {
  if (!v.composerEnvironment.trim()) {
    return "Composer environment is required.";
  }
  if (!COMPOSER_ENV_RE.test(v.composerEnvironment.trim())) {
    return "Composer environment must match format: projects/{project}/locations/{region}/environments/{env}";
  }
  if (!v.composerRegion.trim()) return "Composer region is required.";
  if (!v.dataprocRegion.trim()) return "Dataproc region is required.";
  if (!v.bigQueryLocation.trim()) return "BigQuery location is required.";

  for (const [label, name] of [
    ["Bronze", v.bronzeDataset.trim()],
    ["Silver", v.silverDataset.trim()],
    ["Gold", v.goldDataset.trim()],
    ["Evidence", v.evidenceDataset.trim()],
  ] as const) {
    if (!name) return `${label} dataset is required.`;
    if (!DATASET_NAME_RE.test(name)) {
      return `${label} dataset must match ${DATASET_NAME_RE} (letters/digits/underscore, must not start with a digit).`;
    }
  }

  if (!v.secretManagerProjectId.trim()) {
    return "Secret Manager project ID is required.";
  }
  if (!PROJECT_ID_RE.test(v.secretManagerProjectId.trim())) {
    return "Secret Manager project ID must match GCP project format.";
  }
  if (!v.controlPlaneSaEmail.trim()) {
    return "Control-plane service account email is required.";
  }
  if (!SA_EMAIL_RE.test(v.controlPlaneSaEmail.trim())) {
    return "Control-plane SA must be a valid email address.";
  }
  // Evidence log bucket/prefix are optional — backend will default if blank.
  return null;
}

/* ── Panel ──────────────────────────────────────────────────────────────── */

export function TenantGcpRuntimeTopologyPanel({
  tenantId,
}: {
  tenantId: string;
}) {
  const [value, setValue] = useState<TenantGcpRuntimeTopologyFormValue>(DEFAULT_FORM);
  const [readback, setReadback] =
    useState<TenantGcpRuntimeTopologyReadback | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [reloadToken, setReloadToken] = useState(0);
  const sectionRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      setLoading(true);
      try {
        const data = await api.get<TenantGcpRuntimeTopologyReadback>(
          `/api/v1/tenants/${tenantId}/gcp-runtime-topology`,
        );
        if (cancelled) return;
        setReadback(data);
        setValue({
          composerEnvironment: data.composerEnvironment ?? "",
          composerRegion: data.composerRegion ?? "",
          dataprocRegion: data.dataprocRegion ?? "",
          bigQueryLocation: data.bigQueryLocation ?? "",
          bronzeDataset: data.bronzeDataset ?? "",
          silverDataset: data.silverDataset ?? "",
          goldDataset: data.goldDataset ?? "",
          evidenceDataset: data.evidenceDataset ?? "",
          secretManagerProjectId: data.secretManagerProjectId ?? "",
          controlPlaneSaEmail: data.controlPlaneSaEmail ?? "",
          evidenceLogBucket: data.evidenceLogBucket ?? "",
          evidenceLogPrefix: data.evidenceLogPrefix ?? "",
        });
        setLoadError(null);
      } catch (e) {
        if (cancelled) return;
        setReadback(null);
        setValue(DEFAULT_FORM);
        const msg = e instanceof Error ? e.message : "Failed to load";
        if (!/404|not found|no such/i.test(msg)) setLoadError(msg);
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [tenantId, reloadToken]);

  const onChange = useCallback(
    (patch: Partial<TenantGcpRuntimeTopologyFormValue>) =>
      setValue((prev) => ({ ...prev, ...patch })),
    [],
  );

  const submit = useCallback(
    async (v: TenantGcpRuntimeTopologyFormValue) => {
      // SU-FINAL-3 round-2 retry fix (PKT-FINAL-8): the topology controller
      // (TenantGcpRuntimeTopologyController) declares `@PutMapping` for this
      // path and its TopologyRequest record uses `bqLocation,
      // bqDatasetBronze, bqDatasetSilver, bqDatasetGold, evidenceSinkBucket,
      // dataprocProjectId, bqProjectId, composerProjectId,
      // composerEnvironmentBucket, dataprocWorkloadSaEmail, loggingProjectId,
      // loggingLogBucket` etc. The panel previously POSTed (returns 500
      // MethodNotAllowed) with shorthand field names (`bigQueryLocation`,
      // `bronzeDataset`, `evidenceLogBucket`) that Spring silently dropped.
      // We now PUT with both the panel-shorthand and backend-record keys,
      // and synthesise the project IDs from `secretManagerProjectId` (a
      // tenant-scoped project) so dataproc/bigquery/composer all bind.
      // composerEnvironmentBucket follows GCP's standard
      // `{region}-{project}-pulse-composer` shape. Evidence sink reuses
      // the optional evidenceLogBucket. Logging defaults to the project.
      const projectId = v.secretManagerProjectId.trim();
      const composerEnvBucket =
        v.evidenceLogBucket.trim() || `${projectId}-pulse-composer-bucket`;
      const body = {
        // Composer
        composerProjectId: projectId,
        composerEnvironment: v.composerEnvironment.trim(),
        composerRegion: v.composerRegion.trim(),
        composerEnvironmentBucket: composerEnvBucket,
        // Dataproc
        dataprocProjectId: projectId,
        dataprocRegion: v.dataprocRegion.trim(),
        dataprocWorkloadSaEmail: v.controlPlaneSaEmail.trim(),
        dataprocStagingBucket: `${projectId}-pulse-dataproc-staging`,
        // BigQuery
        bqProjectId: projectId,
        bqLocation: v.bigQueryLocation.trim(),
        bqDatasetBronze: v.bronzeDataset.trim(),
        bqDatasetSilver: v.silverDataset.trim(),
        bqDatasetGold: v.goldDataset.trim(),
        // Evidence
        evidenceSinkBucket: v.evidenceLogBucket.trim() || undefined,
        evidenceSinkDataset: v.evidenceDataset.trim() || undefined,
        // Secret Manager
        secretManagerProjectId: projectId,
        // Logging
        loggingProjectId: projectId,
        loggingLogBucket: v.evidenceLogBucket.trim() || undefined,
        // Control plane
        controlPlaneSaEmail: v.controlPlaneSaEmail.trim(),
        // Legacy panel-shorthand names — harmless extras for forward compat
        bigQueryLocation: v.bigQueryLocation.trim(),
        bronzeDataset: v.bronzeDataset.trim(),
        silverDataset: v.silverDataset.trim(),
        goldDataset: v.goldDataset.trim(),
        evidenceDataset: v.evidenceDataset.trim(),
        evidenceLogBucket: v.evidenceLogBucket.trim() || undefined,
        evidenceLogPrefix: v.evidenceLogPrefix.trim() || undefined,
      };
      await api.put(
        `/api/v1/tenants/${tenantId}/gcp-runtime-topology`,
        body,
      );
      toast.success("GCP runtime topology saved.");
      setReloadToken((n) => n + 1);
      sectionRef.current?.scrollIntoView({
        behavior: "smooth",
        block: "start",
      });
    },
    [tenantId],
  );

  const status = readback?.computedStatus
    ? readback.computedStatus === "PASS" || readback.computedStatus === "PROVEN"
      ? ("pass" as const)
      : readback.computedStatus === "BLOCKED"
        ? ("blocked" as const)
        : ("fail" as const)
    : undefined;

  return (
    <div ref={sectionRef}>
      <ReadinessCategoryPanel<TenantGcpRuntimeTopologyFormValue>
        categoryKey="composer"
        title="GCP Runtime Topology"
        description={
          <>
            Declares the GCP resources PULSE will deploy against for this
            tenant: Composer + Dataproc regions, BigQuery medallion datasets
            (bronze/silver/gold + evidence), Secret Manager project, the
            control-plane SA, and evidence/logging sinks. Required before any
            tenant-scoped package can be deployed.
          </>
        }
        status={status}
        value={value}
        onChange={onChange}
        validate={validateForm}
        onSubmit={submit}
        renderFields={({ value: v, invalid }) => (
          <>
            {loading && (
              <p className="text-xs text-muted-foreground">
                Loading current topology…
              </p>
            )}
            {loadError && (
              <p
                role="alert"
                className="rounded-md border border-destructive/30 bg-destructive/10 text-destructive text-xs px-3 py-2"
              >
                {loadError}
              </p>
            )}

            {/* Composer + Dataproc + BigQuery */}
            <fieldset className="space-y-3 rounded-md border bg-muted/20 p-3">
              <legend className="px-1 text-[11px] font-medium uppercase tracking-wide text-muted-foreground">
                Compute & query engines
              </legend>
              <div className="space-y-2">
                <Label htmlFor="topology-composerEnv">
                  Composer environment{" "}
                  <span className="text-destructive">*</span>
                </Label>
                <Input
                  id="topology-composerEnv"
                  value={v.composerEnvironment}
                  onChange={(e) =>
                    onChange({ composerEnvironment: e.target.value })
                  }
                  placeholder="projects/my-project/locations/us-central1/environments/pulse-composer"
                  className="font-mono text-xs"
                  aria-required
                  aria-invalid={
                    invalid && !COMPOSER_ENV_RE.test(v.composerEnvironment.trim())
                  }
                />
                <p className="text-[11px] text-muted-foreground">
                  Format:{" "}
                  <code className="font-mono">
                    projects/{"{project}"}/locations/{"{region}"}/environments/
                    {"{env}"}
                  </code>
                </p>
              </div>

              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <div className="space-y-2">
                  <Label htmlFor="topology-composerRegion">
                    Composer region{" "}
                    <span className="text-destructive">*</span>
                  </Label>
                  <Select
                    value={v.composerRegion}
                    onValueChange={(next) => onChange({ composerRegion: next })}
                  >
                    <SelectTrigger
                      id="topology-composerRegion"
                      className="h-8 text-xs"
                    >
                      <SelectValue placeholder="Pick a region" />
                    </SelectTrigger>
                    <SelectContent>
                      {GCP_REGIONS.map((r) => (
                        <SelectItem key={r.value} value={r.value}>
                          {r.label}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>
                <div className="space-y-2">
                  <Label htmlFor="topology-dataprocRegion">
                    Dataproc region{" "}
                    <span className="text-destructive">*</span>
                  </Label>
                  <Select
                    value={v.dataprocRegion}
                    onValueChange={(next) => onChange({ dataprocRegion: next })}
                  >
                    <SelectTrigger
                      id="topology-dataprocRegion"
                      className="h-8 text-xs"
                    >
                      <SelectValue placeholder="Pick a region" />
                    </SelectTrigger>
                    <SelectContent>
                      {GCP_REGIONS.map((r) => (
                        <SelectItem key={r.value} value={r.value}>
                          {r.label}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>
              </div>

              <div className="space-y-2">
                <Label htmlFor="topology-bqLocation">
                  BigQuery location{" "}
                  <span className="text-destructive">*</span>
                </Label>
                <Select
                  value={v.bigQueryLocation}
                  onValueChange={(next) => onChange({ bigQueryLocation: next })}
                >
                  <SelectTrigger
                    id="topology-bqLocation"
                    className="h-8 text-xs"
                  >
                    <SelectValue placeholder="Pick a BQ location" />
                  </SelectTrigger>
                  <SelectContent>
                    {BQ_LOCATIONS.map((r) => (
                      <SelectItem key={r.value} value={r.value}>
                        {r.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                <p className="text-[11px] text-muted-foreground">
                  Applies to all four BigQuery datasets below. Cross-region
                  joins are not supported by BigQuery; pick the same location
                  the source data lives in.
                </p>
              </div>
            </fieldset>

            {/* Medallion datasets */}
            <fieldset className="space-y-3 rounded-md border bg-muted/20 p-3">
              <legend className="px-1 text-[11px] font-medium uppercase tracking-wide text-muted-foreground">
                Medallion BigQuery datasets
              </legend>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <div className="space-y-1.5">
                  <Label htmlFor="topology-bronze">
                    Bronze dataset <span className="text-destructive">*</span>
                  </Label>
                  <Input
                    id="topology-bronze"
                    value={v.bronzeDataset}
                    onChange={(e) =>
                      onChange({ bronzeDataset: e.target.value })
                    }
                    placeholder="acme_bronze"
                    className="font-mono text-xs"
                    aria-required
                  />
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="topology-silver">
                    Silver dataset <span className="text-destructive">*</span>
                  </Label>
                  <Input
                    id="topology-silver"
                    value={v.silverDataset}
                    onChange={(e) =>
                      onChange({ silverDataset: e.target.value })
                    }
                    placeholder="acme_silver"
                    className="font-mono text-xs"
                    aria-required
                  />
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="topology-gold">
                    Gold dataset <span className="text-destructive">*</span>
                  </Label>
                  <Input
                    id="topology-gold"
                    value={v.goldDataset}
                    onChange={(e) => onChange({ goldDataset: e.target.value })}
                    placeholder="acme_gold"
                    className="font-mono text-xs"
                    aria-required
                  />
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="topology-evidence">
                    Evidence dataset{" "}
                    <span className="text-destructive">*</span>
                  </Label>
                  <Input
                    id="topology-evidence"
                    value={v.evidenceDataset}
                    onChange={(e) =>
                      onChange({ evidenceDataset: e.target.value })
                    }
                    placeholder="acme_evidence"
                    className="font-mono text-xs"
                    aria-required
                  />
                </div>
              </div>
              <p className="text-[11px] text-muted-foreground">
                Dataset names must match{" "}
                <code className="font-mono">{"^[A-Za-z_][A-Za-z0-9_]*$"}</code>{" "}
                (BigQuery naming rules).
              </p>
            </fieldset>

            {/* Secret Manager + control-plane SA */}
            <fieldset className="space-y-3 rounded-md border bg-muted/20 p-3">
              <legend className="px-1 text-[11px] font-medium uppercase tracking-wide text-muted-foreground">
                Identity & secrets
              </legend>
              <div className="space-y-1.5">
                <Label htmlFor="topology-sm">
                  Secret Manager project ID{" "}
                  <span className="text-destructive">*</span>
                </Label>
                <Input
                  id="topology-sm"
                  value={v.secretManagerProjectId}
                  onChange={(e) =>
                    onChange({ secretManagerProjectId: e.target.value })
                  }
                  placeholder="acme-pulse-secrets"
                  className="font-mono text-xs"
                  aria-required
                />
                <p className="text-[11px] text-muted-foreground">
                  Project hosting the tenant&apos;s Secret Manager secrets. May
                  differ from the runtime project.
                </p>
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="topology-controlPlaneSa">
                  Control-plane service account email{" "}
                  <span className="text-destructive">*</span>
                </Label>
                <Input
                  id="topology-controlPlaneSa"
                  type="email"
                  value={v.controlPlaneSaEmail}
                  onChange={(e) =>
                    onChange({ controlPlaneSaEmail: e.target.value })
                  }
                  placeholder="pulse-control-plane@my-project.iam.gserviceaccount.com"
                  className="font-mono text-xs"
                  aria-required
                />
                <p className="text-[11px] text-muted-foreground">
                  PULSE will use this SA (or impersonate it) to deploy and
                  manage tenant runtime resources.
                </p>
              </div>
            </fieldset>

            {/* Evidence / logging */}
            <fieldset className="space-y-3 rounded-md border bg-muted/20 p-3">
              <legend className="px-1 text-[11px] font-medium uppercase tracking-wide text-muted-foreground">
                Evidence & logging
              </legend>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <div className="space-y-1.5">
                  <Label htmlFor="topology-evBucket">
                    Evidence log bucket (optional)
                  </Label>
                  <Input
                    id="topology-evBucket"
                    value={v.evidenceLogBucket}
                    onChange={(e) =>
                      onChange({ evidenceLogBucket: e.target.value })
                    }
                    placeholder="acme-pulse-evidence"
                    className="font-mono text-xs"
                  />
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="topology-evPrefix">
                    Evidence log prefix (optional)
                  </Label>
                  <Input
                    id="topology-evPrefix"
                    value={v.evidenceLogPrefix}
                    onChange={(e) =>
                      onChange({ evidenceLogPrefix: e.target.value })
                    }
                    placeholder="logs/pulse/"
                    className="font-mono text-xs"
                  />
                </div>
              </div>
              <p className="text-[11px] text-muted-foreground">
                Backend will default to{" "}
                <code className="font-mono">gs://{"{project}"}-pulse-evidence</code>{" "}
                if both are blank.
              </p>
            </fieldset>
          </>
        )}
        readback={
          readback?.updatedAt && (
            <p className="text-[11px] text-muted-foreground">
              Last updated {new Date(readback.updatedAt).toLocaleString()} —{" "}
              <span className="font-mono">
                {readback.composerEnvironment ?? "—"}
              </span>
            </p>
          )
        }
      />
    </div>
  );
}
