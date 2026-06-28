"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { api } from "@/lib/api";
import type {
  CobolDiscoveryArtifact,
  CobolDiscoveryMessageExchange,
  CobolDiscoveryMessage,
  CobolDiscoveryRun,
  CobolDiscoverySession,
  CobolParsingProfile,
} from "@/types";

const DEFAULT_OPTION_OVERRIDES_TEXT = JSON.stringify(
  { schema_retention_policy: "collapse_root", ebcdic_code_page: "cp037" },
  null,
  2
);

const POLL_MS = 3_000;

interface SaveProfileInput { name: string; description?: string; userId: string }
interface UpdateProfileInput { name?: string; description?: string; cobrixOptions?: Record<string, unknown>; flattenSpec?: Record<string, unknown> }

interface Ctx {
  session: CobolDiscoverySession | null;
  messages: CobolDiscoveryMessage[];
  artifacts: CobolDiscoveryArtifact[];
  activeRun: CobolDiscoveryRun | null;
  progressEvents: Array<Record<string, unknown>>;
  profiles: CobolParsingProfile[];
  copybookText: string;
  optionOverridesText: string;
  initializing: boolean;
  busy: boolean;
  loopActive: boolean;
  initialize: (t: string, u: string) => Promise<void>;
  setOptionOverridesText: (v: string) => void;
  sendMessage: (t: string, c: string) => Promise<void>;
  uploadCopybook: (t: string, f: File) => Promise<void>;
  uploadDataFile: (t: string, f: File) => Promise<void>;
  runPreview: (t: string, o: Record<string, unknown>, n: number, auto?: boolean) => Promise<void>;
  runProfile: (t: string, o: Record<string, unknown>, n: number) => Promise<void>;
  cancelRun: (t: string, r: string) => Promise<void>;
  saveProfile: (t: string, i: SaveProfileInput) => Promise<void>;
  updateProfile: (t: string, p: string, i: UpdateProfileInput) => Promise<void>;
  loadProfiles: (t: string) => Promise<void>;
  reprofile: (t: string, p: string, u: string) => Promise<void>;
}

const CobolDiscoveryContext = createContext<Ctx | null>(null);

