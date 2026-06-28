/**
 * ExpressionInput tests (S2, SPEC-construct-library.md §2 + ADR 0024).
 *
 * Asserts:
 *   - renders the textarea seeded with the current value
 *   - typing into the textarea fires onChange with the new string
 *   - clicking the "Insert date token" affordance and choosing a mnemonic
 *     calls onChange with a `[[ MNEMONIC ]]`-shaped token
 *   - when the textarea has a mid-string caret, the token is spliced at that
 *     position (preserving the text on both sides)
 *
 * The Calcite validation call (POST /api/v1/expressions/validate) is mocked
 * so the test never makes a real network request.
 */

import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { ExpressionInput } from "./expression-input";

vi.mock("@/lib/api", () => ({
  api: {
    post: vi.fn().mockResolvedValue({
      valid: true,
      outputType: "boolean",
      referencedColumns: [],
      diagnostics: [],
    }),
  },
}));

describe("ExpressionInput (S2 date-token insertion)", () => {
  it("renders the textarea seeded with the current value", () => {
    render(
      <ExpressionInput
        value="balance > 0"
        onChange={vi.fn()}
        inputSchemas={[]}
        kind="predicate"
      />
    );

    const textarea = screen.getByRole("textbox") as HTMLTextAreaElement;
    expect(textarea.tagName).toBe("TEXTAREA");
    expect(textarea.value).toBe("balance > 0");
  });

  it("typing into the textarea fires onChange", () => {
    const onChange = vi.fn();
    render(
      <ExpressionInput
        value=""
        onChange={onChange}
        inputSchemas={[]}
        kind="value"
      />
    );

    const textarea = screen.getByRole("textbox");
    fireEvent.change(textarea, { target: { value: "current_date" } });
    expect(onChange).toHaveBeenCalledWith("current_date");
  });

  it("choosing a mnemonic calls onChange with a [[ MNEMONIC ]] token", () => {
    const onChange = vi.fn();
    render(
      <ExpressionInput
        value=""
        onChange={onChange}
        inputSchemas={[]}
        kind="predicate"
      />
    );

    // Open the insert affordance, then pick a mnemonic.
    fireEvent.click(screen.getByText(/insert date token/i));
    fireEvent.click(screen.getByRole("menuitem", { name: /insert PBD token/i }));

    expect(onChange).toHaveBeenCalledTimes(1);
    const arg = onChange.mock.calls[0][0] as string;
    expect(arg).toContain("[[ ");
    expect(arg).toContain(" ]]");
    expect(arg).toContain("PBD");
    // Exact token format per ADR 0024.
    expect(arg).toContain("[[ PBD ]]");
  });

  it("splices the token at a mid-string caret position", () => {
    const onChange = vi.fn();
    render(
      <ExpressionInput
        value="run_dt = "
        onChange={onChange}
        inputSchemas={[]}
        kind="predicate"
      />
    );

    const textarea = screen.getByRole("textbox") as HTMLTextAreaElement;
    // Place a collapsed caret at the very end of "run_dt = " (index 9).
    const caret = "run_dt = ".length;
    textarea.focus();
    textarea.setSelectionRange(caret, caret);

    fireEvent.click(screen.getByText(/insert date token/i));
    fireEvent.click(screen.getByRole("menuitem", { name: /insert EOM token/i }));

    expect(onChange).toHaveBeenCalledTimes(1);
    const arg = onChange.mock.calls[0][0] as string;
    // Token spliced exactly at the caret: left half + token + right half.
    expect(arg).toBe("run_dt = [[ EOM ]]");
  });

  it("splices the token between the two halves of a mid-string caret", () => {
    const onChange = vi.fn();
    render(
      <ExpressionInput
        value="a()"
        onChange={onChange}
        inputSchemas={[]}
        kind="value"
      />
    );

    const textarea = screen.getByRole("textbox") as HTMLTextAreaElement;
    // Caret sits between "a(" and ")" — index 2.
    textarea.focus();
    textarea.setSelectionRange(2, 2);

    fireEvent.click(screen.getByText(/insert date token/i));
    fireEvent.click(screen.getByRole("menuitem", { name: /insert TODAY token/i }));

    const arg = onChange.mock.calls[0][0] as string;
    const idx = arg.indexOf("[[ TODAY ]]");
    expect(idx).toBeGreaterThan(-1);
    // Left half preserved before the token, right half after it.
    expect(arg.slice(0, idx)).toBe("a(");
    expect(arg.slice(idx + "[[ TODAY ]]".length)).toBe(")");
  });
});
