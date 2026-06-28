"use client";

import { useCallback, useEffect, useState, useTransition } from "react";
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
} from "@/components/ui/dialog";
import { DqRecommendationsPanel } from "./dq-recommendations";
import { useTenant } from "@/contexts/tenant-context";
import { useDqReadiness } from "@/hooks/use-dq-readiness";
import { api } from "@/lib/api";
import dynamic from "next/dynamic";

const MonacoEditor = dynamic(() => import("@monaco-editor/react").then((m) => m.default), {
  ssr: false,
  loading: () => <div className="h-[400px] bg-muted animate-pulse rounded" />,
});

interface GenerationRun {
  id: string;
  status: string;
  triggerType: string;
  startedAt?: string;
  completedAt?: string;
  metadata?: Record<string, unknown>;
  createdAt: string;
}

interface GeneratedArtifact {
  id: string;
  filePath: string;
  fileType: string;
  content: string;
  contentHash: string;
  templateName?: string;
  manuallyModified: boolean;
  createdAt: string;
}

const FILE_TYPE_LANG: Record<string, string> = {
  AIRFLOW_DAG: "python",
  PYSPARK_JOB: "python",
  DBT_MODEL: "sql",
  DBT_SOURCE: "yaml",
  CONFIG_YAML: "yaml",
  REQUIREMENTS_TXT: "plaintext",
  TEST_SCRIPT: "python",
  DOCKERFILE: "dockerfile",
};

const FILE_TYPE_ICON: Record<string, string> = {
  AIRFLOW_DAG: "DAG",
  PYSPARK_JOB: "PY",
  DBT_MODEL: "SQL",
  DBT_SOURCE: "YML",
  CONFIG_YAML: "CFG",
  REQUIREMENTS_TXT: "REQ",
};

interface CodeEditorPanelProps {
  pipelineId: string;
  versionId: string;
  editable: boolean;
}

