import type {
  ConnectorInstance,
  DeployEnvironment,
  SinkWriterKind,
  WriteDisposition,
} from "@/types";

/**
 * LCT-045/048: connector sink capability resolution.
 *
 * The Add-Sink dialog used to substring-match the connector name and fall
 * through to LakeWriter for anything unmatched (so MongoDB asked for an S3
 * path). These helpers drive the sink blueprint, target field, and legal
 * write dispositions from DECLARED connector capability metadata
 * (`sinkWriterKind` + `writeDispositions`), with a deterministic, NON-lake
 * fallback only when a legacy connector has no declared metadata.
 */

const WRITER_KIND_TO_BLUEPRINT: Record<SinkWriterKind, string> = {
  LAKE: "LakeWriter",
  WAREHOUSE: "WarehouseWriter",
  RELATIONAL: "DatabaseWriter",
  DOCUMENT: "DatabaseWriter",
  STREAM: "StreamWriter",
};

const DEFAULT_DISPOSITIONS: Record<SinkWriterKind, WriteDisposition[]> = {
  LAKE: [
    { value: "merge_on_pk", label: "Merge on keys (upsert)" },
    { value: "append_partition", label: "Append partition" },
    { value: "overwrite", label: "Overwrite" },
  ],
  WAREHOUSE: [
    { value: "overwrite_partition", label: "Overwrite partition" },
    { value: "append", label: "Append" },
    { value: "merge_on_pk", label: "Merge on keys (upsert)" },
  ],
  RELATIONAL: [
    { value: "append", label: "Append" },
    { value: "merge_on_pk", label: "Upsert / merge on keys" },
    { value: "overwrite_partition", label: "Truncate & reload" },
  ],
  DOCUMENT: [
    { value: "append", label: "Insert documents" },
    { value: "merge_on_pk", label: "Upsert by key" },
    { value: "overwrite_partition", label: "Replace collection" },
  ],
  STREAM: [
    { value: "batch_publish", label: "Batch publish" },
    { value: "streaming_publish", label: "Streaming publish" },
  ],
};

/** Target-field descriptor rendered in the configure step, by writer kind. */
export interface SinkTargetField {
  /** Param key written to the sub-pipeline instance (blueprint-legal). */
  paramKey: "output_path" | "target_table" | "topic";
  label: string;
  placeholder: string;
}

const TARGET_FIELD: Record<SinkWriterKind, SinkTargetField> = {
  LAKE: {
    paramKey: "output_path",
    label: "Output Path",
    placeholder: "s3a://bucket/path or catalog.namespace.table",
  },
  WAREHOUSE: {
    paramKey: "target_table",
    label: "Target Table",
    placeholder: "project.dataset.table or database.schema.table",
  },
  RELATIONAL: {
    paramKey: "target_table",
    label: "Target Table",
    placeholder: "schema.table_name",
  },
  DOCUMENT: {
    paramKey: "target_table",
    label: "Target Collection",
    placeholder: "database.collection",
  },
  STREAM: {
    paramKey: "topic",
    label: "Kafka Topic",
    placeholder: "my-topic",
  },
};

