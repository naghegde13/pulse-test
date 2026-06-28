"use client";

import { memo } from "react";
import { Handle, Position, type NodeProps } from "@xyflow/react";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";
import type { BlueprintCategory, SchemaColumn } from "@/types";

const CATEGORY_COLORS: Record<BlueprintCategory, string> = {
  INGESTION: "bg-blue-100 text-blue-800 dark:bg-blue-900 dark:text-blue-200",
  TRANSFORM:
    "bg-purple-100 text-purple-800 dark:bg-purple-900 dark:text-purple-200",
  MODELING:
    "bg-green-100 text-green-800 dark:bg-green-900 dark:text-green-200",
  DATA_QUALITY:
    "bg-amber-100 text-amber-800 dark:bg-amber-900 dark:text-amber-200",
  ORCHESTRATION:
    "bg-slate-100 text-slate-800 dark:bg-slate-900 dark:text-slate-200",
  DESTINATION:
    "bg-rose-100 text-rose-800 dark:bg-rose-900 dark:text-rose-200",
};

const CATEGORY_LABELS: Record<BlueprintCategory, string> = {
  INGESTION: "Ingestion",
  TRANSFORM: "Transform",
  MODELING: "Modeling",
  DATA_QUALITY: "Data Quality",
  ORCHESTRATION: "Orchestration",
  DESTINATION: "Destination",
};

export interface DagNodeData {
  label: string;
  blueprintKey: string;
  category?: BlueprintCategory;
  paramCount: number;
  inputPorts: string[];
  outputPorts: string[];
  connectedInputPorts: Set<string>;
  connectedOutputPorts: Set<string>;
  hasMissingParams: boolean;
  columnCount?: number;
  inputColumnCount?: number;
  outputColumnCount?: number;
  addedColumnCount?: number;
  renamedColumnCount?: number;
  retypedColumnCount?: number;
  droppedColumnCount?: number;
  /** Full resolved column list — populated by SchemaPropagationService via
   *  composition GET. Used to render tag-summary chips (audit/masked/derived)
   *  and the hover tooltip with column names. */
  columns?: SchemaColumn[];
  medallionLayer?: string; // "bronze" | "silver" | "gold" | "control_plane"
  codeTypes?: string[]; // e.g., ["PySpark"], ["dbt"], ["GX"], ["Airflow"]
  schemaStatus?: string; // per Agent E contract: unknown/clean/dirty/conflict/pending
  schemaImpactActive?: boolean;
  /** Number of canonical DQ expectations (SubPipelineInstance.dqExpectations).
   *  Drives the DQ affordance label: "+ Add DQ" when 0, "DQ rules (n)" when >0. */
  dqExpectationCount?: number;
  /** Compact authoring mode (LCT-032): shrink the node and hide secondary
   *  chips so the canvas is less congested while building. */
  compact?: boolean;
  onDqClick?: () => void;
  /** Whether this node's blueprint declares a derived_columns param (LCT-055). */
  canAddColumn?: boolean;
  /** Whether this node's blueprint declares a dropped_columns param AND
   *  does NOT already have a native first-class drop-column param like
   *  drop_columns (LCT-055). BronzeToSilverCleaning has native drop_columns
   *  and should not get a duplicate Remove Column button. */
  canRemoveColumn?: boolean;
  onAddColumnClick?: () => void;
  onRemoveColumnClick?: () => void;
  [key: string]: unknown;
}

/**
 * Reduces a column list to a tag-count summary for the chip row, e.g.
 *   { audit: 7, masked: 2, derived: 1 }
 * Tags are pulled from each column's tags[] array; lineage strings like
 * "masked:hash" or "derived:expression" also count toward the bucket.
 */
function summarizeTags(columns: SchemaColumn[] | undefined): Record<string, number> {
  if (!columns || columns.length === 0) return {};
  const counts: Record<string, number> = {};
  const bump = (key: string) => {
    counts[key] = (counts[key] ?? 0) + 1;
  };
  for (const col of columns) {
    const tags = col.tags ?? [];
    if (tags.includes("audit")) bump("audit");
    if (tags.includes("masked")) bump("masked");
    if (tags.includes("derived")) bump("derived");
    if (tags.includes("pii") && !tags.includes("masked")) bump("pii");
    // Lineage-only fallback when tags weren't set.
    if (col.lineage?.startsWith("masked:") && !tags.includes("masked")) bump("masked");
    if (col.lineage === "derived:expression" && !tags.includes("derived")) bump("derived");
    if (col.lineage === "injected:audit" && !tags.includes("audit")) bump("audit");
  }
  return counts;
}

