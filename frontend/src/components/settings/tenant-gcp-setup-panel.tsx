"use client";

/**
 * Tenant GCP Setup Panel — SU-5 restore (BUG-2026-05-26-70).
 *
 * Combined settings panel covering 4 readiness categories for tenant GCP
 * onboarding (originally PKT-CAND-tenant-gcp-config-ui, Round 2 decision:
 * ONE combined panel with 4 collapsible sections):
 *
 *   1. GCP Config         — controlPlaneProjectId + gcpRegion
 *   2. GCP Credentials    — STATIC_KEY (SA JSON upload) or IMPERSONATION (email)
 *   3. Identity Probe     — runtime check of effective GCP identity
 *   4. Role Manifest      — recommended gcloud commands + validation
 *
 * Ported from Agent H (worktree agent-a1f18eef..., reverted in commit 9a50ca5)
 * and adapted to the live ReadinessCategoryPanel<T> API
 * (value/onChange/validate/onSubmit/renderFields), which differs from the Agent
 * G stub Agent H originally compiled against (fields/initialValues/endpoint).
 *
 * Per BUG-49 operator decision: HARD RENAME to controlPlaneProjectId — no
 * back-compat alias for gcpProjectId.
 *
 * Per BUG-71 spec: IMPERSONATION mode UI is included so operators may grant
 * `roles/iam.serviceAccountTokenCreator` on a tenant SA instead of uploading
 * static key material.
 */

import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { toast } from "sonner";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
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

/* ── Public props ───────────────────────────────────────────────────────── */

export interface TenantGcpSetupPanelProps {
  tenantId: string;
  tenantSlug: string;
}

/* ── Constants ──────────────────────────────────────────────────────────── */

/**
 * GCP project IDs: 6–30 chars, lowercase, digits, hyphens; must start with a
 * letter and end with letter or digit (no trailing hyphen).
 */
const PROJECT_ID_RE = /^[a-z][a-z0-9-]{4,28}[a-z0-9]$/;

const GCP_REGIONS: ReadonlyArray<{ label: string; value: string }> = [
  { label: "us-central1 (Iowa)", value: "us-central1" },
  { label: "us-east1 (S. Carolina)", value: "us-east1" },
  { label: "us-east4 (N. Virginia)", value: "us-east4" },
  { label: "us-west1 (Oregon)", value: "us-west1" },
  { label: "us-west2 (Los Angeles)", value: "us-west2" },
  { label: "europe-west1 (Belgium)", value: "europe-west1" },
  { label: "europe-west2 (London)", value: "europe-west2" },
  { label: "europe-west4 (Netherlands)", value: "europe-west4" },
  { label: "asia-east1 (Taiwan)", value: "asia-east1" },
  { label: "asia-northeast1 (Tokyo)", value: "asia-northeast1" },
  { label: "asia-southeast1 (Singapore)", value: "asia-southeast1" },
];

type CredentialMode = "STATIC_KEY" | "IMPERSONATION";

/* ── Section 1: GCP Config (controlPlaneProjectId + gcpRegion) ──────────── */

interface GcpConfigForm {
  controlPlaneProjectId: string;
  gcpRegion: string;
}

interface GcpConfigReadback extends Partial<GcpConfigForm> {
  // Backend GET emits `gcpProjectId` (legacy) — see TenantGcpController#getGcpConfig.
  // SU-FINAL-3: accept both keys so the panel rehydrates either wire shape.
  gcpProjectId?: string;
  computedStatus?: "PASS" | "FAIL" | "BLOCKED" | "PROVEN";
  updatedAt?: string | null;
}

const DEFAULT_GCP_CONFIG: GcpConfigForm = {
  controlPlaneProjectId: "",
  gcpRegion: "",
};

function validateGcpConfig(v: GcpConfigForm): string | null {
  if (!v.controlPlaneProjectId.trim()) {
    return "Control-plane project ID is required.";
  }
  if (!PROJECT_ID_RE.test(v.controlPlaneProjectId.trim())) {
    return "Project ID must be 6–30 chars, lowercase letters/digits/hyphens, start with a letter, end with a letter or digit.";
  }
  if (!v.gcpRegion.trim()) {
    return "GCP region is required.";
  }
  return null;
}

