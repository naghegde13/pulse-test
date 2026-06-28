/**
 * Static tenant/domain/user fixtures for the producer-onboarding Playwright
 * spec. The spec keeps a single tenant + single domain so the
 * tenant-context auto-selects it and the create-SOR dialog has exactly one
 * domain option (lowest-friction path through the UI).
 */

export const TENANT_ID = "tenant-test-acme";
export const DOMAIN_ID = "domain-test-lending";

export const TENANT_FIXTURE = {
  id: TENANT_ID,
  name: "Test Acme",
  slug: "test-acme",
  domains: [],
};

export const DOMAIN_FIXTURE = {
  id: DOMAIN_ID,
  tenantId: TENANT_ID,
  name: "Lending",
  description: "Lending domain",
  createdAt: "2025-01-01T00:00:00Z",
  updatedAt: "2025-01-01T00:00:00Z",
};

export const STUB_USER = {
  id: "user-playwright",
  email: "playwright@pulse.test",
  displayName: "Playwright Dev",
  role: "DATA_ENGINEER" as const,
  tenantId: TENANT_ID,
  permissions: [],
};
