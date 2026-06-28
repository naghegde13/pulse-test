"use client";

import { useEffect, useState, useTransition } from "react";
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
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { useTenant } from "@/contexts/tenant-context";
import { api } from "@/lib/api";
import type {
  Dataset,
  DatasetDefinitionType,
  DefinitionMethod,
  DataClassification,
} from "@/types";
import { toast } from "sonner";

interface DefineDatasetDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  connectorInstanceId?: string | null;
  connectorDefinitionId?: string | null;
  sorId?: string;
  onCreated: (dataset: Dataset) => void;
}

type Step = "method" | "define" | "review";

interface InferredField {
  name: string;
  type: string;
  nullable: boolean;
  pii?: boolean;
  classification?: string | null;
}

interface SampleInferResult {
  fields: InferredField[];
  classification: DataClassification;
  discoveryMethod: string;
  discoveryProof: Record<string, unknown>;
  previewRows: Array<Record<string, unknown>>;
  fieldCount: number;
}

function sampleFormatFromFilename(filename: string): "CSV" | "TSV" | "JSON" | null {
  const ext = filename.split(".").pop()?.toLowerCase() || "";
  if (ext === "csv") return "CSV";
  if (ext === "tsv") return "TSV";
  if (ext === "json") return "JSON";
  if (ext === "txt") return "CSV";
  return null;
}

