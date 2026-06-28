"use client";

import { useState } from "react";
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
import { Badge } from "@/components/ui/badge";
import { Textarea } from "@/components/ui/textarea";
import { cn } from "@/lib/utils";
import { ColumnPicker, getTypeColor } from "./column-picker";
import { MnemonicDateInput } from "./mnemonic-date-input";
import { SPARK_FUNCTIONS } from "./spark-functions";
import type { SchemaColumn } from "@/types";

export interface FilterCondition {
  column: string;
  operator: string;
  value: string;
  logic: "AND" | "OR";
  /**
   * W-5/W-11: per-value cell mode. "literal" (default) → a plain value Input;
   * "mnemonic" → an embedded `date-mnemonic-picker` cell so the user can pin a
   * business-date mnemonic (PBD, BOM-1, …) directly on a condition value.
   * This is the visual-row date path; `[[ … ]]` tokens are raw-SQL-mode only.
   */
  valueKind?: "literal" | "mnemonic";
}

const OPERATORS = [
  { value: "eq", label: "=" },
  { value: "neq", label: "<>" },
  { value: "gt", label: ">" },
  { value: "gte", label: ">=" },
  { value: "lt", label: "<" },
  { value: "lte", label: "<=" },
  { value: "like", label: "LIKE" },
  { value: "not_like", label: "NOT LIKE" },
  { value: "rlike", label: "RLIKE (regex)" },
  { value: "in", label: "IN" },
  { value: "not_in", label: "NOT IN" },
  { value: "between", label: "BETWEEN" },
  { value: "is_null", label: "IS NULL" },
  { value: "is_not_null", label: "IS NOT NULL" },
];

const NULLARY_OPS = new Set(["is_null", "is_not_null"]);

// Spark SQL function catalog now lives in the shared registry (G-1):
// `./spark-functions`. Imported above — do NOT re-inline a copy here.

interface SqlFilterBuilderProps {
  conditions: FilterCondition[];
  rawSql: string;
  filterMode: "visual" | "sql";
  availableColumns: string[];
  columnSchema?: SchemaColumn[];
  onChange: (update: {
    conditions: FilterCondition[];
    rawSql: string;
    filterMode: "visual" | "sql";
  }) => void;
}

