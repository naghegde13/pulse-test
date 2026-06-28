"use client";

/**
 * ReadinessCategoryPanel<T> — STUB (PKT-EPIC-tenant-readiness-ui-completeness, BUG-2026-05-25-53).
 *
 * Owned by Agent G in the consolidated readiness epic. This file is a
 * placeholder so sibling per-category forms (Secret Manager, Runtime
 * Bindings, Deployment Targets) can be authored against a stable surface
 * while Agent G finalizes the shared component. Agent G's full implementation
 * will replace this file (auto-fetch of readiness category state, redacted
 * readback, integrated `safeNextAction` button routing). Until then this stub
 * provides:
 *
 *   - Card chrome with a stable `id` + `data-readiness-blocker` attribute so
 *     the wizard's "Open Form" navigation already lands on the right anchor.
 *   - A typed `value` + `onChange` + `onSubmit` + `validate` plumbing so each
 *     per-category form follows the same shape and is trivially upgradable
 *     when the real panel ships.
 *   - Inline banner errors and a Save affordance — the per-category form
 *     supplies the field-level UI inside `renderFields`.
 *
 * No data fetching happens here yet — that's the parent form's responsibility
 * for now (Agent G's full panel will hoist it). The contract is intentionally
 * narrow so we don't paint ourselves into a corner.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * RETIREMENT CRITERIA  (BUG-64 / SU-7)  — DO NOT DELETE THIS FILE UNTIL ALL
 * BOXES BELOW ARE TICKED. Stub-deletion is an Integrator-gated action.
 * ─────────────────────────────────────────────────────────────────────────
 *
 * The stub may be retired (file deleted, replaced by Agent G's real
 * implementation) only when ALL of the following hold:
 *
 *   [ ] R1. Agent G's real `ReadinessCategoryPanel<T>` lands in this same
 *           file path (`frontend/src/components/settings/readiness-category-panel.tsx`)
 *           with a SUPERSET of the stub's public surface:
 *             - same exported names: `ReadinessCategoryPanel`,
 *               `ReadinessCategoryPanelProps`, `ReadinessCategoryPanelStatus`,
 *               `FormFieldOption`, `FormFieldDef`, `TenantReadinessBlocker`.
 *             - prop shape MUST remain assignable from all existing call-
 *               sites without breaking changes (additive only).
 *
 *   [ ] R2. The real panel hoists data-fetching: each consumer panel no
 *           longer needs its own `useEffect`-driven readiness GET. Confirm
 *           the per-category fetch in `tenant-secret-manager-panel.tsx`
 *           (and any future deployment-targets-panel / runtime-bindings-panel
 *           consumer) has been deleted, not just duplicated.
 *
 *   [ ] R3. `safeNextAction` button routing is wired (one of the open
 *           items from BUG-2026-05-25-53). The wizard's "Open Form" deep-
 *           link continues to land on the right anchor (`id={categoryKey}`)
 *           — confirmed via a regression test that asserts presence of
 *           `data-readiness-blocker` for every key in `READINESS_CATEGORY_KEYS`.
 *
 *   [ ] R4. The forward-compat test
 *           `readiness-category-panel-retirement.test.ts` (added in SU-7)
 *           still passes against the new implementation. Update the test
 *           ONLY to widen prop expectations — never to drop assertions.
 *
 *   [ ] R5. All known callsites (currently: `TenantSecretManagerPanel`;
 *           future: `DeploymentTargetsPanel`, `RuntimeBindingsPanel`) compile
 *           and `npm run build` is clean.
 *
 * Until R1-R5 are satisfied, leave this file in place. The brief for SU-7
 * explicitly forbids deletion: criteria not met → stub stays.
 */

import { useCallback, useState, type ReactNode } from "react";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import type { ReadinessCategoryKey } from "@/types";

export type ReadinessCategoryPanelStatus =
  | "unknown"
  | "pass"
  | "fail"
  | "blocked";

/**
 * BUG-46 / Agent H / Merge Shepherd stub: form-field definition surface.
 *
 * Per-form panels declare their fields as `FormFieldDef<TValues>[]` so the
 * eventual Agent G shared panel can render them uniformly. Until Agent G's
 * full implementation lands, this stub captures only the shape Agent H's
 * combined tenant-gcp-setup-panel.tsx already consumes — `name`, `label`,
 * `type`, `required`, `placeholder`, `help`, `options`, `validate`.
 *
 * Adding fields here is safe — Agent G's eventual replacement will extend,
 * not contradict, this contract.
 */
export interface FormFieldOption {
  value: string;
  label: string;
}

