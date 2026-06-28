"use client";

import { useEffect, useMemo, useState, useTransition } from "react";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  CodeViewerDialog,
  type CodeExample,
} from "@/components/blueprints/code-viewer-dialog";
import { api } from "@/lib/api";
import {
  CATEGORY_LABELS,
  type Blueprint,
  type BlueprintCategory,
} from "@/types";

const CATEGORIES: BlueprintCategory[] = [
  "INGESTION",
  "TRANSFORM",
  "MODELING",
  "DATA_QUALITY",
  "ORCHESTRATION",
  "DESTINATION",
];

const MEDALLION_BADGE_CLASSES: Record<string, string> = {
  bronze: "bg-orange-200 text-orange-900",
  silver: "bg-slate-200 text-slate-900",
  gold: "bg-amber-200 text-amber-900",
  control_plane: "bg-blue-200 text-blue-900",
};

function medallionBadgeClass(layer: string): string {
  return MEDALLION_BADGE_CLASSES[layer] ?? "bg-muted text-muted-foreground";
}

function medallionLabel(layer: string): string {
  switch (layer) {
    case "bronze":
      return "Bronze";
    case "silver":
      return "Silver";
    case "gold":
      return "Gold";
    case "control_plane":
      return "Control";
    default:
      return layer;
  }
}

function codeTypeLabels(artifactTypes?: string[]): string[] {
  const out = new Set<string>();
  for (const t of artifactTypes ?? []) {
    if (t === "pyspark_job") out.add("PySpark");
    else if (t === "dbt_model" || t === "dbt_snapshot") out.add("dbt");
    else if (t === "gx_checkpoint") out.add("GX");
    else if (t === "airflow_sensor" || t === "airflow_policy") out.add("Airflow");
  }
  return Array.from(out);
}

function isDbtEmitting(artifactTypes?: string[]): boolean {
  const types = artifactTypes ?? [];
  return types.includes("dbt_model") || types.includes("dbt_snapshot");
}

