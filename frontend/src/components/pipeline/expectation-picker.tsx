"use client";

import { useState, useMemo } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { ScrollArea } from "@/components/ui/scroll-area";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { cn } from "@/lib/utils";
import { api } from "@/lib/api";
import { ColumnPicker, MultiColumnPicker } from "./column-picker";
import type { DqExpectation, DqSeverity, SchemaColumn } from "@/types";

/* ── Column-aware param detection (LCT-035) ──────────────── */

/** Param names that select a single column. */
const SINGLE_COLUMN_PARAMS = new Set(["column", "column_A", "column_B"]);
/** Param names that select a list of columns. */
const MULTI_COLUMN_PARAMS = new Set(["column_list", "column_set"]);

/* ── Expectation Catalog ─────────────────────────────────── */

interface CatalogEntry {
  gxClass: string;
  displayName: string;
  description: string;
  params: ParamDef[];
}

interface ParamDef {
  name: string;
  label: string;
  type: "string" | "number" | "string[]" | "boolean";
  required?: boolean;
  placeholder?: string;
}

const col: ParamDef = { name: "column", label: "Column", type: "string", required: true, placeholder: "column_name" };
const mostly: ParamDef = { name: "mostly", label: "Tolerance (0-1)", type: "number", placeholder: "0.95" };
const minVal: ParamDef = { name: "min_value", label: "Min Value", type: "number" };
const maxVal: ParamDef = { name: "max_value", label: "Max Value", type: "number" };
const valSet: ParamDef = { name: "value_set", label: "Value Set (comma-separated)", type: "string[]", placeholder: "a, b, c" };
const colList: ParamDef = { name: "column_list", label: "Columns (comma-separated)", type: "string[]", placeholder: "col1, col2" };

type Category = "Completeness" | "Uniqueness" | "Schema" | "Range" | "Pattern" | "Validity" | "Cross-Column" | "Custom";

