"use client";

/**
 * CronBuilder — standalone schedule-expression construct (W-8).
 *
 * Per SPEC-construct-library.md §6 / IMPL §S6: `cron-builder` is its OWN #4
 * construct, HOSTED by `sensing-config` (the orchestration panel), NOT folded
 * into it. It edits the `cron_expression` value of ScheduleAndTriggers.
 *
 * Two ways to author the value, kept in one control:
 *   - a curated PRESET picker (the Airflow `@daily`/`@hourly`/… macros plus the
 *     common business cadences) — the no-fuss path;
 *   - a raw 5-field cron Input for power users (`0 6 * * *`).
 * The emitted value is always a single string the codegen lowers directly into
 * the DAG `schedule_interval`. A best-effort human summary is shown beneath.
 */

import { useMemo } from "react";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { cn } from "@/lib/utils";

export interface CronPreset {
  value: string;
  label: string;
}

/** Curated cadence presets (Airflow macros + common business schedules). */
export const CRON_PRESETS: CronPreset[] = [
  { value: "@hourly", label: "Hourly (@hourly)" },
  { value: "@daily", label: "Daily at midnight (@daily)" },
  { value: "0 6 * * *", label: "Daily at 06:00" },
  { value: "0 9 * * 1-5", label: "Weekdays at 09:00" },
  { value: "@weekly", label: "Weekly, Sunday midnight (@weekly)" },
  { value: "0 6 * * 1", label: "Weekly, Monday 06:00" },
  { value: "@monthly", label: "Monthly, 1st at midnight (@monthly)" },
  { value: "0 6 1 * *", label: "Monthly, 1st at 06:00" },
  { value: "@yearly", label: "Yearly (@yearly)" },
];

const CUSTOM = "__custom__";

/** Tiny best-effort human summary; falls back to echoing the raw expression. */
export function summarizeCron(expr: string): string {
  const trimmed = expr.trim();
  if (!trimmed) return "No schedule set";
  const preset = CRON_PRESETS.find((p) => p.value === trimmed);
  if (preset) return preset.label;
  return `Custom: ${trimmed}`;
}

export interface CronBuilderProps {
  value: string;
  onChange: (value: string) => void;
  id?: string;
  className?: string;
}

export function CronBuilder({ value, onChange, id, className }: CronBuilderProps) {
  // The dropdown shows the matching preset, else "Custom…".
  const selected = useMemo(() => {
    const trimmed = value.trim();
    return CRON_PRESETS.some((p) => p.value === trimmed) ? trimmed : CUSTOM;
  }, [value]);

  const isCustom = selected === CUSTOM;

  return (
    <div className={cn("space-y-1", className)} data-cron-builder>
      <Select
        value={selected}
        onValueChange={(v) => {
          if (v === CUSTOM) {
            // Switching to custom keeps whatever's there (or seeds a template).
            if (CRON_PRESETS.some((p) => p.value === value.trim())) onChange("");
          } else {
            onChange(v);
          }
        }}
      >
        <SelectTrigger id={id} className="h-8 text-xs">
          <SelectValue placeholder="Pick a cadence…" />
        </SelectTrigger>
        <SelectContent>
          {CRON_PRESETS.map((p) => (
            <SelectItem key={p.value} value={p.value} className="text-xs">
              {p.label}
            </SelectItem>
          ))}
          <SelectItem value={CUSTOM} className="text-xs">
            Custom cron…
          </SelectItem>
        </SelectContent>
      </Select>

      {isCustom && (
        <Input
          value={value}
          onChange={(e) => onChange(e.target.value)}
          className="h-8 text-xs font-mono"
          placeholder="0 6 * * *  (min hour dom mon dow)"
          aria-label="Custom cron expression"
        />
      )}

      <p className="text-[10px] text-muted-foreground" data-cron-summary>
        {summarizeCron(value)}
      </p>
    </div>
  );
}
