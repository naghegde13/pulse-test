/**
 * STAGING / Plan-Preview event bus (Phase 6).
 *
 * The chat-panel SSE reader is the producer (it parses the `candidate_graph`
 * and `plan` SSE frames); the composition panel is the consumer (it renders
 * the ghost staging layer + the "Review N changes" banner). They are decoupled
 * via this tiny in-process bus, the same pattern as `composition-events.ts`.
 *
 * No network here — this is a pure client-side pub/sub for streamed payloads.
 */

import type { CandidateGraphPayload } from "./staging-reconcile";

/** The Plan Preview payload carried on the `plan` SSE event. */
export interface PlanPreviewStep {
  op?: string;
  instanceRef?: string;
  blueprintKey?: string;
  reasoning?: string;
  [key: string]: unknown;
}

export interface PlanPreview {
  planId: string;
  summary?: string;
  trigger?: string;
  steps?: PlanPreviewStep[];
  additionalSpecs?: Record<string, unknown>;
}

export type StagingEvent =
  | { type: "candidate_graph"; payload: CandidateGraphPayload }
  | { type: "plan"; payload: PlanPreview }
  | { type: "turn_started" }
  | { type: "turn_ended" }
  /** Customer rejected/cancelled — drop the staging layer. */
  | { type: "staging_cleared" };

type StagingEventHandler = (event: StagingEvent) => void;

const listeners = new Set<StagingEventHandler>();

export function onStagingEvent(handler: StagingEventHandler): () => void {
  listeners.add(handler);
  return () => {
    listeners.delete(handler);
  };
}

export function emitStagingEvent(event: StagingEvent): void {
  listeners.forEach((handler) => {
    try {
      handler(event);
    } catch {
      // swallow handler errors so one bad listener can't break the stream
    }
  });
}
