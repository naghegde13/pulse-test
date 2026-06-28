"use client";

import { useEffect, useState, useTransition } from "react";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
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
import { Textarea } from "@/components/ui/textarea";
import { Badge } from "@/components/ui/badge";
import { useTenant } from "@/contexts/tenant-context";
import { api } from "@/lib/api";
import type { Domain } from "@/types";
import { toast } from "sonner";

type DialogMode = "create" | "edit" | "delete" | null;

export default function DomainsPage() {
  const { currentTenant, refreshTenant } = useTenant();
  const [domains, setDomains] = useState<Domain[]>([]);
  const [isPending, startTransition] = useTransition();
  const [initialLoad, setInitialLoad] = useState(true);

  const [dialogMode, setDialogMode] = useState<DialogMode>(null);
  const [selected, setSelected] = useState<Domain | null>(null);
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!currentTenant) return;
    startTransition(async () => {
      try {
        const data = await api.get<Domain[]>(
          `/api/v1/tenants/${currentTenant.id}/domains`
        );
        setDomains(data);
      } catch {
        setDomains([]);
      } finally {
        setInitialLoad(false);
      }
    });
  }, [currentTenant]);

  const loading = initialLoad && isPending;

  const openCreate = () => {
    setSelected(null);
    setName("");
    setDescription("");
    setError(null);
    setDialogMode("create");
  };

  const openEdit = (domain: Domain) => {
    setSelected(domain);
    setName(domain.name);
    setDescription(domain.description ?? "");
    setError(null);
    setDialogMode("edit");
  };

  const openDelete = (domain: Domain) => {
    setSelected(domain);
    setError(null);
    setDialogMode("delete");
  };

  const closeDialog = () => {
    setDialogMode(null);
    setSelected(null);
    setError(null);
  };

  const handleSave = async () => {
    if (!currentTenant || !name.trim()) return;
    setSaving(true);
    setError(null);

    try {
      if (dialogMode === "create") {
        const created = await api.post<Domain>(
          `/api/v1/tenants/${currentTenant.id}/domains`,
          { name: name.trim(), description: description.trim() || null }
        );
        setDomains((prev) => [...prev, created]);
        toast.success("Domain created");
        refreshTenant();
      } else if (dialogMode === "edit" && selected) {
        const updated = await api.put<Domain>(
          `/api/v1/tenants/${currentTenant.id}/domains/${selected.id}`,
          { name: name.trim(), description: description.trim() || null }
        );
        setDomains((prev) => prev.map((d) => (d.id === updated.id ? updated : d)));
        toast.success("Domain updated");
        refreshTenant();
      }
      closeDialog();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to save domain");
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async () => {
    if (!currentTenant || !selected) return;
    setSaving(true);
    setError(null);

    try {
      await api.delete(`/api/v1/tenants/${currentTenant.id}/domains/${selected.id}`);
      setDomains((prev) => prev.filter((d) => d.id !== selected.id));
      toast.success("Domain deleted");
      refreshTenant();
      closeDialog();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to delete domain");
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="space-y-6">
      <div className="flex items-start justify-between">
        <div>
          <h2 className="text-2xl font-bold tracking-tight">Domains</h2>
          <p className="text-muted-foreground">
            Manage business domains for {currentTenant?.name ?? "your tenant"}
          </p>
        </div>
        <Button onClick={openCreate}>New Domain</Button>
      </div>

      {loading ? (
        <p className="text-muted-foreground">Loading...</p>
      ) : domains.length === 0 ? (
        <Card>
          <CardHeader>
            <CardTitle>No domains</CardTitle>
            <CardDescription>
              Create your first domain to start organizing data sources and
              pipelines.
            </CardDescription>
          </CardHeader>
        </Card>
      ) : (
        <div className="grid gap-3 md:grid-cols-2 lg:grid-cols-3">
          {domains.map((domain) => (
            <Card key={domain.id} className="group relative">
              <CardHeader className="pb-3">
                <div className="flex items-center justify-between">
                  <CardTitle className="text-sm capitalize">
                    {domain.name}
                  </CardTitle>
                  {domain.currentBusinessDate && (
                    <Badge variant="outline" className="text-[10px]">
                      {domain.currentBusinessDate}
                    </Badge>
                  )}
                </div>
                <CardDescription className="text-xs">
                  {domain.description || "No description"}
                </CardDescription>
                {domain.businessDateGrain && (
                  <p className="text-[10px] text-muted-foreground mt-1">
                    Grain: {domain.businessDateGrain}
                    {domain.businessDateTimezone
                      ? ` | TZ: ${domain.businessDateTimezone}`
                      : ""}
                  </p>
                )}
                <div className="flex gap-2 mt-2">
                  <Button
                    variant="outline"
                    size="sm"
                    className="h-7 text-xs"
                    onClick={() => openEdit(domain)}
                  >
                    Edit
                  </Button>
                  <Button
                    variant="outline"
                    size="sm"
                    className="h-7 text-xs text-destructive hover:text-destructive"
                    onClick={() => openDelete(domain)}
                  >
                    Delete
                  </Button>
                </div>
              </CardHeader>
            </Card>
          ))}
        </div>
      )}

      {/* Create / Edit dialog */}
      <Dialog open={dialogMode === "create" || dialogMode === "edit"} onOpenChange={(open) => !open && closeDialog()}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>
              {dialogMode === "create" ? "Create Domain" : "Edit Domain"}
            </DialogTitle>
            <DialogDescription>
              {dialogMode === "create"
                ? `Add a new business domain to ${currentTenant?.name ?? "your tenant"}.`
                : `Update the domain "${selected?.name}".`}
            </DialogDescription>
          </DialogHeader>

          <div className="space-y-4 py-2">
            <div className="space-y-2">
              <Label htmlFor="domain-name">Name</Label>
              <Input
                id="domain-name"
                placeholder="e.g. finance"
                value={name}
                onChange={(e) => setName(e.target.value)}
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="domain-description">Description (optional)</Label>
              <Textarea
                id="domain-description"
                placeholder="Describe this domain..."
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                rows={3}
              />
            </div>
            {error && <p className="text-sm text-destructive">{error}</p>}
          </div>

          <DialogFooter>
            <Button variant="outline" onClick={closeDialog}>
              Cancel
            </Button>
            <Button onClick={handleSave} disabled={saving || !name.trim()}>
              {saving
                ? "Saving..."
                : dialogMode === "create"
                  ? "Create Domain"
                  : "Save Changes"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Delete confirmation dialog */}
      <Dialog open={dialogMode === "delete"} onOpenChange={(open) => !open && closeDialog()}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Delete Domain</DialogTitle>
            <DialogDescription>
              Are you sure you want to delete &ldquo;{selected?.name}&rdquo;?
              This action cannot be undone.
            </DialogDescription>
          </DialogHeader>
          {error && (
            <p className="text-sm text-destructive">{error}</p>
          )}
          <DialogFooter>
            <Button variant="outline" onClick={closeDialog}>
              Cancel
            </Button>
            <Button variant="destructive" onClick={handleDelete} disabled={saving}>
              {saving ? "Deleting..." : "Delete Domain"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
