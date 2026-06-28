package com.pulse.deploy.preflight;

import com.pulse.auth.policy.CallerContext;
import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.deploy.adapter.CapabilityCheckResult;
import com.pulse.deploy.capability.RuntimeCapabilityMatrix;
import com.pulse.deploy.environment.DeploymentEnvironment;
import com.pulse.deploy.model.DeploymentTarget;
import com.pulse.deploy.model.Package;
import com.pulse.deploy.projection.model.SourcePackageManifestV2;
import com.pulse.deploy.repository.DeploymentTargetRepository;
import com.pulse.deploy.repository.PackageRepository;
import com.pulse.git.model.PullRequest;
import com.pulse.git.policy.BranchAllowlistPolicy;
import com.pulse.git.repository.PullRequestRepository;
import com.pulse.pipeline.repository.VersionAcceptanceRepository;
import com.pulse.deploy.projection.service.RuntimeProjectionService;
import com.pulse.deploy.runtime.AirflowRuntimeClient;
import com.pulse.pipeline.service.OrchestrationNamespaceService;
import com.pulse.runtime.service.RuntimeAuthorityService;
import com.pulse.storage.contract.model.TableContract;
import com.pulse.storage.contract.service.TableContractService;
import com.pulse.secret.service.CredentialReadinessService;
import com.pulse.storage.StorageBackendDeployGate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase 4 — preflight orchestrator.
 *
 * <p>Runs the closed set of {@link PreflightCheckCode} checks against
 * a (package, target, environment) tuple and returns a
 * {@link PreflightCheckResult} suitable for both API responses and
 * evidence storage. No side effects: this service is a pure function
 * of the supplied inputs and the current state of {@code packages},
 * {@code deployment_targets}, {@code storage_backends}, and credential
 * profiles.
 *
 * <p>Phase 4 is intentionally conservative — checks read existing
 * package metadata fields (Phase 2 git provenance, Phase 2 static
 * runtime assessment, Phase 1 storage gate) rather than introducing
 * new side data. Phase 5+ will tighten {@code RUNTIME_CAPABILITY} and
 * add materialization-derived checks.
 */
@Service
public class DeploymentPreflightService {

    private final PackageRepository packageRepo;
    private final DeploymentTargetRepository targetRepo;
    private final StorageBackendDeployGate storageGate;
    private final CredentialReadinessService credentialReadinessService;
    private final BranchAllowlistPolicy branchAllowlistPolicy;
    private final PullRequestRepository pullRequestRepository;
    private final VersionAcceptanceRepository acceptanceRepository;
    private final RuntimeCapabilityMatrix capabilityMatrix;
    private final RuntimeAuthorityService runtimeAuthorityService;
    private final TableContractService tableContractService;
    private final RuntimeProjectionService runtimeProjectionService;
    private final AirflowRuntimeClient airflowRuntimeClient;
    private final OrchestrationNamespaceService orchestrationNamespaceService;

    public DeploymentPreflightService(PackageRepository packageRepo,
                                      DeploymentTargetRepository targetRepo,
                                      StorageBackendDeployGate storageGate,
                                      CredentialReadinessService credentialReadinessService,
                                      BranchAllowlistPolicy branchAllowlistPolicy,
                                      PullRequestRepository pullRequestRepository,
                                      VersionAcceptanceRepository acceptanceRepository,
                                      RuntimeCapabilityMatrix capabilityMatrix,
                                      RuntimeAuthorityService runtimeAuthorityService,
                                      TableContractService tableContractService,
                                      RuntimeProjectionService runtimeProjectionService,
                                      AirflowRuntimeClient airflowRuntimeClient,
                                      OrchestrationNamespaceService orchestrationNamespaceService) {
        this.packageRepo = packageRepo;
        this.targetRepo = targetRepo;
        this.storageGate = storageGate;
        this.credentialReadinessService = credentialReadinessService;
        this.branchAllowlistPolicy = branchAllowlistPolicy;
        this.pullRequestRepository = pullRequestRepository;
        this.acceptanceRepository = acceptanceRepository;
        this.capabilityMatrix = capabilityMatrix;
        this.runtimeAuthorityService = runtimeAuthorityService;
        this.tableContractService = tableContractService;
        this.runtimeProjectionService = runtimeProjectionService;
        this.airflowRuntimeClient = airflowRuntimeClient;
        this.orchestrationNamespaceService = orchestrationNamespaceService;
    }

    /**
     * Convenience overload — preflight without explicit caller context.
     * Used by callers that haven't yet wired the actor through (e.g.
     * legacy paths and Phase 4 unit tests). The {@code AGENT_AUDIT_CONTEXT}
     * check defaults to a "no caller supplied" failure when this overload
     * is used outside a controller path.
     */
    public PreflightCheckResult check(String packageId, String targetId, Instant now) {
        return check(packageId, targetId, now, null, null);
    }

