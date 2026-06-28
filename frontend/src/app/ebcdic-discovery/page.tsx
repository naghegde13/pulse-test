"use client";

import { CobolDiscoveryProvider } from "@/contexts/ebcdic-discovery-context";
import { DiscoveryWorkspace } from "@/components/ebcdic-discovery/discovery-workspace";

export default function EBCDICDiscoveryPage() {
  return (
    <CobolDiscoveryProvider>
      <DiscoveryWorkspace />
    </CobolDiscoveryProvider>
  );
}
