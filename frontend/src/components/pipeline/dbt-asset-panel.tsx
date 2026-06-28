"use client";

import { useEffect, useMemo, useState, useTransition } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { api } from "@/lib/api";
import type { DbtAsset } from "@/types";

interface DbtAssetPanelProps {
  domainId?: string;
  domainName?: string;
}

export function DbtAssetPanel({ domainId, domainName }: DbtAssetPanelProps) {
  const [assets, setAssets] = useState<DbtAsset[]>([]);
  const [refreshOpen, setRefreshOpen] = useState(false);
  const [projectName, setProjectName] = useState("domain_dbt");
  const [manifestJson, setManifestJson] = useState("[]");
  const [refreshError, setRefreshError] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [loading, startTransition] = useTransition();

  useEffect(() => {
    if (!domainId) {
      return;
    }

    startTransition(async () => {
      try {
        const data = await api.get<DbtAsset[]>(`/api/v1/domains/${domainId}/dbt-assets`);
        setAssets(data);
      } catch {
        setAssets([]);
      }
    });
    }, [domainId]);

  const grouped = useMemo(() => {
    const groups = new Map<string, DbtAsset[]>();
    for (const asset of domainId ? assets : []) {
      const key = asset.assetType || "unknown";
      if (!groups.has(key)) groups.set(key, []);
      groups.get(key)!.push(asset);
    }
    return [...groups.entries()];
  }, [assets, domainId]);

  return (
    <Card className="col-span-3">
      <CardHeader className="pb-2">
        <div className="flex items-center justify-between gap-3">
          <div>
            <CardTitle className="text-sm font-medium">dbt Asset Registry</CardTitle>
            <CardDescription>
              {domainId
                ? `Registry-backed reuse candidates for ${domainName || "this domain"}.`
                : "Assign a canonical domain to enable dbt asset reuse suggestions."}
            </CardDescription>
          </div>
          {domainId && (
            <Dialog open={refreshOpen} onOpenChange={setRefreshOpen}>
              <DialogTrigger asChild>
                <Button size="sm" variant="outline" className="h-8 text-xs">
                  Refresh Assets
                </Button>
              </DialogTrigger>
              <DialogContent>
                <DialogHeader>
                  <DialogTitle>Refresh dbt Asset Registry</DialogTitle>
                  <DialogDescription>
                    Paste a normalized manifest asset list to seed reuse candidates
                    for this domain.
                  </DialogDescription>
                </DialogHeader>
                <div className="space-y-3 py-2">
                  <div className="space-y-2">
                    <Label htmlFor="project-name">Project name</Label>
                    <Input
                      id="project-name"
                      value={projectName}
                      onChange={(event) => setProjectName(event.target.value)}
                    />
                  </div>
                  <div className="space-y-2">
                    <Label htmlFor="manifest-assets">Manifest assets JSON</Label>
                    <Textarea
                      id="manifest-assets"
                      rows={12}
                      value={manifestJson}
                      onChange={(event) => setManifestJson(event.target.value)}
                    />
                  </div>
                  {refreshError && (
                    <p className="text-sm text-destructive">{refreshError}</p>
                  )}
                </div>
                <DialogFooter>
                  <Button variant="outline" onClick={() => setRefreshOpen(false)}>
                    Cancel
                  </Button>
                  <Button
                    disabled={refreshing}
                    onClick={async () => {
                      if (!domainId) return;
                      setRefreshing(true);
                      setRefreshError(null);
                      try {
                        const parsed = JSON.parse(manifestJson);
                        const nextAssets = await api.post<DbtAsset[]>(
                          `/api/v1/domains/${domainId}/dbt-assets/refresh`,
                          {
                            projectName,
                            assets: parsed,
                          }
                        );
                        setAssets(nextAssets);
                        setRefreshOpen(false);
                      } catch (error) {
                        setRefreshError(
                          error instanceof Error
                            ? error.message
                            : "Failed to refresh assets"
                        );
                      } finally {
                        setRefreshing(false);
                      }
                    }}
                  >
                    {refreshing ? "Refreshing..." : "Refresh"}
                  </Button>
                </DialogFooter>
              </DialogContent>
            </Dialog>
          )}
        </div>
      </CardHeader>
      <CardContent>
        {!domainId ? (
          <div className="rounded-md border border-dashed px-4 py-5 text-sm text-muted-foreground">
            No canonical domain selected yet, so reuse candidates cannot be loaded.
          </div>
        ) : loading ? (
          <p className="text-sm text-muted-foreground">Loading dbt assets...</p>
        ) : assets.length === 0 ? (
          <div className="rounded-md border border-dashed px-4 py-5 text-sm text-muted-foreground">
            No indexed dbt assets yet. Refresh the domain manifest into the registry to unlock reuse suggestions.
          </div>
        ) : (
          <div className="space-y-4">
            {grouped.map(([assetType, items]) => (
              <div key={assetType} className="space-y-2">
                <div className="flex items-center gap-2">
                  <span className="text-xs font-medium uppercase text-muted-foreground">
                    {assetType}
                  </span>
                  <Badge variant="outline" className="text-[10px]">
                    {items.length}
                  </Badge>
                </div>
                <div className="grid gap-2 md:grid-cols-2">
                  {items.map((asset) => (
                    <div key={asset.id} className="rounded-md border px-3 py-2 text-xs">
                      <div className="flex items-center justify-between gap-2">
                        <span className="font-medium truncate">{asset.assetName}</span>
                        {asset.businessConcept && (
                          <Badge variant="secondary" className="text-[10px]">
                            {asset.businessConcept}
                          </Badge>
                        )}
                      </div>
                      <div className="mt-1 text-muted-foreground font-mono break-all">
                        {asset.path}
                      </div>
                      {(asset.grain || asset.accessLevel) && (
                        <div className="mt-2 flex flex-wrap gap-1">
                          {asset.grain && (
                            <Badge variant="outline" className="text-[10px]">
                              grain: {asset.grain}
                            </Badge>
                          )}
                          {asset.accessLevel && (
                            <Badge variant="outline" className="text-[10px]">
                              {asset.accessLevel}
                            </Badge>
                          )}
                        </div>
                      )}
                    </div>
                  ))}
                </div>
              </div>
            ))}
          </div>
        )}
      </CardContent>
    </Card>
  );
}
