"use client";

import { useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { useAuth } from "@/contexts/auth-context";
import { useTenant } from "@/contexts/tenant-context";
import { useCobolDiscovery } from "@/contexts/ebcdic-discovery-context";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Separator } from "@/components/ui/separator";
import { Textarea } from "@/components/ui/textarea";
import { Badge } from "@/components/ui/badge";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";

export function DiscoveryWorkspace() {
  const { user } = useAuth();
  const { currentTenant } = useTenant();
  const {
    session,
    messages,
    artifacts,
    activeRun,
    progressEvents,
    profiles,
    copybookText,
    optionOverridesText,
    initializing,
    busy,
    loopActive,
    initialize,
    setOptionOverridesText,
    sendMessage,
    uploadCopybook,
    uploadDataFile,
    runPreview,
    runProfile,
    cancelRun,
    saveProfile,
    updateProfile,
    loadProfiles,
    reprofile,
  } = useCobolDiscovery();
  const chatScrollRef = useRef<HTMLDivElement | null>(null);
  const chatLogTailRef = useRef<HTMLDivElement | null>(null);
  const eventLogTailRef = useRef<HTMLDivElement | null>(null);
  const [chatInput, setChatInput] = useState("");
  const [profileName, setProfileName] = useState("");
  const [profileDescription, setProfileDescription] = useState("");
  const [editingProfileId, setEditingProfileId] = useState<string | null>(null);
  const [editingProfileName, setEditingProfileName] = useState("");
  const [editingProfileDescription, setEditingProfileDescription] = useState("");
  const [editingCobrixOptions, setEditingCobrixOptions] = useState("{}");
  const [editingFlattenSpec, setEditingFlattenSpec] = useState("{}");
  const [previewExpanded, setPreviewExpanded] = useState(false);

  useEffect(() => {
    if (!currentTenant || !user) return;
    if (session && session.tenantId === currentTenant.id) return;
    initialize(currentTenant.id, user.id).catch(() => undefined);
  }, [currentTenant, initialize, session, user]);

  useEffect(() => {
    if (!currentTenant) return;
    loadProfiles(currentTenant.id).catch(() => undefined);
  }, [currentTenant, loadProfiles]);

  const parsedOverrides = useMemo(() => {
    try {
      return JSON.parse(optionOverridesText) as Record<string, unknown>;
    } catch {
      return null;
    }
  }, [optionOverridesText]);

  const previewHeaders = useMemo(() => {
    const fields = (activeRun?.resultSchemaSnapshot?.fields ?? []) as Array<{ name?: string }>;
    if (fields.length > 0) return fields.map((field) => field.name ?? "");
    const first = activeRun?.previewRows?.[0] ?? {};
    return Object.keys(first);
  }, [activeRun]);

  const copybookOutline = useMemo(() => parseCopybookOutline(copybookText), [copybookText]);
  const parsedEditingCobrixOptions = useMemo(() => {
    try {
      return JSON.parse(editingCobrixOptions) as Record<string, unknown>;
    } catch {
      return null;
    }
  }, [editingCobrixOptions]);
  const parsedEditingFlattenSpec = useMemo(() => {
    try {
      return JSON.parse(editingFlattenSpec) as Record<string, unknown>;
    } catch {
      return null;
    }
  }, [editingFlattenSpec]);
  const anomalyWarnings = useMemo(() => {
    const raw = activeRun?.anomalySummary?.warnings;
    if (Array.isArray(raw)) {
      return raw.map((item) => String(item));
    }
    if (typeof raw === "string" && raw.trim().length > 0) {
      return [raw];
    }
    return [];
  }, [activeRun?.anomalySummary]);
  useLayoutEffect(() => {
    const scrollToLatest = () => {
      if (chatScrollRef.current) {
        chatScrollRef.current.scrollTop = chatScrollRef.current.scrollHeight;
      }
      chatLogTailRef.current?.scrollIntoView({ behavior: "auto", block: "end" });
    };
    const raf = window.requestAnimationFrame(scrollToLatest);
    const timeout = window.setTimeout(scrollToLatest, 100);
    return () => {
      window.cancelAnimationFrame(raf);
      window.clearTimeout(timeout);
    };
  }, [messages]);

  useEffect(() => {
    eventLogTailRef.current?.scrollIntoView({ behavior: "smooth", block: "end" });
  }, [progressEvents, activeRun?.eventLog]);

  if (!currentTenant || !user) {
    return <p className="text-sm text-muted-foreground">Loading tenant context…</p>;
  }

  if (initializing || !session) {
    return <p className="text-sm text-muted-foreground">Creating discovery session…</p>;
  }

  return (
    <div className="space-y-6">
      <div className="space-y-1">
        <div className="flex items-center gap-3">
          <h1 className="text-2xl font-semibold">EBCDIC Discovery</h1>
          {(() => {
            const sp = activeRun?.samplePolicy as Record<string, unknown> | undefined;
            const iter = typeof sp?.assistantLoopIteration === "number" ? sp.assistantLoopIteration : null;
            const max = typeof sp?.assistantLoopMaxIterations === "number" ? sp.assistantLoopMaxIterations : null;
            if (loopActive) return (
              <Badge variant="default" className="animate-pulse">
                Refining{iter && max ? ` (iteration ${iter} of ${max})` : "..."}
              </Badge>
            );
            if (busy) return <Badge variant="default" className="animate-pulse">Processing...</Badge>;
            return null;
          })()}
          {typeof activeRun?.confidenceScore === "number" && !loopActive && !busy && (
            <Badge variant="secondary">Confidence {activeRun.confidenceScore.toFixed(1)}</Badge>
          )}
        </div>
        <p className="text-xs text-muted-foreground">
          Session: <code className="font-mono select-all">{session.id}</code>
        </p>
      </div>

      <div className="grid items-start gap-6 xl:grid-cols-[minmax(320px,360px)_minmax(420px,520px)_minmax(680px,1fr)]">
        <Card className="min-h-[680px] min-w-0 overflow-hidden xl:sticky xl:top-6">
          <CardHeader className="pb-3">
            <CardTitle className="text-base">Discovery Chat</CardTitle>
          </CardHeader>
          <CardContent className="flex h-[calc(100vh-14rem)] min-h-[620px] min-w-0 flex-col gap-3 overflow-hidden">
            <div className="min-h-0 flex-1 overflow-hidden rounded-md border bg-background">
              <div
                ref={chatScrollRef}
                className="flex h-full flex-col overflow-y-auto overscroll-contain p-3 pr-2"
              >
                <div className="space-y-3">
                  {messages.map((message) => (
                    <div
                      key={message.id}
                      className={`rounded-md px-3 py-2 text-sm ${
                        message.role === "ASSISTANT"
                          ? "bg-muted"
                          : "bg-primary text-primary-foreground"
                      }`}
                    >
                      <p className="mb-1 text-[10px] font-mono opacity-80">{message.role}</p>
                      <p className="whitespace-pre-wrap break-words">{message.content}</p>
                    </div>
                  ))}
                  {(busy || loopActive) && (
                    <div className="rounded-md bg-muted px-3 py-2 text-sm">
                      <p className="mb-1 text-[10px] font-mono opacity-80">ASSISTANT</p>
                      <p className="text-muted-foreground animate-pulse">
                        {loopActive && !busy
                          ? (() => {
                              const sp = activeRun?.samplePolicy as Record<string, unknown> | undefined;
                              const iter = typeof sp?.assistantLoopIteration === "number" && sp.assistantLoopIteration > 0 ? sp.assistantLoopIteration : null;
                              const max = typeof sp?.assistantLoopMaxIterations === "number" && sp.assistantLoopMaxIterations > 0 ? sp.assistantLoopMaxIterations : null;
                              return iter && max ? `Refining (iteration ${iter} of ${max})...` : "Refining...";
                            })()
                          : "Analyzing and applying fixes..."}
                      </p>
                    </div>
                  )}
                  <div ref={chatLogTailRef} />
                </div>
              </div>
            </div>
            <Textarea
              value={chatInput}
              onChange={(e) => setChatInput(e.target.value)}
              placeholder="Ask the Discovery agent what to adjust next…"
              className="min-h-28"
            />
            <Button
              disabled={busy || !chatInput.trim()}
              onClick={async () => {
                const msg = chatInput;
                setChatInput("");
                await sendMessage(currentTenant.id, msg);
              }}
            >
              Send
            </Button>
          </CardContent>
        </Card>

        <div className="min-w-0 space-y-6">
          <Card className="min-w-0">
            <CardHeader className="pb-3">
              <CardTitle className="text-base">Uploads & Options</CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="space-y-2">
                <Label htmlFor="copybook-upload">Copybook</Label>
                <Input
                  id="copybook-upload"
                  type="file"
                  accept=".cpy,.cob,.txt"
                  onChange={async (e) => {
                    const file = e.target.files?.[0];
                    if (file) await uploadCopybook(currentTenant.id, file);
                    e.target.value = "";
                  }}
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="data-upload">EBCDIC Data File</Label>
                <Input
                  id="data-upload"
                  type="file"
                  onChange={async (e) => {
                    const file = e.target.files?.[0];
                    if (file) await uploadDataFile(currentTenant.id, file);
                    e.target.value = "";
                  }}
                />
              </div>
              <div className="space-y-2">
                <Label>Current Transient Artifacts</Label>
                <div className="space-y-2">
                  {artifacts.map((artifact) => (
                    <div key={artifact.id} className="flex items-center justify-between rounded-md border px-3 py-2 text-xs">
                      <div>
                        <p className="font-medium">{artifact.originalFilename}</p>
                        <p className="text-muted-foreground">{artifact.artifactType}</p>
                      </div>
                      <Badge variant="outline">{artifact.cleanupStatus}</Badge>
                    </div>
                  ))}
                  {artifacts.length === 0 && (
                    <p className="text-xs text-muted-foreground">No transient artifacts uploaded yet.</p>
                  )}
                </div>
              </div>
              <Separator />
              <div className="space-y-2">
                <Label>Cobrix Option Overrides (JSON)</Label>
                <Textarea
                  value={optionOverridesText}
                  onChange={(e) => setOptionOverridesText(e.target.value)}
                  className="min-h-48 font-mono text-xs"
                />
                {parsedOverrides === null && (
                  <p className="text-xs text-destructive">Fix the JSON before running preview.</p>
                )}
              </div>
              <div className="flex flex-wrap gap-2">
                <Button
                  disabled={busy || parsedOverrides === null}
                  onClick={() => runPreview(currentTenant.id, parsedOverrides ?? {}, 20)}
                >
                  Run Preview
                </Button>
                <Button
                  variant="outline"
                  disabled={busy || parsedOverrides === null}
                  onClick={() => runProfile(currentTenant.id, parsedOverrides ?? {}, 50)}
                >
                  Run Full Profile
                </Button>
              </div>
            </CardContent>
          </Card>

          <Card className="min-w-0">
            <CardHeader className="pb-3">
              <CardTitle className="text-base">Copybook Viewer</CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              <ScrollArea className="h-[220px] rounded-md border">
                <div className="space-y-1 p-3 text-xs">
                  {copybookOutline.length > 0 ? copybookOutline.map((node) => (
                    <div
                      key={`${node.lineNumber}-${node.name}`}
                      className="rounded-md border bg-background px-2 py-1"
                      style={{ marginLeft: `${Math.max(0, node.level - 1) * 8}px` }}
                    >
                      <div className="flex items-center gap-2">
                        <span className="font-mono text-muted-foreground">{String(node.level).padStart(2, "0")}</span>
                        <span className="font-mono font-medium">{node.name}</span>
                      </div>
                      {node.clauses.length > 0 && (
                        <div className="mt-1 flex flex-wrap gap-1">
                          {node.clauses.map((clause) => (
                            <Badge key={`${node.name}-${clause}`} variant="outline" className="text-[10px]">
                              {clause}
                            </Badge>
                          ))}
                        </div>
                      )}
                    </div>
                  )) : (
                    <p className="text-muted-foreground">Upload a copybook to inspect its structure here.</p>
                  )}
                </div>
              </ScrollArea>
              <details className="rounded-md border">
                <summary className="cursor-pointer px-3 py-2 text-xs font-medium">
                  Raw Copybook Text
                </summary>
                <ScrollArea className="h-[120px] border-t">
                  <pre className="whitespace-pre-wrap p-3 font-mono text-xs">
                    {copybookText || "No copybook uploaded yet."}
                  </pre>
                </ScrollArea>
              </details>
            </CardContent>
          </Card>

          <Card className="min-w-0">
            <CardHeader className="pb-3">
              <CardTitle className="text-base">Mapping Visualization</CardTitle>
            </CardHeader>
            <CardContent>
              <ScrollArea className="h-[220px] rounded-md border p-3">
                <div className="space-y-2">
                  {(activeRun?.mappingSpec ?? []).map((mapping, index) => (
                    <div key={`${mapping.outputColumn ?? index}`} className="grid grid-cols-[1fr_auto_1fr] items-center gap-2 rounded-md border px-2 py-2 text-xs">
                      <div>
                        <p className="text-[10px] uppercase tracking-wide text-muted-foreground">Source</p>
                        <p className="font-mono">{String(mapping.sourcePath ?? "")}</p>
                      </div>
                      <div className="text-muted-foreground">→</div>
                      <div>
                        <p className="text-[10px] uppercase tracking-wide text-muted-foreground">Output</p>
                        <p className="font-mono">{String(mapping.outputColumn ?? "")}</p>
                        <p className="text-muted-foreground">{String(mapping.strategy ?? "")}</p>
                      </div>
                    </div>
                  ))}
                  {(activeRun?.mappingSpec?.length ?? 0) === 0 && (
                    <p className="text-xs text-muted-foreground">
                      Run a preview to generate the flattening map.
                    </p>
                  )}
                </div>
              </ScrollArea>
            </CardContent>
          </Card>
        </div>

        <div className="min-w-0 space-y-6">
          <Card className="min-w-0">
            <CardHeader className="pb-3">
              <CardTitle className="text-base">Flattened Output Preview</CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              <div className="flex flex-wrap items-center gap-2 text-xs">
                {activeRun?.previewRows?.length ? (
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={() => setPreviewExpanded(true)}
                  >
                    Expand Preview
                  </Button>
                ) : null}
                {activeRun && ["QUEUED", "RUNNING"].includes(activeRun.status) && (
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={busy}
                    onClick={() => cancelRun(currentTenant.id, activeRun.id)}
                  >
                    Cancel Run
                  </Button>
                )}
              </div>
              <PreviewGrid
                headers={previewHeaders}
                rows={activeRun?.previewRows ?? []}
                heightClassName="h-[420px]"
                headerCellClassName="min-w-[200px] px-3 py-2 text-left font-mono text-[11px] whitespace-nowrap"
                cellClassName="min-w-[200px] max-w-[320px] px-3 py-2 font-mono text-xs whitespace-pre-wrap break-words"
                emptyMessage="No preview rows yet. Upload files and run preview."
              />
            </CardContent>
          </Card>
          <Dialog open={previewExpanded} onOpenChange={setPreviewExpanded}>
            <DialogContent className="h-[calc(100vh-2rem)] w-[calc(100vw-2rem)] max-w-none gap-0 overflow-hidden p-0 sm:max-w-[calc(100vw-2rem)]">
              <div className="flex h-full min-h-0 flex-col overflow-hidden">
                <DialogHeader className="shrink-0 border-b px-6 py-4">
                  <div className="flex items-start justify-between gap-4 pr-8">
                    <div className="space-y-1">
                      <DialogTitle>Flattened Output Preview</DialogTitle>
                      <p className="text-sm text-muted-foreground">
                        Use the larger workspace to inspect every row and column before saving a profile.
                      </p>
                    </div>
                    <div className="shrink-0 text-right text-xs text-muted-foreground">
                      <p>{activeRun?.previewRows?.length ?? 0} preview rows</p>
                      <p>{previewHeaders.length} columns</p>
                    </div>
                  </div>
                </DialogHeader>
                <div className="min-h-0 flex-1 overflow-auto p-4 sm:p-6">
                  <table className="min-w-max border-separate border-spacing-0 text-xs">
                    <thead className="bg-muted/95 backdrop-blur-sm">
                      <tr>
                        {previewHeaders.map((header) => (
                          <th
                            key={header}
                            className="sticky top-0 z-10 min-w-[200px] border-b bg-muted/95 px-4 py-3 text-left font-mono text-xs whitespace-nowrap"
                          >
                            {header}
                          </th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {(activeRun?.previewRows ?? []).map((row, index) => (
                        <tr key={index} className="align-top even:bg-muted/20">
                          {previewHeaders.map((header) => (
                            <td key={`${index}-${header}`} className="min-w-[200px] max-w-[420px] border-b px-4 py-3 font-mono text-xs whitespace-pre-wrap break-words">
                              {String(row[header] ?? "")}
                            </td>
                          ))}
                        </tr>
                      ))}
                      {(activeRun?.previewRows ?? []).length === 0 && (
                        <tr>
                          <td className="p-3 text-muted-foreground" colSpan={Math.max(1, previewHeaders.length)}>
                            No preview rows available for this run.
                          </td>
                        </tr>
                      )}
                    </tbody>
                  </table>
                </div>
              </div>
            </DialogContent>
          </Dialog>

          <Card className="min-w-0">
            <CardHeader className="pb-3">
              <CardTitle className="text-base">Profiling & Run Events</CardTitle>
            </CardHeader>
            <CardContent className="space-y-4 text-sm">
              <div className="grid gap-3 sm:grid-cols-3">
                <Metric
                  label="Rows"
                  value={String((activeRun?.profilingSummary?.rowCount as number | undefined) ?? 0)}
                />
                <Metric
                  label="Columns"
                  value={String((activeRun?.profilingSummary?.columnCount as number | undefined) ?? 0)}
                />
                <Metric
                  label="Preview Rows"
                  value={String((activeRun?.profilingSummary?.previewRowCount as number | undefined) ?? 0)}
                />
              </div>
              <div className="space-y-2">
                <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">Warnings</p>
                <ul className="space-y-1 text-xs">
                  {anomalyWarnings.map((warning) => (
                    <li key={warning} className="rounded-md border px-2 py-1">
                      {warning}
                    </li>
                  ))}
                  {(anomalyWarnings.length === 0) && (
                    <li className="text-muted-foreground">No anomalies recorded.</li>
                  )}
                </ul>
              </div>
              <div className="space-y-2">
                <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">Event Log</p>
                <ScrollArea className="h-[260px] rounded-md border p-2">
                  <div className="space-y-2 text-xs">
                    {(progressEvents.length > 0 ? progressEvents : (activeRun?.eventLog ?? [])).map((event, index) => (
                      <div key={index} className="rounded-md border px-2 py-1">
                        <p className="font-medium">{String(event.message ?? "")}</p>
                        {Boolean("detail" in event && event.detail) && (
                          <pre className="mt-1 whitespace-pre-wrap text-[10px] text-muted-foreground">
                            {String(JSON.stringify(event.detail, null, 2))}
                          </pre>
                        )}
                        <p className="text-muted-foreground">{String(event.timestamp ?? "")}</p>
                      </div>
                    ))}
                    <div ref={eventLogTailRef} />
                  </div>
                </ScrollArea>
              </div>
            </CardContent>
          </Card>

          <Card className="min-w-0">
            <CardHeader className="pb-3">
              <CardTitle className="text-base">Profile Manager</CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="space-y-2">
                <Label>Name</Label>
                <Input value={profileName} onChange={(e) => setProfileName(e.target.value)} placeholder="loan_master_cp037_vb" />
              </div>
              <div className="space-y-2">
                <Label>Description</Label>
                <Textarea
                  value={profileDescription}
                  onChange={(e) => setProfileDescription(e.target.value)}
                  placeholder="Brief notes about the copybook and chosen Cobrix framing."
                  className="min-h-24"
                />
              </div>
              <Button
                disabled={busy || !activeRun || activeRun.status !== "COMPLETED" || !profileName.trim()}
                onClick={() => saveProfile(currentTenant.id, {
                  name: profileName.trim(),
                  description: profileDescription.trim(),
                  userId: user.id,
                })}
              >
                Save COBOL Profile
              </Button>
              <Separator />
              <div className="space-y-2">
                {profiles.map((profile) => (
                  <div key={profile.id} className="rounded-md border px-3 py-2">
                    <div className="flex items-start justify-between gap-3">
                      <div>
                        <p className="font-medium">{profile.name}</p>
                        <p className="text-xs text-muted-foreground">{profile.description || "No description"}</p>
                      </div>
                      <div className="flex gap-2">
                        <Button
                          variant="outline"
                          size="sm"
                          disabled={busy}
                          onClick={() => {
                            setEditingProfileId(profile.id);
                            setEditingProfileName(profile.name);
                            setEditingProfileDescription(profile.description || "");
                            setEditingCobrixOptions(JSON.stringify(profile.cobrixOptions ?? {}, null, 2));
                            setEditingFlattenSpec(JSON.stringify(profile.flattenSpec ?? {}, null, 2));
                          }}
                        >
                          Edit
                        </Button>
                        <Button
                          variant="outline"
                          size="sm"
                          disabled={busy}
                          onClick={() => reprofile(currentTenant.id, profile.id, user.id)}
                        >
                          Reprofile
                        </Button>
                      </div>
                    </div>
                  </div>
                ))}
                {profiles.length === 0 && (
                  <p className="text-xs text-muted-foreground">No tenant COBOL profiles saved yet.</p>
                )}
              </div>
              {editingProfileId && (
                <>
                  <Separator />
                  <div className="space-y-3 rounded-md border p-3">
                    <div className="flex items-center justify-between">
                      <p className="text-sm font-medium">Edit Saved Profile</p>
                      <Button variant="ghost" size="sm" onClick={() => setEditingProfileId(null)}>
                        Close
                      </Button>
                    </div>
                    <div className="space-y-2">
                      <Label>Name</Label>
                      <Input value={editingProfileName} onChange={(e) => setEditingProfileName(e.target.value)} />
                    </div>
                    <div className="space-y-2">
                      <Label>Description</Label>
                      <Textarea
                        value={editingProfileDescription}
                        onChange={(e) => setEditingProfileDescription(e.target.value)}
                        className="min-h-20"
                      />
                    </div>
                    <div className="space-y-2">
                      <Label>Cobrix Options (JSON)</Label>
                      <Textarea
                        value={editingCobrixOptions}
                        onChange={(e) => setEditingCobrixOptions(e.target.value)}
                        className="min-h-32 font-mono text-xs"
                      />
                    </div>
                    <div className="space-y-2">
                      <Label>Flatten Spec (JSON)</Label>
                      <Textarea
                        value={editingFlattenSpec}
                        onChange={(e) => setEditingFlattenSpec(e.target.value)}
                        className="min-h-32 font-mono text-xs"
                      />
                    </div>
                    <Button
                      disabled={busy || parsedEditingCobrixOptions === null || parsedEditingFlattenSpec === null}
                      onClick={async () => {
                        await updateProfile(currentTenant.id, editingProfileId, {
                          name: editingProfileName.trim(),
                          description: editingProfileDescription.trim(),
                          cobrixOptions: parsedEditingCobrixOptions ?? {},
                          flattenSpec: parsedEditingFlattenSpec ?? {},
                        });
                        setEditingProfileId(null);
                      }}
                    >
                      Save Profile Changes
                    </Button>
                    {(parsedEditingCobrixOptions === null || parsedEditingFlattenSpec === null) && (
                      <p className="text-xs text-destructive">Fix the JSON before saving profile changes.</p>
                    )}
                  </div>
                </>
              )}
            </CardContent>
          </Card>
        </div>
      </div>
    </div>
  );
}

function PreviewGrid({
  headers,
  rows,
  heightClassName,
  headerCellClassName,
  cellClassName,
  emptyMessage,
}: {
  headers: string[];
  rows: Array<Record<string, unknown>>;
  heightClassName: string;
  headerCellClassName: string;
  cellClassName: string;
  emptyMessage: string;
}) {
  return (
    <div className={`min-h-0 overflow-auto rounded-md border bg-background ${heightClassName}`}>
      <div className="h-full min-w-0 overflow-auto overscroll-contain">
        <table className="min-w-max border-separate border-spacing-0 text-xs">
          <thead className="bg-muted/95 backdrop-blur-sm">
            <tr>
              {headers.map((header) => (
                <th
                  key={header}
                  className={`${headerCellClassName} sticky top-0 z-10 border-b bg-muted/95`}
                >
                  {header}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((row, index) => (
              <tr key={`${index}-${headers.join("|")}`} className="align-top even:bg-muted/20">
                {headers.map((header) => (
                  <td key={`${index}-${header}`} className={`${cellClassName} border-b`}>
                    {String(row[header] ?? "")}
                  </td>
                ))}
              </tr>
            ))}
            {rows.length === 0 && (
              <tr>
                <td className="p-3 text-muted-foreground" colSpan={Math.max(1, headers.length)}>
                  {emptyMessage}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-md border p-3">
      <p className="text-[10px] uppercase tracking-wide text-muted-foreground">{label}</p>
      <p className="mt-1 text-lg font-semibold">{value}</p>
    </div>
  );
}

interface CopybookNode {
  lineNumber: number;
  level: number;
  name: string;
  clauses: string[];
}

function parseCopybookOutline(copybookText: string): CopybookNode[] {
  const nodes: CopybookNode[] = [];
  const pattern = /^\s*(\d{2})\s+([A-Z0-9-]+)(.*)$/i;
  const lines = copybookText.split(/\r?\n/);
  lines.forEach((line, index) => {
    const match = line.match(pattern);
    if (!match) return;
    const level = Number(match[1]);
    const name = match[2];
    const tail = match[3] ?? "";
    const clauses: string[] = [];
    if (/REDEFINES/i.test(tail)) clauses.push("REDEFINES");
    if (/OCCURS/i.test(tail)) clauses.push(/DEPENDING ON/i.test(tail) ? "OCCURS DEPENDING ON" : "OCCURS");
    if (/PIC\s+/i.test(tail)) clauses.push("PIC");
    if (/COMP(?:-[12359])?/i.test(tail)) clauses.push("COMP");
    nodes.push({
      lineNumber: index + 1,
      level,
      name,
      clauses,
    });
  });
  return nodes;
}
