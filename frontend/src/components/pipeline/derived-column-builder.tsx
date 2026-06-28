"use client";

/**
 * DerivedColumnBuilder — the `derived-column-builder` #5 construct.
 *
 * Edits the `derived_columns` param (object[]) used by the universal
 * SchemaPropagationService.addenda. Each entry is:
 *   { name: string, type: string, expression: string, nullable?: boolean, description?: string }
 *
 * Reuses the existing ExpressionInput for per-column expression editing with
 * live validation, date-token insertion, and schema-aware column detection.
 */

import { useState, useCallback } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { ExpressionInput, type ExpressionInputPort } from "./expression-input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Trash2, Plus, ChevronDown, ChevronRight } from "lucide-react";
import { cn } from "@/lib/utils";

const COMMON_TYPES = [
  "string", "varchar", "long", "bigint", "integer", "int",
  "double", "float", "decimal", "boolean", "date", "timestamp",
];

export interface DerivedColumnEntry {
  name: string;
  type: string;
  expression: string;
  nullable?: boolean;
  description?: string;
}

interface DerivedColumnBuilderProps {
  value: string;
  onChange: (next: string) => void;
  inputSchemas: ExpressionInputPort[];
}

function parseEntries(value: string): DerivedColumnEntry[] {
  if (!value || value.trim().length === 0) return [];
  try {
    const parsed = JSON.parse(value);
    if (!Array.isArray(parsed)) return [];
    return parsed.map((e: Record<string, unknown>) => ({
      name: typeof e.name === "string" ? e.name : "",
      type: typeof e.type === "string" ? e.type : "string",
      expression: typeof e.expression === "string" ? e.expression : "",
      nullable: e.nullable !== false,
      description: typeof e.description === "string" ? e.description : "",
    }));
  } catch {
    return [];
  }
}

function serializeEntries(entries: DerivedColumnEntry[]): string {
  return JSON.stringify(
    entries.map((e) => {
      const obj: Record<string, unknown> = {
        name: e.name,
        type: e.type || "string",
        expression: e.expression,
      };
      if (e.nullable === false) obj.nullable = false;
      if (e.description) obj.description = e.description;
      return obj;
    }),
    null,
    2
  );
}

export function DerivedColumnBuilder({
  value,
  onChange,
  inputSchemas,
}: DerivedColumnBuilderProps) {
  const [entries, setEntries] = useState<DerivedColumnEntry[]>(() => parseEntries(value));
  const [expandedIndex, setExpandedIndex] = useState<number | null>(
    entries.length > 0 ? 0 : null
  );

  const sync = useCallback(
    (next: DerivedColumnEntry[]) => {
      setEntries(next);
      onChange(serializeEntries(next));
    },
    [onChange]
  );

  const addEntry = () => {
    const next = [
      ...entries,
      { name: "", type: "string", expression: "", nullable: true, description: "" },
    ];
    sync(next);
    setExpandedIndex(next.length - 1);
  };

  const removeEntry = (idx: number) => {
    const next = entries.filter((_, i) => i !== idx);
    sync(next);
    if (expandedIndex !== null && expandedIndex >= next.length) {
      setExpandedIndex(next.length > 0 ? next.length - 1 : null);
    }
  };

  const updateEntry = (idx: number, patch: Partial<DerivedColumnEntry>) => {
    const next = entries.map((e, i) => (i === idx ? { ...e, ...patch } : e));
    sync(next);
  };

  return (
    <div className="space-y-2">
      <div className="flex items-center gap-2">
        <span className="text-xs font-medium text-muted-foreground">
          Derived columns ({entries.length})
        </span>
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={addEntry}
          className="h-6 text-[11px] gap-1"
        >
          <Plus className="size-3" />
          Add Column
        </Button>
      </div>

      {entries.length === 0 && (
        <p className="text-[11px] text-muted-foreground">
          No derived columns. Click &quot;Add Column&quot; to define a computed column
          with a SQL expression.
        </p>
      )}

      {entries.map((entry, idx) => {
        const expanded = expandedIndex === idx;
        const hasName = entry.name.trim().length > 0;
        return (
          <div
            key={idx}
            className={cn(
              "rounded border bg-background",
              expanded ? "p-3" : "px-3 py-1.5"
            )}
          >
            {/* Collapsed/expanded header */}
            <div
              className="flex items-center gap-2 cursor-pointer"
              onClick={() => setExpandedIndex(expanded ? null : idx)}
            >
              {expanded ? (
                <ChevronDown className="size-3.5 text-muted-foreground" />
              ) : (
                <ChevronRight className="size-3.5 text-muted-foreground" />
              )}
              <span
                className={cn(
                  "text-xs font-mono",
                  hasName ? "text-foreground" : "text-muted-foreground italic"
                )}
              >
                {hasName ? entry.name : "unnamed column"}
              </span>
              {!expanded && entry.type && (
                <span className="text-[10px] text-muted-foreground">
                  {entry.type}
                </span>
              )}
              {!expanded && entry.expression && (
                <span className="text-[10px] text-muted-foreground truncate max-w-[200px]">
                  = {entry.expression}
                </span>
              )}
              <Button
                type="button"
                variant="ghost"
                size="sm"
                onClick={(e) => {
                  e.stopPropagation();
                  removeEntry(idx);
                }}
                className="ml-auto h-5 w-5 p-0 text-muted-foreground hover:text-destructive"
              >
                <Trash2 className="size-3" />
              </Button>
            </div>

            {/* Expanded body */}
            {expanded && (
              <div className="mt-2 space-y-2">
                <div className="grid grid-cols-[1fr_140px] gap-2">
                  <div>
                    <Label className="text-[11px] text-muted-foreground">
                      Column name
                    </Label>
                    <Input
                      value={entry.name}
                      onChange={(e) =>
                        updateEntry(idx, { name: e.target.value })
                      }
                      placeholder="loan_age_days"
                      className="h-7 text-xs"
                    />
                  </div>
                  <div>
                    <Label className="text-[11px] text-muted-foreground">
                      Output type
                    </Label>
                    <Select
                      value={entry.type || "string"}
                      onValueChange={(v) => updateEntry(idx, { type: v })}
                    >
                      <SelectTrigger className="h-7 text-xs">
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        {COMMON_TYPES.map((t) => (
                          <SelectItem key={t} value={t}>
                            {t}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  </div>
                </div>
                <div>
                  <Label className="text-[11px] text-muted-foreground">
                    SQL expression
                  </Label>
                  <ExpressionInput
                    value={entry.expression}
                    onChange={(next) => updateEntry(idx, { expression: next })}
                    kind="value"
                    expectedType={entry.type || undefined}
                    inputSchemas={inputSchemas}
                    placeholder="datediff(current_date, origination_date)"
                    rows={2}
                  />
                </div>
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}
