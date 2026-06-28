/**
 * PKT-FINAL-8 / SU-FINAL-2 — Home Lending tenant fixture.
 *
 * Single source of truth for the rehearsal tenant identifiers used by
 * SU-FINAL-3 (wizard E2E) and SU-FINAL-6 (evidence capture) specs. Keep this
 * in sync with `application.yml` under `pulse.tenants.definitions`.
 */
export const HOME_LENDING_TENANT = {
  id: "tenant-home-lending",
  slug: "home-lending",
  displayName: "Home Lending D&I",
  gcpProject: "pulse-proof-04261847",
  gcpRegion: "us-central1",
  gsmProjectId: "pulse-proof-04261847",
} as const;

export type HomeLendingTenant = typeof HOME_LENDING_TENANT;
