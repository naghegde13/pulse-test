"use client";

import { useMemo, useState } from "react";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { useTenant } from "@/contexts/tenant-context";
import { useTenantDomains } from "@/hooks/use-tenant-domains";
import { api } from "@/lib/api";
import {
  resolveDomainOptionByValue,
  toDomainOptionValue,
} from "@/lib/domains";
import { useRuntimeAuthority } from "@/contexts/runtime-authority-context";
import type { Pipeline } from "@/types";

interface CreatePipelineDialogProps {
  onCreated: (pipeline: Pipeline) => void;
}

export function CreatePipelineDialog({ onCreated }: CreatePipelineDialogProps) {
  const { currentTenant } = useTenant();
  const { authority } = useRuntimeAuthority();
  const { domainOptions } = useTenantDomains(currentTenant);
  const [open, setOpen] = useState(false);
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [selectedDomainValue, setSelectedDomainValue] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const selectedDomain = useMemo(
    () => resolveDomainOptionByValue(selectedDomainValue, domainOptions),
    [selectedDomainValue, domainOptions]
  );

  const defaultStorageBackend = useMemo<"DPC" | "GCP">(() => {
    const allowed = authority?.allowedStorageBackends ?? [];
    if (allowed.includes("GCP") && !allowed.includes("DPC")) return "GCP";
    if (allowed.includes("DPC") && !allowed.includes("GCP")) return "DPC";
    return authority?.activePersona === "DPC_PULSE" ? "DPC" : "GCP";
  }, [authority]);

  const handleSubmit = async () => {
    if (!currentTenant || !name.trim() || !selectedDomain) return;
    setLoading(true);
    setError(null);

    try {
      const pipeline = await api.post<Pipeline>(
        `/api/v1/tenants/${currentTenant.id}/pipelines`,
        {
          name: name.trim(),
          description: description.trim() || null,
          domainName: selectedDomain.name,
          domainId: selectedDomain.id ?? null,
          defaultStorageBackend,
        }
      );
      onCreated({
        ...pipeline,
        domainId: pipeline.domainId ?? selectedDomain.id,
        domainName: pipeline.domainName ?? selectedDomain.name,
      });
      setOpen(false);
      setName("");
      setDescription("");
      setSelectedDomainValue("");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to create pipeline");
    } finally {
      setLoading(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button>New Pipeline</Button>
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Create Pipeline</DialogTitle>
          <DialogDescription>
            Create a new Phase 1 pipeline in {currentTenant?.name ?? "your tenant"}.
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-4 py-2">
          <div className="space-y-2">
            <Label htmlFor="name">Pipeline Name</Label>
            <Input
              id="name"
              placeholder="e.g. Customer Bronze to Silver"
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
          </div>

          <div className="space-y-2">
            <Label htmlFor="domain">Domain Scope</Label>
            <Select value={selectedDomainValue} onValueChange={setSelectedDomainValue}>
              <SelectTrigger>
                <SelectValue placeholder="Select a domain" />
              </SelectTrigger>
              <SelectContent>
                {domainOptions.map((domain) => (
                  <SelectItem key={toDomainOptionValue(domain)} value={toDomainOptionValue(domain)}>
                    {domain.name}
                    {domain.source === "legacy" ? " (legacy)" : ""}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <p className="text-xs text-muted-foreground">
              The UI keeps the canonical domain identity when available and falls back to the legacy domain name until backend support is fully rolled out.
            </p>
          </div>

          <div className="space-y-2">
            <Label htmlFor="description">Description (optional)</Label>
            <Textarea
              id="description"
              placeholder="Describe what this pipeline does..."
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              rows={3}
            />
          </div>

          {error && <p className="text-sm text-destructive">{error}</p>}
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => setOpen(false)}>
            Cancel
          </Button>
          <Button
            onClick={handleSubmit}
            disabled={loading || !name.trim() || !selectedDomain}
          >
            {loading ? "Creating..." : "Create Pipeline"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
