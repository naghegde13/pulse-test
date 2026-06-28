"use client";

import { useCallback, useEffect, useState, useTransition, use } from "react";
import { useRouter } from "next/navigation";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { useTenant } from "@/contexts/tenant-context";
import { useTenantDomains } from "@/hooks/use-tenant-domains";
import { api } from "@/lib/api";
import { resolveDomainName } from "@/lib/domains";
import type {
  SystemOfRecord,
  ConnectorInstance,
  CredentialProfile,
  CredentialStatus,
  DeployEnvironment,
} from "@/types";
import {
  DEPLOY_ENVIRONMENT_LABELS,
  DEPLOY_ENVIRONMENT_SHORT_LABELS,
} from "@/types";
import { CredentialDialog } from "@/components/sor/credential-dialog";
import { AddConnectorDialog } from "@/components/sor/add-connector-dialog";

// Producer/target detail surfaces are dev-only inputs (locked decision #5
// + #15 in PROJECT_CONTEXT.MD: higher envs are platform-team-managed
// outside PULSE). The 'local' canonical env exists for laptop MinIO and
// is intentionally hidden here.
const ENV_ORDER: DeployEnvironment[] = ["dev", "integration", "uat", "prod"];
const ENV_SHORT = DEPLOY_ENVIRONMENT_SHORT_LABELS;

const CRED_COLOR: Record<CredentialStatus, string> = {
  VALID: "bg-emerald-500",
  UNTESTED: "bg-amber-500",
  INVALID: "bg-red-500",
  EXPIRED: "bg-red-400",
  SKIPPED: "bg-amber-400",
};

