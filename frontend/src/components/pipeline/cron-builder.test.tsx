/**
 * CronBuilder tests (S6 / W-8 — standalone schedule construct hosted by
 * sensing-config).
 *
 * Asserts:
 *   - a known preset value selects that preset and shows its human label
 *     (no custom Input rendered)
 *   - a non-preset value is treated as "Custom" and renders the raw cron Input,
 *     editing which fires onChange
 *   - summarizeCron maps presets to labels and echoes custom expressions
 */

import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { CronBuilder, summarizeCron, CRON_PRESETS } from "./cron-builder";

describe("CronBuilder", () => {
  it("a preset value shows its label and does NOT render the custom Input", () => {
    const { container } = render(<CronBuilder value="@daily" onChange={vi.fn()} />);

    // Human summary for the preset is shown (target the summary node directly —
    // the label also appears inside the Radix Select trigger).
    const summary = container.querySelector("[data-cron-summary]");
    expect(summary).toHaveTextContent(/Daily at midnight/i);
    // No raw-cron Input while a preset is selected.
    expect(
      screen.queryByLabelText("Custom cron expression")
    ).not.toBeInTheDocument();
  });

  it("a non-preset value falls into custom mode and renders the raw cron Input", () => {
    const onChange = vi.fn();
    render(<CronBuilder value="15 3 * * 2" onChange={onChange} />);

    const input = screen.getByLabelText("Custom cron expression");
    expect(input).toBeInTheDocument();
    expect((input as HTMLInputElement).value).toBe("15 3 * * 2");

    fireEvent.change(input, { target: { value: "0 4 * * *" } });
    expect(onChange).toHaveBeenCalledWith("0 4 * * *");
  });

  it("shows a 'Custom: …' summary for an unknown expression", () => {
    render(<CronBuilder value="15 3 * * 2" onChange={vi.fn()} />);
    expect(screen.getByText(/Custom: 15 3 \* \* 2/)).toBeInTheDocument();
  });

  it("summarizeCron maps presets to labels and echoes custom/empty", () => {
    expect(summarizeCron("@daily")).toMatch(/Daily at midnight/i);
    expect(summarizeCron("0 9 * * 1-5")).toMatch(/Weekdays/i);
    expect(summarizeCron("99 99 * * *")).toBe("Custom: 99 99 * * *");
    expect(summarizeCron("")).toBe("No schedule set");
  });

  it("every preset has a value and a label", () => {
    for (const p of CRON_PRESETS) {
      expect(p.value).toBeTruthy();
      expect(p.label).toBeTruthy();
    }
  });
});
