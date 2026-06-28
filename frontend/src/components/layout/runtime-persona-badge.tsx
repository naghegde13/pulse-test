"use client";

import { useRuntimeAuthority } from "@/contexts/runtime-authority-context";
import { Badge } from "@/components/ui/badge";

export function RuntimePersonaBadge() {
  const { authority, loading } = useRuntimeAuthority();

  if (loading || !authority) return null;

  const isGcp = authority.activePersona === "GCP_PULSE";

  return (
    <Badge variant="outline" className="text-xs font-medium">
      {isGcp ? "☁️" : "🏢"} {authority.displayName}
    </Badge>
  );
}
