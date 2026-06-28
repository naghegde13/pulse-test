"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { useTenant } from "@/contexts/tenant-context";
import { api } from "@/lib/api";
import type { Pipeline } from "@/types";

interface DeletePipelineDialogProps {
  pipeline: Pipeline;
  onDeleted: () => void;
}

export function DeletePipelineDialog({
  pipeline,
  onDeleted,
}: DeletePipelineDialogProps) {
  const { currentTenant } = useTenant();
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleDelete = async () => {
    if (!currentTenant) return;
    setLoading(true);
    setError(null);

    try {
      await api.delete(
        `/api/v1/tenants/${currentTenant.id}/pipelines/${pipeline.id}`
      );
      setOpen(false);
      onDeleted();
    } catch (err) {
      setError(
        err instanceof Error ? err.message : "Failed to delete pipeline"
      );
    } finally {
      setLoading(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button variant="destructive" size="sm">
          Delete
        </Button>
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Delete Pipeline</DialogTitle>
          <DialogDescription>
            Are you sure you want to delete <strong>{pipeline.name}</strong>?
            This action cannot be undone. Only DRAFT pipelines can be deleted.
          </DialogDescription>
        </DialogHeader>

        {error && <p className="text-sm text-destructive">{error}</p>}

        <DialogFooter>
          <Button variant="outline" onClick={() => setOpen(false)}>
            Cancel
          </Button>
          <Button
            variant="destructive"
            onClick={handleDelete}
            disabled={loading}
          >
            {loading ? "Deleting..." : "Delete Pipeline"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
