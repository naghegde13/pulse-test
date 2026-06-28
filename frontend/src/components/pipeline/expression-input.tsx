"use client";

import { useEffect, useRef, useState } from "react";
import { api } from "@/lib/api";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";
import type { SchemaColumn } from "@/types";

/**
 * Input for SQL expressions used in blueprint params (derived columns,
 * filter/router predicates, join conditions, DQ custom SQL). Wires up live
 * validation against POST /api/v1/expressions/validate so users see
 * syntax errors and unknown-column warnings as they type.
 *
 * Design choices:
 *   - Plain monospace textarea (no codemirror) — keeps the bundle small and
 *     avoids handling cursor restoration through controlled input edits.
 *   - 300ms debounce on validation calls — matches typical typing cadence
 *     and prevents flooding the backend.
 *   - Validation result rendered as a status row below the textarea: a
 *     status chip (valid / parse error / column warning) plus a list of
 *     referenced columns and any diagnostics.
 */

export interface ExpressionInputPort {
  portName: string;
  columns: Pick<SchemaColumn, "name" | "type">[];
}

export interface ExpressionInputProps {
  value: string;
  onChange: (next: string) => void;
  /** Input schemas keyed by port name — supplied to the backend for
   *  unknown-column detection. Multiple ports for joins. */
  inputSchemas: ExpressionInputPort[];
  /** "value" for derived-column expressions; "predicate" for filter,
   *  router, join, and boolean DQ expressions. */
  kind: "value" | "predicate";
  /** Optional caller-declared expected type (e.g. "integer" for a
   *  derived loan_age_days column) — passed to the backend for future
   *  type-mismatch diagnostics. */
  expectedType?: string;
  placeholder?: string;
  rows?: number;
  /** Called after every successful validation so the parent (e.g.
   *  configure-dialog) can disable/enable Save. Optional. */
  onValidationChange?: (result: ValidationResult | null) => void;
}

/**
 * Curated set of common `[[ … ]]` date mnemonics (ADR 0024 SQL date tokens).
 * These resolve deterministically at codegen time; here we only insert the
 * literal token text into the expression at the cursor.
 */
const DATE_MNEMONICS: { token: string; label: string }[] = [
  { token: "PBD", label: "Previous business day" },
  { token: "BOM", label: "Beginning of month" },
  { token: "EOM", label: "End of month" },
  { token: "RUN_DATE", label: "Pipeline run date" },
  { token: "TODAY", label: "Today" },
];

interface ValidationResult {
  valid: boolean;
  outputType: string;
  referencedColumns: { port: string | null; column: string }[];
  diagnostics: {
    severity: "error" | "warning";
    code: string;
    message: string;
    line?: number | null;
    column?: number | null;
  }[];
}

