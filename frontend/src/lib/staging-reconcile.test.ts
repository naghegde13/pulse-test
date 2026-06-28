/**
 * staging-reconcile (Phase 6) unit tests.
 *
 * Asserts:
 *   - categorize() splits incoming refs against rendered refs;
 *   - reconcilePositions() PRESERVES manual positions of surviving instances
 *     and DROPS positions of removed instances;
 *   - buildStagingGraph() runs auto-layout ONLY on a structural change and
 *     KEEPS existing positions on a pure param edit (no jump);
 *   - computeChangeCount() banner math (diff.changeCount authority + derived).
 */

import { describe, it, expect } from "vitest";
import {
  buildStagingGraph,
  categorize,
  computeChangeCount,
  reconcilePositions,
  type CandidateGraphPayload,
  type PositionMap,
  type StagingInstance,
} from "./staging-reconcile";

function inst(ref: string, blueprintKey = "FileIngestion"): StagingInstance {
  return { ref, blueprintKey };
}

describe("categorize", () => {
  it("splits incoming refs into add / update / remove vs rendered", () => {
    const incoming = [inst("a"), inst("b"), inst("c")];
    const rendered = ["a", "b", "x"]; // x is stale, c is new
    const { toAdd, toUpdate, toRemove } = categorize(incoming, rendered);
    expect(toAdd.sort()).toEqual(["c"]);
    expect(toUpdate.sort()).toEqual(["a", "b"]);
    expect(toRemove.sort()).toEqual(["x"]);
  });

  it("everything is an add when nothing is rendered", () => {
    const { toAdd, toUpdate, toRemove } = categorize([inst("a")], []);
    expect(toAdd).toEqual(["a"]);
    expect(toUpdate).toEqual([]);
    expect(toRemove).toEqual([]);
  });
});

describe("reconcilePositions", () => {
  it("PRESERVES manual positions of surviving instances, DROPS removed", () => {
    const prev: PositionMap = new Map([
      ["a", { x: 16, y: 32 }],
      ["gone", { x: 99, y: 99 }],
    ]);
    const incoming = [inst("a"), inst("new")];
    const next = reconcilePositions(prev, incoming);
    // surviving instance keeps its exact manual position
    expect(next.get("a")).toEqual({ x: 16, y: 32 });
    // removed instance is dropped
    expect(next.has("gone")).toBe(false);
    // new instance is left unpositioned (layout will place it)
    expect(next.has("new")).toBe(false);
  });
});

describe("buildStagingGraph", () => {
  const payload = (
    instances: StagingInstance[],
    wirings: CandidateGraphPayload["wirings"] = [],
    diff?: CandidateGraphPayload["diff"]
  ): CandidateGraphPayload => ({ instances, wirings, diff });

  it("marks the first snapshot of a turn as structural and lays it out", () => {
    const result = buildStagingGraph({
      payload: payload([inst("a"), inst("b")], [
        { sourceRef: "a", sourcePort: "out", targetRef: "b", targetPort: "in" },
      ]),
      prevPositions: new Map(),
      prevStructure: null,
    });
    expect(result.structural).toBe(true);
    expect(result.nodes).toHaveLength(2);
    expect(result.edges).toHaveLength(1);
  });

  it("PRESERVES a manual position across a pure PARAM edit (no re-layout / jump)", () => {
    const wirings = [
      { sourceRef: "a", sourcePort: "out", targetRef: "b", targetPort: "in" },
    ];
    // turn 1: lay out, then the Customer drags node "a" to a manual spot.
    const first = buildStagingGraph({
      payload: payload([inst("a"), inst("b")], wirings),
      prevPositions: new Map(),
      prevStructure: null,
    });
    const dragged: PositionMap = new Map(first.positions);
    dragged.set("a", { x: 320, y: 160 }); // manual drag (grid-aligned)

    // turn 2: SAME structure (same refs + wires), only a param changed.
    const second = buildStagingGraph({
      payload: payload(
        [{ ...inst("a"), params: { x: 1 } }, inst("b")],
        wirings
      ),
      prevPositions: dragged,
      prevStructure: first.structure,
    });

    expect(second.structural).toBe(false); // param edit ≠ structural change
    // The manual drag position is preserved verbatim — canvas did not jump.
    expect(second.positions.get("a")).toEqual({ x: 320, y: 160 });
    const nodeA = second.nodes.find((n) => n.id === "a")!;
    expect(nodeA.position).toEqual({ x: 320, y: 160 });
  });

  it("re-runs auto-layout when an instance is ADDED (structural)", () => {
    const first = buildStagingGraph({
      payload: payload([inst("a")]),
      prevPositions: new Map(),
      prevStructure: null,
    });
    const second = buildStagingGraph({
      payload: payload([inst("a"), inst("b")], [
        { sourceRef: "a", sourcePort: "out", targetRef: "b", targetPort: "in" },
      ]),
      prevPositions: first.positions,
      prevStructure: first.structure,
    });
    expect(second.structural).toBe(true);
    expect(second.nodes).toHaveLength(2);
  });

  it("applies diff status to ghost nodes and edges", () => {
    const result = buildStagingGraph({
      payload: payload(
        [inst("a"), inst("b")],
        [{ sourceRef: "a", sourcePort: "out", targetRef: "b", targetPort: "in" }],
        {
          changeCount: 2,
          instances: { a: { status: "added" }, b: { status: "equal" } },
          wirings: [
            {
              status: "added",
              wire: { sourceRef: "a", sourcePort: "out", targetRef: "b", targetPort: "in" },
            },
          ],
        }
      ),
      prevPositions: new Map(),
      prevStructure: null,
    });
    expect(result.nodes.find((n) => n.id === "a")!.status).toBe("added");
    expect(result.nodes.find((n) => n.id === "b")!.status).toBe("equal");
    expect(result.edges[0].status).toBe("added");
    expect(result.changeCount).toBe(2);
  });
});

describe("computeChangeCount (banner math)", () => {
  it("uses diff.changeCount as the authority when present", () => {
    const n = computeChangeCount({
      instances: [],
      wirings: [],
      diff: { changeCount: 7, instances: {}, wirings: [] },
    });
    expect(n).toBe(7);
  });

  it("derives count = added + modified + deleted instances + changed wires", () => {
    const n = computeChangeCount({
      instances: [],
      wirings: [],
      // omit changeCount so the derive path runs
      diff: {
        changeCount: undefined as unknown as number,
        instances: {
          a: { status: "added" },
          b: { status: "modified" },
          c: { status: "deleted" },
          d: { status: "equal" }, // not counted
        },
        wirings: [
          { status: "added", wire: { sourceRef: "a", sourcePort: "o", targetRef: "b", targetPort: "i" } },
          { status: "equal", wire: { sourceRef: "b", sourcePort: "o", targetRef: "c", targetPort: "i" } }, // not counted
        ],
      },
    });
    // 3 changed instances + 1 changed wire
    expect(n).toBe(4);
  });

  it("returns 0 when there is no diff", () => {
    expect(computeChangeCount({ instances: [], wirings: [] })).toBe(0);
  });
});
