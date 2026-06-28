"use client";

/**
 * Tenant Secret Manager binding panel (BUG-2026-05-25-54).
 *
 * Backs the missing UI for the `secretManager` readiness category. Wraps the
 * shared {@link ReadinessCategoryPanel} so this form lives alongside the
 * future GCP / Composer / Dataproc forms with consistent chrome (status pill,
 * inline + banner errors, Save button, readiness anchor).
 *
 * Backend (Agent E):
 *   GET  /api/v1/tenants/{tenantId}/secret-manager
 *   PUT  /api/v1/tenants/{tenantId}/secret-manager
 *
 * Request body shape:
 *   {
 *     mode: "LOCAL_STUB" | "TENANT_GCP_SECRET_MANAGER",
 *     gsmProjectId?: string,    // REQUIRED iff mode === TENANT_GCP_SECRET_MANAGER
 *     secretNamePrefix?: string // optional
 *   }
 *
 * Operator decisions honored:
 *   - Round 4 default: `mode = LOCAL_STUB`. Operator must explicitly switch to
 *     GCP Secret Manager.
 *   - Round 1 form pattern: hardcoded mode enum (no async option loading),
 *     existing PULSE form pattern, inline + banner errors, toast + re-fetch +
 *     autoscroll on save.
 *   - Client-side validation blocks submit if `mode = TENANT_GCP_SECRET_MANAGER`
 *     and `gsmProjectId` is empty (matches the backend 400 contract).
 *
 * Readiness anchor: `id="tenantSecretManager"` +
 * `data-readiness-blocker="tenantSecretManager"` so the readiness wizard can
 * scroll to this panel directly.
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
import type {
  SecretManagerMode,
  TenantSecretManagerForm,
  TenantSecretManagerReadback,
} from "@/types";

/* ── Form value ───────────────────────────────────────────────────────────── */
//
// NOTE (BUG-63 / SU-7): `SecretManagerMode`, `TenantSecretManagerForm`, and
// `TenantSecretManagerReadback` were previously declared file-locally here
// (lines 54-63 prior to SU-7). They were promoted to `@/types` so other
// surfaces (settings wizard, e2e fixtures) can share the same wire contract.

const DEFAULT_FORM: TenantSecretManagerForm = {
  mode: "LOCAL_STUB",
  gsmProjectId: "",
  secretNamePrefix: "",
};

/** Validator — runs on every render; null = OK, else user-facing error. */
function validateForm(v: TenantSecretManagerForm): string | null {
  if (v.mode === "TENANT_GCP_SECRET_MANAGER") {
    if (!v.gsmProjectId || !v.gsmProjectId.trim()) {
      return "GCP project ID is required when mode is TENANT_GCP_SECRET_MANAGER.";
    }
  }
  return null;
}

/* ── Panel ────────────────────────────────────────────────────────────────── */

