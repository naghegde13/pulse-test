"use client";

/**
 * Storage Scaffold Panel — SU-5 (BUG-2026-05-26-70).
 *
 * Three-button workflow for materializing the tenant's GCS bucket layout:
 *
 *   1. Preview — GET /api/v1/tenants/{tenantId}/storage-scaffold/preview
 *      Returns the planned tree (buckets + folders). Render as a tree so the
 *      operator can review before any GCS write.
 *
 *   2. Execute — POST /api/v1/tenants/{tenantId}/storage-scaffold/execute
 *      Idempotent create-if-missing of every bucket + folder. Returns
 *      per-bucket + per-folder status (CREATED | ALREADY_EXISTS | FAILED).
 *
 *   3. Status — GET /api/v1/tenants/{tenantId}/storage-scaffold/status
 *      Last execution summary (timestamps, counts, failures).
 *
 * Readiness anchor: `id="storageScaffold"` so the wizard can scroll to this
 * panel directly.
 */

import { useCallback, useEffect, useRef, useState } from "react";
import { toast } from "sonner";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { api } from "@/lib/api";

/* ── DTOs ───────────────────────────────────────────────────────────────── */

interface ScaffoldFolderPlan {
  /** Folder path inside the bucket, e.g. "bronze/raw/". */
  path: string;
  /** Human-readable purpose (optional). */
  purpose?: string;
}

interface ScaffoldBucketPlan {
  /** Bucket name (no gs:// prefix). */
  name: string;
  /** GCS location (e.g. US, us-central1). */
  location?: string;
  /** Optional storage class (e.g. STANDARD, NEARLINE). */
  storageClass?: string;
  /** Planned folder layout. */
  folders: ScaffoldFolderPlan[];
}

interface ScaffoldPreview {
  buckets: ScaffoldBucketPlan[];
  notes?: string[];
}

type ScaffoldOutcome = "CREATED" | "ALREADY_EXISTS" | "FAILED" | "SKIPPED";

interface ScaffoldFolderResult {
  path: string;
  outcome: ScaffoldOutcome;
  message?: string;
}

interface ScaffoldBucketResult {
  name: string;
  outcome: ScaffoldOutcome;
  message?: string;
  folders: ScaffoldFolderResult[];
}

interface ScaffoldExecuteResult {
  buckets: ScaffoldBucketResult[];
  startedAt?: string;
  finishedAt?: string;
  createdCount?: number;
  existingCount?: number;
  failedCount?: number;
}

interface ScaffoldStatus {
  status?: "PASS" | "FAIL" | "BLOCKED" | "PENDING" | "PROVEN";
  lastExecutedAt?: string | null;
  bucketsTotal?: number;
  bucketsReady?: number;
  foldersTotal?: number;
  foldersReady?: number;
  failures?: string[];
}

/* ── Helpers ────────────────────────────────────────────────────────────── */

function outcomeBadgeVariant(
  outcome: ScaffoldOutcome,
): "secondary" | "destructive" | "outline" {
  if (outcome === "CREATED" || outcome === "ALREADY_EXISTS") return "secondary";
  if (outcome === "FAILED") return "destructive";
  return "outline";
}

function statusBadgeVariant(
  status?: ScaffoldStatus["status"],
): "secondary" | "destructive" | "outline" {
  if (!status) return "outline";
  if (status === "PASS" || status === "PROVEN") return "secondary";
  if (status === "FAIL" || status === "BLOCKED") return "destructive";
  return "outline";
}

/* ── Panel ──────────────────────────────────────────────────────────────── */

