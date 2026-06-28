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
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { api } from "@/lib/api";
import type {
  ConnectorCredentialStrategy,
  ConnectorDefinition,
  CredentialProfile,
  CredentialStatus,
  CredentialUpsertRequest,
  DeployEnvironment,
} from "@/types";
import { DEPLOY_ENVIRONMENT_LABELS } from "@/types";
import { toast } from "sonner";

interface CredentialDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  connectorInstanceId: string;
  connectorDefinitionId: string;
  environment: DeployEnvironment;
  existingCredential?: CredentialProfile | null;
  onSaved: (credential: CredentialProfile) => void;
  /** If the connector inherits tenant GCP service account, suppress credential prompts. */
  credentialStrategy?: ConnectorCredentialStrategy;
}

// Long-form labels for the dialog title. Falls back to the canonical key
// for the local env so the dialog still renders if a future caller passes
// it in (today only the dev env is configurable from the dialog per
// PROJECT_CONTEXT.MD locked decision #5).
const ENV_LABELS: Record<DeployEnvironment, string> = {
  ...DEPLOY_ENVIRONMENT_LABELS,
  dev: "Development",
};

const STATUS_VARIANT: Record<CredentialStatus, "secondary" | "destructive" | "outline"> = {
  VALID: "secondary",
  UNTESTED: "outline",
  INVALID: "destructive",
  EXPIRED: "destructive",
  SKIPPED: "outline",
};

function isSecretReference(value: unknown): boolean {
  return typeof value === "string" &&
    (value.startsWith("vault://") || value.startsWith("gcp-sm://"));
}

function getStoredSecretState(
  credential: CredentialProfile | null | undefined,
  key: string
): "reference" | "value" | null {
  const metadata = credential?.secretMetadata?.[key];
  if (metadata?.configured) {
    return metadata.secretReference ? "reference" : "value";
  }

  const existingValue = credential?.connectionConfig?.[key];
  if (existingValue === undefined) {
    return null;
  }

  return isSecretReference(existingValue) ? "reference" : "value";
}

interface SpecProperty {
  type?: string;
  description?: string;
  default?: unknown;
  // PULSE-native annotations (V99). `airbyte_secret` is the legacy flag from
  // pre-V99 specs; both forms are read for transitional compatibility.
  pulse_role?: "credential" | "env_metadata" | "platform_resolved";
  secret?: boolean;
  airbyte_secret?: boolean;
  enum?: string[];
  items?: { type?: string };
}

function isSecretField(prop: SpecProperty): boolean {
  // Post-V99: pulse_role=credential + secret=true marks a secret-shaped credential.
  // Pre-V99: airbyte_secret was the only flag. Read both for safety.
  return prop.secret === true || prop.airbyte_secret === true;
}

function isCredentialField(prop: SpecProperty): boolean {
  // Pre-V99 specs had no pulse_role; everything in `properties` was a candidate
  // and airbyte_secret marked the secret subset. Post-V99 each property is
  // tagged credential / env_metadata / platform_resolved.
  if (prop.pulse_role) return prop.pulse_role === "credential";
  return true; // legacy fallback
}

type SecretInputMode = "value" | "reference";
type SecretDraft = { value: string; reference: string };

function getExistingMetadataValue(
  credential: CredentialProfile | null | undefined,
  key: string
): unknown {
  if (credential?.metadataConfig?.[key] !== undefined) {
    return credential.metadataConfig[key];
  }
  return credential?.connectionConfig?.[key];
}

function getExistingSecretReference(
  credential: CredentialProfile | null | undefined,
  key: string
): string {
  const explicitRef = credential?.secretRefs?.[key];
  if (explicitRef) {
    return explicitRef;
  }
  const fallbackValue = credential?.connectionConfig?.[key];
  return isSecretReference(fallbackValue) ? String(fallbackValue) : "";
}

