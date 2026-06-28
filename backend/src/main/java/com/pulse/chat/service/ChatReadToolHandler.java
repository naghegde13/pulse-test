package com.pulse.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.chat.plan.ChatScopeKind;
import com.pulse.chat.plan.ContractImpactCode;
import com.pulse.chat.plan.ContractImpactDerivation;
import com.pulse.deploy.model.Package;
import com.pulse.deploy.preflight.DeploymentPreflightService;
import com.pulse.deploy.preflight.PreflightCheckResult;
import com.pulse.deploy.projection.model.RuntimeProjection;
import com.pulse.deploy.projection.service.RuntimeProjectionService;
import com.pulse.deploy.projection.service.RuntimeProjectionService.ProjectionDriftResult;
import com.pulse.deploy.repository.PackageRepository;
import com.pulse.git.workspace.DeveloperWorkspaceService;
import com.pulse.git.workspace.WorkspaceDtos;
import com.pulse.runtime.model.RuntimeAuthority;
import com.pulse.runtime.model.RuntimePersona;
import com.pulse.runtime.service.RuntimeAuthorityService;
import com.pulse.storage.contract.service.StorageAuthorityFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ARCH-018 dependency-backed read-only chat tools.
 *
 * <p>This handler exposes contract previews, readiness checks, package
 * manifest reads, deploy preflight, and workspace context lookups as
 * generic, side-effect-free chat tools. The handler routes each tool to
 * the canonical upstream service (Droid storage/projection lanes, Codex
 * workspace lane, deploy preflight) and serializes the result as JSON for
 * the LLM-facing message body.</p>
 *
 * <p>All tools here are read-only. None of them create, mutate, or apply
 * a {@code Plan}; they are safe to call at any point in a chat session
 * without changing product state.</p>
 *
 * <p>The {@code plan_create_pipeline (DOMAIN scope)} draft-ref flow and
 * the {@code request_credential_attach} provisional-ref flow are
 * intentionally NOT implemented here: they require PlanService-level
 * alias / ref-binding infrastructure that does not yet exist, and per
 * Codex's hard rule must not be invented as chat-only state.</p>
 */
