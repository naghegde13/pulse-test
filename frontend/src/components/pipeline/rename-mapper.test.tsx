/**
 * RenameMapper tests (S4, W-3 / W-4, SPEC-construct-library.md §4).
 *
 * Asserts:
 *   (a) existing rows render from a `value` map (source via ColumnPicker, the
 *       target value in the target Input)
 *   (b) "+ Add mapping" appends a blank row
 *   (c) editing the target Input calls onChange with the updated map
 *   (d) rightCell="select" renders a type Select instead of the free-text Input
 *   (e) a duplicate target renders an inline error
 *   (f) the source cell IS the schema-bound ColumnPicker (its columns are
 *       choosable once the dropdown is opened — mirrors column-picker.test.tsx)
 */

import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent, within } from "@testing-library/react";
import { RenameMapper } from "./rename-mapper";
import type { SchemaColumn } from "@/types";

const COLUMNS: SchemaColumn[] = [
  { name: "old_a", type: "string" },
  { name: "old_b", type: "long" },
  { name: "created_at", type: "timestamp" },
  { name: "amount", type: "double" },
];

describe("RenameMapper (two-column map)", () => {
  it("(a) renders existing rows from a value map", () => {
    render(
      <RenameMapper
        columns={COLUMNS}
        value={{ old_a: "new_a" }}
        onChange={vi.fn()}
      />
    );

    // Source cell (ColumnPicker, closed) shows the chosen source column.
    expect(screen.getByText("old_a")).toBeInTheDocument();
    // Target cell holds the mapped new value.
    expect(screen.getByDisplayValue("new_a")).toBeInTheDocument();
  });

  it("(b) '+ Add mapping' appends a blank row", () => {
    const onChange = vi.fn();
    render(
      <RenameMapper
        columns={COLUMNS}
        value={{ old_a: "new_a" }}
        onChange={onChange}
      />
    );

    // One target Input to start (the populated row).
    expect(screen.getAllByPlaceholderText(/new column name/i)).toHaveLength(1);
    expect(screen.getByDisplayValue("new_a")).toBeInTheDocument();

    fireEvent.click(screen.getByText("+ Add mapping"));

    // A second (blank) target Input is appended.
    expect(screen.getAllByPlaceholderText(/new column name/i)).toHaveLength(2);
  });

  it("(c) editing the target Input calls onChange with the updated map", () => {
    const onChange = vi.fn();
    render(
      <RenameMapper
        columns={COLUMNS}
        value={{ old_a: "new_a" }}
        onChange={onChange}
      />
    );

    const target = screen.getByDisplayValue("new_a");
    fireEvent.change(target, { target: { value: "renamed_a" } });

    expect(onChange).toHaveBeenLastCalledWith({ old_a: "renamed_a" });
  });

  it("(d) rightCell='select' renders a type select instead of a text input", () => {
    render(
      <RenameMapper
        columns={COLUMNS}
        value={{ old_b: "long" }}
        onChange={vi.fn()}
        rightCell="select"
      />
    );

    // The Radix Select trigger is exposed as a combobox...
    const combobox = screen.getByRole("combobox");
    expect(combobox).toBeInTheDocument();
    // ...and the free-text target Input is NOT present.
    expect(
      screen.queryByPlaceholderText(/new column name/i)
    ).not.toBeInTheDocument();
    // The currently-selected type is shown on the trigger (scoped to the
    // combobox — "long" also appears as the source column's type badge).
    expect(within(combobox).getByText("long")).toBeInTheDocument();
  });

  it("(e) a duplicate target shows an inline error", () => {
    const onChange = vi.fn();
    render(
      <RenameMapper
        columns={COLUMNS}
        value={{ old_a: "dup", old_b: "dup" }}
        onChange={onChange}
      />
    );

    // Both rows collide on the same target name → an inline error renders.
    const errors = screen.getAllByText(/duplicate target name/i);
    expect(errors.length).toBeGreaterThan(0);
  });

  it("(f) the source cell uses ColumnPicker (schema columns are choosable)", () => {
    render(
      <RenameMapper columns={COLUMNS} value={{}} onChange={vi.fn()} />
    );

    // The empty map renders a single blank row; its source cell is the
    // ColumnPicker button. Open it and assert the schema columns appear,
    // mirroring how column-picker.test.tsx opens the dropdown.
    const pickerButton = screen.getByText(/source column/i);
    fireEvent.click(pickerButton);

    for (const col of COLUMNS) {
      expect(screen.getByText(col.name)).toBeInTheDocument();
    }
  });

  it("editing two rows with the same source does not collapse them in the UI", () => {
    render(
      <RenameMapper columns={COLUMNS} value={{}} onChange={vi.fn()} />
    );

    // Start with one blank row, add a second.
    fireEvent.click(screen.getByText("+ Add mapping"));

    // Two source pickers (each shows the source placeholder) remain visible
    // even before any source is chosen — internal rows are the source of truth.
    const sourceCells = screen.getAllByText(/source column/i);
    expect(sourceCells).toHaveLength(2);

    // And two target inputs exist.
    expect(screen.getAllByPlaceholderText(/new column name/i)).toHaveLength(2);
  });

  it("rightCell='value' uses a plain value Input", () => {
    render(
      <RenameMapper
        columns={COLUMNS}
        value={{ amount: "0" }}
        onChange={vi.fn()}
        rightCell="value"
      />
    );

    // Default placeholder for the value variant, and the existing value shows.
    expect(screen.getByDisplayValue("0")).toBeInTheDocument();
    // No type combobox for the value variant.
    expect(screen.queryByRole("combobox")).not.toBeInTheDocument();
  });

  it("a row with a source but empty target shows a 'required' error", () => {
    // The source cell falls back to a text Input when columns=[] (ColumnPicker
    // built-in), letting us set a source without opening a dropdown.
    const { container } = render(
      <RenameMapper columns={[]} value={{ old_a: "" }} onChange={vi.fn()} />
    );

    // old_a has a source but an empty target → inline error.
    expect(within(container).getByText(/target is required/i)).toBeInTheDocument();
  });
});