const CATALOG: Record<Category, CatalogEntry[]> = {
  Completeness: [
    { gxClass: "ExpectColumnValuesToNotBeNull", displayName: "Column Not Null", description: "Expect values in this column to not be null", params: [col, mostly] },
    { gxClass: "ExpectColumnValuesToBeNull", displayName: "Column All Null", description: "Expect values in this column to be null", params: [col, mostly] },
    { gxClass: "ExpectColumnProportionOfNonNullValuesToBeBetween", displayName: "Non-Null Ratio", description: "Expect proportion of non-null values to be in a range", params: [col, minVal, maxVal] },
    { gxClass: "ExpectTableRowCountToBeBetween", displayName: "Row Count Range", description: "Expect the table to have between N and M rows", params: [minVal, maxVal] },
    { gxClass: "ExpectTableRowCountToEqual", displayName: "Row Count Exact", description: "Expect the table to have exactly N rows", params: [{ name: "value", label: "Expected Count", type: "number", required: true }] },
  ],
  Uniqueness: [
    { gxClass: "ExpectColumnValuesToBeUnique", displayName: "Column Unique", description: "Expect all values in this column to be unique", params: [col, mostly] },
    { gxClass: "ExpectCompoundColumnsToBeUnique", displayName: "Compound Key Unique", description: "Expect the combination of columns to be unique", params: [colList] },
    { gxClass: "ExpectSelectColumnValuesToBeUniqueWithinRecord", displayName: "Row-Level Uniqueness", description: "Expect selected column values to be unique within each row", params: [colList] },
    { gxClass: "ExpectColumnUniqueValueCountToBeBetween", displayName: "Unique Count Range", description: "Expect the number of unique values to be in a range", params: [col, minVal, maxVal] },
    { gxClass: "ExpectColumnProportionOfUniqueValuesToBeBetween", displayName: "Unique Ratio", description: "Expect the proportion of unique values to be in a range", params: [col, minVal, maxVal] },
    { gxClass: "ExpectColumnDistinctValuesToEqualSet", displayName: "Distinct Values Exact Set", description: "Expect distinct values to exactly equal a given set", params: [col, valSet] },
    { gxClass: "ExpectColumnDistinctValuesToContainSet", displayName: "Distinct Values Contain Set", description: "Expect distinct values to contain a specified set", params: [col, valSet] },
  ],
  Schema: [
    { gxClass: "ExpectColumnToExist", displayName: "Column Exists", description: "Expect a specified column to exist in the table", params: [col] },
    { gxClass: "ExpectTableColumnsToMatchOrderedList", displayName: "Columns Match Ordered List", description: "Expect columns to exactly match a specified ordered list", params: [colList] },
    { gxClass: "ExpectTableColumnsToMatchSet", displayName: "Columns Match Set", description: "Expect columns to match an unordered set", params: [{ name: "column_set", label: "Column Set (comma-separated)", type: "string[]", placeholder: "col1, col2" }, { name: "exact_match", label: "Exact Match", type: "boolean" }] },
    { gxClass: "ExpectTableColumnCountToEqual", displayName: "Column Count Exact", description: "Expect the table to have exactly N columns", params: [{ name: "value", label: "Expected Count", type: "number", required: true }] },
    { gxClass: "ExpectTableColumnCountToBeBetween", displayName: "Column Count Range", description: "Expect the number of columns to be in a range", params: [minVal, maxVal] },
    { gxClass: "ExpectColumnValuesToBeOfType", displayName: "Column Type", description: "Expect column values to be of a specified data type", params: [col, { name: "type_", label: "Data Type", type: "string", required: true, placeholder: "StringType" }] },
    { gxClass: "ExpectColumnValuesToBeInTypeList", displayName: "Column Type In List", description: "Expect column values to be from a list of types", params: [col, { name: "type_list", label: "Type List (comma-separated)", type: "string[]", placeholder: "StringType, IntegerType" }] },
  ],
  Range: [
    { gxClass: "ExpectColumnValuesToBeBetween", displayName: "Values In Range", description: "Expect column values to be between min and max", params: [col, minVal, maxVal, mostly] },
    { gxClass: "ExpectColumnMaxToBeBetween", displayName: "Column Max Range", description: "Expect the column maximum to be within a range", params: [col, minVal, maxVal] },
    { gxClass: "ExpectColumnMinToBeBetween", displayName: "Column Min Range", description: "Expect the column minimum to be within a range", params: [col, minVal, maxVal] },
    { gxClass: "ExpectColumnMeanToBeBetween", displayName: "Column Mean Range", description: "Expect the column mean to be within a range", params: [col, minVal, maxVal] },
    { gxClass: "ExpectColumnMedianToBeBetween", displayName: "Column Median Range", description: "Expect the column median to be within a range", params: [col, minVal, maxVal] },
    { gxClass: "ExpectColumnStdevToBeBetween", displayName: "Column Std Dev Range", description: "Expect the column standard deviation to be in a range", params: [col, minVal, maxVal] },
    { gxClass: "ExpectColumnSumToBeBetween", displayName: "Column Sum Range", description: "Expect the column sum to be within a range", params: [col, minVal, maxVal] },
    { gxClass: "ExpectColumnZScoresToBeLessThan", displayName: "Z-Score Threshold", description: "Expect column Z-scores to be below a threshold", params: [col, { name: "threshold", label: "Threshold", type: "number", required: true }, mostly] },
  ],
  Pattern: [
    { gxClass: "ExpectColumnValuesToMatchRegex", displayName: "Matches Regex", description: "Expect column values to match a regular expression", params: [col, { name: "regex", label: "Regex Pattern", type: "string", required: true, placeholder: "^[a-z]+$" }, mostly] },
    { gxClass: "ExpectColumnValuesToNotMatchRegex", displayName: "Does Not Match Regex", description: "Expect column values to NOT match a regex", params: [col, { name: "regex", label: "Regex Pattern", type: "string", required: true }, mostly] },
    { gxClass: "ExpectColumnValuesToMatchRegexList", displayName: "Matches Any Regex In List", description: "Expect values to match at least one regex from a list", params: [col, { name: "regex_list", label: "Regex List (comma-separated)", type: "string[]", placeholder: "^a.*$, ^b.*$" }] },
    { gxClass: "ExpectColumnValuesToMatchLikePattern", displayName: "Matches LIKE Pattern", description: "Expect values to match a SQL LIKE pattern", params: [col, { name: "like_pattern", label: "LIKE Pattern", type: "string", required: true, placeholder: "%@%.%" }, mostly] },
    { gxClass: "ExpectColumnValueLengthsToBeBetween", displayName: "String Length Range", description: "Expect string lengths to be within a range", params: [col, minVal, maxVal] },
    { gxClass: "ExpectColumnValueLengthsToEqual", displayName: "String Length Exact", description: "Expect all string values to have a specific length", params: [col, { name: "value", label: "Expected Length", type: "number", required: true }] },
  ],
  Validity: [
    { gxClass: "ExpectColumnValuesToBeInSet", displayName: "Values In Set", description: "Expect column values to be members of a specified set", params: [col, valSet, mostly] },
    { gxClass: "ExpectColumnValuesToNotBeInSet", displayName: "Values Not In Set", description: "Expect column values to NOT be in a specified set", params: [col, valSet, mostly] },
    { gxClass: "ExpectColumnMostCommonValueToBeInSet", displayName: "Most Common Value In Set", description: "Expect the most common value to be in a set", params: [col, valSet] },
    { gxClass: "ExpectColumnDistinctValuesToBeInSet", displayName: "Distinct Values In Set", description: "Expect distinct values to be contained by a given set", params: [col, valSet] },
  ],
  "Cross-Column": [
    { gxClass: "ExpectColumnPairValuesAToBeGreaterThanB", displayName: "Column A > Column B", description: "Expect values in column A to be greater than column B", params: [{ name: "column_A", label: "Column A", type: "string", required: true }, { name: "column_B", label: "Column B", type: "string", required: true }, { name: "or_equal", label: "Or Equal", type: "boolean" }, mostly] },
    { gxClass: "ExpectColumnPairValuesToBeEqual", displayName: "Column A = Column B", description: "Expect values in column A to equal column B", params: [{ name: "column_A", label: "Column A", type: "string", required: true }, { name: "column_B", label: "Column B", type: "string", required: true }, mostly] },
    { gxClass: "ExpectMulticolumnSumToEqual", displayName: "Multi-Column Sum Equals", description: "Expect the row-wise sum of columns to equal a value", params: [colList, { name: "sum_total", label: "Sum Total", type: "number", required: true }] },
    { gxClass: "ExpectTableRowCountToEqualOtherTable", displayName: "Row Count Matches Other Table", description: "Expect this table's row count to equal another table's", params: [{ name: "other_table_name", label: "Other Table Name", type: "string", required: true }] },
  ],
  Custom: [
    { gxClass: "UnexpectedRowsExpectation", displayName: "Custom SQL Check", description: "Expect a custom SQL query to return no rows (rows = failures)", params: [{ name: "unexpected_rows_query", label: "SQL Query", type: "string", required: true, placeholder: "SELECT * FROM {batch} WHERE ..." }] },
    { gxClass: "ExpectQueryResultsToMatchComparison", displayName: "Cross-Source Comparison", description: "Compare query results across two data sources", params: [{ name: "query", label: "Base Query", type: "string", required: true }, { name: "comparison_query", label: "Comparison Query", type: "string", required: true }, { name: "threshold", label: "Threshold", type: "number" }] },
  ],
};

