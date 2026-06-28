"use client";

/**
 * RenameMapper (S4, W-3 / W-4, SPEC-construct-library.md §4).
 *
 * A general two-column-map control: edit an `old-column → new-value` MAP as a
 * two-column table instead of hand-typing JSON. The LEFT (source) cell is the
 * shared schema-bound `ColumnPicker` (reused, NOT re-implemented); the RIGHT
 * (target) cell is PARAMETERIZED so siblings reuse this one control:
 *
 *   rightCell="text"   (default) — free-text Input for the new column name
 *                                  (rename_map / mapping_rules).
 *   rightCell="select"           — a <Select> of types (type_coercions /
 *                                  type-cast-mapper). Default type list is
 *                                  overridable via `rightOptions`.
 *   rightCell="value"            — a plain Input for an arbitrary value
 *                                  (fill_null_map / key-value-mapper).
 *
 * EDITING MODEL: a map can't hold two empty/duplicate source keys mid-edit, so
 * internal `rows` state (ordered {source,target}[]) is the editing source of
 * truth. We derive/emit the map from rows on every edit, and re-sync rows from
 * the incoming `value` prop only when it changes from the OUTSIDE (a useEffect
 * keyed on JSON.stringify(value)). This keeps two rows with the same/empty
 * source from collapsing in the UI.
 */

import { useState, useEffect, useRef } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { cn } from "@/lib/utils";
import { ColumnPicker } from "./column-picker";
import type { SchemaColumn } from "@/types";

/** Default type list for rightCell="select" (overridable via rightOptions). */
const DEFAULT_TYPE_OPTIONS = [
  "string",
  "integer",
  "long",
  "double",
  "decimal",
  "boolean",
  "date",
  "timestamp",
];

interface MapRow {
  source: string;
  target: string;
}

export interface RenameMapperProps {
  columns: SchemaColumn[];
  value: Record<string, string>;
  onChange: (value: Record<string, string>) => void;
  rightCell?: "text" | "select" | "value";
  rightOptions?: string[];
  /** Forwarded to the ColumnPicker source cell (W-2 loading skeleton). */
  loading?: boolean;
  sourcePlaceholder?: string;
  targetPlaceholder?: string;
}

/** Derive an ordered row list from the incoming map. */
function rowsFromValue(value: Record<string, string>): MapRow[] {
  return Object.entries(value).map(([source, target]) => ({ source, target }));
}

/**
 * Build the emitted map from the editing rows. Rows with an empty source can't
 * be a map key, so they are dropped from the emitted map (but kept in the UI
 * via internal state). Last write wins on duplicate sources.
 */
function valueFromRows(rows: MapRow[]): Record<string, string> {
  const map: Record<string, string> = {};
  for (const row of rows) {
    if (row.source.trim() === "") continue;
    map[row.source] = row.target;
  }
  return map;
}