export function CobolDiscoveryProvider({ children }: { children: React.ReactNode }) {
  const [session, setSession] = useState<CobolDiscoverySession | null>(null);
  const [messages, setMessages] = useState<CobolDiscoveryMessage[]>([]);
  const [artifacts, setArtifacts] = useState<CobolDiscoveryArtifact[]>([]);
  const [activeRun, setActiveRun] = useState<CobolDiscoveryRun | null>(null);
  const [progressEvents, setProgressEvents] = useState<Array<Record<string, unknown>>>([]);
  const [profiles, setProfiles] = useState<CobolParsingProfile[]>([]);
  const [copybookText, setCopybookText] = useState("");
  const [optionOverridesText, setOptionOverridesText] = useState(DEFAULT_OPTION_OVERRIDES_TEXT);
  const [initializing, setInitializing] = useState(true);
  const [busy, setBusy] = useState(false);
  const [loopActive, setLoopActive] = useState(false);
  const initRef = useRef<Promise<void> | null>(null);
  const initTenantRef = useRef<string | null>(null);
  const prevRunIdRef = useRef<string | null>(null);
  const prevRunStatusRef = useRef<string | null>(null);
  const prevMsgCountRef = useRef(0);

  // ── Data fetchers ──

  const refreshMessages = useCallback(async (tid: string, sid: string) => {
    const data = await api.get<CobolDiscoveryMessage[]>(
      `/api/v1/tenants/${tid}/ebcdic-discovery/sessions/${sid}/messages`
    );
    const changed = data.length !== prevMsgCountRef.current;
    prevMsgCountRef.current = data.length;
    if (changed) {
      setMessages(data);
      // Update copybook text when assistant rewrites it
      const latestRewrite = [...data].reverse().find(
        m => m.role === "ASSISTANT" && m.metadata?.recommendedCopybookText
      );
      if (latestRewrite?.metadata?.recommendedCopybookText) {
        setCopybookText(latestRewrite.metadata.recommendedCopybookText as string);
      }
      // Check if the agent declared satisfaction, hit a blocker, or exhausted iterations -- loop-end signals
      const lastAssistant = [...data].reverse().find(m => m.role === "ASSISTANT");
      if (lastAssistant?.content && (
        /satisfied with this preview/i.test(lastAssistant.content) ||
        /hit a blocker/i.test(lastAssistant.content) ||
        /hit the iteration limit/i.test(lastAssistant.content)
      )) {
        setLoopActive(false);
      }
    }
  }, []);

  const refreshArtifacts = useCallback(async (tid: string, sid: string) => {
    const data = await api.get<CobolDiscoveryArtifact[]>(
      `/api/v1/tenants/${tid}/ebcdic-discovery/sessions/${sid}/artifacts`
    );
    setArtifacts(data);
  }, []);

  const refreshLatestRun = useCallback(async (tid: string, sid: string) => {
    try {
      const run = await api.get<CobolDiscoveryRun | null>(
        `/api/v1/tenants/${tid}/ebcdic-discovery/sessions/${sid}/latest-run`
      );
      if (run && run.id) {
        const newRun = run.id !== prevRunIdRef.current;
        const statusChanged = newRun || run.status !== prevRunStatusRef.current;
        if (statusChanged) {
          setActiveRun(run);
          prevRunStatusRef.current = run.status;
        }
        if (newRun && run.configSnapshot && Object.keys(run.configSnapshot).length > 0) {
          setOptionOverridesText(JSON.stringify(run.configSnapshot, null, 2));
        }
        if (newRun) {
          setLoopActive(true);
        }
        prevRunIdRef.current = run.id;
      }
    } catch {
      // ignore
    }
  }, []);

  // ── Polling: 3s interval while session exists ──

  useEffect(() => {
    if (!session) return;
    const tid = session.tenantId;
    const sid = session.id;
    const poll = () => {
      Promise.all([
        refreshMessages(tid, sid),
        refreshLatestRun(tid, sid),
      ]).catch(() => undefined);
    };
    const id = setInterval(poll, POLL_MS);
    return () => clearInterval(id);
  }, [session, refreshMessages, refreshLatestRun]);

  // ── Init ──

  const loadProfiles = useCallback(async (tid: string) => {
    const data = await api.get<CobolParsingProfile[]>(`/api/v1/tenants/${tid}/cobol-profiles`);
    setProfiles(data);
  }, []);

  const initialize = useCallback(async (tid: string, uid: string) => {
    if (initRef.current && initTenantRef.current === tid) return initRef.current;
    setInitializing(true);
    initTenantRef.current = tid;
    const task = (async () => {
      const s = await api.post<CobolDiscoverySession>(
        `/api/v1/tenants/${tid}/ebcdic-discovery/sessions`,
        { userId: uid, title: "EBCDIC Discovery" }
      );
      setSession(s);
      setActiveRun(null);
      setLoopActive(false);
      setProgressEvents([]);
      setArtifacts([]);
      setCopybookText("");
      setOptionOverridesText(DEFAULT_OPTION_OVERRIDES_TEXT);
      prevRunIdRef.current = null;
      await Promise.all([refreshMessages(tid, s.id), refreshArtifacts(tid, s.id), loadProfiles(tid)]);
    })();
    initRef.current = task;
    try { await task; } finally {
      if (initRef.current === task) { initRef.current = null; initTenantRef.current = null; }
      setInitializing(false);
    }
  }, [loadProfiles, refreshArtifacts, refreshMessages]);

  // ── Actions ──

  const sendMessage = useCallback(async (tid: string, content: string) => {
    if (!session || !content.trim()) return;
    const opt: CobolDiscoveryMessage = {
      id: `opt-${Date.now()}`, sessionId: session.id, role: "USER",
      content: content.trim(), safePayloadOnly: true, metadata: {},
      createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(),
    };
    setMessages(prev => [...prev, opt]);
    setBusy(true);
    try {
      let ov: Record<string, unknown> = {};
      try { ov = JSON.parse(optionOverridesText) as Record<string, unknown>; } catch { /* */ }
      const ex = await api.post<CobolDiscoveryMessageExchange>(
        `/api/v1/tenants/${tid}/ebcdic-discovery/sessions/${session.id}/messages`,
        { content, currentOptionOverrides: ov }
      );
      if (ex.optionOverrides && Object.keys(ex.optionOverrides).length > 0)
        setOptionOverridesText(JSON.stringify(ex.optionOverrides, null, 2));
      if (ex.activeRun) {
        setActiveRun(ex.activeRun); setProgressEvents([]); prevRunIdRef.current = ex.activeRun.id;
        const sp = ex.activeRun.samplePolicy as Record<string, unknown> | undefined;
        if (sp?.assistantFollowUp === true) setLoopActive(true);
      }
      await refreshMessages(tid, session.id);
    } catch { setMessages(prev => prev.filter(m => m.id !== opt.id)); } finally { setBusy(false); }
  }, [optionOverridesText, refreshMessages, session]);

  const uploadCopybook = useCallback(async (tid: string, file: File) => {
    if (!session) return;
    setBusy(true);
    try {
      setCopybookText(await file.text());
      const fd = new FormData(); fd.append("file", file);
      await api.postForm(`/api/v1/tenants/${tid}/ebcdic-discovery/sessions/${session.id}/copybook`, fd);
      await refreshArtifacts(tid, session.id);
      await refreshMessages(tid, session.id);
    } finally { setBusy(false); }
  }, [refreshArtifacts, refreshMessages, session]);

  const uploadDataFile = useCallback(async (tid: string, file: File) => {
    if (!session) return;
    setBusy(true);
    try {
      const fd = new FormData(); fd.append("file", file);
      await api.postForm(`/api/v1/tenants/${tid}/ebcdic-discovery/sessions/${session.id}/data-file`, fd);
      await refreshArtifacts(tid, session.id);
    } finally { setBusy(false); }
  }, [refreshArtifacts, session]);

  const triggerRun = useCallback(async (tid: string, path: string, ov: Record<string, unknown>, n: number, auto = false) => {
    if (!session) return;
    setBusy(true);
    try {
      const run = await api.post<CobolDiscoveryRun>(
        `/api/v1/tenants/${tid}/ebcdic-discovery/sessions/${session.id}/runs/${path}`,
        { optionOverrides: ov, sampleRows: n, autoRefine: auto }
      );
      setActiveRun(run); prevRunIdRef.current = run.id;
      if (auto) setLoopActive(true);
      setOptionOverridesText(JSON.stringify(ov, null, 2));
      setProgressEvents([]);
      await refreshMessages(tid, session.id);
    } finally { setBusy(false); }
  }, [refreshMessages, session]);

  const runPreview = useCallback(async (t: string, o: Record<string, unknown>, n: number, auto = true) => {
    await triggerRun(t, "preview", o, n, auto);
  }, [triggerRun]);

  const runProfile = useCallback(async (t: string, o: Record<string, unknown>, n: number) => {
    await triggerRun(t, "profile", o, n, false);
  }, [triggerRun]);

  const cancelRun = useCallback(async (tid: string, runId: string) => {
    setBusy(true);
    try {
      const run = await api.post<CobolDiscoveryRun>(`/api/v1/tenants/${tid}/ebcdic-discovery/runs/${runId}/cancel`, {});
      setActiveRun(run); setLoopActive(false);
    } finally { setBusy(false); }
  }, []);

  const saveProfile = useCallback(async (tid: string, input: SaveProfileInput) => {
    if (!activeRun) return;
    setBusy(true);
    try {
      await api.post(`/api/v1/tenants/${tid}/cobol-profiles`, {
        runId: activeRun.id, name: input.name, description: input.description, userId: input.userId,
      });
      await loadProfiles(tid);
      if (session) await refreshArtifacts(tid, session.id);
    } finally { setBusy(false); }
  }, [activeRun, loadProfiles, refreshArtifacts, session]);

  const updateProfile = useCallback(async (tid: string, pid: string, input: UpdateProfileInput) => {
    setBusy(true);
    try {
      await api.put(`/api/v1/tenants/${tid}/cobol-profiles/${pid}`, {
        name: input.name, description: input.description,
        cobrixOptions: input.cobrixOptions, flattenSpec: input.flattenSpec,
      });
      await loadProfiles(tid);
    } finally { setBusy(false); }
  }, [loadProfiles]);

  const reprofile = useCallback(async (tid: string, pid: string, uid: string) => {
    setBusy(true);
    try {
      const s = await api.post<CobolDiscoverySession>(
        `/api/v1/tenants/${tid}/cobol-profiles/${pid}/reprofile`, { userId: uid }
      );
      setSession(s); setActiveRun(null); setLoopActive(false);
      setProgressEvents([]); setArtifacts([]); setCopybookText("");
      setOptionOverridesText(DEFAULT_OPTION_OVERRIDES_TEXT); prevRunIdRef.current = null;
      await Promise.all([refreshMessages(tid, s.id), refreshArtifacts(tid, s.id)]);
    } finally { setBusy(false); }
  }, [refreshArtifacts, refreshMessages]);

  const value = useMemo<Ctx>(() => ({
    session, messages, artifacts, activeRun, progressEvents, profiles,
    copybookText, optionOverridesText, initializing, busy, loopActive,
    initialize, setOptionOverridesText, sendMessage, uploadCopybook, uploadDataFile,
    runPreview, runProfile, cancelRun, saveProfile, updateProfile, loadProfiles, reprofile,
  }), [
    session, messages, artifacts, activeRun, progressEvents, profiles,
    copybookText, optionOverridesText, initializing, busy, loopActive,
    initialize, sendMessage, uploadCopybook, uploadDataFile,
    runPreview, runProfile, cancelRun, saveProfile, updateProfile, loadProfiles, reprofile,
  ]);

  return <CobolDiscoveryContext.Provider value={value}>{children}</CobolDiscoveryContext.Provider>;
}

export function useCobolDiscovery() {
  const ctx = useContext(CobolDiscoveryContext);
  if (!ctx) throw new Error("useCobolDiscovery must be used within CobolDiscoveryProvider");
  return ctx;
}
