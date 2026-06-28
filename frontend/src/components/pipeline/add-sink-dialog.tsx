"use client";

import { useEffect, useMemo, useState, useTransition } from "react";
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
  ConnectorInstance,
  DeployEnvironment,
  SystemOfRecord,
} from "@/types";
import { DEPLOY_ENVIRONMENT_LABELS } from "@/types";
import {
  buildCredentialWarning,
  buildDefaultSinkConfig,
  dispositionParamName,
  resolveSinkBlueprintKey,
  resolveSinkTargetField,
  resolveWriteDispositions,
  type CredentialWarning,
  type SinkConfig,
} from "@/lib/sink-capability";

type Step = "target" | "connector" | "configure" | "confirm";

function badgeVariantForTone(tone: CredentialWarning["tone"]): "secondary" | "outline" | "destructive" {
  if (tone === "orange") return "destructive";
  return "outline";
}

interface AddSinkDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onAdd: (blueprintKey: string, name: string, params: Record<string, unknown>) => Promise<void>;
  environment?: DeployEnvironment;
}

export function AddSinkDialog({
  open,
  onOpenChange,
  onAdd,
  environment = "dev",
}: AddSinkDialogProps) {
  const { currentTenant } = useTenant();
  const [step, setStep] = useState<Step>("target");
  const [targets, setTargets] = useState<SystemOfRecord[]>([]);
  const [selectedTarget, setSelectedTarget] = useState<SystemOfRecord | null>(null);
  const [connectors, setConnectors] = useState<ConnectorInstance[]>([]);
  const [selectedConnector, setSelectedConnector] = useState<ConnectorInstance | null>(null);
  const [sinkConfig, setSinkConfig] = useState<SinkConfig>({ write_mode: "append" });
  const [instanceName, setInstanceName] = useState("");
  const [adding, setAdding] = useState(false);
  const [connectorsLoading, setConnectorsLoading] = useState(false);
  const [, startTransition] = useTransition();

  useEffect(() => {
    if (!open || !currentTenant) return;
    setStep("target");
    setSelectedTarget(null);
    setSelectedConnector(null);
    setConnectors([]);
    setSinkConfig({ write_mode: "append" });
    setInstanceName("");
    startTransition(async () => {
      try {
        const data = await api.get<SystemOfRecord[]>(
          `/api/v1/tenants/${currentTenant.id}/targets`
        );
        setTargets(data);
      } catch {
        setTargets([]);
      }
    });
  }, [open, currentTenant]);

  const handleSelectTarget = async (target: SystemOfRecord) => {
    setSelectedTarget(target);
    setStep("connector");
    setConnectorsLoading(true);
    try {
      // LCT-045/047: only DESTINATION-capable connectors are valid sinks, and
      // the env-scoped fetch attaches the central credential-readiness contract.
      const data = await api.get<ConnectorInstance[]>(
        `/api/v1/sors/${target.id}/connectors?type=DESTINATION&environment=${encodeURIComponent(environment)}`
      );
      setConnectors(data);
    } catch {
      setConnectors([]);
    } finally {
      setConnectorsLoading(false);
    }
  };

  const handleSelectConnector = (connector: ConnectorInstance) => {
    setSelectedConnector(connector);
    setInstanceName(`Write to ${connector.name}`);
    setSinkConfig(buildDefaultSinkConfig(connector));
    setStep("configure");
  };

  const blueprintKey = useMemo(
    () => (selectedConnector ? resolveSinkBlueprintKey(selectedConnector) : ""),
    [selectedConnector]
  );

  const targetField = useMemo(
    () => (selectedConnector ? resolveSinkTargetField(selectedConnector) : null),
    [selectedConnector]
  );

  const writeDispositions = useMemo(
    () => (selectedConnector ? resolveWriteDispositions(selectedConnector) : []),
    [selectedConnector]
  );

  const targetValue = (): string => {
    if (!targetField) return "";
    return (sinkConfig[targetField.paramKey] || "").trim();
  };

  const canContinue = () => {
    if (!targetField) return true;
    return targetValue().length > 0;
  };

  const setTargetValue = (value: string) => {
    if (!targetField) return;
    setSinkConfig({ ...sinkConfig, [targetField.paramKey]: value });
  };

  const selectedConnectorWarning = useMemo(
    () =>
      selectedConnector
        ? buildCredentialWarning(selectedConnector, environment, DEPLOY_ENVIRONMENT_LABELS[environment])
        : null,
    [selectedConnector, environment]
  );

  const handleAdd = async () => {
    if (!selectedConnector || !selectedTarget) return;
    setAdding(true);
    try {
      const params: Record<string, unknown> = {
        target_id: selectedTarget.id,
        connector_instance_id: selectedConnector.id,
        connector_name: selectedConnector.name,
        credential_strategy: selectedConnector.credentialStrategy,
        [dispositionParamName(selectedConnector)]: sinkConfig.write_mode,
      };

      if (targetField) {
        const value = targetValue();
        if (value) {
          params[targetField.paramKey] = value;
        }
      }

      await onAdd(blueprintKey, instanceName || "Sink", params);
      onOpenChange(false);
    } finally {
      setAdding(false);
    }
  };

  const stepTitle: Record<Step, string> = {
    target: "Select a Sink Target",
    connector: "Select a Connector",
    configure: "Configure Destination",
    confirm: "Confirm Sink",
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-lg max-h-[80vh] flex flex-col">
        <DialogHeader>
          <DialogTitle>Add Data Sink</DialogTitle>
          <DialogDescription>{stepTitle[step]}</DialogDescription>
        </DialogHeader>

        <div className="flex items-center gap-1 text-xs text-muted-foreground">
          <span
            className={step === "target" ? "text-foreground font-medium" : "cursor-pointer hover:text-foreground"}
            onClick={() => {
              if (step !== "target") {
                setStep("target");
                setSelectedTarget(null);
                setSelectedConnector(null);
              }
            }}
          >
            Target
          </span>
          {selectedTarget && (
            <>
              <span>/</span>
              <span
                className={step === "connector" ? "text-foreground font-medium" : "cursor-pointer hover:text-foreground"}
                onClick={() => {
                  if (step === "configure" || step === "confirm") {
                    setStep("connector");
                    setSelectedConnector(null);
                  }
                }}
              >
                {selectedTarget.name}
              </span>
            </>
          )}
          {selectedConnector && (
            <>
              <span>/</span>
              <span
                className={step === "configure" ? "text-foreground font-medium" : "cursor-pointer hover:text-foreground"}
                onClick={() => {
                  if (step === "confirm") setStep("configure");
                }}
              >
                {selectedConnector.name}
              </span>
            </>
          )}
        </div>

        <div className="flex-1 overflow-y-auto space-y-1 min-h-0 max-h-[40vh]">
          {step === "target" && (
            targets.length === 0 ? (
              <p className="text-sm text-muted-foreground p-4 text-center">
                No sink targets are registered for this tenant yet. Register one under Sink Targets.
              </p>
            ) : (
              targets.map((target) => (
                <div
                  key={target.id}
                  className="flex items-center justify-between rounded-md border p-3 cursor-pointer hover:bg-muted/50 transition-colors"
                  onClick={() => handleSelectTarget(target)}
                >
                  <div>
                    <span className="text-sm font-medium">{target.name}</span>
                    <p className="text-xs text-muted-foreground">
                      {target.description || target.domainName || "Sink target"}
                    </p>
                  </div>
                  <div className="flex gap-1 shrink-0">
                    <Badge variant="secondary" className="text-[10px]">
                      {target.connectorCount} connector{target.connectorCount !== 1 ? "s" : ""}
                    </Badge>
                  </div>
                </div>
              ))
            )
          )}

          {step === "connector" && selectedTarget && (
            connectorsLoading ? (
              <p className="text-sm text-muted-foreground p-4 text-center">Loading connectors...</p>
            ) : connectors.length === 0 ? (
              <p className="text-sm text-muted-foreground p-4 text-center">
                No connectors configured under this target yet. Add one from the target detail page.
              </p>
            ) : (
              connectors.map((connector) => {
                const warning = buildCredentialWarning(
                  connector,
                  environment,
                  DEPLOY_ENVIRONMENT_LABELS[environment]
                );
                return (
                  <div
                    key={connector.id}
                    className="flex items-center justify-between rounded-md border p-3 cursor-pointer hover:bg-muted/50 transition-colors"
                    onClick={() => handleSelectConnector(connector)}
                  >
                    <div>
                      <span className="text-sm font-medium">{connector.name}</span>
                      <p className="text-xs text-muted-foreground">
                        {connector.connectorTypeName || "Destination"}
                      </p>
                    </div>
                    <div className="flex gap-1 shrink-0">
                      {warning.message && (
                        <Badge variant={badgeVariantForTone(warning.tone)} className="text-[10px]">
                          {warning.message}
                        </Badge>
                      )}
                    </div>
                  </div>
                );
              })
            )
          )}

          {step === "configure" && selectedConnector && (
            <div className="space-y-3 p-2">
              <p className="text-xs text-muted-foreground">
                Blueprint: <span className="font-medium text-foreground">{blueprintKey}</span>
              </p>

              {selectedConnectorWarning?.message && (
                <Badge
                  variant={badgeVariantForTone(selectedConnectorWarning.tone)}
                  className="text-[10px]"
                >
                  {selectedConnectorWarning.message}
                </Badge>
              )}

              {selectedConnector.credentialStrategy === "CONNECTOR_SPECIFIC" && (
                <p className="text-[11px] text-muted-foreground">
                  Writes use this connector&apos;s own credentials, resolved at runtime
                  from its Secret Manager reference.
                </p>
              )}

              {targetField && (
                <div className="space-y-1">
                  <p className="text-xs text-muted-foreground">{targetField.label} *</p>
                  <Input
                    placeholder={targetField.placeholder}
                    value={targetValue()}
                    onChange={(e) => setTargetValue(e.target.value)}
                    className="h-8 text-sm"
                  />
                </div>
              )}

              <div className="space-y-1">
                <p className="text-xs text-muted-foreground">Write Mode</p>
                <div className="flex flex-wrap gap-2">
                  {writeDispositions.map((disposition) => (
                    <Button
                      key={disposition.value}
                      size="sm"
                      variant={sinkConfig.write_mode === disposition.value ? "default" : "outline"}
                      onClick={() => setSinkConfig({ ...sinkConfig, write_mode: disposition.value })}
                      className="text-xs"
                    >
                      {disposition.label}
                    </Button>
                  ))}
                </div>
              </div>

              <Button
                size="sm"
                className="w-full mt-2"
                disabled={!canContinue()}
                onClick={() => setStep("confirm")}
              >
                Continue
              </Button>
            </div>
          )}

          {step === "confirm" && selectedConnector && selectedTarget && (
            <div className="space-y-3 p-2">
              <div className="rounded-md border p-3 space-y-2">
                <div className="flex justify-between text-sm">
                  <span className="text-muted-foreground">Target</span>
                  <span className="font-medium">{selectedTarget.name}</span>
                </div>
                <div className="flex justify-between text-sm">
                  <span className="text-muted-foreground">Connector</span>
                  <span className="font-medium">{selectedConnector.name}</span>
                </div>
                <div className="flex justify-between text-sm">
                  <span className="text-muted-foreground">Blueprint</span>
                  <span className="font-medium">{blueprintKey}</span>
                </div>
                <div className="flex justify-between text-sm">
                  <span className="text-muted-foreground">Write Mode</span>
                  <span className="font-medium">
                    {writeDispositions.find((d) => d.value === sinkConfig.write_mode)?.label ?? sinkConfig.write_mode}
                  </span>
                </div>
                {targetField && targetValue() && (
                  <div className="flex justify-between text-sm gap-3">
                    <span className="text-muted-foreground shrink-0">{targetField.label}</span>
                    <span className="font-medium break-all text-right">{targetValue()}</span>
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
          {(step === "connector" || step === "configure") && (
            <Button
              variant="outline"
              onClick={() => {
                if (step === "configure") {
                  setStep("connector");
                  setSelectedConnector(null);
                } else if (step === "connector") {
                  setStep("target");
                  setSelectedTarget(null);
                  setConnectors([]);
                }
              }}
            >
              Back
            </Button>
          )}
          {step === "confirm" && (
            <>
              <Button variant="outline" onClick={() => setStep("configure")}>
                Back
              </Button>
              <Button variant="outline" onClick={() => onOpenChange(false)}>
                Cancel
              </Button>
              <Button onClick={handleAdd} disabled={adding}>
                {adding ? "Adding..." : "Add Sink to Pipeline"}
              </Button>
            </>
          )}
          {(step === "target" || step === "connector" || step === "configure") && (
            <Button variant="outline" onClick={() => onOpenChange(false)}>
              Cancel
            </Button>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

