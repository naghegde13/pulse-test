"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { useAuth } from "@/contexts/auth-context";
import { useTenant } from "@/contexts/tenant-context";
import { api } from "@/lib/api";

type PipelineStats = Record<string, number>;

export default function DashboardPage() {
  const { user, loading: authLoading } = useAuth();
  const { currentTenant } = useTenant();
  const [stats, setStats] = useState<PipelineStats>({});

  useEffect(() => {
    if (!currentTenant) return;
    api
      .get<PipelineStats>(
        `/api/v1/tenants/${currentTenant.id}/pipelines/stats`
      )
      .then(setStats)
      .catch(() => setStats({}));
  }, [currentTenant]);

  if (authLoading) {
    return <div className="text-muted-foreground">Loading...</div>;
  }

  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-2xl font-bold tracking-tight">Dashboard</h2>
        <p className="text-muted-foreground">
          Welcome back, {user?.displayName}
        </p>
      </div>

      <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-4">
        <Link href="/pipelines">
          <Card className="hover:border-primary/50 transition-colors cursor-pointer">
            <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
              <CardTitle className="text-sm font-medium">
                Total Pipelines
              </CardTitle>
            </CardHeader>
            <CardContent>
              <div className="text-2xl font-bold">{stats.total ?? 0}</div>
              <p className="text-xs text-muted-foreground">
                Across all stages
              </p>
            </CardContent>
          </Card>
        </Link>

        <Card>
          <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
            <CardTitle className="text-sm font-medium">In Dev</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold">
              {(stats.ENGINEERING ?? 0) +
                (stats.DEV_DEPLOYED ?? 0) +
                (stats.DEV_VALIDATED ?? 0)}
            </div>
            <p className="text-xs text-muted-foreground">
              Engineering, deployed, or validated in dev
            </p>
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
            <CardTitle className="text-sm font-medium">Published</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold">{stats.PUBLISHED ?? 0}</div>
            <p className="text-xs text-muted-foreground">
              Handed off to enterprise CD
            </p>
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
            <CardTitle className="text-sm font-medium">Drafts</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold">{stats.DRAFT ?? 0}</div>
            <p className="text-xs text-muted-foreground">
              Not yet promoted to engineering
            </p>
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