    /**
     * Run preflight for the given package + target id at {@code now}.
     * @param packageId      package being deployed
     * @param targetId       resolved deployment target
     * @param now            deterministic capture timestamp (injected for tests)
     * @param caller         resolved actor context, or {@code null} when invoked
     *                       outside a caller-aware path
     * @param correlationId  request correlation id, or {@code null}
     */
    public PreflightCheckResult check(String packageId,
                                      String targetId,
                                      Instant now,
                                      CallerContext caller,
                                      String correlationId) {
        Package pkg = packageRepo.findById(packageId)
                .orElseThrow(() -> new ResourceNotFoundException("Package", packageId));
        // Resolve the target up front so TARGET_EXISTS / TARGET_ENABLED
        // can produce real outcomes.
        DeploymentTarget target = targetRepo.findById(targetId).orElse(null);
        String tenantId = pkg.getTenantId();
        String canonicalEnv = target != null
                ? safeNormalize(target.getEnvironment())
                : null;

        // The Phase 4 closeout pins the order of checks to the matrix in
        // docs/architecture/deployment-productization-plan.md so the
        // preflight-result.json fixture and the runtime checks list stay
        // in lockstep.
        List<PreflightCheckResult.CheckOutcome> checks = new ArrayList<>();
        checks.add(checkPackageCompleted(pkg));
        checks.add(checkPackageProvenancePresent(pkg));
        checks.add(checkPackageCleanForEnv(pkg, canonicalEnv));
        checks.add(checkStaticDeployability(pkg));
        checks.add(checkAirflowCallbackPolicy(pkg, target, canonicalEnv));
        checks.add(checkTargetExists(target, targetId));
        checks.add(checkTargetEnabled(target));
        checks.add(checkTargetSchemaValid(target));
        checks.add(checkStorageBackendValidated(pkg, canonicalEnv));
        checks.add(checkCredentialReadiness(pkg, canonicalEnv));
        checks.add(checkSecretReferencesOnly(pkg));
        checks.add(checkApprovalPolicy(pkg, canonicalEnv));
        checks.add(checkGitBranchAllowed(pkg, canonicalEnv));
        checks.add(checkPrPolicy(pkg, canonicalEnv));
        checks.add(checkRuntimeCapability(pkg));
        checks.add(checkRuntimeCapabilityOk(pkg, target));
        checks.add(checkRuntimeAuthorityPersonaMatch(pkg));
        checks.add(checkTargetTypePersonaLegal(target));
        checks.add(checkTableContractsPresent(pkg));
        checks.add(checkRuntimeProjectionValid(pkg, target, canonicalEnv));
        checks.add(checkActiveRunSafe(pkg, target, canonicalEnv));
        checks.add(checkAgentAuditContext(caller, correlationId));

        return PreflightCheckResult.of(packageId, tenantId, canonicalEnv, targetId, checks, now);
    }

    // ------------------------------------------------------------------
    //  Individual check implementations
    // ------------------------------------------------------------------

    private PreflightCheckResult.CheckOutcome checkPackageCompleted(Package pkg) {
        if ("COMPLETED".equals(pkg.getBuildStatus())) {
            return PreflightCheckResult.CheckOutcome.pass(PreflightCheckCode.PACKAGE_COMPLETED);
        }
        return PreflightCheckResult.CheckOutcome.fail(
                PreflightCheckCode.PACKAGE_COMPLETED,
                "Package buildStatus is " + pkg.getBuildStatus() + " (expected COMPLETED).");
    }

    private PreflightCheckResult.CheckOutcome checkPackageProvenancePresent(Package pkg) {
        Map<String, Object> meta = pkg.getMetadata();
        if (meta == null) {
            return PreflightCheckResult.CheckOutcome.fail(
                    PreflightCheckCode.PACKAGE_PROVENANCE_PRESENT,
                    "Package has no metadata block; provenance missing.");
        }
        Object git = meta.get("git");
        if (!(git instanceof Map<?, ?> gitMap)) {
            return PreflightCheckResult.CheckOutcome.fail(
                    PreflightCheckCode.PACKAGE_PROVENANCE_PRESENT,
                    "metadata.git block is missing.");
        }
        boolean complete = nonBlank(gitMap.get("repoId"))
                && nonBlank(gitMap.get("branch"))
                && nonBlank(gitMap.get("commitSha"))
                && nonBlank(gitMap.get("treeSha"));
        if (!complete) {
            return PreflightCheckResult.CheckOutcome.fail(
                    PreflightCheckCode.PACKAGE_PROVENANCE_PRESENT,
                    "metadata.git block is incomplete (repoId, branch, commitSha, treeSha all required).");
        }
        return PreflightCheckResult.CheckOutcome.pass(PreflightCheckCode.PACKAGE_PROVENANCE_PRESENT);
    }

    private PreflightCheckResult.CheckOutcome checkPackageCleanForEnv(Package pkg, String env) {
        Map<String, Object> meta = pkg.getMetadata();
        Object status = meta == null ? null : meta.get("workingTreeStatus");
        if ("clean".equals(status)) {
            return PreflightCheckResult.CheckOutcome.pass(PreflightCheckCode.PACKAGE_CLEAN_FOR_ENV);
        }
        // Phase 4 policy: dev tolerates dirty (warning); higher envs hard block.
        if ("dirty".equals(status)) {
            if (env == null || "local".equals(env) || "dev".equals(env)) {
                return PreflightCheckResult.CheckOutcome.pass(
                        PreflightCheckCode.PACKAGE_CLEAN_FOR_ENV,
                        "Working tree dirty; dev policy allows.");
            }
            return PreflightCheckResult.CheckOutcome.fail(
                    PreflightCheckCode.PACKAGE_CLEAN_FOR_ENV,
                    "Working tree dirty; environment '" + env + "' requires clean tree.");
        }
        return PreflightCheckResult.CheckOutcome.fail(
                PreflightCheckCode.PACKAGE_CLEAN_FOR_ENV,
                "Working tree status is '" + status + "'; expected clean or (dev only) dirty.");
    }

    @SuppressWarnings("unchecked")
    private PreflightCheckResult.CheckOutcome checkStaticDeployability(Package pkg) {
        Map<String, Object> meta = pkg.getMetadata();
        Object assessment = meta == null ? null : meta.get("staticRuntimeAssessment");
        if (!(assessment instanceof Map<?, ?> assessmentMap)) {
            return PreflightCheckResult.CheckOutcome.fail(
                    PreflightCheckCode.STATIC_DEPLOYABILITY,
                    "Package metadata is missing staticRuntimeAssessment.");
        }
        Object blockers = assessmentMap.get("blockers");
        if (blockers instanceof List<?> blockerList && !blockerList.isEmpty()) {
            return PreflightCheckResult.CheckOutcome.fail(
                    PreflightCheckCode.STATIC_DEPLOYABILITY,
                    "Static assessment has " + blockerList.size() + " blocker(s).");
        }
        return PreflightCheckResult.CheckOutcome.pass(PreflightCheckCode.STATIC_DEPLOYABILITY);
    }

