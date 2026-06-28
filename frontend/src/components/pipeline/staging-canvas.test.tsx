import React from "react";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi, beforeEach } from "vitest";
import { StagingCanvas } from "./staging-canvas";
import { emitStagingEvent } from "@/lib/staging-events";
import { api } from "@/lib/api";

vi.mock("@xyflow/react", () => ({
  ReactFlow: ({ children }: { children?: React.ReactNode }) => (
    <div data-testid="react-flow">{children}</div>
  ),
  Background: () => <div data-testid="react-flow-background" />,
  Controls: () => <div data-testid="react-flow-controls" />,
  MarkerType: { ArrowClosed: "arrowclosed" },
  useNodesState: (initial: unknown[]) => {
    const [nodes, setNodes] = React.useState(initial);
    return [nodes, setNodes, vi.fn()];
  },
  useEdgesState: (initial: unknown[]) => {
    const [edges, setEdges] = React.useState(initial);
    return [edges, setEdges, vi.fn()];
  },
}));

vi.mock("@/lib/api", () => ({
  api: {
    post: vi.fn(),
  },
}));

const candidateGraph = {
  name: "Loan pipeline",
  instances: [
    {
      ref: "ingest",
      blueprintKey: "GraphSource",
      params: {},
      storageBackend: "DPC",
      lakeLayer: "bronze",
      lakeFormat: "parquet",
    },
  ],
  wirings: [],
  diff: {
    changeCount: 1,
    instanceStatus: { ingest: "ADDED" },
    wireStatus: [],
  },
};

const plan = {
  planId: "plan-1",
  summary: "Review 1 staged composition change before Apply.",
  steps: [
    {
      op: "composition.addInstance",
      instanceRef: "ingest",
      blueprintKey: "GraphSource",
      reasoning: "Add step 'ingest' [GraphSource]",
    },
  ],
};

describe("StagingCanvas plan decisions", () => {
  beforeEach(() => {
    vi.mocked(api.post).mockResolvedValue({});
  });

  it("renders the Review banner and posts Apply to the session-scoped decision endpoint", async () => {
    render(<StagingCanvas sessionId="session-1" />);

    await act(async () => {
      emitStagingEvent({ type: "candidate_graph", payload: candidateGraph });
      emitStagingEvent({ type: "plan", payload: plan });
    });

    fireEvent.click(screen.getByTestId("review-changes-banner"));
    expect(await screen.findByText("composition.addInstance")).toBeInTheDocument();
    expect(screen.getByText("GraphSource")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /apply plan/i }));

    await waitFor(() => {
      expect(api.post).toHaveBeenCalledWith(
        "/api/v1/chat/sessions/session-1/plans/plan-1/decision",
        { decision: "approve" }
      );
    });
    await waitFor(() => {
      expect(screen.queryByTestId("review-changes-banner")).not.toBeInTheDocument();
    });
  });

  it("supports Modify through the same decision endpoint", async () => {
    render(<StagingCanvas sessionId="session-1" />);

    await act(async () => {
      emitStagingEvent({ type: "candidate_graph", payload: candidateGraph });
      emitStagingEvent({ type: "plan", payload: plan });
    });

    fireEvent.click(screen.getByTestId("review-changes-banner"));
    fireEvent.click(await screen.findByRole("button", { name: /modify/i }));

    await waitFor(() => {
      expect(api.post).toHaveBeenCalledWith(
        "/api/v1/chat/sessions/session-1/plans/plan-1/decision",
        { decision: "modify" }
      );
    });
  });
});
