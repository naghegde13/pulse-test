"use client";

import { Suspense, useEffect, useState, useCallback, useTransition } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { Badge } from "@/components/ui/badge";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { CreatePipelineDialog } from "@/components/pipeline/create-pipeline-dialog";
import { useTenant } from "@/contexts/tenant-context";
import { useTenantDomains } from "@/hooks/use-tenant-domains";
import { api } from "@/lib/api";
import {
  resolveDomainName,
  resolveDomainOptionByValue,
} from "@/lib/domains";
import { STAGE_LABELS, type Pipeline, type PipelineVersion } from "@/types";

interface PipelineWithVersion extends Pipeline {
  activeVersion?: PipelineVersion;
}

export default function PipelinesPage() {
  return (
    <Suspense fallback={<p className="text-muted-foreground">Loading pipelines...</p>}>
      <PipelinesContent />
    </Suspense>
  );
}

function PipelinesContent() {
  const { currentTenant } = useTenant();
  const { domainOptions } = useTenantDomains(currentTenant);
  const searchParams = useSearchParams();
  const domainFilter = searchParams.get("domain");
  const selectedDomain = resolveDomainOptionByValue(domainFilter, domainOptions);
  const [pipelines, setPipelines] = useState<PipelineWithVersion[]>([]);
  const [isPending, startTransition] = useTransition();
  const [initialLoad, setInitialLoad] = useState(true);

  const fetchPipelines = useCallback(() => {
    if (!currentTenant) return;
    const query =
      selectedDomain?.id != null
        ? `?domainId=${encodeURIComponent(selectedDomain.id)}`
        : domainFilter
        ? `?domain=${encodeURIComponent(domainFilter)}`
        : "";

    startTransition(async () => {
      try {
        const data = await api.get<Pipeline[]>(
          `/api/v1/tenants/${currentTenant.id}/pipelines${query}`
        );
        const withVersions = await Promise.all(
          data.map(async (pipeline) => {
            const hydratedPipeline: PipelineWithVersion = {
              ...pipeline,
              domainId:
                pipeline.domainId ??
                domainOptions.find((domain) => domain.name === pipeline.domainName)?.id,
            };

            if (!pipeline.activeVersionId) return hydratedPipeline;
            try {
              const versions = await api.get<PipelineVersion[]>(
                `/api/v1/tenants/${currentTenant.id}/pipelines/${pipeline.id}/versions`
              );
              const active = versions.find((version) => version.id === pipeline.activeVersionId);
              return { ...hydratedPipeline, activeVersion: active };
            } catch {
              return hydratedPipeline;
            }
          })
        );
        setPipelines(withVersions);
      } catch {
        setPipelines([]);
      } finally {
        setInitialLoad(false);
      }
    });
  }, [currentTenant, domainFilter, domainOptions, selectedDomain]);

  useEffect(() => {
    fetchPipelines();
  }, [fetchPipelines]);

  const handleCreated = (pipeline: Pipeline) => {
    const withVersion: PipelineWithVersion = {
      ...pipeline,
      activeVersion: {
        id: pipeline.activeVersionId ?? "",
        pipelineId: pipeline.id,
        revision: 1,
        lifecycleStage: "ENGINEERING",
        createdBy: pipeline.createdBy,
        createdAt: pipeline.createdAt,
        updatedAt: pipeline.updatedAt,
      },
    };
    setPipelines((prev) => [withVersion, ...prev]);
  };

  const loading = initialLoad && isPending;
  const domainFilterLabel = selectedDomain?.name ?? domainFilter;

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-2xl font-bold tracking-tight">Pipelines</h2>
          <p className="text-muted-foreground">
            {currentTenant
              ? domainFilterLabel
                ? `Pipelines in ${domainFilterLabel} / ${currentTenant.name}`
                : `Pipelines for ${currentTenant.name}`
              : "Select a tenant to view pipelines"}
          </p>
        </div>
        {currentTenant && <CreatePipelineDialog onCreated={handleCreated} />}
      </div>

      {loading ? (
        <p className="text-muted-foreground">Loading pipelines...</p>
      ) : pipelines.length === 0 ? (
        <Card>
          <CardHeader>
            <CardTitle>No pipelines yet</CardTitle>
            <CardDescription>
              Create your first Phase 1 pipeline using the button above. Start with File Ingestion,
              then add cleaning, normalization, data quality, and serving steps.
            </CardDescription>
          </CardHeader>
        </Card>
      ) : (
        <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
          {pipelines.map((pipeline) => (
            <Link key={pipeline.id} href={`/pipelines/${pipeline.id}`}>
              <Card className="hover:border-primary/50 transition-colors cursor-pointer">
                <CardHeader className="pb-3">
                  <div className="flex items-center justify-between gap-3">
                    <CardTitle className="text-base">{pipeline.name}</CardTitle>
                    {pipeline.activeVersion && (
                      <Badge
                        variant={
                          pipeline.activeVersion.lifecycleStage === "ENGINEERING"
                            ? "secondary"
                            : "default"
                        }
                        className="text-[10px]"
                      >
                        {STAGE_LABELS[pipeline.activeVersion.lifecycleStage]}
                      </Badge>
                    )}
                  </div>
                  <CardDescription className="line-clamp-2">
                    {pipeline.description || "No description"}
                  </CardDescription>
                </CardHeader>
                <CardContent>
                  <div className="flex items-center gap-4 text-xs text-muted-foreground">
                    <span>{resolveDomainName(pipeline, domainOptions)}</span>
                    {pipeline.activeVersion && (
                      <span className="font-mono">
                        Rev #{pipeline.activeVersion.revision}
                      </span>
                    )}
                    <span>{new Date(pipeline.updatedAt).toLocaleDateString()}</span>
                  </div>
                </CardContent>
              </Card>
            </Link>
          ))}
        </div>
      )}
    </div>
  );
}
