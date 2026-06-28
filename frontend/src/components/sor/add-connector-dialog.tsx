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
import { Label } from "@/components/ui/label";
import { api } from "@/lib/api";
import type { ConnectorDefinition, ConnectorInstance } from "@/types";
import { toast } from "sonner";

interface AddConnectorDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  sorId: string;
  onCreated: (connector: ConnectorInstance) => void;
  /** Filter the catalog to this connector type. Defaults to "SOURCE" for
   *  backward compatibility with the producers/source page. Pass
   *  "DESTINATION" for the targets page (LCT-045/P1-3). */
  connectorTypeFilter?: "SOURCE" | "DESTINATION";
}

type Step = "browse" | "configure";

export function AddConnectorDialog({
  open,
  onOpenChange,
  sorId,
  onCreated,
  connectorTypeFilter = "SOURCE",
}: AddConnectorDialogProps) {
  const [step, setStep] = useState<Step>("browse");
  const [catalog, setCatalog] = useState<ConnectorDefinition[]>([]);
  const [search, setSearch] = useState("");
  const [selectedDef, setSelectedDef] = useState<ConnectorDefinition | null>(null);
  const [name, setName] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [, startTransition] = useTransition();

  useEffect(() => {
    if (!open) return;
    setStep("browse");
    setSelectedDef(null);
    setName("");
    setSearch("");
    setError(null);

    startTransition(async () => {
      try {
        const data = await api.get<ConnectorDefinition[]>("/api/v1/connectors");
        // Filter by the requested connector type (SOURCE for producers, DESTINATION for targets)
        setCatalog(data.filter((c) => c.connectorType === connectorTypeFilter));
      } catch {
        setCatalog([]);
      }
    });
  }, [open, connectorTypeFilter]);

  const filtered = catalog.filter(
    (c) =>
      c.name.toLowerCase().includes(search.toLowerCase()) ||
      c.dockerRepository.toLowerCase().includes(search.toLowerCase())
  );

  const handleSelect = (def: ConnectorDefinition) => {
    setSelectedDef(def);
    setName(`${def.name} Connector`);
    setStep("configure");
  };

  const handleSave = async () => {
    if (!selectedDef || !name.trim()) return;
    setSaving(true);
    setError(null);

    try {
      const connector = await api.post<ConnectorInstance>(
        `/api/v1/sors/${sorId}/connectors`,
        {
          connectorDefinitionId: selectedDef.id,
          name: name.trim(),
          enabled: true,
          configTemplate: {},
        }
      );
      toast.success("Connector added");
      onCreated(connector);
      onOpenChange(false);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to add connector");
    } finally {
      setSaving(false);
    }
  };

  const STAGE_VARIANT: Record<string, "secondary" | "outline"> = {
    GENERALLY_AVAILABLE: "secondary",
    BETA: "outline",
    ALPHA: "outline",
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl max-h-[85vh] flex flex-col">
        <DialogHeader>
          <DialogTitle>Add Connector</DialogTitle>
          <DialogDescription>
            {step === "browse" && `Browse the connector catalog and select a ${connectorTypeFilter.toLowerCase()} connector`}
            {step === "configure" && `Configure ${selectedDef?.name ?? "connector"} instance`}
          </DialogDescription>
        </DialogHeader>

        <div className="flex-1 overflow-y-auto space-y-4 min-h-0 max-h-[55vh] px-1">
          {/* Step 1: Browse catalog */}
          {step === "browse" && (
            <>
              <Input
                placeholder="Search connectors..."
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                className="h-8 text-sm"
              />
              {filtered.length === 0 ? (
                <p className="text-xs text-muted-foreground py-4 text-center">
                  {catalog.length === 0 ? "Loading connectors..." : "No connectors match your search."}
                </p>
              ) : (
                <div className="space-y-2">
                  {filtered.map((def) => (
                    <div
                      key={def.id}
                      className="rounded-md border p-3 cursor-pointer hover:bg-muted/50 transition-colors"
                      onClick={() => handleSelect(def)}
                    >
                      <div className="flex items-center justify-between">
                        <div className="flex items-center gap-2">
                          <div className="w-7 h-7 rounded bg-muted flex items-center justify-center text-[10px] font-bold">
                            {def.name.substring(0, 2).toUpperCase()}
                          </div>
                          <span className="text-sm font-medium">{def.name}</span>
                        </div>
                        <div className="flex gap-1">
                          <Badge
                            variant={STAGE_VARIANT[def.releaseStage] ?? "outline"}
                            className="text-[10px]"
                          >
                            {def.releaseStage.replace(/_/g, " ")}
                          </Badge>
                        </div>
                      </div>
                      <p className="text-xs text-muted-foreground mt-1 font-mono">
                        {def.dockerRepository}:{def.dockerImageTag}
                      </p>
                    </div>
                  ))}
                </div>
              )}
            </>
          )}

          {/* Step 2: Configure */}
          {step === "configure" && selectedDef && (
            <div className="space-y-4">
              <div className="rounded-md border p-3">
                <div className="flex items-center gap-2">
                  <div className="w-7 h-7 rounded bg-muted flex items-center justify-center text-[10px] font-bold">
                    {selectedDef.name.substring(0, 2).toUpperCase()}
                  </div>
                  <div>
                    <span className="text-sm font-medium">{selectedDef.name}</span>
                    <p className="text-xs text-muted-foreground font-mono">
                      {selectedDef.dockerRepository}:{selectedDef.dockerImageTag}
                    </p>
                  </div>
                </div>
              </div>

              <div className="space-y-2">
                <Label htmlFor="connector-name">Instance Name</Label>
                <Input
                  id="connector-name"
                  placeholder="e.g. Production Salesforce"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                />
              </div>

              {error && (
                <p className="text-sm text-destructive">{error}</p>
              )}
            </div>
          )}
        </div>

        <DialogFooter>
          {step === "browse" && (
            <Button variant="outline" onClick={() => onOpenChange(false)}>
              Cancel
            </Button>
          )}
          {step === "configure" && (
            <>
              <Button variant="outline" onClick={() => setStep("browse")}>
                Back
              </Button>
              <Button onClick={handleSave} disabled={saving || !name.trim()}>
                {saving ? "Adding..." : "Add Connector"}
              </Button>
            </>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
