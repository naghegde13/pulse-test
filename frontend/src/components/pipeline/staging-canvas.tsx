"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import {
  ReactFlow,
  Background,
  Controls,
  MarkerType,
  useNodesState,
  useEdgesState,
  type Node,
  type Edge,
  type NodeProps,
  type NodeTypes,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { api } from "@/lib/api";
import {
  buildStagingGraph,
  statusBorderColor,
  type CandidateGraphPayload,
  type DiffStatus,
  type PositionMap,
  type StagingEdge,
  type StagingInstance,
  type StagingNode,
} from "@/lib/staging-reconcile";
import type { GraphStructure } from "@/lib/auto-layout";
import {
  onStagingEvent,
  type PlanPreview,
} from "@/lib/staging-events";
import { emitCompositionEvent } from "@/lib/composition-events";

/* ------------------------------------------------------------------ *
 * Ghost node — a lightweight candidate node with a status-colored
 * border. Distinct from the canonical DagNode (this is the staging layer).
 * ------------------------------------------------------------------ */

interface GhostNodeData extends Record<string, unknown> {
  instance: StagingInstance;
  status: DiffStatus;
}

const STATUS_LABEL: Record<DiffStatus, string> = {
  equal: "",
  added: "Added",
  modified: "Modified",
  deleted: "Removed",
};

function GhostNode({ data }: NodeProps) {
  const { instance, status } = data as GhostNodeData;
  const border = statusBorderColor(status);
  const isDeleted = status === "deleted";
  return (
    <div
      className="rounded-md bg-card/80 px-3 py-2 text-xs shadow-sm backdrop-blur"
      style={{
        width: 200,
        border: `2px dashed ${border}`,
        opacity: isDeleted ? 0.55 : 1,
        textDecoration: isDeleted ? "line-through" : undefined,
      }}
      data-staging-ref={instance.ref}
      data-staging-status={status}
    >
      <div className="flex items-center justify-between gap-2">
        <span className="font-medium truncate">{instance.ref}</span>
        {status !== "equal" && (
          <Badge
            variant="outline"
            className="text-[9px] shrink-0"
            style={{ borderColor: border, color: border }}
          >
            {STATUS_LABEL[status]}
          </Badge>
        )}
      </div>
      <div className="mt-0.5 font-mono text-[10px] text-muted-foreground truncate">
        {instance.blueprintKey}
      </div>
    </div>
  );
}

const ghostNodeTypes: NodeTypes = { ghostNode: GhostNode };

/* ------------------------------------------------------------------ */

interface StagingCanvasProps {
  sessionId?: string | null;
}

/**
 * The STAGING canvas + Plan-Preview banner (Phase 6).
 *
 * Subscribes to the staging-events bus (fed by the chat-panel SSE reader):
 *   - `candidate_graph` → reconcile into ghost nodes/edges (preserving manual
 *      positions; auto-layout only on a structural change);
 *   - `plan`            → arm the "Review N changes" banner = the Plan Preview;
 *   - `turn_started` / `staging_cleared` → drop the staging layer.
 *
 * The banner IS the Plan Preview: clicking it opens the before/after overlay
 * with per-op reasoning, and Apply / Reject post the plan decision.
 */
export function StagingCanvas({ sessionId }: StagingCanvasProps) {
  const [nodes, setNodes, onNodesChange] = useNodesState<Node>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState<Edge>([]);
  const [changeCount, setChangeCount] = useState(0);
  const [plan, setPlan] = useState<PlanPreview | null>(null);
  const [planOpen, setPlanOpen] = useState(false);
  const [hasStaging, setHasStaging] = useState(false);
  const [deciding, setDeciding] = useState(false);

  // Per-turn position map (keyed by instanceRef) + structural signature, kept
  // in refs so reconcile reads the latest without re-subscribing the bus.
  const positionsRef = useRef<PositionMap>(new Map());
  const structureRef = useRef<GraphStructure | null>(null);

  const resetStaging = useCallback(() => {
    positionsRef.current = new Map();
    structureRef.current = null;
    setNodes([]);
    setEdges([]);
    setChangeCount(0);
    setPlan(null);
    setPlanOpen(false);
    setHasStaging(false);
  }, [setNodes, setEdges]);

  const applyCandidateGraph = useCallback(
    (payload: CandidateGraphPayload) => {
      const result = buildStagingGraph({
        payload,
        prevPositions: positionsRef.current,
        prevStructure: structureRef.current,
      });
      positionsRef.current = result.positions;
      structureRef.current = result.structure;

      setNodes(toReactFlowNodes(result.nodes));
      setEdges(toReactFlowEdges(result.edges));
      setChangeCount(result.changeCount);
      setHasStaging(result.nodes.length > 0);
    },
    [setNodes, setEdges]
  );

  useEffect(() => {
    return onStagingEvent((event) => {
      switch (event.type) {
        case "turn_started":
        case "staging_cleared":
          resetStaging();
          break;
        case "candidate_graph":
          applyCandidateGraph(event.payload);
          break;
        case "plan":
          setPlan(event.payload);
          break;
        case "turn_ended":
          // keep the staging layer + banner up after the turn so the
          // Customer can still review/Apply.
          break;
      }
    });
  }, [applyCandidateGraph, resetStaging]);

  const decide = useCallback(
    async (decision: "approve" | "reject" | "modify") => {
      if (!plan?.planId || deciding) return;
      if (!sessionId) return;
      setDeciding(true);
      try {
        await api.post(
          `/api/v1/chat/sessions/${sessionId}/plans/${plan.planId}/decision`,
          { decision }
        );
      } finally {
        setDeciding(false);
        setPlanOpen(false);
        resetStaging();
        if (decision === "approve") {
          emitCompositionEvent({ type: "composition_changed" });
        }
      }
    },
    [plan, sessionId, deciding, resetStaging]
  );

  if (!hasStaging && changeCount === 0) return null;

  return (
    <div className="space-y-2">
      {/* The "Review N changes" banner = diff.changeCount; clicking it opens
       *  the Plan Preview (the banner IS the Plan Preview). */}
      {changeCount > 0 && (
        <button
          type="button"
          onClick={() => setPlanOpen(true)}
          className="flex w-full items-center justify-between rounded-md border border-amber-400 bg-amber-50 px-3 py-2 text-left text-xs text-amber-800 transition-colors hover:bg-amber-100 dark:bg-amber-950/30 dark:text-amber-200"
          data-testid="review-changes-banner"
        >
          <span className="font-medium">
            Review {changeCount} change{changeCount !== 1 ? "s" : ""}
          </span>
          <span className="text-amber-600 dark:text-amber-300">
            Open Plan Preview →
          </span>
        </button>
      )}

      {hasStaging && (
        <div className="h-[360px] w-full rounded-md border border-dashed bg-background/50">
          <ReactFlow
            nodes={nodes}
            edges={edges}
            nodeTypes={ghostNodeTypes}
            onNodesChange={onNodesChange}
            onEdgesChange={onEdgesChange}
            fitView
            fitViewOptions={{ padding: 0.3 }}
            nodesConnectable={false}
            elementsSelectable={false}
            proOptions={{ hideAttribution: true }}
            snapToGrid
            snapGrid={[16, 16]}
          >
            <Controls showInteractive={false} className="!bg-card !border !shadow-sm" />
            <Background gap={16} size={1} color="color-mix(in oklch, var(--muted-foreground) 10%, transparent)" />
          </ReactFlow>
        </div>
      )}

      <PlanPreviewDialog
        open={planOpen}
        onOpenChange={setPlanOpen}
        plan={plan}
        changeCount={changeCount}
        deciding={deciding}
        onApprove={() => decide("approve")}
        onReject={() => decide("reject")}
        onModify={() => decide("modify")}
      />
    </div>
  );
}

