"use client";

import { useEffect, useMemo, useState } from "react";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
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
import { Badge } from "@/components/ui/badge";
import { toast } from "sonner";
import { api } from "@/lib/api";
import { useUpstreamSchema } from "@/hooks/use-upstream-schema";
import { SchemaConflictPanel } from "./schema-conflict-panel";
import { MnemonicDateInput } from "./mnemonic-date-input";
import { ExpressionInput } from "./expression-input";
import { ColumnPicker, MultiColumnPicker } from "./column-picker";
import { RenameMapper } from "./rename-mapper";
import { SqlFilterBuilder, type FilterCondition } from "./sql-filter-builder";
import { DqOutcomeControl } from "./dq-outcome-control";
import { DerivedColumnBuilder } from "./derived-column-builder";
import { SimpleSqlBuilder } from "./simple-sql-builder";
import { SqlChainEditor, type SqlChainStep } from "./sql-chain-editor";
import { CronBuilder } from "./cron-builder";
import type {
  Blueprint,
  DbtAsset,
  BlueprintParamDefinition,
  SchemaColumn,
  SubPipelineInstance,
} from "@/types";

/**
 * After V62 param deduplication, these ingestion blueprints have connection
 * parameters inherited from the linked connector instance. The dialog shows a
 * read-only connection summary instead of editable fields for those params.
 */
const INGESTION_INHERITED_PARAMS: Record<string, string[]> = {
  ApiIngestion: ["api_url", "auth_type", "pagination_type"],
  StreamIngestion: ["stream_type", "topic", "consumer_group"],
  FileIngestion: ["source_path", "file_format"],
  CDCIngestion: ["source_type"],
  SnapshotIngestion: ["source_table"],
  EncryptedSourceIngest: ["encryption_type"],
};

function humanizeParamName(name: string) {
  return name
    .split("_")
    .filter(Boolean)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ");
}

/* -------------------------------------------------------------------------- */
/*  Construct value (de)serialization helpers (W-13 type-switch wiring)        */
/*                                                                            */
/*  The dialog stores every param value as a STRING (`values[name]`). The     */
/*  purpose-built constructs work with structured values, so each wrapper      */
/*  parses the stored string into its shape and serializes back on change.     */
/*  Object/object[] params are stored as JSON; string[] as newline text.       */
/* -------------------------------------------------------------------------- */

/** Parse a stored JSON-object param value, tolerating empty / malformed text. */
function parseJsonRecord(raw: string): Record<string, string> {
  if (!raw || !raw.trim()) return {};
  try {
    const v = JSON.parse(raw);
    if (v && typeof v === "object" && !Array.isArray(v)) {
      const out: Record<string, string> = {};
      for (const [k, val] of Object.entries(v)) out[k] = String(val);
      return out;
    }
  } catch {
    /* fall through — malformed JSON yields an empty map (user can re-author) */
  }
  return {};
}

/** Parse a stored JSON-array-of-objects param value into FilterCondition rows. */
function parseConditionArray(raw: string): FilterCondition[] {
  if (!raw || !raw.trim()) return [];
  try {
    const v = JSON.parse(raw);
    if (Array.isArray(v)) {
      return v.map((r) => ({
        column: String(r?.column ?? ""),
        operator: String(r?.operator ?? "eq"),
        value: r?.value === undefined || r?.value === null ? "" : String(r.value),
        logic: r?.logic === "OR" ? "OR" : "AND",
        valueKind: r?.valueKind === "mnemonic" ? "mnemonic" : "literal",
      }));
    }
  } catch {
    /* malformed → empty rows */
  }
  return [];
}

/** Parse a stored steps array into SqlChainStep[]. */
function parseChainSteps(raw: string): SqlChainStep[] {
  if (!raw || !raw.trim()) return [];
  try {
    const v = JSON.parse(raw);
    if (Array.isArray(v)) {
      return v.map((s, i) => ({
        name: String(s?.name ?? `step_${i + 1}`),
        sql: String(s?.sql ?? ""),
        materialize: Boolean(s?.materialize),
      }));
    }
  } catch {
    /* malformed → empty chain */
  }
  return [];
}

const HINT_MULTI_COLUMN = "column-picker (multi)";

/**
 * Resolve the construct hint for a param: prefer the explicit #5 `ui_construct`
 * token; else infer a sensible default from the param `type` + `accepts_mnemonic`
 * so params that pre-date the hint plumbing still get a purpose-built control.
 */
function resolveConstructHint(definition: BlueprintParamDefinition): string {
  if (definition.ui_construct) return definition.ui_construct;
  // Only an EXPLICIT hint (or the legacy accepts_mnemonic flag) selects a
  // purpose-built control. Unhinted params keep their type-based fallback — a
  // generic `string[]` like `tags` is NOT a column role and stays a Textarea.
  if (definition.accepts_mnemonic) return "date-mnemonic-picker";
  return "";
}

function isDerivedParam(definition: BlueprintParamDefinition): boolean {
  return definition.tier === "derived" || definition.tier === "system-derived";
}

function formatParamValue(value: unknown): string {
  if (value === undefined || value === null || value === "") {
    return "Resolved by PULSE";
  }
  if (Array.isArray(value)) {
    return value.length > 0 ? value.map((entry) => String(entry)).join(", ") : "[]";
  }
  if (typeof value === "object") {
    return JSON.stringify(value);
  }
  return String(value);
}

function humanizeDerivedSource(source?: string): string {
  switch (source) {
    case "pipeline.storage":
      return "Pipeline storage";
    case "domain.calendar":
      return "Domain calendar";
    case "platform_default":
      return "Platform default";
    case "connector":
      return "Connector";
    case "pipeline":
      return "Pipeline";
    case "pipeline.id":
      return "Pipeline identity";
    case "target_dataset.state_binding":
      return "Target dataset state";
    default:
      return source ? humanizeParamName(source.replace(/\./g, "_")) : "PULSE";
  }
}

/** Derive the authority-aligned lake_format default for a given
 *  (storageBackend, lakeLayer) pair. This mirrors
 *  RuntimeAuthorityService so the dialog never initializes a GCP
 *  instance with the catalog's blanket "parquet" default. */
