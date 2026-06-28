"use client";

import { useCallback, useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { brokerApi, type RemoteAirflowTrustBinding } from "@/lib/broker-api";
import { useRuntimeAuthority } from "@/contexts/runtime-authority-context";

export function PeerTrustBindingsPanel({ tenantId }: { tenantId: string }) {
  const { authority } = useRuntimeAuthority();
  const [rows, setRows] = useState<RemoteAirflowTrustBinding[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [form, setForm] = useState({
    environment: "dev",
    invokerPersona: authority?.activePersona ?? "GCP_PULSE",
    targetOwnerPersona: authority?.activePersona === "DPC_PULSE" ? "GCP_PULSE" : "DPC_PULSE",
    federatedTenantKey: "",
    airflowBaseUrl: "",
    issuer: "",
    audience: "",
    inboundSharedSecretRef: "",
    outboundSecretRef: "",
  });

  const load = useCallback(async () => {
    setRows(await brokerApi.listTrustBindings(tenantId));
  }, [tenantId]);

  useEffect(() => {
    void load().catch((err) => setError(err.message));
  }, [load]);

  async function save() {
    setSaving(true);
    setError(null);
    try {
      await brokerApi.createTrustBinding(tenantId, form);
      setForm((current) => ({ ...current, federatedTenantKey: "", airflowBaseUrl: "" }));
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to save trust binding");
    } finally {
      setSaving(false);
    }
  }

  async function validate(id: string) {
    setError(null);
    try {
      await brokerApi.validateTrustBinding(id);
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Validation failed");
    }
  }

  async function refreshMirror() {
    setError(null);
    try {
      await brokerApi.syncMirror(tenantId, form.environment);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Mirror refresh failed");
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Remote Airflow Targets</CardTitle>
        <CardDescription>Design-time Airflow target metadata and runtime connection references.</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {error && <p className="text-sm text-destructive">{error}</p>}
        <div className="grid gap-3 md:grid-cols-2">
          <Field label="Environment" value={form.environment} onChange={(environment) => setForm({ ...form, environment })} />
          <Field label="Federated tenant key" value={form.federatedTenantKey} onChange={(federatedTenantKey) => setForm({ ...form, federatedTenantKey })} />
          <Field label="Airflow base URL" value={form.airflowBaseUrl} onChange={(airflowBaseUrl) => setForm({ ...form, airflowBaseUrl })} />
          <Field label="Issuer" value={form.issuer} onChange={(issuer) => setForm({ ...form, issuer })} />
          <Field label="Audience" value={form.audience} onChange={(audience) => setForm({ ...form, audience })} />
          <Field label="Inbound secret ref" value={form.inboundSharedSecretRef} onChange={(inboundSharedSecretRef) => setForm({ ...form, inboundSharedSecretRef })} />
          <Field label="Outbound secret ref" value={form.outboundSecretRef} onChange={(outboundSecretRef) => setForm({ ...form, outboundSecretRef })} />
        </div>
        <div className="flex flex-wrap gap-2">
          <Button onClick={save} disabled={saving}>Add binding</Button>
          <Button variant="outline" onClick={refreshMirror}>Refresh mirror</Button>
        </div>
        <div className="space-y-2">
          {rows.map((row) => (
            <div key={row.id} className="flex items-center justify-between rounded-md border p-3 text-sm">
              <div>
                <div className="font-medium">{row.environment} · {row.targetOwnerPersona}</div>
                <div className="text-muted-foreground">{row.federatedTenantKey} · {row.status}</div>
                {row.validationError && <div className="text-destructive">{row.validationError}</div>}
              </div>
              <Button size="sm" variant="outline" onClick={() => validate(row.id)}>Validate</Button>
            </div>
          ))}
        </div>
      </CardContent>
    </Card>
  );
}

function Field({ label, value, onChange }: { label: string; value: string; onChange: (value: string) => void }) {
  return (
    <div className="space-y-1">
      <Label>{label}</Label>
      <Input value={value} onChange={(event) => onChange(event.target.value)} />
    </div>
  );
}
