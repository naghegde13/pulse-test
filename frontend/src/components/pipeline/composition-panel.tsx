"use client";

import { useEffect, useState, useTransition } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  Card,
  CardContent,
  CardHeader,
} from "@/components/ui/card";
import { api } from "@/lib/api";
import { onCompositionEvent, emitCompositionEvent } from "@/lib/composition-events";
import { AddBlueprintDialog } from "./add-blueprint-dialog";
import { AddSinkDialog } from "./add-sink-dialog";
import { AddSourceDialog } from "./add-source-dialog";
import { ConfigureTransformDialog } from "./configure-transform-dialog";
import { ExpectationPicker } from "./expectation-picker";
import { DagView, type FocusSignal, type NewNodePlacement } from "./dag-view";
import { StagingCanvas } from "./staging-canvas";
import { useChat } from "@/contexts/chat-context";
import type {
  Blueprint,
  CompositionView,
  SubPipelineInstanceResponse,
  SubPipelineInstance,
  PortWiring,
  DqExpectation,
} from "@/types";

type ViewMode = "list" | "graph";

function isBlueprintConfigurable(
  blueprintKey: string | undefined,
  blueprint?: Blueprint | null
) {
  if (!blueprintKey) return false;
  return (blueprint?.paramsSchema?.length ?? 0) > 0;
}


interface CompositionPanelProps {
  pipelineId: string;
  versionId: string;
  editable: boolean;
  domainId?: string;
  surface?: "card" | "workspace";
  selectedInstanceId?: string | null;
  onSelectedInstanceChange?: (instanceId: string | null) => void;
  /** Explicit "open inspector" request (LCT-046): single-click selects,
   *  double-click (or an explicit affordance) inspects. */
  onInstanceInspect?: (instanceId: string) => void;
  /** External pan/center request forwarded to the DAG (LCT-013). */
  focusSignal?: FocusSignal | null;
  onCompositionLoaded?: (composition: CompositionView) => void;
}

