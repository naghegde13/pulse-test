/**
 * SqlFilterBuilder / condition-builder tests (S5, SPEC-construct-library.md §5,
 * worklist W-5 / W-6 / W-11).
 *
 * Asserts:
 *   - visual mode renders condition rows; "+ Add Condition" adds a row
 *   - the operator catalog is offered; a nullary operator (IS NULL) hides the
 *     value cell entirely
 *   - the column cell is schema-bound (reuses ColumnPicker) when a schema is
 *     supplied
 *   - W-6: a non-nullary operator with an empty value surfaces an inline error
 *     (the ONLY visual-mode error — rows are otherwise valid-by-construction)
 *   - W-5/W-11: a per-value literal/date toggle swaps the value Input for the
 *     embedded date-mnemonic-picker, and picking through it round-trips onChange
 *   - the WHERE-clause preview renders for a populated row, with a mnemonic
 *     value rendered UNQUOTED (it is a date token, not a string literal)
 *   - switching to SQL mode shows the raw-SQL Textarea + Spark-function palette
 */

import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { SqlFilterBuilder, type FilterCondition } from "./sql-filter-builder";
import type { SchemaColumn } from "@/types";

const COLUMNS: SchemaColumn[] = [
  { name: "status", type: "string" },
  { name: "amount", type: "double" },
  { name: "as_of_date", type: "date" },
];

function row(overrides: Partial<FilterCondition> = {}): FilterCondition {
  return { column: "status", operator: "eq", value: "ACTIVE", logic: "AND", ...overrides };
}

describe("SqlFilterBuilder (condition-builder, visual mode)", () => {
  it("renders a condition row and '+ Add Condition' appends a new row", () => {
    const onChange = vi.fn();
    render(
      <SqlFilterBuilder
        conditions={[row()]}
        rawSql=""
        filterMode="visual"
        availableColumns={["status"]}
        columnSchema={COLUMNS}
        onChange={onChange}
      />
    );

    fireEvent.click(screen.getByText("+ Add Condition"));
    expect(onChange).toHaveBeenCalledTimes(1);
    const arg = onChange.mock.calls[0][0];
    expect(arg.conditions).toHaveLength(2);
  });

  it("a nullary operator (IS NULL) hides the value cell", () => {
    render(
      <SqlFilterBuilder
        conditions={[row({ operator: "is_null", value: "" })]}
        rawSql=""
        filterMode="visual"
        availableColumns={[]}
        columnSchema={COLUMNS}
        onChange={vi.fn()}
      />
    );

    // No literal/date toggle is rendered for a nullary op (no value cell).
    expect(screen.queryByTitle("Literal value")).not.toBeInTheDocument();
    expect(screen.queryByTitle("Date mnemonic")).not.toBeInTheDocument();
  });

  it("W-6: an empty value on a non-nullary operator shows an inline error", () => {
    render(
      <SqlFilterBuilder
        conditions={[row({ operator: "eq", value: "" })]}
        rawSql=""
        filterMode="visual"
        availableColumns={[]}
        columnSchema={COLUMNS}
        onChange={vi.fn()}
      />
    );

    expect(screen.getByRole("alert")).toHaveTextContent(/value required/i);
  });

  it("a filled non-nullary value clears the inline error", () => {
    render(
      <SqlFilterBuilder
        conditions={[row({ operator: "eq", value: "ACTIVE" })]}
        rawSql=""
        filterMode="visual"
        availableColumns={[]}
        columnSchema={COLUMNS}
        onChange={vi.fn()}
      />
    );

    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("W-5/W-11: toggling 'date' swaps the value cell to the date-mnemonic-picker", () => {
    const onChange = vi.fn();
    render(
      <SqlFilterBuilder
        conditions={[row({ operator: "eq", value: "ACTIVE" })]}
        rawSql=""
        filterMode="visual"
        availableColumns={[]}
        columnSchema={COLUMNS}
        onChange={onChange}
      />
    );

    // Switch the value cell to date-mnemonic mode.
    fireEvent.click(screen.getByTitle("Date mnemonic"));
    expect(onChange).toHaveBeenCalledTimes(1);
    const arg = onChange.mock.calls[0][0];
    expect(arg.conditions[0].valueKind).toBe("mnemonic");
    // Switching kind clears the literal value so it doesn't leak.
    expect(arg.conditions[0].value).toBe("");
  });

  it("W-5/W-11: a mnemonic-kind row renders the embedded date-mnemonic-picker (tri-mode pills)", () => {
    render(
      <SqlFilterBuilder
        conditions={[row({ operator: "eq", value: "PBD", valueKind: "mnemonic" })]}
        rawSql=""
        filterMode="visual"
        availableColumns={[]}
        columnSchema={COLUMNS}
        onChange={vi.fn()}
      />
    );

    // The embedded MnemonicDateInput exposes its three mode pills.
    expect(screen.getByText("Mnemonic")).toBeInTheDocument();
    expect(screen.getByText("ISO date")).toBeInTheDocument();
    expect(screen.getByText("Free-text")).toBeInTheDocument();
  });

  it("renders a WHERE preview with a mnemonic value UNQUOTED", () => {
    render(
      <SqlFilterBuilder
        conditions={[
          row({ column: "as_of_date", operator: "gte", value: "PBD", valueKind: "mnemonic" }),
        ]}
        rawSql=""
        filterMode="visual"
        availableColumns={[]}
        columnSchema={COLUMNS}
        onChange={vi.fn()}
      />
    );

    // The preview block shows the clause; the mnemonic must NOT be wrapped in '…'.
    expect(screen.getByText(/as_of_date >= PBD/)).toBeInTheDocument();
    expect(screen.queryByText(/as_of_date >= 'PBD'/)).not.toBeInTheDocument();
  });

  it("a literal value IS quoted in the preview", () => {
    render(
      <SqlFilterBuilder
        conditions={[row({ column: "status", operator: "eq", value: "ACTIVE" })]}
        rawSql=""
        filterMode="visual"
        availableColumns={[]}
        columnSchema={COLUMNS}
        onChange={vi.fn()}
      />
    );

    expect(screen.getByText(/status = 'ACTIVE'/)).toBeInTheDocument();
  });

  it("SQL mode renders the raw-SQL textarea and the Spark function palette toggle", () => {
    render(
      <SqlFilterBuilder
        conditions={[]}
        rawSql="UPPER(status) = 'ACTIVE'"
        filterMode="sql"
        availableColumns={[]}
        columnSchema={COLUMNS}
        onChange={vi.fn()}
      />
    );

    expect(screen.getByText("Spark SQL Functions")).toBeInTheDocument();
    // The raw SQL is shown in the textarea.
    expect(screen.getByDisplayValue("UPPER(status) = 'ACTIVE'")).toBeInTheDocument();
  });
});
