"use client";

/**
 * PKT-0024: Tenant Readiness Wizard.
 *
 * Displays a cohesive binary READY/NOT_READY verdict for a tenant's
 * cloud-backed pipeline-development and deployment readiness.
 * All 16 consolidated categories from the PKT-0015 backend verdict
 * are rendered with PASS/FAIL/BLOCKED/STALE status, evidence,
 * blocker details, ownership, and fix actions.
 *
 * Design rules:
 *   - Consumes server readiness readback; never infers readiness from
 *     localStorage or client-only state.
 *   - Secret-bearing fields (PAT, private key, JDBC password, credential
 *     JSON) are NEVER rendered. The backend already redacts them.
 *   - PKT-FINAL-3 (BUG-05): LOCAL tenant repos are gone — REMOTE-only.
 *     Any legacy LOCAL banner code below is dead and removed.
 *   - iamManifest shows manifest status without implying live IAM execution.
 *   - Isolated green labels cannot make the overall wizard ready without
 *     ALL required categories passing (fail-closed gate).
 */

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { api } from "@/lib/api";
import type {
  TenantReadiness,
  TenantReadinessStep,
  TenantReadinessBlocker,
  ReadinessCategoryKey,
} from "@/types";
import {
  READINESS_CATEGORY_KEYS,
  READINESS_CATEGORY_LABELS,
  READINESS_CATEGORY_GROUPS,
} from "@/types";

/* ── Secret redaction safeguard ─────────────────────────────── */

/**
 * Fields that must NEVER be rendered in the wizard, even if the
 * backend accidentally includes them. This is a client-side safety
 * net — the backend already strips secrets from the verdict.
 */
const REDACTED_EVIDENCE_KEYS = new Set([
  "privateKey",
  "privateKeyData",
  "private_key",
  "private_key_data",
  "credentialJson",
  "credential_json",
  "pat",
  "token",
  "accessToken",
  "access_token",
  "refreshToken",
  "refresh_token",
  "password",
  "jdbcPassword",
  "jdbc_password",
  "secret",
  "secretValue",
  "secret_value",
  "serviceAccountKey",
  "service_account_key",
]);

function isRedactedKey(key: string): boolean {
  return REDACTED_EVIDENCE_KEYS.has(key);
}

function redactEvidenceValue(key: string, value: unknown): unknown {
  if (isRedactedKey(key)) return "••••••••";
  if (typeof value === "string" && value.length > 200) {
    // Long strings may be embedded credentials — show only prefix
    return value.slice(0, 20) + "…[redacted]";
  }
  return value;
}

/* ── Status rendering ───────────────────────────────────────── */

const STATUS_CONFIG: Record<
  string,
  { label: string; className: string; dotClass: string }
> = {
  ready: {
    label: "PASS",
    className:
      "bg-green-500/15 text-green-700 dark:text-green-300 border-green-500/30",
    dotClass: "bg-green-500",
  },
  blocked: {
    label: "FAIL",
    className:
      "bg-red-500/15 text-red-700 dark:text-red-300 border-red-500/30",
    dotClass: "bg-red-500",
  },
  not_configured: {
    label: "BLOCKED",
    className:
      "bg-amber-500/15 text-amber-700 dark:text-amber-300 border-amber-500/30",
    dotClass: "bg-amber-500",
  },
  incomplete: {
    label: "STALE",
    className:
      "bg-zinc-500/15 text-zinc-600 dark:text-zinc-400 border-zinc-500/30",
    dotClass: "bg-zinc-400",
  },
};

function getStatusConfig(status: string) {
  return STATUS_CONFIG[status] ?? STATUS_CONFIG.incomplete;
}

/* ── Fix-link routing ───────────────────────────────────────── */

/**
 * Map category keys to in-app anchor targets on the settings page
 * where the user can take action to resolve blockers.
 */
