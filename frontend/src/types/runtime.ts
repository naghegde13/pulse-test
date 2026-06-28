export type BindingKind = "GCP" | "DPC" | "LOCAL";
export type SettingsRole = "PRIMARY" | "DIAGNOSTIC";
export type BindingRecordState = "ACTIVE" | "INACTIVE";
export type BindingValidationStatus = "PENDING" | "VALIDATED" | "FAILED" | "DISABLED";

export interface RuntimeBindingDTO {
  id: string;
  tenantId: string;
  environment: string;
  bindingKind: BindingKind;
  settingsRole: SettingsRole;
  recordState: BindingRecordState;
  validationStatus: BindingValidationStatus;
  storageRootFiles: string | null;
  storageRootLake: string | null;
  storageRootOps: string | null;
  diagnosticReason: string | null;
  diagnosticDetails: Record<string, unknown> | null;
  validatedAt: string | null;
  validationError: string | null;
  createdAt: string;
  updatedAt: string;
}