    @SuppressWarnings("unchecked")
    private PreflightCheckResult.CheckOutcome checkAirflowCallbackPolicy(Package pkg,
                                                                        DeploymentTarget target,
                                                                        String environment) {
        Map<String, Object> meta = pkg.getMetadata();
        Object manifest = meta == null ? null : meta.get("packageManifest");
        Map<String, Object> manifestMap = manifest instanceof Map<?, ?>
                ? (Map<String, Object>) manifest
                : Map.of();
        Object capability = manifestMap.get("capabilityProfile");
        Map<String, Object> capabilityProfile = capability instanceof Map<?, ?>
                ? new LinkedHashMap<>((Map<String, Object>) capability)
                : Map.of();
        String callbackPolicy = SourcePackageManifestV2.airflowCallbackPolicy(capabilityProfile);

        Object diagnostics = manifestMap.get("callbackPolicyDiagnostics");
        if (diagnostics instanceof Map<?, ?> diagnosticsMap) {
            Object violations = diagnosticsMap.get("violations");
            if (violations instanceof List<?> list && !list.isEmpty()) {
                return PreflightCheckResult.CheckOutcome.fail(
                        PreflightCheckCode.AIRFLOW_CALLBACK_POLICY_VALID,
                        "Package manifest contains " + list.size()
                                + " Airflow callback policy violation(s).",
                        Map.of("violations", list));
            }
        }

        if (SourcePackageManifestV2.isOptionalAirflowCallbackPolicy(capabilityProfile)
                && !SourcePackageManifestV2.isLocalOrDevEnvironment(environment)) {
            return PreflightCheckResult.CheckOutcome.fail(
                    PreflightCheckCode.AIRFLOW_CALLBACK_POLICY_VALID,
                    "Airflow callback policy OPTIONAL is not allowed for environment '" + environment + "'.");
        }

        var projectionOpt = target == null || environment == null
                ? java.util.Optional.<com.pulse.deploy.projection.model.RuntimeProjection>empty()
                : runtimeProjectionService.getActiveProjection(pkg.getId(), target.getId(), environment);
        if (projectionOpt.isPresent()) {
            var projection = projectionOpt.get();
            Object callbackProjection = projection.getResolvedEntrypoints() == null
                    ? null
                    : projection.getResolvedEntrypoints().get("airflowCallbacks");
            if (callbackProjection instanceof Map<?, ?> callbackMap) {
                if (SourcePackageManifestV2.AIRFLOW_CALLBACK_POLICY_DISABLED.equalsIgnoreCase(callbackPolicy)) {
                    return PreflightCheckResult.CheckOutcome.fail(
                            PreflightCheckCode.AIRFLOW_CALLBACK_POLICY_VALID,
                            "Runtime projection contains Airflow callback settings while callback policy is DISABLED.");
                }
                String callbackUrl = string(callbackMap.get("airflowCallbackUrl"));
                if (callbackUrl.contains("localhost") || callbackUrl.contains("127.0.0.1")) {
                    return PreflightCheckResult.CheckOutcome.fail(
                            PreflightCheckCode.AIRFLOW_CALLBACK_POLICY_VALID,
                            "Runtime projection contains a forbidden localhost Airflow callback URL.");
                }
                if (callbackUrl.contains("PULSE_API_URL")) {
                    return PreflightCheckResult.CheckOutcome.fail(
                            PreflightCheckCode.AIRFLOW_CALLBACK_POLICY_VALID,
                            "Runtime projection reuses PULSE_API_URL for Airflow callback delivery.");
                }
                if (!SourcePackageManifestV2.isLocalOrDevEnvironment(environment)) {
                    return PreflightCheckResult.CheckOutcome.fail(
                            PreflightCheckCode.AIRFLOW_CALLBACK_POLICY_VALID,
                            "Runtime projection contains Airflow callback delivery outside local/dev.");
                }
            }
            List<Map<String, Object>> blockers = projection.getReadinessBlockers();
            if (blockers != null) {
                List<Map<String, Object>> callbackBlockers = blockers.stream()
                        .filter(blocker -> string(blocker.get("code")).startsWith("AIRFLOW_CALLBACK_"))
                        .toList();
                if (!callbackBlockers.isEmpty()) {
                    return PreflightCheckResult.CheckOutcome.fail(
                            PreflightCheckCode.AIRFLOW_CALLBACK_POLICY_VALID,
                            "Runtime projection contains " + callbackBlockers.size()
                                    + " Airflow callback policy blocker(s).",
                            Map.of("readinessBlockers", callbackBlockers));
                }
            }
        }

        return PreflightCheckResult.CheckOutcome.pass(PreflightCheckCode.AIRFLOW_CALLBACK_POLICY_VALID);
    }

    private PreflightCheckResult.CheckOutcome checkTargetExists(DeploymentTarget target, String targetId) {
        if (target == null) {
            return PreflightCheckResult.CheckOutcome.fail(
                    PreflightCheckCode.TARGET_EXISTS,
                    "Deployment target " + targetId + " not found.");
        }
        return PreflightCheckResult.CheckOutcome.pass(PreflightCheckCode.TARGET_EXISTS);
    }

    private PreflightCheckResult.CheckOutcome checkTargetEnabled(DeploymentTarget target) {
        if (target == null) {
            // Already failed at TARGET_EXISTS; surface as a fail here too
            // so per-check consumers don't need to cross-reference.
            return PreflightCheckResult.CheckOutcome.fail(
                    PreflightCheckCode.TARGET_ENABLED,
                    "Target unavailable; cannot evaluate enabled state.");
        }
        if (!target.isEnabled()) {
            return PreflightCheckResult.CheckOutcome.fail(
                    PreflightCheckCode.TARGET_ENABLED,
                    "Target " + target.getId() + " is disabled.");
        }
        return PreflightCheckResult.CheckOutcome.pass(PreflightCheckCode.TARGET_ENABLED);
    }

