"use client";

/**
 * SqlChainEditor — the RICH (Spark-dialect) sql-builder variant, S1a.
 *
 * Spec: SPEC-construct-library.md §1.1a + IMPL §S1a. Owns the #5 `sql-chain-editor`
 * hint → `SqlModel.steps`. Edits the multi-step chain
 *   steps: [{ name, sql, materialize }]
 * with a per-step `materialize` toggle, the SHARED Spark-function palette
 * (imported from `./spark-functions` — the ONE registry, G-1; do NOT fork a
 * second copy), `[[ … ]]` mnemonic insertion, and a per-step DATA preview (S9).
 *
 * STRUCTURALLY-BUILT — two backend dependencies live OUTSIDE this lane:
 *   1. CALCITE-PHASE-2 (#6 SPEC-calcite-sql-model.md): the schema-deriving
 *      per-step validator (`/api/v1/expressions/validate` today returns
 *      outputType="unknown"). Until it lands, per-step live validation degrades
 *      to parse-only — wired here, gated by availability.
 *   2. The S10 design-time preview endpoint
 *      `(input-dataset sample, chain steps 1..N) → top-N rows + schema`
 *      (dev-Spark-on-cached-sample). Wired here; absent → graceful "unavailable".
 */

import { useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Badge } from "@/components/ui/badge";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { api } from "@/lib/api";
import { cn } from "@/lib/utils";
import { SPARK_FUNCTIONS } from "./spark-functions";
import { getTypeColor } from "./column-picker";
import type { SchemaColumn } from "@/types";

export interface SqlChainStep {
  name: string;
  sql: string;
  materialize: boolean;
}

/** Curated mnemonics offered by the per-step `[[ … ]]` insert affordance. */
const INSERT_MNEMONICS = ["PBD", "BOM", "EOM", "RUN_DATE", "TODAY"];

interface PreviewResult {
  columns: SchemaColumn[];
  rows: Record<string, unknown>[];
  truncated?: boolean;
}

type PreviewState =
  | { kind: "idle" }
  | { kind: "loading" }
  | { kind: "ok"; result: PreviewResult }
  | { kind: "error"; message: string }
  | { kind: "unavailable"; message: string };

export interface SqlChainEditorProps {
  steps: SqlChainStep[];
  onChange: (steps: SqlChainStep[]) => void;
  /** Live input schema (the chain's `input` port) — feeds column chips. */
  inputColumns?: SchemaColumn[];
  /** Identifiers the S10 preview endpoint needs to sample the chain input. */
  versionId?: string;
  instanceId?: string;
  /** W-10: business as-of date that `[[ … ]]` mnemonics resolve against for
   *  the per-step preview. */
  asOf?: string;
}