export function StorageScaffoldPanel({ tenantId }: { tenantId: string }) {
  const [preview, setPreview] = useState<ScaffoldPreview | null>(null);
  const [previewing, setPreviewing] = useState(false);
  const [previewError, setPreviewError] = useState<string | null>(null);

  const [executeResult, setExecuteResult] =
    useState<ScaffoldExecuteResult | null>(null);
  const [executing, setExecuting] = useState(false);
  const [executeError, setExecuteError] = useState<string | null>(null);

  const [status, setStatus] = useState<ScaffoldStatus | null>(null);
  const [statusError, setStatusError] = useState<string | null>(null);
  const [statusToken, setStatusToken] = useState(0);

  const sectionRef = useRef<HTMLElement | null>(null);

  /* Auto-load status on mount + after every execute. */
  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const data = await api.get<ScaffoldStatus>(
          `/api/v1/tenants/${tenantId}/storage-scaffold/status`,
        );
        if (!cancelled) {
          setStatus(data);
          setStatusError(null);
        }
      } catch (e) {
        if (cancelled) return;
        setStatus(null);
        const msg = e instanceof Error ? e.message : "Failed to load status";
        if (!/404|not found|no such/i.test(msg)) setStatusError(msg);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [tenantId, statusToken]);

  const runPreview = useCallback(async () => {
    setPreviewing(true);
    setPreviewError(null);
    try {
      const data = await api.get<ScaffoldPreview>(
        `/api/v1/tenants/${tenantId}/storage-scaffold/preview`,
      );
      setPreview(data);
    } catch (e) {
      const msg = e instanceof Error ? e.message : "Preview failed";
      setPreviewError(msg);
      toast.error(msg);
    } finally {
      setPreviewing(false);
    }
  }, [tenantId]);

  const runExecute = useCallback(async () => {
    setExecuting(true);
    setExecuteError(null);
    try {
      const data = await api.post<ScaffoldExecuteResult>(
        `/api/v1/tenants/${tenantId}/storage-scaffold/execute`,
        {},
      );
      setExecuteResult(data);
      const failed = data.failedCount ?? 0;
      if (failed > 0) {
        toast.error(`Storage scaffold finished with ${failed} failure(s).`);
      } else {
        toast.success("Storage scaffold complete.");
      }
      // Re-fetch status so the bottom Status section reflects what just ran.
      setStatusToken((n) => n + 1);
      sectionRef.current?.scrollIntoView({
        behavior: "smooth",
        block: "start",
      });
    } catch (e) {
      const msg = e instanceof Error ? e.message : "Execute failed";
      setExecuteError(msg);
      toast.error(msg);
    } finally {
      setExecuting(false);
    }
  }, [tenantId]);

  return (
    <section
      id="storageScaffold"
      data-readiness-blocker="storageScaffold"
      ref={sectionRef}
      className="scroll-mt-6"
    >
      <Card>
        <CardHeader>
          <div className="flex items-center justify-between gap-3">
            <div>
              <CardTitle className="text-base">Storage Scaffold</CardTitle>
              <CardDescription>
                Materialize the tenant&apos;s GCS bucket layout (bronze/silver/gold
                + evidence). Preview is read-only; Execute is idempotent
                (create-if-missing).
              </CardDescription>
            </div>
            {status?.status && (
              <Badge
                variant={statusBadgeVariant(status.status)}
                className="text-[10px] uppercase"
              >
                {status.status}
              </Badge>
            )}
          </div>
        </CardHeader>

        <CardContent className="space-y-4">
          {/* Action buttons */}
          <div className="flex flex-wrap gap-2">
            <Button
              size="sm"
              variant="outline"
              onClick={runPreview}
              disabled={previewing || executing}
            >
              {previewing ? "Loading preview…" : "Preview scaffold"}
            </Button>
            <Button
              size="sm"
              onClick={runExecute}
              disabled={executing || previewing}
              title="Idempotent — re-running on an already-scaffolded tenant is safe."
            >
              {executing ? "Executing…" : "Execute scaffold"}
            </Button>
          </div>

          {/* Preview ─────────────────────────────────────────────── */}
          {previewError && (
            <p
              role="alert"
              className="rounded-md border border-destructive/30 bg-destructive/10 text-destructive text-xs px-3 py-2"
            >
              {previewError}
            </p>
          )}
          {preview && (
            <div className="space-y-2">
              <p className="text-xs font-medium">Planned layout</p>
              <ul className="space-y-2 text-xs">
                {/* SU-FINAL-3 guard: backend may return `{domainManifests}`
                    instead of `{buckets}` until the panel/backend wire
                    shapes are reconciled (out of SU-FINAL-3 scope). */}
                {(preview.buckets ?? []).map((bucket) => (
                  <li
                    key={bucket.name}
                    className="rounded-md border bg-muted/30 p-2 space-y-1"
                  >
                    <div className="flex items-center justify-between gap-2">
                      <span className="font-mono break-all">
                        gs://{bucket.name}
                      </span>
                      <span className="text-[10px] text-muted-foreground">
                        {bucket.location ?? "—"}
                        {bucket.storageClass ? ` · ${bucket.storageClass}` : ""}
                      </span>
                    </div>
                    {bucket.folders.length > 0 && (
                      <ul className="ml-3 mt-1 space-y-0.5 border-l border-border/50 pl-2">
                        {bucket.folders.map((f) => (
                          <li
                            key={f.path}
                            className="font-mono text-[11px] text-muted-foreground"
                          >
                            {f.path}
                            {f.purpose && (
                              <span className="ml-2 text-[10px] not-italic">
                                — {f.purpose}
                              </span>
                            )}
                          </li>
                        ))}
                      </ul>
                    )}
                  </li>
                ))}
              </ul>
              {preview.notes && preview.notes.length > 0 && (
                <ul className="list-disc pl-5 text-[10px] text-muted-foreground space-y-0.5">
                  {preview.notes.map((n, i) => (
                    <li key={i}>{n}</li>
                  ))}
                </ul>
              )}
            </div>
          )}

          {/* Execute results ───────────────────────────────────── */}
          {executeError && (
            <p
              role="alert"
              className="rounded-md border border-destructive/30 bg-destructive/10 text-destructive text-xs px-3 py-2"
            >
              {executeError}
            </p>
          )}
          {executeResult && (
            <div className="space-y-2">
              <div className="flex items-center justify-between gap-2">
                <p className="text-xs font-medium">Execute results</p>
                <div className="flex gap-1.5 text-[10px]">
                  <Badge variant="secondary">
                    created {executeResult.createdCount ?? 0}
                  </Badge>
                  <Badge variant="outline">
                    existing {executeResult.existingCount ?? 0}
                  </Badge>
                  <Badge
                    variant={
                      (executeResult.failedCount ?? 0) > 0
                        ? "destructive"
                        : "outline"
                    }
                  >
                    failed {executeResult.failedCount ?? 0}
                  </Badge>
                </div>
              </div>
              <ul className="space-y-2 text-xs">
                {executeResult.buckets.map((bucket) => (
                  <li
                    key={bucket.name}
                    className="rounded-md border bg-muted/30 p-2 space-y-1"
                  >
                    <div className="flex items-center justify-between gap-2">
                      <span className="font-mono break-all">
                        gs://{bucket.name}
                      </span>
                      <Badge
                        variant={outcomeBadgeVariant(bucket.outcome)}
                        className="text-[10px]"
                      >
                        {bucket.outcome}
                      </Badge>
                    </div>
                    {bucket.message && (
                      <p className="text-[10px] text-muted-foreground italic">
                        {bucket.message}
                      </p>
                    )}
                    {bucket.folders.length > 0 && (
                      <ul className="ml-3 mt-1 space-y-0.5 border-l border-border/50 pl-2">
                        {bucket.folders.map((f) => (
                          <li
                            key={f.path}
                            className="flex items-center justify-between font-mono text-[11px]"
                          >
                            <span className="text-muted-foreground">
                              {f.path}
                            </span>
                            <Badge
                              variant={outcomeBadgeVariant(f.outcome)}
                              className="text-[9px]"
                            >
                              {f.outcome}
                            </Badge>
                          </li>
                        ))}
                      </ul>
                    )}
                  </li>
                ))}
              </ul>
              {executeResult.startedAt && executeResult.finishedAt && (
                <p className="text-[10px] text-muted-foreground">
                  {new Date(executeResult.startedAt).toLocaleString()} —{" "}
                  {new Date(executeResult.finishedAt).toLocaleString()}
                </p>
              )}
            </div>
          )}

          {/* Status ───────────────────────────────────────────────── */}
          <div className="space-y-1.5 rounded-md border bg-muted/20 p-3 text-[11px]">
            <p className="text-xs font-medium">Status</p>
            {statusError && (
              <p className="text-destructive">{statusError}</p>
            )}
            {status ? (
              <>
                <p>
                  <span className="text-muted-foreground">Last executed:</span>{" "}
                  <span className="font-mono">
                    {status.lastExecutedAt
                      ? new Date(status.lastExecutedAt).toLocaleString()
                      : "—"}
                  </span>
                </p>
                <p>
                  <span className="text-muted-foreground">Buckets:</span>{" "}
                  <span className="font-mono">
                    {status.bucketsReady ?? 0} / {status.bucketsTotal ?? 0}
                  </span>{" "}
                  <span className="text-muted-foreground">· Folders:</span>{" "}
                  <span className="font-mono">
                    {status.foldersReady ?? 0} / {status.foldersTotal ?? 0}
                  </span>
                </p>
                {status.failures && status.failures.length > 0 && (
                  <div>
                    <p className="font-medium text-destructive mt-1">
                      Failures
                    </p>
                    <ul className="list-disc pl-5 text-destructive space-y-0.5">
                      {status.failures.map((f, i) => (
                        <li key={i} className="font-mono text-[10px]">
                          {f}
                        </li>
                      ))}
                    </ul>
                  </div>
                )}
              </>
            ) : (
              <p className="text-muted-foreground">
                No scaffold execution recorded yet.
              </p>
            )}
          </div>
        </CardContent>
      </Card>
    </section>
  );
}
