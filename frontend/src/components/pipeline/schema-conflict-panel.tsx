"use client";

import { useCallback, useEffect, useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { api } from "@/lib/api";

interface SchemaConflict {
  id: string;
  versionId: string;
  instanceId: string;
  portName?: string | null;
  conflictType: string;
  details: Record<string, unknown>;
  resolutionStatus: string;
  resolutionType?: string | null;
  createdAt?: string;
}

interface ConflictResolutionPreview {
  conflictId: string;
  conflictType: string;
  classification: string;
  impactRadius: string[];
  resolutionType: string;
  willResolve: boolean;
  mutations: string[];
  details: Record<string, unknown>;
}

export interface SchemaConflictPanelProps {
  versionId: string;
  instanceId?: string;
  onResolved?: () => void;
}

export function SchemaConflictPanel({
  versionId,
  instanceId,
  onResolved,
}: SchemaConflictPanelProps) {
  const [conflicts, setConflicts] = useState<SchemaConflict[] | null>(null);
  const [previews, setPreviews] = useState<Record<string, ConflictResolutionPreview>>({});
  const [pendingResolution, setPendingResolution] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      setError(null);
      const rows = await api.get<SchemaConflict[]>(
        `/api/v1/versions/${versionId}/schema-conflicts?includeResolved=false`
      );
      const filtered = instanceId
        ? rows.filter((r) => r.instanceId === instanceId)
        : rows;
      setConflicts(filtered);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to load conflicts");
      setConflicts([]);
    }
  }, [versionId, instanceId]);

  useEffect(() => {
    let cancelled = false;
    api
      .get<SchemaConflict[]>(
        `/api/v1/versions/${versionId}/schema-conflicts?includeResolved=false`
      )
      .then((rows) => {
        if (cancelled) return;
        const filtered = instanceId
          ? rows.filter((r) => r.instanceId === instanceId)
          : rows;
        setConflicts(filtered);
      })
      .catch((err) => {
        if (cancelled) return;
        setError(err instanceof Error ? err.message : "Failed to load conflicts");
        setConflicts([]);
      });
    return () => {
      cancelled = true;
    };
  }, [versionId, instanceId]);

  async function preview(conflict: SchemaConflict, resolutionType: string) {
    try {
      setError(null);
      setPendingResolution((prev) => ({ ...prev, [conflict.id]: resolutionType }));
      const result = await api.post<ConflictResolutionPreview>(
        `/api/v1/versions/${versionId}/schema-conflicts/${conflict.id}/preview`,
        { resolutionType }
      );
      setPreviews((prev) => ({ ...prev, [conflict.id]: result }));
      window.dispatchEvent(new CustomEvent("pulse:schema-impact", {
        detail: {
          instanceIds: result.impactRadius.map((entry) => entry.split(":")[0]).filter(Boolean),
          columns: result.details.column_name
            ? [String(result.details.column_name)]
            : result.details.missing_column
              ? [String(result.details.missing_column)]
              : [],
        },
      }));
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to preview conflict fix");
    }
  }

  async function resolve(conflict: SchemaConflict) {
    const previewResult = previews[conflict.id];
    const resolutionType = pendingResolution[conflict.id];
    if (!previewResult || !resolutionType) {
      setError("Preview the conflict fix before applying it.");
      return;
    }
    try {
      await api.post(
        `/api/v1/versions/${versionId}/schema-conflicts/${conflict.id}/resolve`,
        { resolutionType }
      );
      setPreviews((prev) => {
        const next = { ...prev };
        delete next[conflict.id];
        return next;
      });
      setPendingResolution((prev) => {
        const next = { ...prev };
        delete next[conflict.id];
        return next;
      });
      onResolved?.();
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to resolve conflict");
    }
  }

  if (conflicts === null) {
    return (
      <p className="text-xs text-muted-foreground">Loading conflicts…</p>
    );
  }
  if (conflicts.length === 0) {
    return (
      <p className="text-xs text-muted-foreground">No open schema conflicts.</p>
    );
  }

  return (
    <div className="space-y-3">
      {error && (
        <div className="rounded-md bg-destructive/10 text-destructive text-xs px-3 py-2">
          {error}
        </div>
      )}
      <div className="rounded-md border border-amber-300 bg-amber-50/70 px-3 py-2 text-xs text-amber-900 dark:bg-amber-950/20 dark:text-amber-200">
        <div className="font-medium">Apply gate blocked</div>
        <div className="mt-0.5 text-[11px]">
          Resolve {conflicts.length} schema conflict{conflicts.length !== 1 ? "s" : ""} before this step is treated as clean.
        </div>
      </div>
      {conflicts.map((conflict) => (
        (() => {
          const previewResult = previews[conflict.id];
          const details = conflict.details ?? {};
          const classification = previewResult?.classification
            ?? (typeof details.tier === "string" ? details.tier : "unclassified");
          const radius = previewResult?.impactRadius
            ?? (Array.isArray(details.impact_radius)
              ? details.impact_radius.map(String)
              : [conflict.instanceId]);
          return (
        <div
          key={conflict.id}
          className="rounded-md border border-red-300 bg-red-50/40 dark:bg-red-950/20 px-3 py-2 text-xs"
        >
          <div className="flex flex-wrap items-center gap-2">
            <Badge variant="destructive" className="text-[10px]">
              {conflict.conflictType}
            </Badge>
            <Badge variant="outline" className="text-[10px]">
              {classification}
            </Badge>
            <Badge variant="outline" className="text-[10px]">
              radius {radius.length}
            </Badge>
            {conflict.portName && (
              <span className="font-mono text-[10px] text-muted-foreground">
                {conflict.portName}
              </span>
            )}
          </div>
          <pre className="mt-2 max-h-32 overflow-auto whitespace-pre-wrap break-words rounded bg-background px-2 py-1 text-[11px] text-muted-foreground">
            {JSON.stringify(conflict.details, null, 2)}
          </pre>
          <div className="mt-2 rounded-md border bg-background px-2 py-1.5">
            <div className="font-medium text-[11px]">Impact radius</div>
            <div className="mt-1 flex flex-wrap gap-1">
              {radius.map((entry) => (
                <Badge key={entry} variant="outline" className="text-[10px] font-mono">
                  {entry}
                </Badge>
              ))}
            </div>
          </div>
          {previewResult && (
            <div className="mt-2 rounded-md border border-blue-300 bg-blue-50/60 px-2 py-1.5 text-blue-950 dark:bg-blue-950/20 dark:text-blue-100">
              <div className="font-medium text-[11px]">Fix preview: {previewResult.resolutionType}</div>
              <ul className="mt-1 list-disc pl-4 text-[11px]">
                {previewResult.mutations.map((mutation) => (
                  <li key={mutation}>{mutation}</li>
                ))}
              </ul>
            </div>
          )}
          <div className="mt-2 flex flex-wrap gap-2">
            <Button
              size="sm"
              variant="default"
              onClick={() => preview(conflict, "accept_upstream")}
            >
              Preview accept upstream
            </Button>
            <Button
              size="sm"
              variant="outline"
              onClick={() => preview(conflict, "flag_for_review")}
            >
              Preview review gate
            </Button>
            <Button
              size="sm"
              variant="destructive"
              onClick={() => resolve(conflict)}
              disabled={!previewResult}
            >
              Apply preview
            </Button>
          </div>
        </div>
          );
        })()
      ))}
    </div>
  );
}