function GcpConfigSection({ tenantId }: { tenantId: string }) {
  const [value, setValue] = useState<GcpConfigForm>(DEFAULT_GCP_CONFIG);
  const [readback, setReadback] = useState<GcpConfigReadback | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [reloadToken, setReloadToken] = useState(0);
  const sectionRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const data = await api.get<GcpConfigReadback>(
          `/api/v1/tenants/${tenantId}/gcp-config`,
        );
        if (cancelled) return;
        setReadback(data);
        setValue({
          controlPlaneProjectId:
            data.controlPlaneProjectId ?? data.gcpProjectId ?? "",
          gcpRegion: data.gcpRegion ?? "",
        });
        setLoadError(null);
      } catch (e) {
        if (cancelled) return;
        setReadback(null);
        setValue(DEFAULT_GCP_CONFIG);
        const msg = e instanceof Error ? e.message : "Failed to load";
        if (!/404|not found|no such/i.test(msg)) setLoadError(msg);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [tenantId, reloadToken]);

  const onChange = useCallback(
    (patch: Partial<GcpConfigForm>) =>
      setValue((prev) => ({ ...prev, ...patch })),
    [],
  );

  const submit = useCallback(
    async (v: GcpConfigForm) => {
      // SU-FINAL-3 round-2 retry fix (PKT-FINAL-8): the backend
      // GcpConfigRequest record at TenantGcpController.java:219 declares
      // `(String gcpProjectId, String gcpRegion)` — Spring's JSON binding
      // is field-name-sensitive, so sending `controlPlaneProjectId` (the
      // panel's local model name, renamed in BUG-49) is silently dropped
      // and surfaces as "controlPlaneProjectId is required" from the
      // service-level validation. We send both keys to bridge the gap until
      // the backend record is renamed (out of SU-FINAL-3 scope).
      await api.put(`/api/v1/tenants/${tenantId}/gcp-config`, {
        gcpProjectId: v.controlPlaneProjectId.trim(),
        controlPlaneProjectId: v.controlPlaneProjectId.trim(),
        gcpRegion: v.gcpRegion.trim(),
      });
      toast.success("GCP config saved.");
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
      <ReadinessCategoryPanel<GcpConfigForm>
        categoryKey="gcpConfig"
        title="GCP Configuration"
        description={
          <>
            Control-plane GCP project and primary region for this tenant.
            Project ID is used to scope all generated GCP resources for the
            tenant.
          </>
        }
        status={status}
        value={value}
        onChange={onChange}
        validate={validateGcpConfig}
        onSubmit={submit}
        renderFields={({ value: v, invalid }) => (
          <>
            {loadError && (
              <p
                role="alert"
                className="rounded-md border border-destructive/30 bg-destructive/10 text-destructive text-xs px-3 py-2"
              >
                {loadError}
              </p>
            )}

            <div className="space-y-2">
              <Label htmlFor="gcpConfig-controlPlaneProjectId">
                Control-plane Project ID{" "}
                <span className="text-destructive">*</span>
              </Label>
              <Input
                id="gcpConfig-controlPlaneProjectId"
                value={v.controlPlaneProjectId}
                onChange={(e) =>
                  onChange({ controlPlaneProjectId: e.target.value })
                }
                placeholder="my-tenant-control-plane"
                className="font-mono text-xs"
                aria-required
                aria-invalid={
                  invalid && !PROJECT_ID_RE.test(v.controlPlaneProjectId.trim())
                }
              />
              <p className="text-[11px] text-muted-foreground">
                6–30 chars, lowercase letters/digits/hyphens; must start with a
                letter and end with a letter or digit.
              </p>
            </div>

            <div className="space-y-2">
              <Label htmlFor="gcpConfig-region">
                GCP Region <span className="text-destructive">*</span>
              </Label>
              <Select
                value={v.gcpRegion}
                onValueChange={(next) => onChange({ gcpRegion: next })}
              >
                <SelectTrigger id="gcpConfig-region" className="h-9 text-xs">
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
          </>
        )}
        readback={
          readback?.updatedAt && (
            <p className="text-[11px] text-muted-foreground">
              Last updated{" "}
              {new Date(readback.updatedAt).toLocaleString()} —{" "}
              <span className="font-mono">
                {readback.controlPlaneProjectId} · {readback.gcpRegion}
              </span>
            </p>
          )
        }
      />
    </div>
  );
}