export interface FormFieldDef<TValues> {
  /** Field key inside the form values object. */
  name: keyof TValues & string;
  /** Visible label. */
  label: string;
  /** Input flavour. Extend as new per-form fields land. */
  type: "text" | "password" | "select" | "textarea" | "number" | "email";
  required?: boolean;
  placeholder?: string;
  help?: string;
  /** Required when type === "select". */
  options?: ReadonlyArray<FormFieldOption | string>;
  /** Per-field validator. Returns null for ok, or a user-facing error string. */
  validate?: (value: TValues[keyof TValues]) => string | null;
}

/**
 * BUG-46 / Agent H / Merge Shepherd stub: shared blocker shape.
 *
 * The TenantReadinessWizard / per-form panels surface category blockers in
 * a uniform shape: a status, code, and human-readable message + optional
 * next-action hint. The full set is owned by Agent G; per-form panels read
 * a subset, so this stub is intentionally a superset of consumer fields.
 */
export interface TenantReadinessBlocker {
  /** Uppercase status as emitted by the backend readiness DTO. */
  status: "OK" | "BLOCKED" | "WARNING" | "UNKNOWN";
  code: string;
  message: string;
  /** Optional hint about which UI route resolves the blocker. */
  nextAction?: {
    label: string;
    href?: string;
    categoryKey?: ReadinessCategoryKey;
  };
}

export interface ReadinessCategoryPanelProps<T> {
  /** Stable readiness category key — drives `id` + `data-readiness-blocker`. */
  categoryKey: ReadinessCategoryKey;
  title: string;
  description?: ReactNode;
  /** Optional status pill — purely cosmetic until Agent G's wizard wiring lands. */
  status?: ReadinessCategoryPanelStatus;
  /** Current form value. Owned by the parent (controlled). */
  value: T;
  /** Patch callback — receives a partial of T. */
  onChange: (patch: Partial<T>) => void;
  /**
   * Client-side validator. Return `null` for OK, or a user-facing error string.
   * The Save button is disabled when this returns non-null.
   */
  validate?: (value: T) => string | null;
  /**
   * Persist callback. Throw to surface an error in the banner. The panel
   * sets/un-sets its own busy state around this call.
   */
  onSubmit: (value: T) => Promise<void>;
  /**
   * Form fields renderer. Receives the current value + an `invalid` flag the
   * caller can use to drive `aria-invalid` styling on individual inputs.
   */
  renderFields: (args: { value: T; invalid: boolean }) => ReactNode;
  /** Optional readback / extra content shown below the form (e.g. last-saved). */
  readback?: ReactNode;
  /** Optional footer slot (e.g. additional actions). */
  footer?: ReactNode;
}

const STATUS_STYLES: Record<ReadinessCategoryPanelStatus, string> = {
  unknown: "border-zinc-500/30 bg-zinc-500/10 text-zinc-700 dark:text-zinc-300",
  pass: "border-green-500/30 bg-green-500/10 text-green-700 dark:text-green-300",
  fail: "border-red-500/30 bg-red-500/10 text-red-700 dark:text-red-300",
  blocked:
    "border-amber-500/30 bg-amber-500/10 text-amber-700 dark:text-amber-300",
};

export function ReadinessCategoryPanel<T>({
  categoryKey,
  title,
  description,
  status,
  value,
  validate,
  onSubmit,
  renderFields,
  readback,
  footer,
}: ReadinessCategoryPanelProps<T>) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const validationError = validate ? validate(value) : null;
  const invalid = validationError !== null;

  const save = useCallback(async () => {
    if (validationError) {
      setError(validationError);
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await onSubmit(value);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Save failed");
    } finally {
      setBusy(false);
    }
  }, [onSubmit, value, validationError]);

  return (
    <section
      id={categoryKey}
      data-readiness-blocker={categoryKey}
      className="scroll-mt-6"
    >
      <Card>
        <CardHeader>
          <div className="flex items-center justify-between gap-3">
            <CardTitle className="text-base">{title}</CardTitle>
            {status && (
              <span
                className={`rounded-full border px-2 py-0.5 text-[10px] font-medium uppercase tracking-wide ${STATUS_STYLES[status]}`}
              >
                {status}
              </span>
            )}
          </div>
          {description && <CardDescription>{description}</CardDescription>}
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="space-y-3">{renderFields({ value, invalid })}</div>

          {error && (
            <p
              role="alert"
              className="rounded-md border border-destructive/30 bg-destructive/10 text-destructive text-xs px-3 py-2"
            >
              {error}
            </p>
          )}
          {!error && validationError && (
            <p className="text-[11px] text-amber-700 dark:text-amber-300">
              {validationError}
            </p>
          )}

          <div className="flex items-center gap-2">
            <Button
              size="sm"
              onClick={save}
              disabled={busy || invalid}
              title={validationError ?? undefined}
            >
              {busy ? "Saving…" : "Save"}
            </Button>
            {footer}
          </div>

          {readback}
        </CardContent>
      </Card>
    </section>
  );
}