function authorityDefaultLakeFormat(
  storageBackend: string | null | undefined,
  lakeLayer: string | null | undefined
): string | undefined {
  if (!storageBackend) return undefined;          // unknown backend → let catalog default apply
  if (storageBackend === "DPC") return "parquet";
  if (storageBackend === "GCP") {
    if (lakeLayer === "gold") return "bq_native";
    return "iceberg_bq_managed";
  }
  return undefined;                                // unconstrained backend
}

function serializeMetadataValue(
  definition: BlueprintParamDefinition,
  value: unknown
): string {
  if (value === undefined || value === null) return "";

  switch (definition.type) {
    case "boolean":
      return String(Boolean(value));
    case "string[]":
      return Array.isArray(value)
        ? value.map((entry) => String(entry)).join("\n")
        : String(value);
    case "object":
    case "object[]":
      return typeof value === "string" ? value : JSON.stringify(value, null, 2);
    default:
      return String(value);
  }
}

function initializeMetadataDraft(
  paramsSchema: BlueprintParamDefinition[],
  currentParams: Record<string, unknown>,
  instanceStorageBackend?: string | null,
  instanceLakeLayer?: string | null
) {
  return Object.fromEntries(
    paramsSchema.map((definition) => {
      let sourceValue: unknown;
      if (currentParams[definition.name] !== undefined) {
        sourceValue = currentParams[definition.name];
      } else if (
        definition.name === "lake_format" &&
        instanceStorageBackend
      ) {
        // Derive from authority rather than the catalog's blanket default,
        // which may be illegal for this instance's storage backend (e.g.
        // catalog says "parquet" but instance is GCP → iceberg_bq_managed).
        sourceValue = authorityDefaultLakeFormat(
          instanceStorageBackend, instanceLakeLayer
        ) ?? definition.default;
      } else {
        sourceValue = definition.default;
      }
      return [definition.name, serializeMetadataValue(definition, sourceValue)];
    })
  ) as Record<string, string>;
}

function parseMetadataValue(
  definition: BlueprintParamDefinition,
  rawValue: string
): unknown {
  switch (definition.type) {
    case "integer": {
      const parsed = Number.parseInt(rawValue, 10);
      if (Number.isNaN(parsed)) {
        throw new Error(`${humanizeParamName(definition.name)} must be a whole number.`);
      }
      return parsed;
    }
    case "number": {
      const parsed = Number.parseFloat(rawValue);
      if (Number.isNaN(parsed)) {
        throw new Error(`${humanizeParamName(definition.name)} must be a number.`);
      }
      return parsed;
    }
    case "boolean":
      return rawValue === "true";
    case "string[]":
      return rawValue
        .split("\n")
        .map((entry) => entry.trim())
        .filter(Boolean);
    case "object": {
      const parsed = JSON.parse(rawValue);
      if (Array.isArray(parsed) || parsed === null || typeof parsed !== "object") {
        throw new Error(`${humanizeParamName(definition.name)} must be a JSON object.`);
      }
      return parsed;
    }
    case "object[]": {
      const parsed = JSON.parse(rawValue);
      if (!Array.isArray(parsed)) {
        throw new Error(`${humanizeParamName(definition.name)} must be a JSON array.`);
      }
      return parsed;
    }
    default:
      return rawValue;
  }
}

function buildMetadataDrivenParams({
  paramsSchema,
  currentParams,
  draftValues,
  inheritedFields,
  instanceStorageBackend,
  instanceLakeLayer,
}: {
  paramsSchema: BlueprintParamDefinition[];
  currentParams: Record<string, unknown>;
  draftValues: Record<string, string>;
  inheritedFields: string[];
  instanceStorageBackend?: string | null;
  instanceLakeLayer?: string | null;
}) {
  const params: Record<string, unknown> = { ...currentParams };

  for (const inheritedField of inheritedFields) {
    if (currentParams[inheritedField] !== undefined) {
      params[inheritedField] = currentParams[inheritedField];
    }
  }

  for (const definition of paramsSchema) {
    if (inheritedFields.includes(definition.name)) continue;

    const rawValue = draftValues[definition.name] ?? "";
    const trimmedValue = rawValue.trim();

    if (!trimmedValue) {
      // For lake_format, derive the authority-aligned default instead of
      // the catalog's blanket default (which may be illegal for this
      // instance's backend, e.g. catalog says parquet but instance is GCP).
      if (definition.name === "lake_format" && instanceStorageBackend) {
        const authorityDefault = authorityDefaultLakeFormat(
          instanceStorageBackend, instanceLakeLayer
        );
        if (authorityDefault) {
          params[definition.name] = authorityDefault;
          continue;
        }
      }
      if (definition.default !== undefined) {
        params[definition.name] = definition.default;
        continue;
      }
      if (isDerivedParam(definition)) {
        continue;
      }
      if (definition.required) {
        throw new Error(`${humanizeParamName(definition.name)} is required.`);
      }
      delete params[definition.name];
      continue;
    }

    params[definition.name] = parseMetadataValue(definition, trimmedValue);
  }

  return params;
}

interface ConfigureTransformDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  instanceId: string;
  instanceName: string;
  blueprintKey: string;
  blueprint?: Blueprint;
  versionId: string;
  domainId?: string;
  currentParams: Record<string, unknown>;
  /** Canonical storage assignment from the SubPipelineInstance.
   *  Needed so the dialog can derive authority-aligned lake_format
   *  defaults instead of falling back to a blanket catalog default
   *  (which may be illegal for the instance's actual backend). */
  storageBackend?: string | null;
  lakeLayer?: string | null;
  lakeFormat?: string | null;
  onSaved: () => void;
}

