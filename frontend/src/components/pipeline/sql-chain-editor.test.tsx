/**
 * SqlChainEditor tests (S1a rich sql-builder + S9 per-step data-preview).
 *
 * STRUCTURALLY-BUILT control: the per-step preview endpoint (S10) and the
 * schema-deriving Calcite validator (CALCITE-PHASE-2) are BACKEND DEPENDENCIES
 * out of this lane. These tests drive the control surface and the graceful
 * degrade when the endpoint is absent.
 *
 * Asserts:
 *   - renders the chain steps; "+ Add Step" appends a step
 *   - per-step name edit, materialize toggle, remove, and reorder fire onChange
 *   - `[[ … ]]` insertion appends a date token to a step's SQL
 *   - "Preview step" calls the (S10) endpoint and renders the returned rows +
 *     schema on success
 *   - a 404 / not-implemented endpoint degrades to a non-fatal "unavailable"
 *     message (does NOT crash)
 */

import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { SqlChainEditor, type SqlChainStep } from "./sql-chain-editor";
import { api } from "@/lib/api";

vi.mock("@/lib/api", () => ({ api: { post: vi.fn() } }));

const STEPS: SqlChainStep[] = [
  { name: "stage_clean", sql: "SELECT * FROM input", materialize: false },
  { name: "stage_agg", sql: "SELECT k, SUM(v) FROM stage_clean GROUP BY k", materialize: true },
];

beforeEach(() => {
  (api.post as ReturnType<typeof vi.fn>).mockReset();
});

describe("SqlChainEditor (S1a chain editor)", () => {
  it("renders the chain steps", () => {
    render(<SqlChainEditor steps={STEPS} onChange={vi.fn()} />);
    expect(screen.getByDisplayValue("stage_clean")).toBeInTheDocument();
    expect(screen.getByDisplayValue("stage_agg")).toBeInTheDocument();
  });

  it("'+ Add Step' appends a new step", () => {
    const onChange = vi.fn();
    render(<SqlChainEditor steps={STEPS} onChange={onChange} />);
    fireEvent.click(screen.getByText("+ Add Step"));
    expect(onChange).toHaveBeenCalledTimes(1);
    expect(onChange.mock.calls[0][0]).toHaveLength(3);
  });

  it("editing a step name fires onChange with the new name", () => {
    const onChange = vi.fn();
    render(<SqlChainEditor steps={STEPS} onChange={onChange} />);
    fireEvent.change(screen.getByDisplayValue("stage_clean"), {
      target: { value: "stage_renamed" },
    });
    expect(onChange.mock.calls[0][0][0].name).toBe("stage_renamed");
  });

  it("toggling materialize fires onChange", () => {
    const onChange = vi.fn();
    render(<SqlChainEditor steps={STEPS} onChange={onChange} />);
    fireEvent.click(screen.getByLabelText("Materialize step 1"));
    expect(onChange.mock.calls[0][0][0].materialize).toBe(true);
  });

  it("removing a step drops it from the chain", () => {
    const onChange = vi.fn();
    render(<SqlChainEditor steps={STEPS} onChange={onChange} />);
    fireEvent.click(screen.getByLabelText("Remove step 1"));
    expect(onChange.mock.calls[0][0]).toHaveLength(1);
    expect(onChange.mock.calls[0][0][0].name).toBe("stage_agg");
  });

  it("reordering swaps two steps", () => {
    const onChange = vi.fn();
    render(<SqlChainEditor steps={STEPS} onChange={onChange} />);
    fireEvent.click(screen.getByLabelText("Move step 1 down"));
    const next = onChange.mock.calls[0][0];
    expect(next[0].name).toBe("stage_agg");
    expect(next[1].name).toBe("stage_clean");
  });

  it("'[[ … ]]' insertion appends a date token to a step's SQL", () => {
    const onChange = vi.fn();
    render(
      <SqlChainEditor
        steps={[{ name: "s1", sql: "SELECT *", materialize: false }]}
        onChange={onChange}
      />
    );
    fireEvent.click(screen.getByLabelText("Insert PBD token into step 1"));
    expect(onChange.mock.calls[0][0][0].sql).toContain("[[ PBD ]]");
  });

  it("Preview step calls the S10 endpoint and renders rows + schema on success", async () => {
    (api.post as ReturnType<typeof vi.fn>).mockResolvedValue({
      columns: [
        { name: "k", type: "string" },
        { name: "v", type: "long" },
      ],
      rows: [{ k: "a", v: 10 }],
    });
    render(<SqlChainEditor steps={STEPS} onChange={vi.fn()} versionId="v1" instanceId="i1" />);

    fireEvent.click(screen.getAllByText("Preview step")[0]);

    // The right endpoint was called with the step prefix.
    await waitFor(() => expect(api.post).toHaveBeenCalled());
    expect((api.post as ReturnType<typeof vi.fn>).mock.calls[0][0]).toBe(
      "/api/v1/sql/sql-model/preview"
    );

    // The returned data renders.
    expect(await screen.findByText("a")).toBeInTheDocument();
    expect(screen.getByText("10")).toBeInTheDocument();
  });

  it("a not-implemented endpoint degrades to a non-fatal 'unavailable' message", async () => {
    (api.post as ReturnType<typeof vi.fn>).mockRejectedValue(new Error("404 Not Found"));
    render(<SqlChainEditor steps={STEPS} onChange={vi.fn()} />);

    fireEvent.click(screen.getAllByText("Preview step")[0]);

    expect(await screen.findByText(/preview unavailable/i)).toBeInTheDocument();
  });

  it("shows an as-of preview hint when asOf is supplied", () => {
    render(<SqlChainEditor steps={STEPS} onChange={vi.fn()} asOf="2026-06-16" />);
    expect(screen.getByText(/preview as-of 2026-06-16/)).toBeInTheDocument();
  });
});
