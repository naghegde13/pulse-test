type SorEventType = "connector_added" | "dataset_added" | "sor_changed";

interface SorEvent {
  type: SorEventType;
  detail?: {
    sorId?: string;
  };
}

type SorEventHandler = (event: SorEvent) => void;

const listeners = new Set<SorEventHandler>();

export function onSorEvent(handler: SorEventHandler): () => void {
  listeners.add(handler);
  return () => listeners.delete(handler);
}

export function emitSorEvent(event: SorEvent): void {
  listeners.forEach((handler) => {
    try {
      handler(event);
    } catch {
      // swallow handler errors
    }
  });
}
