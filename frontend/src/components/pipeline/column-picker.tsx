"use client";

import { useState, useRef, useEffect } from "react";
import { ChevronDownIcon, CheckIcon } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { cn } from "@/lib/utils";
import type { SchemaColumn } from "@/types";

const TYPE_COLORS: Record<string, string> = {
  string: "bg-green-100 text-green-800 dark:bg-green-900 dark:text-green-200",
  long: "bg-blue-100 text-blue-800 dark:bg-blue-900 dark:text-blue-200",
  integer: "bg-blue-100 text-blue-800 dark:bg-blue-900 dark:text-blue-200",
  int: "bg-blue-100 text-blue-800 dark:bg-blue-900 dark:text-blue-200",
  double: "bg-orange-100 text-orange-800 dark:bg-orange-900 dark:text-orange-200",
  float: "bg-orange-100 text-orange-800 dark:bg-orange-900 dark:text-orange-200",
  decimal: "bg-orange-100 text-orange-800 dark:bg-orange-900 dark:text-orange-200",
  boolean: "bg-purple-100 text-purple-800 dark:bg-purple-900 dark:text-purple-200",
  date: "bg-gray-100 text-gray-700 dark:bg-gray-800 dark:text-gray-300",
  timestamp: "bg-gray-100 text-gray-700 dark:bg-gray-800 dark:text-gray-300",
  struct: "bg-yellow-100 text-yellow-800 dark:bg-yellow-900 dark:text-yellow-200",
  array: "bg-yellow-100 text-yellow-800 dark:bg-yellow-900 dark:text-yellow-200",
  map: "bg-yellow-100 text-yellow-800 dark:bg-yellow-900 dark:text-yellow-200",
};

export function getTypeColor(type: string): string {
  const lower = type.toLowerCase();
  for (const [key, color] of Object.entries(TYPE_COLORS)) {
    if (lower.includes(key)) return color;
  }
  return "bg-gray-100 text-gray-700 dark:bg-gray-800 dark:text-gray-300";
}

/* -------------------------------------------------------------------------- */
/*  Single-select searchable ColumnPicker                                     */
/* -------------------------------------------------------------------------- */

interface ColumnPickerProps {
  columns: SchemaColumn[];
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  filterTypes?: string[];
  className?: string;
  /**
   * When true the upstream schema is still resolving (W-2): render a skeleton
   * placeholder rather than the empty-state text Input, so the user doesn't
   * mistake "still loading" for "genuinely no columns — type a name".
   */
  loading?: boolean;
}

