"use client";

import { useEffect, useState, useTransition } from "react";
import { Badge } from "@/components/ui/badge";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { useTenant } from "@/contexts/tenant-context";
import { api } from "@/lib/api";
import type { CommandLogEntry } from "@/types";

const STATUS_VARIANT: Record<string, "default" | "secondary" | "destructive" | "outline"> = {
  SUCCEEDED: "default",
  FAILED: "destructive",
  EXECUTING: "secondary",
  PENDING: "outline",
  REJECTED: "destructive",
};

export default function CommandsPage() {
  const { currentTenant } = useTenant();
  const [commands, setCommands] = useState<CommandLogEntry[]>([]);
  const [isPending, startTransition] = useTransition();
  const [initialLoad, setInitialLoad] = useState(true);

  useEffect(() => {
    if (!currentTenant) return;
    startTransition(async () => {
      try {
        const data = await api.get<CommandLogEntry[]>(
          `/api/v1/tenants/${currentTenant.id}/commands`
        );
        setCommands(data);
      } catch {
        setCommands([]);
      } finally {
        setInitialLoad(false);
      }
    });
  }, [currentTenant]);

  const loading = initialLoad && isPending;

  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-2xl font-bold tracking-tight">Command Log</h2>
        <p className="text-muted-foreground">
          Append-only audit trail of all executed commands
        </p>
      </div>

      {loading ? (
        <p className="text-muted-foreground">Loading commands...</p>
      ) : commands.length === 0 ? (
        <Card>
          <CardHeader>
            <CardTitle>No commands yet</CardTitle>
            <CardDescription>
              Commands will appear here as you create and manage pipelines.
            </CardDescription>
          </CardHeader>
        </Card>
      ) : (
        <div className="space-y-2">
          {commands.map((cmd) => (
            <Card key={cmd.id}>
              <CardContent className="py-3 px-4">
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-3">
                    <Badge variant="outline" className="font-mono text-[10px]">
                      {cmd.commandType}
                    </Badge>
                    <span className="text-sm text-muted-foreground">
                      {cmd.aggregateType}
                    </span>
                    <Badge
                      variant={STATUS_VARIANT[cmd.status] ?? "secondary"}
                      className="text-[10px]"
                    >
                      {cmd.status}
                    </Badge>
                  </div>
                  <div className="flex items-center gap-3 text-xs text-muted-foreground">
                    <span>{cmd.actorId}</span>
                    <span>
                      {new Date(cmd.createdAt).toLocaleString()}
                    </span>
                  </div>
                </div>
                {cmd.errorMessage && (
                  <p className="text-xs text-destructive mt-1">
                    {cmd.errorMessage}
                  </p>
                )}
              </CardContent>
            </Card>
          ))}
        </div>
      )}
    </div>
  );
}
