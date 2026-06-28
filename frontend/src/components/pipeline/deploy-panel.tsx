"use client";

import { useEffect, useState, useTransition } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
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
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Separator } from "@/components/ui/separator";
import Link from "next/link";
import { useTenant } from "@/contexts/tenant-context";
import { useAuth } from "@/contexts/auth-context";
import { api } from "@/lib/api";
import type {
  DeployPackage,
  DeploymentRecord,
  DeploymentTarget,
  ApprovalRequest,
  DeployEnvironment,
  PromotionProofReadback,
  PromotionProofState,
  RuntimeEvidenceEnvelope,
  DeployBoundaryReadback,
  DeployBoundaryStatus,
} from "@/types";
import {
  DEPLOY_ENVIRONMENT_LABELS,
  DEPLOY_ENVIRONMENTS,
  DEPLOY_BOUNDARY_STATUS_LABELS,
  PROMOTION_PROOF_STATE_LABELS,
  EVIDENCE_PROOF_LEVELS,
  EVIDENCE_PROOF_LEVEL_LABELS,
  hasRuntimeEvidence,
  isRuntimeProofLevel,
  nonRuntimeProofDisclaimer,
  normalizeDeployEnvironment,
} from "@/types";

interface DeployPanelProps {
  pipelineId: string;
  versionId: string;
  editable: boolean;
}

async function fetchDeployData(
  tenantId: string,
  pipelineId: string,
  versionId: string
) {
  const [pkgs, deps, tgts] = await Promise.all([
    api.get<DeployPackage[]>(`/api/v1/versions/${versionId}/packages`),
    api.get<DeploymentRecord[]>(`/api/v1/pipelines/${pipelineId}/deployments`),
    api.get<DeploymentTarget[]>(`/api/v1/tenants/${tenantId}/deployment-targets`),
  ]);
  const relevantDeployments = deps.filter((deployment) => deployment.versionId === versionId);
  const approvalEntries = await Promise.all(
    relevantDeployments.map(async (deployment) => {
      const approvals = await api
        .get<ApprovalRequest[]>(
          `/api/v1/deployments/${deployment.id}/approval-requests`
        )
        .catch(() => []);
      return [deployment.id, approvals] as const;
    })
  );

  return {
    packages: pkgs,
    deployments: relevantDeployments,
    targets: tgts,
    approvalsByDeployment: Object.fromEntries(approvalEntries) as Record<
      string,
      ApprovalRequest[]
    >,
  };
}