export function TenantSecretManagerPanel({ tenantId }: { tenantId: string }) {
  const [value, setValue] = useState<TenantSecretManagerForm>(DEFAULT_FORM);
  const [readback, setReadback] = useState<TenantSecretManagerReadback | null>(
    null,
  );
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [reloadToken, setReloadToken] = useState(0);
  const sectionRef = useRef<HTMLDivElement | null>(null);

  /* Fetch current binding state. New tenants 404 — that's treated as "no
   * binding yet, default to LOCAL_STUB" so the operator sees a usable form
   * on first visit. */
  useEffect(() => {
    let cancelled = false;
    (async () => {
      setLoading(true);
      try {
        const data = await api.get<TenantSecretManagerReadback>(
          `/api/v1/tenants/${tenantId}/secret-manager`,
        );
        if (cancelled) return;
        setReadback(data);
        setValue({
          mode: data.mode ?? "LOCAL_STUB",
          gsmProjectId: data.gsmProjectId ?? "",
          secretNamePrefix: data.secretNamePrefix ?? "",
        });
        setLoadError(null);
      } catch (e) {
        if (cancelled) return;
        // 404 / endpoint-not-yet-shipped is non-fatal — surface the default form.
        setReadback(null);
        setValue(DEFAULT_FORM);
        // Show the message inline only if it's clearly something other than
        // a missing endpoint, to avoid noise during the Agent E rollout window.
        const msg = e instanceof Error ? e.message : "Failed to load";
        if (!/404|not found|no such/i.test(msg)) {
          setLoadError(msg);
        } else {
          setLoadError(null);
        }
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [tenantId, reloadToken]);

  const onChange = useCallback(
    (patch: Partial<TenantSecretManagerForm>) =>
      setValue((prev) => ({ ...prev, ...patch })),
    [],
  );

  const submit = useCallback(
    async (v: TenantSecretManagerForm) => {
      // Normalize: when mode is LOCAL_STUB, omit gsmProjectId from the wire
      // request so the backend can keep its null invariant.
      const body =
        v.mode === "LOCAL_STUB"
          ? {
              mode: v.mode,
              secretNamePrefix: v.secretNamePrefix?.trim() || undefined,
            }
          : {
              mode: v.mode,
              gsmProjectId: v.gsmProjectId?.trim(),
              secretNamePrefix: v.secretNamePrefix?.trim() || undefined,
            };

      await api.put(`/api/v1/tenants/${tenantId}/secret-manager`, body);
      toast.success("Tenant Secret Manager binding saved.");
      setReloadToken((n) => n + 1);
      // Autoscroll the panel back into view so the operator sees the readback.
      sectionRef.current?.scrollIntoView({
        behavior: "smooth",
        block: "start",
      });
    },
    [tenantId],
  );

  const status = readback?.computedStatus
    ? readback.computedStatus === "PASS" || readback.computedStatus === "PROVEN"
      ? "pass"
      : readback.computedStatus === "BLOCKED"
        ? "blocked"
        : "fail"
    : undefined;

  return (
    <div ref={sectionRef}>
      <ReadinessCategoryPanel<TenantSecretManagerForm>
        categoryKey="secretManager"
        title="Tenant Secret Manager binding"
        description={
          <>
            Controls how PULSE persists tenant secrets (Git PATs, connector
            credentials). <strong>LOCAL_STUB</strong> writes encrypted blobs to
            the local filesystem and is the default for dev. Switch to{" "}
            <strong>TENANT_GCP_SECRET_MANAGER</strong> in production once the
            tenant service account has{" "}
            <span className="font-mono text-[11px]">
              roles/secretmanager.secretAccessor
            </span>{" "}
            on the target GSM project.
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
                Loading current binding…
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

            <div className="space-y-2">
              <Label htmlFor="secretManagerMode">Mode</Label>
              <Select
                value={v.mode}
                onValueChange={(next) =>
                  onChange({ mode: next as SecretManagerMode })
                }
              >
                <SelectTrigger id="secretManagerMode" className="h-9 text-xs">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="LOCAL_STUB">
                    LOCAL_STUB (dev — local filesystem)
                  </SelectItem>
                  <SelectItem value="TENANT_GCP_SECRET_MANAGER">
                    TENANT_GCP_SECRET_MANAGER (prod — tenant GSM project)
                  </SelectItem>
                </SelectContent>
              </Select>
              <p className="text-[11px] text-muted-foreground">
                LOCAL_STUB is the default for dev. Switching to GSM does NOT
                auto-migrate existing local-stub secrets — operators must
                re-register each credential.
              </p>
            </div>

            {/* gsmProjectId — only visible/enabled when mode = GSM (per BUG-54). */}
            {v.mode === "TENANT_GCP_SECRET_MANAGER" && (
              <div className="space-y-2">
                <Label htmlFor="gsmProjectId">
                  GCP project ID{" "}
                  <span className="text-destructive">*</span>
                </Label>
                <Input
                  id="gsmProjectId"
                  value={v.gsmProjectId ?? ""}
                  onChange={(e) => onChange({ gsmProjectId: e.target.value })}
                  placeholder="pulse-tenant-prod-gsm"
                  className="font-mono text-xs"
                  aria-required
                  aria-invalid={invalid && !v.gsmProjectId?.trim()}
                />
                <p className="text-[11px] text-muted-foreground">
                  The GCP project hosting the tenant&apos;s Secret Manager
                  secrets. May differ from the runtime project — readiness
                  will surface any cross-project IAM binding gaps.
                </p>
              </div>
            )}

            <div className="space-y-2">
              <Label htmlFor="secretNamePrefix">
                Secret name prefix (optional)
              </Label>
              <Input
                id="secretNamePrefix"
                value={v.secretNamePrefix ?? ""}
                onChange={(e) => onChange({ secretNamePrefix: e.target.value })}
                placeholder="pulse-tenant-foo"
                className="font-mono text-xs"
              />
              <p className="text-[11px] text-muted-foreground">
                Optional namespace applied to generated secret names. Leave
                blank for the deployment default.
              </p>
            </div>
          </>
        )}
        readback={
          readback?.updatedAt && (
            <p className="text-[11px] text-muted-foreground">
              Last updated{" "}
              {new Date(readback.updatedAt).toLocaleString()} —{" "}
              <span className="font-mono">
                {readback.mode}
                {readback.gsmProjectId
                  ? ` · ${readback.gsmProjectId}`
                  : ""}
              </span>
            </p>
          )
        }
      />
    </div>
  );
}