/**
 * Builds the multi-line tooltip string shown on the column-count badge.
 * Format: one line per column, name + type, with a one-letter prefix
 * indicating audit (A), masked (M), derived (D), or source (·).
 */
function buildColumnsTooltip(columns: SchemaColumn[] | undefined): string {
  if (!columns || columns.length === 0) return "No columns resolved yet";
  const lines: string[] = [];
  for (const col of columns) {
    const tags = col.tags ?? [];
    let prefix = "·";
    if (tags.includes("audit") || col.lineage === "injected:audit") prefix = "A";
    else if (tags.includes("masked") || col.lineage?.startsWith("masked:")) prefix = "M";
    else if (tags.includes("derived") || col.lineage === "derived:expression") prefix = "D";
    let line = `${prefix} ${col.name}: ${col.type ?? "?"}`;
    if (col.lineage?.startsWith("masked:")) {
      line += ` [${col.lineage.replace("masked:", "")}]`;
    } else if (col.lineage === "derived:expression" && col.transform?.expression) {
      line += ` = ${col.transform.expression}`;
    }
    lines.push(line);
  }
  return [
    `Columns (${columns.length}) — A=audit, M=masked, D=derived, ·=source/passthrough`,
    "",
    ...lines,
  ].join("\n");
}

const TAG_CHIP_STYLES: Record<string, string> = {
  audit:
    "bg-cyan-100 text-cyan-800 dark:bg-cyan-900 dark:text-cyan-200",
  masked:
    "bg-rose-100 text-rose-800 dark:bg-rose-900 dark:text-rose-200",
  derived:
    "bg-violet-100 text-violet-800 dark:bg-violet-900 dark:text-violet-200",
  pii:
    "bg-amber-100 text-amber-800 dark:bg-amber-900 dark:text-amber-200",
};

const TAG_CHIP_LABELS: Record<string, string> = {
  audit: "audit",
  masked: "masked",
  derived: "derived",
  pii: "PII",
};

const MEDALLION_COLORS: Record<string, string> = {
  bronze: "bg-orange-200 text-orange-900 dark:bg-orange-900 dark:text-orange-100",
  silver: "bg-slate-200 text-slate-900 dark:bg-slate-700 dark:text-slate-100",
  gold: "bg-amber-200 text-amber-900 dark:bg-amber-800 dark:text-amber-100",
  control_plane: "bg-blue-200 text-blue-900 dark:bg-blue-900 dark:text-blue-100",
};

const MEDALLION_LABELS: Record<string, string> = {
  bronze: "Bronze",
  silver: "Silver",
  gold: "Gold",
  control_plane: "Control",
};

const SCHEMA_STATUS_STYLES: Record<
  string,
  { className: string; dot: string; label: string }
> = {
  clean: {
    className: "border-emerald-400 text-emerald-700",
    dot: "bg-emerald-500",
    label: "Schema ✓",
  },
  dirty: {
    className: "border-amber-400 text-amber-700",
    dot: "bg-amber-500",
    label: "Schema stale",
  },
  conflict: {
    className: "border-red-400 text-red-700",
    dot: "bg-red-500",
    label: "Schema conflict",
  },
  pending: {
    className: "border-sky-400 text-sky-700",
    dot: "bg-sky-500 animate-pulse",
    label: "Propagating…",
  },
  unknown: {
    className: "border-muted-foreground/40 text-muted-foreground",
    dot: "bg-muted-foreground/40",
    label: "Schema ?",
  },
};

