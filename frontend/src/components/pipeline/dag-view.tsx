"use client";

import { useMemo, useCallback, useEffect, useRef, useState } from "react";
import dagre from "@dagrejs/dagre";
import {
  ReactFlow,
  Controls,
  MiniMap,
  Background,
  BaseEdge,
  EdgeLabelRenderer,
  getSmoothStepPath,
  useNodesState,
  useEdgesState,
  type Node,
  type Edge,
  type NodeTypes,
  type EdgeTypes,
  type EdgeProps,
  type Connection,
  type IsValidConnection,
  type ReactFlowInstance,
  MarkerType,
  ConnectionMode,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import { Workflow } from "lucide-react";
import { DagNode, type DagNodeData } from "./dag-node";
import { Button } from "@/components/ui/button";
import { computeSchemaDiff } from "@/lib/schema-diff";
import type {
  CompositionView,
  Blueprint,
  SubPipelineInstance,
  PortWiring,
  BlueprintCategory,
} from "@/types";

/**
 * A focus request from an external surface (e.g. the Outline). The `nonce`
 * lets the same instance be re-centered on repeated clicks (LCT-013): the
 * effect keys on the whole object, so a new nonce re-fires even when the
 * target id is unchanged.
 */
export interface FocusSignal {
  instanceId: string;
  nonce: number;
}

/**
 * Seeds the position of a freshly-added node next to an anchor (the selected
 * node) so new steps don't pile up in a second row at column 0 (LCT-049).
 */
export interface NewNodePlacement {
  newInstanceId: string;
  anchorInstanceId: string | null;
}

interface DagViewProps {
  composition: CompositionView;
  blueprintLookup: Record<string, Blueprint>;
  onInstanceClick?: (instanceId: string) => void;
  /** Opens the inspector/editor for a node — split from single-click select
   *  (LCT-046). Wired to ReactFlow's onNodeDoubleClick. */
  onInstanceDoubleClick?: (instanceId: string) => void;
  onDqClick?: (instanceId: string) => void;
  /** Opens the expression builder for adding a derived column (LCT-055). */
  onAddColumnClick?: (instanceId: string) => void;
  /** Opens the column selector for removing columns (LCT-055). */
  onRemoveColumnClick?: (instanceId: string) => void;
  onConnect?: (
    sourceInstanceId: string,
    sourcePortName: string,
    targetInstanceId: string,
    targetPortName: string
  ) => void;
  onDeleteInstance?: (instanceId: string) => void;
  onDeleteWiring?: (wiringId: string) => void;
  editable?: boolean;
  selectedInstanceId?: string | null;
  /** External request to pan/center on a node (LCT-013). */
  focusSignal?: FocusSignal | null;
  /** Context-aware placement seed for a newly-added node (LCT-049). */
  newNodePlacement?: NewNodePlacement | null;
  /** When true, DagView fills its parent (h-full). Default = embedded
   * card mode at h-[500px]. The full-screen modal uses fillParent
   * to make ReactFlow expand to whatever room the modal gives it. */
  fillParent?: boolean;
}

const nodeTypes: NodeTypes = { dagNode: DagNode };

/**
 * Custom edge with a visible inline delete (×) affordance (LCT-051). Wire
 * deletion previously only worked via ReactFlow's implicit edge-select +
 * keyboard path, which testers couldn't discover. The button is rendered
 * only when the canvas is editable and an onDelete handler is wired.
 */
interface DeletableEdgeData extends Record<string, unknown> {
  editable?: boolean;
  onDelete?: (edgeId: string) => void;
  portLabel?: string;
}

function DeletableEdge({
  id,
  sourceX,
  sourceY,
  targetX,
  targetY,
  sourcePosition,
  targetPosition,
  markerEnd,
  style,
  data,
}: EdgeProps) {
  const [edgePath, labelX, labelY] = getSmoothStepPath({
    sourceX,
    sourceY,
    targetX,
    targetY,
    sourcePosition,
    targetPosition,
  });
  const edgeData = data as DeletableEdgeData | undefined;

  return (
    <>
      <BaseEdge id={id} path={edgePath} markerEnd={markerEnd} style={style} />
      <EdgeLabelRenderer>
        <div
          className="absolute flex items-center gap-1 pointer-events-auto nodrag nopan"
          style={{
            transform: `translate(-50%, -50%) translate(${labelX}px, ${labelY}px)`,
          }}
        >
          {edgeData?.portLabel && (
            <span className="rounded bg-background/80 px-1 text-[9px] text-muted-foreground">
              {edgeData.portLabel}
            </span>
          )}
          {edgeData?.editable && edgeData?.onDelete && (
            <button
              type="button"
              onClick={(e) => {
                e.stopPropagation();
                edgeData.onDelete?.(id);
              }}
              title="Delete this wire"
              aria-label="Delete wire"
              className="flex h-4 w-4 items-center justify-center rounded-full border border-destructive bg-background text-[10px] leading-none text-destructive shadow-sm hover:bg-destructive hover:text-destructive-foreground transition-colors"
            >
              ×
            </button>
          )}
        </div>
      </EdgeLabelRenderer>
    </>
  );
}

const edgeTypes: EdgeTypes = { deletable: DeletableEdge };

const DQ_ALLOWED_CATEGORIES = new Set(["INGESTION", "TRANSFORM", "MODELING"]);

/**
 * Capability gate for the in-node "+ Add DQ" affordance (LCT-034).
 *
 * Only table-producing categories (INGESTION / TRANSFORM / MODELING) can carry
 * user DQ expectations, AND the node must not be a SOR-original source root.
 * A SOR-original source (added via "Add Source": carries an `sor_id`/`sor_name`
 * binding plus a `dataset_ids`/`qualified_names` selection) reads a read-only
 * registered dataset directly — its validation surface is the bronze table it
 * writes downstream, not the raw SOR read, so it must not offer +Add DQ.
 */
function supportsUserDqExpectations(
  instance: SubPipelineInstance,
  blueprint: Blueprint | null
): boolean {
  if (!blueprint?.category || !DQ_ALLOWED_CATEGORIES.has(blueprint.category)) {
    return false;
  }
  const params =
    instance.params && typeof instance.params === "object"
      ? (instance.params as Record<string, unknown>)
      : {};
  const hasSorBinding = Boolean(params.sor_id || params.sor_name);
  const hasDatasetBinding =
    Array.isArray(params.dataset_ids) || Array.isArray(params.qualified_names);
  if (hasSorBinding && hasDatasetBinding) return false;
  return true;
}

/** LCT-055: a source-root node is a read-only SOR-original instance that
 *  should not expose user column-shaping controls. Mirrors the gate in
 *  supportsUserDqExpectations: has both sor and dataset bindings. */
function isSourceRoot(instance: SubPipelineInstance): boolean {
  const params =
    instance.params && typeof instance.params === "object"
      ? (instance.params as Record<string, unknown>)
      : {};
  const hasSorBinding = Boolean(params.sor_id || params.sor_name);
  const hasDatasetBinding =
    Array.isArray(params.dataset_ids) || Array.isArray(params.qualified_names);
  return hasSorBinding && hasDatasetBinding;
}

const NODE_WIDTH = 240;
const NODE_HEIGHT = 120;
const H_GAP = 100;
const V_GAP = 50;
const DAGRE_NODE_WIDTH = NODE_WIDTH;
const DAGRE_NODE_HEIGHT = 138;

export function DagView({
  composition,
  blueprintLookup,
  onInstanceClick,
  onInstanceDoubleClick,
  onDqClick,
  onAddColumnClick,
  onRemoveColumnClick,
  onConnect,
  onDeleteInstance,
  onDeleteWiring,
  editable,
  selectedInstanceId,
  focusSignal,
  newNodePlacement,
  fillParent = false,
}: DagViewProps) {
  const { instances, wirings } = composition;
  const [impactInstanceIds, setImpactInstanceIds] = useState<Set<string>>(new Set());
  const [compact, setCompact] = useState(false);
  const reactFlowRef = useRef<ReactFlowInstance | null>(null);

  useEffect(() => {
    const handler = (event: Event) => {
      const detail = (event as CustomEvent<{ instanceIds?: string[] }>).detail;
      setImpactInstanceIds(new Set(detail?.instanceIds ?? []));
    };
    window.addEventListener("pulse:schema-impact", handler);
    return () => window.removeEventListener("pulse:schema-impact", handler);
  }, []);

  const { initialNodes, initialEdges } = useMemo(() => {
    const connectedInputs = new Map<string, Set<string>>();
    const connectedOutputs = new Map<string, Set<string>>();

    for (const w of wirings) {
      if (!connectedOutputs.has(w.sourceInstanceId))
        connectedOutputs.set(w.sourceInstanceId, new Set());
      connectedOutputs.get(w.sourceInstanceId)!.add(w.sourcePortName);

      if (!connectedInputs.has(w.targetInstanceId))
        connectedInputs.set(w.targetInstanceId, new Set());
      connectedInputs.get(w.targetInstanceId)!.add(w.targetPortName);
    }

    // Build adjacency for topological layout
    const inDegree = new Map<string, number>();
    const children = new Map<string, string[]>();
    instances.forEach((inst) => {
      inDegree.set(inst.id, 0);
      children.set(inst.id, []);
    });
    wirings.forEach((w) => {
      inDegree.set(
        w.targetInstanceId,
        (inDegree.get(w.targetInstanceId) || 0) + 1
      );
      children.get(w.sourceInstanceId)?.push(w.targetInstanceId);
    });

    // Assign columns via BFS (topological layers)
    const col = new Map<string, number>();
    const queue: string[] = [];
    instances.forEach((inst) => {
      if ((inDegree.get(inst.id) || 0) === 0) {
        queue.push(inst.id);
        col.set(inst.id, 0);
      }
    });
    const visited = new Set<string>();
    while (queue.length > 0) {
      const id = queue.shift()!;
      if (visited.has(id)) continue;
      visited.add(id);
      const c = col.get(id) || 0;
      for (const child of children.get(id) || []) {
        col.set(child, Math.max(col.get(child) || 0, c + 1));
        inDegree.set(child, (inDegree.get(child) || 0) - 1);
        if (inDegree.get(child) === 0) queue.push(child);
      }
    }
    // Fallback: any unvisited nodes go by execution order
    instances.forEach((inst) => {
      if (!col.has(inst.id))
        col.set(inst.id, inst.executionOrder - 1);
    });

    // Group by column for vertical stacking
    const columns = new Map<number, SubPipelineInstance[]>();
    instances.forEach((inst) => {
      const c = col.get(inst.id) || 0;
      if (!columns.has(c)) columns.set(c, []);
      columns.get(c)!.push(inst);
    });

    const builtNodes: Node[] = [];
    columns.forEach((colInstances, colIdx) => {
      colInstances.forEach((inst, rowIdx) => {
        const bp = blueprintLookup[inst.blueprintKey || ""] || null;
        const inputPorts =
          bp?.inputPorts?.map(
            (p) => p.name
          ) || [];
        let outputPorts =
          bp?.outputPorts?.map(
            (p) => p.name
          ) || [];

        const safeParams =
          inst.params && typeof inst.params === "object" ? inst.params : {};

        // Router: derive output ports dynamically from routes params
        if (
          inst.blueprintKey === "GenericRouter" &&
          Array.isArray((safeParams as Record<string, unknown>).routes)
        ) {
          const routes = (safeParams as Record<string, unknown>).routes as {
            name: string;
          }[];
          const dynamicPorts = routes
            .map((r) => r?.name)
            .filter((name): name is string => !!name);
          if ((safeParams as Record<string, unknown>).include_default !== false) {
            dynamicPorts.push("default_output");
          }
          outputPorts = dynamicPorts;
        }

        const instanceId = inst.id;
        const upstreamColumns = wirings
          .filter((w) => w.targetInstanceId === inst.id)
          .flatMap((w) => {
            const upstream = instances.find((candidate) => candidate.id === w.sourceInstanceId);
            return upstream?.outputSchema?.columns ?? [];
          });
        const outputColumns = inst.outputSchema?.columns ?? [];
        const schemaDiff = computeSchemaDiff(upstreamColumns, outputColumns);
        // Only table-producing blueprints can carry DQ expectations. Hide
        // the "+ Add DQ" affordance on:
        //   - ORCHESTRATION steps (sensors, schedule, advance-as-of, etc.)
        //     — no table to validate
        //   - DESTINATION (sinks read from gold + write external; their
        //     validation surface is the gold table they read FROM, which
        //     is upstream and already gets DQ from its own DQValidator)
        //   - DATA_QUALITY (DQValidator is itself the validator; you
        //     don't add DQ on top of DQ)
        // Codegen attaches the dq_expectations as a paired GX checkpoint
        // task that runs after the step's output table is materialized
        // — works equally for dbt blueprints (paired Airflow task after
        // dbt-run) and PySpark blueprints (paired GX task after the
        // Spark job). The capability gate (LCT-034) additionally excludes
        // SOR-original read-only source roots.
        const dqAllowed = supportsUserDqExpectations(inst, bp);
        // LCT-055: detect column-shaping capability from blueprint params_schema.
        const bpParams = Array.isArray(bp?.paramsSchema) ? bp.paramsSchema : [];
        const paramNames = new Set(bpParams.map((p: {name?: string}) => p?.name));
        const canAddColumn = paramNames.has("derived_columns") && !isSourceRoot(inst);
        // Exclude nodes that already have a native first-class drop-column param
        // (e.g. BronzeToSilverCleaning has drop_columns — no duplicate Remove).
        const hasNativeDrop = paramNames.has("drop_columns");
        const canRemoveColumn = paramNames.has("dropped_columns") && !hasNativeDrop && !isSourceRoot(inst);
        // LCT-036: read the CANONICAL SubPipelineInstance.dqExpectations
        // (NOT legacy params.dq_expectations) so the node label reflects
        // saved rules and reopening the editor shows them.
        const dqExpectationCount = inst.dqExpectations?.length ?? 0;
        const nodeData: DagNodeData = {
          label: inst.name,
          blueprintKey: inst.blueprintKey || inst.blueprintId,
          category: bp?.category as BlueprintCategory | undefined,
          paramCount: Object.keys(safeParams).length,
          inputPorts,
          outputPorts,
          connectedInputPorts:
            connectedInputs.get(inst.id) || new Set(),
          connectedOutputPorts:
            connectedOutputs.get(inst.id) || new Set(),
          hasMissingParams: false,
          columnCount: inst.outputSchema?.columns?.length,
          inputColumnCount: upstreamColumns.length,
          outputColumnCount: outputColumns.length,
          addedColumnCount: schemaDiff.added.length,
          renamedColumnCount: schemaDiff.renamed.length,
          retypedColumnCount: schemaDiff.retyped.length,
          droppedColumnCount: schemaDiff.dropped.length,
          // Pass through resolved columns so the node can render tag summary
          // (audit/masked/derived counts) + a hover tooltip with the full list.
          columns: inst.outputSchema?.columns,
          schemaStatus: inst.schemaStatus,
          schemaImpactActive: impactInstanceIds.has(inst.id),
          dqExpectationCount,
          canAddColumn,
          canRemoveColumn,
          compact,
          onDqClick: dqAllowed && onDqClick ? () => onDqClick(instanceId) : undefined,
          onAddColumnClick: canAddColumn && onAddColumnClick ? () => onAddColumnClick(instanceId) : undefined,
          onRemoveColumnClick: canRemoveColumn && onRemoveColumnClick ? () => onRemoveColumnClick(instanceId) : undefined,
        };

        builtNodes.push({
          id: inst.id,
          type: "dagNode",
          position: {
            x: colIdx * (NODE_WIDTH + H_GAP),
            y: rowIdx * (NODE_HEIGHT + V_GAP),
          },
          data: nodeData,
          selected: selectedInstanceId === inst.id,
          draggable: true,
        });
      });
    });

    // LCT-049: seed a freshly-added node next to the anchor (the node that
    // was selected when "Add Step/Source/Sink" was clicked) instead of
    // letting it pile up at column 0. Only applies while the new node is
    // still UNWIRED — once it has a wire, the BFS layout owns its position.
    if (newNodePlacement) {
      const { newInstanceId, anchorInstanceId } = newNodePlacement;
      const isWired = wirings.some(
        (w) =>
          w.sourceInstanceId === newInstanceId ||
          w.targetInstanceId === newInstanceId
      );
      const newNode = builtNodes.find((n) => n.id === newInstanceId);
      const anchorNode = anchorInstanceId
        ? builtNodes.find((n) => n.id === anchorInstanceId)
        : null;
      if (newNode && !isWired) {
        const base = anchorNode
          ? {
              x: anchorNode.position.x + NODE_WIDTH + H_GAP,
              y: anchorNode.position.y,
            }
          : { x: newNode.position.x, y: newNode.position.y };
        // Nudge down if another node already occupies the target slot.
        let y = base.y;
        const collides = (cx: number, cy: number) =>
          builtNodes.some(
            (n) =>
              n.id !== newInstanceId &&
              Math.abs(n.position.x - cx) < NODE_WIDTH &&
              Math.abs(n.position.y - cy) < NODE_HEIGHT
          );
        let guard = 0;
        while (collides(base.x, y) && guard < 20) {
          y += NODE_HEIGHT + V_GAP;
          guard += 1;
        }
        newNode.position = { x: base.x, y };
      }
    }

    const builtEdges: Edge[] = wirings.map((w: PortWiring) => ({
      id: w.id,
      source: w.sourceInstanceId,
      sourceHandle: w.sourcePortName,
      target: w.targetInstanceId,
      targetHandle: w.targetPortName,
      // LCT-051: custom edge renders a visible inline delete affordance.
      type: "deletable",
      animated: true,
      data: {
        editable: Boolean(editable),
        onDelete: onDeleteWiring,
        portLabel: `${w.sourcePortName} → ${w.targetPortName}`,
      },
      // Per #84: CSS vars are oklch values, not hsl. `hsl(var(--*))`
      // produces invalid color and renders transparent — edge label
      // appeared but the line itself was invisible. Use the var
      // directly (oklch resolves correctly).
      style: { stroke: "var(--primary)", strokeWidth: 1.5 },
      markerEnd: {
        type: MarkerType.ArrowClosed,
        width: 14,
        height: 14,
      },
    }));

    return { initialNodes: builtNodes, initialEdges: builtEdges };
  }, [
    instances,
    wirings,
    blueprintLookup,
    onDqClick,
    onAddColumnClick,
    onRemoveColumnClick,
    selectedInstanceId,
    impactInstanceIds,
    compact,
    editable,
    onDeleteWiring,
    newNodePlacement,
  ]);

  const [nodes, setNodes, onNodesChange] = useNodesState(initialNodes);
  const [edges, setEdges, onEdgesChange] = useEdgesState(initialEdges);

  // Sync when composition changes from server.
  // Per #83: this MUST be useEffect, not useMemo. useMemo can be invoked
  // multiple times per render or optimized out under React 18 concurrent
  // rendering — calling state setters inside it is unreliable. Symptom
  // when this was useMemo: chat-driven composition mutations (5+ rapid
  // tool calls) wouldn't refresh the DAG canvas without a manual page
  // refresh, even though composition-panel.load() fetched fresh data
  // and DagView received new props. useEffect runs deterministically
  // after commit on dep change.
  useEffect(() => {
    setNodes(initialNodes);
    setEdges(initialEdges);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [initialNodes, initialEdges]);

  const handleNodeClick = useCallback(
    (_: React.MouseEvent, node: Node) => {
      onInstanceClick?.(node.id);
    },
    [onInstanceClick]
  );

  // LCT-046: double-click is the explicit "open inspector/editor" gesture,
  // split from single-click selection.
  const handleNodeDoubleClick = useCallback(
    (_: React.MouseEvent, node: Node) => {
      onInstanceDoubleClick?.(node.id);
    },
    [onInstanceDoubleClick]
  );

  const handleConnect = useCallback(
    (connection: Connection) => {
      if (!onConnect || !connection.source || !connection.target) return;
      onConnect(
        connection.source,
        connection.sourceHandle || "output",
        connection.target,
        connection.targetHandle || "input"
      );
    },
    [onConnect]
  );

  // Validate connections: only source handles -> target handles, no self-loops
  const isValidConnection: IsValidConnection = useCallback(
    (connection) => {
      if (connection.source === connection.target) return false;
      if (!connection.sourceHandle || !connection.targetHandle) return false;
      return true;
    },
    []
  );

  const handleEdgesDelete = useCallback(
    (deletedEdges: Edge[]) => {
      if (!onDeleteWiring) return;
      for (const edge of deletedEdges) {
        onDeleteWiring(edge.id);
      }
    },
    [onDeleteWiring]
  );

  useEffect(() => {
    if (!editable || !selectedInstanceId || !onDeleteInstance) return;

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key !== "Delete" && event.key !== "Backspace") return;

      const target = event.target;
      if (target instanceof HTMLElement) {
        const tagName = target.tagName.toLowerCase();
        if (
          target.isContentEditable ||
          tagName === "input" ||
          tagName === "textarea" ||
          tagName === "select"
        ) {
          return;
        }
      }

      event.preventDefault();
      onDeleteInstance(selectedInstanceId);
    };

    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [editable, onDeleteInstance, selectedInstanceId]);

  // LCT-012: responsive fit. The previous build forced zoom = 2.0 on
  // fillParent, which opened multi-node pipelines far too zoomed-in. Both
  // modes now use fitView with bounded zoom so the initial view adapts to
  // the node count / bounding box. We stash the instance for the focus API
  // (LCT-013) and re-fit once after mount.
  const fitViewOptions = useMemo(
    () => ({
      padding: 0.2,
      minZoom: 0.4,
      maxZoom: fillParent ? 1.75 : 1.25,
    }),
    [fillParent]
  );

  const handleAutoLayout = useCallback(() => {
    const graph = new dagre.graphlib.Graph();
    graph.setDefaultEdgeLabel(() => ({}));
    graph.setGraph({
      rankdir: "LR",
      nodesep: compact ? 36 : 64,
      ranksep: compact ? 110 : 150,
      marginx: 24,
      marginy: 24,
      acyclicer: "greedy",
      ranker: "network-simplex",
    });

    const currentNodes = reactFlowRef.current?.getNodes() ?? nodes;
    const currentEdges = reactFlowRef.current?.getEdges() ?? edges;

    currentNodes.forEach((node) => {
      const width = node.measured?.width ?? node.width ?? DAGRE_NODE_WIDTH;
      const height = node.measured?.height ?? node.height ?? DAGRE_NODE_HEIGHT;
      graph.setNode(node.id, { width, height });
    });

    currentEdges.forEach((edge) => {
      graph.setEdge(edge.source, edge.target);
    });

    dagre.layout(graph);

    setNodes((current) =>
      current.map((node) => {
        const layoutNode = graph.node(node.id);
        const width = node.measured?.width ?? node.width ?? DAGRE_NODE_WIDTH;
        const height = node.measured?.height ?? node.height ?? DAGRE_NODE_HEIGHT;
        if (!layoutNode) return node;
        return {
          ...node,
          position: {
            x: layoutNode.x - width / 2,
            y: layoutNode.y - height / 2,
          },
        };
      })
    );

    window.setTimeout(() => {
      reactFlowRef.current?.fitView({
        ...fitViewOptions,
        duration: 400,
      });
    }, 0);
  }, [compact, edges, fitViewOptions, nodes, setNodes]);

  const handleInit = useCallback(
    (instance: ReactFlowInstance) => {
      reactFlowRef.current = instance;
      instance.fitView(fitViewOptions);
    },
    [fitViewOptions]
  );

  // LCT-013: center/pan to a node when an external surface (the Outline)
  // requests focus. The nonce in focusSignal lets repeated clicks on the
  // same node re-center. Preserves the current zoom level.
  useEffect(() => {
    if (!focusSignal) return;
    const instance = reactFlowRef.current;
    if (!instance) return;
    const node = instance.getNode(focusSignal.instanceId);
    if (!node) return;
    const width = node.measured?.width ?? node.width ?? NODE_WIDTH;
    const height = node.measured?.height ?? node.height ?? NODE_HEIGHT;
    instance.setCenter(
      node.position.x + width / 2,
      node.position.y + height / 2,
      { zoom: instance.getZoom(), duration: 400 }
    );
  }, [focusSignal]);

  if (instances.length === 0) return null;

  return (
    <div className={`relative ${fillParent ? "h-full" : "h-[500px]"} w-full rounded-md border bg-background`}>
      {/* LCT-032: compact authoring toggle. Shrinks nodes + hides secondary
          chips so a congested canvas is easier to author in. */}
      <div className="absolute right-2 top-2 z-10 flex items-center gap-2">
        <Button
          type="button"
          size="sm"
          variant="outline"
          className="h-7 px-2 text-[11px] shadow-sm"
          onClick={handleAutoLayout}
          title="Auto layout the DAG"
        >
          <Workflow className="mr-1 h-3.5 w-3.5" aria-hidden="true" />
          Auto layout
        </Button>
        <Button
          type="button"
          size="sm"
          variant={compact ? "default" : "outline"}
          className="h-7 px-2 text-[11px] shadow-sm"
          onClick={() => setCompact((c) => !c)}
          title={compact ? "Switch to detailed nodes" : "Switch to compact nodes"}
          aria-pressed={compact}
        >
          {compact ? "Detailed" : "Compact"}
        </Button>
      </div>
      <ReactFlow
        nodes={nodes}
        edges={edges}
        nodeTypes={nodeTypes}
        edgeTypes={edgeTypes}
        onNodesChange={onNodesChange}
        onEdgesChange={onEdgesChange}
        onNodeClick={handleNodeClick}
        onNodeDoubleClick={handleNodeDoubleClick}
        onConnect={editable ? handleConnect : undefined}
        onEdgesDelete={editable ? handleEdgesDelete : undefined}
        isValidConnection={isValidConnection}
        connectionMode={ConnectionMode.Loose}
        nodesDraggable={true}
        nodesConnectable={editable}
        elementsSelectable={true}
        selectNodesOnDrag={false}
        // LCT-012: both modes fit-to-graph with bounded zoom (see
        // fitViewOptions); onInit re-fits after mount + stashes the
        // instance for the focus API.
        fitView
        fitViewOptions={fitViewOptions}
        onInit={handleInit}
        minZoom={0.3}
        maxZoom={fillParent ? 3 : 2}
        snapToGrid
        snapGrid={[16, 16]}
        proOptions={{ hideAttribution: true }}
      >
        <Controls className="!bg-card !border !shadow-sm" />
        <MiniMap
          className="!bg-card !border"
          nodeColor="var(--primary)"
          maskColor="color-mix(in oklch, var(--muted) 50%, transparent)"
        />
        <Background
          gap={16}
          size={1}
          color="color-mix(in oklch, var(--muted-foreground) 10%, transparent)"
        />
      </ReactFlow>
    </div>
  );
}