function PlanPreviewDialog({
  open,
  onOpenChange,
  plan,
  changeCount,
  deciding,
  onApprove,
  onReject,
  onModify,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  plan: PlanPreview | null;
  changeCount: number;
  deciding: boolean;
  onApprove: () => void;
  onReject: () => void;
  onModify: () => void;
}) {
  const steps = plan?.steps ?? [];
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle className="text-base">
            Plan Preview — {changeCount} change{changeCount !== 1 ? "s" : ""}
          </DialogTitle>
        </DialogHeader>
        {plan?.summary && (
          <p className="text-sm text-muted-foreground">{plan.summary}</p>
        )}
        <div className="max-h-[50vh] space-y-2 overflow-y-auto">
          {steps.length === 0 ? (
            <p className="text-xs text-muted-foreground">No staged ops in this plan.</p>
          ) : (
            steps.map((step, idx) => (
              <div
                key={`${step.op ?? "op"}-${step.instanceRef ?? idx}`}
                className="rounded-md border px-3 py-2"
              >
                <div className="flex items-center gap-2">
                  <Badge variant="outline" className="text-[10px] font-mono">
                    {step.op ?? "op"}
                  </Badge>
                  {step.instanceRef && (
                    <span className="text-xs font-medium">{step.instanceRef}</span>
                  )}
                  {step.blueprintKey && (
                    <span className="text-[10px] font-mono text-muted-foreground">
                      {step.blueprintKey}
                    </span>
                  )}
                </div>
                {step.reasoning && (
                  <p className="mt-1 text-[11px] text-muted-foreground">
                    {step.reasoning}
                  </p>
                )}
              </div>
            ))
          )}
        </div>
        <div className="flex justify-end gap-2 pt-2">
          <Button variant="outline" size="sm" disabled={deciding} onClick={onReject}>
            Reject
          </Button>
          <Button
            variant="outline"
            size="sm"
            disabled={deciding || !plan}
            onClick={() => onModify()}
          >
            Modify
          </Button>
          <Button size="sm" disabled={deciding || !plan} onClick={onApprove}>
            Apply Plan
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

/* ------------------------------------------------------------------ *
 * Map the pure staging descriptors → React Flow node/edge objects.
 * ------------------------------------------------------------------ */

function toReactFlowNodes(staging: StagingNode[]): Node[] {
  return staging.map((n) => ({
    id: n.id,
    type: "ghostNode",
    position: n.position,
    data: { instance: n.instance, status: n.status } satisfies GhostNodeData,
    draggable: true,
  }));
}

function toReactFlowEdges(staging: StagingEdge[]): Edge[] {
  return staging.map((e) => {
    const color = statusBorderColor(e.status);
    return {
      id: e.id,
      source: e.source,
      target: e.target,
      sourceHandle: e.sourceHandle,
      targetHandle: e.targetHandle,
      type: "smoothstep",
      animated: e.status !== "equal",
      style: {
        stroke: e.status === "equal" ? "var(--primary)" : color,
        strokeWidth: 1.5,
        strokeDasharray: e.status === "deleted" ? "4 4" : undefined,
      },
      markerEnd: { type: MarkerType.ArrowClosed, width: 14, height: 14 },
    };
  });
}
