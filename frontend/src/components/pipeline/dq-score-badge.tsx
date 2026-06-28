"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";
import { useDqReadiness } from "@/hooks/use-dq-readiness";
import { DqRecommendationsPanel } from "./dq-recommendations";
import type { DqRecommendation } from "@/types";

interface DqScoreBadgeProps {
  versionId: string;
  onAcceptRecommendations?: (accepted: DqRecommendation[]) => void;
}

function scoreColor(score: number): string {
  if (score >= 80) return "text-green-600 dark:text-green-400";
  if (score >= 50) return "text-yellow-600 dark:text-yellow-400";
  return "text-red-600 dark:text-red-400";
}

function scoreBorderColor(score: number): string {
  if (score >= 80) return "border-green-500";
  if (score >= 50) return "border-yellow-500";
  return "border-red-500";
}

function scoreTrackColor(score: number): string {
  if (score >= 80) return "stroke-green-500";
  if (score >= 50) return "stroke-yellow-500";
  return "stroke-red-500";
}

export function DqScoreBadge({
  versionId,
  onAcceptRecommendations,
}: DqScoreBadgeProps) {
  const { score, recommendations, reasoning, loading, error, evaluate } =
    useDqReadiness(versionId);
  const [showPanel, setShowPanel] = useState(false);

  if (score === null) {
    return (
      <Button
        variant="outline"
        size="sm"
        className="h-7 text-xs gap-1.5"
        onClick={evaluate}
        disabled={loading}
      >
        {loading ? "Evaluating..." : "Evaluate DQ"}
      </Button>
    );
  }

  const radius = 14;
  const circumference = 2 * Math.PI * radius;
  const dashOffset = circumference - (score / 100) * circumference;

  return (
    <>
      <button
        onClick={() => setShowPanel(!showPanel)}
        className={cn(
          "inline-flex items-center gap-1.5 rounded-md border px-2 py-1 text-xs font-medium transition-colors hover:bg-muted/50",
          scoreBorderColor(score)
        )}
        title={`DQ Readiness Score: ${score}/100. Click to ${showPanel ? "hide" : "view"} recommendations.`}
      >
        <svg width="32" height="32" viewBox="0 0 36 36" className="shrink-0">
          <circle
            cx="18"
            cy="18"
            r={radius}
            fill="none"
            stroke="currentColor"
            strokeWidth="3"
            className="text-muted/40"
          />
          <circle
            cx="18"
            cy="18"
            r={radius}
            fill="none"
            strokeWidth="3"
            strokeLinecap="round"
            strokeDasharray={circumference}
            strokeDashoffset={dashOffset}
            transform="rotate(-90 18 18)"
            className={scoreTrackColor(score)}
          />
          <text
            x="18"
            y="19"
            textAnchor="middle"
            dominantBaseline="middle"
            className={cn("text-[10px] font-bold", scoreColor(score))}
            fill="currentColor"
          >
            {score}
          </text>
        </svg>
        <span className={scoreColor(score)}>DQ</span>
        {error && (
          <Badge variant="destructive" className="text-[9px]">
            Error
          </Badge>
        )}
      </button>

      {showPanel && (
        <DqRecommendationsPanel
          versionId={versionId}
          recommendations={recommendations}
          reasoning={reasoning}
          onAccept={onAcceptRecommendations}
          onClose={() => setShowPanel(false)}
        />
      )}
    </>
  );
}
