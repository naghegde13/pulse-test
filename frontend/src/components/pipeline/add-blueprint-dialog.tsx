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
import { api } from "@/lib/api";
import {
  CATEGORY_LABELS,
  type Blueprint,
  type BlueprintCategory,
} from "@/types";

const CATEGORIES: BlueprintCategory[] = [
  "INGESTION",
  "TRANSFORM",
  "MODELING",
  "DATA_QUALITY",
  "ORCHESTRATION",
  "DESTINATION",
];

interface AddBlueprintDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onAdd: (blueprintKey: string, name: string) => Promise<void>;
}

export function AddBlueprintDialog({
  open,
  onOpenChange,
  onAdd,
}: AddBlueprintDialogProps) {
  const [blueprints, setBlueprints] = useState<Blueprint[]>([]);
  const [selectedCategory, setSelectedCategory] =
    useState<BlueprintCategory | null>(null);
  const [search, setSearch] = useState("");
  const [selected, setSelected] = useState<Blueprint | null>(null);
  const [instanceName, setInstanceName] = useState("");
  const [adding, setAdding] = useState(false);
  const [isPending, startTransition] = useTransition();

  useEffect(() => {
    if (!open) return;
    startTransition(async () => {
      try {
        const data = await api.get<Blueprint[]>("/api/v1/blueprints");
        setBlueprints(
          data
            .filter(
              (blueprint) =>
                !blueprint.pipelineConfig && blueprint.status !== "deprecated"
            )
            .sort(
              (a, b) =>
                a.category.localeCompare(b.category) ||
                a.name.localeCompare(b.name)
            )
        );
      } catch {
        setBlueprints([]);
      }
    });
  }, [open]);

  const filtered = useMemo(
    () =>
      blueprints.filter((blueprint) => {
        if (selectedCategory && blueprint.category !== selectedCategory) return false;
        if (
          search &&
          !blueprint.name.toLowerCase().includes(search.toLowerCase()) &&
          !blueprint.description.toLowerCase().includes(search.toLowerCase())
        ) {
          return false;
        }
        return true;
      }),
    [blueprints, search, selectedCategory]
  );

  const handleAdd = async () => {
    if (!selected) return;
    setAdding(true);
    try {
      await onAdd(selected.blueprintKey, instanceName || selected.name);
      onOpenChange(false);
      setSelected(null);
      setInstanceName("");
      setSearch("");
      setSelectedCategory(null);
    } finally {
      setAdding(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl max-h-[80vh] flex flex-col">
        <DialogHeader>
          <DialogTitle>Add Pipeline Step</DialogTitle>
          <DialogDescription>
            Pick a blueprint by category, or search by name. All active blueprints are listed.
          </DialogDescription>
        </DialogHeader>

        <div className="flex gap-2 flex-wrap">
          <Button
            variant={selectedCategory === null ? "default" : "outline"}
            size="sm"
            onClick={() => setSelectedCategory(null)}
          >
            All
          </Button>
          {CATEGORIES.map((category) => (
            <Button
              key={category}
              variant={selectedCategory === category ? "default" : "outline"}
              size="sm"
              onClick={() => setSelectedCategory(category)}
            >
              {CATEGORY_LABELS[category]}
            </Button>
          ))}
        </div>

        <Input
          placeholder="Search blueprints..."
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          className="h-8 text-sm"
        />

        <div className="flex-1 overflow-y-auto space-y-1 min-h-0 max-h-[40vh]">
          {isPending ? (
            <p className="text-sm text-muted-foreground p-2">Loading...</p>
          ) : (
            filtered.map((blueprint) => (
              <div
                key={blueprint.id}
                className={`flex flex-col gap-1 rounded-md border p-3 cursor-pointer transition-colors ${
                  selected?.id === blueprint.id
                    ? "border-primary bg-primary/5"
                    : "hover:bg-muted/50"
                }`}
                onClick={() => {
                  setSelected(blueprint);
                  setInstanceName(blueprint.name);
                }}
              >
                <div className="flex items-center justify-between gap-3">
                  <span className="text-sm font-medium">{blueprint.name}</span>
                  <Badge variant="outline" className="text-[10px] shrink-0">
                    {CATEGORY_LABELS[blueprint.category]}
                  </Badge>
                </div>
                <p className="text-xs text-muted-foreground line-clamp-2">
                  {blueprint.description}
                </p>
              </div>
            ))
          )}
        </div>

        {selected && (
          <div className="space-y-2 border-t pt-3">
            <p className="text-xs text-muted-foreground">
              Step name (you can customize):
            </p>
            <Input
              value={instanceName}
              onChange={(e) => setInstanceName(e.target.value)}
              className="h-8 text-sm"
            />
          </div>
        )}

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button onClick={handleAdd} disabled={!selected || adding}>
            {adding ? "Adding..." : "Add to Pipeline"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
