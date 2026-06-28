"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { Button } from "@/components/ui/button";

import { Badge } from "@/components/ui/badge";
import { useAuth } from "@/contexts/auth-context";
import { useTenant } from "@/contexts/tenant-context";
import { useChat } from "@/contexts/chat-context";
import { api } from "@/lib/api";
import type { ChatSession, ChatMessage } from "@/types";
import { emitCompositionEvent } from "@/lib/composition-events";
import { emitStagingEvent } from "@/lib/staging-events";
import type { CandidateGraphPayload } from "@/lib/staging-reconcile";
import type { PlanPreview } from "@/lib/staging-events";
import { emitSorEvent } from "@/lib/sor-events";
import { ChatDag, parsePipelineSteps } from "@/components/pipeline/chat-dag";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { toast } from "sonner";
import { ChatTableWrapper } from "@/components/pipeline/chat-table-wrapper";

const markdownComponents = {
  // ChatTableWrapper adds the click-to-expand modal for ALL tables
  // (with hover-revealed Expand button). Sticky-first-column + sticky-
  // header behavior comes from globals.css. Together these eliminate
  // the "I lost my row anchor while scrolling right to find PII column"
  // disorientation problem.
  table: ChatTableWrapper,
};

const API_BASE = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";

interface ChatPanelProps {
  pipelineId?: string;
}

