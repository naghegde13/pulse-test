"use client";

import { useEffect, useCallback, useTransition, useState } from "react";
import { api } from "@/lib/api";
import type { SchemaColumn } from "@/types";

const schemaCache = new Map<string, SchemaColumn[]>();

export function useUpstreamSchema(versionId: string, instanceId: string) {
  const [columns, setColumns] = useState<SchemaColumn[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [generation, setGeneration] = useState(0);
  const [isPending, startTransition] = useTransition();

  useEffect(() => {
    if (!versionId || !instanceId) return;

    const key = `${versionId}:${instanceId}`;

    startTransition(async () => {
      // Use cache on first load (generation === 0)
      if (generation === 0) {
        const cached = schemaCache.get(key);
        if (cached) {
          setColumns(cached);
          return;
        }
      }

      try {
        const data = await api.get<{ columns: SchemaColumn[] }>(
          `/api/v1/versions/${versionId}/composition/instances/${instanceId}/upstream-schema`
        );
        const cols = Array.isArray(data?.columns) ? data.columns : [];
        schemaCache.set(key, cols);
        setColumns(cols);
      } catch (e) {
        setError(e instanceof Error ? e.message : "Failed to load schema");
        setColumns([]);
      }
    });
  }, [versionId, instanceId, generation, startTransition]);

  const refetch = useCallback(() => {
    schemaCache.delete(`${versionId}:${instanceId}`);
    setGeneration((g) => g + 1);
  }, [versionId, instanceId]);

  return { columns, loading: isPending, error, refetch };
}
