import type { Domain, Tenant } from "@/types";

export interface DomainOption {
  id?: string;
  name: string;
  description?: string;
  source: "catalog" | "legacy" | "shim";
}

export function toDomainOptionValue(option: DomainOption): string {
  return option.id ?? `legacy:${option.name}`;
}

export function resolveDomainOptionByValue(
  value: string | null | undefined,
  options: DomainOption[]
): DomainOption | undefined {
  if (!value) return undefined;
  return options.find(
    (option) =>
      toDomainOptionValue(option) === value ||
      option.id === value
  );
}

export function normalizeTenantDomains(
  _tenant: Tenant | null | undefined,
  domains: Domain[] = []
): DomainOption[] {
  const options: DomainOption[] = [];
  const seen = new Set<string>();

  for (const domain of domains) {
    const key = domain.id || domain.name.toLowerCase();
    if (seen.has(key)) continue;
    seen.add(key);
    options.push({
      id: domain.id,
      name: domain.name,
      description: domain.description,
      source: "catalog",
    });
  }

  return options.sort((a, b) => a.name.localeCompare(b.name));
}

export function resolveDomainName(
  value: { domainId?: string | null; domainName?: string | null },
  options: DomainOption[] = []
): string {
  if (value.domainId) {
    const matched = options.find((option) => option.id === value.domainId);
    if (matched?.name) return matched.name;
  }

  if (value.domainName?.trim()) {
    return value.domainName.trim();
  }

  return "Unassigned";
}