function readString(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

function joinPathSegments(...segments: string[]): string {
  return segments
    .map((segment, index) => {
      if (index === 0) return segment.replace(/\/+$/, "");
      return segment.replace(/^\/+|\/+$/g, "");
    })
    .filter(Boolean)
    .join("/");
}

/** Heuristic fallback used ONLY when a connector has no declared sinkWriterKind. */
function heuristicWriterKind(name: string): SinkWriterKind {
  const lower = name.toLowerCase();
  if (lower.includes("snowflake") || lower.includes("bigquery") || lower.includes("redshift")) {
    return "WAREHOUSE";
  }
  if (lower.includes("mongo")) return "DOCUMENT";
  if (lower.includes("elasticsearch") || lower.includes("elastic")) return "DOCUMENT";
  if (lower.includes("kafka")) return "STREAM";
  if (
    lower.includes("s3") ||
    lower.includes("gcs") ||
    lower.includes("object storage") ||
    lower.includes("delta") ||
    lower.includes("iceberg") ||
    lower.includes("lake")
  ) {
    return "LAKE";
  }
  // Postgres / MySQL / Oracle / SQL Server / ClickHouse / generic JDBC and any
  // unknown destination default to RELATIONAL — NEVER lake — so we never ask a
  // database/document sink for a storage path.
  return "RELATIONAL";
}

export function resolveSinkWriterKind(connector: ConnectorInstance): SinkWriterKind {
  if (connector.sinkWriterKind) {
    return connector.sinkWriterKind;
  }
  return heuristicWriterKind(connector.connectorTypeName || connector.name || "");
}

export function resolveSinkBlueprintKey(connector: ConnectorInstance): string {
  return WRITER_KIND_TO_BLUEPRINT[resolveSinkWriterKind(connector)];
}

export function resolveWriteDispositions(connector: ConnectorInstance): WriteDisposition[] {
  const declared = connector.writeDispositions;
  if (Array.isArray(declared) && declared.length > 0) {
    return declared.filter(
      (d): d is WriteDisposition =>
        !!d && typeof d.value === "string" && typeof d.label === "string"
    );
  }
  return DEFAULT_DISPOSITIONS[resolveSinkWriterKind(connector)];
}

export function resolveSinkTargetField(connector: ConnectorInstance): SinkTargetField {
  return TARGET_FIELD[resolveSinkWriterKind(connector)];
}

/**
 * Param name carrying the selected write disposition. StreamWriter uses
 * `publish_mode`; every other writer uses `write_mode`.
 */
export function dispositionParamName(connector: ConnectorInstance): "write_mode" | "publish_mode" {
  return resolveSinkWriterKind(connector) === "STREAM" ? "publish_mode" : "write_mode";
}

export interface SinkConfig {
  target_table?: string;
  output_path?: string;
  topic?: string;
  write_mode: string;
}

/**
 * LCT-045(c): only tenant-storage (INHERIT_TENANT_GCP_SERVICE_ACCOUNT)
 * connectors resolve a platform lake path from their config template.
 * Connector-specific destinations never have their metadata treated as
 * platform lake storage; the user supplies their own target.
 */
export function buildDefaultSinkConfig(connector: ConnectorInstance): SinkConfig {
  const kind = resolveSinkWriterKind(connector);
  const dispositions = resolveWriteDispositions(connector);
  const defaultMode = dispositions[0]?.value ?? "append";
  const config: SinkConfig = { write_mode: defaultMode };

  const template = connector.configTemplate || {};

  if (
    kind === "LAKE" &&
    connector.credentialStrategy === "INHERIT_TENANT_GCP_SERVICE_ACCOUNT"
  ) {
    const storagePath = readString(template.storage_path);
    const bucket = readString(template.bucket);
    const prefix = readString(template.path_prefix);
    if (storagePath) {
      config.output_path = storagePath;
    } else if (bucket) {
      config.output_path = joinPathSegments(`s3://${bucket}`, prefix);
    }
  }

  if (kind === "STREAM") {
    const topic = readString(template.topic);
    if (topic) {
      config.topic = topic;
    }
  }

  return config;
}

export type CredentialWarningTone = "none" | "yellow" | "orange";

export interface CredentialWarning {
  tone: CredentialWarningTone;
  message: string | null;
}

/**
 * LCT-047: drive the credential warning from the central, strategy-aware
 * readiness contract when present (so Add-Sink agrees with connector detail),
 * falling back to the per-env credential status map for older payloads.
 * UNTESTED is a soft "not yet validated" notice, NOT an orange "needs
 * attention" gate — and INHERIT connectors never surface UNTESTED at all.
 */
export function buildCredentialWarning(
  connector: ConnectorInstance,
  environment: DeployEnvironment,
  environmentLabel: string
): CredentialWarning {
  const status =
    connector.credentialReadiness?.status ??
    connector.credentialStatuses?.[environment];

  if (!status) {
    return { tone: "yellow", message: `Credentials not configured for ${environmentLabel}` };
  }

  switch (status) {
    case "READY":
    case "VALID":
      return { tone: "none", message: null };
    case "MISSING":
    case "MISSING_CONNECTOR":
      return { tone: "yellow", message: `Credentials not configured for ${environmentLabel}` };
    case "SKIPPED":
      return { tone: "yellow", message: `Credentials skipped for ${environmentLabel}` };
    case "UNTESTED":
      return { tone: "yellow", message: `Credentials not yet validated for ${environmentLabel}` };
    case "BLOCKED":
    case "FAILED":
    case "INVALID":
    case "EXPIRED":
      return { tone: "orange", message: `Credentials need attention for ${environmentLabel}` };
    default:
      return { tone: "yellow", message: `Credentials not configured for ${environmentLabel}` };
  }
}
