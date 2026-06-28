/**
 * MnemonicDateInput tests — S8 date-mnemonic-picker EXTEND.
 *
 * Covers the base tri-mode behavior plus the W-10/W-11/W-12 extensions:
 *   - three mode pills render
 *   - detectMode drives the active mode (mnemonic vs ISO)
 *   - offset stepper presence keyed off the head's supportsOffset
 *   - invalid token surfaces the recognition error
 *   - W-10 as-of hint renders for mnemonic values
 *   - W-11 embedded compact variant still renders all three pills
 *   - W-12 dateTokenMode caption
 *   - DateMnemonicPicker alias === MnemonicDateInput
 */

import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import {
  MnemonicDateInput,
  DateMnemonicPicker,
} from "@/components/pipeline/mnemonic-date-input";

// jsdom doesn't implement Element.scrollIntoView, but Radix Select calls it
// when its content portal mounts. Stub once at module load.
if (
  typeof Element !== "undefined" &&
  typeof Element.prototype.scrollIntoView !== "function"
) {
  Element.prototype.scrollIntoView = function () {
    /* no-op for tests */
  };
}
// PointerEvent / hasPointerCapture is also missing in jsdom; Radix needs both
// to drive Select.Trigger interactions. Patch only if absent.
if (
  typeof Element !== "undefined" &&
  typeof Element.prototype.hasPointerCapture !== "function"
) {
  Element.prototype.hasPointerCapture = () => false;
}
if (
  typeof Element !== "undefined" &&
  typeof Element.prototype.releasePointerCapture !== "function"
) {
  Element.prototype.releasePointerCapture = () => {
    /* no-op */
  };
}

const noop = () => {};

describe("MnemonicDateInput — mode pills", () => {
  it("renders the three mode pills", () => {
    render(<MnemonicDateInput id="d" value="" onChange={noop} />);
    expect(
      screen.getByRole("button", { name: "Mnemonic" })
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "ISO date" })
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Free-text" })
    ).toBeInTheDocument();
  });
});

describe("MnemonicDateInput — detectMode", () => {
  it("a mnemonic value activates Mnemonic mode (shows the combobox)", () => {
    render(<MnemonicDateInput id="d" value="BOM" onChange={noop} />);
    // The mnemonic mode renders a Select combobox trigger.
    expect(screen.getByRole("combobox")).toBeInTheDocument();
  });

  it("an ISO value activates ISO mode (shows a date input, no combobox)", () => {
    const { container } = render(
      <MnemonicDateInput id="d" value="2026-04-01" onChange={noop} />
    );
    expect(screen.queryByRole("combobox")).not.toBeInTheDocument();
    const dateInput = container.querySelector('input[type="date"]');
    expect(dateInput).toBeInTheDocument();
    expect(dateInput).toHaveValue("2026-04-01");
  });
});

describe("MnemonicDateInput — offset stepper", () => {
  it("shows the ±N number input for an offset-supporting head (BOM)", () => {
    const { container } = render(
      <MnemonicDateInput id="d" value="BOM" onChange={noop} />
    );
    expect(container.querySelector('input[type="number"]')).toBeInTheDocument();
  });

  it("hides the ±N number input for a non-offset head (TODAY)", () => {
    const { container } = render(
      <MnemonicDateInput id="d" value="TODAY" onChange={noop} />
    );
    expect(
      container.querySelector('input[type="number"]')
    ).not.toBeInTheDocument();
  });
});

describe("MnemonicDateInput — validation", () => {
  it("an invalid token shows the recognition error", () => {
    // "!!!" fails both the ISO shape and the MNEMONIC_RE shape (non-[A-Z_]
    // characters), so isValid() is false and the error line renders.
    render(<MnemonicDateInput id="d" value="!!!" onChange={noop} />);
    expect(
      screen.getByText(/Not a recognized date or PULSE mnemonic/i)
    ).toBeInTheDocument();
  });
});

describe("MnemonicDateInput — W-10 as-of hint", () => {
  it("renders the 'Resolved as-of' line for a mnemonic value", () => {
    render(
      <MnemonicDateInput id="d" value="BOM" onChange={noop} asOf="2026-06-16" />
    );
    expect(screen.getByText("Resolved as-of 2026-06-16")).toBeInTheDocument();
  });

  it("does not render the as-of line when asOf is absent", () => {
    render(<MnemonicDateInput id="d" value="BOM" onChange={noop} />);
    expect(screen.queryByText(/Resolved as-of/i)).not.toBeInTheDocument();
  });

  it("does not render the as-of line for an ISO (non-mnemonic) value", () => {
    render(
      <MnemonicDateInput
        id="d"
        value="2026-04-01"
        onChange={noop}
        asOf="2026-06-16"
      />
    );
    expect(screen.queryByText(/Resolved as-of/i)).not.toBeInTheDocument();
  });
});

describe("MnemonicDateInput — W-11 embedded variant", () => {
  it("renders all three pills in the compact embedded variant", () => {
    render(<MnemonicDateInput id="d" value="BOM" onChange={noop} embedded />);
    expect(
      screen.getByRole("button", { name: "Mnemonic" })
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "ISO date" })
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Free-text" })
    ).toBeInTheDocument();
    // Still functional: the mnemonic combobox is present.
    expect(screen.getByRole("combobox")).toBeInTheDocument();
  });
});

describe("MnemonicDateInput — W-12 dateTokenMode caption", () => {
  it("defaults to the SQL token caption", () => {
    render(<MnemonicDateInput id="d" value="BOM" onChange={noop} />);
    expect(screen.getByText(/SQL date tokens/i)).toBeInTheDocument();
  });

  it("shows the filename {date} caption when dateTokenMode='filename'", () => {
    render(
      <MnemonicDateInput
        id="d"
        value="BOM"
        onChange={noop}
        dateTokenMode="filename"
      />
    );
    expect(screen.getByText(/filename pattern/i)).toBeInTheDocument();
  });
});

describe("DateMnemonicPicker alias", () => {
  it("is the same component as MnemonicDateInput", () => {
    expect(DateMnemonicPicker).toBe(MnemonicDateInput);
  });

  it("renders identically (three pills) via the alias", () => {
    render(<DateMnemonicPicker id="d" value="" onChange={noop} />);
    expect(
      screen.getByRole("button", { name: "Mnemonic" })
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "ISO date" })
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Free-text" })
    ).toBeInTheDocument();
  });
});