export function DeployPanel({ pipelineId, versionId, editable }: DeployPanelProps) {
  const { currentTenant } = useTenant();
  const { user } = useAuth();
  const [packages, setPackages] = useState<DeployPackage[]>([]);
  const [deployments, setDeployments] = useState<DeploymentRecord[]>([]);
  const [targets, setTargets] = useState<DeploymentTarget[]>([]);
  const [approvalsByDeployment, setApprovalsByDeployment] = useState<Record<string, ApprovalRequest[]>>({});
  const [proofState, setProofState] = useState<PromotionProofReadback | null>(null);
  const [evidenceByRunId, setEvidenceByRunId] = useState<Record<string, RuntimeEvidenceEnvelope>>({});
  const [boundaryByTargetId, setBoundaryByTargetId] = useState<Record<string, DeployBoundaryReadback>>({});
  const [building, setBuilding] = useState(false);
  const [, startTransition] = useTransition();

  useEffect(() => {
    if (!currentTenant?.id) return;
    startTransition(async () => {
      try {
        const [nextData, proof] = await Promise.all([
          fetchDeployData(currentTenant.id, pipelineId, versionId),
          api.get<PromotionProofReadback>(
            `/api/v1/versions/${versionId}/promotion-proof-state`
          ).catch(() => null),
        ]);
        setPackages(nextData.packages);
        setDeployments(nextData.deployments);
        setTargets(nextData.targets);
        setApprovalsByDeployment(nextData.approvalsByDeployment);
        setProofState(proof);
        // PKT-0005: fetch evidence envelopes for deployment runs
        const evidenceEntries = await Promise.all(
          nextData.deployments
            .map((dep) => {
              const meta = (dep.metadata ?? {}) as Record<string, unknown>;
              const runId = typeof meta.deploymentRunId === "string" ? meta.deploymentRunId : null;
              if (!runId) return null;
              return api
                .get<RuntimeEvidenceEnvelope>(`/api/v1/deployment-runs/${runId}/evidence`)
                .then((env) => [runId, env] as const)
                .catch(() => null);
            })
            .filter((p): p is NonNullable<typeof p> => p !== null)
        );
        const evidenceMap: Record<string, RuntimeEvidenceEnvelope> = {};
        for (const entry of evidenceEntries) {
          if (entry) evidenceMap[entry[0]] = entry[1];
        }
        setEvidenceByRunId(evidenceMap);
        // PKT-0004: fetch deploy boundary readback for GCP targets
        const boundaryEntries = await Promise.all(
          nextData.targets
            .filter((t) => t.targetType === "GCP_COMPOSER_DATAPROC")
            .map((t) =>
              api
                .get<DeployBoundaryReadback>(
                  `/api/v1/tenants/${currentTenant.id}/deployment-targets/${t.id}/boundary`
                    + (nextData.packages[0]?.id ? `?packageId=${nextData.packages[0].id}` : "")
                )
                .then((b) => [t.id, b] as const)
                .catch(() => null)
            )
        );
        const boundaryMap: Record<string, DeployBoundaryReadback> = {};
        for (const entry of boundaryEntries) {
          if (entry) boundaryMap[entry[0]] = entry[1];
        }
        setBoundaryByTargetId(boundaryMap);
      } catch {
        setPackages([]);
        setDeployments([]);
        setTargets([]);
        setApprovalsByDeployment({});
        setProofState(null);
      }
    });
  }, [currentTenant?.id, pipelineId, versionId]);

  const handleBuild = async () => {
    if (!currentTenant || !user) return;
    setBuilding(true);
    try {
      const pkg = await api.post<DeployPackage>(`/api/v1/versions/${versionId}/packages`, {
        pipelineId,
        tenantId: currentTenant.id,
        userId: user.id,
        packageType: "ARTIFACT_BUNDLE",
      });
      setPackages((prev) => [pkg, ...prev]);
    } finally {
      setBuilding(false);
    }
  };

  const handleDeploy = async (packageId: string, targetId: string) => {
    if (!currentTenant || !user) return;
    try {
      const dep = await api.post<DeploymentRecord>(`/api/v1/packages/${packageId}/deploy`, {
        targetId,
        tenantId: currentTenant.id,
        userId: user.id,
      });
      setDeployments((prev) => [dep, ...prev]);
    } catch {
      // handled by api client
    }
  };

  const handleRequestApproval = async (deploymentId: string) => {
    if (!currentTenant || !user) return;
    try {
      await api.post<ApprovalRequest>(`/api/v1/deployments/${deploymentId}/approval`, {
        tenantId: currentTenant.id,
        requestedBy: user.id,
      });
      const nextData = await fetchDeployData(
        currentTenant.id,
        pipelineId,
        versionId
      );
      setPackages(nextData.packages);
      setDeployments(nextData.deployments);
      setTargets(nextData.targets);
      setApprovalsByDeployment(nextData.approvalsByDeployment);
    } catch {
      // handled by api client
    }
  };

  const handleDecision = async (
    approvalId: string,
    status: "APPROVED" | "REJECTED"
  ) => {
    if (!user || !currentTenant) return;
    try {
      await api.put<ApprovalRequest>(`/api/v1/approvals/${approvalId}`, {
        status,
        decidedBy: user.id,
        reason: status === "APPROVED" ? "Approved from deploy panel" : "Rejected from deploy panel",
      });
      const nextData = await fetchDeployData(
        currentTenant.id,
        pipelineId,
        versionId
      );
      setPackages(nextData.packages);
      setDeployments(nextData.deployments);
      setTargets(nextData.targets);
      setApprovalsByDeployment(nextData.approvalsByDeployment);
    } catch {
      // handled by api client
    }
  };

  const statusColor = (status: string) => {
    switch (status) {
      case "COMPLETED":
      // Phase 4: a deployment in DEPLOYED is a legacy-row status; new
      // flows use ACTIVE for "current active release" and RUNNING for
      // "preflight passed, run in flight". Both are non-failed.
      case "DEPLOYED":
      case "ACTIVE": return "bg-green-500/10 text-green-700 border-green-500/20";
      case "PENDING":
      case "BUILDING":
      case "DEPLOYING":
      case "DRAFT":
      case "RUNNING": return "bg-yellow-500/10 text-yellow-700 border-yellow-500/20";
      case "FAILED":
      case "PREFLIGHT_FAILED":
      case "ROLLED_BACK":
      case "SUPERSEDED": return "bg-red-500/10 text-red-700 border-red-500/20";
      default: return "";
    }
  };

  const preflightStatusColor = (status: string | undefined) => {
    if (status === "PASS") return "bg-green-500/10 text-green-700 border-green-500/20";
    if (status === "FAIL") return "bg-red-500/10 text-red-700 border-red-500/20";
    return "";
  };

  const latestPackage = packages[0];
  const hasPackages = packages.length > 0;

  return (
    <Card className="col-span-3">
      <CardHeader className="pb-2">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <CardTitle className="text-sm font-medium">Build & Deploy</CardTitle>
            {hasPackages && (
              <Badge variant="outline" className="text-[10px]">
                {packages.length} build{packages.length !== 1 ? "s" : ""}
              </Badge>
            )}
          </div>
          {editable && (
            <Button
              size="sm"
              className="h-7 text-xs"
              onClick={handleBuild}
              disabled={building}
            >
              {building ? "Building..." : "Build Package"}
            </Button>
          )}
        </div>
      </CardHeader>
      <CardContent>
        {proofState && (
          <PromotionProofStateBar proofState={proofState} />
        )}
        {!hasPackages ? (
          <div className="text-center py-6 text-muted-foreground">
            <p className="text-sm">No packages built yet.</p>
            {editable && (
              <p className="text-xs mt-1">
                Build a deployment package from the generated code to deploy to target environments.
              </p>
            )}
          </div>
        ) : (
          <div className="space-y-4">
            {/* Latest package */}
            <div className="space-y-2">
              <span className="text-xs font-medium text-muted-foreground uppercase">
                Packages
              </span>
              {packages.map((pkg) => (
                (() => {
                  const assessment =
                    pkg.metadata?.staticRuntimeAssessment &&
                    typeof pkg.metadata.staticRuntimeAssessment === "object"
                      ? (pkg.metadata.staticRuntimeAssessment as Record<string, unknown>)
                      : null;
                  return (
                <div
                  key={pkg.id}
                  className="flex items-center justify-between rounded-md border px-3 py-2.5 text-xs"
                >
                  <div className="flex items-center gap-2">
                    <Badge
                      variant="outline"
                      className={`text-[10px] ${statusColor(pkg.buildStatus)}`}
                    >
                      {pkg.buildStatus}
                    </Badge>
                    <Badge variant="secondary" className="text-[10px]">
                      {pkg.packageType}
                    </Badge>
                    {typeof pkg.metadata?.compileNamespace === "string" && (
                      <span className="text-muted-foreground font-mono truncate max-w-[220px]">
                        {String(pkg.metadata.compileNamespace)}
                      </span>
                    )}
                    {pkg.artifactUrl && (
                      <span className="text-muted-foreground font-mono truncate max-w-[200px]">
                        {pkg.artifactUrl}
                      </span>
                    )}
                  </div>
                  <div className="flex items-center gap-2 shrink-0">
                    {pkg.builtAt && (
                      <span className="text-muted-foreground">
                        {new Date(pkg.builtAt).toLocaleString()}
                      </span>
                    )}
                    {pkg.buildStatus === "COMPLETED" && targets.length > 0 && (
                      <DeployToTargetDialog
                        packageId={pkg.id}
                        targets={targets}
                        boundaryByTargetId={boundaryByTargetId}
                        onDeploy={handleDeploy}
                      />
                    )}
                  </div>
                    {pkg.metadata && (
                      <div className="mt-2 grid gap-1 text-[11px] text-muted-foreground md:grid-cols-2">
                        <span>
                          Artifacts: {String(pkg.metadata.artifactCount ?? "0")}
                        </span>
                        <span>
                          Gold boundary: {pkg.metadata.hasGoldPublishBoundary ? "present" : "missing"}
                        </span>
                      </div>
                    )}
                    {assessment && (
                        <div className="mt-2 rounded-md bg-muted/40 px-3 py-2 text-[11px]">
                          <div className="flex items-center gap-2 flex-wrap">
                            <Badge variant="outline" className="text-[10px] bg-blue-500/10 text-blue-700 border-blue-500/20">
                              static proof only
                            </Badge>
                            <span className="font-medium">
                              {String(assessment.verdict ?? "UNKNOWN")}
                            </span>
                            <span className="text-muted-foreground">
                              score {String(assessment.score ?? "0")}
                            </span>
                          </div>
                          <p className="mt-1 text-[10px] text-muted-foreground">
                            Static package assessment — does not prove runtime execution success. Promotion requires runtime evidence.
                          </p>
                          {Array.isArray(assessment.blockers) &&
                            (assessment.blockers as unknown[]).length > 0 && (
                              <div className="mt-2 text-destructive">
                                Blockers:{" "}
                                {(assessment.blockers as string[]).join("; ")}
                              </div>
                            )}
                          {Array.isArray(assessment.warnings) &&
                            (assessment.warnings as unknown[]).length > 0 && (
                              <div className="mt-1 text-muted-foreground">
                                Warnings:{" "}
                                {(assessment.warnings as string[]).join("; ")}
                              </div>
                            )}
                        </div>
                      )}
                </div>
                  );
                })()
              ))}
            </div>

            {/* Deployments */}
            {deployments.length > 0 && (
              <>
                <Separator />
                <div className="space-y-2">
                  <span className="text-xs font-medium text-muted-foreground uppercase">
                    Deployments
                  </span>
                  {deployments.map((dep) => {
                    const target = targets.find((t) => t.id === dep.targetId);
                    const approvals = approvalsByDeployment[dep.id] || [];
                    const latestApproval = approvals[0];
                    // Phase 4: deployment metadata may carry preflight
                    // status/blockers + run id. The metadata field is
                    // typed loosely on the wire; pick fields out
                    // defensively so legacy rows still render.
                    const meta = (dep.metadata ?? {}) as Record<string, unknown>;
                    const preflightStatus =
                      typeof meta.preflightStatus === "string"
                        ? (meta.preflightStatus as string)
                        : undefined;
                    const preflightBlockers = Array.isArray(meta.preflightBlockers)
                      ? (meta.preflightBlockers as string[])
                      : undefined;
                    const deploymentRunId =
                      typeof meta.deploymentRunId === "string"
                        ? (meta.deploymentRunId as string)
                        : undefined;
                    const isApprovableStatus =
                      dep.status === "DEPLOYED" ||
                      dep.status === "RUNNING" ||
                      dep.status === "ACTIVE";
                    return (
                      <div
                        key={dep.id}
                        className="rounded-md border px-3 py-2.5 text-xs"
                      >
                        <div className="flex items-center justify-between gap-3">
                          <div className="flex items-center gap-2 flex-wrap">
                            <Badge
                              variant="outline"
                              className={`text-[10px] ${statusColor(dep.status)}`}
                            >
                              {dep.status}
                            </Badge>
                            {target && (
                              <>
                                <span className="font-medium">{target.name}</span>
                                <Badge variant="secondary" className="text-[10px]">
                                  {DEPLOY_ENVIRONMENT_LABELS[
                                    normalizeDeployEnvironment(target.environment) ?? "dev"
                                  ]}
                                </Badge>
                              </>
                            )}
                            {preflightStatus && (
                              <Badge
                                variant="outline"
                                className={`text-[10px] ${preflightStatusColor(preflightStatus)}`}
                                title="Preflight result for the latest deployment run"
                              >
                                preflight: {preflightStatus}
                              </Badge>
                            )}
                            {deploymentRunId && (
                              <Badge
                                variant="outline"
                                className="text-[10px] font-mono"
                                title="Deployment run id (Phase 4)"
                              >
                                run {deploymentRunId.slice(0, 8)}
                              </Badge>
                            )}
                            {latestApproval && (
                              <Badge variant="outline" className="text-[10px]">
                                approval: {latestApproval.status}
                              </Badge>
                            )}
                          </div>
                          <div className="flex items-center gap-2 shrink-0">
                            {dep.deployedAt && (
                              <span className="text-muted-foreground">
                                {new Date(dep.deployedAt).toLocaleString()}
                              </span>
                            )}
                            {isApprovableStatus &&
                              target &&
                              (() => {
                                const envKey = normalizeDeployEnvironment(target.environment);
                                return envKey === "uat" || envKey === "prod";
                              })() &&
                              !latestApproval && (
                                proofState && !hasRuntimeEvidence(proofState.currentState) ? (
                                  <Badge
                                    variant="outline"
                                    className="text-[10px] bg-amber-500/10 text-amber-700 border-amber-500/20"
                                    title="Promotion requires runtime proof — current proof state is insufficient"
                                  >
                                    promotion blocked — runtime proof missing
                                  </Badge>
                                ) : (
                                  <Button
                                    size="sm"
                                    variant="outline"
                                    className="h-6 text-[10px]"
                                    onClick={() => handleRequestApproval(dep.id)}
                                  >
                                    Request Approval
                                  </Button>
                                )
                              )}
                          </div>
                        </div>
                        {preflightBlockers && preflightBlockers.length > 0 && (
                          <div className="mt-2 rounded-md bg-red-500/5 border border-red-500/20 px-2 py-1.5 text-[10px]">
                            <div className="font-medium text-red-700 mb-0.5">
                              Preflight blockers ({preflightBlockers.length})
                            </div>
                            <div className="font-mono text-red-700/80 break-words">
                              {preflightBlockers.join(", ")}
                            </div>
                          </div>
                        )}
                        {deploymentRunId && evidenceByRunId[deploymentRunId] && (
                          <RuntimeEvidenceLadder evidence={evidenceByRunId[deploymentRunId]} />
                        )}
                        {latestApproval && latestApproval.status === "PENDING" && (
                          <div className="mt-2 flex items-center justify-end gap-2">
                            <Button
                              size="sm"
                              variant="outline"
                              className="h-6 text-[10px]"
                              onClick={() => handleDecision(latestApproval.id, "REJECTED")}
                            >
                              Reject
                            </Button>
                            <Button
                              size="sm"
                              className="h-6 text-[10px]"
                              onClick={() => handleDecision(latestApproval.id, "APPROVED")}
                            >
                              Approve
                            </Button>
                          </div>
                        )}
                      </div>
                    );
                  })}
                </div>
              </>
            )}

            {/* No targets hint */}
            {targets.length === 0 && latestPackage?.buildStatus === "COMPLETED" && (
              <>
                <Separator />
                <div className="flex items-center justify-between">
                  <div className="space-y-1">
                    <p className="text-xs text-muted-foreground">
                      No deployment targets configured.
                    </p>
                    <Link
                      href="/settings#tenant-readiness"
                      className="text-[11px] text-primary hover:underline"
                    >
                      Check Tenant Readiness →
                    </Link>
                  </div>
                  {currentTenant && (
                    <CreateTargetDialog
                      tenantId={currentTenant.id}
                      onCreated={(t) => setTargets((prev) => [...prev, t])}
                    />
                  )}
                </div>
              </>
            )}
          </div>
        )}
      </CardContent>
    </Card>
  );
}

