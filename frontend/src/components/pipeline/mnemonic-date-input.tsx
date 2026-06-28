"use client";

/**
 * MnemonicDateInput — tri-mode input for params with `accepts_mnemonic: true`.
 *
 * Three modes selectable via toggle pills at the top:
 *   1. Mnemonic    — combobox with curated vocabulary + offset stepper
 *   2. ISO date    — native date picker (literal YYYY-MM-DD)
 *   3. Free-text   — for power users who want to type 'NBDOM(5)' or
 *                    a less-common mnemonic like 'BOFY-2' directly
 *
 * Vocabulary kept in lockstep with the agent prompt (PulseSystemPrompt.MEDALLION_RULES)
 * and the runtime resolver (pulse_dates/__init__.py) and the codegen-time
 * validator (DateMnemonic.java). Any change to one MUST be matched in all three.
 */

import { useEffect, useMemo, useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";

interface MnemonicDateInputProps {
  id: string;
  value: string;
  onChange: (value: string) => void;
  /**
   * W-10: optional as-of business date the per-step SQL preview resolves
   * mnemonics against. When provided AND the value is a mnemonic (not ISO/empty),
   * a muted "Resolved as-of {asOf}" hint is shown. Purely informational — no
   * client-side resolution math is done here.
   */
  asOf?: string;
  /**
   * W-11: compact single-row variant for embedding in a table cell
   * (e.g. condition-builder's value cell). Stays fully tri-mode and functional;
   * just tightens layout so it fits a narrow container.
   */
  embedded?: boolean;
  /**
   * W-12: documentation/caption flag. "sql" (default) describes `[[ ]]` SQL
   * date tokens; "filename" describes `{date}` substitution (filename_pattern,
   * G-10 unification). Does NOT change the stored value format — caption only.
   */
  dateTokenMode?: "sql" | "filename";
}

/** Mnemonics grouped by category for the dropdown UX. */
const VOCABULARY: Record<string, { value: string; label: string; supportsOffset: boolean }[]> = {
  "Today-relative": [
    { value: "TODAY", label: "TODAY", supportsOffset: false },
    { value: "T", label: "T (today + N days)", supportsOffset: true },
    { value: "RUN_DATE", label: "RUN_DATE", supportsOffset: false },
    { value: "PREVIOUS_RUN_DATE", label: "PREVIOUS_RUN_DATE", supportsOffset: false },
  ],
  Week: [
    { value: "BOW", label: "BOW (beginning of week, Mon)", supportsOffset: true },
    { value: "EOW", label: "EOW (end of week, Sun)", supportsOffset: true },
    { value: "WTD_START", label: "WTD_START (alias for BOW)", supportsOffset: false },
    { value: "SAME_DAY_LAST_WEEK", label: "SAME_DAY_LAST_WEEK", supportsOffset: false },
  ],
  Month: [
    { value: "BOM", label: "BOM (beginning of month)", supportsOffset: true },
    { value: "EOM", label: "EOM (end of month)", supportsOffset: true },
    { value: "FBOM", label: "FBOM (first business day of month)", supportsOffset: false },
    { value: "LBOM", label: "LBOM (last business day of month)", supportsOffset: false },
    { value: "MTD_START", label: "MTD_START (alias for BOM)", supportsOffset: false },
    { value: "LAST_COMPLETED_MONTH_START", label: "LAST_COMPLETED_MONTH_START", supportsOffset: false },
    { value: "LAST_COMPLETED_MONTH_END", label: "LAST_COMPLETED_MONTH_END", supportsOffset: false },
    { value: "SAME_DAY_LAST_MONTH", label: "SAME_DAY_LAST_MONTH", supportsOffset: false },
  ],
  Quarter: [
    { value: "BOQ", label: "BOQ (beginning of quarter)", supportsOffset: true },
    { value: "EOQ", label: "EOQ (end of quarter)", supportsOffset: true },
    { value: "QTD_START", label: "QTD_START (alias for BOQ)", supportsOffset: false },
    { value: "LAST_COMPLETED_QUARTER_START", label: "LAST_COMPLETED_QUARTER_START", supportsOffset: false },
    { value: "LAST_COMPLETED_QUARTER_END", label: "LAST_COMPLETED_QUARTER_END", supportsOffset: false },
    { value: "SAME_DAY_LAST_QUARTER", label: "SAME_DAY_LAST_QUARTER", supportsOffset: false },
  ],
  "Half-year": [
    { value: "BOH", label: "BOH (beginning of half-year)", supportsOffset: true },
    { value: "EOH", label: "EOH (end of half-year)", supportsOffset: true },
  ],
  Year: [
    { value: "BOY", label: "BOY (beginning of year)", supportsOffset: true },
    { value: "EOY", label: "EOY (end of year)", supportsOffset: true },
    { value: "YTD_START", label: "YTD_START (alias for BOY)", supportsOffset: false },
    { value: "SAME_DAY_LAST_YEAR", label: "SAME_DAY_LAST_YEAR", supportsOffset: false },
  ],
  Fiscal: [
    { value: "BOFY", label: "BOFY (beginning of fiscal year)", supportsOffset: true },
    { value: "EOFY", label: "EOFY (end of fiscal year)", supportsOffset: true },
    { value: "BOFQ", label: "BOFQ (beginning of fiscal quarter)", supportsOffset: true },
    { value: "EOFQ", label: "EOFQ (end of fiscal quarter)", supportsOffset: true },
    { value: "BOFM", label: "BOFM (beginning of fiscal month)", supportsOffset: true },
    { value: "EOFM", label: "EOFM (end of fiscal month)", supportsOffset: true },
    { value: "FYTD_START", label: "FYTD_START (alias for BOFY)", supportsOffset: false },
  ],
  "Business day": [
    { value: "PBD", label: "PBD (previous business day)", supportsOffset: true },
    { value: "NBD", label: "NBD (next business day)", supportsOffset: true },
  ],
};

const ISO_DATE_RE = /^\d{4}-\d{2}-\d{2}$/;
// Mirror DateMnemonic.java / pulse_dates regex.
const MNEMONIC_RE = /^([A-Z_]+)([+-]\d+)?$|^NBDOM\(\d+\)$/i;

type Mode = "mnemonic" | "iso" | "freetext";

function detectMode(value: string): Mode {
  const trimmed = value.trim();
  if (!trimmed) return "mnemonic";
  if (ISO_DATE_RE.test(trimmed)) return "iso";
  // If it's a pure head from the vocabulary or head+offset, treat as mnemonic.
  // Anything else → freetext (user typed something custom, e.g., NBDOM(5)).
  const head = trimmed.replace(/[+-]\d+$/, "").toUpperCase();
  const allHeads = Object.values(VOCABULARY).flat().map((v) => v.value);
  if (allHeads.includes(head)) return "mnemonic";
  return "freetext";
}

function parseMnemonicValue(value: string): { head: string; offset: number } {
  const trimmed = value.trim();
  const m = trimmed.match(/^([A-Z_]+)([+-]\d+)?$/i);
  if (!m) return { head: trimmed.toUpperCase(), offset: 0 };
  const head = m[1].toUpperCase();
  const offset = m[2] ? parseInt(m[2], 10) : 0;
  return { head, offset };
}

function isValid(value: string): boolean {
  const trimmed = value.trim();
  if (!trimmed) return false;
  if (ISO_DATE_RE.test(trimmed)) {
    // Shape match — trust the API to validate the actual date.
    return true;
  }
  return MNEMONIC_RE.test(trimmed);
}

export function MnemonicDateInput({
  id,
  value,
  onChange,
  asOf,
  embedded = false,
  dateTokenMode = "sql",
}: MnemonicDateInputProps) {
  const [mode, setMode] = useState<Mode>(() => detectMode(value));
  const parsed = useMemo(() => parseMnemonicValue(value), [value]);

  // When switching modes, don't clobber the underlying value unless the user
  // changes input. This lets the user toggle modes to inspect.
  useEffect(() => {
    // Only re-detect mode when the value changes from outside (controlled prop).
    // setMode is stable by React contract, so it's safe to omit from deps.
    setMode(detectMode(value));
  }, [value]);

  const supportsOffset = useMemo(() => {
    const item = Object.values(VOCABULARY)
      .flat()
      .find((v) => v.value === parsed.head);
    return item?.supportsOffset ?? false;
  }, [parsed.head]);

  const valid = isValid(value);

  // W-10: only surface the as-of hint when there's an as-of AND the current
  // value resolves as a mnemonic (ISO literals and empty values don't resolve).
  const showAsOf = Boolean(asOf) && value.trim().length > 0 && mode === "mnemonic";

  // Embedded variant tightens the wrapper: inline flex, no vertical stacking.
  const wrapperClass = embedded
    ? "flex flex-wrap items-center gap-1"
    : "space-y-2";
  const pillRowClass = embedded ? "flex gap-1 shrink-0" : "flex gap-1";
  const pillClass = embedded ? "text-[11px] h-7 px-2" : "text-xs h-7";

  return (
    <div className={wrapperClass} data-embedded={embedded ? "true" : undefined}>
      <div className={pillRowClass}>
        <Button
          type="button"
          size="sm"
          variant={mode === "mnemonic" ? "default" : "outline"}
          className={pillClass}
          onClick={() => setMode("mnemonic")}
        >
          Mnemonic
        </Button>
        <Button
          type="button"
          size="sm"
          variant={mode === "iso" ? "default" : "outline"}
          className={pillClass}
          onClick={() => setMode("iso")}
        >
          ISO date
        </Button>
        <Button
          type="button"
          size="sm"
          variant={mode === "freetext" ? "default" : "outline"}
          className={pillClass}
          onClick={() => setMode("freetext")}
        >
          Free-text
        </Button>
      </div>

      {mode === "mnemonic" && (
        <div className="flex gap-2">
          <Select
            value={parsed.head || ""}
            onValueChange={(head) => {
              // Reset offset to 0 when picking a new head.
              onChange(head);
            }}
          >
            <SelectTrigger id={id} className="flex-1">
              <SelectValue placeholder="Pick a mnemonic…" />
            </SelectTrigger>
            <SelectContent className="max-h-[400px]">
              {Object.entries(VOCABULARY).map(([group, items]) => (
                <div key={group}>
                  <div className="px-2 py-1 text-[10px] uppercase tracking-wider text-muted-foreground">
                    {group}
                  </div>
                  {items.map((item) => (
                    <SelectItem key={item.value} value={item.value} className="text-xs font-mono">
                      {item.label}
                    </SelectItem>
                  ))}
                </div>
              ))}
            </SelectContent>
          </Select>

          {supportsOffset && (
            <Input
              type="number"
              value={parsed.offset || ""}
              onChange={(e) => {
                const n = parseInt(e.target.value || "0", 10);
                if (n === 0) {
                  onChange(parsed.head);
                } else {
                  const sign = n > 0 ? "+" : "";
                  onChange(`${parsed.head}${sign}${n}`);
                }
              }}
              placeholder="±N"
              className="w-24 text-xs font-mono"
              title="Offset — positive = forward, negative = back"
            />
          )}
        </div>
      )}

      {mode === "iso" && (
        <Input
          id={id}
          type="date"
          value={ISO_DATE_RE.test(value.trim()) ? value.trim() : ""}
          onChange={(e) => onChange(e.target.value)}
        />
      )}

      {mode === "freetext" && (
        <Input
          id={id}
          type="text"
          value={value}
          onChange={(e) => onChange(e.target.value)}
          placeholder="e.g., NBDOM(5), BOM-12, 2026-04-01"
          className="font-mono text-xs"
        />
      )}

      {value.trim() && !valid && (
        <p className="text-[11px] text-destructive">
          Not a recognized date or PULSE mnemonic. Examples: <code>BOM-12</code>,{" "}
          <code>EOM-1</code>, <code>PBD</code>, <code>NBDOM(5)</code>, <code>2026-04-01</code>.
        </p>
      )}

      {/* W-10: as-of resolution hint (informational only). */}
      {showAsOf && (
        <p className="text-[11px] text-muted-foreground" data-asof-hint>
          Resolved as-of {asOf}
        </p>
      )}

      {/* W-12: which token substitution this picker's value feeds. */}
      {dateTokenMode === "filename" ? (
        <p className="text-[11px] text-muted-foreground" data-token-caption>
          Substituted into <code>{"{date}"}</code> in the filename pattern.
        </p>
      ) : (
        <p className="text-[11px] text-muted-foreground" data-token-caption>
          Substituted into <code>[[ ]]</code> SQL date tokens.
        </p>
      )}
    </div>
  );
}

/**
 * Alias matching the #5 / SPEC-construct-library.md §8(f) hint name. Same
 * component — both names are exported so existing `MnemonicDateInput` call
 * sites keep working.
 */
export const DateMnemonicPicker = MnemonicDateInput;
