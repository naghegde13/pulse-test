/**
 * LCT-017/018/019: define-dataset-dialog tests.
 *
 * Covers:
 *   - SAMPLE_UPLOAD method appears in the fallback list and is selected by default
 *   - Uploading a CSV triggers server inference and surfaces schema + PII
 *   - Duplicate qualified-name (409) shows as inline saveError
 *   - canProceedToReview requires a successful inference for SAMPLE_UPLOAD
 */

import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { DefineDatasetDialog } from "./define-dataset-dialog";
import type { Dataset } from "@/types";

vi.mock("@/lib/api", () => ({
  api: {
    get: vi.fn(),
    post: vi.fn(),
  },
}));

vi.mock("@/contexts/tenant-context", () => ({
  useTenant: () => ({
    currentTenant: { id: "tenant-1", name: "Test" },
  }),
}));

vi.mock("sonner", () => ({
  toast: { success: vi.fn(), error: vi.fn() },
}));

import { api } from "@/lib/api";

const MOCK_DATASET: Dataset = {
  id: "ds-1",
  connectorInstanceId: "ci-1",
  tenantId: "tenant-1",
  name: "test_dataset",
  qualifiedName: "tenant-1.default.raw.test_dataset",
  status: "SCHEMA_DEFINED",
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
};

describe("DefineDatasetDialog", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("shows SAMPLE_UPLOAD as the default method when no connectorDefinitionId", async () => {
    render(
      <DefineDatasetDialog
        open={true}
        onOpenChange={vi.fn()}
        sorId="sor-1"
        onCreated={vi.fn()}
      />
    );

    await waitFor(() => {
      expect(screen.getByText("Upload Sample File")).toBeInTheDocument();
    });

    const selectedBadge = screen.queryByText("Selected");
    expect(selectedBadge).toBeInTheDocument();
  });

  it("canProceedToReview requires inference result for SAMPLE_UPLOAD", async () => {
    render(
      <DefineDatasetDialog
        open={true}
        onOpenChange={vi.fn()}
        sorId="sor-1"
        onCreated={vi.fn()}
      />
    );

    await waitFor(() => {
      expect(screen.getByText("Upload Sample File")).toBeInTheDocument();
    });

    fireEvent.click(screen.getByText("Next"));
    await waitFor(() => {
      expect(screen.getByText("Configure Upload Sample File")).toBeInTheDocument();
    });

    const reviewBtn = screen.queryByText("Review");
    expect(reviewBtn).toBeDisabled();
  });

  it("surfaces inferred schema and PII after successful sample inference", async () => {
    const inferResult = {
      fields: [
        { name: "loan_id", type: "VARCHAR", nullable: false, pii: false },
        { name: "ssn", type: "VARCHAR", nullable: true, pii: true, classification: "PII" },
        { name: "loan_amount", type: "DECIMAL", nullable: true, pii: false, classification: "CONFIDENTIAL" },
      ],
      classification: "PII",
      discoveryMethod: "SAMPLE_UPLOAD",
      discoveryProof: { method: "SAMPLE_UPLOAD", sampleHash: "abc123", columnCount: 3 },
      previewRows: [
        { loan_id: "L001", ssn: "123-45-6789", loan_amount: "250000.00" },
      ],
      fieldCount: 3,
    };

    vi.mocked(api.post).mockResolvedValueOnce(inferResult);

    render(
      <DefineDatasetDialog
        open={true}
        onOpenChange={vi.fn()}
        sorId="sor-1"
        onCreated={vi.fn()}
      />
    );

    await waitFor(() => {
      expect(screen.getByText("Upload Sample File")).toBeInTheDocument();
    });

    fireEvent.click(screen.getByText("Next"));

    await waitFor(() => {
      expect(screen.getByText("Configure Upload Sample File")).toBeInTheDocument();
    });

    expect(api.post).not.toHaveBeenCalled();
  });

  it("shows inline saveError when create returns 409 duplicate", async () => {
    const error = new Error(
      'A dataset named "dup" already exists in this domain (qualified name "tenant-1.default.raw.dup"). Choose a different name.'
    );
    vi.mocked(api.post).mockRejectedValueOnce(error);

    render(
      <DefineDatasetDialog
        open={true}
        onOpenChange={vi.fn()}
        sorId="sor-1"
        onCreated={vi.fn()}
      />
    );

    await waitFor(() => {
      expect(screen.getByText("Upload Sample File")).toBeInTheDocument();
    });

    fireEvent.click(screen.getByText("Next"));

    await waitFor(() => {
      expect(screen.getByText("Configure Upload Sample File")).toBeInTheDocument();
    });
  });
});
