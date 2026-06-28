/**
 * Resolved schema column. Optional fields are populated by SchemaPropagationService
 * (#88 Phase 1) for richer DAG rendering — they may be absent on legacy data
 * loaded before the backfill or on user-edited overrides.
 */
export interface SchemaColumn {
  name: string;
  type: string;
  nullable?: boolean;
  description?: string;
  /**
   * Where this column came from in the DAG:
   *   "source"            — present on the upstream dataset/instance
   *   "injected:audit"    — PULSE audit column added at ingestion
   *   "injected:cdc"      — CDC metadata column
   *   "injected:scd"      — dbt snapshot SCD2 column
   *   "passthrough"       — propagated through this transform unchanged
   *   "derived:expression"— computed by a derived_columns expression
   *   "masked:<strategy>" — value transformed by PIIMasking with the named strategy
   *   "joined:left|right" — produced by a join (right side prefixed on collision)
   *   "modeled"           — declared by a MODELING blueprint param schema
   */
  lineage?: string;
  /** Free-form tags for hover/badge rendering: audit, masked, pii, derived, partition_key, etc. */
  tags?: string[];
  /** Transform descriptor — kind + strategy/expression depending on the lineage. */
  transform?: { kind?: string; strategy?: string; expression?: string };
}

export type UserRole = "CITIZEN" | "DATA_ENGINEER" | "DEPLOYER" | "ADMIN";

export interface User {
  id: string;
  email: string;
  displayName: string;
  role: UserRole;
  tenantId: string;
  permissions: string[];
}

export interface Tenant {
  id: string;
  name: string;
  slug: string;
  repoUrl?: string;
  domains: string[];
}

export interface DomainScopedEntity {
  domainId?: string;
  domainName?: string;
}

export interface Domain {
  id: string;
  tenantId: string;
  name: string;
  description?: string;
  currentBusinessDate?: string;
  businessDateGrain?: string;
  businessDateTimezone?: string;
  businessDateConfig?: Record<string, unknown>;
  createdAt: string;
  updatedAt: string;
}

// PULSE-managed lifecycle stages only. PULSE is dev-only (PKT-FINAL-2 /
// BUG-2026-05-25-02): integration/UAT/production are owned by enterprise
// CI/CD after handoff and are NOT modelled here.
export type PipelineStage =
  | "ENGINEERING"
  | "DEV_DEPLOYED"
  | "DEV_VALIDATED"
  | "PUBLISHED";

export const PIPELINE_STAGES: PipelineStage[] = [
  "ENGINEERING",
  "DEV_DEPLOYED",
  "DEV_VALIDATED",
  "PUBLISHED",
];

export const STAGE_LABELS: Record<PipelineStage, string> = {
  ENGINEERING: "Engineering",
  DEV_DEPLOYED: "Dev Deployed",
  DEV_VALIDATED: "Dev Validated",
  PUBLISHED: "Published",
};

/**
 * Stages owned outside PULSE. Now empty: post-PUBLISHED state is rendered
 * as a single terminal "Handed off to enterprise CD" indicator instead of
 * enumerated UAT/Production segments.
 */
export const EXTERNAL_STAGES: Set<PipelineStage> = new Set<PipelineStage>();

export interface Pipeline extends DomainScopedEntity {
  id: string;
  tenantId: string;
  name: string;
  description?: string;
  createdBy: string;
  activeVersionId?: string;
  /**
   * ARCH-010: default storage backend applied to new instances when their
   * top-level `storageBackend` is omitted. One of `'DPC' | 'GCP'`. Derived
   * from runtime authority for normal pipeline creation.
   */
  defaultStorageBackend: "DPC" | "GCP";
  createdAt: string;
  updatedAt: string;
}