export function ConfigureTransformDialog({
  open,
  onOpenChange,
  instanceId,
  instanceName,
  blueprintKey,
  blueprint,
  versionId,
  domainId,
  currentParams: rawCurrentParams,
  storageBackend,
  lakeLayer,
  lakeFormat,
  onSaved,
}: ConfigureTransformDialogProps) {
  // Freshly-added instances persist with params=null until first save.
  // Coalesce here so every downstream access (connector_name,
  // business_concept, grain, etc.) is null-safe without sprinkling
  // ?. throughout the component.
  //
  // CRITICAL: useMemo around the coalesce. Without it, `?? {}` creates
  // a new {} reference on every render, which invalidates downstream
  // useMemo/useEffect deps that include currentParams, which triggers
  // another render, which creates another new {} — "Maximum update
  // depth exceeded" infinite loop. The useMemo keeps the empty-object
  // reference stable across renders when rawCurrentParams stays null.
  const currentParams = useMemo<Record<string, unknown>>(
    () => ({
      ...(rawCurrentParams ?? {}),
      ...(storageBackend ? { storage_backend: storageBackend } : {}),
      ...(lakeLayer ? { lake_layer: lakeLayer } : {}),
      ...(lakeFormat ? { lake_format: lakeFormat } : {}),
    }),
    [rawCurrentParams, storageBackend, lakeLayer, lakeFormat]
  );
  const { columns: upstreamSchema, loading } = useUpstreamSchema(
    open ? versionId : "",
    open ? instanceId : ""
  );
  type TabId = "params" | "schema" | "code_examples" | "dbt_mapping";
  const [activeTab, setActiveTab] = useState<TabId>("params");
  const hasDbtArtifact = blueprintEmitsDbt(blueprint);

  const paramsSchema = useMemo(
    () => blueprint?.paramsSchema ?? [],
    [blueprint]
  );
  const inheritedFields = INGESTION_INHERITED_PARAMS[blueprintKey] ?? [];
  const connectorName = (currentParams.connector_name as string) || "";

  // Fetch the linked connector's configTemplate so the "Inherited connection
  // settings" panel can show the actual values codegen will use, instead of
  // V62's hardcoded field names which often don't match the connector's real
  // vocabulary (e.g. S3 connectors store bucket/path_prefix, not source_path).
  const connectorInstanceId =
    typeof currentParams.connector_instance_id === "string"
      ? currentParams.connector_instance_id
      : null;
  const [connectorConfig, setConnectorConfig] = useState<{
    name: string | null;
    configTemplate: Record<string, unknown>;
  } | null>(null);
  useEffect(() => {
    if (!open || !connectorInstanceId) {
      setConnectorConfig(null);
      return;
    }
    let cancelled = false;
    api
      .get<{ name?: string; configTemplate?: Record<string, unknown> }>(
        `/api/v1/connector-instances/${connectorInstanceId}`
      )
      .then((res) => {
        if (cancelled) return;
        setConnectorConfig({
          name: res?.name ?? null,
          configTemplate: res?.configTemplate ?? {},
        });
      })
      .catch(() => {
        if (!cancelled) setConnectorConfig(null);
      });
    return () => {
      cancelled = true;
    };
  }, [open, connectorInstanceId]);

  const metadataDraftDefaults = useMemo(
    () => initializeMetadataDraft(paramsSchema, currentParams, storageBackend, lakeLayer),
    [paramsSchema, currentParams, storageBackend, lakeLayer]
  );

  const [metadataDraft, setMetadataDraft] = useState<Record<string, string>>(
    metadataDraftDefaults
  );
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [schemaFeedback, setSchemaFeedback] = useState<string | null>(null);
  const [reuseSuggestion, setReuseSuggestion] = useState<{
    asset?: DbtAsset;
    emitStrategy: string;
  } | null>(null);

  useEffect(() => {
    if (open) {
      setMetadataDraft(metadataDraftDefaults);
      setError(null);
      setSchemaFeedback(null);
    }
  }, [open, metadataDraftDefaults]);

  const metadataBusinessConcept =
    metadataDraft.business_concept?.trim() ||
    (typeof currentParams.business_concept === "string"
      ? currentParams.business_concept
      : "");
  const metadataGrain =
    metadataDraft.grain?.trim() ||
    (typeof currentParams.grain === "string" ? currentParams.grain : "");
  const metadataAccessLevel =
    metadataDraft.access_level?.trim() ||
    (typeof currentParams.access_level === "string"
      ? currentParams.access_level
      : "");
  const metadataSchemaSignature =
    metadataDraft.schema_signature?.trim() ||
    (typeof currentParams.schema_signature === "string"
      ? currentParams.schema_signature
      : "");

  useEffect(() => {
    if (!open || !domainId || !blueprint?.supportsReuse) {
      setReuseSuggestion(null);
      return;
    }

    const businessConcept = metadataBusinessConcept.trim();
    if (!businessConcept) {
      setReuseSuggestion(null);
      return;
    }

    let cancelled = false;
    const assetType =
      blueprintKey === "SCD2Dimension" ? "snapshot" : "model";

    api
      .get<{ asset?: DbtAsset; emitStrategy: string }>(
        `/api/v1/domains/${domainId}/dbt-assets/reuse-candidate?businessConcept=${encodeURIComponent(
          businessConcept
        )}&assetType=${encodeURIComponent(assetType)}&grain=${encodeURIComponent(
          metadataGrain
        )}&accessLevel=${encodeURIComponent(
          metadataAccessLevel
        )}&schemaSignature=${encodeURIComponent(metadataSchemaSignature)}`
      )
      .then((result) => {
        if (!cancelled) {
          setReuseSuggestion(
            result.asset && Object.keys(result.asset).length > 0 ? result : null
          );
        }
      })
      .catch(() => {
        if (!cancelled) setReuseSuggestion(null);
      });

    return () => {
      cancelled = true;
    };
  }, [
    blueprint?.supportsReuse,
    blueprintKey,
    domainId,
    metadataAccessLevel,
    metadataBusinessConcept,
    metadataGrain,
    metadataSchemaSignature,
    open,
  ]);

  const handleSave = async () => {
    setSaving(true);
    setError(null);
    setSchemaFeedback(null);
    try {
      if (!blueprint) {
        throw new Error("Blueprint metadata is not loaded yet.");
      }

      const params = buildMetadataDrivenParams({
        paramsSchema,
        currentParams,
        draftValues: metadataDraft,
        inheritedFields,
        instanceStorageBackend: storageBackend,
        instanceLakeLayer: lakeLayer,
      });

      if (reuseSuggestion?.asset?.assetName) {
        params.business_concept = metadataBusinessConcept;
        params.reuse_mode = reuseSuggestion.emitStrategy;
        params.reuse_asset_name = reuseSuggestion.asset.assetName;
      }

      const result = await api.put<SubPipelineInstance>(
        `/api/v1/versions/${versionId}/composition/instances/${instanceId}/params`,
        params
      );

      const colCount = result?.outputSchema?.columns?.length;
      if (colCount && colCount > 0) {
        toast.success(
          `Configuration saved (schema: ${colCount} columns)`
        );
      } else {
        toast.success("Configuration saved");
      }
      onSaved();
      onOpenChange(false);
    } catch (e: unknown) {
      const message = e instanceof Error ? e.message : "Failed to save";
      // Inline error in the dialog for in-context detail (e.g., field
      // name in validation errors), AND a global toast so the user
      // gets feedback even when the dialog is scrolled past the
      // header where the inline banner lives.
      setError(message);
      // Override the default 1s duration — validation errors like
      // "Filename Pattern is required" need longer to read than a
      // success confirmation.
      toast.error(message, { duration: 5000 });
    } finally {
      setSaving(false);
    }
  };

  const displayName = blueprint?.name ?? blueprintKey;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl max-h-[85vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>Configure: {instanceName}</DialogTitle>
          <DialogDescription>
            <Badge variant="secondary" className="text-xs">
              {displayName}
            </Badge>
            <Badge variant="outline" className="font-mono text-xs ml-2">
              {blueprintKey}
            </Badge>
            {upstreamSchema.length > 0 && (
              <span className="ml-2 text-xs">
                {upstreamSchema.length} upstream columns detected
              </span>
            )}
          </DialogDescription>
        </DialogHeader>

        {error && (
          <div className="rounded-md bg-destructive/10 text-destructive text-xs px-3 py-2">
            {error}
          </div>
        )}

        {schemaFeedback && (
          <div className="rounded-md bg-green-50 dark:bg-green-950 text-green-700 dark:text-green-300 text-xs px-3 py-2 flex items-center gap-2">
            <span>✓</span>
            <span>{schemaFeedback}</span>
          </div>
        )}

        <div className="flex gap-1 border-b mt-2">
          <TabButton
            active={activeTab === "params"}
            onClick={() => setActiveTab("params")}
            label="Params"
          />
          <TabButton
            active={activeTab === "schema"}
            onClick={() => setActiveTab("schema")}
            label="Schema"
          />
          <TabButton
            active={activeTab === "code_examples"}
            onClick={() => setActiveTab("code_examples")}
            label="Code Examples"
          />
          {hasDbtArtifact && (
            <TabButton
              active={activeTab === "dbt_mapping"}
              onClick={() => setActiveTab("dbt_mapping")}
              label="dbt Mapping (dbt)"
            />
          )}
        </div>

        {activeTab === "params" && (
          loading ? (
            <p className="text-sm text-muted-foreground py-4">
              Loading upstream schema…
            </p>
          ) : blueprint ? (
            <div className="space-y-4 py-2">
              <MetadataDrivenConfig
                blueprint={blueprint}
                currentParams={currentParams}
                inheritedFields={inheritedFields}
                connectorName={connectorConfig?.name ?? connectorName}
                connectorConfigTemplate={connectorConfig?.configTemplate ?? null}
                values={metadataDraft}
                upstreamSchema={upstreamSchema}
                loading={loading}
                versionId={versionId}
                instanceId={instanceId}
                onValueChange={(fieldName, value) =>
                  setMetadataDraft((prev) => ({ ...prev, [fieldName]: value }))
                }
              />
              {reuseSuggestion?.asset && (
                <div className="rounded-md border border-emerald-500/30 bg-emerald-500/5 px-3 py-2 text-xs">
                  <div className="font-medium text-emerald-700 dark:text-emerald-300">
                    Reuse suggestion available
                  </div>
                  <div className="mt-1 text-muted-foreground">
                    Reuse <span className="font-mono">{reuseSuggestion.asset.assetName}</span>{" "}
                    as a {reuseSuggestion.emitStrategy.replace("_", " ")} for{" "}
                    <span className="font-medium">{metadataBusinessConcept}</span>.
                  </div>
                </div>
              )}
            </div>
          ) : (
            <p className="text-sm text-muted-foreground py-4">
              Blueprint metadata is unavailable.
            </p>
          )
        )}

        {activeTab === "schema" && (
          <SchemaTab
            versionId={versionId}
            instanceId={instanceId}
          />
        )}

        {activeTab === "code_examples" && (
          <CodeExamplesTab blueprintKey={blueprintKey} />
        )}

        {activeTab === "dbt_mapping" && hasDbtArtifact && (
          <DbtMappingTab blueprint={blueprint} />
        )}

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button onClick={handleSave} disabled={saving || !blueprint}>
            {saving ? "Saving…" : "Save Configuration"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/**
 * Param names that hold a free-form SQL boolean predicate. Rendered with the
 * Calcite-validated ExpressionInput component (PR B, #88) so users see
 * syntax / unknown-column diagnostics live as they type. Add to this list
 * when introducing new predicate-bearing params; row-level expression
 * validation inside object[] params (e.g. derived_columns[].expression,
 * route_predicates[].expression) is a follow-up — for now those still use
 * the default JSON-text editor.
 */
const PREDICATE_EXPRESSION_FIELDS = new Set<string>([
  "filter_condition",
  "raw_sql",
  "having_clause",
  "join_condition",
]);

function isPredicateExpressionField(name: string): boolean {
  return PREDICATE_EXPRESSION_FIELDS.has(name);
}

function legalLakeFormats(backend: string, layer: string): string[] {
  if (backend === "DPC") return ["parquet", "iceberg_external"];
  if (backend === "GCP") {
    if (layer === "gold") return ["bq_native"];
    return ["iceberg_bq_managed", "iceberg_external"];
  }
  return ["iceberg_bq_managed", "iceberg_external", "bq_native", "parquet"];
}

function defaultLakeFormat(backend: string, layer: string): string {
  if (backend === "DPC") return "parquet";
  if (backend === "GCP" && layer === "gold") return "bq_native";
  if (backend === "GCP") return "iceberg_bq_managed";
  return "iceberg_bq_managed";
}

/* -------------------------------------------------------------------------- */
/*  MetadataDrivenConfig                                                      */
/* -------------------------------------------------------------------------- */

export function MetadataDrivenConfig({
  blueprint,
  currentParams,
  inheritedFields,
  connectorName,
  connectorConfigTemplate,
  values,
  upstreamSchema,
  loading,
  versionId,
  instanceId,
  onValueChange,
}: {
  blueprint: Blueprint;
  currentParams: Record<string, unknown>;
  inheritedFields: string[];
  connectorName?: string;
  connectorConfigTemplate: Record<string, unknown> | null;
  values: Record<string, string>;
  /** Resolved upstream schema (from useUpstreamSchema). Used by ExpressionInput
   *  for live validation of derived columns / filter / router predicates. */
  upstreamSchema: SchemaColumn[];
  /** W-2: schema fetch in flight → ColumnPicker shows a skeleton, not the text
   *  fallback. */
  loading?: boolean;
  /** Identifiers the SqlChainEditor preview / SimpleSqlBuilder validate calls need. */
  versionId?: string;
  instanceId?: string;
  onValueChange: (fieldName: string, value: string) => void;
}) {
  // Filter out:
  //   - inherited fields (rendered separately in the "Inherited connection
  //     settings" panel above)
  //   - `type: "metadata"` pseudo-params (e.g., _inherited_from_connector,
  //     a marker schema entry that lists which fields are inherited; not
  //     a real input — must not render as an editable string[] textarea)
  //   - any param whose name starts with "_" (convention for hidden /
  //     platform-internal markers)
  const visibleParams = blueprint.paramsSchema.filter(
    (definition) =>
      !inheritedFields.includes(definition.name) &&
      definition.type !== "metadata" &&
      !definition.name.startsWith("_")
  );

  const storageBackend = values["storage_backend"];
  const lakeLayer = values["lake_layer"];
  const lakeFormat = values["lake_format"];
  const goldOnGcp = storageBackend === "GCP" && lakeLayer === "gold";
  useEffect(() => {
    if (!storageBackend || !lakeLayer) return;
    const legalFormats = legalLakeFormats(storageBackend, lakeLayer);
    if (legalFormats.length === 0) return;
    if (!lakeFormat || !legalFormats.includes(lakeFormat)) {
      onValueChange("lake_format", defaultLakeFormat(storageBackend, lakeLayer));
    }
  }, [storageBackend, lakeLayer, lakeFormat, onValueChange]);

  return (
    <div className="space-y-4">
      {(() => {
        // Render the inherited-settings panel when EITHER:
        //   - a linked connector has a non-empty configTemplate (the real
        //     source of truth — codegen reads connector.configTemplate to
        //     build the merged runtime config), or
        //   - the blueprint declares legacy V62 inheritedFields and any of
        //     them happen to be set on the instance (older pipelines that
        //     pre-date the connector-fetch UX).
        // Both can apply; render connector first since it's authoritative.
        const connectorEntries = connectorConfigTemplate
          ? Object.entries(connectorConfigTemplate).filter(
              ([, value]) => value !== null && value !== undefined && value !== ""
            )
          : [];
        const legacyInheritedEntries = inheritedFields
          .map((fieldName) => [fieldName, currentParams[fieldName]] as const)
          .filter(([, value]) => value !== undefined && value !== null && value !== "");
        if (connectorEntries.length === 0 && legacyInheritedEntries.length === 0) {
          return null;
        }
        return (
          <div className="rounded-md border bg-muted/30 px-3 py-3 space-y-3">
            <div>
              <p className="text-sm font-medium">Inherited connection settings</p>
              <p className="text-xs text-muted-foreground">
                These values come from the linked connector{connectorName ? ` (${connectorName})` : ""} and stay read-only. Codegen merges them with credential metadata at runtime.
              </p>
            </div>
            {connectorEntries.length > 0 && (
              <div className="grid gap-2 sm:grid-cols-2">
                {connectorEntries.map(([fieldName, rawValue]) => {
                  const display =
                    typeof rawValue === "string" ? rawValue : JSON.stringify(rawValue);
                  return (
                    <div key={`conn-${fieldName}`} className="rounded border bg-background px-3 py-2">
                      <p className="text-[11px] uppercase tracking-wide text-muted-foreground">
                        {humanizeParamName(fieldName)}
                      </p>
                      <p className="text-sm font-medium break-all">{display}</p>
                    </div>
                  );
                })}
              </div>
            )}
            {legacyInheritedEntries.length > 0 && (
              <div className="grid gap-2 sm:grid-cols-2">
                {legacyInheritedEntries.map(([fieldName, rawValue]) => {
                  const display =
                    typeof rawValue === "string" ? rawValue : JSON.stringify(rawValue);
                  return (
                    <div key={`legacy-${fieldName}`} className="rounded border bg-background px-3 py-2">
                      <p className="text-[11px] uppercase tracking-wide text-muted-foreground">
                        {humanizeParamName(fieldName)}
                      </p>
                      <p className="text-sm font-medium break-all">{display}</p>
                    </div>
                  );
                })}
              </div>
            )}
          </div>
        );
      })()}

      {visibleParams.length === 0 ? (
        <p className="text-sm text-muted-foreground">
          No parameters are exposed for this step.
        </p>
      ) : (
        visibleParams.map((definition) => {
          const fieldLabel = humanizeParamName(definition.name);
          const description = definition.description;
          const currentValue = values[definition.name] ?? "";
          const derived = isDerivedParam(definition);
          // W-13: route by the #5 UI-construct HINT token first (two same-`type`
          // params can need different controls), falling back to type-based arms.
          const constructHint = resolveConstructHint(definition);
          // W-9: the host Blueprint's declared output port roles — drives the
          // port-aware dq-outcome-control (quarantine only with a quarantine port).
          const outputPortRoles = (blueprint?.outputPorts ?? []).map((p) => p.name);

          return (
            <div key={definition.name} className="space-y-2">
              <div className="flex items-center gap-2 flex-wrap">
                <Label htmlFor={`param-${definition.name}`}>{fieldLabel}</Label>
                {definition.required && (
                  <Badge variant="outline" className="text-[10px]">
                    Required
                  </Badge>
                )}
                <Badge variant="secondary" className="text-[10px] font-mono">
                  {definition.type}
                </Badge>
                {derived && (
                  <Badge variant="outline" className="text-[10px]">
                    Derived
                  </Badge>
                )}
              </div>

              {derived ? (
                <DerivedParamInspector
                  definition={definition}
                  value={currentValue}
                />
              ) : constructHint === "column-picker" ? (
                // Single-column role param → schema-bound ColumnPicker (S3).
                <ColumnPicker
                  value={currentValue}
                  onChange={(value) => onValueChange(definition.name, value)}
                  columns={upstreamSchema}
                  loading={loading}
                  filterTypes={definition.filter_types}
                  placeholder={`Select ${fieldLabel.toLowerCase()}…`}
                  className="w-full"
                />
              ) : constructHint === HINT_MULTI_COLUMN ? (
                // Multi-column role param (was a newline Textarea) → badge-toggle
                // MultiColumnPicker (S3). Stored as a newline-joined string to
                // keep the existing `string[]` serialization.
                <MultiColumnPicker
                  columns={upstreamSchema}
                  loading={loading}
                  filterTypes={definition.filter_types}
                  selected={currentValue ? currentValue.split("\n").map((s) => s.trim()).filter(Boolean) : []}
                  onChange={(sel) => onValueChange(definition.name, sel.join("\n"))}
                  fallbackPlaceholder="col1, col2, …"
                />
              ) : constructHint === "rename-mapper" ||
                constructHint === "key-value-mapper" ||
                constructHint === "type-cast-mapper" ? (
                // Two-column-map param (was raw-JSON object Textarea) → RenameMapper
                // (S4). The hint parameterizes the right-hand cell (W-3).
                <RenameMapper
                  columns={upstreamSchema}
                  loading={loading}
                  value={parseJsonRecord(currentValue)}
                  onChange={(map) => onValueChange(definition.name, JSON.stringify(map))}
                  rightCell={
                    constructHint === "type-cast-mapper"
                      ? "select"
                      : constructHint === "key-value-mapper"
                        ? "value"
                        : "text"
                  }
                />
              ) : constructHint === "condition-builder" || constructHint === "route-builder" ? (
                // Filter/WHERE predicate rows (was raw-JSON object[] Textarea) →
                // SqlFilterBuilder visual condition-builder (S5).
                <SqlFilterBuilder
                  conditions={parseConditionArray(currentValue)}
                  rawSql=""
                  filterMode="visual"
                  availableColumns={upstreamSchema.map((c) => c.name)}
                  columnSchema={upstreamSchema}
                  onChange={(u) => onValueChange(definition.name, JSON.stringify(u.conditions))}
                />
              ) : constructHint === "dq-outcome-control" ? (
                // DQ failure disposition (was a generic enum Select) → port-aware
                // DqOutcomeControl (S7, W-9).
                <DqOutcomeControl
                  id={`param-${definition.name}`}
                  value={currentValue}
                  onChange={(value) => onValueChange(definition.name, value)}
                  options={definition.options}
                  outputPorts={outputPortRoles}
                />
              ) : constructHint === "sql-chain-editor" ? (
                // SqlModel.steps (was raw-JSON object[] Textarea) → rich
                // SqlChainEditor (S1a). Backend deps (Calcite, preview) noted.
                <SqlChainEditor
                  steps={parseChainSteps(currentValue)}
                  onChange={(steps) => onValueChange(definition.name, JSON.stringify(steps))}
                  inputColumns={upstreamSchema}
                  versionId={versionId}
                  instanceId={instanceId}
                />
              ) : constructHint === "simple-sql-builder" ? (
                // SourceSQL / BulkBackfill source_query → SimpleSqlBuilder (S1b,
                // source-DB validate-only). JDBC-prepare endpoint = backend dep.
                <SimpleSqlBuilder
                  value={currentValue}
                  onChange={(sql) => onValueChange(definition.name, sql)}
                  connectorInstanceId={
                    (currentParams.connector_instance_id as string) ||
                    (currentParams.connector_id as string) ||
                    undefined
                  }
                />
              ) : constructHint === "cron-builder" ? (
                // Schedule expression → standalone CronBuilder (S6, W-8).
                <CronBuilder
                  id={`param-${definition.name}`}
                  value={currentValue}
                  onChange={(value) => onValueChange(definition.name, value)}
                />
              ) : constructHint === "derived-column-builder" ? (
                // LCT-055: derived_columns (object[]) → rich per-entry builder
                // with name/type/expression fields. Reuses ExpressionInput for
                // live-validated SQL expression editing.
                <DerivedColumnBuilder
                  value={currentValue}
                  onChange={(next) => onValueChange(definition.name, next)}
                  inputSchemas={[{ portName: "primary", columns: upstreamSchema }]}
                />
              ) : constructHint === "expression-builder" ? (
                // Derived-column / SQL expression → live-validated ExpressionInput.
                <ExpressionInput
                  value={currentValue}
                  onChange={(next) => onValueChange(definition.name, next)}
                  kind="value"
                  inputSchemas={[{ portName: "primary", columns: upstreamSchema }]}
                />
              ) : constructHint === "date-mnemonic-picker" || definition.accepts_mnemonic ? (
                // Date-shaped param flagged by V92 audit / #5 hint. The
                // MnemonicDateInput accepts ISO date literals AND PULSE mnemonics
                // (BOM-12, PBD, NBDOM(5), etc.). Resolved at runtime by pulse_dates
                // against the tenant's holiday calendar + fiscal offset.
                <MnemonicDateInput
                  id={`param-${definition.name}`}
                  value={currentValue}
                  onChange={(value) => onValueChange(definition.name, value)}
                  dateTokenMode={
                    definition.name === "filename_pattern" ? "filename" : "sql"
                  }
                />
              ) : isPredicateExpressionField(definition.name) ? (
                // PR B (#88): predicates get the live-validation ExpressionInput
                // so users see syntax / unknown-column diagnostics as they type.
                // The schema is the upstream input columns supplied by
                // useUpstreamSchema. Multi-port join predicates aren't here yet
                // — those need both ports' schemas, follow-up.
                <ExpressionInput
                  value={currentValue}
                  onChange={(next) => onValueChange(definition.name, next)}
                  kind="predicate"
                  inputSchemas={[
                    { portName: "primary", columns: upstreamSchema },
                  ]}
                />
              ) : definition.type === "enum" ? (
                <Select
                  value={currentValue}
                  onValueChange={(value) => onValueChange(definition.name, value)}
                  disabled={definition.name === "lake_format" && goldOnGcp}
                >
                  <SelectTrigger id={`param-${definition.name}`}>
                    <SelectValue placeholder={`Select ${fieldLabel.toLowerCase()}`} />
                  </SelectTrigger>
                  <SelectContent>
                    {(definition.name === "lake_format"
                        ? legalLakeFormats(storageBackend ?? "", lakeLayer ?? "")
                        : (Array.isArray(definition.options) ? definition.options : [])
                    ).map((option) => (
                      <SelectItem key={option} value={option}>
                        {option}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              ) : definition.type === "boolean" ? (
                <Select
                  value={currentValue || "false"}
                  onValueChange={(value) => onValueChange(definition.name, value)}
                >
                  <SelectTrigger id={`param-${definition.name}`}>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="true">True</SelectItem>
                    <SelectItem value="false">False</SelectItem>
                  </SelectContent>
                </Select>
              ) : definition.type === "string[]" ? (
                <Textarea
                  id={`param-${definition.name}`}
                  value={currentValue}
                  onChange={(event) => onValueChange(definition.name, event.target.value)}
                  rows={4}
                  placeholder="One value per line"
                />
              ) : definition.type === "object" || definition.type === "object[]" ? (
                <Textarea
                  id={`param-${definition.name}`}
                  value={currentValue}
                  onChange={(event) => onValueChange(definition.name, event.target.value)}
                  rows={8}
                  className="font-mono text-xs"
                  placeholder={definition.type === "object[]" ? "[]" : "{}"}
                />
              ) : (
                <Input
                  id={`param-${definition.name}`}
                  type={definition.type === "integer" || definition.type === "number" ? "number" : "text"}
                  value={currentValue}
                  onChange={(event) => onValueChange(definition.name, event.target.value)}
                />
              )}

              {description && (
                <p className="text-xs text-muted-foreground">{description}</p>
              )}
              {definition.name === "lake_format" && goldOnGcp && (
                <p className="text-[11px] text-amber-700 dark:text-amber-400">
                  Locked to <code className="font-mono">bq_native</code> — gold tables on
                  GCP must use BigQuery native storage. Switch storage_backend to
                  DPC if you need a non-BQ gold table.
                </p>
              )}
              {definition.type === "string[]" && !constructHint && !derived && (
                <p className="text-[11px] text-muted-foreground">
                  Enter one item per line.
                </p>
              )}
              {(definition.type === "object" || definition.type === "object[]") &&
                !constructHint &&
                !derived && (
                  <p className="text-[11px] text-muted-foreground">
                    Enter valid JSON {definition.type === "object[]" ? "array" : "object"} syntax.
                  </p>
                )}
            </div>
          );
        })
      )}
    </div>
  );
}

function DerivedParamInspector({
  definition,
  value,
}: {
  definition: BlueprintParamDefinition;
  value: string;
}) {
  return (
    <div
      className="rounded-md border bg-muted/30 px-3 py-2"
      data-derived-param={definition.name}
    >
      <div className="flex items-start justify-between gap-3">
        <div>
          <p className="text-sm font-medium break-all">
            {formatParamValue(value)}
          </p>
          <p className="text-[11px] text-muted-foreground">
            Derived from {humanizeDerivedSource(definition.derivedFrom)}
          </p>
        </div>
        <Badge variant="secondary" className="text-[10px] shrink-0">
          Read-only
        </Badge>
      </div>
    </div>
  );
}

function TabButton({
  active,
  onClick,
  label,
}: {
  active: boolean;
  onClick: () => void;
  label: string;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`px-3 py-1.5 text-xs font-medium border-b-2 transition ${
        active
          ? "border-primary text-primary"
          : "border-transparent text-muted-foreground hover:text-foreground"
      }`}
    >
      {label}
    </button>
  );
}

interface SchemaPortView {
  portName: string;
  direction: string;
  columns: Array<{ name: string; type: string }>;
  source: string;
  override: Record<string, unknown> | null;
  schemaHash: string;
}

interface SchemaInstanceView {
  instanceId: string;
  name: string;
  blueprintKey: string;
  schemaStatus: string;
  ports: SchemaPortView[];
}

interface SchemaGraph {
  versionId: string;
  instances: SchemaInstanceView[];
}

function SchemaTab({
  versionId,
  instanceId,
}: {
  versionId: string;
  instanceId: string;
}) {
  const [graph, setGraph] = useState<SchemaGraph | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = () => {
    api
      .get<SchemaGraph>(`/api/v1/versions/${versionId}/schema-graph`)
      .then((g) => setGraph(g))
      .catch((e) =>
        setError(e instanceof Error ? e.message : "Failed to load schema graph")
      );
  };
  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [versionId, instanceId]);

  if (error) {
    return (
      <div className="rounded-md bg-destructive/10 text-destructive text-xs px-3 py-2 mt-2">
        {error}
      </div>
    );
  }
  if (!graph) {
    return (
      <p className="text-sm text-muted-foreground py-4">Loading schema…</p>
    );
  }
  const instance = graph.instances.find((i) => i.instanceId === instanceId);
  const inputPorts = instance?.ports.filter((p) => p.direction === "input") ?? [];
  const outputPorts = instance?.ports.filter((p) => p.direction === "output") ?? [];

  return (
    <div className="space-y-4 py-2 text-xs">
      <div>
        <h4 className="font-semibold mb-1">Input ports</h4>
        {inputPorts.length === 0 ? (
          <p className="text-muted-foreground">
            No resolved input ports. Wire this instance to an upstream to populate.
          </p>
        ) : (
          inputPorts.map((p) => (
            <PortCard key={`${p.portName}-${p.direction}`} port={p} />
          ))
        )}
      </div>
      <div>
        <h4 className="font-semibold mb-1">Output ports</h4>
        {outputPorts.length === 0 ? (
          <p className="text-muted-foreground">No output ports yet.</p>
        ) : (
          outputPorts.map((p) => (
            <PortCard key={`${p.portName}-${p.direction}`} port={p} />
          ))
        )}
      </div>
      <div>
        <h4 className="font-semibold mb-1">Conflicts</h4>
        <SchemaConflictPanel
          versionId={versionId}
          instanceId={instanceId}
          onResolved={load}
        />
      </div>
    </div>
  );
}

function PortCard({ port }: { port: SchemaPortView }) {
  return (
    <div className="rounded-md border px-3 py-2 mb-2">
      <div className="flex items-center gap-2">
        <span className="font-mono text-[11px]">{port.portName}</span>
        <Badge variant="outline" className="text-[10px]">
          {port.source}
        </Badge>
        {port.override && (
          <Badge className="text-[10px] bg-amber-500/20 text-amber-700 border-amber-400">
            Overridden
          </Badge>
        )}
        {port.schemaHash && (
          <span className="font-mono text-[10px] text-muted-foreground">
            {port.schemaHash.slice(0, 8)}
          </span>
        )}
      </div>
      {port.columns.length === 0 ? (
        <p className="text-muted-foreground mt-1">No columns.</p>
      ) : (
        <table className="w-full mt-1 text-[11px]">
          <tbody>
            {port.columns.map((c) => (
              <tr key={c.name} className="border-t first:border-t-0">
                <td className="py-0.5 font-mono">{c.name}</td>
                <td className="py-0.5 text-muted-foreground">{c.type}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

function blueprintEmitsDbt(blueprint: Blueprint | undefined): boolean {
  if (!blueprint?.artifactTypes) return false;
  const types = blueprint.artifactTypes as string[];
  return types.includes("dbt_model") || types.includes("dbt_snapshot");
}

// Matches CodegenExampleService.Example on the backend (since #44).
// Each example file is delivered with its filename-derived key, a
// language hint for syntax-highlighting, and the file content.
interface CodeExample {
  key: string;
  language: string;
  content: string;
}

function CodeExamplesTab({ blueprintKey }: { blueprintKey: string }) {
  const [examples, setExamples] = useState<CodeExample[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    api
      .get<CodeExample[]>(`/api/v1/blueprints/${encodeURIComponent(blueprintKey)}/examples`)
      .then((list) => {
        if (!cancelled) setExamples(list ?? []);
      })
      .catch((err) => {
        if (cancelled) return;
        if (err instanceof Error && err.message.includes("410")) {
          setError("This blueprint is deprecated. Examples are hidden.");
          setExamples([]);
        } else {
          setError(err instanceof Error ? err.message : "Failed to load examples");
          setExamples([]);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [blueprintKey]);

  if (error) {
    return (
      <div className="rounded-md bg-destructive/10 text-destructive text-xs px-3 py-2 mt-2">
        {error}
      </div>
    );
  }
  if (examples === null) {
    return <p className="text-sm text-muted-foreground py-4">Loading examples…</p>;
  }
  if (examples.length === 0) {
    return (
      <p className="text-sm text-muted-foreground py-4">
        No code examples indexed for this blueprint yet.
      </p>
    );
  }
  return (
    <div className="space-y-3 py-2">
      {examples.map((example, idx) => (
        <div key={example.key ?? idx} className="rounded-md border">
          <div className="px-3 py-1 border-b text-[11px] font-mono text-muted-foreground flex items-center justify-between">
            <span>{example.key ?? `Example ${idx + 1}`}</span>
            <span className="text-[10px] uppercase tracking-wider opacity-70">
              {example.language ?? "plaintext"}
            </span>
          </div>
          <pre className="p-3 text-[11px] overflow-x-auto whitespace-pre">
            {example.content}
          </pre>
        </div>
      ))}
    </div>
  );
}

function DbtMappingTab({ blueprint }: { blueprint?: Blueprint }) {
  if (!blueprint) {
    return <p className="text-sm text-muted-foreground py-4">Blueprint unavailable.</p>;
  }
  const validLayers = blueprint.validLayers ?? [];
  const layer = validLayers.includes("gold")
    ? "marts"
    : validLayers.includes("silver")
    ? "staging/intermediate"
    : "models";
  const artifactTypes = (blueprint.artifactTypes as string[]) ?? [];
  const isSnapshot = artifactTypes.includes("dbt_snapshot");
  const pathTemplate = isSnapshot
    ? `dbt_project/snapshots/{domain}/snp__{entity}.sql`
    : `dbt_project/models/${layer}/{source}/{entity}.sql`;
  return (
    <div className="space-y-3 py-2 text-xs">
      <div className="rounded-md border px-3 py-2">
        <p className="font-semibold">Generated dbt artifact (dbt)</p>
        <p className="text-muted-foreground mt-1">
          Path template: <code className="font-mono">{pathTemplate}</code>
        </p>
        <p className="text-muted-foreground">
          Materialization: <code className="font-mono">{isSnapshot ? "snapshot" : "table"}</code>
        </p>
        <p className="text-muted-foreground">
          Compute: Spark via <code className="font-mono">dbt-spark</code>
        </p>
      </div>
      <p className="text-muted-foreground">
        PULSE generates the SQL and wires it into the tenant&apos;s dbt project under{" "}
        <code className="font-mono">dbt_project/</code>. Users do not write dbt directly —
        the agent is the dbt expert (dbt).
      </p>
    </div>
  );
}