export function CodeEditorPanel({ pipelineId, versionId, editable }: CodeEditorPanelProps) {
  const { currentTenant } = useTenant();
  const [runs, setRuns] = useState<GenerationRun[]>([]);
  const [artifacts, setArtifacts] = useState<GeneratedArtifact[]>([]);
  const [selectedFile, setSelectedFile] = useState<GeneratedArtifact | null>(null);
  const [generating, setGenerating] = useState(false);
  const [editedContent, setEditedContent] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [, startTransition] = useTransition();
  const [showDqGate, setShowDqGate] = useState(false);
  const dq = useDqReadiness(versionId);

  const loadArtifacts = useCallback(async (runId: string) => {
    try {
      const data = await api.get<GeneratedArtifact[]>(
        `/api/v1/generations/${runId}/artifacts`
      );
      setArtifacts(data);
      if (data.length > 0) setSelectedFile(data[0]);
    } catch {
      setArtifacts([]);
    }
  }, []);

  useEffect(() => {
    startTransition(async () => {
      try {
        const data = await api.get<GenerationRun[]>(
          `/api/v1/versions/${versionId}/generations`
        );
        setRuns(data);
        if (data.length > 0 && data[0].status === "COMPLETED") {
          await loadArtifacts(data[0].id);
        }
      } catch {
        setRuns([]);
      }
    });
  }, [loadArtifacts, versionId]);

  const proceedGenerate = async () => {
    if (!currentTenant) return;
    setGenerating(true);
    setShowDqGate(false);
    try {
      const run = await api.post<GenerationRun>(`/api/v1/versions/${versionId}/generate`, {
        pipelineId,
        tenantId: currentTenant.id,
        userId: "current-user",
      });
      setRuns((prev) => [run, ...prev]);
      if (run.status === "COMPLETED") {
        await loadArtifacts(run.id);
      }
    } finally {
      setGenerating(false);
    }
  };

  const handleGenerate = async () => {
    if (!currentTenant) return;
    // Run DQ evaluation before code generation
    if (dq.score === null) {
      await dq.evaluate();
    }
    // After evaluation, check the score
    const score = dq.score;
    if (score !== null && score < 50) {
      // Score < 50: block and show recommendations
      setShowDqGate(true);
      return;
    }
    if (score !== null && score < 80) {
      // Score 50-79: show recommendations with continue option
      setShowDqGate(true);
      return;
    }
    // Score >= 80 or evaluation failed (let them proceed)
    await proceedGenerate();
  };

  const handleSave = async () => {
    if (!selectedFile || editedContent === null) return;
    setSaving(true);
    try {
      const updated = await api.put<GeneratedArtifact>(
        `/api/v1/artifacts/${selectedFile.id}/content`,
        { content: editedContent }
      );
      setSelectedFile(updated);
      setArtifacts((prev) =>
        prev.map((a) => (a.id === updated.id ? updated : a))
      );
      setEditedContent(null);
    } finally {
      setSaving(false);
    }
  };

  const groupedFiles = artifacts.reduce<Record<string, GeneratedArtifact[]>>((acc, a) => {
    const dir = a.filePath.includes("/") ? a.filePath.substring(0, a.filePath.lastIndexOf("/")) : ".";
    if (!acc[dir]) acc[dir] = [];
    acc[dir].push(a);
    return acc;
  }, {});

  const hasArtifacts = artifacts.length > 0;

  return (
    <Card className="col-span-3">
      <CardHeader className="pb-2">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <CardTitle className="text-sm font-medium">Generated Code</CardTitle>
            {runs.length > 0 && (
              <Badge variant="secondary" className="text-[10px]">
                {runs[0].status}
              </Badge>
            )}
            {hasArtifacts && (
              <Badge variant="outline" className="text-[10px]">
                {artifacts.length} files
              </Badge>
            )}
            {hasArtifacts && (
              <Badge
                variant="outline"
                className="text-[10px] bg-blue-500/10 text-blue-700 border-blue-500/20"
                title="Generated artifacts are static — they must be packaged, deployed, and executed to produce runtime proof"
              >
                static artifact
              </Badge>
            )}
          </div>
          {editable && (
            <Button
              size="sm"
              className="h-7 text-xs"
              onClick={handleGenerate}
              disabled={generating}
            >
              {generating ? "Generating..." : hasArtifacts ? "Re-generate" : "Generate Code"}
            </Button>
          )}
        </div>
      </CardHeader>
      <CardContent>
        {/* DQ Gate Dialog */}
        <Dialog open={showDqGate} onOpenChange={setShowDqGate}>
          <DialogContent className="sm:max-w-[700px] max-h-[80vh] flex flex-col">
            <DialogHeader>
              <DialogTitle>
                {dq.score !== null && dq.score < 50
                  ? "DQ Readiness — Action Required"
                  : "DQ Readiness — Review Recommendations"}
              </DialogTitle>
              <DialogDescription>
                {dq.score !== null && dq.score < 50
                  ? "Your pipeline's data quality score is below the minimum threshold. Please review and accept the AI recommendations before generating code."
                  : "Your pipeline scored below 80. Review the suggestions below, then continue."}
              </DialogDescription>
            </DialogHeader>
            <div className="flex-1 min-h-0 overflow-y-auto">
              <DqRecommendationsPanel
                versionId={versionId}
                recommendations={dq.recommendations}
                reasoning={dq.reasoning}
                onAccept={() => {
                  setShowDqGate(false);
                  proceedGenerate();
                }}
              />
            </div>
            <DialogFooter>
              <Button variant="outline" size="sm" onClick={() => setShowDqGate(false)}>
                Cancel
              </Button>
              {dq.score !== null && dq.score >= 50 && (
                <Button size="sm" onClick={proceedGenerate} disabled={generating}>
                  Continue Anyway
                </Button>
              )}
            </DialogFooter>
          </DialogContent>
        </Dialog>

        {!hasArtifacts ? (
          <div className="text-center py-8 text-muted-foreground">
            <p className="text-sm">No code generated yet.</p>
            {editable && (
              <p className="text-xs mt-1">
                Add steps to your composition, then click &quot;Generate Code&quot; to produce
                Airflow DAGs, PySpark jobs, and dbt models.
              </p>
            )}
          </div>
        ) : (
          <>
          <p className="text-[10px] text-muted-foreground mb-2">
            Generated artifacts are static code — not runtime proof. Build a package,
            deploy to a target environment, and execute to obtain runtime evidence.
          </p>
          <div className="flex gap-3 min-h-[450px]">
            {/* File tree */}
            <div className="w-56 shrink-0 border rounded-md overflow-y-auto max-h-[500px]">
              {Object.entries(groupedFiles)
                .sort(([a], [b]) => a.localeCompare(b))
                .map(([dir, files]) => (
                  <div key={dir}>
                    <div className="px-2 py-1.5 text-[10px] font-medium text-muted-foreground uppercase bg-muted/30 border-b">
                      {dir}
                    </div>
                    {files.map((file) => {
                      const fileName = file.filePath.substring(file.filePath.lastIndexOf("/") + 1);
                      const isSelected = selectedFile?.id === file.id;
                      return (
                        <div
                          key={file.id}
                          className={`flex items-center gap-1.5 px-2 py-1.5 text-xs cursor-pointer transition-colors ${
                            isSelected ? "bg-primary/10 text-primary" : "hover:bg-muted/50"
                          }`}
                          onClick={() => {
                            setSelectedFile(file);
                            setEditedContent(null);
                          }}
                        >
                          <span className="text-[9px] font-mono bg-muted px-1 rounded shrink-0">
                            {FILE_TYPE_ICON[file.fileType] || "???"}
                          </span>
                          <span className="truncate">{fileName}</span>
                          {file.manuallyModified && (
                            <span className="text-amber-500 text-[9px] shrink-0" title="Manually edited">M</span>
                          )}
                        </div>
                      );
                    })}
                  </div>
                ))}
            </div>

            {/* Editor */}
            <div className="flex-1 border rounded-md overflow-hidden">
              {selectedFile && (
                <>
                  <div className="flex items-center justify-between px-3 py-1.5 bg-muted/30 border-b">
                    <div className="flex items-center gap-2">
                      <span className="text-xs font-mono text-muted-foreground">
                        {selectedFile.filePath}
                      </span>
                      {selectedFile.manuallyModified && (
                        <Badge variant="outline" className="text-[9px]">edited</Badge>
                      )}
                    </div>
                    {editedContent !== null && editable && (
                      <Button size="sm" className="h-6 text-[10px]" onClick={handleSave} disabled={saving}>
                        {saving ? "Saving..." : "Save Changes"}
                      </Button>
                    )}
                  </div>
                  <MonacoEditor
                    height="430px"
                    language={FILE_TYPE_LANG[selectedFile.fileType] || "plaintext"}
                    value={editedContent ?? selectedFile.content}
                    theme="vs-dark"
                    onChange={(value) => {
                      if (editable && value !== undefined) {
                        setEditedContent(value);
                      }
                    }}
                    options={{
                      readOnly: !editable,
                      minimap: { enabled: false },
                      fontSize: 12,
                      lineNumbers: "on",
                      scrollBeyondLastLine: false,
                      wordWrap: "on",
                      padding: { top: 8 },
                    }}
                  />
                </>
              )}
            </div>
          </div>
          </>
        )}
      </CardContent>
    </Card>
  );
}
