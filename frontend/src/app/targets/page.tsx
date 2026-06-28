"use client";

import { useEffect, useMemo, useState, useTransition } from "react";
import { useRouter } from "next/navigation";
import { Badge } from "@/components/ui/badge";
import {
  Card,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { useTenant } from "@/contexts/tenant-context";
import { useTenantDomains } from "@/hooks/use-tenant-domains";
import { api } from "@/lib/api";
import { resolveDomainName } from "@/lib/domains";
import type { SystemOfRecord } from "@/types";
import { CreateTargetDialog } from "@/components/sor/create-target-dialog";

export default function TargetsPage() {
  const router = useRouter();
  const { currentTenant } = useTenant();
  const { domainOptions } = useTenantDomains(currentTenant);
  const [targets, setTargets] = useState<SystemOfRecord[]>([]);
  const [isPending, startTransition] = useTransition();
  const [initialLoad, setInitialLoad] = useState(true);

  useEffect(() => {
    if (!currentTenant) return;
    startTransition(async () => {
      try {
        const data = await api.get<SystemOfRecord[]>(
          `/api/v1/tenants/${currentTenant.id}/targets`
        );
        setTargets(data);
      } catch {
        setTargets([]);
      } finally {
        setInitialLoad(false);
      }
    });
  }, [currentTenant]);

  const loading = initialLoad && isPending;
  const domainGroups = useMemo(() => {
    const groups = new Map<
      string,
      { id: string; label: string; targets: SystemOfRecord[] }
    >();

    for (const target of targets) {
      const label = resolveDomainName(target, domainOptions);
      const key = target.domainId ?? `name:${label}`;
      const existing = groups.get(key);
      if (existing) {
        existing.targets.push(target);
        continue;
      }

      groups.set(key, {
        id: key,
        label,
        targets: [target],
      });
    }

    return Array.from(groups.values()).sort((a, b) =>
      a.label.localeCompare(b.label)
    );
  }, [domainOptions, targets]);

  return (
    <div className="space-y-6">
      <div className="flex items-start justify-between">
        <div>
          <h2 className="text-2xl font-bold tracking-tight">Sink Targets</h2>
          <p className="text-muted-foreground">
            Published data targets grouped by domain, mirroring Data Sources.
          </p>
        </div>
        <CreateTargetDialog
          onCreated={(target) => {
            setTargets((prev) => [...prev, target]);
          }}
        />
      </div>

      {loading ? (
        <p className="text-muted-foreground">Loading...</p>
      ) : targets.length === 0 ? (
        <Card>
          <CardHeader>
            <CardTitle>No targets registered</CardTitle>
            <CardDescription>
              Register your first sink target to start publishing data out of pipelines.
            </CardDescription>
          </CardHeader>
        </Card>
      ) : (
        <div className="space-y-6">
          {domainGroups.map((group) => (
            <div key={group.id} className="space-y-3">
              <h3 className="text-sm font-medium text-muted-foreground uppercase tracking-wider">
                {group.label}
              </h3>
              <div className="grid gap-3 md:grid-cols-2">
                {group.targets.map((target) => (
                  <Card
                    key={target.id}
                    className="hover:border-primary/50 transition-colors cursor-pointer"
                    onClick={() => router.push(`/targets/${target.id}`)}
                  >
                    <CardHeader className="pb-2">
                      <div className="flex items-center justify-between">
                        <CardTitle className="text-sm">{target.name}</CardTitle>
                        <div className="flex gap-1">
                          <Badge variant="secondary" className="text-[10px]">
                            {target.connectorCount} connector
                            {target.connectorCount !== 1 ? "s" : ""}
                          </Badge>
                          <Badge variant="outline" className="text-[10px]">
                            {target.datasetCount} dataset
                            {target.datasetCount !== 1 ? "s" : ""}
                          </Badge>
                        </div>
                      </div>
                      <CardDescription className="text-xs">
                        {target.description}
                      </CardDescription>
                    </CardHeader>
                  </Card>
                ))}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
