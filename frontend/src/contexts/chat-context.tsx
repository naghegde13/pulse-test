"use client";

import { createContext, useCallback, useContext, useEffect, useRef, useState } from "react";
import type React from "react";
import { useAuth } from "@/contexts/auth-context";
import { useTenant } from "@/contexts/tenant-context";
import type { ChatSession, ChatMessage } from "@/types";

const API_BASE = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";

interface ChatContextValue {
  isOpen: boolean;
  pipelineId: string | null;
  session: ChatSession | null;
  messages: ChatMessage[];
  sessionLoaded: boolean;
  open: (pipelineId?: string) => void;
  close: () => void;
  toggle: () => void;
  resetChat: () => void;
  setPipelineId: (id: string | null) => void;
  setSession: (s: ChatSession | null) => void;
  setMessages: React.Dispatch<React.SetStateAction<ChatMessage[]>>;
  rightRegionContent: React.ReactNode | null;
  setRightRegionContent: (content: React.ReactNode | null) => void;
}

const ChatContext = createContext<ChatContextValue | null>(null);

export function ChatProvider({ children }: { children: React.ReactNode }) {
  const { user } = useAuth();
  const { currentTenant } = useTenant();
  const [isOpen, setIsOpen] = useState(false);
  const [pipelineId, setPipelineId] = useState<string | null>(null);
  const [session, setSession] = useState<ChatSession | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [sessionLoaded, setSessionLoaded] = useState(false);
  const [rightRegionContent, setRightRegionContent] = useState<React.ReactNode | null>(null);
  // Tracks WHICH (tenant, user) pair we last loaded for, so switching
  // tenants re-runs the fetch. The prior boolean ref short-circuited
  // ALL re-runs after the first, which meant: log in to Default Tenant
  // (empty session) → switch to Home Lending → blank, because the
  // effect never re-queried for the new tenant.
  const loadedFor = useRef<string | null>(null);

  // Restore the latest session whenever the (tenant, user) pair changes.
  useEffect(() => {
    if (!currentTenant || !user) return;
    const key = `${currentTenant.id}:${user.id}`;
    if (loadedFor.current === key) return;
    loadedFor.current = key;

    // Clear stale messages from the prior tenant immediately so the UI
    // doesn't flash old content during the swap.
    setSession(null);
    setMessages([]);
    setSessionLoaded(false);

    (async () => {
      try {
        const res = await fetch(
          `${API_BASE}/api/v1/tenants/${currentTenant.id}/chat/sessions/latest?userId=${user.id}`,
          { headers: { "Content-Type": "application/json" } }
        );
        if (!res.ok || res.status === 204) {
          setSessionLoaded(true);
          return;
        }
        const latestSession: ChatSession = await res.json();
        setSession(latestSession);

        const msgsRes = await fetch(
          `${API_BASE}/api/v1/chat/sessions/${latestSession.id}/messages`,
          { headers: { "Content-Type": "application/json" } }
        );
        if (msgsRes.ok) {
          const msgs: ChatMessage[] = await msgsRes.json();
          setMessages(msgs);
        }
      } catch {
        // Silently ignore
      } finally {
        setSessionLoaded(true);
      }
    })();
  }, [currentTenant, user]);

  const open = useCallback((pid?: string) => {
    if (pid !== undefined) setPipelineId(pid);
    setRightRegionContent(null);
    setIsOpen(true);
  }, []);

  const close = useCallback(() => {
    setIsOpen(false);
  }, []);

  const toggle = useCallback(() => {
    setIsOpen((prev) => !prev);
  }, []);

  const resetChat = useCallback(async () => {
    setSession(null);
    setMessages([]);
    // Create a new empty session so /sessions/latest returns it on refresh
    if (currentTenant && user) {
      try {
        const res = await fetch(
          `${API_BASE}/api/v1/tenants/${currentTenant.id}/chat/sessions`,
          {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ userId: user.id, title: "New conversation" }),
          }
        );
        if (res.ok) {
          const newSession: ChatSession = await res.json();
          setSession(newSession);
        }
      } catch {
        // Silently ignore
      }
    }
  }, [currentTenant, user]);

  return (
    <ChatContext.Provider
      value={{
        isOpen, pipelineId, session, messages,
        open, close, toggle, resetChat, setPipelineId, setSession, setMessages, sessionLoaded,
        rightRegionContent, setRightRegionContent,
      }}
    >
      {children}
    </ChatContext.Provider>
  );
}

export function useChat() {
  const ctx = useContext(ChatContext);
  if (!ctx) throw new Error("useChat must be used within ChatProvider");
  return ctx;
}
