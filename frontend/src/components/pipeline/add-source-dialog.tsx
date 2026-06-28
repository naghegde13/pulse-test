"use client";

import { useEffect, useState, useTransition } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { useTenant } from "@/contexts/tenant-context";
import { api } from "@/lib/api";
import type {
  SystemOfRecord,
  ConnectorInstance,
  Dataset,
  LandingContractPreview,
} from "@/types";

type Step = "sor" | "connector" | "dataset" | "confirm";

/** Maps a connector's docker image to the appropriate ingestion blueprint key. */
function getIngestionBlueprint(dockerRepository?: string): string {
  if (!dockerRepository) return "FileIngestion";

  const mapping: Record<string, string> = {
    "airbyte/source-s3": "FileIngestion",
    "airbyte/source-gcs": "FileIngestion",
    "airbyte/source-sftp-bulk": "FileIngestion",
    "airbyte/source-kafka": "StreamIngestion",
    "airbyte/source-declarative-manifest": "ApiIngestion",
    "airbyte/source-salesforce": "ApiIngestion",
    "airbyte/source-postgres": "SnapshotIngestion",
    "airbyte/source-mysql": "SnapshotIngestion",
    "airbyte/source-oracle": "SnapshotIngestion",
    "airbyte/source-mssql": "SnapshotIngestion",
    "airbyte/source-jdbc": "SnapshotIngestion",
    "airbyte/source-mongodb-v2": "SnapshotIngestion",
    "airbyte/source-snowflake": "SnapshotIngestion",
    "airbyte/source-bigquery": "SnapshotIngestion",
    "airbyte/source-elasticsearch": "SnapshotIngestion",
  };

  return mapping[dockerRepository] ?? "SnapshotIngestion";
}

interface AddSourceDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onAdd: (blueprintKey: string, name: string, params: Record<string, unknown>) => Promise<void>;
}

