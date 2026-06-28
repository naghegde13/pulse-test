"use client";

import { useCallback, useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";

import { useTenant } from "@/contexts/tenant-context";
import { api } from "@/lib/api";
import type { UserStory, UserStoryTask } from "@/types";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { toast } from "sonner";

const markdownComponents = {
  table: ({ children, ...props }: React.HTMLAttributes<HTMLTableElement>) => (
    <div className="overflow-x-auto my-3">
      <table className="w-full text-sm border-collapse border border-border" {...props}>
        {children}
      </table>
    </div>
  ),
  th: ({ children, ...props }: React.HTMLAttributes<HTMLTableCellElement>) => (
    <th className="border border-border bg-muted px-3 py-1.5 text-left text-xs font-medium" {...props}>
      {children}
    </th>
  ),
  td: ({ children, ...props }: React.HTMLAttributes<HTMLTableCellElement>) => (
    <td className="border border-border px-3 py-1.5 text-xs" {...props}>
      {children}
    </td>
  ),
  h2: ({ children, ...props }: React.HTMLAttributes<HTMLHeadingElement>) => (
    <h2 className="text-base font-semibold mt-5 mb-2" {...props}>{children}</h2>
  ),
  h3: ({ children, ...props }: React.HTMLAttributes<HTMLHeadingElement>) => (
    <h3 className="text-sm font-semibold mt-4 mb-1.5" {...props}>{children}</h3>
  ),
  p: ({ children, ...props }: React.HTMLAttributes<HTMLParagraphElement>) => (
    <p className="text-sm leading-relaxed mb-2" {...props}>{children}</p>
  ),
  ul: ({ children, ...props }: React.HTMLAttributes<HTMLUListElement>) => (
    <ul className="list-disc pl-5 text-sm space-y-0.5 mb-2" {...props}>{children}</ul>
  ),
  ol: ({ children, ...props }: React.HTMLAttributes<HTMLOListElement>) => (
    <ol className="list-decimal pl-5 text-sm space-y-0.5 mb-2" {...props}>{children}</ol>
  ),
  code: ({ children, className, ...props }: React.HTMLAttributes<HTMLElement>) => {
    const isInline = !className;
    return isInline ? (
      <code className="bg-muted px-1 py-0.5 rounded text-xs font-mono" {...props}>{children}</code>
    ) : (
      <code className={className} {...props}>{children}</code>
    );
  },
  pre: ({ children, ...props }: React.HTMLAttributes<HTMLPreElement>) => (
    <pre className="bg-muted rounded-md p-3 text-xs overflow-x-auto my-2" {...props}>{children}</pre>
  ),
  blockquote: ({ children, ...props }: React.HTMLAttributes<HTMLQuoteElement>) => (
    <blockquote className="border-l-2 border-primary/30 pl-3 italic text-muted-foreground text-sm my-2" {...props}>
      {children}
    </blockquote>
  ),
};

const ESTIMATE_COLORS: Record<string, string> = {
  S: "bg-green-100 text-green-800 dark:bg-green-900 dark:text-green-200",
  M: "bg-yellow-100 text-yellow-800 dark:bg-yellow-900 dark:text-yellow-200",
  L: "bg-red-100 text-red-800 dark:bg-red-900 dark:text-red-200",
};

interface UserStoryDialogProps {
  pipelineId: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

export function UserStoryDialog({ pipelineId, open, onOpenChange }: UserStoryDialogProps) {
  const { currentTenant } = useTenant();
  const [story, setStory] = useState<UserStory | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [activeTab, setActiveTab] = useState<"story" | "tasks">("story");

  const fetchStory = useCallback(async (regenerate = false) => {
    if (!currentTenant) return;
    setLoading(true);
    setError(null);
    try {
      const result = await api.post<UserStory>(
        `/api/v1/tenants/${currentTenant.id}/pipelines/${pipelineId}/story?regenerate=${regenerate}`,
        {}
      );
      if ((result as unknown as Record<string, unknown>).error) {
        setError((result as unknown as Record<string, unknown>).error as string);
      } else {
        setStory(result);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to generate story");
    } finally {
      setLoading(false);
    }
  }, [currentTenant, pipelineId]);

  useEffect(() => {
    if (open && !story && !loading) {
      fetchStory();
    }
  }, [open, story, loading, fetchStory]);

  const handleCopyToClipboard = async () => {
    if (!story) return;
    const markdown = buildFullMarkdown(story);
    try {
      await navigator.clipboard.writeText(markdown);
      toast.success("Copied to clipboard");
    } catch {
      toast.error("Failed to copy");
    }
  };

  const handleRegenerate = () => {
    setStory(null);
    fetchStory(true);
  };

  const handleClose = (val: boolean) => {
    if (!val) {
      setStory(null);
      setError(null);
      setActiveTab("story");
    }
    onOpenChange(val);
  };

  return (
    <Dialog open={open} onOpenChange={handleClose}>
      <DialogContent className="sm:max-w-5xl h-[90vh] flex flex-col">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-3">
            User Story
            {story && (
              <span className="text-sm font-normal text-muted-foreground truncate max-w-md">
                {story.title}
              </span>
            )}
          </DialogTitle>
        </DialogHeader>

        {/* Tab switcher */}
        <div className="flex gap-1 border-b">
          <button
            className={`px-3 py-1.5 text-sm font-medium border-b-2 transition-colors ${
              activeTab === "story"
                ? "border-primary text-primary"
                : "border-transparent text-muted-foreground hover:text-foreground"
            }`}
            onClick={() => setActiveTab("story")}
          >
            Story & Acceptance Criteria
          </button>
          <button
            className={`px-3 py-1.5 text-sm font-medium border-b-2 transition-colors ${
              activeTab === "tasks"
                ? "border-primary text-primary"
                : "border-transparent text-muted-foreground hover:text-foreground"
            }`}
            onClick={() => setActiveTab("tasks")}
          >
            Tasks
            {story?.tasks && (
              <Badge variant="secondary" className="ml-1.5 text-[10px]">
                {story.tasks.length}
              </Badge>
            )}
          </button>
        </div>

        {/* Content area */}
        <div className="flex-1 min-h-0 overflow-y-auto overflow-x-hidden pr-2" style={{ scrollbarGutter: "stable" }}>
          {loading && (
            <div className="flex flex-col items-center justify-center py-16 gap-3">
              <svg className="animate-spin h-8 w-8 text-muted-foreground" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24">
                <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
              </svg>
              <p className="text-sm text-muted-foreground">Generating user story from pipeline metadata and chat history...</p>
              <p className="text-xs text-muted-foreground">This may take 10-20 seconds</p>
            </div>
          )}

          {error && !loading && (
            <div className="py-8 text-center space-y-3">
              <p className="text-sm text-destructive">{error}</p>
              <Button variant="outline" size="sm" onClick={() => fetchStory()}>
                Retry
              </Button>
            </div>
          )}

          {story && !loading && activeTab === "story" && (
            <div className="chat-markdown py-2">
              <ReactMarkdown remarkPlugins={[remarkGfm]} components={markdownComponents}>
                {story.storyMarkdown}
              </ReactMarkdown>
            </div>
          )}

          {story && !loading && activeTab === "tasks" && (
            <div className="space-y-3 py-2">
              {story.tasks.map((task: UserStoryTask, i: number) => (
                <div key={i} className="rounded-md border p-3 space-y-1.5">
                  <div className="flex items-center gap-2">
                    <span className="text-xs text-muted-foreground font-mono w-5">{i + 1}.</span>
                    <span className="text-sm font-medium flex-1">{task.title}</span>
                    <span className={`text-[10px] font-medium px-1.5 py-0.5 rounded ${ESTIMATE_COLORS[task.estimate] || ""}`}>
                      {task.estimate}
                    </span>
                  </div>
                  <p className="text-xs text-muted-foreground pl-7">{task.description}</p>
                </div>
              ))}
            </div>
          )}
        </div>

        <DialogFooter className="flex-row justify-between sm:justify-between gap-2">
          <Button variant="outline" size="sm" onClick={handleRegenerate} disabled={loading}>
            Regenerate
          </Button>
          <div className="flex gap-2">
            <Button variant="outline" size="sm" onClick={handleCopyToClipboard} disabled={!story || loading}>
              Copy to Clipboard
            </Button>
            <Button size="sm" disabled title="JIRA integration coming soon">
              Export to JIRA
            </Button>
          </div>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function buildFullMarkdown(story: UserStory): string {
  let md = `# ${story.title}\n\n`;
  md += story.storyMarkdown;
  md += "\n\n---\n\n## Tasks\n\n";
  for (let i = 0; i < story.tasks.length; i++) {
    const t = story.tasks[i];
    md += `### ${i + 1}. ${t.title} [${t.estimate}]\n\n${t.description}\n\n`;
  }
  return md;
}