export function CompositionPanel({
  pipelineId,
  versionId,
  editable,
  domainId,
  surface = "card",
  selectedInstanceId,
  onSelectedInstanceChange,
  onInstanceInspect,
  focusSignal,
  onCompositionLoaded,
}: CompositionPanelProps) {
  const [composition, setComposition] = useState<CompositionView | null>(null);
  const [blueprintLookup, setBlueprintLookup] = useState<Record<string, Blueprint>>({});
  const [viewMode, setViewMode] = useState<ViewMode>("graph");
  const [showAddDialog, setShowAddDialog] = useState(false);
  const [dagFullscreen, setDagFullscreen] = useState(false);
  const [showSourceDialog, setShowSourceDialog] = useState(false);
  const [showSinkDialog, setShowSinkDialog] = useState(false);
  const [configureInstance, setConfigureInstance] = useState<SubPipelineInstance | null>(null);
  const [dqInstance, setDqInstance] = useState<SubPipelineInstance | null>(null);
  // LCT-049: remember the just-added instance + its anchor so the DAG seeds
  // it next to the selected node instead of stacking at column 0.
  const [newNodePlacement, setNewNodePlacement] = useState<NewNodePlacement | null>(null);
  const [, startTransition] = useTransition();
  const { session } = useChat();

  const load = () => {
    startTransition(async () => {
      try {
        const data = await api.get<CompositionView>(
          `/api/v1/versions/${versionId}/composition`
        );
        setComposition(data);
        onCompositionLoaded?.(data);
      } catch {
        const empty = { instances: [], wirings: [] };
        setComposition(empty);
        onCompositionLoaded?.(empty);
      }
    });
  };

  useEffect(() => {
    load();
    startTransition(async () => {
      try {
        const bps = await api.get<Blueprint[]>("/api/v1/blueprints?includeDeferred=true");
        const lookup: Record<string, Blueprint> = {};
        bps.forEach((bp) => { lookup[bp.blueprintKey] = bp; });
        setBlueprintLookup(lookup);
      } catch { /* ignore */ }
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [versionId]);

  useEffect(() => {
    return onCompositionEvent((event) => {
      if (event.type === "composition_changed") {
        load();
      }
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [versionId]);

  const [error, setError] = useState<string | null>(null);

  const recordPlacement = (res: SubPipelineInstanceResponse) => {
    const newId = res?.instance?.id;
    if (newId) {
      setNewNodePlacement({ newInstanceId: newId, anchorInstanceId: selectedInstanceId ?? null });
    }
  };

  const handleAddBlueprint = async (blueprintKey: string, name: string) => {
    try {
      setError(null);
      const res = await api.post<SubPipelineInstanceResponse>(
        `/api/v1/versions/${versionId}/composition/instances`,
        { pipelineId, blueprintKey, name, params: {} }
      );
      recordPlacement(res);
      load();
      emitCompositionEvent({ type: "transform_added", detail: { instanceName: name, blueprintKey } });
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Failed to add step");
    }
  };

  const handleAddSource = async (blueprintKey: string, name: string, params: Record<string, unknown>) => {
    try {
      setError(null);
      const res = await api.post<SubPipelineInstanceResponse>(
        `/api/v1/versions/${versionId}/composition/instances`,
        { pipelineId, blueprintKey, name, params }
      );
      recordPlacement(res);
      load();
      const dsNames = params.dataset_names as string[] | undefined;
      emitCompositionEvent({
        type: "source_added",
        detail: { instanceName: name, blueprintKey, datasetName: dsNames?.[0] },
      });
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Failed to add source");
    }
  };

  const handleAddSink = async (blueprintKey: string, name: string, params: Record<string, unknown>) => {
    try {
      setError(null);
      const res = await api.post<SubPipelineInstanceResponse>(
        `/api/v1/versions/${versionId}/composition/instances`,
        { pipelineId, blueprintKey, name, params }
      );
      recordPlacement(res);
      load();
      emitCompositionEvent({ type: "sink_added", detail: { instanceName: name, blueprintKey } });
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Failed to add sink");
    }
  };

  const handleRemoveInstance = async (instanceId: string) => {
    try {
      setError(null);
      await api.delete(
        `/api/v1/versions/${versionId}/composition/instances/${instanceId}`
      );
      if (selectedInstanceId === instanceId) {
        onSelectedInstanceChange?.(null);
      }
      load();
      emitCompositionEvent({ type: "step_removed", detail: { instanceId } });
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Failed to remove step");
    }
  };

  const handleWire = async (
    sourceInstanceId: string,
    sourcePortName: string,
    targetInstanceId: string,
    targetPortName: string
  ) => {
    try {
      await api.post(`/api/v1/versions/${versionId}/composition/wirings`, {
        sourceInstanceId,
        sourcePortName,
        targetInstanceId,
        targetPortName,
      });
      load();
      emitCompositionEvent({ type: "port_wired" });
    } catch {
      load();
    }
  };

  const handleUnwire = async (wiringId: string) => {
    try {
      await api.delete(`/api/v1/versions/${versionId}/composition/wirings/${wiringId}`);
      load();
      emitCompositionEvent({ type: "composition_changed" });
    } catch {
      load();
    }
  };

  // LCT-046: single-click only SELECTS (highlights). It must NOT open any
  // panel/dialog — that's what made the inspector reopen right after a user
  // closed it. Opening is an explicit gesture (double-click / Edit button).
  const handleInstanceClick = (instanceId: string) => {
    if (!editable) return;
    onSelectedInstanceChange?.(instanceId);
  };

  // LCT-046: double-click is the explicit open gesture. On the workspace
  // surface it opens the right-region inspector; on the card surface it opens
  // the configure dialog (the prior single-click behavior).
  const handleInstanceDoubleClick = (instanceId: string) => {
    if (!editable) return;
    onSelectedInstanceChange?.(instanceId);
    if (surface === "workspace") {
      onInstanceInspect?.(instanceId);
      return;
    }
    const inst = composition?.instances.find((i) => i.id === instanceId);
    const blueprint = inst?.blueprintKey ? blueprintLookup[inst.blueprintKey] : null;
    if (inst && isBlueprintConfigurable(inst.blueprintKey, blueprint)) {
      setConfigureInstance(inst);
    }
  };

  const handleConfigureSelected = () => {
    if (!selectedInstance) return;
    const blueprint = selectedInstance.blueprintKey ? blueprintLookup[selectedInstance.blueprintKey] : null;
    if (isBlueprintConfigurable(selectedInstance.blueprintKey, blueprint)) {
      setConfigureInstance(selectedInstance);
    }
  };

  const handleDqClick = (instanceId: string) => {
    const inst = composition?.instances.find((i) => i.id === instanceId);
    if (inst) setDqInstance(inst);
  };

  const handleAddColumnClick = (instanceId: string) => {
    const inst = composition?.instances.find((i) => i.id === instanceId);
    if (inst) setConfigureInstance(inst);
  };

  const handleRemoveColumnClick = (instanceId: string) => {
    const inst = composition?.instances.find((i) => i.id === instanceId);
    if (inst) setConfigureInstance(inst);
  };

  if (!composition) {
    return <p className="text-sm text-muted-foreground">Loading composition...</p>;
  }

  const { instances, wirings } = composition;
  const wiringMap = buildWiringMap(wirings, instances);
  const selectedInstance = selectedInstanceId
    ? instances.find((inst) => inst.id === selectedInstanceId)
    : null;

  const header = (
    <div className="flex items-center justify-between gap-3">
      <div>
        <h3 className="text-sm font-medium">Pipeline Composition</h3>
        <p className="text-xs text-muted-foreground">
          {instances.length === 0
            ? "No blueprint instances yet"
            : `${instances.length} step${instances.length !== 1 ? "s" : ""}, ${wirings.length} connection${wirings.length !== 1 ? "s" : ""}`}
        </p>
      </div>
      <div className="flex flex-wrap items-center justify-end gap-2">
        {surface === "card" && instances.length > 0 && (
          <>
            <div className="flex rounded-md border text-xs">
              <button
                className={`px-2 py-1 rounded-l-md transition-colors ${viewMode === "list" ? "bg-primary text-primary-foreground" : "hover:bg-muted"}`}
                onClick={() => setViewMode("list")}
              >
                List
              </button>
              <button
                className={`px-2 py-1 rounded-r-md transition-colors ${viewMode === "graph" ? "bg-primary text-primary-foreground" : "hover:bg-muted"}`}
                onClick={() => setViewMode("graph")}
              >
                Graph
              </button>
            </div>
            {viewMode === "graph" && (
              <Button
                size="sm"
                variant="outline"
                onClick={() => setDagFullscreen(true)}
                title="Open the DAG in a full-screen modal"
              >
                Full screen
              </Button>
            )}
          </>
        )}
        {editable && (
          <>
            {surface === "workspace" && selectedInstance && (
              <Button size="sm" variant="outline" onClick={handleConfigureSelected}>
                Edit Step
              </Button>
            )}
            <Button size="sm" variant="outline" onClick={() => setShowSourceDialog(true)}>
              Add Source
            </Button>
            <Button size="sm" onClick={() => setShowAddDialog(true)}>
              Add Step
            </Button>
            <Button size="sm" variant="outline" onClick={() => setShowSinkDialog(true)}>
              Add Sink
            </Button>
          </>
        )}
      </div>
    </div>
  );

  const body = (
    <>
      {error && (
        <div className="mb-3 rounded-md bg-destructive/10 text-destructive text-xs px-3 py-2 flex items-center justify-between">
          <span>{error}</span>
          <button className="text-xs underline ml-2" onClick={() => setError(null)}>dismiss</button>
        </div>
      )}
      <StagingCanvas sessionId={session?.id} />
      {instances.length === 0 ? (
        <div className="text-center py-6 space-y-3">
          <p className="text-sm text-muted-foreground">
            Start with File Ingestion, then add Bronze-to-Silver Cleaning, Schema Normalization, DQ validation, and either quarantine or serving steps.
          </p>
          {editable && (
            <div className="flex justify-center gap-2">
              <Button size="sm" variant="outline" onClick={() => setShowSourceDialog(true)}>
                Add Source
              </Button>
              <Button size="sm" variant="outline" onClick={() => setShowAddDialog(true)}>
                Add Step
              </Button>
              <Button size="sm" variant="outline" onClick={() => setShowSinkDialog(true)}>
                Add Sink
              </Button>
            </div>
          )}
        </div>
      ) : surface === "workspace" || viewMode === "graph" ? (
        <DagView
          composition={composition}
          blueprintLookup={blueprintLookup}
          editable={editable}
          selectedInstanceId={selectedInstance?.id}
          focusSignal={focusSignal}
          newNodePlacement={newNodePlacement}
          onInstanceClick={handleInstanceClick}
          onInstanceDoubleClick={handleInstanceDoubleClick}
          onDqClick={handleDqClick}
          onAddColumnClick={handleAddColumnClick}
          onRemoveColumnClick={handleRemoveColumnClick}
          onConnect={handleWire}
          onDeleteInstance={dagFullscreen ? undefined : handleRemoveInstance}
          onDeleteWiring={handleUnwire}
          fillParent={surface === "workspace"}
        />
      ) : (
        <div className="space-y-1">
          {instances.map((inst, idx) => (
            <div key={inst.id}>
              <InstanceRow
                instance={inst}
                incomingWirings={wiringMap.incoming[inst.id] || []}
                outgoingWirings={wiringMap.outgoing[inst.id] || []}
                instanceLookup={wiringMap.instanceById}
                editable={editable}
                configurable={isBlueprintConfigurable(inst.blueprintKey, inst.blueprintKey ? blueprintLookup[inst.blueprintKey] : null)}
                onRemove={() => handleRemoveInstance(inst.id)}
                onConfigure={() => setConfigureInstance(inst)}
                onDqClick={() => handleDqClick(inst.id)}
              />
              {idx < instances.length - 1 && (
                <WiringConnector
                  from={inst}
                  to={instances[idx + 1]}
                  wirings={wirings}
                />
              )}
            </div>
          ))}
        </div>
      )}
    </>
  );

  return (
    <div className={surface === "workspace" ? "flex h-full min-h-0 flex-col gap-3" : ""}>
      {surface === "workspace" ? (
        <>
          {header}
          <div className="min-h-0 flex-1">{body}</div>
        </>
      ) : (
        <Card className="md:col-span-2">
          <CardHeader className="pb-2">{header}</CardHeader>
          <CardContent>{body}</CardContent>
        </Card>
      )}

      <AddBlueprintDialog
        open={showAddDialog}
        onOpenChange={setShowAddDialog}
        onAdd={handleAddBlueprint}
      />

      <AddSourceDialog
        open={showSourceDialog}
        onOpenChange={setShowSourceDialog}
        onAdd={handleAddSource}
      />

      <AddSinkDialog
        open={showSinkDialog}
        onOpenChange={setShowSinkDialog}
        onAdd={handleAddSink}
      />

      {configureInstance && (
        <ConfigureTransformDialog
          open={!!configureInstance}
          onOpenChange={(open) => { if (!open) setConfigureInstance(null); }}
          instanceId={configureInstance.id}
          instanceName={configureInstance.name}
          blueprintKey={configureInstance.blueprintKey || ""}
          blueprint={configureInstance.blueprintKey ? blueprintLookup[configureInstance.blueprintKey] : undefined}
          versionId={versionId}
          domainId={domainId}
          currentParams={configureInstance.params}
          storageBackend={configureInstance.storageBackend}
          lakeLayer={configureInstance.lakeLayer}
          lakeFormat={configureInstance.lakeFormat}
          onSaved={load}
        />
      )}

      {dqInstance && (
        <ExpectationPicker
          open={!!dqInstance}
          onOpenChange={(open) => { if (!open) setDqInstance(null); }}
          versionId={versionId}
          instanceId={dqInstance.id}
          instanceName={dqInstance.name}
          /* LCT-036: read the CANONICAL dqExpectations column (write path), not
             the legacy params.dq_expectations (always empty), so saved rules
             reopen. LCT-035: pass the resolved output schema for column-aware
             selection + AI suggestions. */
          currentExpectations={(dqInstance.dqExpectations as unknown as DqExpectation[] | undefined) ?? []}
          schema={dqInstance.outputSchema?.columns ?? []}
          onSaved={load}
        />
      )}

      {/* Full-screen DAG modal — same DagView, same handlers, just a
       * lot more pixels. Useful on long pipelines (many steps + many
       * wirings) where the embedded card is too cramped to drag nodes
       * or trace connections. */}
      <Dialog open={dagFullscreen} onOpenChange={setDagFullscreen}>
        <DialogContent className="!max-w-[98vw] sm:!max-w-[98vw] w-[98vw] h-[95vh] flex flex-col p-4">
          <DialogHeader className="flex-shrink-0">
            <DialogTitle className="text-base">
              Pipeline DAG — {instances.length} step{instances.length !== 1 ? "s" : ""}, {wirings.length} connection{wirings.length !== 1 ? "s" : ""}
            </DialogTitle>
          </DialogHeader>
          <div className="flex-1 min-h-0 overflow-hidden rounded-md border">
            <DagView
              composition={composition}
              blueprintLookup={blueprintLookup}
              editable={editable}
              selectedInstanceId={selectedInstance?.id}
              newNodePlacement={newNodePlacement}
              onInstanceClick={handleInstanceClick}
              onInstanceDoubleClick={handleInstanceDoubleClick}
              onDqClick={handleDqClick}
              onAddColumnClick={handleAddColumnClick}
              onRemoveColumnClick={handleRemoveColumnClick}
              onConnect={handleWire}
              onDeleteInstance={handleRemoveInstance}
              onDeleteWiring={handleUnwire}
              fillParent
            />
          </div>
        </DialogContent>
      </Dialog>
    </div>
  );
}

function InstanceRow({
  instance,
  incomingWirings,
  outgoingWirings,
  instanceLookup,
  editable,
  configurable,
  onRemove,
  onConfigure,
  onDqClick,
}: {
  instance: SubPipelineInstance;
  incomingWirings: PortWiring[];
  outgoingWirings: PortWiring[];
  instanceLookup: Record<string, SubPipelineInstance>;
  editable: boolean;
  configurable?: boolean;
  onRemove: () => void;
  onConfigure?: () => void;
  onDqClick?: () => void;
}) {
  // instance.params can be null on a freshly-added instance that
  // hasn't been configured yet. Coalesce to {} so all downstream
  // accesses are null-safe (same pattern as the configure-transform
  // dialog null-guard).
  const params = instance.params ?? {};
  const sorName = params.sor_name as string | undefined;
  const connectorName = params.connector_name as string | undefined;
  const datasetNames = params.dataset_names as string[] | undefined;
  const nonSourceParams = Object.keys(params).filter(
    (k) => !["sor_id", "sor_name", "connector_instance_id", "connector_name", "dataset_ids", "dataset_names", "qualified_names"].includes(k)
  );

  return (
    <div className="flex items-center gap-3 rounded-md border px-3 py-2 bg-card">
      <span className="text-xs font-mono text-muted-foreground w-5 text-right">
        {instance.executionOrder}
      </span>
      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-2">
          <span className="text-sm font-medium truncate">
            {instance.name}
          </span>
          <Badge variant="outline" className="text-[10px] font-mono shrink-0">
            {instance.blueprintKey || instance.blueprintId}
          </Badge>
          {nonSourceParams.length > 0 && (
            <Badge variant="secondary" className="text-[10px] shrink-0">
              {nonSourceParams.length} params
            </Badge>
          )}
          <SchemaStatusBadge instance={instance} />
        </div>
        {sorName && (
          <div className="flex items-center gap-1 mt-0.5">
            <span className="text-[10px] text-muted-foreground">Source:</span>
            <span className="text-[10px] font-medium">{sorName}</span>
            {connectorName && (
              <span className="text-[10px] text-muted-foreground">via {connectorName}</span>
            )}
            {datasetNames && datasetNames.length > 0 && (
              <Badge variant="secondary" className="text-[10px] ml-1">
                {datasetNames.length} dataset{datasetNames.length > 1 ? "s" : ""}
              </Badge>
            )}
          </div>
        )}
        {(incomingWirings.length > 0 || outgoingWirings.length > 0) && (
          <div className="flex gap-3 mt-0.5">
            {incomingWirings.map((w) => (
              <span key={w.id} className="text-[10px] text-muted-foreground">
                {instanceLookup[w.sourceInstanceId]?.name || "?"}.{w.sourcePortName} &rarr; {w.targetPortName}
              </span>
            ))}
          </div>
        )}
      </div>
      <div className="flex items-center gap-1">
        {onDqClick && (
          <Button
            variant="ghost"
            size="sm"
            className="text-xs text-muted-foreground hover:text-amber-600 h-6 px-2"
            onClick={onDqClick}
            title="Configure DQ expectations"
          >
            DQ
          </Button>
        )}
        {editable && configurable && onConfigure && (
          <Button
            variant="ghost"
            size="sm"
            className="text-xs text-muted-foreground hover:text-primary h-6 px-2"
            onClick={onConfigure}
          >
            Configure
          </Button>
        )}
        {editable && (
          <Button
            variant="ghost"
            size="sm"
            className="text-xs text-muted-foreground hover:text-destructive h-6 px-2"
            onClick={onRemove}
          >
            Remove
          </Button>
        )}
      </div>
    </div>
  );
}

function WiringConnector({
  from,
  to,
  wirings,
}: {
  from: SubPipelineInstance;
  to: SubPipelineInstance;
  wirings: PortWiring[];
}) {
  const connection = wirings.find(
    (w) => w.sourceInstanceId === from.id && w.targetInstanceId === to.id
  );

  if (!connection) {
    return (
      <div className="flex justify-center py-0.5">
        <span className="text-muted-foreground/30 text-xs">|</span>
      </div>
    );
  }

  return (
    <div className="flex justify-center py-0.5">
      <span className="text-primary/50 text-xs font-mono">
        &#x2502; {connection.sourcePortName} &rarr; {connection.targetPortName}
      </span>
    </div>
  );
}

function buildWiringMap(wirings: PortWiring[], instances: SubPipelineInstance[]) {
  const incoming: Record<string, PortWiring[]> = {};
  const outgoing: Record<string, PortWiring[]> = {};
  const instanceById: Record<string, SubPipelineInstance> = {};

  for (const inst of instances) {
    instanceById[inst.id] = inst;
  }

  for (const w of wirings) {
    if (!incoming[w.targetInstanceId]) incoming[w.targetInstanceId] = [];
    incoming[w.targetInstanceId].push(w);
    if (!outgoing[w.sourceInstanceId]) outgoing[w.sourceInstanceId] = [];
    outgoing[w.sourceInstanceId].push(w);
  }

  return { incoming, outgoing, instanceById };
}

/**
 * Schema-status badge. Reads the field via a narrow local cast so the component
 * compiles standalone — the shared `SubPipelineInstance` TS type addition for
 * `schemaStatus` is owned by Agent D (parent §8 Contract 4).
 */
function SchemaStatusBadge({ instance }: { instance: SubPipelineInstance }) {
  const status =
    (instance as { schemaStatus?: string }).schemaStatus ?? "unknown";
  const styles = schemaStatusStyles(status);
  return (
    <Badge
      variant="outline"
      className={`text-[10px] shrink-0 flex items-center gap-1 ${styles.className}`}
      title={`Schema status: ${status}`}
    >
      <span
        aria-hidden
        className={`inline-block h-1.5 w-1.5 rounded-full ${styles.dot}`}
      />
      {styles.label}
    </Badge>
  );
}

function schemaStatusStyles(status: string): {
  className: string;
  dot: string;
  label: string;
} {
  switch (status) {
    case "clean":
      return {
        className: "border-emerald-400 text-emerald-700",
        dot: "bg-emerald-500",
        label: "Schema ✓",
      };
    case "dirty":
      return {
        className: "border-amber-400 text-amber-700",
        dot: "bg-amber-500",
        label: "Schema stale",
      };
    case "conflict":
      return {
        className: "border-red-400 text-red-700",
        dot: "bg-red-500",
        label: "Schema conflict",
      };
    case "pending":
      return {
        className: "border-sky-400 text-sky-700",
        dot: "bg-sky-500 animate-pulse",
        label: "Propagating…",
      };
    default:
      return {
        className: "border-muted-foreground/40 text-muted-foreground",
        dot: "bg-muted-foreground/40",
        label: "Schema ?",
      };
  }
}
