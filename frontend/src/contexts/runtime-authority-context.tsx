"use client";

import React, { createContext, useContext, useEffect, useState } from "react";
import type { RuntimeAuthority } from "@/types";
import { api } from "@/lib/api";

interface RuntimeAuthorityContextValue {
  authority: RuntimeAuthority | null;
  loading: boolean;
  error: string | null;
}

const RuntimeAuthorityContext = createContext<RuntimeAuthorityContextValue>({
  authority: null,
  loading: true,
  error: null,
});

export function RuntimeAuthorityProvider({
  children,
}: {
  children: React.ReactNode;
}) {
  const [authority, setAuthority] = useState<RuntimeAuthority | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    api
      .get<RuntimeAuthority>("/api/v1/runtime-authority")
      .then((data) => {
        if (!cancelled) {
          setAuthority(data);
          setLoading(false);
        }
      })
      .catch((err) => {
        if (!cancelled) {
          setError(err.message);
          setLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <RuntimeAuthorityContext.Provider value={{ authority, loading, error }}>
      {children}
    </RuntimeAuthorityContext.Provider>
  );
}

export function useRuntimeAuthority() {
  return useContext(RuntimeAuthorityContext);
}
