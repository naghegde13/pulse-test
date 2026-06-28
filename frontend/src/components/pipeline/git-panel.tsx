"use client";

import Link from "next/link";
import { useEffect, useState, useTransition } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { useTenant } from "@/contexts/tenant-context";
import { api } from "@/lib/api";
import type {
  DeveloperWorkspace,
  GitRepo,
  PromotionProofReadback,
  PullRequestRecord,
  WorkspaceContext,
} from "@/types";
import {
  PROMOTION_PROOF_STATE_LABELS,
  hasRuntimeEvidence,
} from "@/types";

/** Wire contract for the masked git identity read endpoint. */
interface MaskedGitIdentity {
  id: string;
  provider: string;
  credentialType: string;
  credentialReferenceMasked: string | null;
  githubUsername: string | null;
  status: string;
  verifiedAt: string | null;
}

interface GitPanelProps {
  pipelineId: string;
  versionId: string;
  domainId?: string;
  domainName?: string;
}

export function GitPanel({ versionId }: GitPanelProps) {
  const { currentTenant } = useTenant();
  const [repo, setRepo] = useState<GitRepo | null>(null);
  const [noRepo, setNoRepo] = useState(false);
  const [prs, setPrs] = useState<PullRequestRecord[]>([]);
  const [workspace, setWorkspace] = useState<DeveloperWorkspace | null>(null);
  const [proofState, setProofState] = useState<PromotionProofReadback | null>(null);
  const [gitIdentity, setGitIdentity] = useState<MaskedGitIdentity | null>(null);
  const [workspaceError, setWorkspaceError] = useState<string | null>(null);
  const [startingWorkspace, setStartingWorkspace] = useState(false);
  const [, startTransition] = useTransition();

  useEffect(() => {
    if (!currentTenant) return;
    setWorkspace(null);
    setWorkspaceError(null);
    setProofState(null);
    setGitIdentity(null);
    setPrs([]);
    startTransition(async () => {
      try {
        const [context, proof, identity] = await Promise.all([
          api.get<WorkspaceContext>(
            `/api/v1/versions/${versionId}/workspace-context`
          ),
          api.get<PromotionProofReadback>(
            `/api/v1/versions/${versionId}/promotion-proof-state`
          ).catch(() => null),
          api.get<MaskedGitIdentity>(
            `/api/v1/users/me/git-identity`
          ).catch(() => null),
        ]);
        setProofState(proof);
        setGitIdentity(identity);
        if (context.gitRepo) {
          setRepo(context.gitRepo);
          setWorkspace(context.workspace ?? null);
          setNoRepo(false);
        } else {
          setRepo(null);
          setNoRepo(true);
        }
      } catch {
        setRepo(null);
        setNoRepo(true);
      }
    });
  }, [currentTenant, versionId]);

  useEffect(() => {
    if (!repo) return;
    startTransition(async () => {
      try {
        const data = await api.get<PullRequestRecord[]>(
          `/api/v1/versions/${versionId}/pull-requests`
        );
        setPrs(data);
      } catch {
        setPrs([]);
      }
    });
  }, [repo, versionId]);

  const prStatusColor = (status: string) => {
    switch (status) {
      case "OPEN":
        return "bg-green-500/10 text-green-700 border-green-500/20";
      case "MERGED":
        return "bg-purple-500/10 text-purple-700 border-purple-500/20";
      case "CLOSED":
        return "bg-red-500/10 text-red-700 border-red-500/20";
      default:
        return "";
    }
  };

  const startWorkspace = async () => {
    setStartingWorkspace(true);
    setWorkspaceError(null);
    try {
      const data = await api.post<DeveloperWorkspace>(
        `/api/v1/versions/${versionId}/workspace`,
        { leaseOwner: "pulse-ui", leaseMinutes: 120 }
      );
      setWorkspace(data);
    } catch (err) {
      setWorkspaceError(err instanceof Error ? err.message : "Unable to start workspace");
    } finally {
      setStartingWorkspace(false);
    }
  };

  const archiveWorkspace = async () => {
    if (!workspace) return;
    setStartingWorkspace(true);
    setWorkspaceError(null);
    try {
      const data = await api.post<DeveloperWorkspace>(
        `/api/v1/workspaces/${workspace.id}/archive`,
        {}
      );
      setWorkspace(data);
    } catch (err) {
      setWorkspaceError(err instanceof Error ? err.message : "Unable to archive workspace");
    } finally {
      setStartingWorkspace(false);
    }
  };

  return (
    <Card>
      <CardHeader className="pb-2">
        <div className="flex items-center justify-between gap-3 flex-wrap">
          <div className="flex items-center gap-2 flex-wrap">
            <CardTitle className="text-sm font-medium">Git Repository</CardTitle>
            {repo && (
              <Badge variant="secondary" className="text-[10px]">Tenant Repository</Badge>
            )}
            {repo && (
              <Badge variant="outline" className="text-[10px]">{repo.provider}</Badge>
            )}
            {/* PKT-FINAL-3 (BUG-05): repoType badge + LOCAL warning removed;
                every tenant repo is REMOTE/GITHUB now. */}
          </div>
          {noRepo && (
            <Link
              href="/settings#tenant-readiness"
              className="text-xs text-primary hover:underline"
            >
              Check Tenant Readiness →
            </Link>
          )}
        </div>
      </CardHeader>
      <CardContent className="space-y-3">
        {noRepo ? (
          <div className="text-center py-4 text-muted-foreground space-y-1">
            <p className="text-sm">No tenant repository configured.</p>
            <p className="text-xs">
              Onboard this tenant in{" "}
              <Link href="/settings" className="text-primary hover:underline">
                Settings
              </Link>{" "}
              to store generated code.
            </p>
          </div>
        ) : repo ? (
          <>
            <div className="space-y-1.5 text-sm">
              <div className="flex justify-between gap-4">
                <span className="text-muted-foreground">URL</span>
                <a
                  href={repo.repoUrl}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="text-primary hover:underline font-mono text-xs truncate max-w-[260px]"
                >
                  {repo.repoUrl.replace(/^https?:\/\//, "")}
                </a>
              </div>
              <div className="flex justify-between gap-4">
                <span className="text-muted-foreground">Branch</span>
                <span className="font-mono text-xs">
                  {repo.currentBranch ?? repo.defaultBranch}
                </span>
              </div>
              {repo.lastSyncedAt && (
                <div className="flex justify-between gap-4">
                  <span className="text-muted-foreground">Last sync</span>
                  <span className="text-xs">
                    {new Date(repo.lastSyncedAt).toLocaleString()}
                  </span>
                </div>
              )}
            </div>

            {/* PAT identity status (redacted) */}
            {gitIdentity && (
              <div className="rounded-md border px-3 py-2 text-xs space-y-1.5">
                <div className="flex items-center justify-between gap-3">
                  <span className="text-muted-foreground">PAT Identity</span>
                  <Badge
                    variant="outline"
                    className={`text-[10px] ${
                      gitIdentity.status === "VALID"
                        ? "bg-green-500/10 text-green-700 border-green-500/20"
                        : gitIdentity.status === "PENDING_VALIDATION"
                          ? "bg-yellow-500/10 text-yellow-700 border-yellow-500/20"
                          : "bg-red-500/10 text-red-700 border-red-500/20"
                    }`}
                  >
                    {gitIdentity.status}
                  </Badge>
                </div>
                {gitIdentity.githubUsername && (
                  <div className="flex items-center justify-between gap-3">
                    <span className="text-muted-foreground">GitHub User</span>
                    <span className="font-medium">@{gitIdentity.githubUsername}</span>
                  </div>
                )}
                {gitIdentity.credentialReferenceMasked && (
                  <div className="flex items-center justify-between gap-3">
                    <span className="text-muted-foreground">Secret Ref</span>
                    <span className="font-mono text-muted-foreground truncate max-w-[200px]">
                      {gitIdentity.credentialReferenceMasked}
                    </span>
                  </div>
                )}
              </div>
            )}

            {/* PKT-FINAL-3 (BUG-05): LOCAL non-proof disclaimer removed —
                LOCAL repos no longer exist. */}

            <div className="flex justify-end">
              <div className="flex items-center gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  className="h-7 text-xs"
                  onClick={startWorkspace}
                  disabled={startingWorkspace || workspace?.lifecycleStatus === "ACTIVE"}
                >
                  {workspace?.lifecycleStatus === "ACTIVE"
                    ? "Workspace Active"
                    : startingWorkspace
                      ? "Starting..."
                      : "Start Workspace"}
                </Button>
                <Button asChild variant="ghost" size="sm" className="h-7 text-xs">
                  <Link href="/settings">Manage in Settings →</Link>
                </Button>
              </div>
            </div>

            {workspace && (
              <div className="rounded-md border px-3 py-2 text-xs space-y-1.5">
                <div className="flex items-center justify-between gap-3">
                  <span className="text-muted-foreground">Workspace</span>
                  <Badge variant="outline" className="text-[10px]">
                    {workspace.lifecycleStatus}
                  </Badge>
                </div>
                <div className="flex items-center justify-between gap-3">
                  <span className="text-muted-foreground">Branch</span>
                  <span className="font-mono truncate max-w-[320px]">
                    {workspace.branchName}
                  </span>
                </div>
                <div className="flex items-center justify-between gap-3">
                  <span className="text-muted-foreground">Tree</span>
                  <span className="font-mono">
                    {workspace.workingTreeStatus}
                    {workspace.dirtyFileCount > 0 ? ` (${workspace.dirtyFileCount})` : ""}
                  </span>
                </div>
                {workspace.lifecycleStatus === "ACTIVE" && (
                  <div className="flex justify-end">
                    <Button
                      variant="ghost"
                      size="sm"
                      className="h-6 text-[10px]"
                      onClick={archiveWorkspace}
                      disabled={startingWorkspace}
                    >
                      Archive
                    </Button>
                  </div>
                )}
              </div>
            )}

            {workspaceError && (
              <p className="text-xs text-destructive">{workspaceError}</p>
            )}

            {proofState && proofState.acceptance && (
              <div className="rounded-md border px-3 py-2 text-xs space-y-1.5">
                <div className="flex items-center justify-between gap-3">
                  <span className="text-muted-foreground">Proof State</span>
                  <Badge
                    variant="outline"
                    className={`text-[10px] ${
                      hasRuntimeEvidence(proofState.currentState)
                        ? "bg-green-500/10 text-green-700 border-green-500/20"
                        : "bg-blue-500/10 text-blue-700 border-blue-500/20"
                    }`}
                  >
                    {PROMOTION_PROOF_STATE_LABELS[proofState.currentState]}
                  </Badge>
                </div>
                <div className="flex items-center justify-between gap-3">
                  <span className="text-muted-foreground">Acceptance</span>
                  <span className="font-mono truncate max-w-[220px]">
                    {proofState.acceptance.acceptedCommitSha.slice(0, 8)}
                  </span>
                </div>
                {proofState.staticPackage && !proofState.runtimeProof && (
                  <p className="text-[10px] text-amber-700 mt-1">
                    Static proof only — package built but not yet runtime-proved. Promotion requires runtime evidence from the target environment.
                  </p>
                )}
              </div>
            )}

            {prs.length > 0 && (
              <div className="space-y-1.5 pt-2 border-t">
                <span className="text-xs font-medium text-muted-foreground uppercase">
                  Pull Requests
                </span>
                {prs.map((pr) => (
                  <div
                    key={pr.id}
                    className="flex items-center justify-between rounded-md border px-3 py-2 text-xs"
                  >
                    <div className="flex items-center gap-2 min-w-0">
                      <Badge
                        variant="outline"
                        className={`text-[10px] shrink-0 ${prStatusColor(pr.status)}`}
                      >
                        {pr.status}
                      </Badge>
                      <span className="font-mono shrink-0">#{pr.prNumber}</span>
                      <span className="truncate">{pr.title}</span>
                    </div>
                    <div className="flex items-center gap-2 shrink-0 ml-2">
                      <span className="text-muted-foreground font-mono">
                        {pr.sourceBranch}
                      </span>
                      {pr.prUrl && (
                        <a
                          href={pr.prUrl}
                          target="_blank"
                          rel="noopener noreferrer"
                          className="text-primary hover:underline"
                        >
                          View
                        </a>
                      )}
                    </div>
                  </div>
                ))}
              </div>
            )}

            {prs.length === 0 && (
              <p className="text-xs text-muted-foreground pt-2 border-t">
                No pull requests for this version yet.
              </p>
            )}
          </>
        ) : null}
      </CardContent>
    </Card>
  );
}
