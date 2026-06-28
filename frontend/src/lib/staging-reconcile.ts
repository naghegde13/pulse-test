/**
 * STAGING-graph reconcile for the streaming canvas (Phase 6).
 *
 * The PULSE realization of n8n's `useWorkflowUpdate.updateWorkflow` reconcile
 * (`categorizeNodes` → `updateExistingNodes` (preserve positions) → remove
 * stale → add new → reconcile connections), re-expressed for `@xyflow/react`
 * and keyed by `instanceRef` (the only stable pre-Apply handle, since staged
 * instances have no real id). See
 * `docs/ui/chat-prompts/05-streaming-canvas-protocol.md` §C.5/§C.6 and
 * `07-orchestration-revert-layout.md` §3.2.
 *
 * Pure module (no React, no network). Consumes the `candidate_graph` SSE
 * payload (the full STAGING composition snapshot) and produces:
 *   - a categorize result {toAdd, toUpdate, toRemove} keyed by instanceRef;
 *   - a reconciled position map that PRESERVES manual node positions;
 *   - React-Flow-ready node/edge descriptors with status-colored ghost borders;
 *   - the "Review N changes" banner count (= diff.changeCount).
 */

import {
  autoLayout,
  graphStructure,
  isStructuralChange,
  snapPositionToGrid,
  type GraphStructure,
  type LayoutEdge,
  type LayoutNode,
  type XYPosition,
} from "./auto-layout";

/* ------------------------------------------------------------------ *
 * The `candidate_graph` SSE payload (STAGING snapshot) — keyed by ref.
 * Shapes are taken verbatim from the build-handoff contract.
 * ------------------------------------------------------------------ */

export interface StagingInstance {
  /** The instance NAME — the stable pre-Apply handle. */
  ref: string;
  blueprintKey: string;
  blueprintVersion?: string;
  params?: Record<string, unknown>;
  storageBackend?: string | null;
  lakeLayer?: string | null;
  lakeFormat?: string | null;
}

export interface StagingWiring {
  sourceRef: string;
  sourcePort: string;
  targetRef: string;
  targetPort: string;
}

export type DiffStatus = "equal" | "modified" | "added" | "deleted";

export interface CandidateGraphDiff {
  changeCount: number;
  instances: Record<string, { status: DiffStatus }>;
  wirings: Array<{ status: DiffStatus; wire: StagingWiring }>;
}

export interface CandidateGraphPayload {
  name?: string;
  versionId?: string;
  turnId?: string;
  instances: StagingInstance[];
  wirings: StagingWiring[];
  diff?: CandidateGraphDiff;
}

/* ------------------------------------------------------------------ *
 * Categorize — reconcile incoming staging refs against rendered refs.
 * ------------------------------------------------------------------ */

export interface Categorized {
  toAdd: string[];
  toUpdate: string[];
  toRemove: string[];
}

/**
 * Split the incoming staging instances against the set of refs currently
 * rendered on the canvas, keyed by instanceRef. (n8n's `categorizeNodes`,
 * but keyed by ref rather than id→type::name.)
 */
export function categorize(
  incoming: StagingInstance[],
  renderedRefs: Iterable<string>
): Categorized {
  const incomingRefs = new Set(incoming.map((i) => i.ref));
  const rendered = new Set(renderedRefs);

  const toAdd: string[] = [];
  const toUpdate: string[] = [];
  for (const inst of incoming) {
    if (rendered.has(inst.ref)) toUpdate.push(inst.ref);
    else toAdd.push(inst.ref);
  }
  const toRemove: string[] = [];
  for (const ref of rendered) {
    if (!incomingRefs.has(ref)) toRemove.push(ref);
  }
  return { toAdd, toUpdate, toRemove };
}

/* ------------------------------------------------------------------ *
 * Position store — a per-turn map keyed by instanceRef.
 * ------------------------------------------------------------------ */

export type PositionMap = Map<string, XYPosition>;

/**
 * Reconcile positions: PRESERVE the manual position of any instance still
 * present; DROP positions of removed instances; LEAVE new (ghost) instances
 * unpositioned so the layout can place them.
 *
 * This is the position half of n8n's `updateExistingNodes` (preserve existing
 * positions) + `removeStaleNodes` — the manual-position-preservation invariant.
 */
export function reconcilePositions(
  prev: PositionMap,
  incoming: StagingInstance[]
): PositionMap {
  const next: PositionMap = new Map();
  const incomingRefs = new Set(incoming.map((i) => i.ref));
  for (const [ref, pos] of prev) {
    if (incomingRefs.has(ref)) next.set(ref, pos);
  }
  return next;
}

/* ------------------------------------------------------------------ *
 * Build the staging graph into React-Flow node/edge descriptors.
 * ------------------------------------------------------------------ */

export interface StagingNode {
  /** React Flow node id = instanceRef. */
  id: string;
  position: XYPosition;
  instance: StagingInstance;
  /** Diff status drives the ghost border color. */
  status: DiffStatus;
}

export interface StagingEdge {
  id: string;
  source: string;
  target: string;
  sourceHandle: string;
  targetHandle: string;
  status: DiffStatus;
}

function wireId(w: StagingWiring): string {
  return `${w.sourceRef}:${w.sourcePort}->${w.targetRef}:${w.targetPort}`;
}