function DeployToTargetDialog({
  packageId,
  targets,
  boundaryByTargetId,
  onDeploy,
}: {
  packageId: string;
  targets: DeploymentTarget[];
  boundaryByTargetId: Record<string, DeployBoundaryReadback>;
  onDeploy: (packageId: string, targetId: string) => Promise<void>;
}) {
  const [open, setOpen] = useState(false);
  const [targetId, setTargetId] = useState("");
  const [deploying, setDeploying] = useState(false);

  const selectedBoundary = targetId ? boundaryByTargetId[targetId] : null;
  const isOperatorBlocked = selectedBoundary?.boundaryStatus === "OPERATOR_BLOCKED";
  const isHardBlocked = selectedBoundary?.boundaryStatus === "BLOCKED";
  const deployBlocked = isOperatorBlocked || isHardBlocked;

  const handleDeploy = async () => {
    if (!targetId || deployBlocked) return;
    setDeploying(true);
    try {
      await onDeploy(packageId, targetId);
      setOpen(false);
      setTargetId("");
    } finally {
      setDeploying(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button size="sm" variant="outline" className="h-6 text-[10px]">
          Deploy
        </Button>
      </DialogTrigger>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>Deploy Package</DialogTitle>
          <DialogDescription>
            Select a target environment to deploy this package.
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-4 py-2">
          <div className="space-y-2">
            <Label>Target Environment</Label>
            <Select value={targetId} onValueChange={setTargetId}>
              <SelectTrigger className="h-9">
                <SelectValue placeholder="Select target..." />
              </SelectTrigger>
              <SelectContent>
                {targets.filter((t) => t.enabled).map((t) => {
                  const envKey = normalizeDeployEnvironment(t.environment);
                  const envLabel = envKey
                    ? DEPLOY_ENVIRONMENT_LABELS[envKey]
                    : t.environment;
                  const boundary = boundaryByTargetId[t.id];
                  const statusLabel = boundary
                    ? DEPLOY_BOUNDARY_STATUS_LABELS[boundary.boundaryStatus]
                    : null;
                  return (
                    <SelectItem key={t.id} value={t.id}>
                      <div className="flex items-center gap-2">
                        <span>{t.name}</span>
                        <span className="text-muted-foreground">
                          ({envLabel} / {t.targetType})
                        </span>
                        {statusLabel && boundary?.boundaryStatus !== "LIVE" && (
                          <span className="text-amber-700 text-[10px] font-medium">
                            [{statusLabel}]
                          </span>
                        )}
                      </div>
                    </SelectItem>
                  );
                })}
              </SelectContent>
            </Select>
          </div>
          {selectedBoundary && (
            <DeployBoundaryPanel boundary={selectedBoundary} />
          )}
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => setOpen(false)}>Cancel</Button>
          <Button
            onClick={handleDeploy}
            disabled={deploying || !targetId || deployBlocked}
            title={deployBlocked ? "Deploy blocked — resolve operator actions first" : undefined}
          >
            {deploying ? "Deploying..." : deployBlocked ? "Deploy Blocked" : "Deploy"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function CreateTargetDialog({
  tenantId,
  onCreated,
}: {
  tenantId: string;
  onCreated: (target: DeploymentTarget) => void;
}) {
  const [open, setOpen] = useState(false);
  const [name, setName] = useState("");
  const [environment, setEnvironment] = useState<DeployEnvironment>("dev");
  const [targetType, setTargetType] = useState("KUBERNETES");
  const [endpointUrl, setEndpointUrl] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleCreate = async () => {
    if (!name) return;
    setLoading(true);
    setError(null);
    try {
      const target = await api.post<DeploymentTarget>(
        `/api/v1/tenants/${tenantId}/deployment-targets`,
        { name, environment, targetType, endpointUrl: endpointUrl || undefined }
      );
      onCreated(target);
      setOpen(false);
      setName("");
      setEndpointUrl("");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to create target");
    } finally {
      setLoading(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button size="sm" variant="outline" className="h-7 text-xs">
          Add Target
        </Button>
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Add Deployment Target</DialogTitle>
          <DialogDescription>
            Configure a target environment for pipeline deployments.
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-4 py-2">
          <div className="space-y-2">
            <Label htmlFor="target-name">Name</Label>
            <Input
              id="target-name"
              placeholder="e.g. Dev Kubernetes Cluster"
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
          </div>
          <div className="grid grid-cols-2 gap-4">
            <div className="space-y-2">
              <Label>Environment</Label>
              <Select
                value={environment}
                onValueChange={(value) => setEnvironment(value as DeployEnvironment)}
              >
                <SelectTrigger className="h-8 text-xs">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {DEPLOY_ENVIRONMENTS.map((env) => (
                    <SelectItem key={env} value={env}>
                      {DEPLOY_ENVIRONMENT_LABELS[env]}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-2">
              <Label>Type</Label>
              <Select value={targetType} onValueChange={setTargetType}>
                <SelectTrigger className="h-8 text-xs">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="KUBERNETES">Kubernetes</SelectItem>
                  <SelectItem value="AIRFLOW">Airflow</SelectItem>
                  <SelectItem value="DATABRICKS">Databricks</SelectItem>
                </SelectContent>
              </Select>
            </div>
          </div>
          <div className="space-y-2">
            <Label htmlFor="endpoint-url">Endpoint URL (optional)</Label>
            <Input
              id="endpoint-url"
              placeholder="https://k8s.internal.corp/api"
              value={endpointUrl}
              onChange={(e) => setEndpointUrl(e.target.value)}
            />
          </div>
          {error && <p className="text-sm text-destructive">{error}</p>}
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => setOpen(false)}>Cancel</Button>
          <Button onClick={handleCreate} disabled={loading || !name}>
            {loading ? "Creating..." : "Create Target"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/**
 * PKT-0008: Promotion proof-state progress bar.
 *
 * Renders the seven-tier proof state as a horizontal stepper,
 * explicitly distinguishing each tier. Static package proof is
 * never rendered as runtime-proved or promotion-complete.
 */
function PromotionProofStateBar({ proofState }: { proofState: PromotionProofReadback }) {
  const tiers: PromotionProofState[] = [
    "DRAFT_WORKSPACE",
    "ACCEPTED_ARTIFACT",
    "STATIC_PACKAGE_PROOF",
    "DEPLOY_REQUESTED",
    "RUNTIME_PROVED",
    "PROMOTION_READY",
    "PROMOTION_COMPLETE",
  ];

  const currentIndex = tiers.indexOf(proofState.currentState);
  const isRuntime = hasRuntimeEvidence(proofState.currentState);

  const tierColor = (index: number) => {
    if (index > currentIndex) return "bg-muted text-muted-foreground";
    if (index === currentIndex) {
      if (isRuntime) return "bg-green-500/20 text-green-700 border-green-500/30";
      return "bg-blue-500/20 text-blue-700 border-blue-500/30";
    }
    return "bg-primary/10 text-primary/70";
  };

  return (
    <div className="mb-4 space-y-2">
      <div className="flex items-center justify-between">
        <span className="text-xs font-medium text-muted-foreground uppercase">
          Promotion Proof State
        </span>
        <Badge
          variant="outline"
          className={`text-[10px] ${isRuntime ? "bg-green-500/10 text-green-700 border-green-500/20" : "bg-blue-500/10 text-blue-700 border-blue-500/20"}`}
        >
          {PROMOTION_PROOF_STATE_LABELS[proofState.currentState]}
        </Badge>
      </div>
      <div className="flex gap-1">
        {tiers.map((tier, i) => (
          <div
            key={tier}
            className={`flex-1 h-1.5 rounded-full ${tierColor(i)}`}
            title={`${PROMOTION_PROOF_STATE_LABELS[tier]}${i <= currentIndex ? " (reached)" : " (not reached)"}`}
          />
        ))}
      </div>
      {proofState.staticPackage && !proofState.runtimeProof && (
        <div className="rounded-md bg-amber-500/5 border border-amber-500/20 px-2 py-1.5">
          <p className="text-[10px] text-amber-700 font-medium">
            Static proof only — runtime execution evidence not yet available.
          </p>
          <p className="text-[10px] text-amber-700/70 mt-0.5">
            Static package proof cannot satisfy runtime output gates. Deploy and execute to obtain runtime evidence.
          </p>
        </div>
      )}
      {proofState.deployRequest && !proofState.runtimeProof && (
        <div className="rounded-md bg-amber-500/5 border border-amber-500/20 px-2 py-1.5">
          <p className="text-[10px] text-amber-700 font-medium">
            Deploy requested — awaiting runtime execution result.
          </p>
          <p className="text-[10px] text-amber-700/70 mt-0.5">
            Promotion is blocked until runtime proof is obtained from the target environment.
          </p>
        </div>
      )}
      {!proofState.staticPackage && !proofState.runtimeProof && proofState.currentState === "DRAFT_WORKSPACE" && (
        <p className="text-[10px] text-muted-foreground">
          Draft workspace — no packages built yet. Build, deploy, and execute to advance the proof ladder.
        </p>
      )}
    </div>
  );
}

/**
 * PKT-0005: Runtime evidence ladder display.
 *
 * Renders the evidence proof level as a horizontal stepper that
 * explicitly distinguishes local/static/preflight evidence from
 * real runtime output proof. Local synthetic and static evidence
 * are rendered with amber/blue indicators; live runtime+ with green.
 */
function RuntimeEvidenceLadder({ evidence }: { evidence: RuntimeEvidenceEnvelope }) {
  const currentIndex = EVIDENCE_PROOF_LEVELS.indexOf(evidence.proofLevel);
  const isRuntime = isRuntimeProofLevel(evidence.proofLevel);

  const tierColor = (index: number) => {
    if (index > currentIndex) return "bg-muted text-muted-foreground";
    if (index === currentIndex) {
      if (isRuntime) return "bg-green-500/20 text-green-700 border-green-500/30";
      return "bg-amber-500/20 text-amber-700 border-amber-500/30";
    }
    return "bg-primary/10 text-primary/70";
  };

  return (
    <div className="mt-3 space-y-1.5">
      <div className="flex items-center justify-between">
        <span className="text-[10px] font-medium text-muted-foreground uppercase">
          Evidence Proof Level
        </span>
        <div className="flex items-center gap-1.5">
          <Badge
            variant="outline"
            className={`text-[10px] ${isRuntime ? "bg-green-500/10 text-green-700 border-green-500/20" : "bg-amber-500/10 text-amber-700 border-amber-500/20"}`}
          >
            {EVIDENCE_PROOF_LEVEL_LABELS[evidence.proofLevel]}
          </Badge>
          {!evidence.runtimeProof && (
            <Badge variant="outline" className="text-[10px] bg-amber-500/10 text-amber-700 border-amber-500/20">
              non-runtime proof
            </Badge>
          )}
          {evidence.runtimeProof && (
            <Badge variant="outline" className="text-[10px] bg-green-500/10 text-green-700 border-green-500/20">
              runtime proof
            </Badge>
          )}
        </div>
      </div>
      <div className="flex gap-0.5">
        {EVIDENCE_PROOF_LEVELS.map((level, i) => (
          <div
            key={level}
            className={`flex-1 h-1 rounded-full ${tierColor(i)}`}
            title={`${EVIDENCE_PROOF_LEVEL_LABELS[level]}${i <= currentIndex ? " (reached)" : " (not reached)"}`}
          />
        ))}
      </div>
      {!evidence.runtimeProof && (
        <div className="rounded-md bg-amber-500/5 border border-amber-500/20 px-2 py-1.5">
          <p className="text-[10px] text-amber-700 font-medium">
            {nonRuntimeProofDisclaimer(evidence.proofLevel) ||
              "No runtime execution evidence available."}
          </p>
          <p className="text-[10px] text-amber-700/70 mt-0.5">
            This evidence level is insufficient for promotion. Do not treat local or static evidence as end-to-end runtime success.
          </p>
        </div>
      )}
      {evidence.promotionReady && (
        <div className="rounded-md bg-green-500/5 border border-green-500/20 px-2 py-1.5">
          <p className="text-[10px] text-green-700 font-medium">
            Promotion ready — all evidence tiers satisfied with runtime proof.
          </p>
        </div>
      )}
    </div>
  );
}

/**
 * PKT-0004: Deploy boundary readback panel.
 *
 * Displays topology, IAM, credential, and blocker status for
 * Composer, Dataproc, BigQuery, Secret Manager, and evidence/log
 * targets. Boundary readback is NOT runtime proof — the disclaimer
 * is always shown.
 */
function DeployBoundaryPanel({ boundary }: { boundary: DeployBoundaryReadback }) {
  const statusColor = (status: DeployBoundaryStatus) => {
    switch (status) {
      case "LIVE": return "bg-green-500/10 text-green-700 border-green-500/20";
      case "OPERATOR_BLOCKED": return "bg-amber-500/10 text-amber-700 border-amber-500/20";
      case "BLOCKED": return "bg-red-500/10 text-red-700 border-red-500/20";
    }
  };

  const readyIcon = (ready: boolean) => ready ? "ready" : "blocked";

  return (
    <div className="rounded-md border px-3 py-2.5 text-[11px] space-y-2">
      <div className="flex items-center justify-between">
        <span className="text-xs font-medium text-muted-foreground uppercase">
          Deploy Boundary
        </span>
        <Badge variant="outline" className={`text-[10px] ${statusColor(boundary.boundaryStatus)}`}>
          {DEPLOY_BOUNDARY_STATUS_LABELS[boundary.boundaryStatus]}
        </Badge>
      </div>

      {boundary.blockers.length > 0 && (
        <div className="rounded-md bg-amber-500/5 border border-amber-500/20 px-2 py-1.5">
          <div className="font-medium text-amber-700 mb-0.5">
            Blockers ({boundary.blockers.length})
          </div>
          <div className="font-mono text-amber-700/80 break-words space-y-0.5">
            {boundary.blockers.map((b, i) => (
              <div key={i}>{b}</div>
            ))}
          </div>
        </div>
      )}

      {boundary.operatorActions.length > 0 && (
        <div className="rounded-md bg-blue-500/5 border border-blue-500/20 px-2 py-1.5">
          <div className="font-medium text-blue-700 mb-0.5">
            Operator Actions Required
          </div>
          <ul className="list-disc list-inside text-blue-700/80">
            {boundary.operatorActions.map((a, i) => (
              <li key={i}>{a}</li>
            ))}
          </ul>
        </div>
      )}

      <div className="grid grid-cols-2 gap-2">
        {boundary.composer && (
          <div className="rounded-md bg-muted/30 px-2 py-1.5">
            <div className="font-medium flex items-center gap-1">
              Composer
              <Badge variant="outline" className="text-[9px] ml-auto">
                {readyIcon(boundary.composer.ready)}
              </Badge>
            </div>
            <div className="text-muted-foreground mt-0.5 space-y-0.5">
              {boundary.composer.gcpProject && <div>Project: {boundary.composer.gcpProject}</div>}
              {boundary.composer.region && <div>Region: {boundary.composer.region}</div>}
              {boundary.composer.deployIdentityExpectation && (
                <div>Identity: {boundary.composer.deployIdentityExpectation}</div>
              )}
              <div>Responsibility: {boundary.composer.responsibility}</div>
            </div>
          </div>
        )}

        {boundary.dataproc && (
          <div className="rounded-md bg-muted/30 px-2 py-1.5">
            <div className="font-medium flex items-center gap-1">
              Dataproc
              <Badge variant="outline" className="text-[9px] ml-auto">
                {readyIcon(boundary.dataproc.ready)}
              </Badge>
            </div>
            <div className="text-muted-foreground mt-0.5 space-y-0.5">
              {boundary.dataproc.region && <div>Region: {boundary.dataproc.region}</div>}
              <div>Strategy: {boundary.dataproc.batchSubmissionStrategy}</div>
              {boundary.dataproc.runtimeServiceAccount && (
                <div>SA: {boundary.dataproc.runtimeServiceAccount}</div>
              )}
              <div>Responsibility: {boundary.dataproc.responsibility}</div>
            </div>
          </div>
        )}

        {boundary.bigquery && (
          <div className="rounded-md bg-muted/30 px-2 py-1.5">
            <div className="font-medium flex items-center gap-1">
              BigQuery
              <Badge variant="outline" className="text-[9px] ml-auto">
                {readyIcon(boundary.bigquery.ready)}
              </Badge>
            </div>
            <div className="text-muted-foreground mt-0.5 space-y-0.5">
              <div>Datasets: {boundary.bigquery.targetDatasets.join(", ") || "none"}</div>
              {boundary.bigquery.managedIcebergConnection && (
                <div>Iceberg: {boundary.bigquery.managedIcebergConnection}</div>
              )}
              <div>DDL: {boundary.bigquery.ddlTargets.join(", ")}</div>
              <div>Responsibility: {boundary.bigquery.responsibility}</div>
            </div>
          </div>
        )}

        {boundary.secretManager && (
          <div className="rounded-md bg-muted/30 px-2 py-1.5">
            <div className="font-medium flex items-center gap-1">
              Secret Manager
              <Badge variant="outline" className="text-[9px] ml-auto">
                {readyIcon(boundary.secretManager.ready)}
              </Badge>
            </div>
            <div className="text-muted-foreground mt-0.5 space-y-0.5">
              <div>{boundary.secretManager.runtimeAccessExpectation}</div>
              <div>Refs: {boundary.secretManager.requiredSecretRefs.length}</div>
              <div>Responsibility: {boundary.secretManager.responsibility}</div>
            </div>
          </div>
        )}

        {boundary.evidenceLog && (
          <div className="rounded-md bg-muted/30 px-2 py-1.5 col-span-2">
            <div className="font-medium flex items-center gap-1">
              Evidence / Logs
              <Badge variant="outline" className="text-[9px] ml-auto">
                {readyIcon(boundary.evidenceLog.ready)}
              </Badge>
            </div>
            <div className="text-muted-foreground mt-0.5 space-y-0.5">
              {boundary.evidenceLog.evidenceBucket && (
                <div>Bucket: {boundary.evidenceLog.evidenceBucket}</div>
              )}
              {boundary.evidenceLog.logDataset && (
                <div>Log dataset: {boundary.evidenceLog.logDataset}</div>
              )}
              <div>Responsibility: {boundary.evidenceLog.responsibility}</div>
            </div>
          </div>
        )}
      </div>

      <p className="text-[9px] text-muted-foreground italic">
        {boundary.boundaryEvidenceDisclaimer}
      </p>
    </div>
  );
}
