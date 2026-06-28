/**
 * ColumnPicker / MultiColumnPicker tests (W-2, SPEC-construct-library.md §3).
 *
 * Asserts:
 *   - single ColumnPicker lists columns, shows the type badge, and calls
 *     onChange with the chosen column name
 *   - filterTypes narrows the choosable set by a substring match on c.type
 *   - empty schema (columns=[]) falls back to a plain text Input the user can
 *     type into (typing fires onChange)
 *   - loading=true renders the "Loading schema…" skeleton and NOT the text
 *     Input fallback (loading vs empty are distinct states)
 *   - MultiColumnPicker badge-toggle adds/removes a selection via onChange;
 *     loading=true shows the skeleton
 */

import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { ColumnPicker, MultiColumnPicker } from "./column-picker";
import type { SchemaColumn } from "@/types";

const COLUMNS: SchemaColumn[] = [
  { name: "id", type: "long" },
  { name: "name", type: "string" },
  { name: "created_at", type: "timestamp" },
  { name: "birth_date", type: "date" },
  { name: "amount", type: "double" },
];

describe("ColumnPicker (single-select)", () => {
  it("renders columns with type badges and calls onChange with the column name", () => {
    const onChange = vi.fn();
    render(<ColumnPicker columns={COLUMNS} value="" onChange={onChange} />);

    // Closed state shows the placeholder, not the column list yet.
    expect(screen.getByText(/select column/i)).toBeInTheDocument();

    // Open the dropdown.
    fireEvent.click(screen.getByRole("button"));

    // Every column is now choosable...
    for (const col of COLUMNS) {
      expect(screen.getByText(col.name)).toBeInTheDocument();
    }
    // ...and the type badge is rendered (e.g. "timestamp").
    expect(screen.getByText("timestamp")).toBeInTheDocument();

    // Selecting a column calls onChange with its name.
    fireEvent.click(screen.getByText("name"));
    expect(onChange).toHaveBeenCalledTimes(1);
    expect(onChange).toHaveBeenCalledWith("name");
  });

  it("filterTypes narrows the choosable set by substring match on c.type", () => {
    render(
      <ColumnPicker
        columns={COLUMNS}
        value=""
        onChange={vi.fn()}
        filterTypes={["timestamp", "date"]}
      />
    );

    fireEvent.click(screen.getByRole("button"));

    // Only the temporal columns survive the filter.
    expect(screen.getByText("created_at")).toBeInTheDocument();
    expect(screen.getByText("birth_date")).toBeInTheDocument();

    // Non-temporal columns are filtered out.
    expect(screen.queryByText("id")).not.toBeInTheDocument();
    expect(screen.queryByText("amount")).not.toBeInTheDocument();
    expect(screen.queryByText("name")).not.toBeInTheDocument();
  });

  it("empty schema renders a text Input fallback the user can type into", () => {
    const onChange = vi.fn();
    render(
      <ColumnPicker
        columns={[]}
        value=""
        onChange={onChange}
        placeholder="Type a column name"
      />
    );

    const input = screen.getByPlaceholderText("Type a column name");
    expect(input.tagName).toBe("INPUT");

    fireEvent.change(input, { target: { value: "custom_col" } });
    expect(onChange).toHaveBeenCalledWith("custom_col");
  });

  it("loading=true renders the skeleton and NOT the text Input fallback", () => {
    render(
      <ColumnPicker
        columns={[]}
        value=""
        onChange={vi.fn()}
        loading
        placeholder="Type a column name"
      />
    );

    // Skeleton placeholder is shown...
    expect(screen.getByText(/loading schema/i)).toBeInTheDocument();
    // ...and the empty-state text Input is NOT rendered (loading != empty).
    expect(
      screen.queryByPlaceholderText("Type a column name")
    ).not.toBeInTheDocument();
  });
});

describe("MultiColumnPicker (badge-toggle multi-select)", () => {
  it("clicking a badge toggles selection via onChange", () => {
    const onChange = vi.fn();
    const { rerender } = render(
      <MultiColumnPicker
        columns={COLUMNS}
        selected={[]}
        onChange={onChange}
      />
    );

    // Adding: clicking an unselected badge appends it.
    fireEvent.click(screen.getByText("id"));
    expect(onChange).toHaveBeenLastCalledWith(["id"]);

    // Removing: re-render as already-selected, then click removes it.
    rerender(
      <MultiColumnPicker
        columns={COLUMNS}
        selected={["id"]}
        onChange={onChange}
      />
    );
    fireEvent.click(screen.getByText("id"));
    expect(onChange).toHaveBeenLastCalledWith([]);
  });

  it("loading=true renders the skeleton instead of the badges/Input", () => {
    render(
      <MultiColumnPicker
        columns={[]}
        selected={[]}
        onChange={vi.fn()}
        loading
        fallbackPlaceholder="col1, col2, …"
      />
    );

    expect(screen.getByText(/loading schema/i)).toBeInTheDocument();
    // The comma-separated text fallback must NOT be present while loading.
    expect(
      screen.queryByPlaceholderText("col1, col2, …")
    ).not.toBeInTheDocument();
  });
});