    private PreflightCheckResult.CheckOutcome checkStorageBackendValidated(Package pkg, String env) {
        if (env == null) {
            return PreflightCheckResult.CheckOutcome.fail(
                    PreflightCheckCode.STORAGE_BACKEND_VALIDATED,
                    "Cannot evaluate storage backends without a resolved environment.");
        }
        StorageBackendDeployGate.Result gate = storageGate.check(pkg.getPipelineId(), env);
        if (gate.ok()) {
            return PreflightCheckResult.CheckOutcome.pass(PreflightCheckCode.STORAGE_BACKEND_VALIDATED);
        }
        return PreflightCheckResult.CheckOutcome.fail(
                PreflightCheckCode.STORAGE_BACKEND_VALIDATED, gate.reason());
    }

    private PreflightCheckResult.CheckOutcome checkCredentialReadiness(Package pkg, String env) {
        if (env == null) {
            return PreflightCheckResult.CheckOutcome.fail(
                    PreflightCheckCode.CREDENTIAL_READINESS,
                    "Cannot evaluate credentials without a resolved environment.");
        }
        Map<String, Object> readiness;
        try {
            readiness = credentialReadinessService.compute(pkg.getPipelineId(), env);
        } catch (RuntimeException e) {
            return PreflightCheckResult.CheckOutcome.fail(
                    PreflightCheckCode.CREDENTIAL_READINESS,
                    "Credential readiness probe failed: " + e.getMessage());
        }
        Object ready = readiness.get("ready");
        if (Boolean.TRUE.equals(ready)) {
            return PreflightCheckResult.CheckOutcome.pass(PreflightCheckCode.CREDENTIAL_READINESS);
        }
        return PreflightCheckResult.CheckOutcome.fail(
                PreflightCheckCode.CREDENTIAL_READINESS,
                "CredentialReadinessService reports ready=false for env '" + env + "'.");
    }

    private PreflightCheckResult.CheckOutcome checkApprovalPolicy(Package pkg, String env) {
        // Phase 4 policy default: approval is required for uat + prod.
        // Higher-fidelity per-tenant approval policy is Phase 6+ work.
        if (env == null || "local".equals(env) || "dev".equals(env) || "integration".equals(env)) {
            return PreflightCheckResult.CheckOutcome.pass(
                    PreflightCheckCode.APPROVAL_POLICY,
                    "Approval not required for env '" + env + "'.");
        }
        // For Phase 4 we don't yet attach approvals at the package level;
        // surface as a warning (PASS with message) so the gate doesn't
        // block existing flows but the absence is visible. Phase 6 will
        // introduce ApprovalPolicyService.
        return PreflightCheckResult.CheckOutcome.pass(
                PreflightCheckCode.APPROVAL_POLICY,
                "Phase 4 deferred — Phase 6 will hard-gate approval-required envs.");
    }

    /**
     * Phase 7 — RuntimeCapabilityMatrix decision for the
     * (target, requested table format) pair. A {@code rejected}
     * result blocks; a {@code fallback} result blocks unless the
     * package has an explicit operator-acknowledged fallback hint
     * (matrix v1: not yet exposed, so any fallback blocks).
     */
    @SuppressWarnings("unchecked")
    private PreflightCheckResult.CheckOutcome checkRuntimeCapabilityOk(Package pkg, DeploymentTarget target) {
        if (target == null) {
            return PreflightCheckResult.CheckOutcome.fail(
                    PreflightCheckCode.RUNTIME_CAPABILITY_OK,
                    "Target unavailable; cannot consult RuntimeCapabilityMatrix.");
        }
        String targetType = target.getTargetType();
        String requestedFormat = readRequestedTableFormat(pkg);
        boolean dpcIcebergSupported = readDpcIcebergSupported(target);
        CapabilityCheckResult outcome = capabilityMatrix.evaluate(
                new RuntimeCapabilityMatrix.Request(targetType, requestedFormat, dpcIcebergSupported));
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("matrix", outcome.toCanonicalJson());
        if (!outcome.approved()) {
            return PreflightCheckResult.CheckOutcome.fail(
                    PreflightCheckCode.RUNTIME_CAPABILITY_OK,
                    "Capability matrix rejected '" + requestedFormat + "' on '" + targetType
                            + "': " + String.join("; ", outcome.reasons()),
                    evidence);
        }
        if (outcome.fallbackMode() != null) {
            // Fallbacks require explicit operator approval per the plan's
            // §Iceberg Portability section. Matrix v1 has no UI hook for
            // that approval yet — so a fallback always blocks.
            return PreflightCheckResult.CheckOutcome.fail(
                    PreflightCheckCode.RUNTIME_CAPABILITY_OK,
                    "Capability matrix applied fallback '" + outcome.fallbackMode()
                            + "' for '" + requestedFormat + "' on '" + targetType
                            + "'; explicit operator approval required (matrix v1 has no UI hook).",
                    evidence);
        }
        return new PreflightCheckResult.CheckOutcome(
                PreflightCheckCode.RUNTIME_CAPABILITY_OK.name(),
                PreflightCheckResult.PASS,
                "Capability matrix approved '" + requestedFormat + "' on '" + targetType + "'.",
                evidence);
    }

    @SuppressWarnings("unchecked")
    private static String readRequestedTableFormat(Package pkg) {
        Map<String, Object> meta = pkg.getMetadata();
        if (meta == null) return RuntimeCapabilityMatrix.FORMAT_UNSPECIFIED;
        Object manifest = meta.get("packageManifest");
        if (manifest instanceof Map<?, ?> mm) {
            Object capability = mm.get("capabilityProfile");
            if (capability instanceof Map<?, ?> cm) {
                Object format = cm.get("tableFormat");
                if (format instanceof String s && !s.isBlank()) return s;
            }
        }
        // Fall back to a flat alias on metadata if a future builder
        // wants to expose it directly.
        Object flat = meta.get("requestedTableFormat");
        if (flat instanceof String s && !s.isBlank()) return s;
        return RuntimeCapabilityMatrix.FORMAT_UNSPECIFIED;
    }

    private static boolean readDpcIcebergSupported(DeploymentTarget target) {
        Map<String, Object> config = target.getConfig();
        if (config == null) return false;
        Object hint = config.get("dpcIcebergSupported");
        return Boolean.TRUE.equals(hint);
    }

