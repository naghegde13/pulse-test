"use client";

import { useCallback, useEffect, useState, useTransition, use } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { useTenant } from "@/contexts/tenant-context";
import { useTenantDomains } from "@/hooks/use-tenant-domains";
import { api } from "@/lib/api";
import { resolveDomainName } from "@/lib/domains";
import { onSorEvent } from "@/lib/sor-events";
import type {
  SystemOfRecord,
  ConnectorInstance,
  ConnectorCredentialStrategy,
  Dataset,
  CredentialProfile,
  CredentialStatus,
  DeployEnvironment,
} from "@/types";
import {
  DEPLOY_ENVIRONMENT_LABELS,
  DEPLOY_ENVIRONMENT_SHORT_LABELS,
  normalizeDeployEnvironment,
} from "@/types";
import { DefineDatasetDialog } from "@/components/sor/define-dataset-dialog";
import { CredentialDialog } from "@/components/sor/credential-dialog";
import { AddConnectorDialog } from "@/components/sor/add-connector-dialog";

// Producer surfaces are dev-only inputs in PULSE chat (locked decision #5
// + #15 in PROJECT_CONTEXT.MD: higher envs are platform-team-managed).
// 'local' is laptop-only and intentionally hidden here.
const ENV_ORDER: DeployEnvironment[] = ["dev", "integration", "uat", "prod"];
const ENV_SHORT = DEPLOY_ENVIRONMENT_SHORT_LABELS;

const CRED_COLOR: Record<CredentialStatus, string> = {
  VALID: "bg-emerald-500",
  UNTESTED: "bg-amber-500",
  INVALID: "bg-red-500",
  EXPIRED: "bg-red-400",
  SKIPPED: "bg-amber-400",
};