export default function BlueprintsPage() {
  const [blueprints, setBlueprints] = useState<Blueprint[]>([]);
  const [selectedCategory, setSelectedCategory] =
    useState<BlueprintCategory | null>(null);
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [showDeprecated, setShowDeprecated] = useState(false);
  const [isPending, startTransition] = useTransition();
  const [initialLoad, setInitialLoad] = useState(true);

  useEffect(() => {
    startTransition(async () => {
      try {
        const data = await api.get<Blueprint[]>(
          "/api/v1/blueprints?includeDeferred=true"
        );
        setBlueprints(data);
      } catch {
        setBlueprints([]);
      } finally {
        setInitialLoad(false);
      }
    });
  }, []);

  const loading = initialLoad && isPending;

  const visibleBlueprints = useMemo(
    () =>
      blueprints.filter((bp) => showDeprecated || bp.status !== "deprecated"),
    [blueprints, showDeprecated]
  );

  const grouped = useMemo(
    () =>
      CATEGORIES.map((cat) => ({
        category: cat,
        items: visibleBlueprints.filter((bp) => bp.category === cat),
      })),
    [visibleBlueprints]
  );

  const filtered = selectedCategory
    ? grouped.filter((g) => g.category === selectedCategory)
    : grouped;

  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-2xl font-bold tracking-tight">
          Blueprint Catalog
        </h2>
        <p className="text-muted-foreground">
          {blueprints.length} versioned, composable patterns for building data
          pipelines
        </p>
      </div>

      {/* Category filter */}
      <div className="flex gap-2 flex-wrap items-center">
        <Button
          variant={selectedCategory === null ? "default" : "outline"}
          size="sm"
          onClick={() => setSelectedCategory(null)}
        >
          All
        </Button>
        {CATEGORIES.map((cat) => (
          <Button
            key={cat}
            variant={selectedCategory === cat ? "default" : "outline"}
            size="sm"
            onClick={() => setSelectedCategory(cat)}
          >
            {CATEGORY_LABELS[cat]}
          </Button>
        ))}
        <label className="ml-auto flex items-center gap-2 text-xs text-muted-foreground cursor-pointer">
          <input
            type="checkbox"
            checked={showDeprecated}
            onChange={(e) => setShowDeprecated(e.target.checked)}
          />
          Show deprecated
        </label>
      </div>

      {loading ? (
        <p className="text-muted-foreground">Loading blueprints...</p>
      ) : (
        filtered.map((group) => (
          <div key={group.category} className="space-y-3">
            <div className="flex items-center gap-2">
              <h3 className="text-lg font-semibold">
                {CATEGORY_LABELS[group.category]}
              </h3>
              <Badge variant="secondary" className="text-xs">
                {group.items.length}
              </Badge>
            </div>
            <div className="grid gap-3 grid-cols-[repeat(auto-fill,minmax(320px,1fr))]">
              {group.items.map((bp) => {
                const family = bp.subcategory?.trim() || "General";
                return (
                    <Card
                      key={bp.id}
                      className="cursor-pointer hover:border-primary/50 transition-colors"
                      onClick={() =>
                        setExpandedId(expandedId === bp.id ? null : bp.id)
                      }
                    >
                      <CardHeader className="pb-2">
                        <div className="flex items-center justify-between gap-2">
                          <CardTitle className="text-sm">{bp.name}</CardTitle>
                          <div className="flex items-center gap-1 flex-wrap justify-end">
                            <Badge variant="outline" className="text-[10px] font-mono">
                              v{bp.version}
                            </Badge>
                            {bp.supportsReuse && (
                              <Badge variant="secondary" className="text-[10px]">
                                reuse
                              </Badge>
                            )}
                            {bp.status && bp.status !== "active" && (
                              <Badge variant="destructive" className="text-[10px]">
                                {bp.status}
                              </Badge>
                            )}
                          </div>
                        </div>
                        <div className="pt-0.5">
                          <Badge variant="outline" className="text-[9px] uppercase tracking-wider text-muted-foreground border-dashed">
                            {family}
                          </Badge>
                        </div>
                        <CardDescription className="text-xs">
                          {bp.description}
                        </CardDescription>
                        <div className="flex flex-wrap gap-1 pt-1">
                          {/* compute_backend (lowercase enum: airflow/spark) intentionally
                              omitted — codeTypeLabels below renders the human-friendly
                              equivalent (Airflow / PySpark / dbt / GX). */}
                          {/* emit_strategy is shown only when it's NOT the default
                              "generate" (32 of 42 active blueprints). config_only and
                              runtime_only carry signal worth showing on the card;
                              "generate" on every card was pure noise. */}
                          {bp.emitStrategy && bp.emitStrategy !== "generate" && (
                            <Badge variant="outline" className="text-[10px]">
                              {bp.emitStrategy}
                            </Badge>
                          )}
                          {(bp.validLayers ?? []).map((layer) => (
                            <Badge
                              key={layer}
                              variant="outline"
                              className={`text-[10px] border-0 ${medallionBadgeClass(layer)}`}
                            >
                              {medallionLabel(layer)}
                            </Badge>
                          ))}
                          {codeTypeLabels(bp.artifactTypes).map((label) => (
                            <Badge
                              key={`ct-${label}`}
                              variant="outline"
                              className="text-[10px] font-mono"
                            >
                              {label}
                            </Badge>
                          ))}
                          {bp.status === "deprecated" && (
                            <Badge variant="destructive" className="text-[10px]">
                              Deprecated — use {bp.replacementBlueprintKey ?? "n/a"}
                            </Badge>
                          )}
                        </div>
                      </CardHeader>
                      {expandedId === bp.id && (
                        <CardContent
                          className="pt-0 space-y-2"
                          onClick={(e) => e.stopPropagation()}
                        >
                          {bp.inputPorts.length > 0 && (
                            <div>
                              <p className="text-[10px] font-medium text-muted-foreground uppercase tracking-wider mb-1">
                                Inputs
                              </p>
                              <div className="flex flex-wrap gap-1">
                                {bp.inputPorts.map((p, i) => (
                                  <Badge
                                    key={i}
                                    variant="secondary"
                                    className="text-[10px]"
                                  >
                                    {p.name}
                                  </Badge>
                                ))}
                              </div>
                            </div>
                          )}
                          {bp.outputPorts.length > 0 && (
                            <div>
                              <p className="text-[10px] font-medium text-muted-foreground uppercase tracking-wider mb-1">
                                Outputs
                              </p>
                              <div className="flex flex-wrap gap-1">
                                {bp.outputPorts.map((p, i) => (
                                  <Badge
                                    key={i}
                                    variant="secondary"
                                    className="text-[10px]"
                                  >
                                    {p.name}
                                  </Badge>
                                ))}
                              </div>
                            </div>
                          )}
                          <div>
                            <p className="text-[10px] font-medium text-muted-foreground uppercase tracking-wider mb-1">
                              Parameters ({bp.paramsSchema.length})
                            </p>
                            <div className="flex flex-wrap gap-1">
                              {bp.paramsSchema.map((p, i) => (
                                <Badge
                                  key={i}
                                  variant="outline"
                                  className="text-[10px] font-mono"
                                >
                                  {p.name}
                                </Badge>
                              ))}
                            </div>
                          </div>
                          {(bp.artifactTypes ?? []).length > 0 && (
                            <div>
                              <p className="text-[10px] font-medium text-muted-foreground uppercase tracking-wider mb-1">
                                Artifacts
                              </p>
                              <div className="flex flex-wrap gap-1">
                                {bp.artifactTypes?.map((artifactType) => (
                                  <Badge
                                    key={artifactType}
                                    variant="outline"
                                    className="text-[10px]"
                                  >
                                    {artifactType}
                                  </Badge>
                                ))}
                              </div>
                            </div>
                          )}
                          {bp.replacementBlueprintKey && (
                            <p className="text-[11px] text-muted-foreground">
                              Replacement:{" "}
                              <span className="font-mono">
                                {bp.replacementBlueprintKey}
                              </span>
                            </p>
                          )}
                          <CodeExamplesSection
                            blueprintKey={bp.blueprintKey}
                            blueprintName={bp.name}
                          />
                          {isDbtEmitting(bp.artifactTypes) && (
                            <DbtMappingSection blueprint={bp} />
                          )}
                        </CardContent>
                      )}
                    </Card>
                );
              })}
            </div>
          </div>
        ))
      )}
    </div>
  );
}