    private PreflightCheckResult.CheckOutcome checkRuntimeCapability(Package pkg) {
        // Phase 2 records a capabilityProfile inside the manifest. Phase 4
        // only verifies presence; Phase 7 wires real adapter compatibility.
        Map<String, Object> meta = pkg.getMetadata();
        Object manifest = meta == null ? null : meta.get("packageManifest");
        if (manifest instanceof Map<?, ?> manifestMap) {
            Object capability = manifestMap.get("capabilityProfile");
            if (capability != null) {
                return PreflightCheckResult.CheckOutcome.pass(PreflightCheckCode.RUNTIME_CAPABILITY);
            }
        }
        return PreflightCheckResult.CheckOutcome.pass(
                PreflightCheckCode.RUNTIME_CAPABILITY,
                "Capability profile not yet recorded; Phase 7 will tighten this check.");
    }

    /**
     * Phase 4 closeout — target adapter schema validation.
     *
     * <p>Phase 7 will wire each adapter's typed schema. For Phase 4 we
     * verify the target's {@code targetType} is one of the known
     * legacy / planned adapter types so an obviously-malformed row is
     * still surfaced as a blocker.
     */
    private PreflightCheckResult.CheckOutcome checkTargetSchemaValid(DeploymentTarget target) {
        if (target == null) {
            return PreflightCheckResult.CheckOutcome.fail(
                    PreflightCheckCode.TARGET_SCHEMA_VALID,
                    "Target unavailable; cannot evaluate adapter schema.");
        }
        Set<String> known = Set.of(
                // Legacy seeded values.
                "KUBERNETES", "AIRFLOW", "DATABRICKS", "EMR", "DATAPROC",
                // Phase 7 planned adapter values.
                "LOCAL_MATERIALIZATION",
                "GCP_COMPOSER_DATAPROC",
                "DPC_AIRFLOW_OPENSHIFT_SPARK");
        String type = target.getTargetType();
        if (type == null || type.isBlank()) {
            return PreflightCheckResult.CheckOutcome.fail(
                    PreflightCheckCode.TARGET_SCHEMA_VALID,
                    "Target " + target.getId() + " has no targetType.");
        }
        if (!known.contains(type)) {
            return PreflightCheckResult.CheckOutcome.fail(
                    PreflightCheckCode.TARGET_SCHEMA_VALID,
                    "Unknown targetType '" + type + "' for target " + target.getId()
                            + "; expected one of " + known + ".");
        }
        return PreflightCheckResult.CheckOutcome.pass(
                PreflightCheckCode.TARGET_SCHEMA_VALID,
                "Phase 4 minimum schema check; per-adapter schema validation lands in Phase 7.");
    }

    /**
     * Phase 4 closeout — packages must reference secrets, never carry
     * raw values. Heuristic scan over package metadata: any string
     * value whose key looks secret-shaped must already be a
     * recognized secret reference (gcp-sm:// or vault://) or be
     * masked. Any plaintext-looking secret triggers a blocker so the
     * package cannot ship secrets in its metadata.
     */
    @SuppressWarnings("unchecked")
    private PreflightCheckResult.CheckOutcome checkSecretReferencesOnly(Package pkg) {
        Map<String, Object> meta = pkg.getMetadata();
        if (meta == null || meta.isEmpty()) {
            return PreflightCheckResult.CheckOutcome.pass(PreflightCheckCode.SECRET_REFERENCES_ONLY);
        }
        java.util.List<String> offenders = new ArrayList<>();
        scanForPlaintextSecrets(meta, "metadata", offenders);
        if (offenders.isEmpty()) {
            return PreflightCheckResult.CheckOutcome.pass(PreflightCheckCode.SECRET_REFERENCES_ONLY);
        }
        String detail = offenders.size() > 5
                ? offenders.subList(0, 5) + " (+" + (offenders.size() - 5) + " more)"
                : offenders.toString();
        return PreflightCheckResult.CheckOutcome.fail(
                PreflightCheckCode.SECRET_REFERENCES_ONLY,
                "Package metadata contains plaintext-looking secrets at " + detail
                        + "; package may carry only gcp-sm:// or vault:// references.");
    }

    /**
     * Phase 6 — branch-policy check uses {@link BranchAllowlistPolicy}
     * to verify the package's git branch is allowed for the target
     * environment.
     */
    @SuppressWarnings("unchecked")
    private PreflightCheckResult.CheckOutcome checkGitBranchAllowed(Package pkg, String env) {
        Map<String, Object> meta = pkg.getMetadata();
        Object git = meta == null ? null : meta.get("git");
        if (!(git instanceof Map<?, ?> gitMap)) {
            return PreflightCheckResult.CheckOutcome.fail(
                    PreflightCheckCode.GIT_BRANCH_ALLOWED,
                    "Cannot evaluate branch policy: metadata.git is missing.");
        }
        Object branch = gitMap.get("branch");
        if (!nonBlank(branch)) {
            return PreflightCheckResult.CheckOutcome.fail(
                    PreflightCheckCode.GIT_BRANCH_ALLOWED,
                    "Cannot evaluate branch policy: metadata.git.branch is blank.");
        }
        BranchAllowlistPolicy.Outcome outcome = branchAllowlistPolicy.evaluate(
                String.valueOf(branch), env);
        if (!outcome.allowed()) {
            return PreflightCheckResult.CheckOutcome.fail(
                    PreflightCheckCode.GIT_BRANCH_ALLOWED, outcome.reason());
        }
        return PreflightCheckResult.CheckOutcome.pass(PreflightCheckCode.GIT_BRANCH_ALLOWED);
    }

