"use client";

import React, {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useState,
} from "react";
import type { User } from "@/types";
import { api, clearPersistedPulseState } from "@/lib/api";

interface AuthContextValue {
  user: User | null;
  loading: boolean;
  login: (email: string, password: string) => Promise<void>;
  logout: () => void;
  error: string | null;
}

const AuthContext = createContext<AuthContextValue>({
  user: null,
  loading: true,
  login: async () => {},
  logout: () => {},
  error: null,
});

interface LoginResponse {
  accessToken: string;
  user: User;
}

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const logout = useCallback(() => {
    // PKT-FINAL-3 (BUG-09b): clearing only `pulse_token` left stale
    // `pulse.*` keys that pinned the operator to /login in a redirect
    // loop. Purge every `pulse_*` / `pulse.*` key so subsequent route
    // changes start from a known-good "unauthenticated, free to
    // navigate" state.
    clearPersistedPulseState();
    setUser(null);
    setError(null);
  }, []);

  useEffect(() => {
    const handler = () => logout();
    window.addEventListener("pulse:logout", handler);
    return () => window.removeEventListener("pulse:logout", handler);
  }, [logout]);

  useEffect(() => {
    async function restore() {
      const token = localStorage.getItem("pulse_token");
      if (!token) return;
      try {
        const u = await api.get<User>("/api/v1/auth/me");
        setUser(u);
      } catch {
        localStorage.removeItem("pulse_token");
      }
    }
    restore().finally(() => setLoading(false));
  }, []);

  const login = useCallback(async (email: string, password: string) => {
    setError(null);
    try {
      const res = await api.post<LoginResponse>("/api/v1/auth/login", {
        email,
        password,
      });
      localStorage.setItem("pulse_token", res.accessToken);
      setUser(res.user);
    } catch (e) {
      const msg =
        e instanceof Error ? e.message : "Login failed";
      setError(msg);
      throw e;
    }
  }, []);

  return (
    <AuthContext.Provider value={{ user, loading, login, logout, error }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  return useContext(AuthContext);
}
