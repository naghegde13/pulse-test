/**
 * DqOutcomeControl tests (W-9, SPEC-construct-library.md §7).
 *
 * Asserts:
 *   - renders all three dispositions when the host declares a quarantine_output port
 *   - quarantine is HIDDEN when outputPorts lacks quarantine_output (port-aware, W-9)
 *   - clicking an option calls onChange with that enum value
 *   - the selected option's consequence caption is visible
 *   - respects a custom `options` prop (the enum from #5)
 */

import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { DqOutcomeControl } from "./dq-outcome-control";

describe("DqOutcomeControl", () => {
  it("renders all three options when the host declares a quarantine_output port", () => {
    render(
      <DqOutcomeControl
        value="block"
        onChange={vi.fn()}
        outputPorts={["validated_output", "quarantine_output"]}
      />
    );

    expect(screen.getByText("Quarantine")).toBeInTheDocument();
    expect(screen.getByText("Block")).toBeInTheDocument();
    expect(screen.getByText("Warn")).toBeInTheDocument();
  });

  it("hides quarantine when outputPorts lacks quarantine_output (port-aware, W-9)", () => {
    render(
      <DqOutcomeControl
        value="warn"
        onChange={vi.fn()}
        outputPorts={["drift_report"]}
      />
    );

    // No quarantine pill — the host (e.g. SchemaDriftDetection) declares no
    // quarantine_output port, so bad rows have nowhere to go.
    expect(screen.queryByText("Quarantine")).not.toBeInTheDocument();

    // ...but block + warn still render.
    expect(screen.getByText("Block")).toBeInTheDocument();
    expect(screen.getByText("Warn")).toBeInTheDocument();
  });

  it("clicking an option calls onChange with that enum value", () => {
    const onChange = vi.fn();
    render(
      <DqOutcomeControl
        value="warn"
        onChange={onChange}
        outputPorts={["validated_output", "quarantine_output"]}
      />
    );

    fireEvent.click(screen.getByText("Quarantine"));
    expect(onChange).toHaveBeenCalledTimes(1);
    expect(onChange).toHaveBeenCalledWith("quarantine");
  });

  it("shows the selected option's consequence caption", () => {
    render(
      <DqOutcomeControl
        value="block"
        onChange={vi.fn()}
        outputPorts={["validated_output", "quarantine_output"]}
      />
    );

    expect(
      screen.getByText("The run fails immediately on any failed check.")
    ).toBeInTheDocument();
  });

  it("respects a custom `options` prop", () => {
    render(
      <DqOutcomeControl
        value="warn"
        onChange={vi.fn()}
        options={["warn", "block"]}
        outputPorts={["validated_output", "quarantine_output"]}
      />
    );

    // Only the supplied enum members render — even though the host declares a
    // quarantine_output port, "quarantine" isn't in `options`, so it's absent.
    expect(screen.getByText("Warn")).toBeInTheDocument();
    expect(screen.getByText("Block")).toBeInTheDocument();
    expect(screen.queryByText("Quarantine")).not.toBeInTheDocument();
  });
});
