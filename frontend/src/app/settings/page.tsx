"use client";

import Link from "next/link";
import { useCallback, useEffect, useState, useTransition } from "react";
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
import { GitSettingsPanel } from "@/components/settings/git-settings-panel";
import { GitHubPatIdentityPanel } from "@/components/settings/github-pat-identity-panel";
import { StorageBackendsPanel } from "@/components/settings/storage-backends-panel";
import { PeerTrustBindingsPanel } from "@/components/settings/peer-trust-bindings-panel";
import { RuntimeBindingsPanel } from "@/components/settings/runtime-bindings-panel";
import { TenantSecretManagerPanel } from "@/components/settings/tenant-secret-manager-panel";
import { DeploymentTargetsPanel } from "@/components/settings/deployment-targets-panel";
import { TenantReadinessWizard } from "@/components/settings/tenant-readiness-wizard";
// SU-5 BUG-70: tenant-scoped GCP onboarding (config/credentials/identity/role-manifest),
// runtime topology, and storage scaffold panels.
import { TenantGcpSetupPanel } from "@/components/settings/tenant-gcp-setup-panel";
import { TenantGcpRuntimeTopologyPanel } from "@/components/settings/tenant-gcp-runtime-topology-panel";
import { StorageScaffoldPanel } from "@/components/settings/storage-scaffold-panel";
// SU-6 BUG-60: scaffold preview component embedded in wizard Step 3.
import { ScaffoldTree } from "@/components/settings/scaffold-tree";
import { RuntimePersonaBadge } from "@/components/layout/runtime-persona-badge";
import { useTenant } from "@/contexts/tenant-context";
import { api } from "@/lib/api";
import type { OnboardingStatus, ScaffoldPreview } from "@/types";

// PKT-FINAL-3 (BUG-05): step 1 (choose repo mode) is gone; the wizard now
// has 3 steps — configure repo, review domains, initialize.
type WizardStep = 1 | 2 | 3;

/** Shape returned by GET /api/v1/users/me/git-identity. */
interface IdentityProbe {
  status?: string;
}