export function ColumnPicker({
  columns,
  value,
  onChange,
  placeholder = "Select column…",
  filterTypes,
  className,
  loading,
}: ColumnPickerProps) {
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState("");
  const ref = useRef<HTMLDivElement>(null);

  // Close on outside click
  useEffect(() => {
    if (!open) return;
    const handler = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener("mousedown", handler);
    return () => document.removeEventListener("mousedown", handler);
  }, [open]);

  const safeColumns = Array.isArray(columns) ? columns : [];
  const displayColumns = filterTypes
    ? safeColumns.filter((c) =>
        filterTypes.some((t) => c.type.toLowerCase().includes(t.toLowerCase()))
      )
    : safeColumns;

  const filtered = displayColumns.filter(
    (c) => !search || c.name.toLowerCase().includes(search.toLowerCase())
  );

  // Schema still resolving (W-2): show a skeleton, NOT the text fallback.
  if (loading) {
    return (
      <div
        aria-busy="true"
        className={cn(
          "flex items-center h-8 px-2 text-xs rounded-md border border-input",
          "bg-muted/50 text-muted-foreground animate-pulse cursor-default select-none",
          className
        )}
      >
        Loading schema…
      </div>
    );
  }

  // Fallback to plain text input when there are no columns
  if (safeColumns.length === 0) {
    return (
      <Input
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        className={cn("h-8 text-xs", className)}
      />
    );
  }

  const selectedCol = safeColumns.find((c) => c.name === value);

  return (
    <div ref={ref} className={cn("relative w-[160px]", className)}>
      <button
        type="button"
        className={cn(
          "flex items-center gap-1.5 border rounded-md px-2 h-8 text-xs",
          "bg-transparent hover:bg-muted/50 w-full justify-between",
          "border-input shadow-xs"
        )}
        onClick={() => {
          setOpen(!open);
          setSearch("");
        }}
      >
        {selectedCol ? (
          <span className="flex items-center gap-1.5 truncate min-w-0">
            <span className="truncate">{selectedCol.name}</span>
            <span
              className={cn(
                "text-[8px] px-1 rounded shrink-0",
                getTypeColor(selectedCol.type)
              )}
            >
              {selectedCol.type}
            </span>
          </span>
        ) : (
          <span className="text-muted-foreground truncate">{placeholder}</span>
        )}
        <ChevronDownIcon className="size-3 opacity-50 shrink-0" />
      </button>

      {open && (
        <div className="absolute z-50 mt-1 w-full min-w-[220px] rounded-md border bg-popover text-popover-foreground shadow-md">
          <div className="p-1.5">
            <input
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="Search columns…"
              className="w-full h-7 text-xs px-2 border rounded bg-background outline-none focus:ring-1 focus:ring-ring"
              autoFocus
            />
          </div>
          <div className="max-h-[200px] overflow-y-auto p-1">
            {filtered.length === 0 ? (
              <p className="text-xs text-muted-foreground px-2 py-1.5">
                No columns found
              </p>
            ) : (
              filtered.map((col) => (
                <button
                  key={col.name}
                  type="button"
                  className={cn(
                    "flex items-center gap-1.5 w-full rounded px-2 py-1.5 text-xs text-left",
                    "hover:bg-accent hover:text-accent-foreground",
                    value === col.name && "bg-accent"
                  )}
                  onClick={() => {
                    onChange(col.name);
                    setOpen(false);
                    setSearch("");
                  }}
                >
                  <span className="truncate">{col.name}</span>
                  <span
                    className={cn(
                      "text-[8px] px-1 rounded shrink-0",
                      getTypeColor(col.type)
                    )}
                  >
                    {col.type}
                  </span>
                  {value === col.name && (
                    <CheckIcon className="size-3 ml-auto shrink-0" />
                  )}
                </button>
              ))
            )}
          </div>
        </div>
      )}
    </div>
  );
}

/* -------------------------------------------------------------------------- */
/*  Multi-select ColumnPicker (badge toggle style)                            */
/* -------------------------------------------------------------------------- */

interface MultiColumnPickerProps {
  columns: SchemaColumn[];
  selected: string[];
  onChange: (selected: string[]) => void;
  filterTypes?: string[];
  fallbackPlaceholder?: string;
  /**
   * When true the upstream schema is still resolving (W-2): render a skeleton
   * placeholder rather than the comma-separated text Input fallback.
   */
  loading?: boolean;
}

export function MultiColumnPicker({
  columns,
  selected,
  onChange,
  filterTypes,
  fallbackPlaceholder,
  loading,
}: MultiColumnPickerProps) {
  const safeColumns = Array.isArray(columns) ? columns : [];
  const safeSelected = Array.isArray(selected) ? selected : [];
  // Schema still resolving (W-2): show a skeleton, NOT the text fallback.
  if (loading) {
    return (
      <div
        aria-busy="true"
        className={cn(
          "flex items-center h-8 px-2 text-xs rounded-md border border-input",
          "bg-muted/50 text-muted-foreground animate-pulse cursor-default select-none"
        )}
      >
        Loading schema…
      </div>
    );
  }

  const displayCols = filterTypes
    ? safeColumns.filter((c) =>
        filterTypes.some((t) => c.type.toLowerCase().includes(t.toLowerCase()))
      )
    : safeColumns;

  if (displayCols.length === 0) {
    return (
      <Input
        value={safeSelected.join(", ")}
        onChange={(e) =>
          onChange(
            e.target.value
              .split(",")
              .map((s) => s.trim())
              .filter(Boolean)
          )
        }
        placeholder={fallbackPlaceholder || "col1, col2, …"}
        className="h-8 text-xs"
      />
    );
  }

  const toggle = (name: string) => {
    if (safeSelected.includes(name)) {
      onChange(safeSelected.filter((s) => s !== name));
    } else {
      onChange([...safeSelected, name]);
    }
  };

  return (
    <div className="flex flex-wrap gap-1">
      {displayCols.map((col) => (
        <Badge
          key={col.name}
          variant={safeSelected.includes(col.name) ? "default" : "outline"}
          className="text-xs cursor-pointer gap-1"
          onClick={() => toggle(col.name)}
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
  );
}