function CodeExamplesSection({
  blueprintKey,
  blueprintName,
}: {
  blueprintKey: string;
  blueprintName: string;
}) {
  const [examples, setExamples] = useState<CodeExample[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [open, setOpen] = useState(false);

  useEffect(() => {
    let cancelled = false;
    api
      .get<CodeExample[]>(`/api/v1/blueprints/${encodeURIComponent(blueprintKey)}/examples`)
      .then((list) => {
        if (!cancelled) setExamples(list ?? []);
      })
      .catch((err) => {
        if (cancelled) return;
        if (err instanceof Error && err.message.includes("410")) {
          setError("Deprecated blueprint — examples hidden.");
          setExamples([]);
        } else {
          setError(err instanceof Error ? err.message : "Failed to load examples");
          setExamples([]);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [blueprintKey]);

  return (
    <div>
      <p className="text-[10px] font-medium text-muted-foreground uppercase tracking-wider mb-1">
        Code Examples
      </p>
      {error ? (
        <p className="text-[11px] text-destructive">{error}</p>
      ) : examples === null ? (
        <p className="text-[11px] text-muted-foreground">Loading…</p>
      ) : examples.length === 0 ? (
        <p className="text-[11px] text-muted-foreground">No examples indexed.</p>
      ) : (
        <>
          <Button
            variant="outline"
            size="sm"
            className="h-7 text-[11px]"
            onClick={() => setOpen(true)}
          >
            View {examples.length} example{examples.length === 1 ? "" : "s"} →
          </Button>
          <CodeViewerDialog
            open={open}
            onOpenChange={setOpen}
            blueprintName={blueprintName}
            examples={examples}
          />
        </>
      )}
    </div>
  );
}

function DbtMappingSection({ blueprint }: { blueprint: Blueprint }) {
  const types = blueprint.artifactTypes ?? [];
  const isSnapshot = types.includes("dbt_snapshot");
  const layer = blueprint.validLayers?.includes("gold")
    ? "marts"
    : blueprint.validLayers?.includes("silver")
    ? "staging/intermediate"
    : "models";
  const pathTemplate = isSnapshot
    ? `dbt_project/snapshots/{domain}/snp__{entity}.sql`
    : `dbt_project/models/${layer}/{source}/{entity}.sql`;
  return (
    <div className="rounded border px-2 py-1.5 text-[11px] bg-muted/20">
      <p className="font-medium">dbt Mapping (dbt)</p>
      <p className="text-muted-foreground">
        Path: <code className="font-mono">{pathTemplate}</code>
      </p>
      <p className="text-muted-foreground">
        Materialization:{" "}
        <code className="font-mono">{isSnapshot ? "snapshot" : "table"}</code> · Compute:
        Spark via <code className="font-mono">dbt-spark</code>
      </p>
    </div>
  );
}
