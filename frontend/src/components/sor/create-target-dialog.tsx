"use client";

import { useState } from "react";
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
import type { SystemOfRecord } from "@/types";
import { toast } from "sonner";

interface CreateTargetDialogProps {
  onCreated: (target: SystemOfRecord) => void;
}

export function CreateTargetDialog({ onCreated }: CreateTargetDialogProps) {
  const { currentTenant } = useTenant();
  const { domainOptions } = useTenantDomains(currentTenant);
  const [open, setOpen] = useState(false);
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [selectedDomainValue, setSelectedDomainValue] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const selectedDomain = resolveDomainOptionByValue(
    selectedDomainValue,
    domainOptions
  );

  const handleSubmit = async () => {
    if (!currentTenant || !name.trim() || !selectedDomain) return;
    setLoading(true);
    setError(null);

    try {
      const target = await api.post<SystemOfRecord>(
        `/api/v1/tenants/${currentTenant.id}/targets`,
        {
          name: name.trim(),
          description: description.trim() || null,
          domainId: selectedDomain.id,
        }
      );
      toast.success("Sink target created");
      onCreated({
        ...target,
        domainId: target.domainId ?? selectedDomain.id,
        domainName: target.domainName ?? selectedDomain.name,
      });
      setOpen(false);
      setName("");
      setDescription("");
      setSelectedDomainValue("");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to create sink target");
    } finally {
      setLoading(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button>New Target</Button>
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Create Sink Target</DialogTitle>
          <DialogDescription>
            Register a new published data target in {currentTenant?.name ?? "your tenant"}.
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-4 py-2">
          <div className="space-y-2">
            <Label htmlFor="target-name">Name</Label>
            <Input
              id="target-name"
              placeholder="e.g. Analytics Warehouse"
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
          </div>

          <div className="space-y-2">
            <Label htmlFor="target-domain">Domain</Label>
            <Select
              value={selectedDomainValue}
              onValueChange={setSelectedDomainValue}
            >
              <SelectTrigger>
                <SelectValue placeholder="Select a domain" />
              </SelectTrigger>
              <SelectContent>
                {domainOptions.map((domain) => (
                  <SelectItem
                    key={toDomainOptionValue(domain)}
                    value={toDomainOptionValue(domain)}
                  >
                    {domain.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <p className="text-xs text-muted-foreground">
              Targets are grouped by domain, mirroring data sources.
            </p>
          </div>

          <div className="space-y-2">
            <Label htmlFor="target-description">Description (optional)</Label>
            <Textarea
              id="target-description"
              placeholder="Describe this published data target..."
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              rows={3}
            />
          </div>

          {error && (
            <p className="text-sm text-destructive">{error}</p>
          )}
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => setOpen(false)}>
            Cancel
          </Button>
          <Button
            onClick={handleSubmit}
            disabled={loading || !name.trim() || !selectedDomain}
          >
            {loading ? "Creating..." : "Create Target"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