export function ChatPanel({ pipelineId }: ChatPanelProps) {
  const { user } = useAuth();
  const { currentTenant, refreshTenant } = useTenant();
  const { close, resetChat, session, setSession, messages, setMessages } = useChat();
  const router = useRouter();
  const [input, setInput] = useState("");
  const [streaming, setStreaming] = useState(false);
  const [streamContent, setStreamContent] = useState("");
  const [attachedFile, setAttachedFile] = useState<{ name: string; content: string } | null>(null);
  const scrollRef = useRef<HTMLDivElement>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    scrollRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages, streamContent]);

  // Session restoration is handled by ChatProvider (chat-context.tsx)

  const createSession = useCallback(async () => {
    if (!currentTenant || !user) return null;
    const s = await api.post<ChatSession>(
      `/api/v1/tenants/${currentTenant.id}/chat/sessions`,
      {
        userId: user.id,
        pipelineId: pipelineId || null,
        title: pipelineId ? "Pipeline chat" : "New conversation",
      }
    );
    setSession(s);
    return s;
  }, [currentTenant, user, pipelineId, setSession]);

  const handleFileUpload = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = () => {
      const text = reader.result as string;
      const preview = text.length > 5000 ? text.slice(0, 5000) + "\n...(truncated)" : text;
      setAttachedFile({ name: file.name, content: preview });
    };
    reader.readAsText(file);
    e.target.value = "";
  };

  const sendMessage = async () => {
    if ((!input.trim() && !attachedFile) || streaming) return;

    let activeSession = session;
    if (!activeSession) {
      activeSession = await createSession();
      if (!activeSession) return;
    }

    let messageContent = input.trim();
    if (attachedFile) {
      const fileSection = `[Uploaded file: ${attachedFile.name}]\n\`\`\`\n${attachedFile.content}\n\`\`\``;
      messageContent = messageContent
        ? `${messageContent}\n\n${fileSection}`
        : fileSection;
    }

    const displayContent = attachedFile
      ? (input.trim() ? `${input.trim()}\n\n📎 ${attachedFile.name}` : `📎 ${attachedFile.name}`)
      : input.trim();

    const userMsg: ChatMessage = {
      id: `temp-${Date.now()}`,
      sessionId: activeSession.id,
      role: "USER",
      content: displayContent,
      createdAt: new Date().toISOString(),
    };
    setMessages((prev) => [...prev, userMsg]);
    setInput("");
    setAttachedFile(null);
    if (textareaRef.current) textareaRef.current.style.height = "auto";
    setStreaming(true);
    setStreamContent("");
    emitStagingEvent({ type: "turn_started" });

    try {
      const token = localStorage.getItem("pulse_token");
      const tenantId = currentTenant?.id || "tenant-home-lending";
      const response = await fetch(
        `${API_BASE}/api/v1/chat/sessions/${activeSession.id}/messages`,
        {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            ...(token ? { Authorization: `Bearer ${token}` } : {}),
          },
          body: JSON.stringify({ content: messageContent, tenantId }),
        }
      );

      if (!response.ok) throw new Error("Chat request failed");

      const reader = response.body?.getReader();
      if (!reader) throw new Error("No response stream");

      const decoder = new TextDecoder();
      let accumulated = "";
      let currentEvent = "chunk";
      let sseBuffer = "";

      // SSE spec: multiple "data:" lines within one event are joined with "\n".
      // An empty line dispatches the event.
      let eventDataLines: string[] = [];

      const dispatchEvent = () => {
        if (eventDataLines.length === 0) return;
        const data = eventDataLines.join("\n");
        eventDataLines = [];

        if (currentEvent === "done" || data.trim() === "") {
          currentEvent = "chunk";
          return;
        }
        if (currentEvent === "error") {
          // BUG-2026-05-25-57: backend now emits a STRUCTURED JSON error
          // envelope on `event: error` frames:
          //   {"code":"CHAT_UPSTREAM_LLM_ERROR",
          //    "message":"LLM provider returned HTTP 400",
          //    "cause":"...",
          //    "upstream":"{...openrouter error json...}"}
          // Render those fields inline instead of the legacy generic
          // "Sorry, I encountered an error." Falls back to raw `data`
          // if parsing fails (older backend, transient corruption).
          let rendered = data;
          try {
            const parsed = JSON.parse(data) as {
              code?: string;
              message?: string;
              cause?: string;
              upstream?: string;
            };
            const parts: string[] = [];
            if (parsed.code) parts.push(`**${parsed.code}**`);
            if (parsed.message) parts.push(parsed.message);
            if (parsed.cause && parsed.cause !== parsed.message) {
              parts.push(`(${parsed.cause})`);
            }
            if (parsed.upstream) {
              parts.push(`\n\`\`\`\n${parsed.upstream}\n\`\`\``);
            }
            if (parts.length > 0) rendered = parts.join(" ");
          } catch {
            // not JSON — render as-is
          }
          accumulated += `\n\n*Error: ${rendered}*`;
          setStreamContent(accumulated);
          currentEvent = "chunk";
          return;
        }
        if (currentEvent === "tool_call") {
          const toolName = data.trim();
          const compositionTools = [
            "propose_create_pipeline", "propose_add_instance", "propose_wiring",
            "propose_set_params", "configure_step_params", "wire_ports",
            "remove_step", "get_composition"
          ];
          if (compositionTools.includes(toolName)) {
            emitCompositionEvent({ type: "composition_changed" });
          }
          currentEvent = "chunk";
          return;
        }
        if (currentEvent === "tool_result") {
          // ARCH-009: tool_result is now a structured JSON envelope. Refresh
          // and toast behavior key off `mutationApplied` + `refreshHints`,
          // never tool names. The legacy string-payload path is preserved as
          // a fallback during the transition window.
          let toolName = data.trim();
          let mutationApplied = false;
          let planCreated = false;
          let status: string | undefined;
          let refreshHints: string[] = [];
          let message: string | undefined;
          let uiIntents: Array<{
            kind?: string;
            sorId?: string;
            connectorInstanceId?: string;
            environment?: string;
          }> = [];
          if (toolName.startsWith("{")) {
            try {
              const envelope = JSON.parse(toolName) as {
                toolName?: string;
                status?: string;
                planCreated?: boolean;
                mutationApplied?: boolean;
                refreshHints?: string[];
                uiIntents?: Array<{
                  kind?: string;
                  sorId?: string;
                  connectorInstanceId?: string;
                  environment?: string;
                }>;
                message?: string;
              };
              toolName = envelope.toolName ?? "";
              status = envelope.status;
              planCreated = Boolean(envelope.planCreated);
              mutationApplied = Boolean(envelope.mutationApplied);
              refreshHints = envelope.refreshHints ?? [];
              uiIntents = envelope.uiIntents ?? [];
              message = envelope.message;
            } catch {
              // Fallback below: treat as legacy string payload.
            }
          }

          if (status === "error") {
            // Errors must not trigger refresh/toast as success.
            currentEvent = "chunk";
            return;
          }

          const toolToastMap: Record<string, string> = {
            create_data_source: "Data source created",
            create_domain: "Domain created",
            create_dataset: "Dataset created",
            create_connector: "Connector created",
            plan_create_pipeline: "Pipeline planned",
            propose_create_pipeline: "Pipeline planned",
            propose_add_instance: "Blueprint instance added",
            propose_wiring: "Ports wired",
            propose_set_params: "Parameters updated",
            configure_step_params: "Step configured",
            wire_ports: "Ports wired",
            remove_step: "Step removed",
            apply_plan: "Plan applied",
          };
          if (toolToastMap[toolName]) {
            toast.success(toolToastMap[toolName]);
          }

          // ARCH-009 refresh routing — keyed off refreshHints, with a small
          // legacy lookup for non-envelope payloads.
          const refresh = new Set<string>(refreshHints);
          if (!refresh.size) {
            if (toolName === "create_domain") refresh.add("domains");
            if (toolName === "create_dataset") refresh.add("datasets");
            if (toolName === "create_connector") refresh.add("data_sources");
            if (toolName === "create_data_source") refresh.add("data_sources");
          }
          if (refresh.has("domains") || toolName === "create_domain") {
            refreshTenant();
          }
          if (refresh.has("datasets")) {
            emitSorEvent({ type: "dataset_added" });
          }
          if (refresh.has("data_sources") || refresh.has("connector")) {
            emitSorEvent({ type: "connector_added" });
            emitSorEvent({ type: "sor_changed" });
          }
          if (refresh.has("composition") || refresh.has("pipeline")
              || mutationApplied || planCreated) {
            emitCompositionEvent({ type: "composition_changed" });
          }
          for (const intent of uiIntents) {
            if (
              intent.kind === "credential_attach" &&
              intent.sorId &&
              intent.connectorInstanceId &&
              !intent.connectorInstanceId.startsWith("draft:")
            ) {
              const env = encodeURIComponent(intent.environment ?? "DEV");
              router.push(`/producers/${intent.sorId}?attach_credential=${intent.connectorInstanceId}&env=${env}`);
              break;
            }
          }
          // The message is reserved for toast bodies; surface unused for now
          // but keep the binding so future surfaces can read it.
          void message;
          currentEvent = "chunk";
          return;
        }
        if (currentEvent === "candidate_graph") {
          // Phase 6: the STAGING composition snapshot. Hand the full payload to
          // the composition panel, which reconciles it into ghost nodes on the
          // @xyflow canvas (preserving manual positions; auto-layout only on a
          // structural change). The canonical graph is NOT touched — Apply is
          // the only canonical writer.
          try {
            const payload = JSON.parse(data) as CandidateGraphPayload;
            emitStagingEvent({ type: "candidate_graph", payload });
          } catch {
            // ignore malformed staging frame
          }
          currentEvent = "chunk";
          return;
        }
        if (currentEvent === "plan") {
          // Phase 6: the Plan Preview. The "Review N changes" banner IS this
          // preview — clicking it opens the before/after + per-op reasoning.
          try {
            const parsed = JSON.parse(data) as {
              planId?: string;
              plan?: PlanPreview;
            } & PlanPreview;
            const plan: PlanPreview = parsed.plan
              ? { ...parsed.plan, planId: parsed.plan.planId ?? parsed.planId }
              : parsed;
            if (plan && plan.planId) {
              emitStagingEvent({ type: "plan", payload: plan });
            }
          } catch {
            // ignore malformed plan frame
          }
          currentEvent = "chunk";
          return;
        }
        if (currentEvent === "navigate") {
          router.push(data.trim());
          emitCompositionEvent({ type: "composition_changed" });
          currentEvent = "chunk";
          return;
        }
        // chunk event: accumulate the text preserving newlines
        accumulated += data;
        setStreamContent(accumulated);
        currentEvent = "chunk";
      };

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;

        sseBuffer += decoder.decode(value, { stream: true });
        const lines = sseBuffer.split("\n");
        sseBuffer = lines.pop() || "";

        for (const line of lines) {
          if (line.startsWith("event:")) {
            dispatchEvent();
            currentEvent = line.substring(6).trim();
          } else if (line.startsWith("data:")) {
            eventDataLines.push(line.substring(5));
          } else if (line.trim() === "") {
            // Empty line = end of SSE event, dispatch it
            dispatchEvent();
          }
        }
      }
      // Dispatch any remaining event data
      dispatchEvent();

      if (accumulated) {
        const assistantMsg: ChatMessage = {
          id: `temp-${Date.now()}-reply`,
          sessionId: activeSession.id,
          role: "ASSISTANT",
          content: accumulated,
          createdAt: new Date().toISOString(),
        };
        setMessages((prev) => [...prev, assistantMsg]);
        emitCompositionEvent({ type: "composition_changed" });
      }
    } catch {
      const errMsg: ChatMessage = {
        id: `temp-${Date.now()}-err`,
        sessionId: activeSession.id,
        role: "ASSISTANT",
        content:
          "Sorry, I encountered an error. Please try again.",
        createdAt: new Date().toISOString(),
      };
      setMessages((prev) => [...prev, errMsg]);
    } finally {
      setStreaming(false);
      setStreamContent("");
      emitStagingEvent({ type: "turn_ended" });
    }
  };

  return (
    <div className="flex flex-col h-full bg-card overflow-hidden">
      <div className="flex items-center justify-between px-4 py-2 border-b">
        <div className="flex items-center gap-2">
          <span className="text-sm font-medium">PULSE Assistant</span>
          <Badge variant="outline" className="text-[10px]">
            {session ? "Active" : "New"}
          </Badge>
        </div>
        <div className="flex items-center gap-1">
          <Button
            variant="ghost"
            size="sm"
            className="h-7 px-2 text-xs text-muted-foreground hover:text-foreground"
            onClick={resetChat}
            title="Start a new conversation"
          >
            New Chat
          </Button>
          <Button variant="ghost" size="sm" className="h-7 w-7 p-0" onClick={close}>
            &times;
          </Button>
        </div>
      </div>

      <div className="flex-1 overflow-y-scroll p-4 chat-scroll">
        <div className="space-y-4">
          {messages.length === 0 && !streaming && (
            <div className="text-center py-8 space-y-2">
              <p className="text-sm text-muted-foreground">
                Describe the pipeline you want to build. I will help you choose
                the right data sources, blueprints, and transformations.
              </p>
              <div className="flex flex-wrap justify-center gap-2 pt-2">
                {[
                  "What data sources are available?",
                  "Show me the blueprint catalog",
                  "I need a pipeline for loan data",
                ].map((suggestion) => (
                  <Button
                    key={suggestion}
                    variant="outline"
                    size="sm"
                    className="text-xs h-7"
                    onClick={() => {
                      setInput(suggestion);
                    }}
                  >
                    {suggestion}
                  </Button>
                ))}
              </div>
            </div>
          )}

          {messages
            // Hide internal-only message rows from the user-facing chat.
            // Two distinct cases that BOTH exist in DB but neither is
            // user-facing:
            //   1. role=TOOL: raw tool-result payloads (verbose internal
            //      IDs, NOTE: NEVER show to user markers).
            //   2. role=ASSISTANT with empty content + populated tool_calls:
            //      the LLM emits one of these per tool call as the
            //      tool-dispatch row. Renders as an empty rounded-lg
            //      bubble — visible as "small squares" of whitespace.
            // Live streaming never showed these (handleSubmit only
            // appends the assembled prose accumulator), but a DB
            // refetch on login surfaced them.
            .filter((msg) => {
              if (msg.role === "TOOL") return false;
              if (msg.role === "ASSISTANT"
                  && (!msg.content || !msg.content.trim())) {
                return false;
              }
              return true;
            })
            .map((msg) => (
              <MessageBubble key={msg.id} message={msg} />
            ))}

          {streaming && !streamContent && (
            <div className="flex gap-2">
              <div className="rounded-lg px-3 py-2 text-sm bg-muted max-w-[85%] flex items-center gap-2">
                <svg className="animate-spin h-4 w-4 text-muted-foreground" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24">
                  <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                  <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
                </svg>
                <span className="text-muted-foreground">Thinking...</span>
              </div>
            </div>
          )}

          {streaming && streamContent && (
            <div className="flex gap-2">
              <div className="rounded-lg px-3 py-2 text-sm bg-muted max-w-[85%] break-words chat-markdown">
                <ReactMarkdown remarkPlugins={[remarkGfm]} components={markdownComponents}>{streamContent}</ReactMarkdown>
                <span className="animate-pulse">|</span>
              </div>
            </div>
          )}

          <div ref={scrollRef} />
        </div>
      </div>

      <div className="border-t px-4 py-3 space-y-2">
        {attachedFile && (
          <div className="flex items-center gap-2 px-2 py-1.5 bg-muted rounded-md text-xs">
            <span className="truncate flex-1">📎 {attachedFile.name}</span>
            <button
              type="button"
              onClick={() => setAttachedFile(null)}
              className="text-muted-foreground hover:text-foreground shrink-0"
            >
              &times;
            </button>
          </div>
        )}
        <form
          onSubmit={(e) => {
            e.preventDefault();
            sendMessage();
          }}
          className="flex gap-2 items-end"
        >
          <input
            ref={fileInputRef}
            type="file"
            accept=".csv,.tsv,.txt,.json,.xml,.parquet,.xls,.xlsx"
            onChange={handleFileUpload}
            className="hidden"
          />
          <Button
            type="button"
            variant="ghost"
            size="sm"
            className="h-9 w-9 p-0 shrink-0"
            onClick={() => fileInputRef.current?.click()}
            disabled={streaming}
            title="Attach a file"
          >
            📎
          </Button>
          <textarea
            ref={textareaRef}
            placeholder="Describe what you need..."
            value={input}
            onChange={(e) => {
              setInput(e.target.value);
              const el = e.target;
              el.style.height = "auto";
              el.style.height = Math.min(el.scrollHeight, 150) + "px";
            }}
            onKeyDown={(e) => {
              if (e.key === "Enter" && !e.shiftKey && !e.altKey) {
                e.preventDefault();
                sendMessage();
              }
            }}
            disabled={streaming}
            rows={1}
            className="flex-1 resize-none rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50"
            style={{ minHeight: "42px", maxHeight: "150px" }}
          />
          <Button type="submit" size="sm" disabled={streaming || (!input.trim() && !attachedFile)}>
            {streaming ? "..." : "Send"}
          </Button>
        </form>
      </div>
    </div>
  );
}