/* ── Section 2: GCP Credentials (STATIC_KEY | IMPERSONATION) ────────────── */

interface ServiceAccountJsonShape {
  client_email?: unknown;
  private_key_id?: unknown;
  project_id?: unknown;
  private_key?: unknown;
}

interface GcpCredentialsReadback {
  credentialMode?: CredentialMode;
  serviceAccountEmail?: string | null;
  privateKeyId?: string | null;
  credentialProjectId?: string | null;
  credentialStatus?: string | null;
  updatedAt?: string | null;
}

function GcpCredentialsSection({ tenantId }: { tenantId: string }) {
  const [mode, setMode] = useState<CredentialMode>("STATIC_KEY");
  const [serviceAccountJson, setServiceAccountJson] = useState<string>("");
  const [parsedPreview, setParsedPreview] = useState<{
    email?: string;
    keyId?: string;
    projectId?: string;
  } | null>(null);
  const [tenantSaEmail, setTenantSaEmail] = useState<string>("");
  const [fileError, setFileError] = useState<string | null>(null);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [readback, setReadback] = useState<GcpCredentialsReadback | null>(null);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const data = await api.get<GcpCredentialsReadback>(
          `/api/v1/tenants/${tenantId}/gcp-credentials`,
        );
        if (cancelled) return;
        setReadback(data);
        if (data?.credentialMode) setMode(data.credentialMode);
        if (data?.serviceAccountEmail && data.credentialMode === "IMPERSONATION") {
          setTenantSaEmail(data.serviceAccountEmail);
        }
      } catch {
        if (!cancelled) setReadback(null);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [tenantId]);

  const handleFileChange = async (e: React.ChangeEvent<HTMLInputElement>) => {
    setFileError(null);
    setParsedPreview(null);
    setServiceAccountJson("");
    const file = e.target.files?.[0];
    if (!file) return;
    if (file.size > 64 * 1024) {
      setFileError("File too large (max 64 KB).");
      return;
    }
    try {
      const text = await file.text();
      let parsed: ServiceAccountJsonShape;
      try {
        parsed = JSON.parse(text) as ServiceAccountJsonShape;
      } catch {
        setFileError("Not valid JSON.");
        return;
      }
      const missing: string[] = [];
      if (typeof parsed.client_email !== "string") missing.push("client_email");
      if (typeof parsed.private_key_id !== "string") missing.push("private_key_id");
      if (typeof parsed.project_id !== "string") missing.push("project_id");
      if (typeof parsed.private_key !== "string") missing.push("private_key");
      if (missing.length > 0) {
        setFileError(`Missing required fields: ${missing.join(", ")}.`);
        return;
      }
      setParsedPreview({
        email: parsed.client_email as string,
        keyId: parsed.private_key_id as string,
        projectId: parsed.project_id as string,
      });
      setServiceAccountJson(text);
    } catch {
      setFileError("Failed to read file.");
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setSubmitError(null);
    setSaving(true);
    try {
      let body: Record<string, unknown>;
      if (mode === "STATIC_KEY") {
        if (!serviceAccountJson) {
          setSubmitError("Upload a service account JSON file before saving.");
          setSaving(false);
          return;
        }
        body = { serviceAccountJson, credentialMode: "STATIC_KEY" };
        await api.put(`/api/v1/tenants/${tenantId}/gcp-credentials`, body);
      } else {
        if (!tenantSaEmail.trim()) {
          setSubmitError("Tenant service account email is required.");
          setSaving(false);
          return;
        }
        // SU-FINAL-3 round-2 retry fix (PKT-FINAL-8): IMPERSONATION mode has
        // its own POST endpoint at `/gcp-credentials/impersonation`; the
        // STATIC_KEY PUT endpoint requires `serviceAccountJson` and rejects
        // the IMPERSONATION body with "serviceAccountJson is required".
        body = {
          tenantServiceAccountEmail: tenantSaEmail.trim(),
        };
        await api.post(
          `/api/v1/tenants/${tenantId}/gcp-credentials/impersonation`,
          body,
        );
      }
      // SECURITY: clear raw key material from React state immediately.
      setServiceAccountJson("");
      const fileInput = document.getElementById(
        `gcp-sa-file-${tenantId}`,
      ) as HTMLInputElement | null;
      if (fileInput) fileInput.value = "";
      toast.success("GCP credentials saved.");
    } catch (err) {
      setSubmitError(err instanceof Error ? err.message : "Save failed");
    } finally {
      setSaving(false);
    }
  };

  return (
    <section
      id="gcpCredentials"
      data-readiness-blocker="gcpCredentials"
      className="scroll-mt-6 rounded-lg border bg-card p-4 space-y-3"
    >
      <div className="space-y-1">
        <h3 className="text-base font-semibold">GCP Credentials</h3>
        <p className="text-xs text-muted-foreground">
          Either upload a service account JSON key (STATIC_KEY) or grant
          impersonation on a tenant service account (IMPERSONATION).
        </p>
      </div>

      {submitError && (
        <p
          role="alert"
          className="rounded-md border border-destructive/30 bg-destructive/10 text-destructive text-xs px-3 py-2"
        >
          {submitError}
        </p>
      )}

      <form onSubmit={handleSubmit} className="space-y-3">
        <div className="flex gap-2">
          <Button
            type="button"
            size="sm"
            variant={mode === "STATIC_KEY" ? "default" : "outline"}
            onClick={() => setMode("STATIC_KEY")}
          >
            Static SA Key
          </Button>
          <Button
            type="button"
            size="sm"
            variant={mode === "IMPERSONATION" ? "default" : "outline"}
            onClick={() => setMode("IMPERSONATION")}
          >
            Impersonation
          </Button>
        </div>

        {mode === "STATIC_KEY" && (
          <div className="space-y-1.5">
            <Label htmlFor={`gcp-sa-file-${tenantId}`} className="text-xs">
              Service Account JSON Key{" "}
              <span className="text-destructive">*</span>
            </Label>
            <input
              id={`gcp-sa-file-${tenantId}`}
              type="file"
              accept="application/json,.json"
              onChange={handleFileChange}
              className="block w-full text-xs file:mr-3 file:rounded-md file:border file:bg-muted file:px-2 file:py-1 file:text-xs"
            />
            <p className="text-[10px] text-muted-foreground">
              The key is sent once on save and then cleared from this form.
              Stored encrypted server-side.
            </p>
            {fileError && (
              <p className="text-[10px] text-destructive">{fileError}</p>
            )}
            {parsedPreview && (
              <div className="rounded-md border bg-muted/30 p-2 text-[10px] space-y-0.5">
                <p>
                  <span className="text-muted-foreground">Email:</span>{" "}
                  <span className="font-mono">{parsedPreview.email}</span>
                </p>
                <p>
                  <span className="text-muted-foreground">Key ID:</span>{" "}
                  <span className="font-mono">{parsedPreview.keyId}</span>
                </p>
                <p>
                  <span className="text-muted-foreground">Project ID:</span>{" "}
                  <span className="font-mono">{parsedPreview.projectId}</span>
                </p>
              </div>
            )}
          </div>
        )}

        {mode === "IMPERSONATION" && (
          <div className="space-y-1.5">
            <Label
              htmlFor={`gcp-impersonate-email-${tenantId}`}
              className="text-xs"
            >
              Tenant Service Account Email{" "}
              <span className="text-destructive">*</span>
            </Label>
            <Input
              id={`gcp-impersonate-email-${tenantId}`}
              type="email"
              value={tenantSaEmail}
              onChange={(e) => setTenantSaEmail(e.target.value)}
              placeholder="tenant-runner@my-project.iam.gserviceaccount.com"
              className="h-8 text-sm font-mono"
            />
            <p className="text-[10px] text-muted-foreground">
              The PULSE control-plane SA must have{" "}
              <code className="font-mono">
                roles/iam.serviceAccountTokenCreator
              </code>{" "}
              on this account.
            </p>
          </div>
        )}

        <div className="flex justify-end">
          <Button type="submit" size="sm" disabled={saving}>
            {saving ? "Saving…" : "Save Credentials"}
          </Button>
        </div>
      </form>

      {readback?.credentialMode && (
        <div className="rounded-md border bg-muted/30 p-3 text-[10px] space-y-0.5">
          <p className="font-medium text-xs mb-1">
            Current credential (redacted)
          </p>
          <p>
            <span className="text-muted-foreground">Mode:</span>{" "}
            <span className="font-mono">{readback.credentialMode}</span>
          </p>
          <p>
            <span className="text-muted-foreground">Email:</span>{" "}
            <span className="font-mono">
              {readback.serviceAccountEmail ?? "—"}
            </span>
          </p>
          <p>
            <span className="text-muted-foreground">Key ID:</span>{" "}
            <span className="font-mono">{readback.privateKeyId ?? "—"}</span>
          </p>
          <p>
            <span className="text-muted-foreground">Project ID:</span>{" "}
            <span className="font-mono">
              {readback.credentialProjectId ?? "—"}
            </span>
          </p>
          <p>
            <span className="text-muted-foreground">Status:</span>{" "}
            <span className="font-mono">
              {readback.credentialStatus ?? "—"}
            </span>
          </p>
        </div>
      )}
    </section>
  );
}