    /**
     * Phase 6 — PR-policy check.
     *
     * <p>Default per-env policy:
     * <ul>
     *   <li>{@code local}, {@code dev} — PR not required.</li>
     *   <li>{@code integration} — at least one OPEN or MERGED PR exists
     *       for the package's pipeline version.</li>
     *   <li>{@code uat}, {@code prod} — at least one MERGED PR exists
     *       for the package's pipeline version.</li>
     * </ul>
     *
     * <p>Per-tenant overrides are a later sweep; this baseline already
     * exercises the {@link PullRequestRepository} surface so Phase 6
     * + Phase 4 wire end-to-end.
     */
    private PreflightCheckResult.CheckOutcome checkPrPolicy(Package pkg, String env) {
        if (env == null || "local".equals(env) || "dev".equals(env)) {
            return PreflightCheckResult.CheckOutcome.pass(
                    PreflightCheckCode.PR_POLICY,
                    "PR policy not required for env '" + env + "'.");
        }
        String versionId = pkg.getVersionId();
        if (versionId == null || versionId.isBlank()) {
            return PreflightCheckResult.CheckOutcome.fail(
                    PreflightCheckCode.PR_POLICY,
                    "Cannot evaluate PR policy: package has no versionId.");
        }
        List<PullRequest> prs = pullRequestRepository.findByVersionIdOrderByCreatedAtDesc(versionId);
        boolean requiresMerged = "uat".equals(env) || "prod".equals(env);
        if (requiresMerged) {
            boolean mergedPrExists = prs.stream().anyMatch(pr -> "MERGED".equalsIgnoreCase(pr.getStatus()));
            if (!mergedPrExists) {
                return PreflightCheckResult.CheckOutcome.fail(
                        PreflightCheckCode.PR_POLICY,
                        "Env '" + env + "' requires a MERGED PR for versionId="
                                + versionId + "; none found.");
            }
            var acceptance = acceptanceRepository
                    .findFirstByVersionIdAndAcceptanceStatusOrderByCreatedAtDesc(versionId, "ACTIVE");
            if (acceptance.isEmpty()) {
                return PreflightCheckResult.CheckOutcome.fail(
                        PreflightCheckCode.PR_POLICY,
                        "Env '" + env + "' requires active exact-head version acceptance for versionId="
                                + versionId + "; none found.");
            }
            var accepted = acceptance.get();
            if (!java.util.Objects.equals(accepted.getAcceptedPackageId(), pkg.getId())) {
                return PreflightCheckResult.CheckOutcome.fail(
                        PreflightCheckCode.PR_POLICY,
                        "Env '" + env + "' requires the selected package to match active version acceptance.");
            }
            if (!java.util.Objects.equals(accepted.getAcceptedCommitSha(), pkg.getCommitSha())
                    || !java.util.Objects.equals(accepted.getAcceptedTreeSha(), pkg.getTreeSha())) {
                return PreflightCheckResult.CheckOutcome.fail(
                        PreflightCheckCode.PR_POLICY,
                        "Accepted commit/tree evidence does not match selected package.");
            }
            return PreflightCheckResult.CheckOutcome.pass(PreflightCheckCode.PR_POLICY);
        }
        // integration — open OR merged is sufficient.
        boolean anyEligible = prs.stream().anyMatch(pr ->
                "OPEN".equalsIgnoreCase(pr.getStatus()) || "MERGED".equalsIgnoreCase(pr.getStatus()));
        if (!anyEligible) {
            return PreflightCheckResult.CheckOutcome.fail(
                    PreflightCheckCode.PR_POLICY,
                    "Env '" + env + "' requires an OPEN or MERGED PR for versionId="
                            + versionId + "; none found.");
        }
        return PreflightCheckResult.CheckOutcome.pass(PreflightCheckCode.PR_POLICY);
    }

    @SuppressWarnings("unchecked")
    private PreflightCheckResult.CheckOutcome checkRuntimeAuthorityPersonaMatch(Package pkg) {
        Map<String, Object> meta = pkg.getMetadata();
        if (meta == null) {
            return PreflightCheckResult.CheckOutcome.pass(
                    PreflightCheckCode.RUNTIME_AUTHORITY_PERSONA_MATCH,
                    "No metadata; legacy package — persona check deferred.");
        }
        Object manifest = meta.get("packageManifest");
        if (!(manifest instanceof Map<?, ?> manifestMap)) {
            return PreflightCheckResult.CheckOutcome.pass(
                    PreflightCheckCode.RUNTIME_AUTHORITY_PERSONA_MATCH,
                    "No packageManifest; legacy package — persona check deferred.");
        }
        Object runtimeAuth = manifestMap.get("runtimeAuthority");
        if (!(runtimeAuth instanceof Map<?, ?> authMap)) {
            return PreflightCheckResult.CheckOutcome.pass(
                    PreflightCheckCode.RUNTIME_AUTHORITY_PERSONA_MATCH,
                    "No runtimeAuthority block in manifest; legacy package.");
        }
        Object packagePersona = authMap.get("activePersona");
        String activePersona = runtimeAuthorityService.getActivePersona().name();
        if (packagePersona instanceof String pp && pp.equals(activePersona)) {
            return PreflightCheckResult.CheckOutcome.pass(
                    PreflightCheckCode.RUNTIME_AUTHORITY_PERSONA_MATCH);
        }
        return PreflightCheckResult.CheckOutcome.fail(
                PreflightCheckCode.RUNTIME_AUTHORITY_PERSONA_MATCH,
                "Package persona '" + packagePersona + "' does not match active deployment persona '"
                        + activePersona + "'.");
    }

    private PreflightCheckResult.CheckOutcome checkTargetTypePersonaLegal(DeploymentTarget target) {
        if (target == null) {
            return PreflightCheckResult.CheckOutcome.fail(
                    PreflightCheckCode.TARGET_TYPE_PERSONA_LEGAL,
                    "Target unavailable; cannot evaluate persona legality.");
        }
        String targetType = target.getTargetType();
        if (runtimeAuthorityService.isTargetTypeAllowed(targetType)) {
            return PreflightCheckResult.CheckOutcome.pass(
                    PreflightCheckCode.TARGET_TYPE_PERSONA_LEGAL);
        }
        return PreflightCheckResult.CheckOutcome.fail(
                PreflightCheckCode.TARGET_TYPE_PERSONA_LEGAL,
                "Target type '" + targetType + "' is not allowed for persona "
                        + runtimeAuthorityService.getActivePersona() + ".");
    }