export function SqlFilterBuilder({
  conditions,
  rawSql,
  filterMode,
  availableColumns,
  columnSchema,
  onChange,
}: SqlFilterBuilderProps) {
  const hasSchema = columnSchema && columnSchema.length > 0;
  const [mode, setMode] = useState<"visual" | "sql">(filterMode);
  const [showFunctions, setShowFunctions] = useState(false);
  const [fnCategory, setFnCategory] = useState(SPARK_FUNCTIONS[0].category);

  const addCondition = () => {
    const updated = [
      ...conditions,
      { column: "", operator: "eq", value: "", logic: "AND" as const },
    ];
    onChange({ conditions: updated, rawSql, filterMode: mode });
  };

  const removeCondition = (idx: number) => {
    const updated = conditions.filter((_, i) => i !== idx);
    onChange({ conditions: updated, rawSql, filterMode: mode });
  };

  const updateCondition = (
    idx: number,
    field: keyof FilterCondition,
    val: string
  ) => {
    const updated = conditions.map((c, i) =>
      i === idx ? { ...c, [field]: val } : c
    );
    onChange({ conditions: updated, rawSql, filterMode: mode });
  };

  // W-5/W-11: toggle a row's value cell between a literal Input and the
  // embedded date-mnemonic-picker (clearing the value on switch so a literal
  // doesn't leak into mnemonic mode and vice-versa).
  const setValueKind = (idx: number, kind: "literal" | "mnemonic") => {
    const updated = conditions.map((c, i) =>
      i === idx ? { ...c, valueKind: kind, value: "" } : c
    );
    onChange({ conditions: updated, rawSql, filterMode: mode });
  };

  // W-6: visual rows are structurally-valid-by-construction (the column cell is
  // schema-bound, the operator is enumerated). The ONLY visual error is an empty
  // value on a non-nullary operator — surface it inline.
  const rowError = (c: FilterCondition): string | null => {
    if (NULLARY_OPS.has(c.operator)) return null;
    if (!c.value.trim()) return "Value required for this operator";
    return null;
  };

  const switchMode = (newMode: "visual" | "sql") => {
    setMode(newMode);
    onChange({ conditions, rawSql, filterMode: newMode });
  };

  const insertFunction = (signature: string) => {
    if (mode === "sql") {
      onChange({ conditions, rawSql: rawSql + signature, filterMode: mode });
    }
  };

  const previewCondition = (c: FilterCondition): string => {
    const op = OPERATORS.find((o) => o.value === c.operator)?.label || c.operator;
    if (NULLARY_OPS.has(c.operator)) return `${c.column} ${op}`;
    // A mnemonic value is a date token, not a string literal — render unquoted.
    const isMnemonic = c.valueKind === "mnemonic";
    const v = isMnemonic ? c.value : `'${c.value}'`;
    if (c.operator === "between") return `${c.column} BETWEEN ${c.value}`;
    if (c.operator === "rlike") return `${c.column} RLIKE '${c.value}'`;
    return `${c.column} ${op} ${v}`;
  };

  const preview = conditions
    .map((c, i) => {
      const clause = previewCondition(c);
      return i === 0 ? clause : `${c.logic} ${clause}`;
    })
    .join("\n  ");

  return (
    <div className="space-y-3">
      <div className="flex items-center gap-2">
        <Label className="text-xs font-medium">Filter Mode</Label>
        <div className="flex rounded-md border text-xs">
          <button
            className={`px-3 py-1 rounded-l-md transition-colors ${mode === "visual" ? "bg-primary text-primary-foreground" : "hover:bg-muted"}`}
            onClick={() => switchMode("visual")}
          >
            Visual Builder
          </button>
          <button
            className={`px-3 py-1 rounded-r-md transition-colors ${mode === "sql" ? "bg-primary text-primary-foreground" : "hover:bg-muted"}`}
            onClick={() => switchMode("sql")}
          >
            Spark SQL
          </button>
        </div>
        <Badge variant="secondary" className="text-[9px]">Spark SQL dialect</Badge>
      </div>

      {mode === "visual" ? (
        <div className="space-y-2">
          {conditions.map((cond, idx) => (
            <div key={idx} className="flex items-start gap-2">
              {idx > 0 && (
                <Select
                  value={cond.logic}
                  onValueChange={(v) => updateCondition(idx, "logic", v)}
                >
                  <SelectTrigger className="w-[70px] h-8 text-xs">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="AND">AND</SelectItem>
                    <SelectItem value="OR">OR</SelectItem>
                  </SelectContent>
                </Select>
              )}
              {idx === 0 && <span className="w-[70px] text-xs text-muted-foreground text-center">WHERE</span>}

              {hasSchema ? (
                <ColumnPicker
                  value={cond.column}
                  onChange={(v) => updateCondition(idx, "column", v)}
                  columns={columnSchema}
                  placeholder="Column…"
                  className="w-[160px]"
                />
              ) : availableColumns.length > 0 ? (
                <Select
                  value={cond.column}
                  onValueChange={(v) => updateCondition(idx, "column", v)}
                >
                  <SelectTrigger className="w-[160px] h-8 text-xs">
                    <SelectValue placeholder="Column..." />
                  </SelectTrigger>
                  <SelectContent>
                    {availableColumns.map((col) => (
                      <SelectItem key={col} value={col}>
                        {col}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              ) : (
                <Input
                  value={cond.column}
                  onChange={(e) => updateCondition(idx, "column", e.target.value)}
                  placeholder="column_name"
                  className="w-[160px] h-8 text-xs font-mono"
                />
              )}

              <Select
                value={cond.operator}
                onValueChange={(v) => updateCondition(idx, "operator", v)}
              >
                <SelectTrigger className="w-[140px] h-8 text-xs">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {OPERATORS.map((op) => (
                    <SelectItem key={op.value} value={op.value}>
                      {op.label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>

              {!NULLARY_OPS.has(cond.operator) && (
                <div className="flex-1 flex flex-col gap-1 min-w-0">
                  <div className="flex items-center gap-1">
                    {/* W-5/W-11: literal vs date-mnemonic value cell toggle */}
                    <div className="flex rounded-md border text-[10px] shrink-0">
                      <button
                        type="button"
                        aria-pressed={cond.valueKind !== "mnemonic"}
                        className={cn(
                          "px-1.5 py-1 rounded-l-md transition-colors",
                          cond.valueKind !== "mnemonic"
                            ? "bg-primary text-primary-foreground"
                            : "hover:bg-muted"
                        )}
                        onClick={() => setValueKind(idx, "literal")}
                        title="Literal value"
                      >
                        abc
                      </button>
                      <button
                        type="button"
                        aria-pressed={cond.valueKind === "mnemonic"}
                        className={cn(
                          "px-1.5 py-1 rounded-r-md transition-colors",
                          cond.valueKind === "mnemonic"
                            ? "bg-primary text-primary-foreground"
                            : "hover:bg-muted"
                        )}
                        onClick={() => setValueKind(idx, "mnemonic")}
                        title="Date mnemonic"
                      >
                        date
                      </button>
                    </div>

                    {cond.valueKind === "mnemonic" ? (
                      <div className="flex-1 min-w-0">
                        <MnemonicDateInput
                          id={`cond-${idx}-date`}
                          value={cond.value}
                          onChange={(v) => updateCondition(idx, "value", v)}
                          embedded
                        />
                      </div>
                    ) : (
                      <Input
                        value={cond.value}
                        onChange={(e) =>
                          updateCondition(idx, "value", e.target.value)
                        }
                        placeholder={
                          cond.operator === "between"
                            ? "low AND high"
                            : cond.operator === "in"
                              ? "'a','b','c'"
                              : "value"
                        }
                        className="flex-1 h-8 text-xs font-mono min-w-0"
                      />
                    )}
                  </div>
                  {rowError(cond) && (
                    <p className="text-[10px] text-destructive" role="alert">
                      {rowError(cond)}
                    </p>
                  )}
                </div>
              )}

              <Button
                variant="ghost"
                size="sm"
                className="h-8 px-2 text-muted-foreground hover:text-destructive shrink-0"
                onClick={() => removeCondition(idx)}
              >
                x
              </Button>
            </div>
          ))}

          <Button
            variant="outline"
            size="sm"
            className="text-xs"
            onClick={addCondition}
          >
            + Add Condition
          </Button>

          {conditions.length > 0 && (
            <div className="rounded-md bg-muted/50 p-2 font-mono text-xs">
              <span className="text-muted-foreground">WHERE </span>
              {preview}
            </div>
          )}
        </div>
      ) : (
        <div className="space-y-2">
          <Textarea
            value={rawSql}
            onChange={(e) =>
              onChange({ conditions, rawSql: e.target.value, filterMode: mode })
            }
            placeholder="e.g. UPPER(status) = 'ACTIVE' AND CAST(amount AS DOUBLE) > 1000.0"
            className="font-mono text-xs min-h-[100px]"
          />
          <p className="text-[10px] text-muted-foreground">
            Write a Spark SQL WHERE clause. Do not include the WHERE keyword. Click functions below to insert.
          </p>
        </div>
      )}

      {/* Spark Function Catalog */}
      <div className="border rounded-md">
        <button
          className="w-full flex items-center justify-between px-3 py-1.5 text-xs font-medium hover:bg-muted/50"
          onClick={() => setShowFunctions(!showFunctions)}
        >
          <span>Spark SQL Functions</span>
          <span className="text-muted-foreground">{showFunctions ? "hide" : "show"}</span>
        </button>
        {showFunctions && (
          <div className="border-t p-2 space-y-2">
            <div className="flex flex-wrap gap-1">
              {SPARK_FUNCTIONS.map((cat) => (
                <Badge
                  key={cat.category}
                  variant={fnCategory === cat.category ? "default" : "outline"}
                  className="text-[10px] cursor-pointer"
                  onClick={() => setFnCategory(cat.category)}
                >
                  {cat.category}
                </Badge>
              ))}
            </div>
            <div className="grid grid-cols-2 gap-1 max-h-[200px] overflow-y-auto">
              {SPARK_FUNCTIONS.find((c) => c.category === fnCategory)?.functions.map(
                (fn) => (
                  <button
                    key={fn.name}
                    className="text-left rounded px-2 py-1 text-xs hover:bg-muted/70 group"
                    onClick={() => insertFunction(fn.signature)}
                    title={fn.description}
                  >
                    <span className="font-mono text-primary">{fn.name}</span>
                    <span className="text-[9px] text-muted-foreground ml-1 group-hover:text-foreground">
                      {fn.description}
                    </span>
                  </button>
                )
              )}
            </div>
          </div>
        )}
      </div>

      {hasSchema ? (
        <div className="flex flex-wrap gap-1">
          <span className="text-[10px] text-muted-foreground mr-1">Columns:</span>
          {columnSchema.map((col) => (
            <Badge
              key={col.name}
              variant="secondary"
              className="text-[9px] font-mono cursor-pointer hover:bg-primary/20 gap-0.5"
              onClick={() => {
                if (mode === "sql") {
                  onChange({ conditions, rawSql: rawSql + col.name, filterMode: mode });
                }
              }}
            >
              {col.name}
              <span
                className={cn(
                  "text-[8px] px-0.5 rounded",
                  getTypeColor(col.type)
                )}
              >
                {col.type}
              </span>
            </Badge>
          ))}
        </div>
      ) : availableColumns.length > 0 ? (
        <div className="flex flex-wrap gap-1">
          <span className="text-[10px] text-muted-foreground mr-1">Columns:</span>
          {availableColumns.map((col) => (
            <Badge
              key={col}
              variant="secondary"
              className="text-[9px] font-mono cursor-pointer hover:bg-primary/20"
              onClick={() => {
                if (mode === "sql") {
                  onChange({ conditions, rawSql: rawSql + col, filterMode: mode });
                }
              }}
            >
              {col}
            </Badge>
          ))}
        </div>
      ) : null}
    </div>
  );
}