export interface PipelineVersion {
  id: string;
  pipelineId: string;
  revision: number;
  commitHash?: string;
  lifecycleStage: PipelineStage;
  createdBy: string;
  slaConfig?: Record<string, unknown>;
  metadata?: Record<string, unknown>;
  changeSummary?: string;
  dqReadinessScore?: number;
  scheduleCron?: string;
  catchupEnabled?: boolean;
  maxActiveRuns?: number;
  dependsOnPast?: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface SubPipelineInstance {
  id: string;
  pipelineId: string;
  versionId?: string;
  blueprintId: string;
  blueprintKey?: string;
  blueprintVersion: string;
  name: string;
  executionOrder: number;
  params: Record<string, unknown>;
  inputDatasets: Array<Record<string, unknown>>;
  outputDatasets: Array<Record<string, unknown>>;
  outputSchema?: { columns: SchemaColumn[] };
  /**
   * Schema-propagation status written by Agent E's SchemaPropagationService.
   * Values: "unknown" | "clean" | "dirty" | "conflict" | "pending". Null/undefined → "unknown".
   */
  schemaStatus?: string;
  /**
   * ARCH-010 canonical storage authority columns. Top-level fields are the
   * single source of truth; mirrored `params` keys are stripped server-side
   * on every write.
   */
  storageBackend: "DPC" | "GCP";
  lakeLayer?: "bronze" | "silver" | "gold" | null;
  lakeFormat?:
    | "delta"
    | "iceberg_external"
    | "iceberg_bq_managed"
    | "bq_native"
    | "parquet"
    | null;
  /** DQ expectations live as their own column, not inside `params`. */
  dqExpectations?: Array<Record<string, unknown>>;
  createdAt: string;
  updatedAt: string;
}

/**
 * Canonical add/update response (ARCH-010). Returned by
 * POST /api/v1/versions/:versionId/composition/instances and
 * PUT /api/v1/versions/:versionId/composition/instances/:instanceId.
 */
export interface SubPipelineInstanceResponse {
  instance: SubPipelineInstance;
  canonicalFields: {
    storageBackend: string;
    lakeLayer: string | null;
    lakeFormat: string | null;
  };
  /** Mirrored `params` keys that were stripped server-side. */
  deprecations: string[];
  /** Non-fatal advisory messages from BlueprintInstanceConfigurationService. */
  warnings: string[];
}

export interface PortWiring {
  id: string;
  versionId: string;
  sourceInstanceId: string;
  sourcePortName: string;
  targetInstanceId: string;
  targetPortName: string;
  createdAt: string;
  updatedAt: string;
}

export interface CompositionView {
  instances: SubPipelineInstance[];
  wirings: PortWiring[];
}

export type BlueprintCategory =
  | "INGESTION"
  | "TRANSFORM"
  | "MODELING"
  | "DATA_QUALITY"
  | "ORCHESTRATION"
  | "DESTINATION";

export const CATEGORY_LABELS: Record<BlueprintCategory, string> = {
  INGESTION: "Ingestion",
  TRANSFORM: "Transform",
  MODELING: "Modeling",
  DATA_QUALITY: "Data Quality",
  ORCHESTRATION: "Orchestration",
  DESTINATION: "Destination",
};

export interface BlueprintParamDefinition {
  name: string;
  type: string;
  required?: boolean;
  description?: string;
  default?: unknown;
  options?: string[];
  /**
   * ADR 0023 param tiering. V153 serializes `derived`; intent docs often use
   * the human spelling `system-derived`.
   */
  tier?: "user" | "derived" | "system-derived";
  /** Resolution source for derived/system-derived params. */
  derivedFrom?:
    | "pipeline.storage"
    | "domain.calendar"
    | "platform_default"
    | "connector"
    | "pipeline"
    | "pipeline.id"
    | "target_dataset.state_binding"
    | string;
  /**
   * When true (set by V92 audit on date-shaped params), the configure dialog
   * renders a {@link MnemonicDateInput} instead of a plain text field. Accepted
   * values: ISO date literals (YYYY-MM-DD) OR PULSE date mnemonics (BOM-12,
   * EOM-1, PBD, NBD+3, etc.). Resolved at runtime via pulse_dates against
   * domain.business_date_config.holiday_calendar_id and fiscal_offset_months.
   */
  accepts_mnemonic?: boolean;
  /**
   * #5 UI-construct HINT token (SPEC-construct-library.md §G-9 / W-13). Names
   * WHICH purpose-built construct renders this param in the configure dialog's
   * type-switch — the routing key when two same-`type` params need different
   * controls (e.g. an `object` `rename_map` vs an `object` `fill_null_map`).
   * When absent, the dialog falls back to routing by `type`.
   *
   * Known tokens (this lane): `column-picker`, `column-picker (multi)`,
   * `rename-mapper`, `key-value-mapper`, `type-cast-mapper`, `condition-builder`,
   * `route-builder`, `dq-outcome-control`, `date-mnemonic-picker`,
   * `expression-builder`, `sql-chain-editor`, `simple-sql-builder`, `cron-builder`.
   */
  ui_construct?: string;
  /**
   * Optional type restriction for `column-picker` / `column-picker (multi)`
   * (e.g. `["timestamp","date"]` for a `timestamp_column`, struct types for a
   * JsonFlatten `source_columns`). Substring-matched against each column's type.
   */
  filter_types?: string[];
}

export interface BlueprintPortDefinition {
  name: string;
  description?: string;
}

export interface Blueprint {
  id: string;
  blueprintKey: string;
  name: string;
  description: string;
  category: BlueprintCategory;
  subcategory?: string;
  version: string;
  paramsSchema: BlueprintParamDefinition[];
  inputPorts: BlueprintPortDefinition[];
  outputPorts: BlueprintPortDefinition[];
  runtimeRequirements: Record<string, unknown>;
  artifactTypes?: string[];
  computeBackend?: string;
  compositionRole?: string;
  validLayers?: string[];
  usageGuidance?: Record<string, unknown>;
  requiredParamsSchema?: Record<string, unknown>;
  optionalParamsSchema?: Record<string, unknown>;
  uiSchema?: Record<string, unknown>;
  emitStrategy?: string;
  supportsReuse?: boolean;
  schemaBehavior?: Record<string, unknown>;
  codegenHints?: Record<string, unknown>;
  status?: string;
  replacementBlueprintKey?: string | null;
  deferred: boolean;
  pipelineConfig: boolean;
  /**
   * Add surface (ARCH-011). One of: composition, orchestration_policy, none.
   * Drives which UIs may instantiate or configure the blueprint.
   */
  addSurface?: "composition" | "orchestration_policy" | "none";
  createdAt: string;
  updatedAt: string;
}

export type ConnectorType = "SOURCE" | "DESTINATION";
export type ReleaseStage = "ALPHA" | "BETA" | "GENERALLY_AVAILABLE";
export type CredentialStatus = "UNTESTED" | "VALID" | "INVALID" | "EXPIRED" | "SKIPPED";
export type ConnectorCredentialStrategy = "INHERIT_TENANT_GCP_SERVICE_ACCOUNT" | "CONNECTOR_SPECIFIC";
export type DataClassification = "PUBLIC" | "INTERNAL" | "CONFIDENTIAL" | "PII";
/**
 * Canonical persisted deployment environment keys (Phase 1 of the
 * deployment productization plan). Always lowercase on the wire, in
 * persisted records, and in client state. UI labels live in
 * {@link DEPLOY_ENVIRONMENT_LABELS} and are presentation-only.
 */
export type DeployEnvironment = "local" | "dev" | "integration" | "uat" | "prod";

/** Display ordering for env-keyed UI grids (status dots, env columns). */
export const DEPLOY_ENVIRONMENTS: DeployEnvironment[] = [
  "local",
  "dev",
  "integration",
  "uat",
  "prod",
];

/** Long-form labels used in dialogs and select menus. */
export const DEPLOY_ENVIRONMENT_LABELS: Record<DeployEnvironment, string> = {
  local: "Local",
  dev: "Dev",
  integration: "Integration",
  uat: "UAT",
  prod: "Production",
};

/** Short labels used in compact UIs (per-env status dots, badges). */
export const DEPLOY_ENVIRONMENT_SHORT_LABELS: Record<DeployEnvironment, string> = {
  local: "Local",
  dev: "Dev",
  integration: "Int",
  uat: "UAT",
  prod: "Prod",
};

/**
 * Best-effort normalizer for legacy server-supplied environment values
 * (uppercase {@code DEV}, {@code INTEGRATION}, {@code PRODUCTION}, {@code INT})
 * to the canonical lowercase keys. Returns {@code null} for unknown values
 * so callers can decide whether to render them as-is or hide them.
 */
export function normalizeDeployEnvironment(raw: unknown): DeployEnvironment | null {
  if (typeof raw !== "string") return null;
  const trimmed = raw.trim();
  if (!trimmed) return null;
  switch (trimmed.toUpperCase()) {
    case "LOCAL":
      return "local";
    case "DEV":
      return "dev";
    case "INT":
    case "INTEGRATION":
      return "integration";
    case "UAT":
      return "uat";
    case "PROD":
    case "PRODUCTION":
      return "prod";
    default:
      return null;
  }
}

export interface ConnectorDefinition {
  id: string;
  airbyteId: string;
  name: string;
  connectorType: ConnectorType;
  dockerRepository: string;
  dockerImageTag: string;
  iconUrl?: string;
  connectionSpec: Record<string, unknown>;
  supportedModes: string[];
  documentationUrl?: string;
  releaseStage: ReleaseStage;
  credentialStrategy: ConnectorCredentialStrategy;
  sinkWriterKind?: SinkWriterKind | null;
  writeDispositions?: WriteDisposition[] | null;
}

export type SinkWriterKind =
  | "LAKE"
  | "WAREHOUSE"
  | "RELATIONAL"
  | "DOCUMENT"
  | "STREAM";

export interface WriteDisposition {
  value: string;
  label: string;
}

/** Strategy-aware, central credential readiness contract (LCT-047). */
export interface ConnectorCredentialReadiness {
  connectorInstanceId: string;
  connectorName?: string;
  connectorTypeName?: string;
  connectionKind?: string;
  credentialStrategy?: ConnectorCredentialStrategy;
  status: string;
  reason?: string | null;
  gcpCredentialStatus?: string;
  storageScaffoldStatus?: string;
  lastValidatedAt?: string;
  validationCategory?: string;
}

export interface SystemOfRecord extends DomainScopedEntity {
  id: string;
  tenantId: string;
  name: string;
  description?: string;
  ownerId: string;
  connectorCount: number;
  datasetCount: number;
  metadata: Record<string, unknown>;
  createdAt: string;
  updatedAt: string;
}

export interface ConnectorInstance {
  id: string;
  sorId: string;
  connectorDefinitionId: string;
  name: string;
  description?: string;
  configTemplate: Record<string, unknown>;
  enabled: boolean;
  connectorTypeName?: string;
  dockerRepository?: string;
  connectorType?: ConnectorType;
  iconUrl?: string;
  releaseStage?: ReleaseStage;
  credentialStrategy?: ConnectorCredentialStrategy;
  credentialStatuses?: Record<DeployEnvironment, CredentialStatus>;
  sinkWriterKind?: SinkWriterKind | null;
  writeDispositions?: WriteDisposition[] | null;
  credentialReadiness?: ConnectorCredentialReadiness | null;
  datasetCount?: number;
  createdAt: string;
  updatedAt: string;
}

export type DatasetDefinitionType =
  | "TABLE_SELECTION"
  | "CUSTOM_SQL"
  | "SCHEMA_UPLOAD"
  | "FILE_INFERENCE"
  | "SCHEMA_REGISTRY"
  | "API_SPEC_IMPORT"
  | "OBJECT_SELECTION"
  | "SAMPLE_INFERENCE"
  | "SAMPLE_UPLOAD"
  | "MANUAL_DEFINITION";

export type DatasetStatus = "DRAFT" | "SCHEMA_DEFINED" | "VALIDATED" | "ACTIVE";

export interface DefinitionMethod {
  type: DatasetDefinitionType;
  label: string;
  description: string;
  isDefault: boolean;
}

/**
 * Credential responses keep the legacy redacted connectionConfig for compatibility
 * while exposing metadataConfig and secretRefs as the canonical UI-friendly slices.
 */
export interface CredentialProfile {
  id: string;
  connectorInstanceId: string;
  environment: DeployEnvironment;
  connectionConfig: Record<string, unknown>;
  metadataConfig?: Record<string, unknown>;
  secretRefs?: Record<string, string>;
  secretMetadata?: Record<
    string,
    {
      configured: boolean;
      secretReference: boolean;
    }
  >;
  status: CredentialStatus;
  lastTestedAt?: string;
  createdAt: string;
  updatedAt: string;
}

/**
 * Structured credential write payload used by the dialog so non-secret metadata
 * is never mixed with secret references or newly entered secret values in the request body.
 */
export interface CredentialUpsertRequest {
  metadata?: Record<string, unknown>;
  secretRefs?: Record<string, string>;
  secretValues?: Record<string, string>;
}

export interface Dataset {
  id: string;
  connectorInstanceId: string;
  tenantId: string;
  name: string;
  qualifiedName: string;
  description?: string;
  schemaSnapshot?: Record<string, unknown>;
  schemaFormat?: string;
  classification?: DataClassification;
  freshnessSla?: Record<string, unknown>;
  definitionType?: DatasetDefinitionType;
  definitionConfig?: Record<string, unknown>;
  customSql?: string;
  sourceTables?: string[];
  apiSpec?: Record<string, unknown>;
  status?: DatasetStatus;
  createdAt: string;
  updatedAt: string;
}

export type PlanStatus =
  | "PREVIEW"
  | "APPROVED"
  | "APPLYING"
  | "APPLIED"
  | "FAILED"
  | "CANCELLED";

export interface Plan {
  id: string;
  tenantId: string;
  pipelineId?: string;
  actorId: string;
  description: string;
  status: PlanStatus;
  previewData?: Record<string, unknown>;
  commandIds: string[];
  appliedAt?: string;
  createdAt: string;
  updatedAt: string;
}

export type CommandStatus =
  | "PENDING"
  | "VALIDATING"
  | "EXECUTING"
  | "SUCCEEDED"
  | "FAILED"
  | "REJECTED";

export interface CommandLogEntry {
  id: string;
  planId?: string;
  commandType: string;
  aggregateType: string;
  aggregateId: string;
  tenantId: string;
  actorId: string;
  idempotencyKey: string;
  payload: Record<string, unknown>;
  status: CommandStatus;
  errorMessage?: string;
  executedAt?: string;
  createdAt: string;
}

export interface ChatSession {
  id: string;
  tenantId: string;
  pipelineId?: string;
  userId: string;
  title?: string;
  createdAt: string;
  updatedAt: string;
}

export type ChatRole = "USER" | "ASSISTANT" | "SYSTEM" | "TOOL";

export interface ChatMessage {
  id: string;
  sessionId: string;
  role: ChatRole;
  content: string;
  toolCalls?: Record<string, unknown>[];
  toolResults?: Record<string, unknown>[];
  planId?: string;
  createdAt: string;
}

// PKT-FINAL-3 (BUG-05): the LOCAL provider/repoType were removed in V145 +
// GitController.onboard. The wire types are narrowed to GitHub/REMOTE only;
// any legacy backfilled rows surface as REMOTE with a placeholder URL.
export type GitProvider = "GITHUB";
export type PRStatus = "OPEN" | "MERGED" | "CLOSED";

export interface GitRepo extends DomainScopedEntity {
  id: string;
  tenantId: string;
  pipelineId?: string;
  provider: GitProvider;
  repoUrl: string;
  defaultBranch: string;
  lastSyncedAt?: string;
  metadata?: Record<string, unknown>;
  scope?: "TENANT" | "LEGACY";
  /** PKT-FINAL-3 (BUG-05): always "REMOTE" on new rows; preserved for
   *  back-compat with any cached client state. */
  repoType?: "REMOTE";
  localPath?: string;
  currentBranch?: string;
  createdAt: string;
  updatedAt: string;
}

export interface OnboardingStatus {
  onboarded: boolean;
  gitRepo?: GitRepo | null;
  domains: Array<{ id: string; name: string }>;
}

// BUG-43 / Agent L: Scaffold preview surfaced in OnboardingWizard Step 3 so
// operators can see exactly what PULSE will create in the tenant repo before
// clicking Initialize. Backend endpoint:
// POST /api/v1/tenants/{tenantId}/onboard/preview returns a read-only,
// idempotent ScaffoldPreview.
export interface ScaffoldPreviewDomain {
  domainId: string;
  domainName: string;
  domainSlug: string;
  status: "SCAFFOLDED" | "MISSING" | "ERROR";
  paths: string[];
}

export interface GitIdentityReadiness {
  required: boolean;
  ready: boolean;
  code: "ready" | "git_identity_required" | "git_identity_invalid" | "not_required";
  message: string;
  authorName?: string;
  authorEmail?: string;
}

export interface ScaffoldPreview {
  tenantId: string;
  repoType: string;
  branchName: string;
  gitIdentity: GitIdentityReadiness;
  topLevelMissing: boolean;
  topLevelPaths: string[];
  domains: ScaffoldPreviewDomain[];
}

export interface PullRequestRecord {
  id: string;
  gitRepoId: string;
  generationRunId?: string;
  versionId: string;
  prNumber: number;
  title: string;
  sourceBranch: string;
  targetBranch: string;
  status: PRStatus;
  prUrl?: string;
  mergeCommitSha?: string;
  providerPrId?: string;
  providerNodeId?: string;
  providerRepositoryId?: string;
  headSha?: string;
  headTreeSha?: string;
  baseSha?: string;
  packageArtifactSha256?: string;
  providerSyncedAt?: string;
  mergedAt?: string;
  closedAt?: string;
  metadata?: Record<string, unknown>;
  createdAt: string;
}

export interface DeveloperWorkspace {
  id: string;
  tenantId: string;
  pipelineId: string;
  versionId: string;
  gitRepoId: string;
  actorUserId: string;
  branchName: string;
  baseBranch: string;
  baseSha?: string;
  checkoutPath: string;
  legacySeed: boolean;
  lifecycleStatus: "ACTIVE" | "ARCHIVED";
  workingTreeStatus: "clean" | "dirty" | "unborn" | "conflict" | "missing" | "unknown";
  remoteSyncStatus: "not_pushed" | "pushed" | "behind" | "diverged" | "unknown";
  prStatus: "none" | "open" | "merged" | "closed" | "draft";
  headSha?: string;
  headTreeSha?: string;
  dirtyFileCount: number;
  lastPackageId?: string;
  lastDevDeploymentRunId?: string;
  lastCommitSha?: string;
  lastPushSha?: string;
  pullRequestId?: string;
  lockVersion: number;
  leaseOwner?: string;
  leaseExpiresAt?: string;
  metadata?: Record<string, unknown>;
}

export interface WorkspaceDiff {
  workspaceId: string;
  workingTreeStatus: DeveloperWorkspace["workingTreeStatus"];
  dirtyFileCount: number;
  changedPaths: string[];
}

export interface WorkspaceFileManifest {
  id: string;
  workspaceId: string;
  path: string;
  sourceArtifactId?: string;
  lastMaterializedSha256?: string;
  currentWorkspaceSha256?: string;
  lastCommittedSha256?: string;
  managedByPulse: boolean;
  pathScope: "PIPELINE" | "TENANT_SHARED";
  ownershipKey?: string;
  lastMaterializedAt?: string;
}

export interface WorkspaceFileContent {
  manifest: WorkspaceFileManifest;
  content: string;
}

export interface WorkspaceContext {
  gitRepo?: GitRepo | null;
  workspace?: DeveloperWorkspace | null;
}

export type PackageBuildStatus = "PENDING" | "BUILDING" | "COMPLETED" | "FAILED";

export interface DeployPackage {
  id: string;
  pipelineId: string;
  versionId: string;
  tenantId: string;
  packageType: string;
  artifactUrl?: string;
  artifactHash?: string;
  buildStatus: PackageBuildStatus;
  builtBy: string;
  buildLog?: string;
  builtAt?: string;
  sourceKind?: "WORKSPACE_SNAPSHOT" | "GIT_COMMIT" | string;
  workspaceId?: string;
  commitSha?: string;
  treeSha?: string;
  packageArtifactUri?: string;
  packageArtifactSha256?: string;
  packageManifestHash?: string;
  promotable?: boolean;
  metadata?: Record<string, unknown>;
  createdAt: string;
}

/**
 * Deployment row status. Phase 4 (preflight, run, evidence split)
 * introduced the lifecycle states {@code DRAFT}, {@code RUNNING},
 * and {@code PREFLIGHT_FAILED}; the legacy values are kept for
 * backwards compatibility with rows persisted before V104.
 *
 * <p>Note: a Phase 4 {@code DEPLOY} response no longer creates a
 * deployment in {@code DEPLOYED} state — the deployment lands in
 * {@code RUNNING} (preflight passed, run is non-terminal) or
 * {@code FAILED} (preflight failed). The {@code DEPLOYED} value
 * stays in the union for legacy rows but new flows do not produce it.
 */
export type DeploymentStatus =
  | "PENDING"
  | "DEPLOYING"
  | "DEPLOYED"
  | "FAILED"
  | "ROLLED_BACK"
  // Phase 4 additions (see V104):
  | "DRAFT"
  | "RUNNING"
  | "PREFLIGHT_FAILED"
  | "ACTIVE"
  | "SUPERSEDED";

/** Phase 4 PreflightCheckResult.status values surfaced via metadata.preflightStatus. */
export type PreflightStatus = "PASS" | "FAIL";

/**
 * Phase 4 — additional fields the controller stamps onto
 * {@link DeploymentRecord#metadata} alongside the existing
 * {@code targetEnvironment}/{@code targetType}/{@code targetName}.
 * Typed loosely as {@code Record<string, unknown>} on the parent so
 * legacy rows still parse; {@link DeployPhase4Metadata} is the
 * recommended cast when present.
 */
export interface DeployPhase4Metadata {
  deploymentRunId?: string;
  preflightStatus?: PreflightStatus;
  preflightBlockers?: string[];
  correlationId?: string;
  idempotentReplay?: boolean;
}

export interface DeploymentRecord {
  id: string;
  packageId: string;
  targetId: string;
  pipelineId: string;
  versionId: string;
  tenantId: string;
  deployedBy: string;
  status: DeploymentStatus;
  deployLog?: string;
  deployedAt?: string;
  rolledBackAt?: string;
  metadata?: Record<string, unknown> & Partial<DeployPhase4Metadata>;
  createdAt: string;
}

export interface DeploymentTarget {
  id: string;
  tenantId: string;
  name: string;
  environment: string;
  targetType: string;
  endpointUrl?: string;
  config?: Record<string, unknown>;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

export type ApprovalStatus = "PENDING" | "APPROVED" | "REJECTED" | "EXPIRED";

export interface ApprovalRequest {
  id: string;
  deploymentId: string;
  tenantId: string;
  requestedBy: string;
  approvedBy?: string;
  status: ApprovalStatus;
  reason?: string;
  decidedAt?: string;
  expiresAt?: string;
  createdAt: string;
}

/* ── Promotion Proof State (PKT-0008) ─────────────────────── */

/**
 * Seven-tier promotion proof state. Each tier represents a distinct
 * evidence level that is not interchangeable with other tiers.
 * Static package proof does not imply runtime proof. A deploy
 * request does not imply runtime success.
 */
export type PromotionProofState =
  | "DRAFT_WORKSPACE"
  | "ACCEPTED_ARTIFACT"
  | "STATIC_PACKAGE_PROOF"
  | "DEPLOY_REQUESTED"
  | "RUNTIME_PROVED"
  | "PROMOTION_READY"
  | "PROMOTION_COMPLETE";

export const PROMOTION_PROOF_STATES: PromotionProofState[] = [
  "DRAFT_WORKSPACE",
  "ACCEPTED_ARTIFACT",
  "STATIC_PACKAGE_PROOF",
  "DEPLOY_REQUESTED",
  "RUNTIME_PROVED",
  "PROMOTION_READY",
  "PROMOTION_COMPLETE",
];

export const PROMOTION_PROOF_STATE_LABELS: Record<PromotionProofState, string> = {
  DRAFT_WORKSPACE: "Draft Workspace",
  ACCEPTED_ARTIFACT: "Accepted Artifact",
  STATIC_PACKAGE_PROOF: "Static Package Proof",
  DEPLOY_REQUESTED: "Deploy Requested",
  RUNTIME_PROVED: "Runtime Proved",
  PROMOTION_READY: "Promotion Ready",
  PROMOTION_COMPLETE: "Promotion Complete",
};

/** Returns true if the proof state includes runtime execution evidence. */
export function hasRuntimeEvidence(state: PromotionProofState): boolean {
  return PROMOTION_PROOF_STATES.indexOf(state) >= PROMOTION_PROOF_STATES.indexOf("RUNTIME_PROVED");
}

export interface PromotionProofReadback {
  versionId: string;
  currentState: PromotionProofState;
  workspace?: {
    workspaceId: string;
    lifecycleStatus: string;
    workingTreeStatus: string;
    observedAt: string;
  } | null;
  acceptance?: {
    acceptanceId: string;
    acceptanceKind: string;
    acceptedPackageId: string;
    acceptedCommitSha: string;
    acceptedAt: string;
  } | null;
  staticPackage?: {
    packageId: string;
    sourceKind: string;
    promotable: boolean;
    staticVerdict: string;
    blockers: string[];
    builtAt: string;
  } | null;
  deployRequest?: {
    deploymentId: string;
    targetEnvironment: string;
    status: string;
    requestedAt: string;
  } | null;
  runtimeProof?: {
    deploymentRunId: string;
    runState: string;
    succeededAt: string;
  } | null;
  promotionGate?: {
    approvalId: string;
    approvalStatus: string;
    decidedAt: string;
  } | null;
  promotionComplete?: {
    promotedDeploymentId: string;
    targetEnvironment: string;
    promotedAt: string;
  } | null;
}

/* ── Runtime Evidence Envelope (PKT-0005) ─────────────────── */

/**
 * Proof level on the evidence ladder. Each level is not
 * interchangeable with any other. Lower levels cannot satisfy
 * gates that require higher levels.
 */
export type EvidenceProofLevel =
  | "STATIC_PACKAGE"
  | "PREFLIGHT"
  | "LOCAL_SYNTHETIC"
  | "LIVE_RUNTIME"
  | "ORACLE_VERDICT"
  | "PROMOTION_READINESS";

export const EVIDENCE_PROOF_LEVELS: EvidenceProofLevel[] = [
  "STATIC_PACKAGE",
  "PREFLIGHT",
  "LOCAL_SYNTHETIC",
  "LIVE_RUNTIME",
  "ORACLE_VERDICT",
  "PROMOTION_READINESS",
];

export const EVIDENCE_PROOF_LEVEL_LABELS: Record<EvidenceProofLevel, string> = {
  STATIC_PACKAGE: "Static Package",
  PREFLIGHT: "Preflight",
  LOCAL_SYNTHETIC: "Local Synthetic",
  LIVE_RUNTIME: "Live Runtime",
  ORACLE_VERDICT: "Oracle Verdict",
  PROMOTION_READINESS: "Promotion Ready",
};

/** Returns true only for LIVE_RUNTIME, ORACLE_VERDICT, PROMOTION_READINESS. */
export function isRuntimeProofLevel(level: EvidenceProofLevel): boolean {
  return EVIDENCE_PROOF_LEVELS.indexOf(level) >= EVIDENCE_PROOF_LEVELS.indexOf("LIVE_RUNTIME");
}

export interface RuntimeEvidenceEnvelope {
  schemaVersion: string;
  proofLevel: EvidenceProofLevel;
  evidenceType: string;
  deploymentRunId: string | null;
  packageId: string | null;
  tenantId: string | null;
  environment: string | null;
  adapter: string | null;
  producedAt: string | null;
  producedBy: string | null;
  correlationId: string | null;
  runtimeProof: boolean;
  promotionReady: boolean;
  composer?: {
    environment: string;
    dagId: string;
    dagRunId: string;
    taskStates: Record<string, string>;
    logLinks: string[];
    observedAt: string | null;
  } | null;
  dataproc?: {
    batchId: string;
    batchStatus: string;
    driverLogLocation: string | null;
    applicationLogLocation: string | null;
    sparkJobEvidence: Record<string, unknown>;
    observedAt: string | null;
  } | null;
  bigquery?: {
    jobIds: string[];
    targetDatasets: string[];
    targetTables: string[];
    ddlJobStatus: Record<string, string>;
    queryJobStatus: Record<string, string>;
    tableProbes: Array<{
      tableReference: string;
      uri: string;
      rowCount: number;
      checksum: string;
      probeStatus: string;
      probedAt: string | null;
    }>;
    observedAt: string | null;
  } | null;
  outputProof?: {
    outputs: Array<{
      outputType: string;
      uri: string | null;
      tableReference: string | null;
      rowCount: number;
      checksum: string | null;
      semanticProbeResult: string | null;
      probeStatus: string;
    }>;
    observedAt: string | null;
  } | null;
  oracleVerdict?: {
    semanticComparison: string;
    expectedSummary: string;
    actualSummary: string;
    verdict: string;
    evidenceIndexLinks: string[];
    comparisonDetails: Record<string, unknown>;
    evaluatedAt: string | null;
  } | null;
  cleanupPolicy?: {
    policy: string;
    retainArtifacts: boolean;
    retainRuntimeLogs: boolean;
    retentionDays: number;
    cleanupStatus: string;
    scheduledAt: string | null;
  } | null;
  promotionDecision?: {
    ready: boolean;
    reason: string;
    satisfiedGates: string[];
    unsatisfiedGates: string[];
    targetEnvironment: string;
    decidedAt: string | null;
  } | null;
  evidenceIndex?: Array<{
    artifactId: string;
    evidenceType: string;
    proofLevel: EvidenceProofLevel;
    path: string;
    sha256: string;
    producedAt: string | null;
  }>;
}

/* ── Deploy Boundary Readback (PKT-0004) ──────────────────── */

export type DeployBoundaryStatus = "LIVE" | "OPERATOR_BLOCKED" | "BLOCKED";

export interface DeployBoundaryReadback {
  schemaVersion: string;
  tenantId: string;
  targetId: string;
  targetType: string | null;
  environment: string | null;
  boundaryStatus: DeployBoundaryStatus;
  producedAt: string | null;
  producedBy: string | null;
  blockers: string[];
  operatorActions: string[];
  boundaryEvidenceDisclaimer: string;
  composer?: {
    gcpProject: string | null;
    region: string | null;
    composerEnvironment: string | null;
    dagTargetPaths: string[];
    packageDeliveryBucket: string | null;
    deployIdentityExpectation: string | null;
    responsibility: string;
    blockers: string[];
    ready: boolean;
  } | null;
  dataproc?: {
    region: string | null;
    runtimeServiceAccount: string | null;
    batchSubmissionStrategy: string;
    stagingBucket: string | null;
    packageTarget: string | null;
    responsibility: string;
    blockers: string[];
    ready: boolean;
  } | null;
  bigquery?: {
    targetDatasets: string[];
    targetTables: string[];
    ddlTargets: string[];
    jobTargets: string[];
    managedIcebergConnection: string | null;
    managedIcebergResourceRef: string | null;
    responsibility: string;
    blockers: string[];
    ready: boolean;
  } | null;
  secretManager?: {
    runtimeAccessExpectation: string;
    requiredSecretRefs: Array<{
      name: string;
      secretUri: string;
      purpose: string;
    }>;
    responsibility: string;
    blockers: string[];
    ready: boolean;
  } | null;
  evidenceLog?: {
    evidenceBucket: string | null;
    evidencePrefix: string | null;
    logDataset: string | null;
    logPrefix: string | null;
    responsibility: string;
    blockers: string[];
    ready: boolean;
  } | null;
  artifactReadiness?: {
    hasGeneratedArtifacts: boolean;
    hasPackage: boolean;
    packageStatus: string | null;
    artifactsReady: boolean;
    blockers: string[];
  } | null;
}

export const DEPLOY_BOUNDARY_STATUS_LABELS: Record<DeployBoundaryStatus, string> = {
  LIVE: "Live",
  OPERATOR_BLOCKED: "Operator Blocked",
  BLOCKED: "Blocked",
};

export interface DbtAsset {
  id: string;
  domainId: string;
  projectName: string;
  assetName: string;
  assetType: string;
  path: string;
  tags?: string[];
  groupName?: string;
  accessLevel?: string;
  grain?: string;
  businessConcept?: string;
  schemaSignature?: string;
  description?: string;
  metadata?: Record<string, unknown>;
}

/* ── User Story Generation ──────────────────────────────────── */

export interface UserStoryTask {
  title: string;
  description: string;
  estimate: "S" | "M" | "L";
}

export interface UserStory {
  title: string;
  storyMarkdown: string;
  tasks: UserStoryTask[];
}

/* ── Data Quality (GX Integration) ─────────────────────────── */

export type DqSeverity = "critical" | "warning" | "info";

export interface DqExpectation {
  type: string;
  kwargs: Record<string, unknown>;
  severity: DqSeverity;
  reason?: string;
}

export interface DqRecommendation {
  instance_name: string;
  instance_id?: string;
  expectations: DqExpectation[];
}

export interface DqReadinessResult {
  score: number;
  recommendations: DqRecommendation[];
  reasoning: string;
}

export interface DqValidationResult {
  id: string;
  instanceId: string;
  suiteName: string;
  success: boolean;
  statistics: Record<string, unknown>;
  results: Record<string, unknown>[];
  createdAt: string;
}

// #30 storage convention types.

export type StorageBackendKind = "DPC" | "GCP";
export type StorageEnvironment = "dev" | "integration" | "uat" | "prod";
export type StorageProvisioningStatus = "pending" | "validated" | "failed" | "disabled";
export type DpcScheme = "s3a" | "hdfs";

export interface StorageBackend {
  id: string;
  tenantId: string;
  environment: StorageEnvironment;
  backend: StorageBackendKind;
  storageRootFiles: string;
  storageRootLake: string;
  gcpProject: string | null;
  dpcScheme: DpcScheme | null;
  dpcCluster: string | null;
  provisioningStatus: StorageProvisioningStatus;
  provisioningValidatedAt: string | null;
  provisioningError: string | null;
  disabled: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface CobolDiscoverySession {
  id: string;
  tenantId: string;
  userId: string;
  title: string;
  status: string;
  createdAt: string;
  updatedAt: string;
}

export interface CobolDiscoveryMessage {
  id: string;
  sessionId: string;
  role: "USER" | "ASSISTANT";
  content: string;
  safePayloadOnly: boolean;
  metadata?: Record<string, unknown>;
  createdAt: string;
  updatedAt: string;
}

export interface CobolDiscoveryToolAction {
  name: "update_copybook_text" | "update_config" | "run_preview" | "run_full_profile" | "retrieve_preview_results";
  status: string;
  input: Record<string, unknown>;
  result: Record<string, unknown>;
}

export interface CobolDiscoveryArtifact {
  id: string;
  sessionId: string;
  tenantId: string;
  artifactType: "copybook" | "data_file";
  originalFilename: string;
  storageUri: string;
  sha256: string;
  sizeBytes: number;
  contentType?: string;
  cleanupStatus: string;
  expiresAt: string;
  createdAt: string;
  updatedAt: string;
}

export interface CobolDiscoveryRun {
  id: string;
  sessionId: string;
  tenantId: string;
  runType: "preview" | "profile" | "reprofile";
  status: string;
  configSnapshot: Record<string, unknown>;
  profilingSummary: Record<string, unknown>;
  confidenceScore?: number;
  anomalySummary: Record<string, unknown>;
  samplePolicy: Record<string, unknown>;
  resultSchemaSnapshot: Record<string, unknown>;
  previewRows: Record<string, unknown>[];
  mappingSpec: Record<string, unknown>[];
  eventLog: Record<string, unknown>[];
  cleanupStatus: string;
  expiresAt?: string;
  createdAt: string;
  updatedAt: string;
}

export interface CobolParsingProfile {
  id: string;
  tenantId: string;
  name: string;
  description?: string;
  copybookContent: string;
  cobrixOptions: Record<string, unknown>;
  flattenSpec: Record<string, unknown>;
  outputSchemaSnapshot: Record<string, unknown>;
  profileQualitySummary: Record<string, unknown>;
  metadata: Record<string, unknown>;
  createdBy?: string;
  createdAt: string;
  updatedAt: string;
}

export interface CobolDiscoveryMessageExchange {
  userMessage: CobolDiscoveryMessage;
  assistantMessage: CobolDiscoveryMessage;
  optionOverrides: Record<string, unknown>;
  toolActions: CobolDiscoveryToolAction[];
  activeRun?: CobolDiscoveryRun | null;
  previewSummary: Record<string, unknown>;
}

/* ── Tenant Readiness Readback (PKT-0022) ────────────────── */

/**
 * Server-produced readiness summary for a tenant. Each category
 * carries a boolean `ready` and optional `blockers[]`. The UI
 * must never synthesize readiness from localStorage or client-only
 * assumptions — this payload is the single source of truth.
 */
export interface TenantReadinessCategory {
  category: string;
  label: string;
  ready: boolean;
  blockers: string[];
}

export interface TenantReadinessReadback {
  tenantId: string;
  overallReady: boolean;
  categories: TenantReadinessCategory[];
  producedAt: string;
}

/* ── Landing Contract Preview (PKT-0022) ─────────────────── */

/**
 * Preview of a dataset's landing contract — the expected URI and
 * any binding blockers that prevent the contract from being
 * fulfilled. Returned by GET /api/v1/datasets/{id}/landing-contract/preview.
 */
export interface LandingContractPreview {
  datasetId: string;
  contractUri: string | null;
  landingPath: string | null;
  bindingBlockers: string[];
  ready: boolean;
}

/**
 * Returns true only for proof levels that represent non-runtime
 * (static, local, or preflight) evidence. These levels must never
 * be presented as end-to-end runtime success.
 */
export function isNonRuntimeProofLevel(level: EvidenceProofLevel): boolean {
  return !isRuntimeProofLevel(level);
}

/** Human-readable disclaimer for non-runtime evidence levels. */
export function nonRuntimeProofDisclaimer(level: EvidenceProofLevel): string {
  switch (level) {
    case "STATIC_PACKAGE":
      return "Static package assessment only — no runtime execution evidence. Cannot satisfy runtime output gates.";
    case "PREFLIGHT":
      return "Preflight checks passed — no runtime execution evidence yet. Cannot satisfy runtime output gates.";
    case "LOCAL_SYNTHETIC":
      return "Local synthetic run with simulated data — not real data-plane proof. Cannot satisfy runtime output gates.";
    default:
      return "";
  }
}

/* ── Tenant Readiness (PKT-0024 / PKT-0015) ─────────────────── */

/**
 * Readiness step status for a single category in the consolidated
 * tenant readiness verdict. Maps to backend ReadinessCategory.status.
 */
export type TenantReadinessStatus = "ready" | "blocked" | "not_configured" | "incomplete";

/**
 * Structured blocker from the consolidated readiness verdict.
 * Maps to backend ReadinessBlocker record.
 * No secret material is ever included in any field.
 */
export interface TenantReadinessBlocker {
  code: string;
  message: string;
  sourceSurface: string;
  evidenceRef: string;
  staleCheckTimestamp: string | null;
  safeNextAction: string;
  operatorRequired: boolean;
}

/**
 * Create-vs-validate ownership evidence for GCP resources.
 */
export interface TenantReadinessOwnership {
  resourceKind: string;
  createOwner: string;
  validateOwner: string;
}

/**
 * A single readiness category in the consolidated verdict.
 * Maps to backend ReadinessCategory record.
 */
export interface TenantReadinessStep {
  name: string;
  status: TenantReadinessStatus;
  blockers: TenantReadinessBlocker[];
  ownership: TenantReadinessOwnership | null;
  evidence: Record<string, unknown>;
}

/**
 * Consolidated tenant readiness verdict.
 * Maps to backend ConsolidatedReadinessVerdict record.
 * overallStatus is "ready" or "blocked" (fail-closed gate).
 */
export interface TenantReadiness {
  tenantId: string;
  overallStatus: "ready" | "blocked";
  checkedAt: string;
  categories: Record<string, TenantReadinessStep>;
  blockerSummary: TenantReadinessBlocker[];
  readyCategoryCount: number;
  totalCategoryCount: number;
}

/**
 * The 16 consolidated readiness category keys (PKT-0015).
 * Order matches the backend service evaluation order.
 */
export const READINESS_CATEGORY_KEYS = [
  "tenantIdentity",
  "gcpConfig",
  "gcpCredentials",
  "iamManifest",
  "githubRepo",
  "githubPat",
  "gitScaffold",
  "storageScaffold",
  "domainScaffold",
  "composer",
  "dataproc",
  "bigQuery",
  "secretManager",
  "runtimeBinding",
  "deploymentTarget",
  "evidenceLogging",
] as const;

export type ReadinessCategoryKey = (typeof READINESS_CATEGORY_KEYS)[number];

export const READINESS_CATEGORY_LABELS: Record<ReadinessCategoryKey, string> = {
  tenantIdentity: "Tenant Identity",
  gcpConfig: "GCP Configuration",
  gcpCredentials: "GCP Credentials",
  iamManifest: "IAM Manifest",
  githubRepo: "GitHub Repository",
  githubPat: "GitHub PAT",
  gitScaffold: "Git Scaffold",
  storageScaffold: "Storage Scaffold",
  domainScaffold: "Domain Scaffold",
  composer: "Cloud Composer",
  dataproc: "Dataproc Serverless",
  bigQuery: "BigQuery",
  secretManager: "Secret Manager",
  runtimeBinding: "Runtime Binding",
  deploymentTarget: "Deployment Target",
  evidenceLogging: "Evidence & Logging",
};

/**
 * Group labels for visual sectioning in the readiness wizard.
 */
export const READINESS_CATEGORY_GROUPS: Array<{
  label: string;
  keys: ReadinessCategoryKey[];
}> = [
  { label: "Tenant & Identity", keys: ["tenantIdentity", "gcpConfig", "gcpCredentials", "iamManifest"] },
  { label: "Git & Repository", keys: ["githubRepo", "githubPat", "gitScaffold"] },
  { label: "Storage & Domains", keys: ["storageScaffold", "domainScaffold"] },
  { label: "GCP Runtime", keys: ["composer", "dataproc", "bigQuery", "secretManager"] },
  { label: "Deployment & Observability", keys: ["runtimeBinding", "deploymentTarget", "evidenceLogging"] },
];

/* ── Tenant Secret Manager Binding (BUG-63 / SU-7) ─────────────
 *
 * Shared shapes for the `secretManager` readiness category. Previously
 * declared as file-local interfaces inside
 * `frontend/src/components/settings/tenant-secret-manager-panel.tsx`
 * (lines 54-63 prior to SU-7); promoted here so downstream consumers
 * (settings wizard, e2e fixtures, integration tests) can reference the
 * same wire contract without re-deriving it.
 *
 * Backend wire contract (per Agent E):
 *   GET  /api/v1/tenants/{tenantId}/secret-manager  → TenantSecretManagerReadback
 *   PUT  /api/v1/tenants/{tenantId}/secret-manager  ← TenantSecretManagerForm
 *
 * Operator decisions honored:
 *   - Default mode is `LOCAL_STUB` (dev). Switching to GSM does NOT
 *     auto-migrate existing local-stub secrets.
 *   - `gsmProjectId` is REQUIRED iff `mode === "TENANT_GCP_SECRET_MANAGER"`.
 *   - `secretNamePrefix` is always optional.
 */

/** Persistence backend selected for tenant secrets. */
export type SecretManagerMode = "LOCAL_STUB" | "TENANT_GCP_SECRET_MANAGER";

/** Form value POSTed to the secret-manager binding endpoint. */
export interface TenantSecretManagerForm {
  mode: SecretManagerMode;
  /** REQUIRED iff `mode === "TENANT_GCP_SECRET_MANAGER"`. */
  gsmProjectId?: string;
  /** Optional namespace applied to generated secret names. */
  secretNamePrefix?: string;
}

/**
 * Readback returned by GET `/api/v1/tenants/{tenantId}/secret-manager`.
 * Extends the form shape with readiness-computed status + audit timestamp.
 */
export interface TenantSecretManagerReadback extends TenantSecretManagerForm {
  computedStatus?: "PASS" | "FAIL" | "BLOCKED" | "PROVEN";
  updatedAt?: string | null;
}

/* ── Tenant GCP Runtime Topology (BUG-70 / SU-5) ───────────────
 *
 * Wire body for `POST /api/v1/tenants/{tenantId}/gcp-runtime-topology`.
 * Mirrors the file-local interface inside
 * `frontend/src/components/settings/tenant-gcp-runtime-topology-panel.tsx`
 * so the Integrator's tests can import the same shape.
 */
export interface TenantGcpRuntimeTopologyForm {
  composerEnvironment: string;
  composerRegion: string;
  dataprocRegion: string;
  bigQueryLocation: string;
  bronzeDataset: string;
  silverDataset: string;
  goldDataset: string;
  evidenceDataset: string;
  secretManagerProjectId: string;
  controlPlaneSaEmail: string;
  evidenceLogBucket?: string;
  evidenceLogPrefix?: string;
}

/**
 * Discriminated-union POST body for `/api/v1/tenants/{tenantId}/deployment-targets`.
 * SU-5 BUG-69 — `endpointUrl` removed (dead weight); config keys are type-specific.
 */
export interface DeploymentTargetCreateRequest {
  name: string;
  environment: "dev" | "integration" | "uat" | "prod";
  targetType: string;
  config: Record<string, unknown>;
}

// ARCH-004: Runtime Authority
export type RuntimePersonaKey = "GCP_PULSE" | "DPC_PULSE";

export interface RuntimeAuthority {
  activePersona: RuntimePersonaKey;
  displayName: string;
  allowedTargetTypes: string[];
  allowedStorageBackends: string[];
  allowedOrchestrators: string[];
  allowedComputeRuntimes: string[];
  allowedStorageKinds: string[];
  allowedCatalogs: string[];
  allowedBrokerPeers: RuntimePersonaKey[];
  allowedMaterializations: Record<string, string[]>;
  secretAuthority: string;
  legalRuntimeMatrixVersion: string;
}
