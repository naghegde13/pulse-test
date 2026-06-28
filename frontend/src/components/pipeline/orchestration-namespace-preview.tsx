"use client";

import { useEffect, useState, useTransition } from "react";
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { api } from "@/lib/api";
import type { OrchestrationNamespacePreview as NamespacePreview } from "@/types/orchestration";

interface OrchestrationNamespacePreviewProps {
  pipelineId: string;
}

export function OrchestrationNamespacePreview({
  pipelineId,
}: OrchestrationNamespacePreviewProps) {
  const [preview, setPreview] = useState<NamespacePreview | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [isPending, startTransition] = useTransition();

  useEffect(() => {
    if (!pipelineId) return;
    startTransition(async () => {
      try {
        const data = await api.get<NamespacePreview>(
          `/api/v1/pipelines/${pipelineId}/orchestration-namespace-preview`
        );
        setPreview(data);
        setError(null);
      } catch (err) {
        setError(
          err instanceof Error ? err.message : "Failed to load namespace preview"
        );
        setPreview(null);
      }
    });
  }, [pipelineId]);

  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="text-sm font-medium">
          Orchestration Namespace
        </CardTitle>
      </CardHeader>
      <CardContent>
        {isPending && (
          <div className="text-xs text-muted-foreground">Loading namespace preview…</div>
        )}

        {error && (
          <div className="rounded-md border border-destructive/30 bg-destructive/5 px-3 py-2 text-xs text-destructive">
            {error}
          </div>
        )}

        {!isPending && !error && preview && (
          <div className="space-y-2 text-xs">
            <div className="rounded bg-muted/40 px-2.5 py-2">
              <div className="font-medium text-foreground/80">Logical DAG ID</div>
              <div className="mt-1 font-mono text-muted-foreground break-all">
                {preview.logicalDagId}
              </div>
            </div>
            <div className="rounded bg-muted/40 px-2.5 py-2">
              <div className="font-medium text-foreground/80">Package Namespace</div>
              <div className="mt-1 font-mono text-muted-foreground break-all">
                {preview.logicalPackageNamespace}
              </div>
            </div>
            <div className="rounded bg-muted/40 px-2.5 py-2">
              <div className="font-medium text-foreground/80">Pipeline Complete URI</div>
              <div className="mt-1 font-mono text-muted-foreground break-all">
                {preview.pipelineCompleteUri}
              </div>
            </div>
            {Object.keys(preview.tableReadyUris).length > 0 && (
              <div className="rounded bg-muted/40 px-2.5 py-2">
                <div className="font-medium text-foreground/80">Table Ready URIs</div>
                <div className="mt-1 space-y-1">
                  {Object.entries(preview.tableReadyUris).map(([table, uri]) => (
                    <div key={table} className="flex flex-col">
                      <span className="font-medium text-foreground/70">{table}</span>
                      <span className="font-mono text-muted-foreground break-all">
                        {uri}
                      </span>
                    </div>
                  ))}
                </div>
              </div>
            )}
          </div>
        )}

        {!isPending && !error && !preview && (
          <div className="text-xs text-muted-foreground">
            No namespace preview available.
          </div>
        )}
      </CardContent>
    </Card>
  );
}