function DagNodeComponent({ data, selected }: NodeProps) {
  const nodeData = data as unknown as DagNodeData;
  const {
    label,
    blueprintKey,
    category,
    paramCount,
    inputPorts,
    outputPorts,
    connectedInputPorts,
    connectedOutputPorts,
    hasMissingParams,
    columnCount,
    inputColumnCount,
    addedColumnCount,
    renamedColumnCount,
    retypedColumnCount,
    droppedColumnCount,
    columns,
    medallionLayer,
    codeTypes,
    schemaStatus,
    schemaImpactActive,
    dqExpectationCount,
    compact,
    onDqClick,
    canAddColumn,
    canRemoveColumn,
    onAddColumnClick,
    onRemoveColumnClick,
  } = nodeData;
  const schemaStyle =
    SCHEMA_STATUS_STYLES[schemaStatus ?? "unknown"] ?? SCHEMA_STATUS_STYLES.unknown;
  const tagCounts = summarizeTags(columns);
  const columnsTooltip = buildColumnsTooltip(columns);
  const dqCount = dqExpectationCount ?? 0;

  return (
    <div
      className={cn(
        "rounded-lg border bg-card text-card-foreground shadow-sm",
        compact ? "px-2 py-1.5 min-w-[120px] max-w-[180px]" : "px-4 py-3 min-w-[180px] max-w-[260px]",
        hasMissingParams && "border-dashed border-amber-500",
        // LCT-050: consume ReactFlow's `selected` prop so single-click
        // selection is visually obvious. The schema-impact red ring wins
        // when active (it signals a problem the user must see).
        selected && !schemaImpactActive && "ring-2 ring-primary ring-offset-1 border-primary shadow-md",
        schemaImpactActive && "ring-2 ring-red-500 border-red-500 bg-red-50/60 dark:bg-red-950/30"
      )}
    >
      {/* Input port handles on left */}
      {inputPorts.map((port, idx) => {
        const isConnected = connectedInputPorts.has(port);
        const topPercent =
          inputPorts.length === 1
            ? 50
            : 25 + (50 / (inputPorts.length - 1)) * idx;

        return (
          <div key={`in-${port}`}>
            <Handle
              type="target"
              position={Position.Left}
              id={port}
              style={{ top: `${topPercent}%` }}
              className={cn(
                "!w-3 !h-3 !border-2 !rounded-full",
                isConnected
                  ? "!bg-primary !border-primary"
                  : "!bg-amber-400 !border-amber-500 hover:!bg-amber-300 hover:!scale-125"
              )}
              title={`Input: ${port}`}
            />
            {inputPorts.length > 1 && (
              <span
                className="absolute text-[8px] text-muted-foreground pointer-events-none"
                style={{ top: `${topPercent}%`, left: 14, transform: "translateY(-50%)" }}
              >
                {port.replace(/_/g, " ")}
              </span>
            )}
          </div>
        );
      })}

      {/* Output port handles on right */}
      {outputPorts.map((port, idx) => {
        const isConnected = connectedOutputPorts.has(port);
        const topPercent =
          outputPorts.length === 1
            ? 50
            : 25 + (50 / (outputPorts.length - 1)) * idx;

        return (
          <div key={`out-${port}`}>
            <Handle
              type="source"
              position={Position.Right}
              id={port}
              style={{ top: `${topPercent}%` }}
              className={cn(
                "!w-3 !h-3 !border-2 !rounded-full",
                isConnected
                  ? "!bg-primary !border-primary"
                  : "!bg-amber-400 !border-amber-500 hover:!bg-amber-300 hover:!scale-125"
              )}
              title={`Output: ${port}`}
            />
            {outputPorts.length > 1 && (
              <span
                className="absolute text-[8px] text-muted-foreground pointer-events-none"
                style={{ top: `${topPercent}%`, right: 14, transform: "translateY(-50%)", textAlign: "right" }}
              >
                {port.replace(/_/g, " ")}
              </span>
            )}
          </div>
        );
      })}

      {/* No fallback handles -- only render actual ports from the blueprint.
         INGESTION nodes have no input ports (they are data sources).
         DESTINATION nodes have no output ports (they are data sinks). */}

      {/* Node content */}
      <div className={cn("flex flex-col", compact ? "gap-1" : "gap-1.5")}>
        <span className={cn("font-bold leading-tight truncate", compact ? "text-xs" : "text-sm")}>
          {label}
        </span>
        {!compact && (
          <span className="text-[10px] font-mono text-muted-foreground truncate">
            {blueprintKey}
          </span>
        )}
        <div className="flex items-center gap-1.5 flex-wrap">
          {category && (
            <Badge
              variant="outline"
              className={cn("text-[10px] border-0", CATEGORY_COLORS[category])}
            >
              {CATEGORY_LABELS[category]}
            </Badge>
          )}
          {/* LCT-032: secondary chips are hidden in compact authoring mode to
              de-clutter the canvas; the full chip set returns in normal mode. */}
          {!compact && medallionLayer && MEDALLION_COLORS[medallionLayer] && (
            <Badge
              variant="outline"
              className={cn("text-[10px] border-0", MEDALLION_COLORS[medallionLayer])}
            >
              {MEDALLION_LABELS[medallionLayer] ?? medallionLayer}
            </Badge>
          )}
          {!compact && codeTypes?.map((type) => (
            <Badge
              key={`ct-${type}`}
              variant="outline"
              className="text-[10px] font-mono"
            >
              {type}
            </Badge>
          ))}
          {!compact && paramCount > 0 && (
            <Badge variant="secondary" className="text-[10px]">
              {paramCount} params
            </Badge>
          )}
          {!compact && inputColumnCount !== undefined && inputColumnCount > 0 && (
            <Badge
              variant="outline"
              className="text-[10px] text-muted-foreground cursor-help"
              title="Input columns received from connected upstream ports"
            >
              ← {inputColumnCount} in
            </Badge>
          )}
          {!compact && columnCount !== undefined && columnCount > 0 && (
            <Badge
              variant="outline"
              className="text-[10px] text-muted-foreground cursor-help"
              title={columnsTooltip}
            >
              → {columnCount} cols
            </Badge>
          )}
          {!compact && ((addedColumnCount ?? 0) > 0
            || (renamedColumnCount ?? 0) > 0
            || (retypedColumnCount ?? 0) > 0
            || (droppedColumnCount ?? 0) > 0) && (
            <Badge
              variant="outline"
              className={cn(
                "text-[10px] cursor-help",
                (droppedColumnCount ?? 0) > 0 || (retypedColumnCount ?? 0) > 0
                  ? "border-red-300 text-red-700"
                  : "border-emerald-300 text-emerald-700"
              )}
              title={`Schema diff: ${addedColumnCount ?? 0} added, ${renamedColumnCount ?? 0} renamed, ${retypedColumnCount ?? 0} retyped, ${droppedColumnCount ?? 0} dropped`}
            >
              Δ +{addedColumnCount ?? 0}/~{renamedColumnCount ?? 0}/!{retypedColumnCount ?? 0}/-{droppedColumnCount ?? 0}
            </Badge>
          )}
          {/* Tag-summary chips: per-bucket counts (audit / masked / derived / pii)
              show at-a-glance how the schema was composed. Hover the cols badge
              for the full per-column listing with lineage prefixes. */}
          {!compact && (["audit", "masked", "derived", "pii"] as const).map((tag) => {
            const n = tagCounts[tag];
            if (!n) return null;
            return (
              <Badge
                key={`tag-${tag}`}
                variant="outline"
                className={cn("text-[10px] border-0", TAG_CHIP_STYLES[tag])}
                title={`${n} ${tag} column${n > 1 ? "s" : ""}`}
              >
                {TAG_CHIP_LABELS[tag]} · {n}
              </Badge>
            );
          })}
          {!compact && (
            <Badge
              variant="outline"
              className={cn("text-[10px] flex items-center gap-1", schemaStyle.className)}
              title={`Schema status: ${schemaStatus ?? "unknown"}`}
            >
              <span
                aria-hidden
                className={cn("inline-block h-1.5 w-1.5 rounded-full", schemaStyle.dot)}
              />
              {schemaStyle.label}
            </Badge>
          )}
          {onDqClick && (
            <button
              onClick={(e) => {
                e.stopPropagation();
                onDqClick();
              }}
              className={cn(
                "text-[10px] font-medium px-1.5 py-0.5 rounded transition-colors",
                dqCount > 0
                  ? "bg-amber-200 text-amber-900 dark:bg-amber-800 dark:text-amber-100 hover:bg-amber-300 dark:hover:bg-amber-700"
                  : "bg-amber-100 text-amber-800 dark:bg-amber-900 dark:text-amber-200 hover:bg-amber-200 dark:hover:bg-amber-800"
              )}
              title={
                dqCount > 0
                  ? `${dqCount} DQ rule${dqCount > 1 ? "s" : ""} configured — click to view/edit. Emits a paired Great Expectations checkpoint task after this step's output table is written.`
                  : "Add DQ rules — emits a paired Great Expectations checkpoint task that runs after this step's output table is written. dbt models and PySpark jobs both work; the GX task is a separate Airflow task wired sequentially after this step."
              }
            >
              {dqCount > 0 ? `DQ rules (${dqCount})` : "+ Add DQ"}
            </button>
          )}
          {canAddColumn && onAddColumnClick && (
            <button
              onClick={(e) => {
                e.stopPropagation();
                onAddColumnClick();
              }}
              className="text-[10px] font-medium px-1.5 py-0.5 rounded bg-violet-100 text-violet-800 dark:bg-violet-900 dark:text-violet-200 hover:bg-violet-200 dark:hover:bg-violet-800 transition-colors"
              title="Add a derived column — opens the expression builder to define column name, type, and SQL expression evaluated per row."
            >
              + Add Column
            </button>
          )}
          {canRemoveColumn && onRemoveColumnClick && (
            <button
              onClick={(e) => {
                e.stopPropagation();
                onRemoveColumnClick();
              }}
              className="text-[10px] font-medium px-1.5 py-0.5 rounded bg-rose-100 text-rose-800 dark:bg-rose-900 dark:text-rose-200 hover:bg-rose-200 dark:hover:bg-rose-800 transition-colors"
              title="Remove column(s) — opens a schema-aware column selector to drop columns from this step's output."
            >
              − Remove Col
            </button>
          )}
        </div>
      </div>
    </div>
  );
}

export const DagNode = memo(DagNodeComponent);