/* ── Section 3: Identity Probe ──────────────────────────────────────────── */

interface IdentityProbeResult {
  status: string;
  email?: string;
  keyId?: string;
  configuredProjectId?: string;
  credentialSource?: string;
  ambientAuthUsed?: boolean;
  message?: string;
}

function statusBadgeVariant(
  status: string,
): "secondary" | "destructive" | "outline" {
  if (status === "OK" || status === "READY" || status === "VALID")
    return "secondary";
  if (status === "ERROR" || status === "FAILED" || status === "INVALID")
    return "destructive";
  return "outline";
}

function IdentityProbeSection({ tenantId }: { tenantId: string }) {
  const [result, setResult] = useState<IdentityProbeResult | null>(null);
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleRun = async () => {
    setRunning(true);
    setError(null);
    setResult(null);
    try {
      const res = await api.get<IdentityProbeResult>(
        `/api/v1/tenants/${tenantId}/gcp-identity-probe`,
      );
      setResult(res);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Identity probe failed");
    } finally {
      setRunning(false);
    }
  };

  return (
    <section
      id="iamManifest-probe"
      className="scroll-mt-6 rounded-lg border bg-card p-4 space-y-3"
    >
      <div className="space-y-1">
        <h3 className="text-base font-semibold">Identity Probe</h3>
        <p className="text-xs text-muted-foreground">
          Verify the effective GCP identity PULSE will use for tenant
          operations.
        </p>
      </div>

      <div className="flex gap-2">
        <Button size="sm" onClick={handleRun} disabled={running}>
          {running ? "Running…" : "Run Identity Probe"}
        </Button>
      </div>

      {error && (
        <p
          role="alert"
          className="rounded-md border border-destructive/30 bg-destructive/10 text-destructive text-xs px-3 py-2"
        >
          {error}
        </p>
      )}

      {result && (
        <div className="rounded-md border bg-muted/30 p-3 text-[11px] space-y-1">
          <div className="flex items-center gap-2">
            <span className="text-muted-foreground">Status:</span>
            <Badge
              variant={statusBadgeVariant(result.status)}
              className="text-[10px]"
            >
              {result.status}
            </Badge>
            {result.ambientAuthUsed && (
              <Badge variant="outline" className="text-[10px]">
                ambient
              </Badge>
            )}
          </div>
          <p>
            <span className="text-muted-foreground">Email:</span>{" "}
            <span className="font-mono">{result.email ?? "—"}</span>
          </p>
          <p>
            <span className="text-muted-foreground">Key ID:</span>{" "}
            <span className="font-mono">{result.keyId ?? "—"}</span>
          </p>
          <p>
            <span className="text-muted-foreground">Configured Project:</span>{" "}
            <span className="font-mono">
              {result.configuredProjectId ?? "—"}
            </span>
          </p>
          <p>
            <span className="text-muted-foreground">Credential Source:</span>{" "}
            <span className="font-mono">{result.credentialSource ?? "—"}</span>
          </p>
          {result.message && (
            <p className="text-muted-foreground italic">{result.message}</p>
          )}
        </div>
      )}
    </section>
  );
}

