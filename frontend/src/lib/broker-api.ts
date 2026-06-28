import { api } from "@/lib/api";

export interface RemoteAirflowTrustBinding {
  id: string;
  environment: string;
  invokerPersona: string;
  targetOwnerPersona: string;
  federatedTenantKey: string;
  airflowBaseUrl: string;
  issuer: string;
  audience: string;
  jwksUri?: string | null;
  status: "UNVALIDATED" | "VALIDATED" | "DISABLED" | "ERROR";
  validatedAt?: string | null;
  validationError?: string | null;
  inboundSecretConfigured: boolean;
  outboundSecretConfigured: boolean;
}

export interface UpsertTrustBindingRequest {
  environment: string;
  invokerPersona: string;
  targetOwnerPersona: string;
  federatedTenantKey: string;
  airflowBaseUrl: string;
  issuer: string;
  audience: string;
  jwksUri?: string;
  inboundSharedSecretRef?: string;
  outboundSecretRef?: string;
}

export interface MirrorSyncResult {
  bindingsSynced: number;
  targetsSynced: number;
}

export const brokerApi = {
  listTrustBindings(tenantId: string) {
    return api.get<RemoteAirflowTrustBinding[]>(`/api/v1/tenants/${tenantId}/broker/trust-bindings`);
  },
  createTrustBinding(tenantId: string, body: UpsertTrustBindingRequest) {
    return api.post<RemoteAirflowTrustBinding>(`/api/v1/tenants/${tenantId}/broker/trust-bindings`, body);
  },
  validateTrustBinding(id: string) {
    return api.post<RemoteAirflowTrustBinding>(`/api/v1/broker/trust-bindings/${id}/validate`, {});
  },
  syncMirror(tenantId: string, environment?: string) {
    const qs = environment ? `?environment=${encodeURIComponent(environment)}` : "";
    return api.post<MirrorSyncResult>(`/api/v1/tenants/${tenantId}/broker/mirror/sync${qs}`, {});
  },
};