    /**
     * Phase 4 closeout — caller identity / source surface / correlation
     * id are present in the audit record being created. The
     * controller passes the resolved CallerContext + correlation id;
     * the check fails when called outside a caller-aware path
     * (defensive — Phase 4 controller flow always supplies caller).
     */
    private PreflightCheckResult.CheckOutcome checkTableContractsPresent(Package pkg) {
        if (pkg.getVersionId() == null || pkg.getVersionId().isBlank()) {
            return PreflightCheckResult.CheckOutcome.fail(
                    PreflightCheckCode.TABLE_CONTRACTS_PRESENT,
                    "Package version ID missing; cannot check table contracts.");
        }
        List<TableContract> active = tableContractService.findActiveContracts(pkg.getVersionId());
        if (active.isEmpty()) {
            return PreflightCheckResult.CheckOutcome.pass(PreflightCheckCode.TABLE_CONTRACTS_PRESENT);
        }
        boolean anyStale = active.stream().anyMatch(c -> "stale".equals(c.getStatus()));
        if (anyStale) {
            return PreflightCheckResult.CheckOutcome.fail(
                    PreflightCheckCode.TABLE_CONTRACTS_PRESENT,
                    "Stale table contracts detected for version " + pkg.getVersionId()
                            + "; regenerate contracts before deploy.");
        }
        return PreflightCheckResult.CheckOutcome.pass(PreflightCheckCode.TABLE_CONTRACTS_PRESENT);
    }

    private PreflightCheckResult.CheckOutcome checkRuntimeProjectionValid(
            Package pkg, DeploymentTarget target, String environment) {
        if (target == null || environment == null) {
            return PreflightCheckResult.CheckOutcome.pass(PreflightCheckCode.RUNTIME_PROJECTION_VALID);
        }

        // PKT-0023: If the package has table contracts, a runtime projection
        // is required for physical-design authority. Legacy packages without
        // contracts are exempted.
        List<TableContract> contracts = pkg.getVersionId() != null
                ? tableContractService.findActiveContracts(pkg.getVersionId())
                : List.of();
        boolean hasContracts = !contracts.isEmpty();

        var projectionOpt = runtimeProjectionService.getActiveProjection(
                pkg.getId(), target.getId(), environment);
        if (projectionOpt.isEmpty()) {
            if (hasContracts) {
                return PreflightCheckResult.CheckOutcome.fail(
                        PreflightCheckCode.RUNTIME_PROJECTION_VALID,
                        "Package has " + contracts.size()
                                + " active table contract(s) but no runtime projection; "
                                + "physical design cannot be verified without projection.");
            }
            if (hasBrokerInvocations(pkg) || hasTimeStateBindings(pkg)) {
                return PreflightCheckResult.CheckOutcome.fail(
                        PreflightCheckCode.RUNTIME_PROJECTION_VALID,
                        "Package contains projected runtime entrypoints but has no active runtime projection.");
            }
            return PreflightCheckResult.CheckOutcome.pass(PreflightCheckCode.RUNTIME_PROJECTION_VALID);
        }
        var projection = projectionOpt.get();
        List<Map<String, Object>> blockers = projection.getReadinessBlockers();
        if (blockers != null && !blockers.isEmpty()) {
            return PreflightCheckResult.CheckOutcome.fail(
                    PreflightCheckCode.RUNTIME_PROJECTION_VALID,
                    "Runtime projection has " + blockers.size() + " readiness blocker(s).",
                    Map.of("readinessBlockers", blockers));
        }
        if (hasBrokerInvocations(pkg) && !hasBrokerEdgeBindings(projection.getResolvedEntrypoints())) {
            return PreflightCheckResult.CheckOutcome.fail(
                    PreflightCheckCode.RUNTIME_PROJECTION_VALID,
                    "Package contains broker invocations but runtime projection has no broker edge bindings.");
        }
        if (hasTimeStateBindings(pkg) && !hasResolvedTimeStateBindings(projection.getResolvedEntrypoints())) {
            return PreflightCheckResult.CheckOutcome.fail(
                    PreflightCheckCode.RUNTIME_PROJECTION_VALID,
                    "Package contains AdvanceTimeDimension bindings but runtime projection has no resolved time-state bindings.");
        }
        var drift = runtimeProjectionService.checkDrift(projection.getId());
        if (drift.drifted()) {
            return PreflightCheckResult.CheckOutcome.fail(
                    PreflightCheckCode.RUNTIME_PROJECTION_VALID,
                    "Runtime projection has drifted (stored=" + drift.storedHash()
                            + ", current=" + drift.currentHash()
                            + "); refresh projection before deploy.");
        }
        return PreflightCheckResult.CheckOutcome.pass(PreflightCheckCode.RUNTIME_PROJECTION_VALID);
    }

    @SuppressWarnings("unchecked")
    private static boolean hasBrokerInvocations(Package pkg) {
        Map<String, Object> meta = pkg.getMetadata();
        Object manifest = meta == null ? null : meta.get("packageManifest");
        if (!(manifest instanceof Map<?, ?> manifestMap)) return false;
        Object catalog = manifestMap.get("entrypointCatalog");
        if (!(catalog instanceof Map<?, ?> catalogMap)) return false;
        Object invocations = catalogMap.get("brokerInvocations");
        return invocations instanceof List<?> list && !list.isEmpty();
    }