export function AddSourceDialog({
  open,
  onOpenChange,
  onAdd,
}: AddSourceDialogProps) {
  const { currentTenant } = useTenant();
  const [step, setStep] = useState<Step>("sor");
  const [sors, setSors] = useState<SystemOfRecord[]>([]);
  const [connectors, setConnectors] = useState<ConnectorInstance[]>([]);
  const [datasets, setDatasets] = useState<Dataset[]>([]);
  const [selectedSor, setSelectedSor] = useState<SystemOfRecord | null>(null);
  const [selectedConnector, setSelectedConnector] = useState<ConnectorInstance | null>(null);
  const [selectedDatasets, setSelectedDatasets] = useState<Dataset[]>([]);
  const [instanceName, setInstanceName] = useState("");
  const [adding, setAdding] = useState(false);
  const [landingPreviews, setLandingPreviews] = useState<Record<string, LandingContractPreview>>({});
  const [, startTransition] = useTransition();

  useEffect(() => {
    if (!open || !currentTenant) return;
    setStep("sor");
    setSelectedSor(null);
    setSelectedConnector(null);
    setSelectedDatasets([]);
    setInstanceName("");
    startTransition(async () => {
      try {
        const data = await api.get<SystemOfRecord[]>(
          `/api/v1/tenants/${currentTenant.id}/sors`
        );
        setSors(data);
      } catch {
        setSors([]);
      }
    });
  }, [open, currentTenant]);

  const handleSelectSor = (sor: SystemOfRecord) => {
    setSelectedSor(sor);
    setSelectedConnector(null);
    setSelectedDatasets([]);
    startTransition(async () => {
      try {
        const data = await api.get<ConnectorInstance[]>(
          `/api/v1/sors/${sor.id}/connectors`
        );
        setConnectors(data);
        setStep("connector");
      } catch {
        setConnectors([]);
        setStep("connector");
      }
    });
  };

  const handleSelectConnector = (ci: ConnectorInstance) => {
    setSelectedConnector(ci);
    setSelectedDatasets([]);
    setInstanceName(`Ingest from ${selectedSor?.name || "source"} (${ci.name})`);
    startTransition(async () => {
      try {
        if (!currentTenant) return;
        const data = await api.get<Dataset[]>(
          `/api/v1/tenants/${currentTenant.id}/datasets?connectorInstanceId=${ci.id}`
        );
        setDatasets(data);
        setStep("dataset");
      } catch {
        setDatasets([]);
        setStep("dataset");
      }
    });
  };

  const toggleDataset = (ds: Dataset) => {
    const wasSelected = selectedDatasets.some((d) => d.id === ds.id);
    setSelectedDatasets((prev) =>
      wasSelected
        ? prev.filter((d) => d.id !== ds.id)
        : [...prev, ds]
    );
    // Fetch landing contract preview for newly selected datasets
    if (!wasSelected && !landingPreviews[ds.id]) {
      api.get<LandingContractPreview>(
        `/api/v1/datasets/${ds.id}/landing-contract/preview`
      ).then((preview) => {
        setLandingPreviews((prev) => ({ ...prev, [ds.id]: preview }));
      }).catch(() => {
        // Landing contract preview is optional — silently skip
      });
    }
  };

  const handleAdd = async () => {
    if (!selectedSor || !selectedConnector) return;
    setAdding(true);
    try {
      const params: Record<string, unknown> = {
        sor_id: selectedSor.id,
        sor_name: selectedSor.name,
        connector_instance_id: selectedConnector.id,
        connector_name: selectedConnector.name,
      };
      if (selectedDatasets.length > 0) {
        params.dataset_ids = selectedDatasets.map((d) => d.id);
        params.dataset_names = selectedDatasets.map((d) => d.name);
        params.qualified_names = selectedDatasets.map((d) => d.qualifiedName);
      }

      const blueprintKey = getIngestionBlueprint(selectedConnector.dockerRepository);
      await onAdd(blueprintKey, instanceName || "Source Ingestion", params);
      onOpenChange(false);
    } finally {
      setAdding(false);
    }
  };

  const stepTitle: Record<Step, string> = {
    sor: "Select a System of Record",
    connector: "Select a Connector",
    dataset: "Select Datasets",
    confirm: "Confirm Source",
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-lg max-h-[80vh] flex flex-col">
        <DialogHeader>
          <DialogTitle>Add Data Source</DialogTitle>
          <DialogDescription>{stepTitle[step]}</DialogDescription>
        </DialogHeader>

        {/* Breadcrumb */}
        <div className="flex items-center gap-1 text-xs text-muted-foreground">
          <span className={step === "sor" ? "text-foreground font-medium" : "cursor-pointer hover:text-foreground"}
                onClick={() => { if (step !== "sor") { setStep("sor"); setSelectedSor(null); } }}>
            SOR
          </span>
          {selectedSor && (
            <>
              <span>/</span>
              <span className={step === "connector" ? "text-foreground font-medium" : "cursor-pointer hover:text-foreground"}
                    onClick={() => { if (step !== "sor" && step !== "connector") setStep("connector"); }}>
                {selectedSor.name}
              </span>
            </>
          )}
          {selectedConnector && (
            <>
              <span>/</span>
              <span className="text-foreground font-medium">{selectedConnector.name}</span>
            </>
          )}
        </div>

        <div className="flex-1 overflow-y-auto space-y-1 min-h-0 max-h-[40vh]">
          {step === "sor" && (
            sors.length === 0 ? (
              <p className="text-sm text-muted-foreground p-4 text-center">
                No Systems of Record registered. Go to Data Sources to add one.
              </p>
            ) : (
              sors.map((sor) => (
                <div
                  key={sor.id}
                  className="flex items-center justify-between rounded-md border p-3 cursor-pointer hover:bg-muted/50 transition-colors"
                  onClick={() => handleSelectSor(sor)}
                >
                  <div>
                    <span className="text-sm font-medium">{sor.name}</span>
                    <p className="text-xs text-muted-foreground">{sor.description}</p>
                  </div>
                  <div className="flex gap-1 shrink-0">
                    <Badge variant="outline" className="text-[10px]">{sor.domainName || sor.domainId || "Unassigned"}</Badge>
                    <Badge variant="secondary" className="text-[10px]">
                      {sor.connectorCount} connector{sor.connectorCount !== 1 ? "s" : ""}
                    </Badge>
                  </div>
                </div>
              ))
            )
          )}

          {step === "connector" && (
            connectors.length === 0 ? (
              <p className="text-sm text-muted-foreground p-4 text-center">
                No connectors configured for this SOR.
              </p>
            ) : (
              connectors.map((ci) => (
                <div
                  key={ci.id}
                  className="flex items-center justify-between rounded-md border p-3 cursor-pointer hover:bg-muted/50 transition-colors"
                  onClick={() => handleSelectConnector(ci)}
                >
                  <div>
                    <span className="text-sm font-medium">{ci.name}</span>
                    {ci.description && (
                      <p className="text-xs text-muted-foreground">{ci.description}</p>
                    )}
                  </div>
                  <Badge variant={ci.enabled ? "secondary" : "outline"} className="text-[10px]">
                    {ci.enabled ? "Active" : "Disabled"}
                  </Badge>
                </div>
              ))
            )
          )}

          {step === "dataset" && (
            <>
              {datasets.length === 0 && (
                <div className="text-center py-6 space-y-2">
                  <p className="text-sm text-muted-foreground">
                    No datasets defined for this connector yet.
                  </p>
                  <p className="text-xs text-muted-foreground">
                    Go to <span className="font-medium text-foreground">Data Sources</span> to define datasets before adding a source to a pipeline.
                  </p>
                </div>
              )}
              {datasets.map((ds) => {
                const isSelected = selectedDatasets.some((d) => d.id === ds.id);
                return (
                  <div
                    key={ds.id}
                    className={`flex items-center justify-between rounded-md border p-3 cursor-pointer transition-colors ${
                      isSelected ? "border-primary bg-primary/5" : "hover:bg-muted/50"
                    }`}
                    onClick={() => toggleDataset(ds)}
                  >
                    <div>
                      <span className="text-sm font-medium">{ds.name}</span>
                      <p className="text-[11px] text-muted-foreground font-mono">{ds.qualifiedName}</p>
                    </div>
                    <div className="flex gap-1 shrink-0">
                      {ds.classification && (
                        <Badge variant="outline" className="text-[10px]">{ds.classification}</Badge>
                      )}
                      {ds.schemaFormat && (
                        <Badge variant="secondary" className="text-[10px]">{ds.schemaFormat}</Badge>
                      )}
                      {landingPreviews[ds.id]?.ready && (
                        <Badge variant="outline" className="text-[10px] bg-green-500/10 text-green-700 border-green-500/20">
                          contract
                        </Badge>
                      )}
                      {landingPreviews[ds.id] && !landingPreviews[ds.id].ready && (
                        <Badge variant="outline" className="text-[10px] bg-amber-500/10 text-amber-700 border-amber-500/20">
                          no contract
                        </Badge>
                      )}
                    </div>
                  </div>
                );
              })}
              <Button
                size="sm"
                className="w-full mt-2"
                disabled={selectedDatasets.length === 0}
                onClick={() => setStep("confirm")}
              >
                {selectedDatasets.length > 0
                  ? `Continue with ${selectedDatasets.length} dataset${selectedDatasets.length > 1 ? "s" : ""}`
                  : "Select at least one dataset"}
              </Button>
            </>
          )}

          {step === "confirm" && selectedSor && selectedConnector && (
            <div className="space-y-3 p-2">
              <div className="rounded-md border p-3 space-y-2">
                <div className="flex justify-between text-sm">
                  <span className="text-muted-foreground">System of Record</span>
                  <span className="font-medium">{selectedSor.name}</span>
                </div>
                <div className="flex justify-between text-sm">
                  <span className="text-muted-foreground">Connector</span>
                  <span className="font-medium">{selectedConnector.name}</span>
                </div>
                {selectedDatasets.length > 0 && (
                  <div className="flex justify-between text-sm">
                    <span className="text-muted-foreground">Datasets</span>
                    <span className="font-medium text-right">
                      {selectedDatasets.map((d) => d.name).join(", ")}
                    </span>
                  </div>
                )}
                {selectedDatasets.some((d) => landingPreviews[d.id]) && (
                  <div className="border-t pt-2 mt-2 space-y-1.5">
                    <span className="text-xs font-medium text-muted-foreground">Landing Contracts</span>
                    {selectedDatasets.map((d) => {
                      const preview = landingPreviews[d.id];
                      if (!preview) return null;
                      return (
                        <div key={d.id} className="text-xs space-y-0.5">
                          <span className="font-medium">{d.name}</span>
                          {preview.contractUri ? (
                            <p className="font-mono text-muted-foreground text-[10px] break-all">
                              {preview.contractUri}
                            </p>
                          ) : preview.bindingBlockers.length > 0 ? (
                            <div className="rounded-md bg-amber-500/5 border border-amber-500/20 px-2 py-1 text-[10px]">
                              <span className="text-amber-700 font-medium">
                                Landing contract blocked:
                              </span>{" "}
                              <span className="text-amber-700/80">
                                {preview.bindingBlockers.join("; ")}
                              </span>
                            </div>
                          ) : (
                            <p className="text-[10px] text-muted-foreground">No landing contract configured</p>
                          )}
                        </div>
                      );
                    })}
                  </div>
                )}
              </div>
              <div className="space-y-1">
                <p className="text-xs text-muted-foreground">Step name:</p>
                <Input
                  value={instanceName}
                  onChange={(e) => setInstanceName(e.target.value)}
                  className="h-8 text-sm"
                />
              </div>
            </div>
          )}
        </div>

        <DialogFooter>
          {step !== "sor" && step !== "confirm" && (
            <Button
              variant="outline"
              onClick={() => {
                if (step === "connector") { setStep("sor"); setSelectedSor(null); }
                if (step === "dataset") setStep("connector");
              }}
            >
              Back
            </Button>
          )}
          {step === "confirm" && (
            <>
              <Button variant="outline" onClick={() => onOpenChange(false)}>
                Cancel
              </Button>
              <Button onClick={handleAdd} disabled={adding}>
                {adding ? "Adding..." : "Add Source to Pipeline"}
              </Button>
            </>
          )}
          {step === "sor" && (
            <Button variant="outline" onClick={() => onOpenChange(false)}>
              Cancel
            </Button>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