const CATEGORIES = Object.keys(CATALOG) as Category[];

const SEVERITY_OPTIONS: { value: DqSeverity; label: string; color: string }[] = [
  { value: "critical", label: "Critical", color: "bg-red-100 text-red-800 dark:bg-red-900 dark:text-red-200" },
  { value: "warning", label: "Warning", color: "bg-yellow-100 text-yellow-800 dark:bg-yellow-900 dark:text-yellow-200" },
  { value: "info", label: "Info", color: "bg-blue-100 text-blue-800 dark:bg-blue-900 dark:text-blue-200" },
];

/* ── Component ──────────────────────────────────────────── */

interface ExpectationPickerProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  versionId: string;
  instanceId: string;
  instanceName: string;
  currentExpectations?: DqExpectation[];
  /** Resolved output schema of the step (LCT-035). Enables column-aware
   *  selectors instead of free-text and powers de-dupe of AI suggestions. */
  schema?: SchemaColumn[];
  onSaved?: () => void;
}

export function ExpectationPicker({
  open,
  onOpenChange,
  versionId,
  instanceId,
  instanceName,
  currentExpectations,
  schema,
  onSaved,
}: ExpectationPickerProps) {
  const [expectations, setExpectations] = useState<DqExpectation[]>(
    currentExpectations ?? []
  );
  const [selectedCategory, setSelectedCategory] = useState<Category>("Completeness");
  const [search, setSearch] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [suggesting, setSuggesting] = useState(false);
  const [suggestNote, setSuggestNote] = useState<string | null>(null);
  const safeSchema = Array.isArray(schema) ? schema : [];

  // Filter catalog entries by search
  const filteredEntries = useMemo(() => {
    const entries = CATALOG[selectedCategory];
    if (!search.trim()) return entries;
    const q = search.toLowerCase();
    return entries.filter(
      (e) =>
        e.displayName.toLowerCase().includes(q) ||
        e.description.toLowerCase().includes(q) ||
        e.gxClass.toLowerCase().includes(q)
    );
  }, [selectedCategory, search]);

  const addExpectation = (entry: CatalogEntry) => {
    const kwargs: Record<string, unknown> = {};
    for (const p of entry.params) {
      if (p.type === "boolean") kwargs[p.name] = false;
    }
    setExpectations((prev) => [
      ...prev,
      { type: entry.gxClass, kwargs, severity: "warning" as DqSeverity },
    ]);
  };

  const removeExpectation = (idx: number) => {
    setExpectations((prev) => prev.filter((_, i) => i !== idx));
  };

  const updateKwarg = (idx: number, key: string, value: unknown) => {
    setExpectations((prev) =>
      prev.map((e, i) =>
        i === idx ? { ...e, kwargs: { ...e.kwargs, [key]: value } } : e
      )
    );
  };

  const updateSeverity = (idx: number, severity: DqSeverity) => {
    setExpectations((prev) =>
      prev.map((e, i) => (i === idx ? { ...e, severity } : e))
    );
  };

  const expectationKey = (e: DqExpectation): string => {
    const col =
      (e.kwargs?.column as string | undefined) ??
      (e.kwargs?.column_A as string | undefined) ??
      "";
    return `${e.type}::${col}`;
  };

  /**
   * LCT-035: "Suggest DQ rules" calls the backend suggestion flow (the same
   * reasoning path the chat `suggest_dq_expectations` tool uses, exposed as a
   * REST endpoint) and appends any rules not already configured.
   */
  const handleSuggest = async () => {
    setSuggesting(true);
    setError(null);
    setSuggestNote(null);
    try {
      const res = await api.get<{ suggestions?: DqExpectation[] }>(
        `/api/v1/versions/${versionId}/instances/${instanceId}/dq/suggestions`
      );
      const suggestions = Array.isArray(res?.suggestions) ? res.suggestions : [];
      if (suggestions.length === 0) {
        setSuggestNote("No additional rules suggested for this step.");
        return;
      }
      setExpectations((prev) => {
        const existing = new Set(prev.map(expectationKey));
        const additions = suggestions
          .filter((s) => s && s.type && !existing.has(expectationKey(s)))
          .map((s) => ({
            type: s.type,
            kwargs: s.kwargs && typeof s.kwargs === "object" ? s.kwargs : {},
            severity: (s.severity ?? "warning") as DqSeverity,
            ...(s.reason ? { reason: s.reason } : {}),
          }));
        if (additions.length === 0) {
          setSuggestNote("All suggested rules are already configured.");
          return prev;
        }
        setSuggestNote(`Added ${additions.length} suggested rule${additions.length > 1 ? "s" : ""}.`);
        return [...prev, ...additions];
      });
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to fetch DQ suggestions");
    } finally {
      setSuggesting(false);
    }
  };

  const handleSave = async () => {
    setSaving(true);
    setError(null);
    try {
      await api.put(
        `/api/v1/versions/${versionId}/instances/${instanceId}/dq-expectations`,
        { expectations }
      );
      onSaved?.();
      onOpenChange(false);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to save expectations");
    } finally {
      setSaving(false);
    }
  };

  const catalogEntryForType = (gxClass: string): CatalogEntry | undefined => {
    for (const entries of Object.values(CATALOG)) {
      const found = entries.find((e) => e.gxClass === gxClass);
      if (found) return found;
    }
    return undefined;
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-[800px] max-h-[85vh] flex flex-col">
        <DialogHeader>
          <DialogTitle>DQ Expectations — {instanceName}</DialogTitle>
          <DialogDescription>
            Configure data quality expectations for this pipeline step.
          </DialogDescription>
        </DialogHeader>

        <div className="flex gap-4 flex-1 min-h-0">
          {/* Left: Catalog browser */}
          <div className="w-[340px] shrink-0 flex flex-col gap-2">
            <Input
              placeholder="Search expectations..."
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              className="h-8 text-xs"
            />
            <div className="flex flex-wrap gap-1">
              {CATEGORIES.map((cat) => (
                <button
                  key={cat}
                  onClick={() => setSelectedCategory(cat)}
                  className={cn(
                    "px-2 py-0.5 rounded-full text-[10px] font-medium transition-colors border",
                    selectedCategory === cat
                      ? "bg-primary text-primary-foreground border-primary"
                      : "bg-muted text-muted-foreground border-transparent hover:bg-muted/80"
                  )}
                >
                  {cat} ({CATALOG[cat].length})
                </button>
              ))}
            </div>
            <ScrollArea className="flex-1 max-h-[350px] rounded-md border">
              <div className="p-2 space-y-1">
                {filteredEntries.map((entry) => (
                  <button
                    key={entry.gxClass}
                    onClick={() => addExpectation(entry)}
                    className="w-full text-left rounded-md px-2 py-1.5 hover:bg-muted/50 transition-colors"
                  >
                    <div className="text-xs font-medium">{entry.displayName}</div>
                    <div className="text-[10px] text-muted-foreground leading-snug">
                      {entry.description}
                    </div>
                  </button>
                ))}
                {filteredEntries.length === 0 && (
                  <p className="text-[10px] text-muted-foreground text-center py-4">
                    No matching expectations
                  </p>
                )}
              </div>
            </ScrollArea>
          </div>

          {/* Right: Configured expectations */}
          <div className="flex-1 flex flex-col gap-2 min-w-0">
            <div className="flex items-center justify-between">
              <span className="text-xs font-medium">
                Configured ({expectations.length})
              </span>
              <div className="flex items-center gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  className="h-6 px-2 text-[10px]"
                  onClick={handleSuggest}
                  disabled={suggesting}
                  title="Use AI to suggest data quality rules for this step's schema"
                >
                  {suggesting ? "Suggesting…" : "✨ Suggest DQ rules"}
                </Button>
                {expectations.length > 0 && (
                  <button
                    onClick={() => setExpectations([])}
                    className="text-[10px] text-muted-foreground hover:text-destructive"
                  >
                    Clear all
                  </button>
                )}
              </div>
            </div>
            {suggestNote && (
              <p className="text-[10px] text-muted-foreground">{suggestNote}</p>
            )}
            <ScrollArea className="flex-1 max-h-[380px] rounded-md border">
              <div className="p-2 space-y-2">
                {expectations.length === 0 && (
                  <p className="text-[10px] text-muted-foreground text-center py-8">
                    Click expectations on the left to add them
                  </p>
                )}
                {expectations.map((exp, idx) => {
                  const entry = catalogEntryForType(exp.type);
                  return (
                    <div
                      key={`${exp.type}-${idx}`}
                      className="rounded-md border p-2 space-y-2"
                    >
                      <div className="flex items-center justify-between">
                        <span className="text-xs font-medium">
                          {entry?.displayName ?? exp.type}
                        </span>
                        <div className="flex items-center gap-1">
                          <Select
                            value={exp.severity}
                            onValueChange={(v) =>
                              updateSeverity(idx, v as DqSeverity)
                            }
                          >
                            <SelectTrigger className="h-5 w-[90px] text-[10px] px-1.5">
                              <SelectValue />
                            </SelectTrigger>
                            <SelectContent>
                              {SEVERITY_OPTIONS.map((opt) => (
                                <SelectItem
                                  key={opt.value}
                                  value={opt.value}
                                  className="text-[10px]"
                                >
                                  <Badge
                                    variant="outline"
                                    className={cn("text-[9px] border-0", opt.color)}
                                  >
                                    {opt.label}
                                  </Badge>
                                </SelectItem>
                              ))}
                            </SelectContent>
                          </Select>
                          <button
                            onClick={() => removeExpectation(idx)}
                            className="text-[10px] text-muted-foreground hover:text-destructive px-1"
                          >
                            ✕
                          </button>
                        </div>
                      </div>
                      {/* Param form */}
                      {entry?.params && entry.params.length > 0 && (
                        <div className="grid grid-cols-2 gap-x-2 gap-y-1">
                          {entry.params.map((p) => (
                            <div key={p.name} className="space-y-0.5">
                              <Label className="text-[10px] text-muted-foreground">
                                {p.label}
                                {p.required && <span className="text-destructive"> *</span>}
                              </Label>
                              {p.type === "boolean" ? (
                                <button
                                  onClick={() =>
                                    updateKwarg(idx, p.name, !exp.kwargs[p.name])
                                  }
                                  className={cn(
                                    "h-6 w-full rounded border text-[10px] transition-colors",
                                    exp.kwargs[p.name]
                                      ? "bg-primary text-primary-foreground"
                                      : "bg-muted text-muted-foreground"
                                  )}
                                >
                                  {exp.kwargs[p.name] ? "Yes" : "No"}
                                </button>
                              ) : SINGLE_COLUMN_PARAMS.has(p.name) ? (
                                <ColumnPicker
                                  columns={safeSchema}
                                  value={(exp.kwargs[p.name] as string) ?? ""}
                                  onChange={(v) => updateKwarg(idx, p.name, v)}
                                  placeholder={p.placeholder ?? "Select column…"}
                                  className="w-full"
                                />
                              ) : MULTI_COLUMN_PARAMS.has(p.name) ? (
                                <MultiColumnPicker
                                  columns={safeSchema}
                                  selected={
                                    Array.isArray(exp.kwargs[p.name])
                                      ? (exp.kwargs[p.name] as string[])
                                      : []
                                  }
                                  onChange={(sel) => updateKwarg(idx, p.name, sel)}
                                  fallbackPlaceholder={p.placeholder}
                                />
                              ) : (
                                <Input
                                  className="h-6 text-[10px] px-1.5"
                                  placeholder={p.placeholder}
                                  type={p.type === "number" ? "number" : "text"}
                                  value={
                                    p.type === "string[]"
                                      ? Array.isArray(exp.kwargs[p.name])
                                        ? (exp.kwargs[p.name] as string[]).join(", ")
                                        : (exp.kwargs[p.name] as string) ?? ""
                                      : (exp.kwargs[p.name] as string | number) ?? ""
                                  }
                                  onChange={(e) => {
                                    const raw = e.target.value;
                                    if (p.type === "number") {
                                      updateKwarg(
                                        idx,
                                        p.name,
                                        raw === "" ? undefined : Number(raw)
                                      );
                                    } else if (p.type === "string[]") {
                                      updateKwarg(
                                        idx,
                                        p.name,
                                        raw
                                          .split(",")
                                          .map((s) => s.trim())
                                          .filter(Boolean)
                                      );
                                    } else {
                                      updateKwarg(idx, p.name, raw);
                                    }
                                  }}
                                />
                              )}
                            </div>
                          ))}
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>
            </ScrollArea>
          </div>
        </div>

        {error && (
          <p className="text-xs text-destructive">{error}</p>
        )}

        <DialogFooter>
          <Button
            variant="outline"
            size="sm"
            onClick={() => onOpenChange(false)}
          >
            Cancel
          </Button>
          <Button size="sm" onClick={handleSave} disabled={saving}>
            {saving ? "Saving..." : "Save Expectations"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