@Component
public class ChatReadToolHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatReadToolHandler.class);

    private final StorageAuthorityFacade storageAuthorityFacade;
    private final RuntimeProjectionService runtimeProjectionService;
    private final RuntimeAuthorityService runtimeAuthorityService;
    private final DeploymentPreflightService deploymentPreflightService;
    private final PackageRepository packageRepository;
    private final DeveloperWorkspaceService developerWorkspaceService;
    private final ContractImpactDerivation contractImpactDerivation;
    private final ObjectMapper objectMapper;

    public ChatReadToolHandler(StorageAuthorityFacade storageAuthorityFacade,
                                RuntimeProjectionService runtimeProjectionService,
                                RuntimeAuthorityService runtimeAuthorityService,
                                DeploymentPreflightService deploymentPreflightService,
                                PackageRepository packageRepository,
                                DeveloperWorkspaceService developerWorkspaceService,
                                ContractImpactDerivation contractImpactDerivation,
                                ObjectMapper objectMapper) {
        this.storageAuthorityFacade = storageAuthorityFacade;
        this.runtimeProjectionService = runtimeProjectionService;
        this.runtimeAuthorityService = runtimeAuthorityService;
        this.deploymentPreflightService = deploymentPreflightService;
        this.packageRepository = packageRepository;
        this.developerWorkspaceService = developerWorkspaceService;
        this.contractImpactDerivation = contractImpactDerivation;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------ tools

    /**
     * {@code preview_dataset_landing} - canonical landing-contract preview
     * for a dataset against a target environment. Returns the resolved
     * landing / rejected / archive / outgoing URIs along with the binding
     * blocker code (if any).
     */
    public String previewDatasetLanding(String tenantId, Map<String, Object> args) {
        String datasetId = stringArg(args, "dataset_id");
        String environment = stringArg(args, "environment", "dev");
        if (datasetId == null) {
            return "Error: dataset_id is required";
        }
        try {
            Map<String, Object> preview = storageAuthorityFacade.getDatasetLandingPreview(
                    datasetId, tenantId, environment);
            return toJson("preview_dataset_landing", preview);
        } catch (Exception e) {
            log.warn("preview_dataset_landing failed for dataset {}: {}", datasetId, e.getMessage());
            return "Error reading landing preview: " + e.getMessage();
        }
    }

    /**
     * {@code preview_table_contract} - canonical table-contract preview
     * for a SubPipelineInstance against a target environment. Returns
     * contracts with resolved object-store URIs, catalog identifiers, and
     * projection status.
     */
    public String previewTableContract(String tenantId, Map<String, Object> args) {
        String instanceId = stringArg(args, "instance_id");
        String versionId = stringArg(args, "version_id");
        String environment = stringArg(args, "environment", "dev");
        if (instanceId == null || versionId == null) {
            return "Error: instance_id and version_id are required";
        }
        try {
            Map<String, Object> preview = storageAuthorityFacade.getTableContractPreview(
                    instanceId, versionId, tenantId, environment);
            return toJson("preview_table_contract", preview);
        } catch (Exception e) {
            log.warn("preview_table_contract failed: {}", e.getMessage());
            return "Error reading table contract preview: " + e.getMessage();
        }
    }

    /**
     * {@code preview_runtime_projection} - composes
     * {@link RuntimeProjectionService#getActiveProjection(String,String,String)}
     * with a drift check so the LLM can see both the active projection and
     * whether the stored hash still matches the current state.
     */
    public String previewRuntimeProjection(String tenantId, Map<String, Object> args) {
        String packageId = stringArg(args, "package_id");
        String targetId = stringArg(args, "target_id");
        String environment = stringArg(args, "environment", "dev");
        if (packageId == null || targetId == null) {
            return "Error: package_id and target_id are required";
        }
        try {
            Optional<RuntimeProjection> projection =
                    runtimeProjectionService.getActiveProjection(packageId, targetId, environment);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("packageId", packageId);
            body.put("targetId", targetId);
            body.put("environment", environment);
            if (projection.isEmpty()) {
                body.put("status", "no_projection");
                body.put("message",
                        "No active runtime projection for this (package, target, environment). "
                                + "Run POST /api/v1/packages/{packageId}/runtime-projections to create one.");
                return toJson("preview_runtime_projection", body);
            }
            RuntimeProjection p = projection.get();
            Map<String, Object> projectionView = new LinkedHashMap<>();
            projectionView.put("projectionId", p.getId());
            projectionView.put("runtimePersona", p.getRuntimePersona());
            projectionView.put("runtimeAuthorityVersion", p.getRuntimeAuthorityVersion());
            projectionView.put("projectionHash", p.getProjectionHash());
            projectionView.put("status", p.getStatus());
            projectionView.put("readinessBlockers", p.getReadinessBlockers());
            projectionView.put("resolvedEntrypoints", p.getResolvedEntrypoints());
            projectionView.put("projectedAt", p.getProjectedAt());
            body.put("status", "ok");
            body.put("projection", projectionView);
            try {
                ProjectionDriftResult drift = runtimeProjectionService.checkDrift(p.getId());
                Map<String, Object> driftView = new LinkedHashMap<>();
                driftView.put("drifted", drift.drifted());
                driftView.put("storedHash", drift.storedHash());
                driftView.put("currentHash", drift.currentHash());
                body.put("drift", driftView);
            } catch (Exception e) {
                body.put("driftError", e.getMessage());
            }
            return toJson("preview_runtime_projection", body);
        } catch (Exception e) {
            log.warn("preview_runtime_projection failed: {}", e.getMessage());
            return "Error reading runtime projection: " + e.getMessage();
        }
    }

    /**
     * {@code check_table_contract_readiness} - the canonical table-contract
     * readiness read. Returns {@code ready} plus a list of blocker
     * {code, message} records.
     */
    public String checkTableContractReadiness(String tenantId, Map<String, Object> args) {
        String versionId = stringArg(args, "version_id");
        if (versionId == null) {
            return "Error: version_id is required";
        }
        try {
            Map<String, Object> readiness = storageAuthorityFacade.getContractReadiness(versionId);
            return toJson("check_table_contract_readiness", readiness);
        } catch (Exception e) {
            log.warn("check_table_contract_readiness failed: {}", e.getMessage());
            return "Error reading contract readiness: " + e.getMessage();
        }
    }

    /**
     * {@code get_package_contract} - returns the canonical
     * {@code packageManifest} stored on the {@code Package.metadata} jsonb
     * blob. Source of truth for the v2 source-package contract a deploy
     * adapter consumes.
     */
    public String getPackageContract(String tenantId, Map<String, Object> args) {
        String packageId = stringArg(args, "package_id");
        if (packageId == null) {
            return "Error: package_id is required";
        }
        Optional<Package> pkgOpt = packageRepository.findById(packageId);
        if (pkgOpt.isEmpty()) {
            return "Error: package_id not found: " + packageId;
        }
        Package pkg = pkgOpt.get();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("packageId", pkg.getId());
        body.put("pipelineId", pkg.getPipelineId());
        body.put("versionId", pkg.getVersionId());
        body.put("workspaceId", pkg.getWorkspaceId());
        Map<String, Object> metadata = pkg.getMetadata();
        Object manifest = metadata == null ? null : metadata.get("packageManifest");
        if (manifest == null) {
            body.put("status", "no_manifest");
            body.put("message",
                    "Package row exists but metadata.packageManifest is empty. The package was likely "
                            + "produced by an older codegen path; regenerate to emit v2.");
        } else {
            body.put("status", "ok");
            body.put("manifest", manifest);
        }
        return toJson("get_package_contract", body);
    }

    /**
     * {@code check_deploy_readiness} - composes
     * {@link DeploymentPreflightService#check(String,String,Instant)} and
     * returns the canonical {@code PreflightCheckResult} JSON
     * (schema {@code deployment-preflight-result.v1}). This is the only
     * canonical deploy-readiness read.
     */
    public String checkDeployReadiness(String tenantId, Map<String, Object> args) {
        String packageId = stringArg(args, "package_id");
        String targetId = stringArg(args, "target_id");
        if (packageId == null || targetId == null) {
            return "Error: package_id and target_id are required";
        }
        try {
            PreflightCheckResult result = deploymentPreflightService.check(
                    packageId, targetId, Instant.now());
            return toJson("check_deploy_readiness", result.toCanonicalJson());
        } catch (com.pulse.common.exception.ResourceNotFoundException nf) {
            return "Error: " + nf.getMessage();
        } catch (Exception e) {
            log.warn("check_deploy_readiness failed: {}", e.getMessage());
            return "Error reading deploy readiness: " + e.getMessage();
        }
    }

    /**
     * {@code get_workspace_context} - the canonical WORKSPACE-scope chat
     * lookup. Surfaces the resolved git repo plus the active workspace
     * status (or null) for a given version. Drives the LLM's choice
     * between WORKSPACE scope and PIPELINE scope.
     */
    public String getWorkspaceContext(String tenantId, Map<String, Object> args) {
        String versionId = stringArg(args, "version_id");
        if (versionId == null) {
            return "Error: version_id is required";
        }
        try {
            WorkspaceDtos.WorkspaceContextDto ctx =
                    developerWorkspaceService.getWorkspaceContext(versionId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("versionId", versionId);
            if (ctx.gitRepo() != null) {
                Map<String, Object> repoView = new LinkedHashMap<>();
                repoView.put("repoId", ctx.gitRepo().getId());
                repoView.put("scope", ctx.gitRepo().getScope());
                repoView.put("provider", ctx.gitRepo().getProvider());
                repoView.put("repoUrl", ctx.gitRepo().getRepoUrl());
                body.put("gitRepo", repoView);
            }
            WorkspaceDtos.WorkspaceStatusDto ws = ctx.workspace();
            if (ws == null) {
                body.put("workspaceStatus", "none");
                body.put("scopeHint", ChatScopeKind.PIPELINE.wire());
                body.put("message",
                        "No active developer workspace for this version. PIPELINE-scope tools apply.");
            } else {
                Map<String, Object> wsView = new LinkedHashMap<>();
                wsView.put("workspaceId", ws.id());
                wsView.put("branchName", ws.branchName());
                wsView.put("baseBranch", ws.baseBranch());
                wsView.put("lifecycleStatus", ws.lifecycleStatus());
                wsView.put("workingTreeStatus", ws.workingTreeStatus());
                wsView.put("remoteSyncStatus", ws.remoteSyncStatus());
                wsView.put("prStatus", ws.prStatus());
                wsView.put("dirtyFileCount", ws.dirtyFileCount());
                wsView.put("headSha", ws.headSha());
                wsView.put("legacySeed", ws.legacySeed());
                body.put("workspace", wsView);
                body.put("scopeHint", ChatScopeKind.WORKSPACE.wire());
            }
            return toJson("get_workspace_context", body);
        } catch (com.pulse.common.exception.ResourceNotFoundException nf) {
            return "Error: " + nf.getMessage();
        } catch (Exception e) {
            log.warn("get_workspace_context failed: {}", e.getMessage());
            return "Error reading workspace context: " + e.getMessage();
        }
    }

    /**
     * {@code derive_contract_impact} - exposes the
     * {@link ContractImpactDerivation} helper as a chat tool so the LLM can
     * surface an explicit impact hint to the user without invoking a full
     * preview chain.
     */
    public String deriveContractImpact(String tenantId, Map<String, Object> args) {
        String versionId = stringArg(args, "version_id");
        String packageId = stringArg(args, "package_id");
        String targetId = stringArg(args, "target_id");
        String environment = stringArg(args, "environment", "dev");
        ContractImpactCode code = contractImpactDerivation.derive(
                versionId, packageId, targetId, environment);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("versionId", versionId);
        body.put("packageId", packageId);
        body.put("targetId", targetId);
        body.put("environment", environment);
        body.put("contractImpact", code.wire());
        body.put("message", switch (code) {
            case NONE -> "No downstream contract is stale.";
            case SCHEMA_STALE ->
                    "Upstream schema changed; regenerate to refresh contracts.";
            case TABLE_CONTRACT_STALE ->
                    "Table contract is stale or missing; re-run contract generation for the version.";
            case RUNTIME_PROJECTION_STALE ->
                    "Runtime projection for (package, target, environment) is missing or drifted; "
                            + "re-project before deploy.";
            case READINESS_RECHECK_REQUIRED ->
                    "Readiness state is uncertain; re-run check_deploy_readiness before the next apply.";
        });
        return toJson("derive_contract_impact", body);
    }

    /**
     * {@code preview_runtime_authority} - canonical runtime authority readback
     * including physical design authority (partition transforms, layout
     * strategies, DDL executors/dialects, DDL limits).
     */
    public String previewRuntimeAuthority(String tenantId, Map<String, Object> args) {
        try {
            RuntimeAuthority auth = runtimeAuthorityService.getAuthority();
            RuntimePersona persona = auth.activePersona();

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("activePersona", persona.name());
            body.put("displayName", auth.displayName());
            body.put("allowedTargetTypes", auth.allowedTargetTypes());
            body.put("allowedStorageBackends", auth.allowedStorageBackends());
            body.put("allowedCatalogs", auth.allowedCatalogs());
            body.put("allowedMaterializations", auth.allowedMaterializations());
            body.put("legalRuntimeMatrixVersion", auth.legalRuntimeMatrixVersion());

            Map<String, Object> pda = new LinkedHashMap<>();
            pda.put("partitionTransforms", switch (persona) {
                case GCP_PULSE -> List.of("identity", "year", "month", "day", "hour", "truncate", "bucket");
                case DPC_PULSE -> List.of("identity", "year", "month", "day");
            });
            pda.put("layoutStrategies", switch (persona) {
                case GCP_PULSE -> List.of("clustering", "z_order");
                case DPC_PULSE -> List.of("sort_by");
            });
            pda.put("ddlExecutors", switch (persona) {
                case GCP_PULSE -> List.of("BIGQUERY_SQL", "SPARK_SQL");
                case DPC_PULSE -> List.of("HIVE_JDBC", "SPARK_SQL");
            });
            pda.put("ddlDialects", switch (persona) {
                case GCP_PULSE -> List.of("BIGQUERY", "SPARK_ICEBERG");
                case DPC_PULSE -> List.of("HIVE", "SPARK_ICEBERG");
            });
            pda.put("ddlLimits", Map.of(
                    "maxStatementsPerProjection", 50,
                    "maxBodySizeBytes", 65536,
                    "requireIdempotency", true
            ));
            body.put("physicalDesignAuthority", pda);

            return toJson("preview_runtime_authority", body);
        } catch (Exception e) {
            log.warn("preview_runtime_authority failed: {}", e.getMessage());
            return "Error reading runtime authority: " + e.getMessage();
        }
    }

    // ------------------------------------------------------------------ helpers

    private String toJson(String toolName, Object body) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(body);
        } catch (Exception e) {
            log.warn("{} JSON serialization failed: {}", toolName, e.getMessage());
            return "{\"toolName\":\"" + toolName + "\",\"status\":\"error\",\"message\":\""
                    + e.getMessage() + "\"}";
        }
    }

    private static String stringArg(Map<String, Object> args, String key) {
        return stringArg(args, key, null);
    }

    private static String stringArg(Map<String, Object> args, String key, String defaultValue) {
        if (args == null) return defaultValue;
        Object v = args.get(key);
        if (v instanceof String s && !s.isBlank()) {
            return s;
        }
        return defaultValue;
    }
}
