export interface OrchestrationNamespacePreview {
  tenantSlug: string;
  domainSlug: string;
  pipelineSlug: string;
  logicalResourcePrefix: string;
  logicalDagId: string;
  logicalPackageNamespace: string;
  pipelineCompleteUri: string;
  tableReadyUris: Record<string, string>;
}

export interface ContractReadiness {
  versionId: string;
  ready: boolean;
  activeContractCount: number;
  totalContracts: number;
  blockers: Array<{ code: string; message: string }>;
}

export interface EventRef {
  eventType: "table_ready" | "pipeline_event";
  tableContractId?: string;
  tableRef?: { domainSlug: string; layer: string; tableSlug: string };
  pipelineId?: string;
  eventSlug?: string;
  description?: string;
}
