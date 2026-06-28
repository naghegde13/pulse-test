"use client";

import { useEffect, useState, useTransition } from "react";
import { Badge } from "@/components/ui/badge";
import { api } from "@/lib/api";
import type { ContractReadiness } from "@/types/orchestration";
import type { LandingContractPreview } from "@/types";

interface ContractReadinessBadgeProps {
  versionId: string;
  /** Optional dataset ID to fetch landing contract preview. */
  datasetId?: string;
}

export function ContractReadinessBadge({
  versionId,
  datasetId,
}: ContractReadinessBadgeProps) {
  const [readiness, setReadiness] = useState<ContractReadiness | null>(null);
  const [landingPreview, setLandingPreview] = useState<LandingContractPreview | null>(null);
  const [isPending, startTransition] = useTransition();

  useEffect(() => {
    if (!versionId) return;
    startTransition(async () => {
      try {
        const [contractData, preview] = await Promise.all([
          api.get<ContractReadiness>(
            `/api/v1/versions/${versionId}/contract-readiness`
          ),
          datasetId
            ? api.get<LandingContractPreview>(
                `/api/v1/datasets/${datasetId}/landing-contract/preview`
              ).catch(() => null)
            : Promise.resolve(null),
        ]);
        setReadiness(contractData);
        setLandingPreview(preview);
      } catch {
        setReadiness(null);
        setLandingPreview(null);
      }
    });
  }, [versionId, datasetId]);

  if (isPending || !readiness) return null;

  return (
    <div className="flex items-center gap-1.5 flex-wrap">
      {readiness.ready ? (
        <Badge
          variant="outline"
          className="text-[10px] bg-green-500/10 text-green-700 border-green-500/20"
        >
          Contracts Ready ({readiness.activeContractCount})
        </Badge>
      ) : (
        <Badge
          variant="outline"
          className="text-[10px] bg-red-500/10 text-red-700 border-red-500/20"
          title={
            readiness.blockers.length > 0
              ? readiness.blockers.map((b) => b.message).join("; ")
              : undefined
          }
        >
          Contracts Not Ready ({readiness.blockers.length} blocker
          {readiness.blockers.length !== 1 ? "s" : ""})
        </Badge>
      )}
      {landingPreview && (
        <>
          {landingPreview.contractUri ? (
            <Badge
              variant="outline"
              className="text-[10px] font-mono max-w-[300px] truncate"
              title={`Landing contract: ${landingPreview.contractUri}`}
            >
              {landingPreview.contractUri}
            </Badge>
          ) : landingPreview.bindingBlockers.length > 0 ? (
            <Badge
              variant="outline"
              className="text-[10px] bg-amber-500/10 text-amber-700 border-amber-500/20"
              title={landingPreview.bindingBlockers.join("; ")}
            >
              landing contract blocked ({landingPreview.bindingBlockers.length})
            </Badge>
          ) : null}
        </>
      )}
    </div>
  );
}