export function ExpressionInput({
  value,
  onChange,
  inputSchemas,
  kind,
  expectedType,
  placeholder,
  rows = 3,
  onValidationChange,
}: ExpressionInputProps) {
  const [result, setResult] = useState<ValidationResult | null>(null);
  const [validating, setValidating] = useState(false);
  const [tokenMenuOpen, setTokenMenuOpen] = useState(false);
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  /**
   * Splice a `[[ MNEMONIC ]]` token into `value` at the textarea's current
   * selection (replacing any selected range), preserving the text on both
   * sides, then emit via onChange. Falls back to appending if no selection
   * info is available. After insertion, restore focus and place the caret
   * just after the inserted token.
   */
  function insertToken(mnemonic: string) {
    const token = `[[ ${mnemonic} ]]`;
    const el = textareaRef.current;
    let next: string;
    let caret: number | null = null;
    if (el && typeof el.selectionStart === "number") {
      const start = el.selectionStart;
      const end = el.selectionEnd ?? start;
      next = value.slice(0, start) + token + value.slice(end);
      caret = start + token.length;
    } else {
      // No selection info — append, normalizing the surrounding spacing.
      next = value.length > 0 ? `${value} ${token} ` : `${token} `;
    }
    onChange(next);
    setTokenMenuOpen(false);
    if (el && caret !== null) {
      // Restore focus + caret on the next tick (after the controlled value
      // re-renders) so the user can keep typing where they left off.
      requestAnimationFrame(() => {
        el.focus();
        try {
          el.setSelectionRange(caret as number, caret as number);
        } catch {
          // Some environments (jsdom) may not implement setSelectionRange.
        }
      });
    }
  }

  useEffect(() => {
    if (debounceRef.current) clearTimeout(debounceRef.current);
    if (!value || value.trim().length === 0) {
      setResult(null);
      onValidationChange?.(null);
      return;
    }
    setValidating(true);
    debounceRef.current = setTimeout(async () => {
      try {
        const next = await api.post<ValidationResult>(
          "/api/v1/expressions/validate",
          {
            expression: value,
            kind,
            inputSchemas: inputSchemas.map((s) => ({
              portName: s.portName,
              columns: s.columns.map((c) => ({ name: c.name, type: c.type })),
            })),
            expectedType,
          }
        );
        setResult(next);
        onValidationChange?.(next);
      } catch (err) {
        // Network/backend failure shouldn't block typing; surface a
        // non-fatal banner and clear the parent's state.
        const msg = err instanceof Error ? err.message : "Validator unavailable";
        const stub: ValidationResult = {
          valid: false,
          outputType: "unknown",
          referencedColumns: [],
          diagnostics: [
            { severity: "warning", code: "VALIDATOR_UNAVAILABLE", message: msg },
          ],
        };
        setResult(stub);
        onValidationChange?.(stub);
      } finally {
        setValidating(false);
      }
    }, 300);
    return () => {
      if (debounceRef.current) clearTimeout(debounceRef.current);
    };
    // inputSchemas can be re-created on every render of the parent — depend
    // on its serialized shape, not the array identity, to avoid loop fetches.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [value, kind, expectedType, JSON.stringify(inputSchemas)]);

  const errors = result?.diagnostics.filter((d) => d.severity === "error") ?? [];
  const warnings = result?.diagnostics.filter((d) => d.severity === "warning") ?? [];

  return (
    <div className="space-y-1.5">
      <textarea
        ref={textareaRef}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder ?? (kind === "predicate"
          ? "balance > 0 AND status IN ('ACTIVE','DELINQUENT')"
          : "datediff(current_date, origination_date)")}
        rows={rows}
        spellCheck={false}
        className={cn(
          "w-full font-mono text-xs px-2 py-1.5 rounded border bg-background",
          "focus:outline-none focus:ring-1 focus:ring-ring",
          errors.length > 0 && "border-red-400 focus:ring-red-400",
          warnings.length > 0 && errors.length === 0 && "border-amber-400 focus:ring-amber-400",
          result?.valid && errors.length === 0 && warnings.length === 0
            && "border-emerald-300"
        )}
      />
      {/* Insert date-token affordance — splices a `[[ MNEMONIC ]]` token at
          the cursor (ADR 0024). Minimal toggle + curated mnemonic buttons. */}
      <div className="flex items-center gap-1.5 flex-wrap text-[10px]">
        <button
          type="button"
          onClick={() => setTokenMenuOpen((o) => !o)}
          aria-expanded={tokenMenuOpen}
          aria-haspopup="menu"
          className="px-1.5 py-0.5 rounded border bg-background text-muted-foreground hover:bg-accent hover:text-accent-foreground"
        >
          + Insert date token
        </button>
        {tokenMenuOpen && (
          <div role="menu" aria-label="Date tokens" className="flex items-center gap-1 flex-wrap">
            {DATE_MNEMONICS.map((m) => (
              <button
                key={m.token}
                type="button"
                role="menuitem"
                title={m.label}
                aria-label={`Insert ${m.token} token`}
                onClick={() => insertToken(m.token)}
                className="px-1.5 py-0.5 rounded border bg-background font-mono hover:bg-accent hover:text-accent-foreground"
              >
                {m.token}
              </button>
            ))}
          </div>
        )}
      </div>
      <div className="flex items-center gap-1.5 flex-wrap text-[10px]">
        {validating && (
          <Badge variant="outline" className="text-[10px] text-muted-foreground">
            validating…
          </Badge>
        )}
        {!validating && result && errors.length === 0 && warnings.length === 0 && (
          <Badge
            variant="outline"
            className="text-[10px] border-emerald-400 text-emerald-700"
          >
            ✓ valid
          </Badge>
        )}
        {!validating && errors.length > 0 && (
          <Badge
            variant="outline"
            className="text-[10px] border-red-400 text-red-700"
          >
            ✗ {errors[0].code === "PARSE_ERROR" ? "parse error" : errors[0].code.toLowerCase().replace(/_/g, " ")}
          </Badge>
        )}
        {!validating && errors.length === 0 && warnings.length > 0 && (
          <Badge
            variant="outline"
            className="text-[10px] border-amber-400 text-amber-700"
          >
            {warnings.length} warning{warnings.length > 1 ? "s" : ""}
          </Badge>
        )}
        {/* Referenced columns chip — useful sanity check for the user */}
        {result && result.referencedColumns.length > 0 && (
          <span className="text-muted-foreground">
            refs:{" "}
            {result.referencedColumns
              .map((r) => (r.port ? `${r.port}.${r.column}` : r.column))
              .join(", ")}
          </span>
        )}
      </div>
      {/* Diagnostic detail rows (one per error/warning) */}
      {result && (errors.length > 0 || warnings.length > 0) && (
        <ul className="text-[10px] space-y-0.5 mt-0.5">
          {errors.map((d, i) => (
            <li key={`err-${i}`} className="text-red-600">
              {d.message}
            </li>
          ))}
          {warnings.map((d, i) => (
            <li key={`warn-${i}`} className="text-amber-700">
              {d.message}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