const CATEGORY_FIX_LINKS: Partial<Record<ReadinessCategoryKey, { href: string; label: string }>> = {
  githubRepo: { href: "/settings", label: "Git Settings" },
  githubPat: { href: "/settings#github-pat-identity", label: "GitHub Identity" },
  gitScaffold: { href: "/settings", label: "Git Settings" },
  storageScaffold: { href: "/settings", label: "Storage Backends" },
  domainScaffold: { href: "/domains", label: "Domains" },
  runtimeBinding: { href: "/settings#runtime-bindings", label: "Runtime Bindings" },
  deploymentTarget: { href: "/settings#runtime-bindings", label: "Runtime Bindings" },
};

/* ── Main Component ─────────────────────────────────────────── */

export interface TenantReadinessWizardProps {
  tenantId: string;
}

export function TenantReadinessWizard({ tenantId }: TenantReadinessWizardProps) {
  const [verdict, setVerdict] = useState<TenantReadiness | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [expandedCategories, setExpandedCategories] = useState<Set<string>>(
    new Set()
  );

  const fetchVerdict = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await api.get<TenantReadiness>(
        `/api/v1/tenants/${tenantId}/readiness/consolidated`
      );
      setVerdict(data);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to load readiness");
      setVerdict(null);
    } finally {
      setLoading(false);
    }
  }, [tenantId]);

  useEffect(() => {
    fetchVerdict();
  }, [fetchVerdict]);

  const toggleCategory = (key: string) => {
    setExpandedCategories((prev) => {
      const next = new Set(prev);
      if (next.has(key)) {
        next.delete(key);
      } else {
        next.add(key);
      }
      return next;
    });
  };

  if (loading) {
    return (
      <Card>
        <CardHeader>
          <CardTitle className="text-base">Tenant Readiness</CardTitle>
        </CardHeader>
        <CardContent>
          <p className="text-sm text-muted-foreground">
            Loading readiness verdict…
          </p>
        </CardContent>
      </Card>
    );
  }

  if (error || !verdict) {
    return (
      <Card>
        <CardHeader>
          <CardTitle className="text-base">Tenant Readiness</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="flex items-center gap-2">
            <Badge
              variant="outline"
              className="bg-red-500/15 text-red-700 border-red-500/30"
            >
              NOT READY
            </Badge>
            <span className="text-sm text-muted-foreground">
              Unable to determine readiness
            </span>
          </div>
          {error && (
            <p className="rounded-md border border-destructive/30 bg-destructive/10 text-destructive text-xs px-3 py-2">
              {error}
            </p>
          )}
          <Button size="sm" variant="outline" onClick={fetchVerdict}>
            Retry
          </Button>
        </CardContent>
      </Card>
    );
  }

  const isReady = verdict.overallStatus === "ready";

  return (
    <Card id="tenant-readiness">
      <CardHeader>
        <div className="flex items-center justify-between">
          <div>
            <CardTitle className="text-base">Tenant Readiness</CardTitle>
            <CardDescription>
              Pipeline-development and deployment readiness for this tenant.
              All categories must pass for overall readiness.
            </CardDescription>
          </div>
          <div className="flex items-center gap-2">
            <Badge
              variant="outline"
              className={
                isReady
                  ? "bg-green-500/15 text-green-700 border-green-500/30 text-sm px-3 py-1"
                  : "bg-red-500/15 text-red-700 border-red-500/30 text-sm px-3 py-1"
              }
            >
              {isReady ? "READY" : "NOT READY"}
            </Badge>
            <Button size="sm" variant="ghost" onClick={fetchVerdict}>
              Refresh
            </Button>
          </div>
        </div>
      </CardHeader>
      <CardContent className="space-y-4">
        {/* Summary bar */}
        <div className="flex items-center gap-3 text-sm">
          <span className="text-muted-foreground">
            {verdict.readyCategoryCount} / {verdict.totalCategoryCount} categories ready
          </span>
          <span className="text-muted-foreground text-xs">
            Checked {new Date(verdict.checkedAt).toLocaleString()}
          </span>
        </div>

        {/* Progress dots */}
        <div className="flex gap-1">
          {READINESS_CATEGORY_KEYS.map((key) => {
            const step = verdict.categories[key];
            const config = step ? getStatusConfig(step.status) : STATUS_CONFIG.incomplete;
            return (
              <div
                key={key}
                className={`flex-1 h-1.5 rounded-full ${config.dotClass}`}
                title={`${READINESS_CATEGORY_LABELS[key]}: ${step?.status ?? "missing"}`}
              />
            );
          })}
        </div>

        {/* Blocker summary (only when NOT ready) */}
        {!isReady && verdict.blockerSummary.length > 0 && (
          <div className="rounded-md border border-red-500/20 bg-red-500/5 px-3 py-2 text-xs space-y-1">
            <div className="font-medium text-red-700 dark:text-red-300">
              {verdict.blockerSummary.length} blocker{verdict.blockerSummary.length !== 1 ? "s" : ""} preventing readiness
            </div>
            <ul className="list-disc list-inside text-red-700/80 dark:text-red-300/80 space-y-0.5">
              {verdict.blockerSummary.slice(0, 5).map((b, i) => (
                <li key={i} className="truncate" title={b.message}>
                  <span className="font-mono text-[10px]">{b.code}</span>{" "}
                  {b.message}
                </li>
              ))}
              {verdict.blockerSummary.length > 5 && (
                <li className="text-muted-foreground">
                  …and {verdict.blockerSummary.length - 5} more
                </li>
              )}
            </ul>
          </div>
        )}

        {/* Category groups */}
        {READINESS_CATEGORY_GROUPS.map((group) => (
          <div key={group.label} className="space-y-1.5">
            <h3 className="text-xs font-medium text-muted-foreground uppercase tracking-wide">
              {group.label}
            </h3>
            <div className="space-y-1">
              {group.keys.map((key) => {
                const step = verdict.categories[key];
                if (!step) {
                  return (
                    <CategoryRow
                      key={key}
                      categoryKey={key}
                      step={null}
                      expanded={false}
                      onToggle={() => {}}
                    />
                  );
                }
                return (
                  <CategoryRow
                    key={key}
                    categoryKey={key}
                    step={step}
                    expanded={expandedCategories.has(key)}
                    onToggle={() => toggleCategory(key)}
                  />
                );
              })}
            </div>
          </div>
        ))}
      </CardContent>
    </Card>
  );
}

