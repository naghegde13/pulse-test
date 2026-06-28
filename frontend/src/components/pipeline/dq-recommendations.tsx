"use client";

import { useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { ScrollArea } from "@/components/ui/scroll-area";
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { cn } from "@/lib/utils";
import { api } from "@/lib/api";
import type { DqRecommendation, DqSeverity } from "@/types";

/* ── Helpers ─────────────────────────────────────────────── */

const SEVERITY_STYLES: Record<DqSeverity, string> = {
  critical: "bg-red-100 text-red-800 dark:bg-red-900 dark:text-red-200",
  warning: "bg-yellow-100 text-yellow-800 dark:bg-yellow-900 dark:text-yellow-200",
  info: "bg-blue-100 text-blue-800 dark:bg-blue-900 dark:text-blue-200",
};

function gxClassToDisplay(gxClass: string): string {
  // e.g. "ExpectColumnValuesToNotBeNull" → "Column Values To Not Be Null"
  return gxClass
    .replace(/^Expect/, "")
    .replace(/([A-Z])/g, " $1")
    .trim();
}

/* ── Component ──────────────────────────────────────────── */

interface DqRecommendationsPanelProps {
  versionId: string;
  recommendations: DqRecommendation[];
  reasoning: string | null;
  onAccept?: (accepted: DqRecommendation[]) => void;
  onClose?: () => void;
}

export function DqRecommendationsPanel({
  versionId,
  recommendations,
  reasoning,
  onAccept,
  onClose,
}: DqRecommendationsPanelProps) {
  const [dismissed, setDismissed] = useState<Set<string>>(new Set());
  const [acceptedInstances, setAcceptedInstances] = useState<Set<string>>(new Set());
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const dismissKey = (instanceName: string, idx: number) =>
    `${instanceName}::${idx}`;

  const handleDismiss = (instanceName: string, idx: number) => {
    setDismissed((prev) => new Set(prev).add(dismissKey(instanceName, idx)));
  };

  const handleAcceptSingle = async (rec: DqRecommendation, expIdx: number) => {
    if (!rec.instance_id) return;
    setSaving(true);
    setError(null);
    try {
      await api.put(
        `/api/v1/versions/${versionId}/instances/${rec.instance_id}/dq-expectations`,
        { expectations: [rec.expectations[expIdx]] }
      );
      setAcceptedInstances((prev) => new Set(prev).add(dismissKey(rec.instance_name, expIdx)));
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to save expectation");
    } finally {
      setSaving(false);
    }
  };

  const handleAcceptAll = async () => {
    setSaving(true);
    setError(null);
    try {
      // Group expectations by instance and save all at once per instance
      for (const rec of recommendations) {
        if (!rec.instance_id) continue;
        const nonDismissed = rec.expectations.filter(
          (_, idx) => !dismissed.has(dismissKey(rec.instance_name, idx))
        );
        if (nonDismissed.length === 0) continue;
        await api.put(
          `/api/v1/versions/${versionId}/instances/${rec.instance_id}/dq-expectations`,
          { expectations: nonDismissed }
        );
      }
      onAccept?.(recommendations);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to save expectations");
    } finally {
      setSaving(false);
    }
  };

  const totalExpectations = recommendations.reduce(
    (sum, r) => sum + r.expectations.length,
    0
  );
  const activeDismissed = dismissed.size;

  return (
    <Card className="mt-3 border-dashed">
      <CardHeader className="pb-2">
        <div className="flex items-center justify-between">
          <CardTitle className="text-sm font-medium">
            AI DQ Recommendations
            <Badge variant="secondary" className="ml-2 text-[10px]">
              {totalExpectations - activeDismissed} suggestions
            </Badge>
          </CardTitle>
          <div className="flex items-center gap-2">
            <Button
              size="sm"
              className="h-6 text-[10px]"
              onClick={handleAcceptAll}
              disabled={saving || totalExpectations - activeDismissed === 0}
            >
              {saving ? "Saving..." : "Accept All"}
            </Button>
            {onClose && (
              <button
                onClick={onClose}
                className="text-xs text-muted-foreground hover:text-foreground"
              >
                ✕
              </button>
            )}
          </div>
        </div>
      </CardHeader>
      <CardContent>
        {reasoning && (
          <div className="mb-3 rounded-md bg-muted/50 px-3 py-2">
            <p className="text-[10px] text-muted-foreground font-medium mb-0.5">
              AI Reasoning
            </p>
            <p className="text-xs leading-relaxed">{reasoning}</p>
          </div>
        )}

        {error && (
          <div className="mb-3 rounded-md bg-destructive/10 text-destructive text-xs px-3 py-2">
            {error}
          </div>
        )}

        <ScrollArea className="max-h-[400px]">
          <div className="space-y-3">
            {recommendations.map((rec) => (
              <div key={rec.instance_name} className="space-y-1">
                <div className="flex items-center gap-2">
                  <span className="text-xs font-medium">{rec.instance_name}</span>
                  <Badge variant="outline" className="text-[10px]">
                    {rec.expectations.length} expectations
                  </Badge>
                </div>
                <div className="space-y-1 pl-2 border-l-2 border-muted">
                  {rec.expectations.map((exp, idx) => {
                    const key = dismissKey(rec.instance_name, idx);
                    const isDismissed = dismissed.has(key);
                    const isAccepted = acceptedInstances.has(key);

                    if (isDismissed) return null;

                    return (
                      <div
                        key={key}
                        className={cn(
                          "flex items-start gap-2 rounded-md px-2 py-1.5 text-xs",
                          isAccepted
                            ? "bg-green-50 dark:bg-green-950/30"
                            : "hover:bg-muted/30"
                        )}
                      >
                        <div className="flex-1 min-w-0">
                          <div className="flex items-center gap-1.5 flex-wrap">
                            <span className="font-medium">
                              {gxClassToDisplay(exp.type)}
                            </span>
                            <Badge
                              variant="outline"
                              className={cn(
                                "text-[9px] border-0",
                                SEVERITY_STYLES[exp.severity]
                              )}
                            >
                              {exp.severity}
                            </Badge>
                            {isAccepted && (
                              <Badge variant="outline" className="text-[9px] text-green-600">
                                ✓ Accepted
                              </Badge>
                            )}
                          </div>
                          {Object.keys(exp.kwargs).length > 0 && (
                            <div className="flex gap-2 mt-0.5 flex-wrap">
                              {Object.entries(exp.kwargs).map(([k, v]) => (
                                <span
                                  key={k}
                                  className="text-[10px] text-muted-foreground font-mono"
                                >
                                  {k}={JSON.stringify(v)}
                                </span>
                              ))}
                            </div>
                          )}
                          {exp.reason && (
                            <p className="text-[10px] text-muted-foreground mt-0.5 italic">
                              {exp.reason}
                            </p>
                          )}
                        </div>
                        {!isAccepted && (
                          <div className="flex items-center gap-1 shrink-0">
                            <Button
                              variant="ghost"
                              size="sm"
                              className="h-5 px-1.5 text-[10px]"
                              onClick={() => handleAcceptSingle(rec, idx)}
                              disabled={saving}
                            >
                              Accept
                            </Button>
                            <Button
                              variant="ghost"
                              size="sm"
                              className="h-5 px-1.5 text-[10px] text-muted-foreground hover:text-destructive"
                              onClick={() =>
                                handleDismiss(rec.instance_name, idx)
                              }
                            >
                              Dismiss
                            </Button>
                          </div>
                        )}
                      </div>
                    );
                  })}
                </div>
              </div>
            ))}
          </div>
        </ScrollArea>
      </CardContent>
    </Card>
  );
}