export default function TargetDetailPage({
  params,
}: {
  params: Promise<{ targetId: string }>;
}) {
  const { targetId } = use(params);
  const router = useRouter();
  const { currentTenant } = useTenant();
  const { domainOptions } = useTenantDomains(currentTenant);
  const [target, setTarget] = useState<SystemOfRecord | null>(null);
  const [connectors, setConnectors] = useState<ConnectorInstance[]>([]);
  const [credentialsByConnector, setCredentialsByConnector] = useState<Record<string, CredentialProfile[]>>({});
  const [credDialogOpen, setCredDialogOpen] = useState(false);
  const [credConnectorId, setCredConnectorId] = useState<string | null>(null);
  const [credConnectorDefId, setCredConnectorDefId] = useState<string | null>(null);
  const [credEnvironment, setCredEnvironment] = useState<DeployEnvironment>("dev");
  const [credExisting, setCredExisting] = useState<CredentialProfile | null>(null);
  const [addConnectorOpen, setAddConnectorOpen] = useState(false);
  const [, startTransition] = useTransition();
  const [loading, setLoading] = useState(true);

  const fetchTargetData = useCallback(async (tenantId: string) => {
    setLoading(true);
    try {
      const [targetData, connData] = await Promise.all([
        api.get<SystemOfRecord>(`/api/v1/tenants/${tenantId}/targets/${targetId}`),
        api.get<ConnectorInstance[]>(`/api/v1/sors/${targetId}/connectors`),
      ]);
      setTarget(targetData);
      setConnectors(connData);

      const credMap: Record<string, CredentialProfile[]> = {};
      await Promise.all(
        connData.map(async (ci) => {
          const credentials = await api
            .get<CredentialProfile[]>(`/api/v1/connector-instances/${ci.id}/credentials`)
            .catch(() => [] as CredentialProfile[]);
          credMap[ci.id] = credentials;
        })
      );
      setCredentialsByConnector(credMap);
    } catch {
      setTarget(null);
      setConnectors([]);
    } finally {
      setLoading(false);
    }
  }, [targetId]);

  useEffect(() => {
    if (!currentTenant) return;
    startTransition(() => {
      fetchTargetData(currentTenant.id);
    });
  }, [currentTenant, fetchTargetData]);

  const handleOpenCredentials = (
    connectorId: string,
    connectorDefId: string,
    env: DeployEnvironment
  ) => {
    const existing = credentialsByConnector[connectorId]?.find(
      (c) => c.environment === env
    );
    setCredConnectorId(connectorId);
    setCredConnectorDefId(connectorDefId);
    setCredEnvironment(env);
    setCredExisting(existing || null);
    setCredDialogOpen(true);
  };

  const handleCredentialSaved = (credential: CredentialProfile) => {
    setCredentialsByConnector((prev) => {
      const existing = prev[credential.connectorInstanceId] || [];
      const idx = existing.findIndex((c) => c.environment === credential.environment);
      const updated = idx >= 0
        ? existing.map((c, i) => (i === idx ? credential : c))
        : [...existing, credential];
      return { ...prev, [credential.connectorInstanceId]: updated };
    });
    setConnectors((prev) =>
      prev.map((ci): ConnectorInstance =>
        ci.id === credential.connectorInstanceId
          ? {
              ...ci,
              credentialStatuses: {
                ...ci.credentialStatuses,
                [credential.environment]: credential.status,
              } as Record<DeployEnvironment, CredentialStatus>,
            }
          : ci
      )
    );
  };

  const handleConnectorCreated = (connector: ConnectorInstance) => {
    setConnectors((prev) => [...prev, connector]);
  };

  const handleDeleteConnector = async (connectorId: string, connectorName: string) => {
    if (!window.confirm(`Delete connector "${connectorName}"?`)) return;
    try {
      await api.delete(`/api/v1/sors/${targetId}/connectors/${connectorId}`);
      setConnectors((prev) => prev.filter((c) => c.id !== connectorId));
    } catch (err) {
      alert(err instanceof Error ? err.message : "Failed to delete connector");
    }
  };

  if (loading) {
    return (
      <div className="space-y-4">
        <div className="h-8 w-64 bg-muted animate-pulse rounded" />
        <div className="h-40 bg-muted animate-pulse rounded" />
      </div>
    );
  }

  if (!target) {
    return (
      <div className="space-y-4">
        <p className="text-muted-foreground">Sink target not found.</p>
        <Button variant="outline" onClick={() => router.push("/targets")}>
          Back to Targets
        </Button>
      </div>
    );
  }

  const targetDomainName = resolveDomainName(target, domainOptions);

  return (
    <div className="space-y-6">
      <div className="flex items-start justify-between">
        <div>
          <div className="flex items-center gap-2 mb-1">
            <Button
              variant="ghost"
              size="sm"
              className="h-7 px-2 text-muted-foreground"
              onClick={() => router.push("/targets")}
            >
              Sink Targets
            </Button>
            <span className="text-muted-foreground">/</span>
            <h2 className="text-2xl font-bold tracking-tight">{target.name}</h2>
          </div>
          {target.description && <p className="text-muted-foreground">{target.description}</p>}
          <div className="flex gap-2 mt-2">
            <Badge variant="outline">{targetDomainName}</Badge>
            <Badge variant="secondary">
              {connectors.length} connector{connectors.length !== 1 ? "s" : ""}
            </Badge>
          </div>
        </div>
      </div>

      <div className="space-y-3">
        <div className="flex items-center justify-between">
          <h3 className="text-lg font-semibold">Connectors</h3>
          <Button size="sm" onClick={() => setAddConnectorOpen(true)}>
            Add Connector
          </Button>
        </div>
        {connectors.length === 0 ? (
          <Card>
            <CardHeader>
              <CardTitle className="text-sm">No connectors configured</CardTitle>
              <CardDescription>
                Add a connector to associate a physical destination with this sink target.
              </CardDescription>
            </CardHeader>
          </Card>
        ) : (
          connectors.map((ci) => (
            <Card key={ci.id} className="overflow-hidden">
              <CardContent className="flex items-center justify-between p-4">
                <div className="flex items-center gap-3">
                  <div className="w-8 h-8 rounded bg-muted flex items-center justify-center text-xs font-bold">
                    {(ci.connectorTypeName || ci.name).substring(0, 2).toUpperCase()}
                  </div>
                  <div>
                    <div className="flex items-center gap-2">
                      <span className="font-medium text-sm">{ci.name}</span>
                      {ci.connectorTypeName && (
                        <Badge variant="outline" className="text-[10px]">
                          {ci.connectorTypeName}
                        </Badge>
                      )}
                      <Badge
                        variant={ci.enabled ? "secondary" : "destructive"}
                        className="text-[10px]"
                      >
                        {ci.enabled ? "Active" : "Disabled"}
                      </Badge>
                    </div>
                    {ci.description && (
                      <p className="text-xs text-muted-foreground mt-0.5">{ci.description}</p>
                    )}
                  </div>
                </div>
                <div className="flex items-center gap-4">
                  <div className="flex gap-1.5">
                    {ENV_ORDER.map((env) => {
                      const status = ci.credentialStatuses?.[env];
                      const envLabel = DEPLOY_ENVIRONMENT_LABELS[env];
                      return (
                        <button
                          key={env}
                          type="button"
                          className="flex flex-col items-center gap-0.5 hover:opacity-80 transition-opacity"
                          title={`${envLabel}: ${status || "Not configured"} — click to manage`}
                          onClick={() =>
                            handleOpenCredentials(ci.id, ci.connectorDefinitionId, env)
                          }
                        >
                          <div
                            className={`w-2.5 h-2.5 rounded-full ${
                              status ? CRED_COLOR[status] : "bg-muted-foreground/20"
                            }`}
                          />
                          <span className="text-[9px] text-muted-foreground">
                            {ENV_SHORT[env]}
                          </span>
                        </button>
                      );
                    })}
                  </div>
                  <Button
                    variant="ghost"
                    size="sm"
                    className="h-7 px-2 text-xs text-destructive hover:text-destructive"
                    onClick={() => handleDeleteConnector(ci.id, ci.name)}
                  >
                    Delete
                  </Button>
                </div>
              </CardContent>
            </Card>
          ))
        )}
      </div>

      {credConnectorId && credConnectorDefId && (
        <CredentialDialog
          open={credDialogOpen}
          onOpenChange={setCredDialogOpen}
          connectorInstanceId={credConnectorId}
          connectorDefinitionId={credConnectorDefId}
          environment={credEnvironment}
          existingCredential={credExisting}
          onSaved={handleCredentialSaved}
        />
      )}

      <AddConnectorDialog
        open={addConnectorOpen}
        onOpenChange={setAddConnectorOpen}
        sorId={targetId}
        onCreated={handleConnectorCreated}
        connectorTypeFilter="DESTINATION"
      />
    </div>
  );
}