export function CredentialDialog({
  open,
  onOpenChange,
  connectorInstanceId,
  connectorDefinitionId,
  environment,
  existingCredential,
  onSaved,
  credentialStrategy,
}: CredentialDialogProps) {
  const isInheritedStrategy = credentialStrategy === "INHERIT_TENANT_GCP_SERVICE_ACCOUNT";
  const [spec, setSpec] = useState<{
    required?: string[];
    properties?: Record<string, SpecProperty>;
  } | null>(null);
  const [metadataFormData, setMetadataFormData] = useState<Record<string, unknown>>({});
  const [secretDrafts, setSecretDrafts] = useState<Record<string, SecretDraft>>({});
  const [secretModes, setSecretModes] = useState<Record<string, SecretInputMode>>({});
  const [saving, setSaving] = useState(false);
  const [skipping, setSkipping] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [, startTransition] = useTransition();

  // Fetch connectionSpec from connector definition
  useEffect(() => {
    if (!open) return;
    setError(null);
    startTransition(async () => {
      try {
        const def = await api.get<ConnectorDefinition>(
          `/api/v1/connectors/${connectorDefinitionId}`
        );
        const connSpec = def.connectionSpec as {
          required?: string[];
          properties?: Record<string, SpecProperty>;
        };
        setSpec(connSpec);

        // Pre-fill from existing credential or defaults
        const initialMetadata: Record<string, unknown> = {};
        const initialSecretDrafts: Record<string, SecretDraft> = {};
        const initialModes: Record<string, SecretInputMode> = {};
        if (connSpec.properties) {
          for (const [key, prop] of Object.entries(connSpec.properties)) {
            if (isSecretField(prop)) {
              const storedSecretState = getStoredSecretState(existingCredential, key);
              const existingRef = getExistingSecretReference(existingCredential, key);
              initialModes[key] = storedSecretState === "reference" ? "reference" : "value";
              initialSecretDrafts[key] = {
                value: "",
                reference: storedSecretState === "reference" ? existingRef : "",
              };
            } else if (getExistingMetadataValue(existingCredential, key) !== undefined) {
              initialMetadata[key] = getExistingMetadataValue(existingCredential, key);
            } else if (prop.default !== undefined) {
              initialMetadata[key] = prop.default;
            } else {
              initialMetadata[key] = "";
            }
          }
        }
        setMetadataFormData(initialMetadata);
        setSecretDrafts(initialSecretDrafts);
        setSecretModes(initialModes);
      } catch {
        setError("Failed to load connector specification");
      }
    });
  }, [open, connectorDefinitionId, existingCredential]);

  const handleMetadataFieldChange = (key: string, value: unknown) => {
    setMetadataFormData((prev) => ({ ...prev, [key]: value }));
  };

  const handleSecretFieldChange = (key: string, value: string) => {
    const mode = secretModes[key] ?? "value";
    setSecretDrafts((prev) => ({
      ...prev,
      [key]: {
        value: prev[key]?.value ?? "",
        reference: prev[key]?.reference ?? "",
        [mode]: value,
      },
    }));
  };

  const handleSecretModeChange = (key: string, mode: SecretInputMode) => {
    setSecretModes((prev) => ({ ...prev, [key]: mode }));
    setSecretDrafts((prev) => ({
      ...prev,
      [key]: {
        value: prev[key]?.value ?? "",
        reference: prev[key]?.reference || getExistingSecretReference(existingCredential, key),
      },
    }));
  };

  const handleSkip = async () => {
    setSkipping(true);
    setError(null);
    try {
      const saved = await api.post<CredentialProfile>(
        `/api/v1/connector-instances/${connectorInstanceId}/credentials/${environment}/skip`,
        {}
      );
      toast.success("Credentials marked as skipped");
      onSaved(saved);
      onOpenChange(false);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to skip credentials");
    } finally {
      setSkipping(false);
    }
  };

  const handleSave = async () => {
    setSaving(true);
    setError(null);
    try {
      const metadata: Record<string, unknown> = {};
      const secretRefs: Record<string, string> = {};
      const secretValues: Record<string, string> = {};

      for (const [key, value] of Object.entries(metadataFormData)) {
        if (value !== "" && value !== undefined) {
          metadata[key] = value;
        }
      }

      for (const [key, drafts] of Object.entries(secretDrafts)) {
        const selectedValue = secretModes[key] === "reference" ? drafts.reference : drafts.value;
        if (!selectedValue.trim()) {
          continue;
        }

        if (secretModes[key] === "reference") {
          secretRefs[key] = selectedValue.trim();
        } else {
          secretValues[key] = selectedValue;
        }
      }

      const payload: CredentialUpsertRequest = { metadata, secretRefs, secretValues };
      const saved = await api.put<CredentialProfile>(
        `/api/v1/connector-instances/${connectorInstanceId}/credentials/${environment}`,
        payload
      );
      toast.success("Credentials saved");
      onSaved(saved);
      onOpenChange(false);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to save credentials");
    } finally {
      setSaving(false);
    }
  };

  const isRequired = (key: string) =>
    Array.isArray(spec?.required) ? spec.required.includes(key) : false;

  const renderField = (key: string, prop: SpecProperty) => {
    const value = metadataFormData[key];
    const required = isRequired(key);
    const label = key.replace(/_/g, " ").replace(/\b\w/g, (c) => c.toUpperCase());

    // Boolean field
    if (prop.type === "boolean") {
      return (
        <div key={key} className="flex items-center gap-2">
          <input
            type="checkbox"
            id={`cred-${key}`}
            checked={value === true}
            onChange={(e) => handleMetadataFieldChange(key, e.target.checked)}
            className="w-4 h-4"
          />
          <Label htmlFor={`cred-${key}`} className="text-xs">
            {label}
            {required && <span className="text-destructive ml-0.5">*</span>}
          </Label>
          {prop.description && (
            <span className="text-[10px] text-muted-foreground">{prop.description}</span>
          )}
        </div>
      );
    }

    // Enum field
    if (prop.enum && prop.enum.length > 0) {
      return (
        <div key={key} className="space-y-1.5">
          <Label htmlFor={`cred-${key}`} className="text-xs">
            {label}
            {required && <span className="text-destructive ml-0.5">*</span>}
          </Label>
          <Select
            value={String(value ?? "")}
            onValueChange={(v) => handleMetadataFieldChange(key, v)}
          >
            <SelectTrigger className="h-8 text-sm">
              <SelectValue placeholder={`Select ${label.toLowerCase()}`} />
            </SelectTrigger>
            <SelectContent>
              {prop.enum.map((opt) => (
                <SelectItem key={opt} value={opt}>
                  {opt}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          {prop.description && (
            <p className="text-[10px] text-muted-foreground">{prop.description}</p>
          )}
        </div>
      );
    }

    // Number / integer field
    if (prop.type === "integer" || prop.type === "number") {
      return (
        <div key={key} className="space-y-1.5">
          <Label htmlFor={`cred-${key}`} className="text-xs">
            {label}
            {required && <span className="text-destructive ml-0.5">*</span>}
          </Label>
          <Input
            id={`cred-${key}`}
            type="number"
            value={value !== undefined && value !== "" ? String(value) : ""}
            onChange={(e) =>
              handleMetadataFieldChange(key, e.target.value ? Number(e.target.value) : "")
            }
            placeholder={prop.default !== undefined ? `Default: ${prop.default}` : ""}
            className="h-8 text-sm"
          />
          {prop.description && (
            <p className="text-[10px] text-muted-foreground">{prop.description}</p>
          )}
        </div>
      );
    }

    // Secret field (password)
    if (isSecretField(prop)) {
      const storedSecretState = getStoredSecretState(existingCredential, key);
      const secretMode = secretModes[key] ?? (storedSecretState === "reference" ? "reference" : "value");
      const drafts = secretDrafts[key] ?? { value: "", reference: "" };
      const secretValue = secretMode === "reference" ? drafts.reference : drafts.value;
      return (
        <div key={key} className="space-y-1.5">
          <div className="flex items-center justify-between gap-2">
            <Label htmlFor={`cred-${key}`} className="text-xs">
              {label}
              {required && <span className="text-destructive ml-0.5">*</span>}
              <Badge variant="outline" className="text-[9px] ml-1.5 font-normal">
                secret
              </Badge>
            </Label>
            <Select
              value={secretMode}
              onValueChange={(mode) => handleSecretModeChange(key, mode as SecretInputMode)}
            >
              <SelectTrigger className="h-7 w-[132px] text-[11px]">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="value">Secret value</SelectItem>
                <SelectItem value="reference">Secret reference</SelectItem>
              </SelectContent>
            </Select>
          </div>
          <Input
            id={`cred-${key}`}
            type={secretMode === "reference" ? "text" : "password"}
            value={secretValue}
            onChange={(e) => handleSecretFieldChange(key, e.target.value)}
            placeholder={
              secretMode === "reference"
                ? storedSecretState === "reference"
                  ? "Stored secret reference"
                  : "Enter secret reference"
                : storedSecretState === "value"
                  ? "Stored secret value"
                  : `Enter ${label.toLowerCase()}`
            }
            className="h-8 text-sm"
          />
          <p className="text-[10px] text-muted-foreground">
            {prop.description}
            {prop.description ? " " : ""}
            {secretMode === "reference"
              ? (
                <>
                  Save a secret reference like <span className="font-mono">gcp-sm://...</span> or{" "}
                  <span className="font-mono">vault://...</span>. Switching modes keeps your draft. Leave blank to keep
                  {storedSecretState === "reference"
                    ? " the existing stored secret reference."
                    : storedSecretState === "value"
                      ? " the existing stored secret value."
                      : " the existing stored secret."}
                </>
              )
              : (
                <>
                  Enter a secret value without mixing it into the normal metadata fields. Switching modes keeps your draft. Leave blank to keep
                  {storedSecretState === "reference"
                    ? " the existing stored secret reference."
                    : storedSecretState === "value"
                      ? " the existing stored secret value."
                      : " the existing stored secret."}
                </>
              )}
          </p>
        </div>
      );
    }

    // Default: text string field
    return (
      <div key={key} className="space-y-1.5">
        <Label htmlFor={`cred-${key}`} className="text-xs">
          {label}
          {required && <span className="text-destructive ml-0.5">*</span>}
        </Label>
        <Input
          id={`cred-${key}`}
          type="text"
          value={String(value ?? "")}
          onChange={(e) => handleMetadataFieldChange(key, e.target.value)}
          placeholder={
            prop.default !== undefined
              ? `Default: ${prop.default}`
              : prop.description || ""
          }
          className="h-8 text-sm"
        />
        {prop.description && (
          <p className="text-[10px] text-muted-foreground">{prop.description}</p>
        )}
      </div>
    );
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-lg max-h-[85vh] flex flex-col">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            Credentials — {ENV_LABELS[environment]}
            {existingCredential && (
              <Badge
                variant={STATUS_VARIANT[existingCredential.status]}
                className="text-[10px]"
              >
                {existingCredential.status}
              </Badge>
            )}
          </DialogTitle>
          <DialogDescription>
            {existingCredential
              ? "Update connection credentials for this environment. Non-secret metadata stays separate from secret references and secret values."
              : "Configure connection credentials for this environment. Non-secret metadata stays separate from secret references and secret values."}
          </DialogDescription>
        </DialogHeader>

        <div className="flex-1 overflow-y-auto space-y-4 min-h-0 max-h-[55vh] px-1">
          {isInheritedStrategy && (
            <div className="rounded-md border bg-blue-500/5 border-blue-500/20 px-3 py-3 text-xs space-y-1.5">
              <p className="font-medium text-blue-700">
                Inherited Tenant Credential Strategy
              </p>
              <p className="text-blue-700/80">
                This connector inherits the tenant&apos;s GCP service account for authentication.
                No per-connector credentials are needed — authentication is managed by the
                tenant-level GCP service account configured in Settings.
              </p>
              <p className="text-blue-700/60 text-[10px]">
                Strategy: INHERIT_TENANT_GCP_SERVICE_ACCOUNT
              </p>
            </div>
          )}

          {error && (
            <div className="rounded-md border border-destructive/50 bg-destructive/5 p-3">
              <p className="text-xs text-destructive">{error}</p>
            </div>
          )}

          {!spec && !error && !isInheritedStrategy && (
            <div className="space-y-3">
              <div className="h-8 w-full bg-muted animate-pulse rounded" />
              <div className="h-8 w-full bg-muted animate-pulse rounded" />
              <div className="h-8 w-2/3 bg-muted animate-pulse rounded" />
            </div>
          )}

          {/* V99: Filter to credential + env_metadata fields only. Platform-resolved
              fields (e.g. on object-storage connectors) are not user-input and are
              skipped here entirely. Object-storage connectors typically have empty
              connection_specs so this dialog renders an empty body and the user can
              just close. Inherited-strategy connectors skip all fields. */}
          {!isInheritedStrategy && spec?.properties &&
            Object.entries(spec.properties)
              .filter(([, prop]) =>
                prop.pulse_role
                  ? prop.pulse_role !== "platform_resolved"
                  : true
              )
              .map(([key, prop]) => renderField(key, prop))}

          {spec?.properties &&
            Object.values(spec.properties).every(
              (prop) => prop.pulse_role === "platform_resolved"
            ) &&
            Object.keys(spec.properties).length === 0 && (
              <div className="rounded-md border bg-muted/30 px-3 py-3 text-xs text-muted-foreground">
                This connector is platform-resolved — bucket, path, and authentication
                all derive from the tenant&apos;s storage_backend at runtime. No user
                credentials needed. Close this dialog.
              </div>
            )}
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button
            variant="outline"
            onClick={handleSkip}
            disabled={skipping || saving}
          >
            {skipping ? "Skipping..." : "Skip for Now"}
          </Button>
          <Button onClick={handleSave} disabled={saving || skipping || !spec}>
            {saving ? "Saving..." : existingCredential ? "Update Credentials" : "Save Credentials"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
