"use client";

import { useState, useCallback } from "react";
import { api } from "@/lib/api";
import type { DqReadinessResult, DqRecommendation } from "@/types";

interface UseDqReadinessReturn {
  score: number | null;
  recommendations: DqRecommendation[];
  reasoning: string | null;
  loading: boolean;
  error: string | null;
  evaluate: () => Promise<void>;
}

export function useDqReadiness(versionId: string): UseDqReadinessReturn {
  const [score, setScore] = useState<number | null>(null);
  const [recommendations, setRecommendations] = useState<DqRecommendation[]>(
    []
  );
  const [reasoning, setReasoning] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const evaluate = useCallback(async () => {
    if (!versionId) return;
    setLoading(true);
    setError(null);
    try {
      const result = await api.post<DqReadinessResult>(
        `/api/v1/versions/${versionId}/dq/evaluate`,
        {}
      );
      setScore(result.score);
      setRecommendations(result.recommendations ?? []);
      setReasoning(result.reasoning ?? null);
    } catch (e) {
      setError(e instanceof Error ? e.message : "DQ evaluation failed");
    } finally {
      setLoading(false);
    }
  }, [versionId]);

  return { score, recommendations, reasoning, loading, error, evaluate };
}
