"use client";

import { useCallback, useEffect, useState, useTransition } from "react";
import { useParams, useRouter, useSearchParams } from "next/navigation";
import Link from "next/link";
import { X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import {
  Card,
  CardContent,
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
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { Separator } from "@/components/ui/separator";
import { LifecycleRibbon } from "@/components/pipeline/lifecycle-ribbon";
import { EditPipelineDialog } from "@/components/pipeline/edit-pipeline-dialog";
import { DeletePipelineDialog } from "@/components/pipeline/delete-pipeline-dialog";
import { PlanPreviewDialog } from "@/components/pipeline/plan-preview-dialog";
import { UserStoryDialog } from "@/components/pipeline/user-story-dialog";
import { CompositionPanel } from "@/components/pipeline/composition-panel";
import { type FocusSignal } from "@/components/pipeline/dag-view";
import { CodeEditorPanel } from "@/components/pipeline/code-editor-panel";
import { GitPanel } from "@/components/pipeline/git-panel";
import { DeployPanel } from "@/components/pipeline/deploy-panel";
import { DbtAssetPanel } from "@/components/pipeline/dbt-asset-panel";
import { OrchestrationPanel } from "@/components/pipeline/orchestration-panel";
import { DqScoreBadge } from "@/components/pipeline/dq-score-badge";
import { SchemaConflictPanel } from "@/components/pipeline/schema-conflict-panel";
import { useTenant } from "@/contexts/tenant-context";
import { useTenantDomains } from "@/hooks/use-tenant-domains";
import { useChat } from "@/contexts/chat-context";
import { api } from "@/lib/api";
import { emitCompositionEvent, onCompositionEvent } from "@/lib/composition-events";
import { resolveDomainName } from "@/lib/domains";
import { computeSchemaDiff } from "@/lib/schema-diff";
import {
  STAGE_LABELS,
  type Pipeline,
  type PipelineVersion,
  type PipelineStage,
  type CompositionView,
  type SubPipelineInstance,
  type SchemaColumn,
  type DeployPackage,
  type DeveloperWorkspace,
  type PullRequestRecord,
} from "@/types";

interface GenerationRunRecord {
  id: string;
  status: string;
  triggeredBy: string;
  startedAt?: string;
  completedAt?: string;
  errorMessage?: string;
  metadata?: Record<string, unknown>;
  createdAt: string;
}

interface RunLogEntry {
  id: string;
  at?: string;
  source: string;
  status: string;
  message: string;
}

// Forward-transition action labels per PULSE-managed stage. PULSE is dev-only
// (PKT-FINAL-2 / BUG-2026-05-25-02) — there is no "Promote to Integration / UAT
// / Production" button because PULSE does not own those deployments. The
// terminal PULSE step is PUBLISHED; downstream promotion belongs to enterprise
// CI/CD.
const FORWARD_ACTION: Record<PipelineStage, { label: string; targetStage: PipelineStage } | null> = {
  ENGINEERING: { label: "Deploy to Dev", targetStage: "DEV_DEPLOYED" },
  DEV_DEPLOYED: { label: "Mark Validated", targetStage: "DEV_VALIDATED" },
  DEV_VALIDATED: { label: "Advance proof state", targetStage: "PUBLISHED" },
  PUBLISHED: null,
};

const REGRESSION_ACTION: Record<PipelineStage, { label: string; targetStage: PipelineStage } | null> = {
  ENGINEERING: null,
  DEV_DEPLOYED: { label: "Back to Engineering", targetStage: "ENGINEERING" },
  DEV_VALIDATED: { label: "Back to Engineering", targetStage: "ENGINEERING" },
  PUBLISHED: null,
};

// Terminal status displayed after PUBLISHED. Once PULSE has handed off the
// artifact there is no further per-environment status PULSE can claim.
const EXTERNAL_STATUS_LABEL: Partial<Record<PipelineStage, string>> = {
  PUBLISHED: "Handed off to enterprise CD",
};

export default function PipelineDetailPage() {
  const params = useParams();
  const searchParams = useSearchParams();
  const pipelineId = params.pipelineId as string;
  const { currentTenant } = useTenant();
  const [pipeline, setPipeline] = useState<Pipeline | null>(null);
  const [versions, setVersions] = useState<PipelineVersion[]>([]);
  const [selectedVersion, setSelectedVersion] = useState<PipelineVersion | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [transitioning, setTransitioning] = useState(false);
  const { setPipelineId: setChatPipelineId, open: openChat } = useChat();
  const [planPreview, setPlanPreview] = useState<{
    title: string;
    targetStage: PipelineStage;
    label: string;
  } | null>(null);
  const [isPending, startTransition] = useTransition();
  const [initialLoad, setInitialLoad] = useState(true);

  useEffect(() => {
    setChatPipelineId(pipelineId);
    return () => setChatPipelineId(null);
  }, [pipelineId, setChatPipelineId]);

  useEffect(() => {
    if (searchParams.get("chat") === "1") {
      openChat(pipelineId);
    }
  }, [openChat, pipelineId, searchParams]);

  useEffect(() => {
    if (!currentTenant || !pipelineId) return;
    startTransition(async () => {
      try {
        const pipelineData = await api.get<Pipeline>(
          `/api/v1/tenants/${currentTenant.id}/pipelines/${pipelineId}`
        );
        setPipeline(pipelineData);

        let versionsData: PipelineVersion[] = [];
        try {
          versionsData = await api.get<PipelineVersion[]>(
            `/api/v1/tenants/${currentTenant.id}/pipelines/${pipelineId}/versions`
          );
        } catch {
          // Backend may not have versions endpoint yet -- degrade gracefully
        }

        setVersions(versionsData);
        if (versionsData.length > 0) {
          const active = versionsData.find(
            (v) => v.id === pipelineData.activeVersionId
          );
          setSelectedVersion(active ?? versionsData[0]);
        }
      } catch (err) {
        setError(
          err instanceof Error ? err.message : "Failed to load pipeline"
        );
      } finally {
        setInitialLoad(false);
      }
    });
  }, [currentTenant, pipelineId]);

  const handleTransition = async (targetStage: PipelineStage) => {
    if (!currentTenant || !pipeline || !selectedVersion) return;
    setTransitioning(true);
    setError(null);
    try {
      const updated = await api.post<PipelineVersion>(
        `/api/v1/tenants/${currentTenant.id}/pipelines/${pipeline.id}/versions/${selectedVersion.id}/transition`,
        { targetStage }
      );
      setSelectedVersion(updated);
      setVersions((prev) =>
        prev.map((v) => (v.id === updated.id ? updated : v))
      );
    } catch (err) {
      setError(
        err instanceof Error ? err.message : "Failed to transition"
      );
    } finally {
      setTransitioning(false);
    }
  };

  const handleVersionCreated = (version: PipelineVersion) => {
    setVersions((prev) => [version, ...prev]);
    setSelectedVersion(version);
  };

  const loading = initialLoad && isPending;

  if (loading) {
    return <p className="text-muted-foreground">Loading pipeline...</p>;
  }

  if (error && !pipeline) {
    return (
      <div className="space-y-4">
        <p className="text-destructive">{error}</p>
        <Button variant="outline" asChild>
          <Link href="/pipelines">Back to pipelines</Link>
        </Button>
      </div>
    );
  }

  if (!pipeline) return null;

  return (
    <PipelineContent
      pipeline={pipeline}
      setPipeline={setPipeline}
      versions={versions}
      selectedVersion={selectedVersion}
      setSelectedVersion={setSelectedVersion}
      error={error}
      setError={setError}
      transitioning={transitioning}
      planPreview={planPreview}
      setPlanPreview={setPlanPreview}
      handleTransition={handleTransition}
      handleVersionCreated={handleVersionCreated}
    />
  );
}

interface PipelineContentProps {
  pipeline: Pipeline;
  setPipeline: (p: Pipeline) => void;
  versions: PipelineVersion[];
  selectedVersion: PipelineVersion | null;
  setSelectedVersion: (v: PipelineVersion) => void;
  error: string | null;
  setError: (e: string | null) => void;
  transitioning: boolean;
  planPreview: { title: string; targetStage: PipelineStage; label: string } | null;
  setPlanPreview: (p: { title: string; targetStage: PipelineStage; label: string } | null) => void;
  handleTransition: (stage: PipelineStage) => Promise<void>;
  handleVersionCreated: (v: PipelineVersion) => void;
}

function PipelineContent({
  pipeline,
  setPipeline,
  versions,
  selectedVersion,
  setSelectedVersion,
  error,
  setError,
  transitioning,
  planPreview,
  setPlanPreview,
  handleTransition,
  handleVersionCreated,
}: PipelineContentProps) {
  const { currentTenant } = useTenant();
  const { domainOptions } = useTenantDomains(currentTenant);
  const { close: closeChat, setRightRegionContent } = useChat();
  const router = useRouter();
  const [storyDialogOpen, setStoryDialogOpen] = useState(false);
  const [workspaceComposition, setWorkspaceComposition] = useState<CompositionView | null>(null);
  const [selectedWorkspaceInstanceId, setSelectedWorkspaceInstanceId] = useState<string | null>(null);
  // LCT-046: split selection from inspector open. selectedWorkspaceInstanceId
  // tracks the highlighted node; inspectorInstanceId tracks the open panel.
  // Single-click selects; double-click or "Inspect" opens the panel; closing
  // the panel clears inspectorInstanceId WITHOUT clearing selection.
  const [inspectorInstanceId, setInspectorInstanceId] = useState<string | null>(null);
  // LCT-013: focus signal so the Outline can pan/center the DAG.
  const [focusSignal, setFocusSignal] = useState<FocusSignal | null>(null);
  const [logsOpen, setLogsOpen] = useState(true);
  const [savingWorkspace, setSavingWorkspace] = useState(false);
  const [actionStatus, setActionStatus] = useState<{
    action: "generate" | "package" | "git" | "save";
    state: "running" | "success" | "error";
    message: string;
  } | null>(null);
  const [runLogs, setRunLogs] = useState<RunLogEntry[]>([]);

  const hasVersions = selectedVersion !== null;
  const currentStage = selectedVersion?.lifecycleStage;
  const forwardAction = currentStage ? FORWARD_ACTION[currentStage] : null;
  const regressionAction = currentStage ? REGRESSION_ACTION[currentStage] : null;
  const isActiveVersion = selectedVersion?.id === pipeline.activeVersionId;
  const existingEngineering = versions.find(
    (v) => v.lifecycleStage === "ENGINEERING"
  );
  // PULSE-managed terminal stage. Once published the artifact is handed off to
  // enterprise CD; any further change requires a new revision back in
  // ENGINEERING.
  const isPromotedStage = currentStage === "PUBLISHED";
  const showNewRevisionButton = hasVersions && isPromotedStage;
  const selectedWorkspaceInstance =
    workspaceComposition?.instances.find((inst) => inst.id === selectedWorkspaceInstanceId)
    ?? null;
  const inspectorInstance =
    workspaceComposition?.instances.find((inst) => inst.id === inspectorInstanceId)
    ?? null;
  const actionRunning = actionStatus?.state === "running";

  const refreshRunLogs = useCallback(async () => {
    if (!selectedVersion) {
      setRunLogs([]);
      return;
    }
    const [generations, packages, prs, context] = await Promise.all([
      api.get<GenerationRunRecord[]>(
        `/api/v1/versions/${selectedVersion.id}/generations`
      ).catch(() => []),
      api.get<DeployPackage[]>(
        `/api/v1/versions/${selectedVersion.id}/packages`
      ).catch(() => []),
      api.get<PullRequestRecord[]>(
        `/api/v1/versions/${selectedVersion.id}/pull-requests`
      ).catch(() => []),
      api.get<{ workspace?: DeveloperWorkspace | null }>(
        `/api/v1/versions/${selectedVersion.id}/workspace-context`
      ).catch(() => null),
    ]);

    const entries: RunLogEntry[] = [
      ...generations.map((run) => ({
        id: `generation-${run.id}`,
        at: run.completedAt ?? run.startedAt ?? run.createdAt,
        source: "codegen",
        status: run.status,
        message: run.errorMessage
          ? `Generation ${run.id.slice(-6)} failed: ${run.errorMessage}`
          : `Generation ${run.id.slice(-6)} ${run.status.toLowerCase()}`,
      })),
      ...packages.map((pkg) => ({
        id: `package-${pkg.id}`,
        at: pkg.builtAt ?? pkg.createdAt,
        source: "package",
        status: pkg.buildStatus,
        message: pkg.buildLog || `Package ${pkg.id.slice(-6)} ${pkg.buildStatus.toLowerCase()}`,
      })),
      ...prs.map((pr) => ({
        id: `pr-${pr.id}`,
        at: pr.createdAt,
        source: "git",
        status: pr.status,
        message: `PR #${pr.prNumber} ${pr.status.toLowerCase()} from ${pr.sourceBranch}`,
      })),
    ];

    if (context?.workspace) {
      entries.push({
        id: `workspace-${context.workspace.id}`,
        at: context.workspace.leaseExpiresAt,
        source: "git",
        status: context.workspace.workingTreeStatus,
        message: `Workspace ${context.workspace.branchName} ${context.workspace.workingTreeStatus}`,
      });
    }

    setRunLogs(entries
      .sort((a, b) => new Date(b.at ?? 0).getTime() - new Date(a.at ?? 0).getTime())
      .slice(0, 24));
  }, [selectedVersion]);

  useEffect(() => {
    void refreshRunLogs();
    return onCompositionEvent(() => {
      void refreshRunLogs();
    });
  }, [refreshRunLogs]);

  useEffect(() => {
    if (!selectedVersion || !inspectorInstance) {
      setRightRegionContent(null);
      return;
    }
    closeChat();
    setRightRegionContent(
      <WorkspaceStepInspector
        versionId={selectedVersion.id}
        instance={inspectorInstance}
        composition={workspaceComposition}
        onClose={() => {
          setInspectorInstanceId(null);
          setRightRegionContent(null);
        }}
      />
    );
    return () => setRightRegionContent(null);
  }, [
    closeChat,
    selectedVersion,
    inspectorInstance,
    workspaceComposition,
    setRightRegionContent,
  ]);

  const handleGenerateFromActionBar = async () => {
    if (!currentTenant || !selectedVersion) return;
    setActionStatus({ action: "generate", state: "running", message: "Generating code" });
    setError(null);
    try {
      const run = await api.post<GenerationRunRecord>(
        `/api/v1/versions/${selectedVersion.id}/generate`,
        {
          pipelineId: pipeline.id,
          tenantId: currentTenant.id,
          userId: "current-user",
        }
      );
      setActionStatus({
        action: "generate",
        state: run.status === "COMPLETED" ? "success" : "error",
        message: run.errorMessage || `Generation ${run.status.toLowerCase()}`,
      });
      await refreshRunLogs();
    } catch (err) {
      const message = err instanceof Error ? err.message : "Generate failed";
      setActionStatus({ action: "generate", state: "error", message });
      setError(message);
      await refreshRunLogs();
    }
  };

  const handlePackageFromActionBar = async () => {
    if (!currentTenant || !selectedVersion) return;
    setActionStatus({ action: "package", state: "running", message: "Building package" });
    setError(null);
    try {
      const pkg = await api.post<DeployPackage>(
        `/api/v1/versions/${selectedVersion.id}/packages`,
        {
          pipelineId: pipeline.id,
          tenantId: currentTenant.id,
          userId: "current-user",
          packageType: "ARTIFACT_BUNDLE",
        }
      );
      setActionStatus({
        action: "package",
        state: pkg.buildStatus === "COMPLETED" ? "success" : "error",
        message: pkg.buildLog || `Package ${pkg.buildStatus.toLowerCase()}`,
      });
      await refreshRunLogs();
    } catch (err) {
      const message = err instanceof Error ? err.message : "Package failed";
      setActionStatus({ action: "package", state: "error", message });
      setError(message);
      await refreshRunLogs();
    }
  };

  const handleGitFromActionBar = async () => {
    if (!selectedVersion) return;
    setActionStatus({ action: "git", state: "running", message: "Preparing workspace" });
    setError(null);
    try {
      const context = await api.get<{ workspace?: DeveloperWorkspace | null }>(
        `/api/v1/versions/${selectedVersion.id}/workspace-context`
      );
      let workspace = context.workspace;
      if (!workspace || workspace.lifecycleStatus !== "ACTIVE") {
        workspace = await api.post<DeveloperWorkspace>(
          `/api/v1/versions/${selectedVersion.id}/workspace`,
          { leaseOwner: "pulse-ui", leaseMinutes: 120 }
        );
      }

      setActionStatus({ action: "git", state: "running", message: "Materializing workspace code" });
      const generated = await api.post<{
        workspaceId: string;
        generationRunId: string;
        materializedFileCount: number;
        workspace: DeveloperWorkspace;
      }>(`/api/v1/workspaces/${workspace.id}/generate`, {});
      workspace = generated.workspace;

      setActionStatus({ action: "git", state: "running", message: "Committing workspace" });
      workspace = await api.post<DeveloperWorkspace>(
        `/api/v1/workspaces/${workspace.id}/commit`,
        { message: `pulse: update ${pipeline.name} rev ${selectedVersion.revision}` }
      );

      setActionStatus({ action: "git", state: "running", message: "Pushing branch" });
      workspace = await api.post<DeveloperWorkspace>(
        `/api/v1/workspaces/${workspace.id}/push`,
        {}
      );

      setActionStatus({ action: "git", state: "running", message: "Opening pull request" });
      const pr = await api.post<PullRequestRecord>(
        `/api/v1/workspaces/${workspace.id}/pull-request`,
        {
          title: `PULSE ${pipeline.name} rev ${selectedVersion.revision}`,
          body: `Generated from PULSE pipeline ${pipeline.id}, version ${selectedVersion.id}.`,
          targetBranch: workspace.baseBranch || "main",
        }
      );

      setActionStatus({
        action: "git",
        state: "success",
        message: `PR #${pr.prNumber} ${pr.status.toLowerCase()} on ${workspace.branchName}`,
      });
      await refreshRunLogs();
    } catch (err) {
      const message = err instanceof Error ? err.message : "Git operation failed";
      setActionStatus({ action: "git", state: "error", message });
      setError(message);
      await refreshRunLogs();
    }
  };

  const handleSaveWorkspace = async () => {
    if (!selectedVersion) return;
    setSavingWorkspace(true);
    setActionStatus({ action: "save", state: "running", message: "Refreshing composition" });
    setError(null);
    try {
      const composition = await api.get<CompositionView>(
        `/api/v1/versions/${selectedVersion.id}/composition`
      );
      setWorkspaceComposition(composition);
      emitCompositionEvent({ type: "composition_changed" });
      setActionStatus({ action: "save", state: "success", message: "Composition refreshed" });
      await refreshRunLogs();
    } catch (err) {
      const message = err instanceof Error ? err.message : "Failed to sync workspace";
      setActionStatus({ action: "save", state: "error", message });
      setError(message);
    } finally {
      setSavingWorkspace(false);
    }
  };

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <div className="flex items-center gap-2 mb-1">
            <Link
              href="/pipelines"
              className="text-xs text-muted-foreground hover:text-foreground"
            >
              Pipelines
            </Link>
            <span className="text-xs text-muted-foreground">/</span>
            <span className="text-xs">{pipeline.name}</span>
          </div>
          <h2 className="text-2xl font-bold tracking-tight">
            {pipeline.name}
          </h2>
          <p className="text-muted-foreground">
            {pipeline.description || "No description"}
          </p>
        </div>
        <div className="flex items-center gap-2">
          {selectedVersion && (
            <DqScoreBadge versionId={selectedVersion.id} />
          )}
          {selectedVersion && (
            <Button variant="outline" onClick={() => setStoryDialogOpen(true)}>
              Generate Story
            </Button>
          )}
          <EditPipelineDialog pipeline={pipeline} onUpdated={setPipeline} />
          <DeletePipelineDialog
            pipeline={pipeline}
            onDeleted={() => router.push("/pipelines")}
          />
          {showNewRevisionButton && selectedVersion && (
            existingEngineering ? (
              <Button
                variant="outline"
                onClick={() => setSelectedVersion(existingEngineering)}
              >
                View Rev #{existingEngineering.revision} (in engineering)
              </Button>
            ) : currentTenant ? (
              <CreateRevisionDialog
                tenantId={currentTenant.id}
                pipelineId={pipeline.id}
                onCreated={handleVersionCreated}
              />
            ) : null
          )}
          {regressionAction && (
            <Button
              variant="outline"
              onClick={() =>
                setPlanPreview({
                  title: regressionAction.label,
                  targetStage: regressionAction.targetStage,
                  label: regressionAction.label,
                })
              }
              disabled={transitioning}
            >
              {regressionAction.label}
            </Button>
          )}
          {forwardAction ? (
            <Button
              onClick={() =>
                setPlanPreview({
                  title: forwardAction.label,
                  targetStage: forwardAction.targetStage,
                  label: forwardAction.label,
                })
              }
              disabled={transitioning}
            >
              {forwardAction.label}
            </Button>
          ) : currentStage && EXTERNAL_STATUS_LABEL[currentStage] ? (
            <Badge variant="outline" className="text-xs px-3 py-1.5">
              {EXTERNAL_STATUS_LABEL[currentStage]}
            </Badge>
          ) : null}
        </div>
      </div>

      {error && <p className="text-sm text-destructive">{error}</p>}

      {/* Version selector */}
      {hasVersions && selectedVersion && (
        <div className="flex items-center gap-3">
          <Select
            value={selectedVersion.id}
            onValueChange={(id) => {
              const v = versions.find((v) => v.id === id);
              if (v) setSelectedVersion(v);
            }}
          >
            <SelectTrigger className="w-[280px] h-8 text-xs">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {versions.map((v) => (
                <SelectItem key={v.id} value={v.id} className="text-xs">
                  <span className="font-mono">Rev #{v.revision}</span>
                  <span className="ml-2 text-muted-foreground">
                    {STAGE_LABELS[v.lifecycleStage]}
                  </span>
                  {v.id === pipeline.activeVersionId && (
                    <span className="ml-1 text-primary">(active)</span>
                  )}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          {isActiveVersion && (
            <Badge variant="default" className="text-[10px]">
              Active
            </Badge>
          )}
        </div>
      )}

      {currentStage && <LifecycleRibbon currentStage={currentStage} />}

      <Separator />

      {selectedVersion && (
        <section
          className="overflow-hidden rounded-md border bg-background"
          data-testid="pipeline-workspace-shell"
        >
          <div className="flex flex-wrap items-center justify-between gap-3 border-b px-3 py-2">
            <div className="min-w-0">
              <div className="flex items-center gap-2 text-xs text-muted-foreground">
                <span>{resolveDomainName(pipeline, domainOptions)}</span>
                <span>/</span>
                <span className="font-mono">Rev #{selectedVersion.revision}</span>
                <Badge variant="secondary" className="text-[10px]">
                  {STAGE_LABELS[selectedVersion.lifecycleStage]}
                </Badge>
              </div>
              <h3 className="truncate text-sm font-medium">{pipeline.name}</h3>
            </div>
            <div className="flex flex-wrap items-center gap-2">
              <Button
                size="sm"
                variant="outline"
                onClick={handleGenerateFromActionBar}
                disabled={actionRunning}
              >
                {actionStatus?.action === "generate" && actionRunning ? "Generating" : "Generate"}
              </Button>
              <Button
                size="sm"
                variant="outline"
                onClick={handlePackageFromActionBar}
                disabled={actionRunning}
              >
                {actionStatus?.action === "package" && actionRunning ? "Packaging" : "Package"}
              </Button>
              <Button
                size="sm"
                variant="outline"
                onClick={handleGitFromActionBar}
                disabled={actionRunning}
              >
                {actionStatus?.action === "git" && actionRunning ? "Running Git" : "Git Branch Commit PR"}
              </Button>
              <Button
                size="sm"
                variant="outline"
                onClick={handleSaveWorkspace}
                disabled={savingWorkspace || actionRunning}
              >
                {savingWorkspace ? "Saving" : "Save"}
              </Button>
              <Button size="sm" variant="ghost" onClick={() => setLogsOpen((open) => !open)}>
                Run Logs
              </Button>
            </div>
          </div>
          {actionStatus && (
            <div
              className={`border-b px-3 py-2 text-xs ${
                actionStatus.state === "error"
                  ? "bg-destructive/10 text-destructive"
                  : actionStatus.state === "success"
                    ? "bg-green-500/10 text-green-700"
                    : "bg-muted/40 text-muted-foreground"
              }`}
            >
              <span className="font-medium capitalize">{actionStatus.action}</span>
              <span className="ml-2">{actionStatus.message}</span>
            </div>
          )}

          <div
            className="grid max-h-[72vh] min-h-[640px] grid-cols-1 overflow-y-scroll lg:grid-cols-[220px_minmax(0,1fr)]"
            style={{ scrollbarGutter: "stable" }}
          >
            <PipelineOutline
              composition={workspaceComposition}
              selectedInstanceId={selectedWorkspaceInstance?.id ?? null}
              onSelect={(id) => {
                setSelectedWorkspaceInstanceId(id);
                // LCT-013: pan/center the DAG on the clicked outline node.
                setFocusSignal({ instanceId: id, nonce: Date.now() });
              }}
            />
            <div className="min-h-[520px] border-y lg:border-x lg:border-y-0">
              <CompositionPanel
                pipelineId={pipeline.id}
                versionId={selectedVersion.id}
                editable={currentStage === "ENGINEERING"}
                domainId={pipeline.domainId}
                surface="workspace"
                selectedInstanceId={selectedWorkspaceInstance?.id ?? null}
                onSelectedInstanceChange={setSelectedWorkspaceInstanceId}
                onInstanceInspect={(id) => setInspectorInstanceId(id)}
                focusSignal={focusSignal}
                onCompositionLoaded={(composition) => {
                  setWorkspaceComposition(composition);
                  setSelectedWorkspaceInstanceId((current) =>
                    current && composition.instances.some((inst) => inst.id === current)
                      ? current
                      : composition.instances[0]?.id ?? null
                  );
                }}
              />
            </div>
          </div>

          <RunLogDrawer open={logsOpen} logs={runLogs} />
        </section>
      )}

      <div className="space-y-6">
        <div className="grid gap-6 xl:grid-cols-2">
          <Card>
            <CardHeader className="pb-2">
              <CardTitle className="text-sm font-medium">Details</CardTitle>
            </CardHeader>
            <CardContent className="space-y-2 text-sm">
              <DetailRows
                pipeline={pipeline}
                selectedVersion={selectedVersion}
                domainName={resolveDomainName(pipeline, domainOptions)}
              />
            </CardContent>
          </Card>

          {selectedVersion && (
            <div id="git-panel">
            <GitPanel
              pipelineId={pipeline.id}
              versionId={selectedVersion.id}
              domainId={pipeline.domainId}
              domainName={resolveDomainName(pipeline, domainOptions)}
            />
            </div>
          )}
        </div>
      </div>

      {/* Code Editor */}
      {selectedVersion && (
        <div id="codegen-panel">
        <CodeEditorPanel
          pipelineId={pipeline.id}
          versionId={selectedVersion.id}
          editable={currentStage === "ENGINEERING"}
        />
        </div>
      )}

      {selectedVersion && (
        <OrchestrationPanel
          tenantId={currentTenant?.id || pipeline.tenantId}
          pipelineId={pipeline.id}
          versionId={selectedVersion.id}
          editable={currentStage === "ENGINEERING"}
          selectedVersion={selectedVersion}
        />
      )}

      <DbtAssetPanel
        domainId={pipeline.domainId}
        domainName={resolveDomainName(pipeline, domainOptions)}
      />

      {/* Build & Deploy */}
      {selectedVersion && (
        <div id="deploy-panel">
        <DeployPanel
          pipelineId={pipeline.id}
          versionId={selectedVersion.id}
          editable={currentStage === "ENGINEERING"}
        />
        </div>
      )}

      {/* Version history */}
      {versions.length > 1 && (
        <>
          <Separator />
          <div>
            <h3 className="text-sm font-medium mb-3">Version History</h3>
            <div className="space-y-2">
              {versions.map((v) => (
                <div
                  key={v.id}
                  className={`flex items-center justify-between rounded-md border px-4 py-2 text-sm cursor-pointer transition-colors ${
                    v.id === selectedVersion?.id
                      ? "border-primary bg-primary/5"
                      : "hover:bg-muted/50"
                  }`}
                  onClick={() => setSelectedVersion(v)}
                >
                  <div className="flex items-center gap-3">
                    <span className="font-mono font-medium">Rev #{v.revision}</span>
                    <Badge
                      variant={
                        v.lifecycleStage === "PUBLISHED"
                          ? "default"
                          : "secondary"
                      }
                      className="text-[10px]"
                    >
                      {STAGE_LABELS[v.lifecycleStage]}
                    </Badge>
                    {v.id === pipeline.activeVersionId && (
                      <Badge variant="outline" className="text-[10px]">
                        active
                      </Badge>
                    )}
                  </div>
                  <div className="flex items-center gap-3 text-xs text-muted-foreground">
                    <span className="max-w-[200px] truncate">
                      {v.changeSummary}
                    </span>
                    <span>{new Date(v.createdAt).toLocaleDateString()}</span>
                  </div>
                </div>
              ))}
            </div>
          </div>
        </>
      )}

      {/* Plan Preview Dialog */}
      {planPreview && selectedVersion && (
        <PlanPreviewDialog
          open={!!planPreview}
          onOpenChange={(open) => {
            if (!open) setPlanPreview(null);
          }}
          title={planPreview.title}
          description={`Review the commands that will be executed for Rev #${selectedVersion.revision}.`}
          actions={[
            {
              type: "pipeline.transition",
              description: `${STAGE_LABELS[selectedVersion.lifecycleStage]} → ${STAGE_LABELS[planPreview.targetStage]}`,
            },
          ]}
          onApply={async () => {
            await handleTransition(planPreview.targetStage);
            setPlanPreview(null);
          }}
          applyLabel={planPreview.label}
        />
      )}

      {/* User Story Dialog */}
      <UserStoryDialog
        pipelineId={pipeline.id}
        open={storyDialogOpen}
        onOpenChange={setStoryDialogOpen}
      />
    </div>
  );
}

function DetailRows({
  pipeline,
  selectedVersion,
  domainName,
}: {
  pipeline: Pipeline;
  selectedVersion: PipelineVersion | null;
  domainName: string;
}) {
  return (
    <>
      <div className="flex justify-between">
        <span className="text-muted-foreground">Domain</span>
        <span>{domainName}</span>
      </div>
      {selectedVersion ? (
        <>
          <div className="flex justify-between">
            <span className="text-muted-foreground">Stage</span>
            <Badge variant="secondary" className="text-xs">
              {STAGE_LABELS[selectedVersion.lifecycleStage]}
            </Badge>
          </div>
          <div className="flex justify-between">
            <span className="text-muted-foreground">Revision</span>
            <span className="font-mono">#{selectedVersion.revision}</span>
          </div>
          {selectedVersion.commitHash && (
            <div className="flex justify-between">
              <span className="text-muted-foreground">Commit</span>
              <span className="font-mono text-xs">
                {selectedVersion.commitHash.substring(0, 7)}
              </span>
            </div>
          )}
          {selectedVersion.changeSummary && (
            <div className="flex justify-between">
              <span className="text-muted-foreground">Changes</span>
              <span className="max-w-[180px] truncate text-right">
                {selectedVersion.changeSummary}
              </span>
            </div>
          )}
          <div className="flex justify-between">
            <span className="text-muted-foreground">Updated</span>
            <span>{new Date(selectedVersion.updatedAt).toLocaleDateString()}</span>
          </div>
        </>
      ) : (
        <>
          <div className="flex justify-between">
            <span className="text-muted-foreground">Created</span>
            <span>{new Date(pipeline.createdAt).toLocaleDateString()}</span>
          </div>
          <div className="flex justify-between">
            <span className="text-muted-foreground">Updated</span>
            <span>{new Date(pipeline.updatedAt).toLocaleDateString()}</span>
          </div>
        </>
      )}
    </>
  );
}

function PipelineOutline({
  composition,
  selectedInstanceId,
  onSelect,
}: {
  composition: CompositionView | null;
  selectedInstanceId: string | null;
  onSelect: (instanceId: string) => void;
}) {
  const instances = composition?.instances ?? [];
  const wirings = composition?.wirings ?? [];
  return (
    <aside className="flex min-h-0 flex-col gap-3 bg-muted/25 p-3">
      <div>
        <h3 className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
          Outline
        </h3>
        <p className="text-[11px] text-muted-foreground">
          {instances.length} steps, {wirings.length} wires
        </p>
      </div>
      <div className="min-h-0 flex-1 space-y-1 overflow-y-auto">
        {instances.length === 0 ? (
          <p className="text-xs text-muted-foreground">No steps yet.</p>
        ) : (
          instances.map((instance) => (
            <button
              key={instance.id}
              type="button"
              onClick={() => onSelect(instance.id)}
              className={`w-full rounded-md border px-2 py-2 text-left text-xs transition-colors ${
                selectedInstanceId === instance.id
                  ? "border-primary bg-primary/10"
                  : "bg-background hover:bg-muted"
              }`}
            >
              <span className="block truncate font-medium">{instance.name}</span>
              <span className="block truncate font-mono text-[10px] text-muted-foreground">
                {instance.blueprintKey ?? instance.blueprintId}
              </span>
            </button>
          ))
        )}
      </div>
    </aside>
  );
}

function WorkspaceStepInspector({
  versionId,
  instance,
  composition,
  onClose,
}: {
  versionId: string;
  instance: SubPipelineInstance | null;
  composition: CompositionView | null;
  onClose?: () => void;
}) {
  const [tab, setTab] = useState<"config" | "schema" | "validation" | "observability">("config");
  const schema = schemaStats(instance, composition);
  const params = Object.entries(instance?.params ?? {});
  return (
    <aside className="flex h-full min-h-0 flex-col bg-background p-3">
      <div className="flex items-start justify-between gap-3 border-b pb-3">
        <div className="min-w-0">
          <h3 className="truncate text-sm font-medium">
            {instance?.name ?? "Step inspector"}
          </h3>
          <p className="truncate font-mono text-[11px] text-muted-foreground">
            {instance?.blueprintKey ?? "Select a canvas node"}
          </p>
        </div>
        {onClose && (
          <Button
            type="button"
            variant="ghost"
            size="sm"
            className="h-7 w-7 shrink-0 p-0"
            onClick={onClose}
            aria-label="Close step inspector"
          >
            <X className="h-4 w-4" />
          </Button>
        )}
      </div>
      <div
        role="tablist"
        className="my-3 grid grid-cols-2 gap-1 rounded-md border p-1 text-[11px] sm:grid-cols-4 lg:grid-cols-2 xl:grid-cols-4"
      >
        {[
          ["config", "Config"],
          ["schema", "Ports-Schema"],
          ["validation", "Validation"],
          ["observability", "Observability"],
        ].map(([id, label]) => (
          <button
            key={id}
            type="button"
            role="tab"
            aria-selected={tab === id}
            onClick={() => setTab(id as typeof tab)}
            className={`rounded px-2 py-1 transition-colors ${
              tab === id ? "bg-primary text-primary-foreground" : "hover:bg-muted"
            }`}
          >
            {label}
          </button>
        ))}
      </div>
      <div className="min-h-0 flex-1 overflow-y-auto text-xs">
        {!instance ? (
          <p className="text-muted-foreground">Select a step on the canvas.</p>
        ) : tab === "config" ? (
          <div className="space-y-2">
            <InspectorRow label="Storage" value={instance.storageBackend} />
            <InspectorRow label="Layer" value={instance.lakeLayer ?? "not set"} />
            <InspectorRow label="Format" value={instance.lakeFormat ?? "not set"} />
            {params.length === 0 ? (
              <p className="text-muted-foreground">No configured parameters.</p>
            ) : (
              params.map(([key, value]) => (
                <div key={key} className="rounded-md border px-2 py-1.5">
                  <div className="flex items-center justify-between gap-2">
                    <span className="font-mono text-[11px]">{key}</span>
                    <Badge variant="outline" className="text-[10px]">tiered</Badge>
                  </div>
                  <pre className="mt-1 max-h-20 overflow-auto whitespace-pre-wrap break-words text-[11px] text-muted-foreground">
                    {formatParamValue(value)}
                  </pre>
                </div>
              ))
            )}
          </div>
        ) : tab === "schema" ? (
          <div className="space-y-3">
            <div className="grid grid-cols-3 gap-2">
              <Metric label="Input" value={schema.input.length} />
              <Metric label="Output" value={schema.output.length} />
              <Metric
                label="Diff"
                value={`+${schema.added.length}/~${schema.renamed.length}/!${schema.retyped.length}/-${schema.dropped.length}`}
              />
            </div>
            <SchemaDiffList title="Added" columns={schema.added} />
            <RenameList title="Renamed" renames={schema.renamed} />
            <RetypeList title="Retyped" retypes={schema.retyped} />
            <SchemaDiffList title="Dropped" columns={schema.dropped} />
            <ColumnList title="Input Columns" columns={schema.input} />
            <ColumnList title="Output Columns" columns={schema.output} />
          </div>
        ) : tab === "validation" ? (
          <div className="space-y-3">
            <SchemaConflictPanel versionId={versionId} instanceId={instance.id} />
          </div>
        ) : (
          <div className="space-y-2">
            <InspectorRow label="Schema status" value={instance.schemaStatus ?? "unknown"} />
            <InspectorRow label="Execution order" value={String(instance.executionOrder)} />
            <InspectorRow label="DQ rules" value={String(instance.dqExpectations?.length ?? 0)} />
          </div>
        )}
      </div>
    </aside>
  );
}

function RunLogDrawer({ open, logs }: { open: boolean; logs: RunLogEntry[] }) {
  if (!open) return null;
  return (
    <div className="border-t bg-muted/20 px-3 py-2">
      <div className="mb-1 flex items-center justify-between">
        <h3 className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
          Run Logs
        </h3>
        <span className="text-[10px] text-muted-foreground">backend feed</span>
      </div>
      <div className="max-h-28 overflow-y-auto rounded-md bg-background px-3 py-2 font-mono text-[11px]">
        {logs.length === 0 ? (
          <div className="text-muted-foreground">No backend run records for this version.</div>
        ) : (
          logs.map((entry) => (
            <div key={entry.id} className="grid grid-cols-[88px_72px_72px_minmax(0,1fr)] gap-2 text-muted-foreground">
              <span>{entry.at ? new Date(entry.at).toLocaleTimeString() : "--"}</span>
              <span>{entry.source}</span>
              <span>{entry.status}</span>
              <span className="truncate">{entry.message}</span>
            </div>
          ))
        )}
      </div>
    </div>
  );
}

function InspectorRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-center justify-between gap-3 rounded-md border px-2 py-1.5">
      <span className="text-muted-foreground">{label}</span>
      <span className="truncate text-right font-medium">{value}</span>
    </div>
  );
}

function Metric({ label, value }: { label: string; value: number | string }) {
  return (
    <div className="rounded-md border px-2 py-2 text-center">
      <div className="text-base font-semibold">{value}</div>
      <div className="text-[10px] uppercase tracking-wide text-muted-foreground">{label}</div>
    </div>
  );
}

function ColumnList({ title, columns }: { title: string; columns: SchemaColumn[] }) {
  return (
    <div>
      <h4 className="mb-1 text-[11px] font-medium uppercase tracking-wide text-muted-foreground">
        {title}
      </h4>
      <div className="max-h-[22.5rem] space-y-1 overflow-y-auto">
        {columns.length === 0 ? (
          <p className="text-[11px] text-muted-foreground">No columns resolved.</p>
        ) : (
          columns.map((column) => (
            <div key={`${column.name}-${column.type}`} className="flex justify-between gap-2 rounded border px-2 py-1">
              <span className="truncate font-mono text-[11px]">{column.name}</span>
              <span className="shrink-0 text-[11px] text-muted-foreground">{column.type}</span>
            </div>
          ))
        )}
      </div>
    </div>
  );
}

function SchemaDiffList({ title, columns }: { title: string; columns: SchemaColumn[] }) {
  if (columns.length === 0) return null;
  return (
    <div>
      <h4 className="mb-1 text-[11px] font-medium uppercase tracking-wide text-muted-foreground">
        {title}
      </h4>
      <div className="space-y-1">
        {columns.map((column) => (
          <div key={`${title}-${column.name}`} className="flex justify-between gap-2 rounded border px-2 py-1">
            <span className="truncate font-mono text-[11px]">{column.name}</span>
            <span className="shrink-0 text-[11px] text-muted-foreground">{column.type}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

function RenameList({
  title,
  renames,
}: {
  title: string;
  renames: ReturnType<typeof computeSchemaDiff>["renamed"];
}) {
  if (renames.length === 0) return null;
  return (
    <div>
      <h4 className="mb-1 text-[11px] font-medium uppercase tracking-wide text-muted-foreground">
        {title}
      </h4>
      <div className="space-y-1">
        {renames.map(({ from, to }) => (
          <div key={`${from.name}-${to.name}`} className="rounded border px-2 py-1 text-[11px]">
            <span className="font-mono">{from.name}</span>
            <span className="mx-1 text-muted-foreground">→</span>
            <span className="font-mono">{to.name}</span>
            <span className="ml-2 text-muted-foreground">{to.type}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

function RetypeList({
  title,
  retypes,
}: {
  title: string;
  retypes: ReturnType<typeof computeSchemaDiff>["retyped"];
}) {
  if (retypes.length === 0) return null;
  return (
    <div>
      <h4 className="mb-1 text-[11px] font-medium uppercase tracking-wide text-muted-foreground">
        {title}
      </h4>
      <div className="space-y-1">
        {retypes.map(({ before, after }) => (
          <div key={`${before.name}-${before.type}-${after.type}`} className="rounded border px-2 py-1 text-[11px]">
            <span className="font-mono">{before.name}</span>
            <span className="ml-2 text-muted-foreground">{before.type} → {after.type}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

function schemaStats(instance: SubPipelineInstance | null, composition: CompositionView | null) {
  const output = instance?.outputSchema?.columns ?? [];
  const input = !instance || !composition
    ? []
    : composition.wirings
      .filter((wire) => wire.targetInstanceId === instance.id)
      .flatMap((wire) =>
        composition.instances.find((candidate) => candidate.id === wire.sourceInstanceId)
          ?.outputSchema?.columns ?? []
      );
  return computeSchemaDiff(input, output);
}

function formatParamValue(value: unknown) {
  if (value === null || value === undefined || value === "") return "not set";
  if (typeof value === "string") return value;
  return JSON.stringify(value, null, 2);
}

function CreateRevisionDialog({
  tenantId,
  pipelineId,
  onCreated,
}: {
  tenantId: string;
  pipelineId: string;
  onCreated: (version: PipelineVersion) => void;
}) {
  const [open, setOpen] = useState(false);
  const [changeSummary, setChangeSummary] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleCreate = async () => {
    setLoading(true);
    setError(null);
    try {
      const version = await api.post<PipelineVersion>(
        `/api/v1/tenants/${tenantId}/pipelines/${pipelineId}/versions`,
        { changeSummary }
      );
      onCreated(version);
      setOpen(false);
      setChangeSummary("");
    } catch (err) {
      setError(
        err instanceof Error ? err.message : "Failed to create revision"
      );
    } finally {
      setLoading(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button variant="outline">New Revision</Button>
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>New Revision</DialogTitle>
          <DialogDescription>
            Start a new engineering revision for this pipeline. The current
            production revision will continue running undisturbed.
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-4 py-2">
          <div className="space-y-2">
            <Label htmlFor="change-summary">What are you changing?</Label>
            <Textarea
              id="change-summary"
              placeholder="e.g. Add APAC region support, fix currency conversion..."
              value={changeSummary}
              onChange={(e) => setChangeSummary(e.target.value)}
              rows={3}
            />
          </div>

          {error && <p className="text-sm text-destructive">{error}</p>}
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => setOpen(false)}>
            Cancel
          </Button>
          <Button onClick={handleCreate} disabled={loading || !changeSummary}>
            {loading ? "Creating..." : "Start New Revision"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