export default function SORDetailPage({ params }: { params: Promise<{ sorId: string }> }) {
  const { sorId } = use(params);
  const router = useRouter();
  const searchParams = useSearchParams();
  const { currentTenant } = useTenant();
  const { domainOptions } = useTenantDomains(currentTenant);
  const [sor, setSor] = useState<SystemOfRecord | null>(null);
  const [connectors, setConnectors] = useState<ConnectorInstance[]>([]);
  const [datasetsByConnector, setDatasetsByConnector] = useState<Record<string, Dataset[]>>({});
  const [sorDatasets, setSorDatasets] = useState<Dataset[]>([]);
  const [expandedConnector, setExpandedConnector] = useState<string | null>(null);
  const [defineDialogOpen, setDefineDialogOpen] = useState(false);
  const [defineConnectorId, setDefineConnectorId] = useState<string | null>(null);
  const [defineConnectorDefId, setDefineConnectorDefId] = useState<string | null>(null);
  const [credDialogOpen, setCredDialogOpen] = useState(false);
  const [credConnectorId, setCredConnectorId] = useState<string | null>(null);
  const [credConnectorDefId, setCredConnectorDefId] = useState<string | null>(null);
  const [credEnvironment, setCredEnvironment] = useState<DeployEnvironment>("dev");
  const [credExisting, setCredExisting] = useState<CredentialProfile | null>(null);
  const [credStrategy, setCredStrategy] = useState<ConnectorCredentialStrategy | undefined>(undefined);
  const [credentialsByConnector, setCredentialsByConnector] = useState<Record<string, CredentialProfile[]>>({});
  const [addConnectorOpen, setAddConnectorOpen] = useState(false);
  const [editingSorName, setEditingSorName] = useState(false);
  const [editSorName, setEditSorName] = useState("");
  const [editSorDesc, setEditSorDesc] = useState("");
  const [savingSor, setSavingSor] = useState(false);
  const [editingDataset, setEditingDataset] = useState<string | null>(null);
  const [editDatasetName, setEditDatasetName] = useState("");
  const [editDatasetDesc, setEditDatasetDesc] = useState("");
  const [expandedSchema, setExpandedSchema] = useState<string | null>(null);
  // V99 + #82: connector_definitions lookup so we can detect Family A
  // (object-storage; empty connection_spec.properties → no user creds)
  // vs Family B (external SOR; user-entered creds via the credential
  // dialog, dev only per SoD).
  const [connectorDefSpecs, setConnectorDefSpecs] = useState<
    Record<string, { properties?: Record<string, unknown> } | null>
  >({});
  const [, startTransition] = useTransition();
  const [loading, setLoading] = useState(true);

  // Family A connectors have empty connection_spec.properties (object
  // storage — auth is storage_backend-managed). Family B connectors
  // have at least one property (external SOR — user enters creds in
  // the credential dialog for DEV; higher envs are platform-team).
  const isFamilyAConnector = (connectorDefinitionId: string): boolean => {
    const spec = connectorDefSpecs[connectorDefinitionId];
    if (!spec) return false; // unknown → render full UI
    const props = spec.properties || {};
    return Object.keys(props).length === 0;
  };

  const fetchSorData = useCallback(async (tenantId: string, showLoading = true) => {
    if (showLoading) setLoading(true);
    try {
      const [sorData, connData, allSorDatasets, defs] = await Promise.all([
        api.get<SystemOfRecord>(`/api/v1/tenants/${tenantId}/sors/${sorId}`),
        api.get<ConnectorInstance[]>(`/api/v1/sors/${sorId}/connectors`),
        api.get<Dataset[]>(`/api/v1/tenants/${tenantId}/datasets?sorId=${sorId}`),
        api.get<Array<{ id: string; connectionSpec?: { properties?: Record<string, unknown> } }>>(
          `/api/v1/connectors`
        ),
      ]);
      setSor(sorData);
      setConnectors(connData);
      setSorDatasets(allSorDatasets);
      const specMap: Record<string, { properties?: Record<string, unknown> } | null> = {};
      defs.forEach((d) => { specMap[d.id] = d.connectionSpec ?? null; });
      setConnectorDefSpecs(specMap);

      const dsMap: Record<string, Dataset[]> = {};
      const credMap: Record<string, CredentialProfile[]> = {};
      await Promise.all(
        connData.map(async (ci) => {
          const [datasets, credentials] = await Promise.all([
            api.get<Dataset[]>(
              `/api/v1/tenants/${tenantId}/datasets?connectorInstanceId=${ci.id}`
            ),
            api.get<CredentialProfile[]>(
              `/api/v1/connector-instances/${ci.id}/credentials`
            ).catch(() => [] as CredentialProfile[]),
          ]);
          dsMap[ci.id] = datasets;
          credMap[ci.id] = credentials;
        })
      );
      setDatasetsByConnector(dsMap);
      setCredentialsByConnector(credMap);
      if (connData.length > 0 && showLoading) setExpandedConnector(connData[0].id);
    } catch {
      if (showLoading) {
        setSor(null);
        setConnectors([]);
      }
    } finally {
      setLoading(false);
    }
  }, [sorId]);

  useEffect(() => {
    if (!currentTenant) return;
    startTransition(() => { fetchSorData(currentTenant.id); });
  }, [currentTenant, fetchSorData]);

  // Listen for chat-triggered SOR changes (dataset/connector created by AI)
  useEffect(() => {
    if (!currentTenant) return;
    return onSorEvent(() => {
      fetchSorData(currentTenant.id, false);
    });
  }, [currentTenant, fetchSorData]);

  // Chat-driven credential attach: when the chat agent calls
  // request_credential_attach, the navigation lands here with
  // ?attach_credential={ciId}&env=DEV. Open the credential dialog
  // automatically once the connector list has loaded.
  useEffect(() => {
    const targetCiId = searchParams.get("attach_credential");
    if (targetCiId?.startsWith("draft:")) return;
    if (!targetCiId || connectors.length === 0) return;
    const target = connectors.find((c) => c.id === targetCiId);
    if (!target) return;
    // The chat agent historically passed env=DEV/PRODUCTION (uppercase).
    // Normalize before comparing against the canonical ENV_ORDER.
    const rawEnvParam = searchParams.get("env");
    const normalized = normalizeDeployEnvironment(rawEnvParam);
    const env: DeployEnvironment =
      normalized && ENV_ORDER.includes(normalized) ? normalized : "dev";
    const existing = credentialsByConnector[target.id]?.find((c) => c.environment === env);
    setExpandedConnector(target.id);
    setCredConnectorId(target.id);
    setCredConnectorDefId(target.connectorDefinitionId);
    setCredEnvironment(env);
    setCredExisting(existing || null);
    setCredDialogOpen(true);
    // Clear the params so the dialog doesn't reopen on subsequent
    // tenant/connector refreshes.
    router.replace(`/producers/${sorId}`);
  }, [searchParams, connectors, credentialsByConnector, router, sorId]);

  const handleDefineDataset = (connectorId?: string, connectorDefId?: string) => {
    setDefineConnectorId(connectorId || null);
    setDefineConnectorDefId(connectorDefId || null);
    setDefineDialogOpen(true);
  };

  const handleOpenCredentials = (
    connectorId: string,
    connectorDefId: string,
    env: DeployEnvironment,
    strategy?: ConnectorCredentialStrategy
  ) => {
    const existing = credentialsByConnector[connectorId]?.find(
      (c) => c.environment === env
    );
    setCredConnectorId(connectorId);
    setCredConnectorDefId(connectorDefId);
    setCredEnvironment(env);
    setCredExisting(existing || null);
    setCredStrategy(strategy);
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
    // Update credential status on the connector instance
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

  const handleDatasetCreated = (dataset: Dataset) => {
    setDatasetsByConnector((prev) => ({
      ...prev,
      [dataset.connectorInstanceId]: [
        ...(prev[dataset.connectorInstanceId] || []),
        dataset,
      ],
    }));
  };

  const handleConnectorCreated = (connector: ConnectorInstance) => {
    setConnectors((prev) => [...prev, connector]);
    setDatasetsByConnector((prev) => ({ ...prev, [connector.id]: [] }));
    setExpandedConnector(connector.id);
  };

  const handleDeleteSor = async () => {
    if (!currentTenant || !sor) return;
    if (!window.confirm(`Delete "${sor.name}"? This will remove all connectors and datasets.`)) return;
    try {
      await api.delete(`/api/v1/tenants/${currentTenant.id}/sors/${sorId}`);
      router.push("/producers");
    } catch (err) {
      alert(err instanceof Error ? err.message : "Failed to delete data source");
    }
  };

  const handleEditSor = () => {
    if (!sor) return;
    setEditSorName(sor.name);
    setEditSorDesc(sor.description ?? "");
    setEditingSorName(true);
  };

  const handleSaveSor = async () => {
    if (!currentTenant || !sor || !editSorName.trim()) return;
    setSavingSor(true);
    try {
      const updated = await api.put<SystemOfRecord>(
        `/api/v1/tenants/${currentTenant.id}/sors/${sorId}`,
        { name: editSorName.trim(), description: editSorDesc.trim() || null }
      );
      setSor(updated);
      setEditingSorName(false);
    } catch (err) {
      alert(err instanceof Error ? err.message : "Failed to update data source");
    } finally {
      setSavingSor(false);
    }
  };

  const handleDeleteConnector = async (connectorId: string, connectorName: string) => {
    if (!window.confirm(`Delete connector "${connectorName}"?`)) return;
    try {
      await api.delete(`/api/v1/sors/${sorId}/connectors/${connectorId}`);
      setConnectors((prev) => prev.filter((c) => c.id !== connectorId));
      setDatasetsByConnector((prev) => {
        const next = { ...prev };
        delete next[connectorId];
        return next;
      });
    } catch (err) {
      alert(err instanceof Error ? err.message : "Failed to delete connector");
    }
  };

  const handleEditDataset = (ds: Dataset) => {
    setEditingDataset(ds.id);
    setEditDatasetName(ds.name);
    setEditDatasetDesc(ds.description ?? "");
  };

  const handleSaveDataset = async (datasetId: string, connectorInstanceId: string) => {
    if (!editDatasetName.trim()) return;
    try {
      const updated = await api.put<Dataset>(
        `/api/v1/datasets/${datasetId}`,
        { name: editDatasetName.trim(), description: editDatasetDesc.trim() || null }
      );
      setSorDatasets((prev) => prev.map((ds) => ds.id === datasetId ? updated : ds));
      if (connectorInstanceId) {
        setDatasetsByConnector((prev) => ({
          ...prev,
          [connectorInstanceId]: (prev[connectorInstanceId] || []).map((ds) =>
            ds.id === datasetId ? updated : ds
          ),
        }));
      }
      setEditingDataset(null);
    } catch (err) {
      alert(err instanceof Error ? err.message : "Failed to update dataset");
    }
  };

  const handleDeleteDataset = async (datasetId: string, connectorInstanceId: string, datasetName: string) => {
    if (!window.confirm(`Delete dataset "${datasetName}"?`)) return;
    try {
      await api.delete(`/api/v1/datasets/${datasetId}`);
      setSorDatasets((prev) => prev.filter((ds) => ds.id !== datasetId));
      if (connectorInstanceId) {
        setDatasetsByConnector((prev) => ({
          ...prev,
          [connectorInstanceId]: (prev[connectorInstanceId] || []).filter((ds) => ds.id !== datasetId),
        }));
      }
    } catch (err) {
      alert(err instanceof Error ? err.message : "Failed to delete dataset");
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

  if (!sor) {
    return (
      <div className="space-y-4">
        <p className="text-muted-foreground">System of Record not found.</p>
        <Button variant="outline" onClick={() => router.push("/producers")}>
          Back to Data Sources
        </Button>
      </div>
    );
  }

  const sorDomainName = resolveDomainName(sor, domainOptions);

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-start justify-between">
        <div>
          <div className="flex items-center gap-2 mb-1">
            <Button variant="ghost" size="sm" className="h-7 px-2 text-muted-foreground"
                    onClick={() => router.push("/producers")}>
              Data Sources
            </Button>
            <span className="text-muted-foreground">/</span>
            {editingSorName ? (
              <div className="flex items-center gap-2">
                <Input
                  value={editSorName}
                  onChange={(e) => setEditSorName(e.target.value)}
                  className="h-8 text-sm w-56"
                  autoFocus
                />
                <Input
                  value={editSorDesc}
                  onChange={(e) => setEditSorDesc(e.target.value)}
                  placeholder="Description (optional)"
                  className="h-8 text-sm w-64"
                />
                <Button size="sm" className="h-8" onClick={handleSaveSor} disabled={savingSor || !editSorName.trim()}>
                  {savingSor ? "Saving..." : "Save"}
                </Button>
                <Button variant="outline" size="sm" className="h-8" onClick={() => setEditingSorName(false)}>
                  Cancel
                </Button>
              </div>
            ) : (
              <h2 className="text-2xl font-bold tracking-tight">{sor.name}</h2>
            )}
          </div>
          {!editingSorName && <p className="text-muted-foreground">{sor.description}</p>}
          <div className="flex gap-2 mt-2">
            <Badge variant="outline">{sorDomainName}</Badge>
            <Badge variant="secondary">
              {connectors.length} connector{connectors.length !== 1 ? "s" : ""}
            </Badge>
            <Badge variant="secondary">
              {Object.values(datasetsByConnector).flat().length} dataset
              {Object.values(datasetsByConnector).flat().length !== 1 ? "s" : ""}
            </Badge>
          </div>
        </div>
        <div className="flex gap-2">
          <Button variant="outline" size="sm" onClick={handleEditSor}>
            Edit
          </Button>
          <Button variant="destructive" size="sm" onClick={handleDeleteSor}>
            Delete
          </Button>
        </div>
      </div>

      {/* Datasets (SOR-level) */}
      <div className="space-y-3">
        <div className="flex items-center justify-between">
          <h3 className="text-lg font-semibold">Datasets</h3>
          <Button size="sm" onClick={() => handleDefineDataset()}>
            Define Dataset
          </Button>
        </div>
        {sorDatasets.length === 0 ? (
          <Card>
            <CardHeader>
              <CardTitle className="text-sm">No datasets defined</CardTitle>
              <CardDescription>Define a dataset to describe the data this source provides.</CardDescription>
            </CardHeader>
          </Card>
        ) : (
          <div className="space-y-1.5">
            {sorDatasets.map((ds) => {
              const schemaFields = (ds.schemaSnapshot as { fields?: Array<{ name: string; type: string; description?: string }> })?.fields;
              const isSchemaExpanded = expandedSchema === ds.id;
              return (
                <Card key={ds.id} className="overflow-hidden">
                  <div className="flex items-center justify-between p-3">
                    <div className="flex items-center gap-2 min-w-0 flex-1">
                      {editingDataset === ds.id ? (
                        <div className="flex items-center gap-2">
                          <Input value={editDatasetName} onChange={(e) => setEditDatasetName(e.target.value)} className="h-7 text-sm w-48" />
                          <Input value={editDatasetDesc} onChange={(e) => setEditDatasetDesc(e.target.value)} placeholder="Description" className="h-7 text-sm w-48" />
                          <Button size="sm" className="h-7 text-xs" onClick={() => handleSaveDataset(ds.id, ds.connectorInstanceId || "")}>Save</Button>
                          <Button variant="ghost" size="sm" className="h-7 text-xs" onClick={() => setEditingDataset(null)}>Cancel</Button>
                        </div>
                      ) : (
                        <button
                          type="button"
                          className="flex items-center gap-2 text-left min-w-0"
                          onClick={() => setExpandedSchema(isSchemaExpanded ? null : ds.id)}
                        >
                          <svg
                            className={`w-3.5 h-3.5 shrink-0 text-muted-foreground transition-transform ${isSchemaExpanded ? "rotate-90" : ""}`}
                            fill="none" viewBox="0 0 24 24" stroke="currentColor"
                          >
                            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
                          </svg>
                          <span className="text-sm font-medium truncate">{ds.name}</span>
                          {ds.status && <Badge variant="outline" className="text-[10px] shrink-0">{ds.status}</Badge>}
                          {ds.classification && <Badge variant="secondary" className="text-[10px] shrink-0">{ds.classification}</Badge>}
                          {schemaFields && (
                            <Badge variant="secondary" className="text-[10px] shrink-0">{schemaFields.length} fields</Badge>
                          )}
                          {!ds.connectorInstanceId && (
                            <Badge variant="outline" className="text-[10px] text-muted-foreground shrink-0">No connector</Badge>
                          )}
                        </button>
                      )}
                    </div>
                    {editingDataset !== ds.id && (
                      <div className="flex items-center gap-1 shrink-0 ml-2">
                        <Button variant="ghost" size="sm" className="h-7 text-xs" onClick={() => handleEditDataset(ds)}>Edit</Button>
                        <Button variant="ghost" size="sm" className="h-7 text-xs text-destructive hover:text-destructive"
                          onClick={() => handleDeleteDataset(ds.id, ds.connectorInstanceId || "", ds.name)}>Delete</Button>
                      </div>
                    )}
                  </div>
                  {isSchemaExpanded && (
                    <div className="border-t px-3 py-3 bg-muted/20">
                      {ds.description && <p className="text-xs text-muted-foreground mb-2">{ds.description}</p>}
                      {ds.qualifiedName && <p className="text-[11px] text-muted-foreground font-mono mb-2">{ds.qualifiedName}</p>}
                      {schemaFields && schemaFields.length > 0 ? (
                        <div className="rounded-md border overflow-hidden">
                          <table className="w-full text-xs">
                            <thead>
                              <tr className="bg-muted/50 border-b">
                                <th className="text-left px-3 py-1.5 font-medium">Column</th>
                                <th className="text-left px-3 py-1.5 font-medium">Type</th>
                                <th className="text-left px-3 py-1.5 font-medium">Description</th>
                              </tr>
                            </thead>
                            <tbody>
                              {schemaFields.map((f, i) => (
                                <tr key={f.name} className={i % 2 === 0 ? "" : "bg-muted/20"}>
                                  <td className="px-3 py-1.5 font-mono">{f.name}</td>
                                  <td className="px-3 py-1.5 text-muted-foreground">{f.type}</td>
                                  <td className="px-3 py-1.5 text-muted-foreground">{f.description || "\u2014"}</td>
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        </div>
                      ) : (
                        <p className="text-xs text-muted-foreground italic">No schema defined</p>
                      )}
                    </div>
                  )}
                </Card>
              );
            })}
          </div>
        )}
      </div>

      {/* Connectors */}
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
              <CardDescription>Add a connector to start defining datasets.</CardDescription>
            </CardHeader>
          </Card>
        ) : (
          connectors.map((ci) => {
            const datasets = datasetsByConnector[ci.id] || [];
            const isExpanded = expandedConnector === ci.id;
            return (
              <Card key={ci.id} className="overflow-hidden">
                <div
                  className="flex items-center justify-between p-4 cursor-pointer hover:bg-muted/30 transition-colors"
                  onClick={() => setExpandedConnector(isExpanded ? null : ci.id)}
                >
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
                        <Badge variant={ci.enabled ? "secondary" : "destructive"} className="text-[10px]">
                          {ci.enabled ? "Active" : "Disabled"}
                        </Badge>
                        {ci.credentialStrategy === "INHERIT_TENANT_GCP_SERVICE_ACCOUNT" && (
                          <Badge
                            variant="outline"
                            className="text-[10px] bg-blue-500/10 text-blue-700 border-blue-500/20"
                            title="Credentials inherited from tenant GCP service account — no per-connector credential configuration needed"
                          >
                            inherited SA
                          </Badge>
                        )}
                      </div>
                      {ci.description && (
                        <p className="text-xs text-muted-foreground mt-0.5">{ci.description}</p>
                      )}
                    </div>
                  </div>
                  <div className="flex items-center gap-4">
                    {/* Credential status dots – clickable */}
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
                            onClick={(e) => {
                              e.stopPropagation();
                              handleOpenCredentials(ci.id, ci.connectorDefinitionId, env, ci.credentialStrategy);
                            }}
                          >
                            <div className={`w-2.5 h-2.5 rounded-full ${status ? CRED_COLOR[status] : "bg-muted-foreground/20"}`} />
                            <span className="text-[9px] text-muted-foreground">{ENV_SHORT[env]}</span>
                          </button>
                        );
                      })}
                    </div>
                    <Badge variant="outline" className="text-[10px]">
                      {datasets.length} dataset{datasets.length !== 1 ? "s" : ""}
                    </Badge>
                    <Button
                      variant="outline"
                      size="sm"
                      className="h-7 px-2 text-xs"
                      onClick={(e) => {
                        e.stopPropagation();
                        handleDefineDataset(ci.id, ci.connectorDefinitionId);
                      }}
                    >
                      Define Dataset
                    </Button>
                    <Button
                      variant="ghost"
                      size="sm"
                      className="h-7 px-2 text-xs text-destructive hover:text-destructive"
                      onClick={(e) => {
                        e.stopPropagation();
                        handleDeleteConnector(ci.id, ci.name);
                      }}
                    >
                      Delete
                    </Button>
                    <svg
                      className={`w-4 h-4 text-muted-foreground transition-transform ${isExpanded ? "rotate-180" : ""}`}
                      fill="none" viewBox="0 0 24 24" stroke="currentColor"
                    >
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
                    </svg>
                  </div>
                </div>

                {isExpanded && (
                  <CardContent className="border-t pt-4 space-y-3">
                    {/* Credentials section — branched by connector family per #82.
                        Family A (object-storage): empty connection_spec; auth is
                        storage_backend-managed (workload identity / Kerberos).
                        Render an info note instead of configurable cells.
                        Family B (external SOR): credentials per env. Per locked
                        decision #5 + SoD: only DEV is dev-developer-configurable
                        in PULSE; higher envs are platform-team-managed in Secret
                        Manager directly. */}
                    {ci.credentialStrategy === "INHERIT_TENANT_GCP_SERVICE_ACCOUNT" ? (
                      <div className="rounded-md border bg-blue-500/5 border-blue-500/20 px-3 py-2 text-xs space-y-1">
                        <p className="font-medium text-blue-700">Inherited Tenant Credential Strategy</p>
                        <p className="text-blue-700/80">
                          This connector inherits the tenant&apos;s GCP service account. No per-connector
                          credential configuration is needed. The service account is managed in Settings.
                        </p>
                      </div>
                    ) : isFamilyAConnector(ci.connectorDefinitionId) ? (
                      <div className="rounded-md border bg-muted/30 px-3 py-2 text-xs text-muted-foreground">
                        Auth is managed by the storage_backend (workload identity for GCP, Kerberos for DPC).
                        No user credentials needed for object-storage connectors.
                      </div>
                    ) : (
                      <div className="space-y-1.5">
                        <h4 className="text-sm font-medium">Credentials</h4>
                        <div className="grid grid-cols-4 gap-2">
                          {ENV_ORDER.map((env) => {
                            const status = ci.credentialStatuses?.[env];
                            const isDev = env === "dev";
                            const envLabel = DEPLOY_ENVIRONMENT_LABELS[env];
                            const tooltip = isDev
                              ? `${envLabel}: ${status || "Not configured"} — click to manage`
                              : `${envLabel}: managed by platform team in Secret Manager (pulse-{tenant}-{domain}-{kind}-{resource}-{field}-{id} naming convention). PULSE chat is dev-only.`;
                            return (
                              <button
                                key={env}
                                type="button"
                                disabled={!isDev}
                                title={tooltip}
                                className={
                                  "flex items-center justify-between rounded-md border p-2 transition-colors text-left "
                                  + (isDev
                                      ? "hover:bg-muted/50 cursor-pointer"
                                      : "opacity-60 cursor-not-allowed")
                                }
                                onClick={() => {
                                  if (isDev) {
                                    handleOpenCredentials(ci.id, ci.connectorDefinitionId, env, ci.credentialStrategy);
                                  }
                                }}
                              >
                                <div className="flex items-center gap-1.5">
                                  <div className={`w-2 h-2 rounded-full ${status ? CRED_COLOR[status] : "bg-muted-foreground/20"}`} />
                                  <span className="text-xs font-medium">{ENV_SHORT[env]}</span>
                                </div>
                                <span className="text-[10px] text-muted-foreground">
                                  {isDev
                                    ? (status ? status.toLowerCase() : "configure")
                                    : "platform-managed"}
                                </span>
                              </button>
                            );
                          })}
                        </div>
                      </div>
                    )}

                    <div className="flex items-center justify-between">
                      <h4 className="text-sm font-medium">Datasets</h4>
                      <Button
                        size="sm"
                        className="h-7 text-xs"
                        onClick={(e) => {
                          e.stopPropagation();
                          handleDefineDataset(ci.id, ci.connectorDefinitionId);
                        }}
                      >
                        Define Dataset
                      </Button>
                    </div>
                    {datasets.length === 0 ? (
                      <p className="text-xs text-muted-foreground py-2">
                        No datasets defined yet. Click &quot;Define Dataset&quot; to create one.
                      </p>
                    ) : (
                      <div className="space-y-1.5">
                        {datasets.map((ds) => {
                          const cSchemaFields = (ds.schemaSnapshot as { fields?: Array<{ name: string; type: string; description?: string }> })?.fields;
                          const cIsExpanded = expandedSchema === ds.id;
                          return (
                            <div key={ds.id} className="rounded-md border overflow-hidden">
                              <div className="flex items-center justify-between p-3 hover:bg-muted/30 transition-colors">
                                <div className="min-w-0 flex-1">
                                  {editingDataset === ds.id ? (
                                    <div className="flex items-center gap-2">
                                      <Input value={editDatasetName} onChange={(e) => setEditDatasetName(e.target.value)} className="h-7 text-xs w-40" autoFocus />
                                      <Input value={editDatasetDesc} onChange={(e) => setEditDatasetDesc(e.target.value)} placeholder="Description" className="h-7 text-xs w-48" />
                                      <Button size="sm" className="h-7 text-xs" onClick={() => handleSaveDataset(ds.id, ci.id)} disabled={!editDatasetName.trim()}>Save</Button>
                                      <Button variant="outline" size="sm" className="h-7 text-xs" onClick={() => setEditingDataset(null)}>Cancel</Button>
                                    </div>
                                  ) : (
                                    <button
                                      type="button"
                                      className="flex items-center gap-2 text-left min-w-0"
                                      onClick={() => setExpandedSchema(cIsExpanded ? null : ds.id)}
                                    >
                                      <svg
                                        className={`w-3.5 h-3.5 shrink-0 text-muted-foreground transition-transform ${cIsExpanded ? "rotate-90" : ""}`}
                                        fill="none" viewBox="0 0 24 24" stroke="currentColor"
                                      >
                                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
                                      </svg>
                                      <span className="text-sm font-medium truncate">{ds.name}</span>
                                      {ds.definitionType && <Badge variant="outline" className="text-[10px]">{ds.definitionType.replace(/_/g, " ")}</Badge>}
                                      {ds.status && <Badge variant={ds.status === "SCHEMA_DEFINED" ? "secondary" : "outline"} className="text-[10px]">{ds.status.replace(/_/g, " ")}</Badge>}
                                      {cSchemaFields && <Badge variant="secondary" className="text-[10px]">{cSchemaFields.length} fields</Badge>}
                                    </button>
                                  )}
                                </div>
                                <div className="flex gap-1 shrink-0 ml-2 items-center">
                                  {ds.classification && <Badge variant="outline" className="text-[10px]">{ds.classification}</Badge>}
                                  {editingDataset !== ds.id && (
                                    <>
                                      <Button variant="ghost" size="sm" className="h-6 px-1.5 text-[10px]" onClick={() => handleEditDataset(ds)}>Edit</Button>
                                      <Button variant="ghost" size="sm" className="h-6 px-1.5 text-[10px] text-destructive hover:text-destructive" onClick={() => handleDeleteDataset(ds.id, ci.id, ds.name)}>Delete</Button>
                                    </>
                                  )}
                                </div>
                              </div>
                              {cIsExpanded && (
                                <div className="border-t px-3 py-3 bg-muted/20">
                                  {ds.qualifiedName && <p className="text-[11px] text-muted-foreground font-mono mb-2">{ds.qualifiedName}</p>}
                                  {ds.description && <p className="text-xs text-muted-foreground mb-2">{ds.description}</p>}
                                  {cSchemaFields && cSchemaFields.length > 0 ? (
                                    <div className="rounded-md border overflow-hidden">
                                      <table className="w-full text-xs">
                                        <thead>
                                          <tr className="bg-muted/50 border-b">
                                            <th className="text-left px-3 py-1.5 font-medium">Column</th>
                                            <th className="text-left px-3 py-1.5 font-medium">Type</th>
                                            <th className="text-left px-3 py-1.5 font-medium">Description</th>
                                          </tr>
                                        </thead>
                                        <tbody>
                                          {cSchemaFields.map((f, i) => (
                                            <tr key={f.name} className={i % 2 === 0 ? "" : "bg-muted/20"}>
                                              <td className="px-3 py-1.5 font-mono">{f.name}</td>
                                              <td className="px-3 py-1.5 text-muted-foreground">{f.type}</td>
                                              <td className="px-3 py-1.5 text-muted-foreground">{f.description || "\u2014"}</td>
                                            </tr>
                                          ))}
                                        </tbody>
                                      </table>
                                    </div>
                                  ) : (
                                    <p className="text-xs text-muted-foreground italic">No schema defined</p>
                                  )}
                                </div>
                              )}
                            </div>
                          );
                        })}
                      </div>
                    )}
                  </CardContent>
                )}
              </Card>
            );
          })
        )}
      </div>

      {/* Define Dataset Dialog */}
      {defineConnectorId && defineConnectorDefId && (
        <DefineDatasetDialog
          open={defineDialogOpen}
          onOpenChange={setDefineDialogOpen}
          connectorInstanceId={defineConnectorId}
          connectorDefinitionId={defineConnectorDefId}
          onCreated={handleDatasetCreated}
        />
      )}

      {/* Credential Dialog */}
      {credConnectorId && credConnectorDefId && (
        <CredentialDialog
          open={credDialogOpen}
          onOpenChange={setCredDialogOpen}
          connectorInstanceId={credConnectorId}
          connectorDefinitionId={credConnectorDefId}
          environment={credEnvironment}
          existingCredential={credExisting}
          onSaved={handleCredentialSaved}
          credentialStrategy={credStrategy}
        />
      )}

      {/* Add Connector Dialog */}
      <AddConnectorDialog
        open={addConnectorOpen}
        onOpenChange={setAddConnectorOpen}
        sorId={sorId}
        onCreated={handleConnectorCreated}
      />
    </div>
  );
}
