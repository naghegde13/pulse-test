"use client";

import { cn } from "@/lib/utils";
import {
  PIPELINE_STAGES,
  STAGE_LABELS,
  type PipelineStage,
} from "@/types";

interface LifecycleRibbonProps {
  currentStage: PipelineStage;
}

/**
 * Visualises the 4 PULSE-managed lifecycle stages plus a single static
 * "Handed off to enterprise CD" terminal indicator. PULSE is dev-only
 * (PKT-FINAL-2 / BUG-2026-05-25-02) — anything past PUBLISHED is owned by
 * enterprise CI/CD and is not modelled per-environment here.
 */
export function LifecycleRibbon({ currentStage }: LifecycleRibbonProps) {
  const currentIndex = PIPELINE_STAGES.indexOf(currentStage);
  const isHandedOff = currentStage === "PUBLISHED";

  return (
    <div className="space-y-1">
      <div className="flex items-center gap-1 overflow-x-auto pb-1">
        {PIPELINE_STAGES.map((stage, i) => {
          const isActive = i === currentIndex;
          const isCompleted = i < currentIndex;

          return (
            <div key={stage} className="flex items-center">
              {i > 0 && (
                <div
                  className={cn(
                    "h-px w-4 mx-0.5",
                    isCompleted ? "bg-primary" : "bg-border"
                  )}
                />
              )}
              <div
                className={cn(
                  "flex items-center gap-1.5 rounded-full px-3 py-1 text-xs font-medium whitespace-nowrap transition-colors",
                  isActive && "bg-primary text-primary-foreground",
                  isCompleted && "bg-primary/15 text-primary",
                  !isActive && !isCompleted && "bg-muted text-muted-foreground"
                )}
              >
                <span
                  className={cn(
                    "flex h-4 w-4 items-center justify-center rounded-full text-[10px] font-bold",
                    isActive && "bg-primary-foreground text-primary",
                    isCompleted && "bg-primary text-primary-foreground",
                    !isActive && !isCompleted && "bg-muted-foreground/30 text-muted-foreground"
                  )}
                >
                  {isCompleted ? "✓" : i + 1}
                </span>
                {STAGE_LABELS[stage]}
              </div>
            </div>
          );
        })}

        {/* Static terminal indicator — not a stage PULSE owns or tracks. */}
        <div className="flex items-center" data-testid="enterprise-cd-handoff">
          <div
            className={cn(
              "h-px w-6 mx-0.5 border-t border-dashed",
              isHandedOff
                ? "border-amber-500/60"
                : "border-muted-foreground/40"
            )}
          />
          <div
            className={cn(
              "flex items-center gap-1.5 rounded-full px-3 py-1 text-xs font-medium whitespace-nowrap border border-dashed",
              isHandedOff
                ? "border-amber-500/40 bg-amber-500/10 text-amber-700"
                : "border-muted-foreground/30 bg-muted/40 text-muted-foreground/70"
            )}
            title="Promotion to integration, UAT, and production is managed by enterprise CI/CD after the PULSE-built artifact is published."
          >
            <span className="inline-block h-2 w-2 rounded-full bg-current opacity-60" />
            Handed off to enterprise CD
          </div>
        </div>
      </div>
      <div className="flex items-center gap-3 text-[10px] text-muted-foreground px-1">
        <span className="flex items-center gap-1">
          <span className="inline-block h-2 w-2 rounded-full bg-primary" />
          PULSE managed (dev only)
        </span>
        <span className="flex items-center gap-1">
          <span className="inline-block h-2 w-2 rounded-full border border-dashed border-muted-foreground/60" />
          Enterprise CD (out of PULSE scope)
        </span>
      </div>
    </div>
  );
}
