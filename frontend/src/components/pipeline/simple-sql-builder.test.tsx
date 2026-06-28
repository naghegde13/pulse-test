/**
 * SimpleSqlBuilder tests (S1b, SPEC-construct-library.md §1.1b, ADR 0024).
 *
 * Asserts:
 *   - renders the SQL textarea with the current value; typing fires onChange
 *   - a Validate button POSTs to /api/v1/sql/source/validate with the SQL +
 *     connectorInstanceId
 *   - a successful validate result renders the returned result-schema columns
 *   - a failed validate result (valid:false) renders the source-DB error
 *   - when the validate endpoint REJECTS (not built yet) a non-fatal
 *     "unavailable / not yet implemented" notice renders and nothing throws
 *
 * The backend JDBC-prepare validator endpoint is OUT of this lane — these
 * tests mock the api so the component is exercised structurally.
 */

import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { SimpleSqlBuilder } from "./simple-sql-builder";
import { api } from "@/lib/api";

vi.mock("@/lib/api", () => ({ api: { post: vi.fn() } }));

beforeEach(() => {
  (api.post as ReturnType<typeof vi.fn>).mockReset();
});

describe("SimpleSqlBuilder", () => {
  it("(a) renders the SQL textarea with the current value; typing fires onChange", () => {
    const onChange = vi.fn();
    render(
      <SimpleSqlBuilder value="SELECT 1" onChange={onChange} />
    );

    const textarea = screen.getByDisplayValue("SELECT 1");
    expect(textarea.tagName).toBe("TEXTAREA");

    fireEvent.change(textarea, {
      target: { value: "SELECT loan_id FROM loans" },
    });
    expect(onChange).toHaveBeenCalledWith("SELECT loan_id FROM loans");
  });

  it("(b) clicking Validate POSTs to /api/v1/sql/source/validate with sql + connectorInstanceId", async () => {
    (api.post as ReturnType<typeof vi.fn>).mockResolvedValue({
      valid: true,
      columns: [{ name: "loan_id", type: "long" }],
    });

    render(
      <SimpleSqlBuilder
        value="SELECT loan_id FROM loans"
        onChange={vi.fn()}
        connectorInstanceId="conn-123"
      />
    );

    fireEvent.click(screen.getByRole("button", { name: /validate/i }));

    await waitFor(() => expect(api.post).toHaveBeenCalledTimes(1));
    expect(api.post).toHaveBeenCalledWith("/api/v1/sql/source/validate", {
      connectorInstanceId: "conn-123",
      sql: "SELECT loan_id FROM loans",
    });
  });

  it("(c) renders the returned result-schema columns on a successful validate", async () => {
    (api.post as ReturnType<typeof vi.fn>).mockResolvedValue({
      valid: true,
      columns: [
        { name: "loan_id", type: "long" },
        { name: "balance", type: "double" },
      ],
    });

    render(
      <SimpleSqlBuilder
        value="SELECT loan_id, balance FROM loans"
        onChange={vi.fn()}
        connectorInstanceId="conn-123"
      />
    );

    fireEvent.click(screen.getByRole("button", { name: /validate/i }));

    expect(await screen.findByText("loan_id")).toBeInTheDocument();
    expect(await screen.findByText("balance")).toBeInTheDocument();
    // The "✓ valid" status chip is shown too (matched precisely so it doesn't
    // collide with the "…validates its own dialect" caption copy).
    expect(await screen.findByText(/✓ valid/)).toBeInTheDocument();
  });

  it("(d) renders the source-DB error when the validate result is invalid", async () => {
    (api.post as ReturnType<typeof vi.fn>).mockResolvedValue({
      valid: false,
      error: "ORA-00942: table does not exist",
    });

    render(
      <SimpleSqlBuilder
        value="SELECT * FROM nope"
        onChange={vi.fn()}
        connectorInstanceId="conn-123"
      />
    );

    fireEvent.click(screen.getByRole("button", { name: /validate/i }));

    expect(
      await screen.findByText("ORA-00942: table does not exist")
    ).toBeInTheDocument();
  });

  it("(e) renders a non-fatal 'unavailable / not yet implemented' notice when the endpoint rejects", async () => {
    (api.post as ReturnType<typeof vi.fn>).mockRejectedValue(new Error("404"));

    render(
      <SimpleSqlBuilder
        value="SELECT 1"
        onChange={vi.fn()}
        connectorInstanceId="conn-123"
      />
    );

    fireEvent.click(screen.getByRole("button", { name: /validate/i }));

    // Component must NOT crash; it shows a graceful unavailable message.
    expect(
      await screen.findByText(/not yet implemented/i)
    ).toBeInTheDocument();
    // The "validator unavailable" status chip is also shown (exact match so it
    // doesn't collide with the longer "Validator unavailable — …" message).
    expect(screen.getByText("validator unavailable")).toBeInTheDocument();
  });
});
