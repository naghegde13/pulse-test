"use client";

import { useEffect, useMemo, useState } from "react";
import { api } from "@/lib/api";
import { normalizeTenantDomains, type DomainOption } from "@/lib/domains";
import type { Domain, Tenant } from "@/types";

export function useTenantDomains(tenant: Tenant | null | undefined) {
  const [domains, setDomains] = useState<Domain[]>([]);

  useEffect(() => {
    if (!tenant?.id) {
      return;
    }

    let cancelled = false;

    api
      .get<Domain[]>(`/api/v1/tenants/${tenant.id}/domains`)
      .then((data) => {
        if (!cancelled) setDomains(data);
      })
      .catch(() => {
        if (!cancelled) setDomains([]);
      });

    return () => {
      cancelled = true;
    };
  }, [tenant?.id]);

  const domainOptions: DomainOption[] = useMemo(
    () => normalizeTenantDomains(tenant, tenant?.id ? domains : []),
    [tenant, domains]
  );

  return { domains, domainOptions };
}