/* ── Section 4: Role Manifest ───────────────────────────────────────────── */

interface RoleManifestRecommendation {
  gcloudCommands: string[];
  notes?: string[];
}

interface ManifestValidationCoverage {
  area: string;
  covered: boolean;
}

interface RoleManifestValidationResult {
  status: "OK" | "ERROR" | "WARNING" | "OPERATOR_BLOCKED";
  errors?: string[];
  warnings?: string[];
  coverage?: ManifestValidationCoverage[];
  operatorBlocked?: boolean;
}

function RoleManifestSection({ tenantId }: { tenantId: string }) {
  const [recommendation, setRecommendation] =
    useState<RoleManifestRecommendation | null>(null);
  const [loadingRecommendation, setLoadingRecommendation] = useState(false);
  const [recommendationError, setRecommendationError] = useState<string | null>(
    null,
  );

  const [validation, setValidation] =
    useState<RoleManifestValidationResult | null>(null);
  const [validating, setValidating] = useState(false);
  const [validationError, setValidationError] = useState<string | null>(null);
  const [manifestText, setManifestText] = useState<string>("");

  const handleShowRecommended = async () => {
    setLoadingRecommendation(true);
    setRecommendationError(null);
    try {
      const res = await api.get<RoleManifestRecommendation>(
        `/api/v1/tenants/${tenantId}/gcp-role-manifest/recommended`,
      );
      setRecommendation(res);
    } catch (err) {
      setRecommendationError(
        err instanceof Error
          ? err.message
          : "Failed to load recommended manifest",
      );
    } finally {
      setLoadingRecommendation(false);
    }
  };

  const handleManifestFile = async (e: React.ChangeEvent<HTMLInputElement>) => {
    setValidationError(null);
    const file = e.target.files?.[0];
    if (!file) return;
    if (file.size > 256 * 1024) {
      setValidationError("Manifest file too large (max 256 KB).");
      return;
    }
    try {
      const text = await file.text();
      setManifestText(text);
    } catch {
      setValidationError("Failed to read file.");
    }
  };

  const handleValidate = async () => {
    if (!manifestText) {
      setValidationError("Choose a manifest file first.");
      return;
    }
    setValidating(true);
    setValidationError(null);
    setValidation(null);
    try {
      const res = await api.post<RoleManifestValidationResult>(
        `/api/v1/tenants/${tenantId}/gcp-role-manifest/validate`,
        { manifest: manifestText },
      );
      setValidation(res);
    } catch (err) {
      setValidationError(
        err instanceof Error ? err.message : "Validation failed",
      );
    } finally {
      setValidating(false);
    }
  };

  const handleCopyCommands = async () => {
    if (!recommendation) return;
    try {
      await navigator.clipboard.writeText(
        recommendation.gcloudCommands.join("\n"),
      );
      toast.success("Copied to clipboard");
    } catch {
      toast.error("Copy failed");
    }
  };

  return (
    <section
      id="iamManifest"
      data-readiness-blocker="iamManifest"
      className="scroll-mt-6 rounded-lg border bg-card p-4 space-y-3"
    >
      <div className="space-y-1">
        <h3 className="text-base font-semibold">Role Manifest</h3>
        <p className="text-xs text-muted-foreground">
          Show recommended IAM bindings as gcloud commands, then validate the
          applied manifest.
        </p>
      </div>

      <div className="space-y-2">
        <div className="flex gap-2">
          <Button
            size="sm"
            variant="outline"
            onClick={handleShowRecommended}
            disabled={loadingRecommendation}
          >
            {loadingRecommendation ? "Loading…" : "Show Recommended Manifest"}
          </Button>
          {recommendation && (
            <Button size="sm" variant="ghost" onClick={handleCopyCommands}>
              Copy
            </Button>
          )}
        </div>
        {recommendationError && (
          <p className="text-[10px] text-destructive">{recommendationError}</p>
        )}
        {recommendation && (
          <pre className="rounded-md border bg-muted/50 p-3 text-[11px] font-mono overflow-x-auto whitespace-pre">
            {recommendation.gcloudCommands.join("\n")}
          </pre>
        )}
        {recommendation?.notes && recommendation.notes.length > 0 && (
          <ul className="list-disc pl-5 text-[10px] text-muted-foreground space-y-0.5">
            {recommendation.notes.map((n, i) => (
              <li key={i}>{n}</li>
            ))}
          </ul>
        )}
      </div>

      <div className="space-y-2">
        <Label htmlFor={`gcp-manifest-file-${tenantId}`} className="text-xs">
          Manifest to validate
        </Label>
        <input
          id={`gcp-manifest-file-${tenantId}`}
          type="file"
          accept="application/json,.json,.yaml,.yml,.txt"
          onChange={handleManifestFile}
          className="block w-full text-xs file:mr-3 file:rounded-md file:border file:bg-muted file:px-2 file:py-1 file:text-xs"
        />
        <div className="flex gap-2">
          <Button size="sm" onClick={handleValidate} disabled={validating}>
            {validating ? "Validating…" : "Validate Manifest"}
          </Button>
        </div>
        {validationError && (
          <p className="text-[10px] text-destructive">{validationError}</p>
        )}

        {validation && (
          <div className="rounded-md border bg-muted/30 p-3 text-[11px] space-y-2">
            <div className="flex items-center gap-2 flex-wrap">
              <span className="text-muted-foreground">Status:</span>
              <Badge
                variant={
                  validation.status === "OK"
                    ? "secondary"
                    : validation.status === "WARNING"
                      ? "outline"
                      : "destructive"
                }
                className="text-[10px]"
              >
                {validation.status}
              </Badge>
              {validation.operatorBlocked && (
                <Badge variant="destructive" className="text-[10px]">
                  OPERATOR_BLOCKED
                </Badge>
              )}
            </div>

            {validation.coverage && validation.coverage.length > 0 && (
              <div className="flex flex-wrap gap-1.5">
                {validation.coverage.map((c) => (
                  <Badge
                    key={c.area}
                    variant={c.covered ? "secondary" : "destructive"}
                    className="text-[10px]"
                  >
                    {c.area}: {c.covered ? "covered" : "missing"}
                  </Badge>
                ))}
              </div>
            )}

            {validation.errors && validation.errors.length > 0 && (
              <div>
                <p className="text-[10px] font-medium text-destructive mb-0.5">
                  Errors
                </p>
                <ul className="list-disc pl-5 text-[10px] text-destructive space-y-0.5">
                  {validation.errors.map((e, i) => (
                    <li key={i}>{e}</li>
                  ))}
                </ul>
              </div>
            )}

            {validation.warnings && validation.warnings.length > 0 && (
              <div>
                <p className="text-[10px] font-medium mb-0.5">Warnings</p>
                <ul className="list-disc pl-5 text-[10px] text-muted-foreground space-y-0.5">
                  {validation.warnings.map((w, i) => (
                    <li key={i}>{w}</li>
                  ))}
                </ul>
              </div>
            )}
          </div>
        )}
      </div>
    </section>
  );
}

