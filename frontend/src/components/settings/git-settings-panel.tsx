"use client";

import { useCallback, useEffect, useState } from "react";
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
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { api } from "@/lib/api";
import type { GitRepo, OnboardingStatus } from "@/types";

export function GitSettingsPanel({
  tenantId,
  onChanged,
}: {
  tenantId: string;
  onChanged?: () => void;
}) {
  const [repo, setRepo] = useState<GitRepo | null>(null);
  const [branches, setBranches] = useState<string[]>([]);
  const [loading, setLoading] = useState(true);
  const [rescaffoldError, setRescaffoldError] = useState<string | null>(null);
  const [rescaffoldOk, setRescaffoldOk] = useState(false);
  const [reloadToken, setReloadToken] = useState(0);

  const reload = useCallback(() => setReloadToken((n) => n + 1), []);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const s = await api.get<OnboardingStatus>(
          `/api/v1/tenants/${tenantId}/onboarding-status`
        );
        if (!cancelled) setRepo(s.gitRepo ?? null);
      } catch {
        if (!cancelled) setRepo(null);
      } finally {
        if (!cancelled) setLoading(false);
      }
      try {
        const b = await api.get<string[]>(`/api/v1/tenants/${tenantId}/git-repo/branches`);
        if (!cancelled) setBranches(b);
      } catch {
        if (!cancelled) setBranches([]);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [tenantId, reloadToken]);

  const handleBranchChange = async (branch: string) => {
    const updated = await api.put<GitRepo>(
      `/api/v1/tenants/${tenantId}/git-repo/branch`,
      { branch }
    );
    setRepo(updated);
    onChanged?.();
  };

  const handleRescaffold = async () => {
    setRescaffoldError(null);
    setRescaffoldOk(false);
    try {
      await api.post(`/api/v1/tenants/${tenantId}/scaffold`, {});
      setRescaffoldOk(true);
      reload();
      onChanged?.();
    } catch (e) {
      setRescaffoldError(e instanceof Error ? e.message : "Scaffold failed");
    }
  };

  if (loading) {
    return <p className="text-sm text-muted-foreground">Loading tenant repo…</p>;
  }
  if (!repo) {
    return <p className="text-sm text-muted-foreground">No tenant repo.</p>;
  }

  return (
    <Card>
      <CardHeader>
        <div className="flex items-center justify-between flex-wrap gap-2">
          <div>
            <CardTitle className="text-base">Tenant repository</CardTitle>
            <CardDescription>
              The single repo PULSE uses for all generated code in this tenant.
            </CardDescription>
          </div>
          <div className="flex gap-1.5">
            {/* PKT-FINAL-3 (BUG-05): repoType badge removed (only REMOTE exists now). */}
            <Badge variant="outline" className="text-[10px]">{repo.provider}</Badge>
          </div>
        </div>
      </CardHeader>
      <CardContent className="space-y-3">
        <dl className="grid grid-cols-3 gap-x-3 gap-y-2 text-sm">
          <dt className="text-muted-foreground">Repository</dt>
          <dd className="col-span-2 font-mono text-xs break-all">{repo.repoUrl}</dd>

          {repo.localPath && (
            <>
              <dt className="text-muted-foreground">Local path</dt>
              <dd className="col-span-2 font-mono text-xs break-all">{repo.localPath}</dd>
            </>
          )}

          <dt className="text-muted-foreground">Current branch</dt>
          <dd className="col-span-2">
            {branches.length > 0 ? (
              <Select
                value={repo.currentBranch ?? repo.defaultBranch}
                onValueChange={handleBranchChange}
              >
                <SelectTrigger className="h-8 text-xs w-64">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {branches.map((b) => (
                    <SelectItem key={b} value={b}>{b}</SelectItem>
                  ))}
                </SelectContent>
              </Select>
            ) : (
              <span className="font-mono text-xs">
                {repo.currentBranch ?? repo.defaultBranch}
              </span>
            )}
          </dd>

          {repo.lastSyncedAt && (
            <>
              <dt className="text-muted-foreground">Last sync</dt>
              <dd className="col-span-2 text-xs">
                {new Date(repo.lastSyncedAt).toLocaleString()}
              </dd>
            </>
          )}
        </dl>

        <div className="flex items-center gap-2 pt-2">
          <Button variant="outline" size="sm" onClick={handleRescaffold}>
            Re-scaffold
          </Button>
          {rescaffoldOk && (
            <span className="text-xs text-emerald-600">Repo directories refreshed.</span>
          )}
          {rescaffoldError && (
            <span className="text-xs text-destructive">{rescaffoldError}</span>
          )}
        </div>
      </CardContent>

    </Card>
  );
}