export default function SettingsPage() {
  const { currentTenant } = useTenant();
  const [status, setStatus] = useState<OnboardingStatus | null>(null);
  /** Loading tenant onboarding/repo status only — do not hide the whole page. */
  const [statusLoading, setStatusLoading] = useState(true);
  const [reloadToken, setReloadToken] = useState(0);

  // SU-6 BUG-59: lift `identityStatus` out of the wizard so the page component
  // owns it. The PAT panel notifies via `onIdentityChanged`; the wizard reads
  // the derived state as a prop. Register / rotate / revoke now cascade.
  const [identityStatus, setIdentityStatus] = useState<
    "unknown" | "missing" | "invalid" | "valid"
  >("unknown");

  const refetchIdentityStatus = useCallback(async () => {
    try {
      const m = await api.get<IdentityProbe>(`/api/v1/users/me/git-identity`);
      setIdentityStatus(m?.status === "VALID" ? "valid" : "invalid");
    } catch {
      setIdentityStatus("missing");
    }
  }, []);

  useEffect(() => {
    // Re-derive identity status on initial mount (was previously inside the
    // wizard — see BUG-59 / SU-6 MANIFEST step 5).
    refetchIdentityStatus();
  }, [refetchIdentityStatus]);

  const fetchStatus = useCallback(() => {
    setReloadToken((n) => n + 1);
  }, []);

  useEffect(() => {
    if (!currentTenant) return;
    let cancelled = false;
    setStatusLoading(true);
    (async () => {
      try {
        const data = await api.get<OnboardingStatus>(
          `/api/v1/tenants/${currentTenant.id}/onboarding-status`
        );
        if (!cancelled) setStatus(data);
      } catch {
        if (!cancelled) setStatus(null);
      } finally {
        if (!cancelled) setStatusLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [currentTenant, reloadToken]);

  if (!currentTenant) {
    return (
      <div className="container max-w-3xl py-8 text-sm text-muted-foreground">
        Select a tenant to configure settings.
      </div>
    );
  }

  return (
    <div className="container max-w-3xl py-8 space-y-6">
      <div>
        <div className="flex items-center gap-3">
          <h1 className="text-2xl font-semibold">Tenant Settings</h1>
          <RuntimePersonaBadge />
        </div>
        <p className="text-sm text-muted-foreground">
          Configure the git repository that holds PULSE-generated code for{" "}
          <span className="font-medium">{currentTenant.name}</span>.
        </p>
      </div>

      {/* PKT-0024: Authoritative tenant readiness verdict */}
      <TenantReadinessWizard tenantId={currentTenant.id} />

      <section
        id="github-pat-identity"
        className="scroll-mt-6 space-y-3 rounded-lg border border-primary/20 bg-muted/30 p-4"
      >
        <div>
          <h2 className="text-lg font-semibold tracking-tight">
            Your GitHub identity
          </h2>
          <p className="text-sm text-muted-foreground">
            User-level token. Required before initializing a tenant repository.
            Separate from the tenant repository onboarding below.
          </p>
        </div>
        {/* SU-6 BUG-59: callback notifies parent on register / rotate / revoke. */}
        <GitHubPatIdentityPanel onIdentityChanged={refetchIdentityStatus} />
      </section>

      {statusLoading ? (
        <p className="text-sm text-muted-foreground">Loading repository status…</p>
      ) : status?.onboarded && status.gitRepo ? (
        <GitSettingsPanel tenantId={currentTenant.id} onChanged={fetchStatus} />
      ) : (
        <OnboardingWizard
          tenantId={currentTenant.id}
          onDone={fetchStatus}
          identityStatus={identityStatus}
        />
      )}

      {/* SU-5 BUG-70: Tenant GCP onboarding (GCP Config, Credentials, Identity Probe, Role Manifest). */}
      <TenantGcpSetupPanel
        tenantId={currentTenant.id}
        tenantSlug={currentTenant.slug}
      />

      {/* SU-5 BUG-70: Tenant GCP runtime topology (Composer, Dataproc, BQ datasets, SA). */}
      <TenantGcpRuntimeTopologyPanel tenantId={currentTenant.id} />

      {/* SU-5 BUG-70: Storage scaffold (preview / execute / status). */}
      <StorageScaffoldPanel tenantId={currentTenant.id} />

      <StorageBackendsPanel tenantId={currentTenant.id} />

      {/* BUG-39: runtime bindings are deployment-global; no tenantId prop. */}
      <RuntimeBindingsPanel />

      {/* BUG-54: tenant Secret Manager binding form. */}
      <TenantSecretManagerPanel tenantId={currentTenant.id} />

      {/* BUG-55: tenant deployment targets list + CRUD (Agent C backend). */}
      <DeploymentTargetsPanel tenantId={currentTenant.id} />

      <PeerTrustBindingsPanel tenantId={currentTenant.id} />
    </div>
  );
}

/* -------------------------------------------------------------------------- */

function OnboardingWizard({
  tenantId,
  onDone,
  identityStatus,
}: {
  tenantId: string;
  onDone: () => void;
  /**
   * SU-6 BUG-59: now owned by SettingsPage and threaded down. Was previously
   * a local useState seeded by an empty-deps useEffect — stale after
   * register/rotate/revoke on the PAT panel.
   */
  identityStatus: "unknown" | "missing" | "invalid" | "valid";
}) {
  const [step, setStep] = useState<WizardStep>(1);
  const [repoUrl, setRepoUrl] = useState("");
  const [defaultBranch, setDefaultBranch] = useState("main");
  const [domains, setDomains] = useState<OnboardingStatus["domains"]>([]);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [, startTransition] = useTransition();
  const [submitting, setSubmitting] = useState(false);
  // SU-6 BUG-60: scaffold preview fetched lazily when wizard hits Step 3.
  const [scaffoldPreview, setScaffoldPreview] = useState<ScaffoldPreview | null>(null);

  useEffect(() => {
    startTransition(async () => {
      try {
        const s = await api.get<OnboardingStatus>(
          `/api/v1/tenants/${tenantId}/onboarding-status`
        );
        setDomains(s.domains);
      } catch {
        setDomains([]);
      }
    });
  }, [tenantId]);

  useEffect(() => {
    // SU-6 BUG-60: pull a scaffold preview when the user lands on Step 3 so
    // the planned directory tree can be reviewed before commit.
    if (step !== 3) return;
    let cancelled = false;
    (async () => {
      try {
        const p = await api.post<ScaffoldPreview>(
          `/api/v1/tenants/${tenantId}/onboard/preview`,
          {
            repoType: "REMOTE",
            repoUrl: repoUrl.trim(),
            provider: "GITHUB",
            defaultBranch: defaultBranch.trim() || "main",
            scaffold: { mode: "ALL", refreshTopLevel: true },
          },
        );
        if (!cancelled) setScaffoldPreview(p);
      } catch {
        if (!cancelled) setScaffoldPreview(null);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [step, tenantId, repoUrl, defaultBranch]);

  const repoUrlValid = repoUrl.trim().length > 0 && /^https?:\/\//i.test(repoUrl.trim());
  const canProceed = useCallback(() => {
    // Step 1 (configure repo): block Next until a non-empty URL is typed.
    if (step === 1) return repoUrlValid;
    return true;
  }, [step, repoUrlValid]);

  // PKT-FINAL-3 (BUG-09c): wrap in useCallback with stable deps + an early
  // submit-guard so a re-render cascade (e.g. tenant switch mid-flight)
  // can't double-fire. The API-layer dedupe in api.ts is the second line
  // of defense.
  const handleSubmit = useCallback(async () => {
    if (submitting) return;
    setSubmitting(true);
    setSubmitError(null);
    try {
      // PKT-FINAL-3 (BUG-05): always REMOTE / GITHUB now.
      await api.post(`/api/v1/tenants/${tenantId}/onboard`, {
        repoType: "REMOTE",
        repoUrl: repoUrl.trim(),
        provider: "GITHUB",
        defaultBranch: defaultBranch.trim() || "main",
        scaffold: { mode: "ALL", refreshTopLevel: true },
      });
      onDone();
    } catch (e) {
      setSubmitError(e instanceof Error ? e.message : "Onboarding failed");
      setStep(3);
    } finally {
      setSubmitting(false);
    }
  }, [tenantId, repoUrl, defaultBranch, onDone, submitting]);

  const initializeDisabled =
    submitting || identityStatus !== "valid" || !repoUrlValid;

  return (
    <Card>
      <CardHeader>
        <div className="flex items-center justify-between">
          <div>
            <CardTitle className="text-base">Onboard this tenant</CardTitle>
            <CardDescription>
              Step {step} of 3 — {stepLabel(step)}
            </CardDescription>
          </div>
          <div className="flex gap-1.5">
            {[1, 2, 3].map((s) => (
              <span
                key={s}
                className={`h-1.5 w-6 rounded-full ${
                  s <= step ? "bg-primary" : "bg-muted"
                }`}
              />
            ))}
          </div>
        </div>
      </CardHeader>
      <CardContent className="space-y-4">
        {/* PKT-FINAL-3 (BUG-06): preflight identity hint — make it impossible
            to wonder why the Initialize button is disabled. */}
        {identityStatus !== "valid" && (
          <div className="rounded-md border border-amber-500/30 bg-amber-500/10 px-3 py-2 text-xs text-amber-800 dark:text-amber-200">
            A valid GitHub PAT identity is required before initializing the
            repository.{" "}
            <Link
              href="#github-pat-identity"
              className="font-medium underline"
            >
              Register a GitHub PAT first
            </Link>
            .
          </div>
        )}

        {step === 1 && (
          <div className="space-y-3">
            <p className="text-sm">
              PULSE stores all generated code in a GitHub repository owned by
              this tenant. Provide the repository URL — the URL is{" "}
              <strong>required</strong>.
            </p>
            <div className="space-y-2">
              <Label htmlFor="repo-url">
                Repository URL <span className="text-destructive">*</span>
              </Label>
              <Input
                id="repo-url"
                placeholder="https://github.com/org/repo.git"
                value={repoUrl}
                onChange={(e) => setRepoUrl(e.target.value)}
                aria-invalid={!repoUrlValid && repoUrl.length > 0}
                aria-required="true"
              />
              {repoUrl.length > 0 && !repoUrlValid && (
                <p className="text-[11px] text-destructive">
                  Must be an https:// or http:// GitHub URL.
                </p>
              )}
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div className="space-y-2">
                <Label>Provider</Label>
                <Badge variant="outline" className="h-9 px-3 py-2 text-xs">
                  GitHub
                </Badge>
              </div>
              <div className="space-y-2">
                <Label htmlFor="default-branch">Default branch</Label>
                <Input
                  id="default-branch"
                  value={defaultBranch}
                  onChange={(e) => setDefaultBranch(e.target.value)}
                />
              </div>
            </div>
          </div>
        )}

        {step === 2 && (
          <div className="space-y-3">
            <p className="text-sm">
              PULSE will scaffold a directory tree inside the repo, including a
              {" "}<code className="font-mono">dbt_project/</code> folder and a
              per-domain model directory for each domain below.
            </p>
            {domains.length === 0 ? (
              <p className="text-xs text-muted-foreground">
                No domains registered yet. You can add them later and re-scaffold.
              </p>
            ) : (
              <ul className="space-y-1">
                {domains.map((d) => (
                  <li
                    key={d.id}
                    className="rounded-md border px-3 py-2 text-sm flex items-center justify-between"
                  >
                    <span>{d.name}</span>
                    <Badge variant="outline" className="text-[10px]">
                      domain
                    </Badge>
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}

        {step === 3 && (
          <div className="space-y-3">
            <p className="text-sm">Ready to initialize the tenant repository.</p>
            <dl className="text-sm grid grid-cols-3 gap-x-3 gap-y-1">
              <dt className="text-muted-foreground">URL</dt>
              <dd className="col-span-2 font-mono text-xs break-all">{repoUrl}</dd>
              <dt className="text-muted-foreground">Provider</dt>
              <dd className="col-span-2">GITHUB</dd>
              <dt className="text-muted-foreground">Default branch</dt>
              <dd className="col-span-2">{defaultBranch || "main"}</dd>
              <dt className="text-muted-foreground">Domains</dt>
              <dd className="col-span-2">{domains.length} registered</dd>
            </dl>
            {submitError && (
              <p className="rounded-md border border-destructive/30 bg-destructive/10 text-destructive text-xs px-3 py-2">
                {submitError}
              </p>
            )}
            {/* SU-6 BUG-60: render scaffold preview so user sees planned tree. */}
            {scaffoldPreview && (
              <ScaffoldTree preview={scaffoldPreview} framed={false} />
            )}
          </div>
        )}
      </CardContent>
      <div className="flex items-center justify-between px-6 pb-6">
        <Button
          variant="outline"
          disabled={step === 1 || submitting}
          onClick={() => setStep((s) => (s > 1 ? ((s - 1) as WizardStep) : s))}
        >
          Back
        </Button>
        {step < 3 ? (
          <Button
            disabled={!canProceed()}
            onClick={() => setStep((s) => (s < 3 ? ((s + 1) as WizardStep) : s))}
          >
            Next
          </Button>
        ) : (
          <Button
            onClick={handleSubmit}
            disabled={initializeDisabled}
            title={
              identityStatus !== "valid"
                ? "Register a GitHub PAT first"
                : !repoUrlValid
                  ? "Enter a valid GitHub repository URL"
                  : undefined
            }
          >
            {submitting ? "Initializing…" : "Initialize repository"}
          </Button>
        )}
      </div>
    </Card>
  );
}

function stepLabel(step: WizardStep): string {
  switch (step) {
    case 1:
      return "configure repo";
    case 2:
      return "review domains";
    case 3:
      return "initialize";
  }
}
