"use client";

/**
 * DqOutcomeControl — port-aware segmented control for the Blueprint-level DQ
 * failure disposition (SPEC-construct-library.md §7, IMPL §S7, worklist W-9).
 *
 * Picks what happens when a data-quality check fails:
 *   - quarantine — bad rows routed to the quarantine side-table; the run continues
 *   - block      — the run fails immediately on any failed check
 *   - warn       — a report is emitted; the run continues
 *
 * PORT-AWARE (the key behavior, W-9): `quarantine` is only a real option when
 * the host Blueprint actually declares a `quarantine_output` port to route bad
 * rows to. DQValidator declares it (→ offer quarantine). SchemaDriftDetection
 * does NOT (its `drift_policy` emits a `drift_report`, not a quarantine port) →
 * quarantine is hidden, leaving block + warn.
 *
 * The option set is the param's enum options (#5 passes `options`) intersected
 * with port-awareness (#3 passes the declared output port roles).
 */

import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

interface DqOutcomeControlProps {
  /** Chosen enum value. */
  value: string;
  onChange: (value: string) => void;
  /**
   * The enum options from the param definition (#5 `options`).
   * Defaults to ["quarantine", "block", "warn"].
   */
  options?: string[];
  /**
   * The host Blueprint's declared output port roles (#3 passes these, W-9).
   * `quarantine` is only offered when this includes "quarantine_output".
   */
  outputPorts?: string[];
  id?: string;
}

const DEFAULT_OPTIONS = ["quarantine", "block", "warn"];

/** One-line consequence caption per disposition. */
const CONSEQUENCE: Record<string, string> = {
  quarantine:
    "Bad rows are routed to the quarantine side-table; the run continues.",
  block: "The run fails immediately on any failed check.",
  warn: "A report is emitted; the run continues.",
};

/** Short label per disposition (falls back to the raw value for unknown enums). */
const LABEL: Record<string, string> = {
  quarantine: "Quarantine",
  block: "Block",
  warn: "Warn",
};

export function DqOutcomeControl({
  value,
  onChange,
  options = DEFAULT_OPTIONS,
  outputPorts = [],
  id,
}: DqOutcomeControlProps) {
  // W-9: port-aware filtering. `quarantine` survives only when the host
  // Blueprint declares a `quarantine_output` port to route bad rows to.
  const availableOptions = options.filter(
    (o) => o !== "quarantine" || outputPorts.includes("quarantine_output")
  );

  // Caption for the selected option (only when it's still an available pill).
  const selectedCaption = availableOptions.includes(value)
    ? CONSEQUENCE[value]
    : undefined;

  return (
    <div className="space-y-2" id={id} data-dq-outcome>
      <div role="radiogroup" className="flex flex-wrap gap-1">
        {availableOptions.map((option) => {
          const active = option === value;
          return (
            <Button
              key={option}
              type="button"
              size="sm"
              role="radio"
              aria-checked={active}
              variant={active ? "default" : "outline"}
              className="text-xs h-7"
              onClick={() => onChange(option)}
            >
              {LABEL[option] ?? option}
            </Button>
          );
        })}
      </div>

      {selectedCaption && (
        <p
          className={cn("text-[11px] text-muted-foreground")}
          data-dq-caption
        >
          {selectedCaption}
        </p>
      )}
    </div>
  );
}
