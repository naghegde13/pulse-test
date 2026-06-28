"use client";

/**
 * Runtime Bindings panel (ARCH-017, BUG-2026-05-25-39 — deployment-global scope).
 *
 * Per the operator's BUG-39 decision, runtime bindings are GLOBAL to a PULSE
 * deployment, NOT per-tenant. This panel therefore talks to the new global
 * endpoints (Agent E):
 *
 *   GET    /api/v1/runtime-bindings
 *   POST   /api/v1/runtime-bindings
 *   PATCH  /api/v1/runtime-bindings/{id}
 *
 * No `{tenantId}` segment. Two tenants in the same deployment read the same
 * binding rows. The legacy tenant-scoped routes are being decommissioned in
 * the same release; we do not call them.
 *
 * UI shape (operator Round 2 decision): table of bindings with inline-edit-in-row
 * (NOT modal). Per-row Edit flips the row into editable inputs; Save POSTs (new)
 * or PATCHes (existing); Disable PATCHes `enabled: false`. "Add binding" opens
 * a new empty row at the bottom of the table.
 *
 * Errors: banner at the top (load) + inline-in-row (per-row save/disable).
 * Toast + re-fetch + autoscroll on save (Round 1).
 *
 * Readiness anchor: `id="runtimeBindings"` + `data-readiness-blocker="runtimeBindings"`
 * so the readiness wizard's "Open Form" navigation lands here.
 */

