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
import { CreateSORDialog } from "@/components/sor/create-sor-dialog";

export default function ProducersPage() {
  const router = useRouter();
  const { currentTenant } = useTenant();
  const { domainOptions } = useTenantDomains(currentTenant);
  const [sors, setSors] = useState<SystemOfRecord[]>([]);
  const [isPending, startTransition] = useTransition();
  const [initialLoad, setInitialLoad] = useState(true);

  useEffect(() => {
    if (!currentTenant) return;
    startTransition(async () => {
      try {
        const data = await api.get<SystemOfRecord[]>(
          `/api/v1/tenants/${currentTenant.id}/sors`
        );
        setSors(data);
      } catch {
        setSors([]);
      } finally {
        setInitialLoad(false);
      }
    });
  }, [currentTenant]);

  const loading = initialLoad && isPending;
  const domainGroups = useMemo(() => {
    const groups = new Map<
      string,
      { id: string; label: string; sors: SystemOfRecord[] }
    >();

    for (const sor of sors) {
      const label = resolveDomainName(sor, domainOptions);
      const key = sor.domainId ?? `name:${label}`;
      const existing = groups.get(key);
      if (existing) {
        existing.sors.push(sor);
        continue;
      }

      groups.set(key, {
        id: key,
        label,
        sors: [sor],
      });
    }

    return Array.from(groups.values()).sort((a, b) =>
      a.label.localeCompare(b.label)
    );
  }, [domainOptions, sors]);

  return (
    <div className="space-y-6">
      <div className="flex items-start justify-between">
        <div>
          <h2 className="text-2xl font-bold tracking-tight">
            Systems of Record
          </h2>
          <p className="text-muted-foreground">
            Business systems registered as data sources, with their connectors and
            datasets
          </p>
        </div>
        <CreateSORDialog
          onCreated={(sor) => {
            setSors((prev) => [...prev, sor]);
          }}
        />
      </div>

      {loading ? (
        <p className="text-muted-foreground">Loading...</p>
      ) : sors.length === 0 ? (
        <Card>
          <CardHeader>
            <CardTitle>No systems registered</CardTitle>
            <CardDescription>
              Register your first System of Record to start building pipelines.
            </CardDescription>
          </CardHeader>
        </Card>
      ) : (
        <div className="space-y-6">
          {domainGroups.map((domainGroup) => (
            <div key={domainGroup.id} className="space-y-3">
              <h3 className="text-sm font-medium text-muted-foreground uppercase tracking-wider">
                {domainGroup.label}
              </h3>
              <div className="grid gap-3 md:grid-cols-2">
                {domainGroup.sors.map((sor) => (
                  <Card
                    key={sor.id}
                    className="hover:border-primary/50 transition-colors cursor-pointer"
                    onClick={() => router.push(`/producers/${sor.id}`)}
                  >
                    <CardHeader className="pb-2">
                      <div className="flex items-center justify-between">
                        <CardTitle className="text-sm">{sor.name}</CardTitle>
                        <div className="flex gap-1">
                          <Badge variant="secondary" className="text-[10px]">
                            {sor.connectorCount} connector
                            {sor.connectorCount !== 1 ? "s" : ""}
                          </Badge>
                          <Badge variant="outline" className="text-[10px]">
                            {sor.datasetCount} dataset
                            {sor.datasetCount !== 1 ? "s" : ""}
                          </Badge>
                        </div>
                      </div>
                      <CardDescription className="text-xs">
                        {sor.description}
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
