/**
 * auto-layout (Phase 6) unit tests.
 *
 * Asserts the three spec-pinned behaviors of the Dagre layout
 * (docs/ui/chat-prompts/07-orchestration-revert-layout.md §3.2):
 *   1. the structural-change gate fires on add/remove of an instance OR a wire,
 *      and does NOT fire on a pure param edit (same nodes + same edges);
 *   2. autoLayout RESPECTS explicit/manual positions (pinned nodes keep their
 *      coords; only unpositioned ghosts get computed coords);
 *   3. positions SNAP to the 16px grid.
 */

import { describe, it, expect } from "vitest";
import {
  autoLayout,
  graphStructure,
  isStructuralChange,
  snapToGrid,
  GRID_SIZE,
  type LayoutEdge,
  type LayoutNode,
} from "./auto-layout";

describe("isStructuralChange", () => {
  const base = graphStructure(
    [{ id: "a" }, { id: "b" }],
    [{ source: "a", target: "b" }]
  );

  it("treats a null prior (first snapshot of a turn) as structural", () => {
    expect(isStructuralChange(null, base)).toBe(true);
  });

  it("does NOT fire on a pure param edit (identical node + edge sets)", () => {
    // Same refs, same wires — only params changed (params are not part of the
    // structural signature). The canvas must NOT re-layout / jump.
    const next = graphStructure(
      [{ id: "a" }, { id: "b" }],
      [{ source: "a", target: "b" }]
    );
    expect(isStructuralChange(base, next)).toBe(false);
  });

  it("fires when an INSTANCE is added", () => {
    const next = graphStructure(
      [{ id: "a" }, { id: "b" }, { id: "c" }],
      [{ source: "a", target: "b" }]
    );
    expect(isStructuralChange(base, next)).toBe(true);
  });

  it("fires when an INSTANCE is removed", () => {
    const next = graphStructure([{ id: "a" }], []);
    expect(isStructuralChange(base, next)).toBe(true);
  });

  it("fires when a WIRE is added", () => {
    const next = graphStructure(
      [{ id: "a" }, { id: "b" }],
      [
        { source: "a", target: "b" },
        { source: "b", target: "a" },
      ]
    );
    expect(isStructuralChange(base, next)).toBe(true);
  });

  it("fires when a WIRE is removed", () => {
    const next = graphStructure([{ id: "a" }, { id: "b" }], []);
    expect(isStructuralChange(base, next)).toBe(true);
  });

  it("is order-insensitive for node and edge sets", () => {
    const reordered = graphStructure(
      [{ id: "b" }, { id: "a" }],
      [{ source: "a", target: "b" }]
    );
    expect(isStructuralChange(base, reordered)).toBe(false);
  });
});

describe("snapToGrid", () => {
  it("snaps to the 16px grid", () => {
    expect(snapToGrid(0)).toBe(0);
    expect(snapToGrid(7)).toBe(0);
    expect(snapToGrid(8)).toBe(16);
    expect(snapToGrid(15)).toBe(16);
    expect(snapToGrid(100)).toBe(96); // 100/16=6.25 → round 6 → 96
  });
});

describe("autoLayout", () => {
  it("returns an empty map for an empty graph", () => {
    expect(autoLayout([], []).size).toBe(0);
  });

  it("RESPECTS a manual position (pinned node keeps its coords, snapped)", () => {
    const nodes: LayoutNode[] = [
      { id: "pinned", position: { x: 48, y: 32 } },
      { id: "ghost" }, // unpositioned
    ];
    const edges: LayoutEdge[] = [{ source: "pinned", target: "ghost" }];

    const out = autoLayout(nodes, edges);

    // The pinned node keeps EXACTLY its manual position (already grid-aligned).
    expect(out.get("pinned")).toEqual({ x: 48, y: 32 });
    // The ghost gets a computed position (defined, distinct from the pin).
    const ghost = out.get("ghost")!;
    expect(ghost).toBeDefined();
    expect(ghost).not.toEqual({ x: 48, y: 32 });
  });

  it("snaps every computed position to the grid", () => {
    const nodes: LayoutNode[] = [{ id: "a" }, { id: "b" }, { id: "c" }];
    const edges: LayoutEdge[] = [
      { source: "a", target: "b" },
      { source: "b", target: "c" },
    ];
    const out = autoLayout(nodes, edges);
    for (const pos of out.values()) {
      expect(pos.x % GRID_SIZE).toBe(0);
      expect(pos.y % GRID_SIZE).toBe(0);
    }
  });

  it("places every node, including disconnected ones", () => {
    const nodes: LayoutNode[] = [{ id: "a" }, { id: "b" }, { id: "lonely" }];
    const edges: LayoutEdge[] = [{ source: "a", target: "b" }];
    const out = autoLayout(nodes, edges);
    expect(out.has("a")).toBe(true);
    expect(out.has("b")).toBe(true);
    expect(out.has("lonely")).toBe(true);
  });
});