/* ── Combined panel ─────────────────────────────────────────────────────── */

/**
 * Bootstrap banner shown above all sections — surfaces the SA-bootstrap
 * helper script so operators can self-service a tenant SA without re-doing
 * the gcloud commands by hand.
 */
function BootstrapBanner({ tenantSlug }: { tenantSlug: string }): ReactNode {
  return (
    <div className="rounded-md border border-primary/30 bg-primary/5 p-3 text-xs space-y-1">
      <p className="font-medium">Need to create a service account?</p>
      <p className="text-muted-foreground">
        Run{" "}
        <code className="font-mono bg-muted px-1.5 py-0.5 rounded">
          scripts/gcp-bootstrap-tenant-provisioner.sh --project &lt;gcp-project&gt;
          --tenant {tenantSlug}
        </code>{" "}
        to generate the gcloud commands. Safe for review; does not execute GCP
        commands.
      </p>
    </div>
  );
}

export function TenantGcpSetupPanel({
  tenantId,
  tenantSlug,
}: TenantGcpSetupPanelProps) {
  return (
    <div
      id="tenantGcpSetup"
      data-readiness-blocker="tenantGcpSetup"
      className="scroll-mt-6 space-y-4"
    >
      <BootstrapBanner tenantSlug={tenantSlug} />
      <GcpConfigSection tenantId={tenantId} />
      <GcpCredentialsSection tenantId={tenantId} />
      <IdentityProbeSection tenantId={tenantId} />
      <RoleManifestSection tenantId={tenantId} />
    </div>
  );
}
