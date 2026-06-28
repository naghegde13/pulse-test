"use client";

import { useState } from "react";
import dynamic from "next/dynamic";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";

// Dynamic import mirrors how code-editor-panel.tsx uses Monaco — SSR off,
// skeleton placeholder while the editor chunk loads.
const MonacoEditor = dynamic(
  () => import("@monaco-editor/react").then((m) => m.default),
  {
    ssr: false,
    loading: () => (
      <div className="h-[60vh] bg-muted animate-pulse rounded" />
    ),
  }
);

export interface CodeExample {
  /** Filename without extension; e.g. "bulk_backfill_date_range" */
  key: string;
  /** Monaco language id; "python" / "sql" / "yaml" / "plaintext" */
  language: string;
  /** Raw file body */
  content: string;
}

interface CodeViewerDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  blueprintName: string;
  examples: CodeExample[];
}

const LANG_EXTENSION: Record<string, string> = {
  python: "py",
  sql: "sql",
  yaml: "yaml",
  plaintext: "txt",
};

function fileNameFor(example: CodeExample): string {
  const ext = LANG_EXTENSION[example.language] ?? "txt";
  return `${example.key}.${ext}`;
}

export function CodeViewerDialog({
  open,
  onOpenChange,
  blueprintName,
  examples,
}: CodeViewerDialogProps) {
  const [activeIdx, setActiveIdx] = useState(0);
  const [copied, setCopied] = useState(false);
  const active = examples[activeIdx] ?? examples[0];

  const handleCopy = async () => {
    if (!active) return;
    try {
      await navigator.clipboard.writeText(active.content);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      // Clipboard API unavailable (insecure context, denied permission); silently no-op.
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        // sm:max-w-[1400px] is needed to override shadcn DialogContent's baked-in
        // sm:max-w-lg (512px) — without the same-breakpoint override, Tailwind class
        // merging keeps the narrow default. Cap at 1400px so the modal doesn't span
        // the full width on a 27" monitor; 1400px renders ~140 char lines comfortably.
        className="!max-w-[95vw] sm:!max-w-[1400px] w-[95vw] max-h-[90vh] flex flex-col p-0 overflow-hidden"
      >
        <DialogHeader className="px-6 pt-6 pb-3 border-b">
          <DialogTitle className="text-base">
            {blueprintName} — Code Examples
          </DialogTitle>
          <DialogDescription className="text-xs">
            These are reference implementations PULSE uses when generating
            this blueprint. The agent reads them as patterns during code
            generation.
          </DialogDescription>
        </DialogHeader>

        {examples.length > 1 && (
          <div className="flex flex-wrap gap-1 px-6 py-2 border-b bg-muted/30">
            {examples.map((ex, i) => (
              <Button
                key={ex.key}
                variant={i === activeIdx ? "default" : "outline"}
                size="sm"
                className="text-xs h-7 font-mono"
                onClick={() => setActiveIdx(i)}
              >
                {fileNameFor(ex)}
              </Button>
            ))}
          </div>
        )}

        {active && (
          <>
            <div className="flex items-center justify-between px-6 py-2 border-b bg-muted/20">
              <div className="flex items-center gap-2">
                <code className="text-xs font-mono">{fileNameFor(active)}</code>
                <Badge variant="outline" className="text-[10px] uppercase">
                  {active.language}
                </Badge>
              </div>
              <Button
                size="sm"
                variant="outline"
                className="text-xs h-7"
                onClick={handleCopy}
              >
                {copied ? "Copied ✓" : "Copy"}
              </Button>
            </div>

            <div className="flex-1 min-h-0">
              <MonacoEditor
                height="65vh"
                language={active.language}
                value={active.content}
                theme="vs-dark"
                options={{
                  readOnly: true,
                  minimap: { enabled: false },
                  fontSize: 13,
                  lineNumbers: "on",
                  scrollBeyondLastLine: false,
                  // wordWrap off — let long lines scroll horizontally so code reads as
                  // it was written. Wrap-on at any sensible modal width breaks Python
                  // indentation visually and disrupts the agent's pattern-match cue.
                  wordWrap: "off",
                  padding: { top: 12, bottom: 12 },
                  renderLineHighlight: "none",
                  // Read-only viewer; suppress UX affordances that imply editing.
                  contextmenu: false,
                  occurrencesHighlight: "off",
                }}
              />
            </div>
          </>
        )}
      </DialogContent>
    </Dialog>
  );
}