export function RenameMapper({
  columns,
  value,
  onChange,
  rightCell = "text",
  rightOptions,
  loading,
  sourcePlaceholder = "Source column…",
  targetPlaceholder,
}: RenameMapperProps) {
  // Internal rows are the editing source of truth (see file header). Empty map
  // → start with one blank row so there's always an editable target.
  const [rows, setRows] = useState<MapRow[]>(() => {
    const initial = rowsFromValue(value);
    return initial.length > 0 ? initial : [{ source: "", target: "" }];
  });

  // Re-sync internal rows from `value` ONLY when it changes from the OUTSIDE.
  // We track the last map WE emitted so our own onChange round-trips don't
  // clobber in-progress edits (e.g. a half-typed duplicate/blank source).
  const lastEmitted = useRef<string>(JSON.stringify(value));
  const valueKey = JSON.stringify(value);
  useEffect(() => {
    if (valueKey === lastEmitted.current) return;
    lastEmitted.current = valueKey;
    const next = rowsFromValue(value);
    setRows(next.length > 0 ? next : [{ source: "", target: "" }]);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [valueKey]);

  const emit = (next: MapRow[]) => {
    setRows(next);
    const map = valueFromRows(next);
    lastEmitted.current = JSON.stringify(map);
    onChange(map);
  };

  const updateRow = (idx: number, field: keyof MapRow, val: string) => {
    emit(rows.map((r, i) => (i === idx ? { ...r, [field]: val } : r)));
  };

  const addRow = () => {
    emit([...rows, { source: "", target: "" }]);
  };

  const removeRow = (idx: number) => {
    const next = rows.filter((_, i) => i !== idx);
    // Always keep at least one blank row to edit into.
    emit(next.length > 0 ? next : [{ source: "", target: "" }]);
  };

  // ---- Per-row validation (W-3 / W-4): duplicate source, duplicate target,
  // empty target on a row that has a source. Build the dup counts once. ----
  const sourceCounts = new Map<string, number>();
  const targetCounts = new Map<string, number>();
  for (const r of rows) {
    const s = r.source.trim();
    const t = r.target.trim();
    if (s !== "") sourceCounts.set(s, (sourceCounts.get(s) ?? 0) + 1);
    if (t !== "") targetCounts.set(t, (targetCounts.get(t) ?? 0) + 1);
  }

  const rowError = (row: MapRow): string | null => {
    const s = row.source.trim();
    const t = row.target.trim();
    if (s !== "" && (sourceCounts.get(s) ?? 0) > 1) {
      return "Duplicate source column";
    }
    if (t !== "" && (targetCounts.get(t) ?? 0) > 1) {
      return "Duplicate target name";
    }
    if (s !== "" && t === "") {
      return "Target is required";
    }
    return null;
  };

  const typeOptions = rightOptions ?? DEFAULT_TYPE_OPTIONS;
  const resolvedTargetPlaceholder =
    targetPlaceholder ??
    (rightCell === "value" ? "Value…" : "New column name…");

  return (
    <div className="space-y-2">
      <div className="space-y-1.5">
        {rows.map((row, idx) => {
          const error = rowError(row);
          return (
            <div key={idx} className="space-y-0.5">
              <div className="flex items-center gap-2">
                {/* LEFT (source) cell — reuse the shared ColumnPicker. */}
                <ColumnPicker
                  columns={columns}
                  value={row.source}
                  onChange={(v) => updateRow(idx, "source", v)}
                  placeholder={sourcePlaceholder}
                  loading={loading}
                  className="w-[160px]"
                />

                <span className="text-muted-foreground text-xs shrink-0">
                  →
                </span>

                {/* RIGHT (target) cell — parameterized by rightCell. */}
                {rightCell === "select" ? (
                  <Select
                    value={row.target || undefined}
                    onValueChange={(v) => updateRow(idx, "target", v)}
                  >
                    <SelectTrigger
                      size="sm"
                      className="w-[160px] flex-1 text-xs"
                      aria-invalid={error ? true : undefined}
                    >
                      <SelectValue
                        placeholder={targetPlaceholder ?? "Type…"}
                      />
                    </SelectTrigger>
                    <SelectContent>
                      {typeOptions.map((opt) => (
                        <SelectItem key={opt} value={opt} className="text-xs">
                          {opt}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                ) : (
                  <Input
                    value={row.target}
                    onChange={(e) => updateRow(idx, "target", e.target.value)}
                    placeholder={resolvedTargetPlaceholder}
                    aria-invalid={error ? true : undefined}
                    className={cn(
                      "h-8 flex-1 text-xs",
                      rightCell === "text" && "font-mono"
                    )}
                  />
                )}

                {/* Remove (x) — mirrors the sql-filter-builder ghost idiom. */}
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  className="h-8 px-2 text-muted-foreground hover:text-destructive shrink-0"
                  aria-label="Remove mapping"
                  onClick={() => removeRow(idx)}
                >
                  x
                </Button>
              </div>
              {error && (
                <p className="text-destructive text-[10px] pl-[168px]">
                  {error}
                </p>
              )}
            </div>
          );
        })}
      </div>

      <Button
        type="button"
        variant="outline"
        size="sm"
        className="text-xs"
        onClick={addRow}
      >
        + Add mapping
      </Button>
    </div>
  );
}