function MessageBubble({ message }: { message: ChatMessage }) {
  const isUser = message.role === "USER";
  const parsed = !isUser ? parsePipelineSteps(message.content) : null;

  if (parsed) {
    return (
      <div className="flex justify-start">
        <div className="rounded-lg px-3 py-2 text-sm max-w-[95%] bg-muted break-words chat-markdown">
          {parsed.beforeText && (
            <div className="mb-2">
              <ReactMarkdown remarkPlugins={[remarkGfm]} components={markdownComponents}>{parsed.beforeText}</ReactMarkdown>
            </div>
          )}
          <ChatDag steps={parsed.steps} />
          {parsed.afterText && (
            <div className="mt-2">
              <ReactMarkdown remarkPlugins={[remarkGfm]} components={markdownComponents}>{parsed.afterText}</ReactMarkdown>
            </div>
          )}
        </div>
      </div>
    );
  }

  return (
    <div className={`flex ${isUser ? "justify-end" : "justify-start"}`}>
      <div
        className={`rounded-lg px-3 py-2 text-sm max-w-[85%] break-words ${
          isUser
            ? "bg-primary text-primary-foreground whitespace-pre-wrap"
            : "bg-muted chat-markdown"
        }`}
      >
        {isUser ? (
          message.content
        ) : (
          <ReactMarkdown remarkPlugins={[remarkGfm]} components={markdownComponents}>{message.content}</ReactMarkdown>
        )}
      </div>
    </div>
  );
}