/* ── Category Row ───────────────────────────────────────────── */

function CategoryRow({
  categoryKey,
  step,
  expanded,
  onToggle,
}: {
  categoryKey: ReadinessCategoryKey;
  step: TenantReadinessStep | null;
  expanded: boolean;
  onToggle: () => void;
}) {
  const label = READINESS_CATEGORY_LABELS[categoryKey];
  const fixLink = CATEGORY_FIX_LINKS[categoryKey];

  if (!step) {
    return (
      <div className="flex items-center justify-between rounded-md border px-3 py-2 text-xs opacity-60">
        <div className="flex items-center gap-2">
          <span className="h-2 w-2 rounded-full bg-zinc-400 shrink-0" />
          <span>{label}</span>
        </div>
        <Badge
          variant="outline"
          className="text-[10px] bg-zinc-500/15 text-zinc-600 border-zinc-500/30"
        >
          MISSING
        </Badge>
      </div>
    );
  }

  const config = getStatusConfig(step.status);
  const hasDetails =
    step.blockers.length > 0 ||
    step.ownership !== null ||
    Object.keys(step.evidence).length > 0;

  return (
    <div className="rounded-md border text-xs">
      <button
        type="button"
        onClick={hasDetails ? onToggle : undefined}
        aria-expanded={hasDetails ? expanded : undefined}
        className={`flex items-center justify-between w-full px-3 py-2 text-left ${
          hasDetails ? "cursor-pointer hover:bg-muted/30" : "cursor-default"
        }`}
      >
        <div className="flex items-center gap-2">
          <span className={`h-2 w-2 rounded-full shrink-0 ${config.dotClass}`} />
          <span className="font-medium">{label}</span>
          {hasDetails && (
            <span
              className="text-muted-foreground transition-transform inline-block text-[10px]"
              style={{
                transform: expanded ? "rotate(90deg)" : "rotate(0deg)",
              }}
            >
              ▶
            </span>
          )}
        </div>
        <div className="flex items-center gap-2">
          {/* PKT-FINAL-3 (BUG-05): LOCAL repo warning removed — LOCAL repos
              are no longer possible (V145 + GitController guard). */}
          {/* IAM manifest disclaimer */}
          {categoryKey === "iamManifest" &&
            step.status === "ready" && (
              <span className="text-[10px] text-muted-foreground">
                manifest only — no live IAM execution
              </span>
            )}
          <Badge variant="outline" className={`text-[10px] ${config.className}`}>
            {config.label}
          </Badge>
        </div>
      </button>

      {expanded && hasDetails && (
        <div className="border-t px-3 py-2 space-y-2">
          {/* Ownership badge */}
          {step.ownership && (
            <div className="flex items-center gap-2 text-[11px]">
              <span className="text-muted-foreground">Ownership:</span>
              <Badge variant="outline" className="text-[10px]">
                create: {step.ownership.createOwner}
              </Badge>
              <Badge variant="outline" className="text-[10px]">
                validate: {step.ownership.validateOwner}
              </Badge>
              <span className="text-muted-foreground">
                ({step.ownership.resourceKind})
              </span>
            </div>
          )}

          {/* Blockers */}
          {step.blockers.length > 0 && (
            <div className="space-y-1">
              {step.blockers.map((blocker, i) => (
                <BlockerRow key={i} blocker={blocker} />
              ))}
            </div>
          )}

          {/* Evidence (redacted) */}
          {Object.keys(step.evidence).length > 0 && (
            <div className="space-y-0.5">
              <span className="text-[10px] text-muted-foreground font-medium uppercase">
                Evidence
              </span>
              <dl className="grid grid-cols-3 gap-x-2 gap-y-0.5 text-[11px] font-mono">
                {Object.entries(step.evidence).map(([k, v]) => {
                  if (isRedactedKey(k)) return null;
                  const displayValue = redactEvidenceValue(k, v);
                  return (
                    <EvidenceRow key={k} label={k} value={displayValue} />
                  );
                })}
              </dl>
            </div>
          )}

          {/* Fix link */}
          {step.status !== "ready" && fixLink && (
            <div className="pt-1">
              <Link
                href={fixLink.href}
                className="text-[11px] text-primary hover:underline"
              >
                Fix in {fixLink.label} →
              </Link>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

/* ── Blocker Row ────────────────────────────────────────────── */

function BlockerRow({ blocker }: { blocker: TenantReadinessBlocker }) {
  return (
    <div className="rounded-md bg-red-500/5 border border-red-500/20 px-2 py-1.5 text-[11px]">
      <div className="flex items-center justify-between gap-2">
        <div className="flex items-center gap-1.5">
          <span className="font-mono text-red-700 dark:text-red-300 font-medium">
            {blocker.code}
          </span>
          {blocker.operatorRequired && (
            <Badge
              variant="outline"
              className="text-[9px] bg-amber-500/10 text-amber-700 border-amber-500/20"
            >
              operator required
            </Badge>
          )}
        </div>
        {blocker.staleCheckTimestamp && (
          <span className="text-[10px] text-muted-foreground shrink-0">
            checked {new Date(blocker.staleCheckTimestamp).toLocaleString()}
          </span>
        )}
      </div>
      <p className="text-red-700/80 dark:text-red-300/80 mt-0.5">
        {blocker.message}
      </p>
      {blocker.safeNextAction && (
        <p className="text-muted-foreground mt-0.5">
          Action: {blocker.safeNextAction}
        </p>
      )}
    </div>
  );
}

/* ── Evidence Row ───────────────────────────────────────────── */

function EvidenceRow({ label, value }: { label: string; value: unknown }) {
  const display =
    value === null || value === undefined
      ? "—"
      : typeof value === "boolean"
        ? value
          ? "yes"
          : "no"
        : typeof value === "object"
          ? JSON.stringify(value)
          : String(value);

  return (
    <>
      <dt className="text-muted-foreground truncate">{label}</dt>
      <dd className="col-span-2 break-all">{display}</dd>
    </>
  );
}
