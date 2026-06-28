"use client";

import React, { createContext, useContext, useState, useEffect, useCallback } from "react";
import type { Tenant } from "@/types";
import { api } from "@/lib/api";

interface TenantContextValue {
  tenants: Tenant[];
  currentTenant: Tenant | null;
  setCurrentTenant: (tenant: Tenant) => void;
  refreshTenant: () => void;
  loading: boolean;
}

const TenantContext = createContext<TenantContextValue>({
  tenants: [],
  currentTenant: null,
  setCurrentTenant: () => {},
  refreshTenant: () => {},
  loading: true,
});

const FALLBACK_TENANTS: Tenant[] = [
  {
    id: "tenant-home-lending",
    name: "Acme Corp",
    slug: "home-lending",
    domains: [],
  },
  {
    id: "tenant-unsecured-lending",
    name: "Globex Industries",
    slug: "unsecured-lending",
    domains: [],
  },
];

// Persist the user's selected tenant ID across refresh / dev-server-restart /
// tab-close-reopen so they don't keep getting bumped back to "Default Tenant".
// Prior behavior: every mount picked tenants[0] (the API's first tenant,
// which is the seeded "Default Tenant"), so the user had to manually
// switch every time they refreshed.
const TENANT_STORAGE_KEY = "pulse.currentTenantId";

function readPersistedTenantId(): string | null {
  if (typeof window === "undefined") return null;
  try {
    return window.localStorage.getItem(TENANT_STORAGE_KEY);
  } catch {
    return null;
  }
}

function writePersistedTenantId(id: string | null) {
  if (typeof window === "undefined") return;
  try {
    if (id) window.localStorage.setItem(TENANT_STORAGE_KEY, id);
    else window.localStorage.removeItem(TENANT_STORAGE_KEY);
  } catch {
    // Quota / disabled storage — silently no-op.
  }
}

export function TenantProvider({ children }: { children: React.ReactNode }) {
  const [tenants, setTenants] = useState<Tenant[]>([]);
  const [currentTenant, setCurrentTenantState] = useState<Tenant | null>(null);
  const [loading, setLoading] = useState(true);

  // Wrap the setter so EVERY tenant change persists. Direct setter
  // (setCurrentTenantState) is kept private to this provider; consumers
  // get the wrapped version.
  const setCurrentTenant = useCallback((tenant: Tenant) => {
    setCurrentTenantState(tenant);
    writePersistedTenantId(tenant?.id ?? null);
  }, []);

  useEffect(() => {
    const persistedId = readPersistedTenantId();
    api
      .get<Tenant[]>("/api/v1/tenants")
      .then((data) => {
        setTenants(data);
        if (data.length === 0) return;
        // Prefer the persisted tenant if it still exists in the list;
        // otherwise fall back to the API's first tenant.
        const restored = persistedId
          ? data.find((t) => t.id === persistedId)
          : null;
        const chosen = restored ?? data[0];
        setCurrentTenantState(chosen);
        writePersistedTenantId(chosen.id);
      })
      .catch(() => {
        setTenants(FALLBACK_TENANTS);
        const restored = persistedId
          ? FALLBACK_TENANTS.find((t) => t.id === persistedId)
          : null;
        const chosen = restored ?? FALLBACK_TENANTS[0];
        setCurrentTenantState(chosen);
        writePersistedTenantId(chosen.id);
      })
      .finally(() => setLoading(false));
  }, []);

  const refreshTenant = useCallback(() => {
    api
      .get<Tenant[]>("/api/v1/tenants")
      .then((data) => {
        setTenants(data);
        const updated = data.find((t) => t.id === currentTenant?.id);
        if (updated) setCurrentTenantState(updated);
      })
      .catch(() => {});
  }, [currentTenant?.id]);

  return (
    <TenantContext.Provider
      value={{ tenants, currentTenant, setCurrentTenant, refreshTenant, loading }}
    >
      {children}
    </TenantContext.Provider>
  );
}

export function useTenant() {
  return useContext(TenantContext);
}