/** Diff-status → border color token (Tailwind/oklch CSS vars). */
export function statusBorderColor(status: DiffStatus): string {
  switch (status) {
    case "added":
      return "var(--color-emerald-500, #10b981)";
    case "modified":
      return "var(--color-amber-500, #f59e0b)";
    case "deleted":
      return "var(--color-red-500, #ef4444)";
    default:
      return "var(--border)";
  }
}

export interface BuildStagingGraphResult {
  nodes: StagingNode[];
  edges: StagingEdge[];
  /** The structural signature, for the structural-change gate next turn. */
  structure: GraphStructure;
  /** The reconciled position map (manual positions preserved). */
  positions: PositionMap;
  /** Whether this snapshot changed the graph STRUCTURE vs the prior one. */
  structural: boolean;
  /** The "Review N changes" banner count. */
  changeCount: number;
}

export interface BuildStagingGraphArgs {
  payload: CandidateGraphPayload;
  /** Manual/known positions carried from the prior snapshot in this turn. */
  prevPositions: PositionMap;
  /** The prior snapshot's structural signature (null on the first snapshot). */
  prevStructure: GraphStructure | null;
}

/**
 * The full reconcile pass for one `candidate_graph` snapshot:
 *
 *   1. categorize incoming refs → {toAdd, toUpdate, toRemove};
 *   2. reconcile the position map (preserve manual, drop removed);
 *   3. decide if this is a STRUCTURAL change vs the prior snapshot;
 *   4. run autoLayout() ONLY on a structural change (seeded with the preserved
 *      manual positions; computes positions only for unpositioned ghosts);
 *      on a pure param edit, keep the existing positions (no jump);
 *   5. build status-colored React-Flow node/edge descriptors;
 *   6. compute the banner change count (= diff.changeCount when present).
 */
export function buildStagingGraph(
  args: BuildStagingGraphArgs
): BuildStagingGraphResult {
  const { payload, prevPositions, prevStructure } = args;
  const { instances, wirings, diff } = payload;

  const layoutEdges: LayoutEdge[] = wirings.map((w) => ({
    source: w.sourceRef,
    target: w.targetRef,
  }));

  const structure = graphStructure(
    instances.map((i) => ({ id: i.ref })),
    layoutEdges
  );
  const structural = isStructuralChange(prevStructure, structure);

  // Step 2: reconcile positions (preserve manual, drop removed).
  const reconciled = reconcilePositions(prevPositions, instances);

  // Steps 3-4: layout ONLY on structural change.
  let positions: PositionMap;
  if (structural) {
    const layoutNodes: LayoutNode[] = instances.map((i) => ({
      id: i.ref,
      position: reconciled.get(i.ref) ?? null,
    }));
    positions = autoLayout(layoutNodes, layoutEdges);
  } else {
    // Pure param edit: keep existing positions verbatim. Any instance that
    // somehow lacks a position (shouldn't happen on a non-structural change)
    // falls back to origin so it still renders.
    positions = new Map();
    for (const inst of instances) {
      positions.set(
        inst.ref,
        reconciled.get(inst.ref) ?? snapPositionToGrid({ x: 0, y: 0 })
      );
    }
  }

  // Step 5: build node/edge descriptors with ghost-border status.
  const instStatus = (ref: string): DiffStatus =>
    diff?.instances?.[ref]?.status ?? "equal";

  const nodes: StagingNode[] = instances.map((inst) => ({
    id: inst.ref,
    position: positions.get(inst.ref) ?? snapPositionToGrid({ x: 0, y: 0 }),
    instance: inst,
    status: instStatus(inst.ref),
  }));

  const wireStatusByKey = new Map<string, DiffStatus>();
  for (const wd of diff?.wirings ?? []) {
    wireStatusByKey.set(wireId(wd.wire), wd.status);
  }
  const edges: StagingEdge[] = wirings.map((w) => ({
    id: wireId(w),
    source: w.sourceRef,
    target: w.targetRef,
    sourceHandle: w.sourcePort,
    targetHandle: w.targetPort,
    status: wireStatusByKey.get(wireId(w)) ?? "equal",
  }));

  return {
    nodes,
    edges,
    structure,
    positions,
    structural,
    changeCount: computeChangeCount(payload),
  };
}

/* ------------------------------------------------------------------ *
 * Change-count math for the "Review N changes" banner.
 * ------------------------------------------------------------------ */

/**
 * The banner count. When the backend supplies `diff.changeCount`, that is the
 * authority (the banner IS the Plan Preview, driven by `diff.changeCount`).
 * Otherwise it is derived client-side: added + modified + deleted instances,
 * plus added/deleted wires — exactly the SPEC §C.6 formula
 * `N = count(added)+count(modified)+count(deleted)+changed wires`.
 */
export function computeChangeCount(payload: CandidateGraphPayload): number {
  const diff = payload.diff;
  if (diff && typeof diff.changeCount === "number") {
    return diff.changeCount;
  }
  if (!diff) return 0;

  let n = 0;
  for (const ref of Object.keys(diff.instances ?? {})) {
    const s = diff.instances[ref]?.status;
    if (s === "added" || s === "modified" || s === "deleted") n += 1;
  }
  for (const w of diff.wirings ?? []) {
    if (w.status === "added" || w.status === "deleted" || w.status === "modified") {
      n += 1;
    }
  }
  return n;
}
