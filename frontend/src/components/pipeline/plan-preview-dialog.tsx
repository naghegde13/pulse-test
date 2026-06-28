"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";

interface PlanAction {
  type: string;
  description: string;
}

interface PlanPreviewDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  description: string;
  actions: PlanAction[];
  onApply: () => Promise<void>;
  applyLabel?: string;
}

export function PlanPreviewDialog({
  open,
  onOpenChange,
  title,
  description,
  actions,
  onApply,
  applyLabel = "Apply Plan",
}: PlanPreviewDialogProps) {
  const [applying, setApplying] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleApply = async () => {
    setApplying(true);
    setError(null);
    try {
      await onApply();
      onOpenChange(false);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to apply plan");
    } finally {
      setApplying(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Plan Preview</DialogTitle>
          <DialogDescription>{description}</DialogDescription>
        </DialogHeader>

        <div className="space-y-3 py-2">
          <p className="text-sm font-medium">{title}</p>
          <div className="rounded-md border bg-muted/30 p-3 space-y-2">
            {actions.map((action, i) => (
              <div key={i} className="flex items-center gap-2 text-sm">
                <span className="text-muted-foreground font-mono text-xs w-4">
                  {i + 1}.
                </span>
                <Badge variant="outline" className="font-mono text-[10px]">
                  {action.type}
                </Badge>
                <span className="text-muted-foreground">
                  {action.description}
                </span>
              </div>
            ))}
          </div>
          <p className="text-xs text-muted-foreground">
            {actions.length} command{actions.length !== 1 ? "s" : ""} will be
            executed and logged to the audit trail.
          </p>

          {error && <p className="text-sm text-destructive">{error}</p>}
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button onClick={handleApply} disabled={applying}>
            {applying ? "Applying..." : applyLabel}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