export function SqlChainEditor({
  steps,
  onChange,
  inputColumns,
  versionId,
  instanceId,
  asOf,
}: SqlChainEditorProps) {
  const [previewByStep, setPreviewByStep] = useState<Record<number, PreviewState>>({});
  const sqlRefs = useRef<Record<number, HTMLTextAreaElement | null>>({});

  const update = (idx: number, patch: Partial<SqlChainStep>) => {
    onChange(steps.map((s, i) => (i === idx ? { ...s, ...patch } : s)));
  };

  const addStep = () => {
    onChange([
      ...steps,
      { name: `step_${steps.length + 1}`, sql: "", materialize: false },
    ]);
  };

  const removeStep = (idx: number) => {
    onChange(steps.filter((_, i) => i !== idx));
  };

  const moveStep = (idx: number, dir: -1 | 1) => {
    const target = idx + dir;
    if (target < 0 || target >= steps.length) return;
    const next = [...steps];
    [next[idx], next[target]] = [next[target], next[idx]];
    onChange(next);
  };

  // `[[ … ]]` insertion at the caret of a step's SQL textarea.
  const insertToken = (idx: number, mnemonic: string) => {
    const ta = sqlRefs.current[idx];
    const token = `[[ ${mnemonic} ]]`;
    const cur = steps[idx]?.sql ?? "";
    if (!ta) {
      update(idx, { sql: `${cur}${cur && !cur.endsWith(" ") ? " " : ""}${token} ` });
      return;
    }
    const start = ta.selectionStart ?? cur.length;
    const end = ta.selectionEnd ?? cur.length;
    const next = cur.slice(0, start) + token + cur.slice(end);
    update(idx, { sql: next });
    requestAnimationFrame(() => {
      const pos = start + token.length;
      ta.focus();
      ta.setSelectionRange(pos, pos);
    });
  };

  // S9/S10: request the per-step preview (sample → steps 1..idx). Degrades
  // gracefully when the endpoint is absent (backend dep, not built here).
  const runPreview = async (idx: number) => {
    setPreviewByStep((p) => ({ ...p, [idx]: { kind: "loading" } }));
    try {
      const result = await api.post<PreviewResult>(
        // BACKEND DEPENDENCY (S10) — design-time, dev-Spark-on-cached-sample.
        "/api/v1/sql/sql-model/preview",
        {
          versionId,
          instanceId,
          steps: steps.slice(0, idx + 1),
          asOf,
          stepIndex: idx,
        }
      );
      setPreviewByStep((p) => ({ ...p, [idx]: { kind: "ok", result } }));
    } catch (err) {
      // Endpoint not yet implemented or transient — never crash the editor.
      const msg = err instanceof Error ? err.message : "unknown error";
      const notImplemented = /404|not found|not implemented/i.test(msg);
      setPreviewByStep((p) => ({
        ...p,
        [idx]: notImplemented
          ? {
              kind: "unavailable",
              message:
                "Per-step preview unavailable — the dev-Spark preview endpoint is not yet implemented.",
            }
          : { kind: "error", message: msg },
      }));
    }
  };

  return (
    <div className="space-y-3" data-sql-chain-editor>
      <div className="flex items-center gap-2">
        <Label className="text-xs font-medium">SQL Model Steps</Label>
        <Badge variant="secondary" className="text-[9px]">
          Spark SQL dialect
        </Badge>
        {asOf && (
          <span className="text-[10px] text-muted-foreground">
            preview as-of {asOf}
          </span>
        )}
      </div>

      {steps.length === 0 && (
        <p className="text-xs text-muted-foreground">
          No steps yet. Add the first transform step of the chain.
        </p>
      )}

      {steps.map((step, idx) => {
        const preview = previewByStep[idx] ?? { kind: "idle" as const };
        return (
          <div key={idx} className="rounded-md border p-2 space-y-2">
            <div className="flex items-center gap-2">
              <span className="text-[10px] text-muted-foreground w-5 text-center">
                {idx + 1}
              </span>
              <Input
                value={step.name}
                onChange={(e) => update(idx, { name: e.target.value })}
                placeholder="step_name"
                className="h-7 text-xs font-mono w-[180px]"
                aria-label={`Step ${idx + 1} name`}
              />
              <label className="flex items-center gap-1 text-[10px] text-muted-foreground">
                <input
                  type="checkbox"
                  checked={step.materialize}
                  onChange={(e) => update(idx, { materialize: e.target.checked })}
                  aria-label={`Materialize step ${idx + 1}`}
                />
                materialize
              </label>
              <div className="ml-auto flex items-center gap-1">
                <Button
                  variant="ghost"
                  size="sm"
                  className="h-7 px-2 text-muted-foreground"
                  onClick={() => moveStep(idx, -1)}
                  disabled={idx === 0}
                  aria-label={`Move step ${idx + 1} up`}
                >
                  ↑
                </Button>
                <Button
                  variant="ghost"
                  size="sm"
                  className="h-7 px-2 text-muted-foreground"
                  onClick={() => moveStep(idx, 1)}
                  disabled={idx === steps.length - 1}
                  aria-label={`Move step ${idx + 1} down`}
                >
                  ↓
                </Button>
                <Button
                  variant="ghost"
                  size="sm"
                  className="h-7 px-2 text-muted-foreground hover:text-destructive"
                  onClick={() => removeStep(idx)}
                  aria-label={`Remove step ${idx + 1}`}
                >
                  x
                </Button>
              </div>
            </div>

            <textarea
              ref={(el) => {
                sqlRefs.current[idx] = el;
              }}
              value={step.sql}
              onChange={(e) => update(idx, { sql: e.target.value })}
              placeholder="SELECT … FROM input"
              spellCheck={false}
              rows={3}
              className={cn(
                "w-full font-mono text-xs px-2 py-1.5 rounded border bg-background",
                "focus:outline-none focus:ring-1 focus:ring-ring"
              )}
              aria-label={`Step ${idx + 1} SQL`}
            />

            <div className="flex items-center gap-1 flex-wrap">
              <span className="text-[10px] text-muted-foreground mr-1">
                Insert date token:
              </span>
              {INSERT_MNEMONICS.map((m) => (
                <Button
                  key={m}
                  variant="outline"
                  size="sm"
                  className="h-6 px-1.5 text-[10px] font-mono"
                  onClick={() => insertToken(idx, m)}
                  aria-label={`Insert ${m} token into step ${idx + 1}`}
                >
                  [[ {m} ]]
                </Button>
              ))}
              <Button
                variant="outline"
                size="sm"
                className="h-6 px-2 text-[10px] ml-auto"
                onClick={() => runPreview(idx)}
                disabled={preview.kind === "loading"}
              >
                {preview.kind === "loading" ? "Previewing…" : "Preview step"}
              </Button>
            </div>

            {/* S9: per-step data-preview pane */}
            {preview.kind === "unavailable" && (
              <p className="text-[10px] text-amber-700" role="status">
                {preview.message}
              </p>
            )}
            {preview.kind === "error" && (
              <p className="text-[10px] text-destructive" role="alert">
                Preview failed: {preview.message}
              </p>
            )}
            {preview.kind === "ok" && (
              <div className="rounded border bg-muted/40 p-1.5 overflow-x-auto">
                <div className="flex gap-1 mb-1">
                  {preview.result.columns.map((c) => (
                    <span
                      key={c.name}
                      className="text-[9px] font-mono flex items-center gap-0.5"
                    >
                      {c.name}
                      <span className={cn("text-[8px] px-0.5 rounded", getTypeColor(c.type))}>
                        {c.type}
                      </span>
                    </span>
                  ))}
                </div>
                <table className="text-[10px] font-mono">
                  <tbody>
                    {preview.result.rows.slice(0, 10).map((r, ri) => (
                      <tr key={ri}>
                        {preview.result.columns.map((c) => (
                          <td key={c.name} className="px-1.5 py-0.5 border-t">
                            {String(r[c.name] ?? "")}
                          </td>
                        ))}
                      </tr>
                    ))}
                  </tbody>
                </table>
                {preview.result.truncated && (
                  <p className="text-[9px] text-muted-foreground mt-1">
                    sample truncated
                  </p>
                )}
              </div>
            )}
          </div>
        );
      })}

      <div className="flex items-center gap-2">
        <Button variant="outline" size="sm" className="text-xs" onClick={addStep}>
          + Add Step
        </Button>
      </div>

      {/* Shared Spark-function palette (G-1: the ONE registry; imported, not forked). */}
      <details className="border rounded-md">
        <summary className="px-3 py-1.5 text-xs font-medium cursor-pointer">
          Spark SQL Functions
        </summary>
        <div className="border-t p-2">
          <Select defaultValue={SPARK_FUNCTIONS[0].category}>
            <SelectTrigger className="h-7 text-xs w-[180px]">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {SPARK_FUNCTIONS.map((cat) => (
                <SelectItem key={cat.category} value={cat.category} className="text-xs">
                  {cat.category}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <p className="text-[10px] text-muted-foreground mt-1">
            {SPARK_FUNCTIONS.reduce((n, c) => n + c.functions.length, 0)} functions
            available — the same registry the condition-builder uses.
          </p>
        </div>
      </details>

      {inputColumns && inputColumns.length > 0 && (
        <div className="flex flex-wrap gap-1">
          <span className="text-[10px] text-muted-foreground mr-1">Input columns:</span>
          {inputColumns.map((c) => (
            <Badge key={c.name} variant="secondary" className="text-[9px] font-mono gap-0.5">
              {c.name}
              <span className={cn("text-[8px] px-0.5 rounded", getTypeColor(c.type))}>
                {c.type}
              </span>
            </Badge>
          ))}
        </div>
      )}
    </div>
  );
}