export function DefineDatasetDialog({
  open,
  onOpenChange,
  connectorInstanceId,
  connectorDefinitionId,
  sorId,
  onCreated,
}: DefineDatasetDialogProps) {
  const { currentTenant } = useTenant();
  const [step, setStep] = useState<Step>("method");
  const [methods, setMethods] = useState<DefinitionMethod[]>([]);
  const [selectedMethod, setSelectedMethod] = useState<DatasetDefinitionType | null>(null);
  const [saving, setSaving] = useState(false);
  const [, startTransition] = useTransition();

  // Form state
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [classification, setClassification] = useState<DataClassification>("INTERNAL");
  const [customSql, setCustomSql] = useState("");
  const [sourceTables, setSourceTables] = useState("");
  const [schemaFormat, setSchemaFormat] = useState("JSON_SCHEMA");
  const [schemaText, setSchemaText] = useState("");
  const [filePath, setFilePath] = useState("");
  const [fileGlob, setFileGlob] = useState("*");
  const [fileFormat, setFileFormat] = useState("parquet");
  const [registryUrl, setRegistryUrl] = useState("");
  const [topic, setTopic] = useState("");
  const [apiSpecText, setApiSpecText] = useState("");
  const [apiEndpoint, setApiEndpoint] = useState("");
  const [objectNames, setObjectNames] = useState("");
  const [sampleSize, setSampleSize] = useState("100");
  const [manualFields, setManualFields] = useState<Array<{ name: string; type: string; nullable: boolean; description: string }>>([
    { name: "", type: "STRING", nullable: true, description: "" },
  ]);

  // Physical design fields (PKT-0023)
  const [writeMode, setWriteMode] = useState("append");
  const [tableFormatHint, setTableFormatHint] = useState("");
  const [partitionColumns, setPartitionColumns] = useState("");

  // LCT-017/018: first-class sample-upload ingestion state
  const [sampleFileName, setSampleFileName] = useState("");
  const [sampleInferring, setSampleInferring] = useState(false);
  const [sampleResult, setSampleResult] = useState<SampleInferResult | null>(null);
  const [sampleError, setSampleError] = useState<string | null>(null);

  // LCT-019: inline save-error surface (e.g. duplicate qualified name -> 409)
  const [saveError, setSaveError] = useState<string | null>(null);

  useEffect(() => {
    if (!open) return;
    setStep("method");
    setSelectedMethod(null);
    setName("");
    setDescription("");
    setClassification("INTERNAL");
    setCustomSql("");
    setSourceTables("");
    setSchemaText("");
    setFilePath("");
    setFileGlob("*");
    setFileFormat("parquet");
    setRegistryUrl("");
    setTopic("");
    setApiSpecText("");
    setApiEndpoint("");
    setObjectNames("");
    setSampleSize("100");
    setManualFields([{ name: "", type: "STRING", nullable: true, description: "" }]);
    setWriteMode("append");
    setTableFormatHint("");
    setPartitionColumns("");
    setSampleFileName("");
    setSampleInferring(false);
    setSampleResult(null);
    setSampleError(null);
    setSaveError(null);

    startTransition(async () => {
      if (connectorDefinitionId) {
        try {
          const data = await api.get<DefinitionMethod[]>(
            `/api/v1/connectors/${connectorDefinitionId}/definition-methods`
          );
          setMethods(data);
          const defaultMethod = data.find((m) => m.isDefault);
          if (defaultMethod) setSelectedMethod(defaultMethod.type);
        } catch {
          setMethods([]);
        }
      } else {
        const fallbackMethods: DefinitionMethod[] = [
          { type: "SAMPLE_UPLOAD", label: "Upload Sample File", description: "Upload a CSV or JSON sample. PULSE parses the content, infers a typed schema, previews rows, and flags PII/confidential columns automatically.", isDefault: true },
          { type: "SCHEMA_UPLOAD", label: "Upload Schema", description: "Upload a JSON Schema, CSV sample, or Avro schema file", isDefault: false },
          { type: "MANUAL_DEFINITION", label: "Manual Definition", description: "Define columns manually", isDefault: false },
        ];
        setMethods(fallbackMethods);
        setSelectedMethod("SAMPLE_UPLOAD");
      }
    });
  }, [open, connectorDefinitionId]);

  const runSampleInference = (file: File) => {
    const format = sampleFormatFromFilename(file.name);
    if (!format) {
      setSampleError("Unsupported file type. Upload a .csv, .tsv, or .json sample.");
      return;
    }
    setSampleFileName(file.name);
    setSampleError(null);
    setSampleResult(null);
    setSampleInferring(true);
    const reader = new FileReader();
    reader.onload = async () => {
      try {
        const sampleData = reader.result as string;
        const result = await api.post<SampleInferResult>(
          `/api/v1/tenants/${currentTenant!.id}/datasets/infer-sample`,
          { sampleData, format }
        );
        setSampleResult(result);
        if (result.classification) {
          setClassification(result.classification);
        }
      } catch (err) {
        setSampleError(err instanceof Error ? err.message : "Failed to infer schema from sample");
      } finally {
        setSampleInferring(false);
      }
    };
    reader.onerror = () => {
      setSampleInferring(false);
      setSampleError("Could not read the selected file.");
    };
    reader.readAsText(file);
  };

  const handleSave = async () => {
    if (!selectedMethod || !name.trim()) return;
    setSaving(true);
    setSaveError(null);
    try {
      const body: Record<string, unknown> = {
        name: name.trim(),
        description: description.trim() || undefined,
        definitionType: selectedMethod,
        classification,
      };

      switch (selectedMethod) {
        case "CUSTOM_SQL":
          body.customSql = customSql;
          break;
        case "TABLE_SELECTION":
          body.sourceTables = sourceTables.split(",").map((s) => s.trim()).filter(Boolean);
          break;
        case "SCHEMA_UPLOAD":
          body.schemaFormat = schemaFormat;
          try {
            body.schemaSnapshot = JSON.parse(schemaText);
          } catch {
            body.schemaSnapshot = { raw: schemaText };
          }
          break;
        case "FILE_INFERENCE":
          body.definitionConfig = { path: filePath, glob: fileGlob, format: fileFormat };
          break;
        case "SCHEMA_REGISTRY":
          body.definitionConfig = { registryUrl, topic, subjectName: `${topic}-value` };
          break;
        case "API_SPEC_IMPORT":
          try {
            body.apiSpec = JSON.parse(apiSpecText);
          } catch {
            body.apiSpec = { raw: apiSpecText };
          }
          body.definitionConfig = { endpoint: apiEndpoint, method: "GET" };
          break;
        case "OBJECT_SELECTION":
          body.definitionConfig = { objects: objectNames.split(",").map((s) => s.trim()).filter(Boolean) };
          break;
        case "SAMPLE_INFERENCE":
          body.definitionConfig = { sampleSize: parseInt(sampleSize) || 100 };
          break;
        case "SAMPLE_UPLOAD":
          if (sampleResult) {
            body.schemaSnapshot = { fields: sampleResult.fields };
            body.discoveryMethod = sampleResult.discoveryMethod;
            body.discoveryProof = sampleResult.discoveryProof;
            body.definitionConfig = { sampleFileName };
          }
          break;
        case "MANUAL_DEFINITION":
          body.schemaSnapshot = {
            fields: manualFields.filter((f) => f.name.trim()).map((f) => ({
              name: f.name.trim(),
              type: f.type,
              nullable: f.nullable,
              description: f.description.trim() || undefined,
            })),
          };
          break;
      }

      // PKT-0023: Physical design fields
      if (writeMode && writeMode !== "append") body.writeMode = writeMode;
      if (tableFormatHint) body.tableFormatHint = tableFormatHint;
      if (partitionColumns.trim()) {
        body.partitionStrategy = {
          columns: partitionColumns.split(",").map((c) => ({
            name: c.trim(),
            transform: "identity",
          })).filter((c) => c.name),
        };
      }

      const url = connectorInstanceId
        ? `/api/v1/connector-instances/${connectorInstanceId}/datasets`
        : `/api/v1/tenants/${currentTenant!.id}/sors/${sorId}/datasets`;
      const dataset = await api.post<Dataset>(url, body);
      toast.success("Dataset created");
      onCreated(dataset);
      onOpenChange(false);
    } catch (err) {
      // LCT-019: surface duplicate-name (409) and other failures inline
      // instead of letting an opaque error bubble to a Next overlay.
      const message = err instanceof Error ? err.message : "Failed to create dataset";
      setSaveError(message);
      toast.error(message);
    } finally {
      setSaving(false);
    }
  };

  const addManualField = () => {
    setManualFields([...manualFields, { name: "", type: "STRING", nullable: true, description: "" }]);
  };

  const removeManualField = (idx: number) => {
    setManualFields(manualFields.filter((_, i) => i !== idx));
  };

  const updateManualField = (idx: number, field: Partial<typeof manualFields[0]>) => {
    setManualFields(manualFields.map((f, i) => (i === idx ? { ...f, ...field } : f)));
  };

  const canProceedToReview = () => {
    if (!name.trim()) return false;
    switch (selectedMethod) {
      case "CUSTOM_SQL": return customSql.trim().length > 0;
      case "TABLE_SELECTION": return sourceTables.trim().length > 0;
      case "SCHEMA_UPLOAD": return schemaText.trim().length > 0;
      case "FILE_INFERENCE": return filePath.trim().length > 0;
      case "SCHEMA_REGISTRY": return registryUrl.trim().length > 0 && topic.trim().length > 0;
      case "API_SPEC_IMPORT": return apiSpecText.trim().length > 0;
      case "OBJECT_SELECTION": return objectNames.trim().length > 0;
      case "SAMPLE_INFERENCE": return true;
      case "SAMPLE_UPLOAD": return !!sampleResult && sampleResult.fields.length > 0;
      case "MANUAL_DEFINITION": return manualFields.some((f) => f.name.trim());
      default: return false;
    }
  };

  const FIELD_TYPES = ["STRING", "INTEGER", "LONG", "DOUBLE", "DECIMAL", "BOOLEAN", "DATE", "TIMESTAMP", "BINARY", "ARRAY", "MAP", "STRUCT"];

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl max-h-[85vh] flex flex-col">
        <DialogHeader>
          <DialogTitle>Define Dataset</DialogTitle>
          <DialogDescription>
            {step === "method" && "Choose how to define the dataset schema"}
            {step === "define" && `Configure ${methods.find((m) => m.type === selectedMethod)?.label || selectedMethod}`}
            {step === "review" && "Review and save"}
          </DialogDescription>
        </DialogHeader>

        <div className="flex-1 overflow-y-auto space-y-4 min-h-0 max-h-[55vh] px-1">
          {/* Step 1: Pick method */}
          {step === "method" && (
            <div className="space-y-2">
              {methods.map((m) => (
                <div
                  key={m.type}
                  className={`rounded-md border p-3 cursor-pointer transition-colors ${
                    selectedMethod === m.type ? "border-primary bg-primary/5" : "hover:bg-muted/50"
                  }`}
                  onClick={() => setSelectedMethod(m.type)}
                >
                  <div className="flex items-center justify-between">
                    <span className="text-sm font-medium">{m.label}</span>
                    <div className="flex gap-1">
                      {m.isDefault && <Badge variant="secondary" className="text-[10px]">Recommended</Badge>}
                      {selectedMethod === m.type && <Badge className="text-[10px]">Selected</Badge>}
                    </div>
                  </div>
                  <p className="text-xs text-muted-foreground mt-1">{m.description}</p>
                </div>
              ))}
            </div>
          )}

          {/* Step 2: Method-specific form */}
          {step === "define" && (
            <div className="space-y-4">
              <div className="grid grid-cols-2 gap-3">
                <div className="space-y-1.5">
                  <Label className="text-xs">Dataset Name *</Label>
                  <Input value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g. active_loans" className="h-8 text-sm" />
                </div>
                <div className="space-y-1.5">
                  <Label className="text-xs">Classification</Label>
                  <Select value={classification} onValueChange={(v) => setClassification(v as DataClassification)}>
                    <SelectTrigger className="h-8 text-sm"><SelectValue /></SelectTrigger>
                    <SelectContent>
                      <SelectItem value="PUBLIC">Public</SelectItem>
                      <SelectItem value="INTERNAL">Internal</SelectItem>
                      <SelectItem value="CONFIDENTIAL">Confidential</SelectItem>
                      <SelectItem value="PII">PII</SelectItem>
                    </SelectContent>
                  </Select>
                </div>
              </div>
              <div className="space-y-1.5">
                <Label className="text-xs">Description</Label>
                <Input value={description} onChange={(e) => setDescription(e.target.value)} placeholder="What does this dataset contain?" className="h-8 text-sm" />
              </div>

              {/* CUSTOM_SQL */}
              {selectedMethod === "CUSTOM_SQL" && (
                <div className="space-y-1.5">
                  <Label className="text-xs">SQL Query *</Label>
                  <Textarea
                    value={customSql}
                    onChange={(e) => setCustomSql(e.target.value)}
                    placeholder="SELECT col1, col2 FROM schema.table WHERE ..."
                    className="font-mono text-xs min-h-[120px]"
                  />
                </div>
              )}

              {/* TABLE_SELECTION */}
              {selectedMethod === "TABLE_SELECTION" && (
                <div className="space-y-1.5">
                  <Label className="text-xs">Tables * (comma-separated schema.table)</Label>
                  <Textarea
                    value={sourceTables}
                    onChange={(e) => setSourceTables(e.target.value)}
                    placeholder="LOAN_SCHEMA.LOANS, LOAN_SCHEMA.BORROWERS"
                    className="font-mono text-xs min-h-[80px]"
                  />
                </div>
              )}

              {/* SCHEMA_UPLOAD */}
              {selectedMethod === "SCHEMA_UPLOAD" && (
                <>
                  <div className="space-y-1.5">
                    <Label className="text-xs">Schema Format</Label>
                    <Select value={schemaFormat} onValueChange={setSchemaFormat}>
                      <SelectTrigger className="h-8 text-sm"><SelectValue /></SelectTrigger>
                      <SelectContent>
                        <SelectItem value="JSON_SCHEMA">JSON Schema</SelectItem>
                        <SelectItem value="AVRO">Avro (.avsc)</SelectItem>
                        <SelectItem value="PARQUET">Parquet Schema</SelectItem>
                        <SelectItem value="CSV_SAMPLE">CSV Sample File</SelectItem>
                      </SelectContent>
                    </Select>
                  </div>
                  <div className="space-y-1.5">
                    <Label className="text-xs">
                      {schemaFormat === "CSV_SAMPLE" ? "Upload a sample file *" : "Schema Definition *"}
                    </Label>
                    <div className="space-y-2">
                      <div className="flex items-center gap-2">
                        <Button
                          type="button"
                          variant="outline"
                          size="sm"
                          className="h-8 text-xs"
                          onClick={() => {
                            const input = document.createElement("input");
                            input.type = "file";
                            input.accept = schemaFormat === "CSV_SAMPLE"
                              ? ".csv,.tsv,.txt"
                              : ".json,.avsc,.avro,.schema,.txt";
                            input.onchange = (e) => {
                              const file = (e.target as HTMLInputElement).files?.[0];
                              if (!file) return;
                              const reader = new FileReader();
                              reader.onload = () => {
                                const text = reader.result as string;
                                if (schemaFormat === "CSV_SAMPLE") {
                                  const lines = text.split("\n").filter((l) => l.trim());
                                  const headers = lines[0]?.split(",").map((h) => h.trim().replace(/^"|"$/g, ""));
                                  if (headers) {
                                    const fields = headers.map((h) => ({ name: h, type: "STRING", nullable: true }));
                                    setSchemaText(JSON.stringify({ fields }, null, 2));
                                  }
                                } else {
                                  setSchemaText(text);
                                }
                              };
                              reader.readAsText(file);
                            };
                            input.click();
                          }}
                        >
                          📎 Upload File
                        </Button>
                        {schemaText && (
                          <span className="text-xs text-muted-foreground">
                            {schemaFormat === "CSV_SAMPLE" ? "Schema inferred from headers" : "File loaded"}
                          </span>
                        )}
                      </div>
                      <Textarea
                        value={schemaText}
                        onChange={(e) => setSchemaText(e.target.value)}
                        placeholder={
                          schemaFormat === "CSV_SAMPLE"
                            ? "Upload a CSV file above or paste headers here..."
                            : '{"fields": [{"name": "id", "type": "integer"}, ...]}'
                        }
                        className="font-mono text-xs min-h-[150px]"
                      />
                    </div>
                  </div>
                </>
              )}

              {/* FILE_INFERENCE */}
              {selectedMethod === "FILE_INFERENCE" && (
                <div className="space-y-3">
                  <div className="space-y-1.5">
                    <Label className="text-xs">Upload a sample file to infer schema, or provide a remote path</Label>
                    <div className="flex items-center gap-2">
                      <Button
                        type="button"
                        variant="outline"
                        size="sm"
                        className="h-8 text-xs"
                        onClick={() => {
                          const input = document.createElement("input");
                          input.type = "file";
                          input.accept = ".csv,.tsv,.json,.parquet,.avro,.orc,.txt";
                          input.onchange = (e) => {
                            const file = (e.target as HTMLInputElement).files?.[0];
                            if (!file) return;
                            setFilePath(file.name);
                            const ext = file.name.split(".").pop()?.toLowerCase() || "";
                            if (["csv", "tsv", "txt"].includes(ext)) setFileFormat("csv");
                            else if (ext === "json") setFileFormat("json");
                            else if (ext === "parquet") setFileFormat("parquet");
                            else if (ext === "avro") setFileFormat("avro");
                            else if (ext === "orc") setFileFormat("orc");
                          };
                          input.click();
                        }}
                      >
                        📎 Upload Sample File
                      </Button>
                      {filePath && <span className="text-xs text-muted-foreground truncate">{filePath}</span>}
                    </div>
                  </div>
                  <div className="grid grid-cols-3 gap-3">
                    <div className="col-span-2 space-y-1.5">
                      <Label className="text-xs">File Path *</Label>
                      <Input value={filePath} onChange={(e) => setFilePath(e.target.value)} placeholder="s3://bucket/data/loans/" className="h-8 text-sm font-mono" />
                    </div>
                    <div className="space-y-1.5">
                      <Label className="text-xs">Format</Label>
                      <Select value={fileFormat} onValueChange={setFileFormat}>
                        <SelectTrigger className="h-8 text-sm"><SelectValue /></SelectTrigger>
                        <SelectContent>
                          <SelectItem value="parquet">Parquet</SelectItem>
                          <SelectItem value="csv">CSV</SelectItem>
                          <SelectItem value="json">JSON</SelectItem>
                          <SelectItem value="avro">Avro</SelectItem>
                          <SelectItem value="orc">ORC</SelectItem>
                        </SelectContent>
                      </Select>
                    </div>
                    <div className="space-y-1.5">
                      <Label className="text-xs">Glob Pattern</Label>
                      <Input value={fileGlob} onChange={(e) => setFileGlob(e.target.value)} placeholder="*.parquet" className="h-8 text-sm font-mono" />
                    </div>
                  </div>
                </div>
              )}

              {/* SAMPLE_UPLOAD (LCT-017/018): first-class deterministic sample ingestion */}
              {selectedMethod === "SAMPLE_UPLOAD" && (
                <div className="space-y-3">
                  <div className="space-y-1.5">
                    <Label className="text-xs">Sample File * (CSV, TSV, or JSON)</Label>
                    <div className="flex items-center gap-2">
                      <Button
                        type="button"
                        variant="outline"
                        size="sm"
                        className="h-8 text-xs"
                        disabled={sampleInferring}
                        onClick={() => {
                          const input = document.createElement("input");
                          input.type = "file";
                          input.accept = ".csv,.tsv,.json,.txt";
                          input.onchange = (e) => {
                            const file = (e.target as HTMLInputElement).files?.[0];
                            if (file) runSampleInference(file);
                          };
                          input.click();
                        }}
                      >
                        📎 {sampleFileName ? "Replace Sample File" : "Upload Sample File"}
                      </Button>
                      {sampleFileName && (
                        <span className="text-xs text-muted-foreground truncate">{sampleFileName}</span>
                      )}
                    </div>
                    <p className="text-[11px] text-muted-foreground">
                      PULSE parses the file on the server, infers a typed schema, previews rows, and
                      flags PII/confidential columns. Inference is deterministic (no AI).
                    </p>
                  </div>

                  {sampleInferring && (
                    <p className="text-xs text-muted-foreground">Inferring schema from sample…</p>
                  )}

                  {sampleError && (
                    <div className="rounded-md border border-destructive/50 bg-destructive/5 p-2 text-xs text-destructive">
                      {sampleError}
                    </div>
                  )}

                  {sampleResult && !sampleInferring && (
                    <div className="space-y-3">
                      <div className="flex items-center gap-2">
                        <Badge variant="secondary" className="text-[10px]">
                          {sampleResult.fields.length} columns
                        </Badge>
                        <Badge variant="outline" className="text-[10px]">
                          Classification: {sampleResult.classification}
                        </Badge>
                      </div>

                      <div className="rounded-md border">
                        <p className="text-[11px] font-medium text-muted-foreground px-3 py-1.5 border-b">
                          Inferred Schema
                        </p>
                        <div className="max-h-44 overflow-y-auto divide-y">
                          {sampleResult.fields.map((f) => (
                            <div key={f.name} className="flex items-center justify-between px-3 py-1 text-xs">
                              <span className="font-mono">{f.name}</span>
                              <div className="flex items-center gap-1.5">
                                {f.pii && <Badge className="text-[9px] bg-red-600 hover:bg-red-600">PII</Badge>}
                                {!f.pii && f.classification === "CONFIDENTIAL" && (
                                  <Badge variant="outline" className="text-[9px]">CONFIDENTIAL</Badge>
                                )}
                                <span className="text-muted-foreground">{f.type}</span>
                                <span className="text-[10px] text-muted-foreground">
                                  {f.nullable ? "nullable" : "required"}
                                </span>
                              </div>
                            </div>
                          ))}
                        </div>
                      </div>

                      {sampleResult.previewRows.length > 0 && (
                        <div className="rounded-md border">
                          <p className="text-[11px] font-medium text-muted-foreground px-3 py-1.5 border-b">
                            Data Preview ({sampleResult.previewRows.length} rows)
                          </p>
                          <div className="overflow-x-auto max-h-44">
                            <table className="text-[11px] w-full">
                              <thead>
                                <tr className="border-b bg-muted/50">
                                  {sampleResult.fields.map((f) => (
                                    <th key={f.name} className="text-left font-medium px-2 py-1 whitespace-nowrap font-mono">
                                      {f.name}
                                    </th>
                                  ))}
                                </tr>
                              </thead>
                              <tbody>
                                {sampleResult.previewRows.map((row, ri) => (
                                  <tr key={ri} className="border-b last:border-0">
                                    {sampleResult.fields.map((f) => (
                                      <td key={f.name} className="px-2 py-1 whitespace-nowrap font-mono text-muted-foreground">
                                        {String(row[f.name] ?? "")}
                                      </td>
                                    ))}
                                  </tr>
                                ))}
                              </tbody>
                            </table>
                          </div>
                        </div>
                      )}
                    </div>
                  )}
                </div>
              )}

              {/* SCHEMA_REGISTRY */}
              {selectedMethod === "SCHEMA_REGISTRY" && (
                <div className="space-y-3">
                  <div className="space-y-1.5">
                    <Label className="text-xs">Schema Registry URL *</Label>
                    <Input value={registryUrl} onChange={(e) => setRegistryUrl(e.target.value)} placeholder="http://schema-registry:8081" className="h-8 text-sm font-mono" />
                  </div>
                  <div className="space-y-1.5">
                    <Label className="text-xs">Topic *</Label>
                    <Input value={topic} onChange={(e) => setTopic(e.target.value)} placeholder="loan-payment-events" className="h-8 text-sm font-mono" />
                  </div>
                </div>
              )}

              {/* API_SPEC_IMPORT */}
              {selectedMethod === "API_SPEC_IMPORT" && (
                <>
                  <div className="space-y-1.5">
                    <Label className="text-xs">OpenAPI / Swagger Spec (JSON) *</Label>
                    <Textarea
                      value={apiSpecText}
                      onChange={(e) => setApiSpecText(e.target.value)}
                      placeholder='{"openapi": "3.0.0", "paths": { ... }}'
                      className="font-mono text-xs min-h-[120px]"
                    />
                  </div>
                  <div className="space-y-1.5">
                    <Label className="text-xs">Endpoint Path</Label>
                    <Input value={apiEndpoint} onChange={(e) => setApiEndpoint(e.target.value)} placeholder="/api/v1/loans" className="h-8 text-sm font-mono" />
                  </div>
                </>
              )}

              {/* OBJECT_SELECTION */}
              {selectedMethod === "OBJECT_SELECTION" && (
                <div className="space-y-1.5">
                  <Label className="text-xs">Object / Collection Names * (comma-separated)</Label>
                  <Input value={objectNames} onChange={(e) => setObjectNames(e.target.value)} placeholder="Account, Opportunity, Contact" className="h-8 text-sm" />
                </div>
              )}

              {/* SAMPLE_INFERENCE */}
              {selectedMethod === "SAMPLE_INFERENCE" && (
                <div className="space-y-1.5">
                  <Label className="text-xs">Sample Size (records)</Label>
                  <Input type="number" value={sampleSize} onChange={(e) => setSampleSize(e.target.value)} className="h-8 text-sm w-32" />
                  <p className="text-[11px] text-muted-foreground">Schema will be inferred from sampled records at connection time.</p>
                </div>
              )}

              {/* MANUAL_DEFINITION */}
              {selectedMethod === "MANUAL_DEFINITION" && (
                <div className="space-y-2">
                  <div className="grid grid-cols-[1fr_100px_60px_1fr_32px] gap-1.5 text-[10px] font-medium text-muted-foreground uppercase">
                    <span>Name</span><span>Type</span><span>Null?</span><span>Description</span><span />
                  </div>
                  {manualFields.map((field, idx) => (
                    <div key={idx} className="grid grid-cols-[1fr_100px_60px_1fr_32px] gap-1.5 items-center">
                      <Input
                        value={field.name}
                        onChange={(e) => updateManualField(idx, { name: e.target.value })}
                        placeholder="field_name"
                        className="h-7 text-xs font-mono"
                      />
                      <Select value={field.type} onValueChange={(v) => updateManualField(idx, { type: v })}>
                        <SelectTrigger className="h-7 text-xs"><SelectValue /></SelectTrigger>
                        <SelectContent>
                          {FIELD_TYPES.map((t) => (
                            <SelectItem key={t} value={t}>{t}</SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                      <input
                        type="checkbox"
                        checked={field.nullable}
                        onChange={(e) => updateManualField(idx, { nullable: e.target.checked })}
                        className="w-4 h-4 mx-auto"
                      />
                      <Input
                        value={field.description}
                        onChange={(e) => updateManualField(idx, { description: e.target.value })}
                        placeholder="optional"
                        className="h-7 text-xs"
                      />
                      <Button variant="ghost" size="sm" className="h-7 w-7 p-0 text-muted-foreground" onClick={() => removeManualField(idx)}>
                        x
                      </Button>
                    </div>
                  ))}
                  <Button variant="outline" size="sm" className="h-7 text-xs" onClick={addManualField}>
                    + Add Field
                  </Button>
                </div>
              )}

              {/* PKT-0023: Physical Design (optional, collapsed) */}
              <details className="border rounded-md p-3 mt-4">
                <summary className="text-sm font-medium cursor-pointer">
                  Physical Design (optional)
                </summary>
                <div className="space-y-3 mt-3">
                  <div>
                    <Label className="text-xs">Write Mode</Label>
                    <Select value={writeMode} onValueChange={setWriteMode}>
                      <SelectTrigger className="h-8 text-xs">
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="append">Append</SelectItem>
                        <SelectItem value="overwrite">Overwrite</SelectItem>
                        <SelectItem value="merge">Merge</SelectItem>
                      </SelectContent>
                    </Select>
                  </div>
                  <div>
                    <Label className="text-xs">Table Format Hint</Label>
                    <Select value={tableFormatHint || "none"} onValueChange={(v) => setTableFormatHint(v === "none" ? "" : v)}>
                      <SelectTrigger className="h-8 text-xs">
                        <SelectValue placeholder="Auto (persona default)" />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="none">Auto (persona default)</SelectItem>
                        <SelectItem value="PARQUET">Parquet</SelectItem>
                        <SelectItem value="ICEBERG">Iceberg</SelectItem>
                        <SelectItem value="DELTA">Delta</SelectItem>
                      </SelectContent>
                    </Select>
                  </div>
                  <div>
                    <Label className="text-xs">Partition Columns</Label>
                    <Input
                      value={partitionColumns}
                      onChange={(e) => setPartitionColumns(e.target.value)}
                      placeholder="e.g. ingest_date, region"
                      className="h-8 text-xs"
                    />
                    <p className="text-[10px] text-muted-foreground mt-0.5">
                      Comma-separated column names for partitioning
                    </p>
                  </div>
                </div>
              </details>
            </div>
          )}

          {/* Step 3: Review */}
          {step === "review" && (
            <div className="space-y-3">
              <div className="rounded-md border p-3 space-y-2 text-sm">
                <div className="flex justify-between">
                  <span className="text-muted-foreground">Name</span>
                  <span className="font-medium">{name}</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-muted-foreground">Method</span>
                  <Badge variant="outline" className="text-[10px]">
                    {methods.find((m) => m.type === selectedMethod)?.label || selectedMethod}
                  </Badge>
                </div>
                <div className="flex justify-between">
                  <span className="text-muted-foreground">Classification</span>
                  <Badge variant="secondary" className="text-[10px]">{classification}</Badge>
                </div>
                {description && (
                  <div className="flex justify-between">
                    <span className="text-muted-foreground">Description</span>
                    <span className="text-right max-w-[60%]">{description}</span>
                  </div>
                )}
                {writeMode !== "append" && (
                  <div className="flex justify-between">
                    <span className="text-muted-foreground">Write Mode</span>
                    <Badge variant="outline" className="text-[10px]">{writeMode}</Badge>
                  </div>
                )}
                {tableFormatHint && (
                  <div className="flex justify-between">
                    <span className="text-muted-foreground">Table Format</span>
                    <Badge variant="outline" className="text-[10px]">{tableFormatHint}</Badge>
                  </div>
                )}
                {partitionColumns.trim() && (
                  <div className="flex justify-between">
                    <span className="text-muted-foreground">Partition Columns</span>
                    <span className="text-xs font-mono">{partitionColumns}</span>
                  </div>
                )}
              </div>
              {selectedMethod === "CUSTOM_SQL" && (
                <div className="rounded-md border p-3">
                  <p className="text-xs text-muted-foreground mb-1">SQL Query</p>
                  <pre className="text-xs font-mono bg-muted p-2 rounded overflow-x-auto">{customSql}</pre>
                </div>
              )}
              {selectedMethod === "TABLE_SELECTION" && (
                <div className="rounded-md border p-3">
                  <p className="text-xs text-muted-foreground mb-1">Source Tables</p>
                  <p className="text-xs font-mono">{sourceTables}</p>
                </div>
              )}
              {selectedMethod === "MANUAL_DEFINITION" && (
                <div className="rounded-md border p-3">
                  <p className="text-xs text-muted-foreground mb-1">Fields ({manualFields.filter((f) => f.name.trim()).length})</p>
                  <div className="space-y-0.5">
                    {manualFields.filter((f) => f.name.trim()).map((f, i) => (
                      <p key={i} className="text-xs font-mono">
                        {f.name}: {f.type} {f.nullable ? "(nullable)" : "(required)"}
                      </p>
                    ))}
                  </div>
                </div>
              )}
              {selectedMethod === "SAMPLE_UPLOAD" && sampleResult && (
                <div className="rounded-md border p-3 space-y-2">
                  <div className="flex items-center justify-between">
                    <p className="text-xs text-muted-foreground">
                      Inferred Schema ({sampleResult.fields.length})
                      {sampleFileName ? ` · ${sampleFileName}` : ""}
                    </p>
                    {sampleResult.fields.some((f) => f.pii) && (
                      <Badge className="text-[9px] bg-red-600 hover:bg-red-600">
                        {sampleResult.fields.filter((f) => f.pii).length} PII
                      </Badge>
                    )}
                  </div>
                  <div className="space-y-0.5 max-h-40 overflow-y-auto">
                    {sampleResult.fields.map((f) => (
                      <p key={f.name} className="text-xs font-mono flex items-center gap-1.5">
                        <span>{f.name}: {f.type} {f.nullable ? "(nullable)" : "(required)"}</span>
                        {f.pii && <span className="text-red-600 font-semibold">PII</span>}
                        {!f.pii && f.classification === "CONFIDENTIAL" && (
                          <span className="text-amber-600">CONFIDENTIAL</span>
                        )}
                      </p>
                    ))}
                  </div>
                </div>
              )}
              {saveError && (
                <div className="rounded-md border border-destructive/50 bg-destructive/5 p-3 text-xs text-destructive">
                  {saveError}
                </div>
              )}
            </div>
          )}
        </div>

        <DialogFooter>
          {step === "method" && (
            <>
              <Button variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
              <Button disabled={!selectedMethod} onClick={() => setStep("define")}>Next</Button>
            </>
          )}
          {step === "define" && (
            <>
              <Button variant="outline" onClick={() => setStep("method")}>Back</Button>
              <Button disabled={!canProceedToReview()} onClick={() => setStep("review")}>Review</Button>
            </>
          )}
          {step === "review" && (
            <>
              <Button variant="outline" onClick={() => setStep("define")}>Back</Button>
              <Button onClick={handleSave} disabled={saving}>
                {saving ? "Creating..." : "Create Dataset"}
              </Button>
            </>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