import {
  useCallback,
  useEffect,
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
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { api } from "@/lib/api";
import { useRuntimeAuthority } from "@/contexts/runtime-authority-context";
import { RuntimePersonaBadge } from "@/components/layout/runtime-persona-badge";
import type {
  BindingKind,
  BindingValidationStatus,
  RuntimeBindingDTO,
  SettingsRole,
} from "@/types/runtime";

/* ── Static option lists (hardcoded per Round 1) ──────────────────────────── */

const ENV_OPTIONS = ["dev", "integration", "uat", "prod"] as const;

/**
 * SU-6 / BUG-65: BINDING_KIND_OPTIONS used to be a hardcoded enumeration.
 * The set of legal kinds is now derived from RuntimeAuthority — the same
 * filter pattern applied to the storage-backends panel (BUG-61). LOCAL is
 * preserved as an always-allowed binding kind because it is an
 * authority-agnostic dev-time escape hatch (no remote provider).
 *
 * The constant below remains the canonical superset; the panel filters
 * it at render time using {@link useRuntimeAuthority}.
 */
const ALL_BINDING_KINDS: BindingKind[] = ["GCP", "DPC", "LOCAL"];

function filterAllowedBindingKinds(
  allowed: string[] | undefined | null,
): BindingKind[] {
  if (!allowed || allowed.length === 0) {
    // Authority not yet loaded — surface only LOCAL so the form is usable
    // for dev-time bring-up rather than empty.
    return ALL_BINDING_KINDS.filter((k) => k === "LOCAL");
  }
  const allowedSet = new Set(allowed.map((s) => s.toUpperCase()));
  // LOCAL is always allowed (see comment above); the remote kinds (GCP,
  // DPC) are governed by RuntimeAuthority.allowedStorageBackends.
  return ALL_BINDING_KINDS.filter(
    (k) => k === "LOCAL" || allowedSet.has(k),
  );
}

/* ── Status badge dictionaries ───────────────────────────────────────────── */

const VALIDATION_BADGE: Record<
  BindingValidationStatus,
  { label: string; className: string }
> = {
  VALIDATED: {
    label: "validated",
    className:
      "bg-green-500/15 text-green-700 dark:text-green-300 border-green-500/30",
  },
  PENDING: {
    label: "pending",
    className:
      "bg-amber-500/15 text-amber-700 dark:text-amber-300 border-amber-500/30",
  },
  FAILED: {
    label: "failed",
    className:
      "bg-red-500/15 text-red-700 dark:text-red-300 border-red-500/30",
  },
  DISABLED: {
    label: "disabled",
    className:
      "bg-zinc-500/15 text-zinc-600 dark:text-zinc-400 border-zinc-500/30",
  },
};

/* ── Draft (for new + editing rows) ───────────────────────────────────────── */

interface BindingDraft {
  environment: string;
  bindingKind: BindingKind;
  settingsRole: SettingsRole;
  storageRootFiles: string;
  storageRootLake: string;
  storageRootOps: string;
}

const EMPTY_DRAFT: BindingDraft = {
  environment: "dev",
  bindingKind: "GCP",
  settingsRole: "PRIMARY",
  storageRootFiles: "",
  storageRootLake: "",
  storageRootOps: "",
};

function draftFromBinding(b: RuntimeBindingDTO): BindingDraft {
  return {
    environment: b.environment,
    bindingKind: b.bindingKind,
    settingsRole: b.settingsRole,
    storageRootFiles: b.storageRootFiles ?? "",
    storageRootLake: b.storageRootLake ?? "",
    storageRootOps: b.storageRootOps ?? "",
  };
}

function validateDraft(d: BindingDraft): string | null {
  if (!d.environment.trim()) return "Environment is required.";
  if (!ENV_OPTIONS.includes(d.environment as (typeof ENV_OPTIONS)[number])) {
    return "Environment must be one of dev / integration / uat / prod.";
  }
  if (!d.bindingKind) return "Binding kind is required.";
  // At least one storage root must be set so the row is meaningful.
  if (
    !d.storageRootFiles.trim() &&
    !d.storageRootLake.trim() &&
    !d.storageRootOps.trim()
  ) {
    return "At least one storage root (files / lake / ops) is required.";
  }
  return null;
}

/* ── Panel ────────────────────────────────────────────────────────────────── */

export function RuntimeBindingsPanel() {
  const { authority, loading: authorityLoading } = useRuntimeAuthority();
  const allowedKinds = filterAllowedBindingKinds(
    authority?.allowedStorageBackends,
  );
  const [bindings, setBindings] = useState<RuntimeBindingDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [reloadToken, setReloadToken] = useState(0);

  /** id-of-row currently being edited, or "__new__" for the add-row, or null. */
  const [editingId, setEditingId] = useState<string | null>(null);
  const [draft, setDraft] = useState<BindingDraft>(EMPTY_DRAFT);

  const sectionRef = useRef<HTMLElement | null>(null);

  const reload = useCallback(() => setReloadToken((n) => n + 1), []);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      setLoading(true);
      try {
        const data = await api.get<RuntimeBindingDTO[]>(
          `/api/v1/runtime-bindings`,
        );
        if (!cancelled) {
          setBindings(data);
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
  }, [reloadToken]);

  const startEdit = useCallback((b: RuntimeBindingDTO) => {
    setDraft(draftFromBinding(b));
    setEditingId(b.id);
  }, []);

  const startAdd = useCallback(() => {
    setDraft(EMPTY_DRAFT);
    setEditingId("__new__");
  }, []);

  const cancelEdit = useCallback(() => {
    setEditingId(null);
    setDraft(EMPTY_DRAFT);
  }, []);

  const afterSuccess = useCallback(
    (message: string) => {
      toast.success(message);
      setEditingId(null);
      setDraft(EMPTY_DRAFT);
      reload();
      // Autoscroll back to the section so the operator sees the refreshed row.
      sectionRef.current?.scrollIntoView({ behavior: "smooth", block: "start" });
    },
    [reload],
  );

  const rows = [...bindings].sort((a, b) =>
    a.environment === b.environment
      ? a.bindingKind.localeCompare(b.bindingKind)
      : a.environment.localeCompare(b.environment),
  );

  return (
    <section
      id="runtimeBindings"
      data-readiness-blocker="runtimeBindings"
      ref={sectionRef}
      className="scroll-mt-6"
    >
      <Card>
        <CardHeader>
          <div className="flex items-center justify-between gap-3">
            <CardTitle>Runtime Bindings (deployment-global)</CardTitle>
            <RuntimePersonaBadge />
          </div>
          <CardDescription>
            Per-environment runtime bindings PULSE uses for code generation and
            deployment. These are <strong>global</strong> to this PULSE
            deployment — all tenants share the same binding rows. PRIMARY
            bindings drive codegen; DIAGNOSTIC bindings are read-only snapshots
            for troubleshooting.
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

          {loading || authorityLoading ? (
            <p className="text-sm text-muted-foreground">
              Loading runtime bindings…
            </p>
          ) : (
            <div className="overflow-x-auto rounded-md border">
              <table className="w-full text-xs">
                <thead className="bg-muted/40 text-muted-foreground">
                  <tr>
                    <th className="px-2 py-1.5 text-left font-medium">env</th>
                    <th className="px-2 py-1.5 text-left font-medium">kind</th>
                    <th className="px-2 py-1.5 text-left font-medium">role</th>
                    <th className="px-2 py-1.5 text-left font-medium">
                      files root
                    </th>
                    <th className="px-2 py-1.5 text-left font-medium">
                      lake root
                    </th>
                    <th className="px-2 py-1.5 text-left font-medium">
                      status
                    </th>
                    <th className="px-2 py-1.5 text-left font-medium">
                      last validated
                    </th>
                    <th className="px-2 py-1.5 text-right font-medium">
                      actions
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {rows.length === 0 && editingId !== "__new__" && (
                    <tr>
                      <td
                        colSpan={8}
                        className="px-3 py-3 text-center text-muted-foreground"
                      >
                        No primary bindings configured for this PULSE
                        deployment.
                      </td>
                    </tr>
                  )}
                  {rows.map((b) =>
                    editingId === b.id ? (
                      <EditRow
                        key={b.id}
                        bindingId={b.id}
                        draft={draft}
                        onDraftChange={setDraft}
                        onCancel={cancelEdit}
                        onSaved={() => afterSuccess("Runtime binding updated.")}
                        allowedKinds={allowedKinds}
                      />
                    ) : (
                      <ReadRow
                        key={b.id}
                        binding={b}
                        disabledByOther={editingId !== null}
                        onEdit={() => startEdit(b)}
                        onDisabled={() =>
                          afterSuccess("Runtime binding disabled.")
                        }
                      />
                    ),
                  )}
                  {editingId === "__new__" && (
                    <EditRow
                      bindingId={null}
                      draft={draft}
                      onDraftChange={setDraft}
                      onCancel={cancelEdit}
                      onSaved={() => afterSuccess("Runtime binding created.")}
                      allowedKinds={allowedKinds}
                    />
                  )}
                </tbody>
              </table>
            </div>
          )}

          {!loading && (
            <div className="flex justify-end">
              <Button
                size="sm"
                variant="outline"
                onClick={startAdd}
                disabled={editingId !== null}
                title={
                  editingId !== null
                    ? "Finish editing the current row first."
                    : "Add a new global runtime binding."
                }
              >
                + Add binding
              </Button>
            </div>
          )}
        </CardContent>
      </Card>
    </section>
  );
}

/* ── Read row ─────────────────────────────────────────────────────────────── */

function ReadRow({
  binding,
  disabledByOther,
  onEdit,
  onDisabled,
}: {
  binding: RuntimeBindingDTO;
  disabledByOther: boolean;
  onEdit: () => void;
  onDisabled: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [busyError, setBusyError] = useState<string | null>(null);

  const validationBadge = VALIDATION_BADGE[binding.validationStatus];

  const disable = useCallback(async () => {
    setBusy(true);
    setBusyError(null);
    try {
      // BUG-39 contract: disable = PATCH with enabled: false (no DELETE).
      await api.patch(`/api/v1/runtime-bindings/${binding.id}`, {
        enabled: false,
      });
      onDisabled();
    } catch (e) {
      const msg = e instanceof Error ? e.message : "Disable failed";
      setBusyError(msg);
      toast.error(msg);
    } finally {
      setBusy(false);
    }
  }, [binding.id, onDisabled]);

  const alreadyDisabled =
    binding.validationStatus === "DISABLED" || binding.recordState !== "ACTIVE";

  return (
    <tr className="border-t">
      <td className="px-2 py-1.5 font-mono">{binding.environment}</td>
      <td className="px-2 py-1.5 font-mono">{binding.bindingKind}</td>
      <td className="px-2 py-1.5 font-mono">{binding.settingsRole}</td>
      <td className="px-2 py-1.5 font-mono break-all">
        {binding.storageRootFiles ?? "—"}
      </td>
      <td className="px-2 py-1.5 font-mono break-all">
        {binding.storageRootLake ?? "—"}
      </td>
      <td className="px-2 py-1.5">
        <Badge
          variant="outline"
          className={`text-[10px] ${validationBadge.className}`}
          title={binding.validationError ?? undefined}
        >
          {validationBadge.label}
        </Badge>
      </td>
      <td className="px-2 py-1.5 text-muted-foreground">
        {binding.validatedAt
          ? new Date(binding.validatedAt).toLocaleString()
          : "—"}
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
            disabled={busy || alreadyDisabled || disabledByOther}
            title={
              alreadyDisabled
                ? "Already disabled."
                : "Mark this binding disabled."
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

function EditRow({
  bindingId,
  draft,
  onDraftChange,
  onCancel,
  onSaved,
  allowedKinds,
}: {
  /** null = creating a new row (POST), string = editing existing (PATCH). */
  bindingId: string | null;
  draft: BindingDraft;
  onDraftChange: (d: BindingDraft) => void;
  onCancel: () => void;
  onSaved: () => void;
  /** SU-6 / BUG-65: persona-filtered set of legal binding kinds. */
  allowedKinds: BindingKind[];
}) {
  const [busy, setBusy] = useState(false);
  const [busyError, setBusyError] = useState<string | null>(null);

  const validationError = validateDraft(draft);

  const onField = useCallback(
    (k: keyof BindingDraft) => (e: ChangeEvent<HTMLInputElement>) =>
      onDraftChange({ ...draft, [k]: e.target.value }),
    [draft, onDraftChange],
  );

  const save = useCallback(async () => {
    if (validationError) {
      setBusyError(validationError);
      return;
    }
    setBusy(true);
    setBusyError(null);
    try {
      if (bindingId === null) {
        await api.post(`/api/v1/runtime-bindings`, draft);
      } else {
        await api.patch(`/api/v1/runtime-bindings/${bindingId}`, draft);
      }
      onSaved();
    } catch (e) {
      const msg = e instanceof Error ? e.message : "Save failed";
      setBusyError(msg);
      toast.error(msg);
    } finally {
      setBusy(false);
    }
  }, [bindingId, draft, onSaved, validationError]);

  return (
    <tr className="border-t bg-blue-500/5">
      <td className="px-2 py-1.5">
        <Select
          value={draft.environment}
          onValueChange={(v) => onDraftChange({ ...draft, environment: v })}
        >
          <SelectTrigger className="h-7 text-xs">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {ENV_OPTIONS.map((e) => (
              <SelectItem key={e} value={e}>
                {e}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </td>
      <td className="px-2 py-1.5">
        <Select
          value={draft.bindingKind}
          onValueChange={(v) =>
            onDraftChange({ ...draft, bindingKind: v as BindingKind })
          }
        >
          <SelectTrigger className="h-7 text-xs">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {allowedKinds.map((k) => (
              <SelectItem key={k} value={k}>
                {k}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </td>
      <td className="px-2 py-1.5">
        <Select
          value={draft.settingsRole}
          onValueChange={(v) =>
            onDraftChange({ ...draft, settingsRole: v as SettingsRole })
          }
        >
          <SelectTrigger className="h-7 text-xs">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="PRIMARY">PRIMARY</SelectItem>
            <SelectItem value="DIAGNOSTIC">DIAGNOSTIC</SelectItem>
          </SelectContent>
        </Select>
      </td>
      <td className="px-2 py-1.5">
        <Input
          value={draft.storageRootFiles}
          onChange={onField("storageRootFiles")}
          className="h-7 text-xs font-mono"
          placeholder="files-bucket"
          aria-invalid={Boolean(validationError)}
        />
      </td>
      <td className="px-2 py-1.5">
        <Input
          value={draft.storageRootLake}
          onChange={onField("storageRootLake")}
          className="h-7 text-xs font-mono"
          placeholder="lake-bucket"
          aria-invalid={Boolean(validationError)}
        />
      </td>
      <td className="px-2 py-1.5" colSpan={2}>
        <Input
          value={draft.storageRootOps}
          onChange={onField("storageRootOps")}
          className="h-7 text-xs font-mono"
          placeholder="ops-bucket (optional)"
        />
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
