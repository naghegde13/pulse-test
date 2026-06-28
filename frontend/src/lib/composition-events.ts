type CompositionEventType =
  | "source_added"
  | "transform_added"
  | "sink_added"
  | "port_wired"
  | "params_configured"
  | "step_removed"
  | "composition_changed";

interface CompositionEvent {
  type: CompositionEventType;
  detail?: {
    instanceId?: string;
    instanceName?: string;
    blueprintKey?: string;
    datasetName?: string;
  };
}

type CompositionEventHandler = (event: CompositionEvent) => void;

const listeners = new Set<CompositionEventHandler>();

export function onCompositionEvent(handler: CompositionEventHandler): () => void {
  listeners.add(handler);
  return () => listeners.delete(handler);
}

export function emitCompositionEvent(event: CompositionEvent): void {
  listeners.forEach((handler) => {
    try {
      handler(event);
    } catch {
      // swallow handler errors
    }
  });
}