    private static boolean hasBrokerEdgeBindings(Map<String, Object> resolvedEntrypoints) {
        if (resolvedEntrypoints == null) return false;
        Object bindings = resolvedEntrypoints.get("brokerEdgeBindings");
        return bindings instanceof List<?> list && !list.isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static boolean hasTimeStateBindings(Package pkg) {
        Map<String, Object> meta = pkg.getMetadata();
        Object manifest = meta == null ? null : meta.get("packageManifest");
        if (!(manifest instanceof Map<?, ?> manifestMap)) return false;
        Object catalog = manifestMap.get("entrypointCatalog");
        if (!(catalog instanceof Map<?, ?> catalogMap)) return false;
        Object bindings = catalogMap.get("timeStateBindings");
        return bindings instanceof List<?> list && !list.isEmpty();
    }

    private static boolean hasResolvedTimeStateBindings(Map<String, Object> resolvedEntrypoints) {
        if (resolvedEntrypoints == null) return false;
        Object bindings = resolvedEntrypoints.get("timeStateBindings");
        return bindings instanceof List<?> list && !list.isEmpty();
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private PreflightCheckResult.CheckOutcome checkActiveRunSafe(
            Package pkg, DeploymentTarget target, String environment) {
        // ARCH-007: Active-run guard. In production, this queries the Airflow/Composer
        // API for active DAG runs. Locally, without live Airflow, this passes by default.
        // The check fails closed when the runtime client cannot answer.
        if (target == null || environment == null) {
            return PreflightCheckResult.CheckOutcome.pass(PreflightCheckCode.ACTIVE_RUN_SAFE);
        }
        // Resolve logical DAG ID from pipeline metadata on the package
        String logicalDagId = resolveLogicalDagIdFromPackage(pkg);
        if (logicalDagId == null || logicalDagId.isBlank()) {
            return PreflightCheckResult.CheckOutcome.pass(PreflightCheckCode.ACTIVE_RUN_SAFE);
        }
        try {
            boolean hasActiveRun = airflowRuntimeClient.isActiveRunPresent(
                    logicalDagId, target.getId(), environment);
            if (hasActiveRun) {
                return PreflightCheckResult.CheckOutcome.fail(
                        PreflightCheckCode.ACTIVE_RUN_SAFE,
                        "Active Airflow run exists for DAG '" + logicalDagId
                                + "' on target " + target.getId()
                                + "; wait for completion or cancel before redeploying.");
            }
            return PreflightCheckResult.CheckOutcome.pass(PreflightCheckCode.ACTIVE_RUN_SAFE);
        } catch (Exception e) {
            return PreflightCheckResult.CheckOutcome.fail(
                    PreflightCheckCode.ACTIVE_RUN_SAFE,
                    "Cannot verify active run status for DAG '" + logicalDagId
                            + "': " + e.getMessage()
                            + ". Failing closed per ARCH-007 safety policy.");
        }
    }

    @SuppressWarnings("unchecked")
    private String resolveLogicalDagIdFromPackage(Package pkg) {
        if (pkg.getMetadata() == null) return null;
        Object manifest = pkg.getMetadata().get("sourcePackageManifestVersion");
        if (manifest == null) return null;
        Object entrypoint = pkg.getMetadata().get("entrypointCatalog");
        if (entrypoint instanceof Map<?, ?> ep) {
            Object dags = ep.get("dagEntrypoints");
            if (dags instanceof List<?> dagList && !dagList.isEmpty()) {
                Object first = dagList.get(0);
                if (first instanceof Map<?, ?> dagMap) {
                    Object dagId = dagMap.get("logicalDagId");
                    if (dagId != null) return dagId.toString();
                }
            }
        }
        return null;
    }

    private PreflightCheckResult.CheckOutcome checkAgentAuditContext(CallerContext caller, String correlationId) {
        if (caller == null || caller.userId() == null || caller.userId().isBlank()) {
            return PreflightCheckResult.CheckOutcome.fail(
                    PreflightCheckCode.AGENT_AUDIT_CONTEXT,
                    "No caller context resolved; deploy event would be unattributable.");
        }
        if (caller.surface() == null) {
            return PreflightCheckResult.CheckOutcome.fail(
                    PreflightCheckCode.AGENT_AUDIT_CONTEXT,
                    "Caller surface unset; cannot attribute deploy event to UI / AGENT / SYSTEM.");
        }
        if (correlationId == null || correlationId.isBlank()) {
            return PreflightCheckResult.CheckOutcome.fail(
                    PreflightCheckCode.AGENT_AUDIT_CONTEXT,
                    "No correlation id supplied; downstream events would not be replayable.");
        }
        return PreflightCheckResult.CheckOutcome.pass(PreflightCheckCode.AGENT_AUDIT_CONTEXT);
    }

    /**
     * Walks a JSON-shaped metadata tree and collects key-paths whose
     * value is a non-reference plaintext secret. {@code key} is
     * "secret-shaped" when it contains password/secret/token/api_key
     * tokens; the value is "plaintext" when it doesn't start with
     * a recognized reference scheme.
     */
    @SuppressWarnings("unchecked")
    private static void scanForPlaintextSecrets(Object node, String path, java.util.List<String> offenders) {
        if (node instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object value = entry.getValue();
                String childPath = path + "." + key;
                if (value instanceof String stringValue && !stringValue.isBlank()) {
                    if (isSecretShapedKey(key) && !isSecretReference(stringValue) && !isMasked(stringValue)) {
                        offenders.add(childPath);
                    }
                }
                scanForPlaintextSecrets(value, childPath, offenders);
            }
        } else if (node instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                scanForPlaintextSecrets(list.get(i), path + "[" + i + "]", offenders);
            }
        }
    }

    private static boolean isSecretShapedKey(String key) {
        if (key == null) return false;
        String lower = key.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("password")
                || lower.contains("secret")
                || lower.endsWith("_token") || lower.equals("token")
                || lower.equals("api_key") || lower.equals("private_key")
                || lower.equals("refresh_token") || lower.equals("credentials_json")
                || lower.equals("service_account") || lower.equals("sasl_password")
                || lower.equals("client_secret");
    }

    private static boolean isSecretReference(String value) {
        return value.startsWith("gcp-sm://") || value.startsWith("vault://");
    }

    private static boolean isMasked(String value) {
        // Standard Pulse mask used by sanitize() across the credential layer.
        return "••••••••".equals(value);
    }

    private static boolean nonBlank(Object value) {
        return value instanceof String s && !s.isBlank();
    }

    private static String safeNormalize(String env) {
        if (env == null || env.isBlank()) {
            return null;
        }
        try {
            return DeploymentEnvironment.normalize(env);
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }
}
