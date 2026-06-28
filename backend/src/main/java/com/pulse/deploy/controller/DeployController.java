package com.pulse.deploy.controller;

import com.pulse.auth.policy.ActionContext;
import com.pulse.auth.policy.ActorResolverService;
import com.pulse.auth.policy.AuthorizationPolicyService;
import com.pulse.auth.policy.CallerContext;
import com.pulse.auth.policy.CallerSurface;
import com.pulse.auth.policy.PolicyDecision;
import com.pulse.auth.policy.PulseAction;
import com.pulse.codegen.repository.GeneratedArtifactRepository;
import com.pulse.codegen.repository.GenerationRunRepository;
import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.deploy.environment.DeploymentEnvironment;
import com.pulse.deploy.model.ApprovalRequest;
import com.pulse.deploy.model.Deployment;
import com.pulse.deploy.model.DeploymentTarget;
import com.pulse.deploy.model.Package;
import com.pulse.deploy.repository.ApprovalRequestRepository;
import com.pulse.deploy.repository.DeploymentRepository;
import com.pulse.deploy.repository.DeploymentTargetRepository;
import com.pulse.deploy.evidence.DeploymentEvidenceService;
import com.pulse.deploy.evidence.RuntimeEvidenceEnvelope;
import com.pulse.deploy.evidence.RuntimeEvidenceService;
import com.pulse.deploy.model.DeploymentRun;
import com.pulse.deploy.orchestrator.DeploymentRunOrchestrator;
import com.pulse.deploy.preflight.DeploymentPreflightService;
import com.pulse.deploy.preflight.PreflightCheckResult;
import com.pulse.deploy.repository.DeploymentRunRepository;
import com.pulse.deploy.repository.PackageRepository;
import com.pulse.deploy.boundary.DeployBoundaryReadback;
import com.pulse.deploy.boundary.DeployBoundaryService;
import com.pulse.deploy.run.DeploymentRunState;
import com.pulse.deploy.service.PackageService;
import com.pulse.runtime.service.RuntimeAuthorityService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@RestController
public class DeployController {

    private final PackageRepository packageRepo;
    private final DeploymentRepository deployRepo;
    private final DeploymentTargetRepository targetRepo;
    private final ApprovalRequestRepository approvalRepo;
    private final GenerationRunRepository generationRunRepository;
    private final GeneratedArtifactRepository generatedArtifactRepository;
    private final com.pulse.storage.StorageBackendDeployGate deployGate;
    private final PackageService packageService;
    private final AuthorizationPolicyService authPolicy;
    private final ActorResolverService actorResolver;
    private final DeploymentPreflightService preflightService;
    private final DeploymentRunRepository deploymentRunRepository;
    private final DeploymentEvidenceService evidenceService;
    private final DeploymentRunOrchestrator orchestrator;
    private final RuntimeAuthorityService runtimeAuthorityService;
    private final RuntimeEvidenceService runtimeEvidenceService;
    private final DeployBoundaryService deployBoundaryService;

    public DeployController(PackageRepository packageRepo, DeploymentRepository deployRepo,
                             DeploymentTargetRepository targetRepo,
                             ApprovalRequestRepository approvalRepo,
                             GenerationRunRepository generationRunRepository,
                             GeneratedArtifactRepository generatedArtifactRepository,
                             com.pulse.storage.StorageBackendDeployGate deployGate,
                             PackageService packageService,
                             AuthorizationPolicyService authPolicy,
                             ActorResolverService actorResolver,
                             DeploymentPreflightService preflightService,
                             DeploymentRunRepository deploymentRunRepository,
                             DeploymentEvidenceService evidenceService,
                             DeploymentRunOrchestrator orchestrator,
                             RuntimeAuthorityService runtimeAuthorityService,
                             RuntimeEvidenceService runtimeEvidenceService,
                             DeployBoundaryService deployBoundaryService) {
        this.packageRepo = packageRepo;
        this.deployRepo = deployRepo;
        this.targetRepo = targetRepo;
        this.approvalRepo = approvalRepo;
        this.generationRunRepository = generationRunRepository;
        this.generatedArtifactRepository = generatedArtifactRepository;
        this.deployGate = deployGate;
        this.packageService = packageService;
        this.authPolicy = authPolicy;
        this.actorResolver = actorResolver;
        this.preflightService = preflightService;
        this.deploymentRunRepository = deploymentRunRepository;
        this.evidenceService = evidenceService;
        this.orchestrator = orchestrator;
        this.runtimeAuthorityService = runtimeAuthorityService;
        this.runtimeEvidenceService = runtimeEvidenceService;
        this.deployBoundaryService = deployBoundaryService;
    }

    /**
     * Phase 3: deny → stable 403 with the policy reason code as the
     * status reason. Returns the resolved {@link CallerContext} on
     * success so mutation methods can stamp audit fields ({@code builtBy},
     * {@code deployedBy}, {@code approvedBy}, etc.) from
     * {@link CallerContext#userId()} instead of trusting the request body.
     * UI and AGENT surfaces both route through this helper so the same
     * policy gate fires on both code paths.
     */
    private CallerContext enforce(CallerSurface surface,
                                  PulseAction action,
                                  String tenantId,
                                  String environment) {
        CallerContext caller = actorResolver.resolve(surface, tenantId);
        ActionContext target = environment == null
                ? ActionContext.forTenant(tenantId)
                : ActionContext.forTenantAndEnv(tenantId, environment);
        PolicyDecision decision = authPolicy.check(caller, action, target);
        if (!decision.allowed()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, decision.denyReason());
        }
        return caller;
    }

    @PostMapping("/api/v1/versions/{versionId}/packages")
    public ResponseEntity<Package> buildPackage(@PathVariable String versionId, @RequestBody BuildRequest req) {
        var run = generationRunRepository.findTopByVersionIdOrderByCreatedAtDesc(versionId)
                .orElseThrow(() -> new ResourceNotFoundException("GenerationRun for version", versionId));
        // Phase 3: PACKAGE_BUILD policy gate fires BEFORE any
        // packageService / packageRepo mutation. tenantId comes from the
        // resolved generation run, not the request body, so tenant
        // scoping is server-derived. The returned CallerContext drives
        // every audit field below — request-body userId is ignored.
        CallerContext caller = enforce(CallerSurface.UI, PulseAction.PACKAGE_BUILD,
                run.getTenantId(), null);
        var artifacts = generatedArtifactRepository.findByGenerationRunIdOrderByFilePathAsc(run.getId());
        if (artifacts.isEmpty()) {
            throw new ResourceNotFoundException("GeneratedArtifacts for run", run.getId());
        }

        List<String> selectorPaths = artifacts.stream()
                .filter(artifact -> "DBT_SELECTOR".equals(artifact.getFileType()))
                .map(com.pulse.codegen.model.GeneratedArtifact::getFilePath)
                .toList();
        List<String> manifestPaths = artifacts.stream()
                .filter(artifact -> artifact.getFilePath().contains("/manifests/")
                        || artifact.getFilePath().endsWith("compile-plan.json")
                        || artifact.getFilePath().endsWith("gold-publish-contract.json"))
                .map(com.pulse.codegen.model.GeneratedArtifact::getFilePath)
                .toList();
        List<String> publishArtifacts = artifacts.stream()
                .filter(artifact -> "GOLD_PUBLISH_CONTRACT".equals(artifact.getFileType())
                        || artifact.getFilePath().contains("gold-publish"))
                .map(com.pulse.codegen.model.GeneratedArtifact::getFilePath)
                .toList();

        Package pkg = new Package();
        pkg.setPipelineId(run.getPipelineId());
        pkg.setVersionId(versionId);
        pkg.setTenantId(run.getTenantId());
        // Phase 3 audit attribution: builtBy comes from the resolved
        // CallerContext, never from the request body. A spoofed body
        // userId has no effect on the persisted Package row.
        pkg.setBuiltBy(caller.userId());
        pkg.setPackageType(req.packageType() != null ? req.packageType() : "ARTIFACT_BUNDLE");
        pkg.setBuildStatus("COMPLETED");
        Instant builtAt = Instant.now();
        pkg.setBuiltAt(builtAt);
        String compileNamespace = run.getMetadata() != null
                ? (String) run.getMetadata().getOrDefault("compile_namespace", "pipelines/" + run.getPipelineId())
                : "pipelines/" + run.getPipelineId();
        pkg.setArtifactUrl("artifactory.corp.example.com/pulse/" + compileNamespace + "/" + versionId + ".tar.gz");
        String artifactHash = hashArtifacts(artifacts);
        pkg.setArtifactHash(artifactHash);
        pkg.setBuildLog("Packaged " + artifacts.size() + " generated artifacts from run " + run.getId());

        // Phase 2: capture Git provenance for the tenant repo. Caller-supplied
        // BuildRequest fields (record: pipelineId, tenantId, userId,
        // packageType) deliberately have no Git fields — the controller
        // resolves repo identity / branch / commit SHA / tree SHA / working
        // tree status server-side via PackageService, so request bodies
        // cannot spoof provenance.
        PackageService.PackageProvenance provenance =
                packageService.captureProvenance(run.getTenantId(), builtAt);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("generationRunId", run.getId());
        metadata.put("compileNamespace", compileNamespace);
        metadata.put("artifactCount", artifacts.size());
        metadata.put("selectorPaths", selectorPaths);
        metadata.put("manifestPaths", manifestPaths);
        metadata.put("publishArtifacts", publishArtifacts);
        metadata.put("hasGoldPublishBoundary", !publishArtifacts.isEmpty());
        metadata.put("fileTypes", artifacts.stream()
                .collect(Collectors.groupingBy(
                        com.pulse.codegen.model.GeneratedArtifact::getFileType,
                        LinkedHashMap::new,
                        Collectors.counting()
                )));
        metadata.put("artifactPreview", artifacts.stream()
                .sorted(Comparator.comparing(com.pulse.codegen.model.GeneratedArtifact::getFilePath))
                .limit(12)
                .map(artifact -> Map.of(
                        "path", artifact.getFilePath(),
                        "type", artifact.getFileType()
                ))
                .collect(Collectors.toCollection(ArrayList::new)));
        // Phase 2 provenance block + diagnostics + manifest hash. The plan's
        // canonical "git" block at metadata.git matches the
        // package-manifest.json schema exactly; "gitProvenanceDiagnostics"
        // is a stable diagnostic record for downstream Phase 4 preflight.
        metadata.put("git", provenance.toManifestBlock());
        metadata.put("gitProvenanceDiagnostics", packageService.diagnostics(provenance));
        // Phase 2 acceptance text in deployment-productization-plan.md lists
        // the same provenance fields as flat top-level keys (gitRepoId,
        // gitBranch, gitCommitSha, gitTreeSha, workingTreeStatus,
        // artifactHash). Mirror the nested git block as flat aliases so
        // both shapes are queryable; consumers should treat metadata.git
        // as the canonical source of truth.
        metadata.put("gitRepoId", provenance.gitRepoId());
        metadata.put("gitBranch", provenance.branch());
        metadata.put("gitCommitSha", provenance.commitSha());
        metadata.put("gitTreeSha", provenance.treeSha());
        metadata.put("workingTreeStatus", provenance.workingTreeStatus());
        metadata.put("artifactHash", artifactHash);
        metadata.put("staticRuntimeAssessment",
                assessPackageReadiness(artifacts, provenance));
        // Persist first so the row gets an id, then compute the manifest +
        // hash against that id (manifests reference the package id).
        Package saved = packageRepo.save(pkg);
        // Phase 3 audit: manifest createdBy is the resolved actor, never
        // the request body. Mirrors Package.builtBy above.
        Map<String, Object> manifest = packageService.buildManifest(
                saved.getId(),
                saved.getTenantId(),
                saved.getPipelineId(),
                versionId,
                run,
                caller.userId(),
                artifacts,
                artifactHash,
                provenance,
                builtAt
        );
        String manifestHash = packageService.computeManifestHash(manifest);
        metadata.put("packageManifest", manifest);
        metadata.put("packageManifestHash", manifestHash);
        saved.setMetadata(metadata);
        return ResponseEntity.ok(packageRepo.save(saved));
    }

    @GetMapping("/api/v1/versions/{versionId}/packages")
    public ResponseEntity<List<Package>> listPackages(@PathVariable String versionId) {
        return ResponseEntity.ok(packageRepo.findByVersionIdOrderByCreatedAtDesc(versionId));
    }

    @GetMapping("/api/v1/tenants/{tenantId}/deployment-targets")
    public ResponseEntity<List<DeploymentTarget>> listTargets(
            @PathVariable String tenantId,
            @RequestParam(defaultValue = "false") boolean includeDisabled) {
        if (includeDisabled) {
            return ResponseEntity.ok(targetRepo.findByTenantIdOrderByEnvironmentAsc(tenantId));
        }
        return ResponseEntity.ok(targetRepo.findByTenantIdAndEnabledTrueOrderByEnvironmentAsc(tenantId));
    }

    @PostMapping("/api/v1/tenants/{tenantId}/deployment-targets")
    public ResponseEntity<DeploymentTarget> createTarget(@PathVariable String tenantId, @RequestBody CreateTargetReq req) {
        // Phase 3: TARGET_CONFIG requires tenant_admin or platform_admin.
        enforce(CallerSurface.UI, PulseAction.TARGET_CONFIG, tenantId, null);
        // Phase 7 — boundary normalizer accepts legacy KUBERNETES alias
        // and writes the canonical LOCAL_MATERIALIZATION key, matching V106.
        String canonicalTargetType = DeploymentTarget.normalizeTargetType(req.targetType());
        // ARCH-004: reject persona-illegal target types before persistence.
        runtimeAuthorityService.validateTargetType(canonicalTargetType);
        DeploymentTarget t = new DeploymentTarget();
        t.setTenantId(tenantId);
        t.setName(req.name());
        t.setEnvironment(normalizeEnvironment(req.environment()));
        t.setTargetType(canonicalTargetType);
        t.setEndpointUrl(req.endpointUrl());
        t.setConfig(req.config() != null ? req.config() : new HashMap<>());
        return ResponseEntity.ok(targetRepo.save(t));
    }

    // BUG-55 / Agent C: PATCH + DELETE on deployment-targets.
    // PATCH allows mutation of name, endpointUrl, config, enabled.
    // DELETE is a soft-delete (sets enabled=false) so audit / cascade-safe.
    @PatchMapping("/api/v1/tenants/{tenantId}/deployment-targets/{targetId}")
    public ResponseEntity<DeploymentTarget> updateTarget(
            @PathVariable String tenantId,
            @PathVariable String targetId,
            @RequestBody UpdateTargetReq req) {
        DeploymentTarget t = targetRepo.findById(targetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "DeploymentTarget not found: " + targetId));
        if (!tenantId.equals(t.getTenantId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Target not in tenant scope");
        }
        if (req.name() != null) t.setName(req.name());
        if (req.endpointUrl() != null) t.setEndpointUrl(req.endpointUrl());
        if (req.config() != null) t.setConfig(req.config());
        if (req.enabled() != null) t.setEnabled(req.enabled());
        return ResponseEntity.ok(targetRepo.save(t));
    }

    @DeleteMapping("/api/v1/tenants/{tenantId}/deployment-targets/{targetId}")
    public ResponseEntity<Void> deleteTarget(
            @PathVariable String tenantId,
            @PathVariable String targetId) {
        DeploymentTarget t = targetRepo.findById(targetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "DeploymentTarget not found: " + targetId));
        if (!tenantId.equals(t.getTenantId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Target not in tenant scope");
        }
        t.setEnabled(false);
        targetRepo.save(t);
        return ResponseEntity.noContent().build();
    }

    /**
     * Deploy a package to a target.
     *
     * <p>PULSE is dev-only (PKT-FINAL-2 / BUG-2026-05-25-02). Targets whose
     * canonical {@code environment} is anything other than {@code dev} are
     * rejected with {@code 403}: promotion to higher environments is owned by
     * enterprise CI/CD after the pipeline's PR is merged. The
     * {@code DeploymentTarget.environment} column is preserved (per the
     * package config matrix decision) but PULSE itself will only deploy to
     * the dev target.
     */
    static final String DEV_ONLY_DEPLOY_MESSAGE =
            "PULSE deploys to dev only; promote to higher environments via enterprise CI/CD after merging.";

    @PostMapping("/api/v1/packages/{packageId}/deploy")
    public ResponseEntity<Deployment> deploy(
            @PathVariable String packageId,
            @RequestBody DeployRequest req,
            @org.springframework.web.bind.annotation.RequestHeader(value = "Idempotency-Key", required = false)
                    String idempotencyKey,
            @org.springframework.web.bind.annotation.RequestHeader(value = "X-Correlation-Id", required = false)
                    String correlationIdHeader) {
        Package pkg = packageRepo.findById(packageId)
                .orElseThrow(() -> new ResourceNotFoundException("Package", packageId));
        DeploymentTarget target = targetRepo.findById(req.targetId())
                .orElseThrow(() -> new ResourceNotFoundException("DeploymentTarget", req.targetId()));

        // Phase 1: normalize the target environment to a canonical lowercase
        // key for both the storage gate lookup and the deployment metadata,
        // even if the persisted target row predates the V103 backfill.
        String canonicalEnv = normalizeEnvironment(target.getEnvironment());

        // PKT-FINAL-2: PULSE deploys to dev only. Reject any non-dev target
        // with 403 + the canonical message. Higher envs (integration / UAT /
        // production) are managed by enterprise CI/CD after merge — PULSE
        // has no business writing to them. This fires BEFORE the policy
        // gate so the message is unambiguous and identical regardless of
        // the caller's role.
        if (!"dev".equals(canonicalEnv)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, DEV_ONLY_DEPLOY_MESSAGE);
        }

        // Phase 3: DEPLOY policy gate fires BEFORE preflight so unauthorized
        // callers don't get a "validated" preflight answer that would leak
        // which envs/backends are provisioned. tenantId is resolved from
        // the package row, never from the request body.
        CallerContext caller = enforce(CallerSurface.UI, PulseAction.DEPLOY,
                pkg.getTenantId(), canonicalEnv);

        DeployValidationRequest validation = resolveValidationRequest(req);

        // Phase 4 — body hash + idempotency. Compute a deterministic
        // sha256 over the resolved request fields so a replay with the
        // same Idempotency-Key returns the same DeploymentRun, and a
        // replay with the same key but a different body returns 409.
        Map<String, Object> bodyForHash = new java.util.LinkedHashMap<>();
        bodyForHash.put("packageId", packageId);
        bodyForHash.put("targetId", req.targetId());
        bodyForHash.put("environment", canonicalEnv);
        bodyForHash.put("validationMode", validation.mode().name());
        bodyForHash.put("awaitValidation", validation.awaitValidation());
        bodyForHash.put("validationConfHash", validation.validationConfHash());
        String requestBodySha = evidenceService.sha256Json(bodyForHash);
        String correlationId = correlationIdHeader != null && !correlationIdHeader.isBlank()
                ? correlationIdHeader.trim()
                : java.util.UUID.randomUUID().toString();

        // Idempotency lookup: a Deployment row is the parent for runs of
        // this (package, target, env) tuple. Reuse the most-recent
        // matching deployment if one exists; otherwise create a new one.
        Deployment deployment = findOrCreateDeployment(pkg, target, canonicalEnv, caller);

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            var existingRun = deploymentRunRepository
                    .findByDeploymentIdAndIdempotencyKey(deployment.getId(), idempotencyKey);
            if (existingRun.isPresent()) {
                DeploymentRun prior = existingRun.get();
                if (!java.util.Objects.equals(prior.getRequestBodySha256(), requestBodySha)) {
                    throw new org.springframework.web.server.ResponseStatusException(
                            org.springframework.http.HttpStatus.CONFLICT,
                            "idempotency_body_mismatch");
                }
                // Same key + same body → return the existing deployment
                // unchanged. Phase 4 contract: replay yields the same run.
                return ResponseEntity.ok(rehydrateForResponse(deployment, prior));
            }
        }

        // Run preflight. Includes the storage gate check so the legacy
        // pre-Phase-4 PRECONDITION_FAILED response is replaced by a
        // structured PreflightCheckResult (storage failure surfaces as a
        // STORAGE_BACKEND_VALIDATED blocker). The caller + correlation
        // id flow into preflight so AGENT_AUDIT_CONTEXT can verify the
        // run we're about to create is fully attributable.
        Instant now = Instant.now();
        PreflightCheckResult preflight = preflightService.check(
                packageId, target.getId(), now, caller, correlationId);

        // Create a DeploymentRun in the appropriate non-terminal /
        // terminal state. Phase 4 ends here — Phase 5 will drive the
        // run forward from PREFLIGHT_PASSED through MATERIALIZING.
        DeploymentRun run = new DeploymentRun();
        run.setDeploymentId(deployment.getId());
        run.setTenantId(pkg.getTenantId());
        run.setInitiatedBy(caller.userId());
        run.setCorrelationId(correlationId);
        run.setIdempotencyKey(idempotencyKey);
        run.setRequestBodySha256(requestBodySha);
        run.setStartedAt(now);
        // Phase 5: stash the package id on the run row so the local
        // materialization adapter can look it up without re-walking
        // through the parent Deployment row.
        Map<String, Object> runMetadata = new java.util.LinkedHashMap<>();
        runMetadata.put("packageId", packageId);
        runMetadata.put("targetId", target.getId());
        runMetadata.put("environment", canonicalEnv);
        runMetadata.put("validationRequested", validation.validationRequested());
        runMetadata.put("validationMode", validation.mode().name());
        runMetadata.put("awaitValidation", validation.awaitValidation());
        runMetadata.put("validationStatus", validation.initialStatus());
        runMetadata.put("validationConfHash", validation.validationConfHash());
        if (!validation.validationConf().isEmpty()) {
            runMetadata.put("validationConf", validation.validationConf());
        }
        run.setMetadata(runMetadata);
        if (preflight.passed()) {
            // Phase 4 lands at PREFLIGHT_PASSED; Phase 5 will move forward.
            run.setStatus(DeploymentRunState.PREFLIGHT_PASSED.name());
            deployment.setStatus("RUNNING");
        } else {
            run.setStatus(DeploymentRunState.PREFLIGHT_FAILED.name());
            run.setFinishedAt(now);
            run.setFailureReason("preflight_failed: " + String.join(",", preflight.blockers()));
            deployment.setStatus("FAILED");
        }
        run = deploymentRunRepository.save(run);

        // Persist the preflight evidence + RUN_STATE_CHANGED event so
        // downstream surfaces can replay every decision.
        evidenceService.recordPreflightResult(
                deployment.getId(), run.getId(), packageId, preflight, correlationId);
        evidenceService.recordPreflightOutcome(
                deployment.getId(), run.getId(), preflight, caller, correlationId, requestBodySha);
        evidenceService.recordRunStateChange(
                deployment.getId(), run.getId(),
                DeploymentRunState.PENDING.name(), run.getStatus(),
                caller, correlationId, requestBodySha,
                Map.of("preflightStatus", preflight.status()));

        // Phase 7 — once preflight passes, drive the run forward
        // through MATERIALIZE -> SUBMIT -> POLL via the orchestrator.
        // Stub-backed adapters resolve synchronously; production cloud
        // adapters return early after submit() and Phase 8 will
        // schedule async polling.
        if (preflight.passed()) {
            try {
                if (validation.validationRequested() && !validation.awaitValidation()) {
                    var submitExecution = orchestrator.runThroughSubmit(run.getId(), caller);
                    deployment.setStatus(submitExecution.succeeded() ? "ACTIVE" : "FAILED");
                } else {
                    DeploymentRunState terminal = orchestrator.runToTerminal(run.getId(), caller);
                    deployment.setStatus(terminal == DeploymentRunState.SUCCEEDED ? "ACTIVE" : "FAILED");
                }
                run = deploymentRunRepository.findById(run.getId()).orElse(run);
            } catch (RuntimeException orchestratorErr) {
                // The orchestrator already wrote the FAILED transition
                // and a CANCEL_RESULT/runtime-status evidence row; surface
                // the failure on the deployment row too so the controller
                // response stays in sync.
                deployment.setStatus("FAILED");
                run = deploymentRunRepository.findById(run.getId()).orElse(run);
            }
        }

        // Update Deployment metadata with the new Phase 4 contract: run id,
        // preflight summary, and the resolved actor — but no longer mark
        // the deployment "DEPLOYED" right here.
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("targetEnvironment", canonicalEnv);
        metadata.put("targetType", target.getTargetType());
        metadata.put("targetName", target.getName());
        metadata.put("deploymentRunId", run.getId());
        metadata.put("preflightStatus", preflight.status());
        metadata.put("preflightBlockers", preflight.blockers());
        metadata.put("correlationId", correlationId);
        mergeValidationMetadata(metadata, run);
        deployment.setMetadata(metadata);
        deployment.setDeployLog(buildDeployLog(packageId, canonicalEnv, target, preflight, run));
        deployment.setDeployedAt(now);
        return ResponseEntity.ok(deployRepo.save(deployment));
    }

    /**
     * Phase 4 — find or create the parent {@link Deployment} row for the
     * given (package, target, env) tuple. Idempotent: reuses the most
     * recent matching row when present so multiple runs share the same
     * deployment intent. Audit fields come from the resolved actor.
     */
    private Deployment findOrCreateDeployment(Package pkg,
                                              DeploymentTarget target,
                                              String canonicalEnv,
                                              CallerContext caller) {
        for (Deployment existing : deployRepo.findByPipelineIdOrderByCreatedAtDesc(pkg.getPipelineId())) {
            if (pkg.getId().equals(existing.getPackageId())
                    && target.getId().equals(existing.getTargetId())) {
                return existing;
            }
        }
        Deployment d = new Deployment();
        d.setPackageId(pkg.getId());
        d.setTargetId(target.getId());
        d.setPipelineId(pkg.getPipelineId());
        d.setVersionId(pkg.getVersionId());
        d.setTenantId(pkg.getTenantId());
        d.setDeployedBy(caller.userId());
        d.setStatus("DRAFT");
        d.setDeployLog("Created by Phase 4 deploy contract");
        return deployRepo.save(d);
    }

    /** Replay-friendly response builder: ensures metadata.deploymentRunId points at the prior run. */
    private Deployment rehydrateForResponse(Deployment deployment, DeploymentRun run) {
        Map<String, Object> meta = deployment.getMetadata() == null
                ? new java.util.LinkedHashMap<>()
                : new java.util.LinkedHashMap<>(deployment.getMetadata());
        meta.put("deploymentRunId", run.getId());
        meta.put("idempotentReplay", true);
        deployment.setMetadata(meta);
        return deployment;
    }

    /**
     * Normalize a target environment value to a canonical lowercase form so
     * the dev-only policy gate is robust to legacy uppercase / mixed-case
     * values that may exist in {@code deployment_targets.environment}.
     */
    private static String canonicalEnvironment(String raw) {
        if (raw == null) return null;
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    @GetMapping("/api/v1/pipelines/{pipelineId}/deployments")
    public ResponseEntity<List<Deployment>> listDeployments(@PathVariable String pipelineId) {
        return ResponseEntity.ok(deployRepo.findByPipelineIdOrderByCreatedAtDesc(pipelineId));
    }

    /**
     * PKT-0005 — runtime evidence readback for a deployment run.
     * Returns the assembled {@link RuntimeEvidenceEnvelope} with
     * explicit proofLevel/evidenceType fields so consumers can
     * distinguish local/static/preflight evidence from real
     * runtime output proof.
     *
     * <p>Authorization: DEPLOY gate scoped to the run's tenant and
     * target environment. This prevents cross-tenant evidence readback
     * via direct deployment-run-id guessing. The DEPLOY action requires
     * DEPLOYMENT_OPERATOR, TENANT_ADMIN, or PLATFORM_ADMIN — matching
     * the deploy endpoint's own gate.
     */
    @GetMapping("/api/v1/deployment-runs/{deploymentRunId}/evidence")
    public ResponseEntity<Map<String, Object>> getRunEvidence(@PathVariable String deploymentRunId) {
        DeploymentRun run = deploymentRunRepository.findById(deploymentRunId)
                .orElseThrow(() -> new ResourceNotFoundException("DeploymentRun", deploymentRunId));
        Deployment deployment = deployRepo.findById(run.getDeploymentId())
                .orElseThrow(() -> new ResourceNotFoundException("Deployment", run.getDeploymentId()));
        DeploymentTarget target = deployment.getTargetId() != null
                ? targetRepo.findById(deployment.getTargetId()).orElse(null)
                : null;
        String environment = normalizeEnvironment(target != null ? target.getEnvironment() : null);

        // PKT-0005: tenant-scoped DEPLOY gate fires before evidence
        // readback so cross-tenant callers cannot read another tenant's
        // deployment-run evidence via direct id guessing. tenantId is
        // derived from the persisted run row, never from the request.
        enforce(CallerSurface.UI, PulseAction.DEPLOY, run.getTenantId(), environment);

        String adapter = target != null ? target.getTargetType() : "unknown";
        String packageId = run.getMetadata() != null
                ? Objects.toString(run.getMetadata().get("packageId"), null)
                : null;

        RuntimeEvidenceEnvelope envelope = runtimeEvidenceService.assembleForRun(
                deploymentRunId,
                deployment.getId(),
                packageId,
                run.getTenantId(),
                environment,
                adapter);
        return ResponseEntity.ok(envelope.toCanonicalJson());
    }

    @PostMapping("/api/v1/deployments/{deploymentId}/approval")
    public ResponseEntity<ApprovalRequest> requestApproval(@PathVariable String deploymentId, @RequestBody ApprovalReq req) {
        Deployment deployment = deployRepo.findById(deploymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Deployment", deploymentId));
        // Phase 3 audit: requestedBy is the resolved actor, not the
        // request body. We don't gate this endpoint with a PulseAction
        // (approval REQUEST is record-keeping; the actual gated action
        // is APPROVE on the decide endpoint), but we still resolve the
        // actor server-side so audit attribution is trustworthy.
        CallerContext caller = actorResolver.resolve(CallerSurface.UI, deployment.getTenantId());
        ApprovalRequest a = new ApprovalRequest();
        a.setDeploymentId(deploymentId);
        a.setTenantId(deployment.getTenantId());
        a.setRequestedBy(caller.userId());
        a.setExpiresAt(Instant.now().plusSeconds(86400));
        return ResponseEntity.ok(approvalRepo.save(a));
    }

    @GetMapping("/api/v1/deployments/{deploymentId}/approval-requests")
    public ResponseEntity<List<ApprovalRequest>> listApprovals(@PathVariable String deploymentId) {
        return ResponseEntity.ok(approvalRepo.findByDeploymentIdOrderByCreatedAtDesc(deploymentId));
    }

    @PutMapping("/api/v1/approvals/{approvalId}")
    public ResponseEntity<ApprovalRequest> decide(@PathVariable String approvalId, @RequestBody Map<String, String> body) {
        ApprovalRequest a = approvalRepo.findById(approvalId)
                .orElseThrow(() -> new ResourceNotFoundException("ApprovalRequest", approvalId));
        // Phase 3: APPROVE requires pull_request_approver / tenant_admin /
        // platform_admin. Tenant comes from the approval row, not the body.
        // The returned CallerContext stamps approvedBy below — body
        // 'decidedBy' is ignored for audit purposes.
        CallerContext caller = enforce(CallerSurface.UI, PulseAction.APPROVE, a.getTenantId(), null);
        a.setStatus(body.get("status"));
        a.setApprovedBy(caller.userId());
        a.setReason(body.get("reason"));
        a.setDecidedAt(Instant.now());
        return ResponseEntity.ok(approvalRepo.save(a));
    }

    /**
     * Phase 2 spoof-resistance: this record is the wire shape of the
     * {@code POST /api/v1/versions/{versionId}/packages} request body.
     * It deliberately has NO Git provenance fields. Adding any (e.g.
     * {@code gitRepoId}, {@code gitCommitSha}) would let callers spoof
     * provenance through the request body and bypass server-side
     * capture in {@link com.pulse.deploy.service.PackageService}.
     */
    public record BuildRequest(String pipelineId, String tenantId, String userId, String packageType) {}
    public record CreateTargetReq(String name, String environment, String targetType, String endpointUrl, Map<String, Object> config) {}
    // BUG-55 / Agent C: PATCH body for deployment-targets.
    public record UpdateTargetReq(String name, String endpointUrl, Map<String, Object> config, Boolean enabled) {}
    public record DeployRequest(String targetId,
                                String tenantId,
                                String userId,
                                ValidationMode validationMode,
                                Boolean awaitValidation,
                                Map<String, Object> validationConf) {
        public DeployRequest(String targetId, String tenantId, String userId) {
            this(targetId, tenantId, userId, null, null, null);
        }
    }
    public record ApprovalReq(String tenantId, String requestedBy) {}

    public enum ValidationMode {
        NONE,
        SMOKE
    }

    /**
     * Normalize an environment string supplied by an API caller (legacy
     * uppercase or canonical lowercase) to its canonical persisted key.
     * Blank/null input defaults to {@code dev} to preserve the historical
     * controller behavior; unknown non-blank input throws via
     * {@link DeploymentEnvironment#parse(String)}.
     */
    private String normalizeEnvironment(String environment) {
        if (environment == null || environment.isBlank()) {
            return DeploymentEnvironment.DEV.key();
        }
        return DeploymentEnvironment.normalize(environment);
    }

    private DeployValidationRequest resolveValidationRequest(DeployRequest req) {
        ValidationMode mode = req.validationMode() == null ? ValidationMode.NONE : req.validationMode();
        Map<String, Object> validationConf = sanitizeValidationConf(req.validationConf());
        boolean hasValidationConf = !validationConf.isEmpty();
        if (mode == ValidationMode.NONE) {
            if (Boolean.TRUE.equals(req.awaitValidation())) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST,
                        "awaitValidation requires validationMode=SMOKE");
            }
            if (hasValidationConf) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST,
                        "validationConf requires validationMode=SMOKE");
            }
            return new DeployValidationRequest(
                    ValidationMode.NONE,
                    false,
                    false,
                    "NOT_REQUESTED",
                    Map.of(),
                    null
            );
        }
        boolean awaitValidation = req.awaitValidation() == null || req.awaitValidation();
        String validationConfHash = hasValidationConf ? evidenceService.sha256Json(validationConf) : null;
        return new DeployValidationRequest(
                ValidationMode.SMOKE,
                true,
                awaitValidation,
                "REQUESTED",
                validationConf,
                validationConfHash
        );
    }

    private Map<String, Object> sanitizeValidationConf(Map<String, Object> validationConf) {
        if (validationConf == null || validationConf.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        validationConf.forEach(copy::put);
        return Collections.unmodifiableMap(copy);
    }

    private void mergeValidationMetadata(Map<String, Object> metadata, DeploymentRun run) {
        metadata.put("validationRequested", readRunMeta(run, "validationRequested"));
        metadata.put("validationMode", readRunMeta(run, "validationMode"));
        metadata.put("awaitValidation", readRunMeta(run, "awaitValidation"));
        metadata.put("validationStatus", readRunMeta(run, "validationStatus"));
        metadata.put("validationConfHash", readRunMeta(run, "validationConfHash"));
        metadata.put("activationProviderRunId", readRunMeta(run, "activationProviderRunId"));
        metadata.put("validationDagRunId", readRunMeta(run, "validationDagRunId"));
    }

    private Object readRunMeta(DeploymentRun run, String key) {
        if (run == null || run.getMetadata() == null) {
            return null;
        }
        return run.getMetadata().get(key);
    }

    private String buildDeployLog(String packageId,
                                  String canonicalEnv,
                                  DeploymentTarget target,
                                  PreflightCheckResult preflight,
                                  DeploymentRun run) {
        if (!preflight.passed()) {
            return "Preflight FAILED for package " + packageId + " to " + canonicalEnv + " / " + target.getName()
                    + "; blockers=" + String.join(",", preflight.blockers());
        }
        String validationMode = Objects.toString(readRunMeta(run, "validationMode"), "NONE");
        String validationStatus = Objects.toString(readRunMeta(run, "validationStatus"), "NOT_REQUESTED");
        String validationDagRunId = Objects.toString(readRunMeta(run, "validationDagRunId"), "");
        if ("SMOKE".equals(validationMode)) {
            if ("TRIGGERED".equals(validationStatus)) {
                return "Activated package " + packageId + " to " + canonicalEnv + " / " + target.getName()
                        + "; Airflow smoke validation triggered asynchronously"
                        + (validationDagRunId.isBlank() ? "." : " as " + validationDagRunId + ".");
            }
            return "Activated package " + packageId + " to " + canonicalEnv + " / " + target.getName()
                    + "; Airflow smoke validation " + validationStatus.toLowerCase()
                    + (validationDagRunId.isBlank() ? "." : " as " + validationDagRunId + ".");
        }
        return "Activated package " + packageId + " to " + canonicalEnv + " / " + target.getName()
                + " via Airflow sync only.";
    }

    private record DeployValidationRequest(
            ValidationMode mode,
            boolean validationRequested,
            boolean awaitValidation,
            String initialStatus,
            Map<String, Object> validationConf,
            String validationConfHash
    ) {}

    private String hashArtifacts(List<com.pulse.codegen.model.GeneratedArtifact> artifacts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (var artifact : artifacts) {
                digest.update(artifact.getFilePath().getBytes(StandardCharsets.UTF_8));
                digest.update(artifact.getContentHash().getBytes(StandardCharsets.UTF_8));
            }
            byte[] hash = digest.digest();
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            return "package-hash-error";
        }
    }

    private Map<String, Object> assessPackageReadiness(
            List<com.pulse.codegen.model.GeneratedArtifact> artifacts,
            PackageService.PackageProvenance provenance) {
        Map<String, Object> assessment = new LinkedHashMap<>();
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        // Phase 2: surface missing/dirty Git provenance as a static
        // assessment blocker. Hard-blocking package creation on missing
        // provenance is deferred to Phase 4 preflight policy
        // (PACKAGE_PROVENANCE_PRESENT in the blocker matrix). Until then
        // the provenance block + this surfaced blocker make the gap visible
        // to UI / preflight without breaking existing build flows.
        if (provenance != null) {
            // 'dirty' counts as a warning today (dev-policy permissive);
            // 'missing'/'unborn' are blockers because they prevent any
            // downstream promotion of this package.
            for (String text : packageService.buildStaticAssessmentSeed(provenance)) {
                if (text.startsWith("Working tree dirty")) {
                    warnings.add(text);
                } else {
                    blockers.add(text);
                }
            }
        }

        boolean hasDag = artifacts.stream().anyMatch(a -> "AIRFLOW_DAG".equals(a.getFileType()));
        boolean hasRequirements = artifacts.stream().anyMatch(a -> "REQUIREMENTS_TXT".equals(a.getFileType()));
        boolean hasConfig = artifacts.stream().anyMatch(a -> "CONFIG_YAML".equals(a.getFileType()));
        boolean hasCompilePlan = artifacts.stream().anyMatch(a -> "COMPILE_PLAN".equals(a.getFileType()));
        boolean hasDbtSelectors = artifacts.stream().anyMatch(a -> "DBT_SELECTOR".equals(a.getFileType()));

        if (!hasDag) blockers.add("Missing generated Airflow DAG artifact");
        if (!hasRequirements) blockers.add("Missing generated requirements artifact");
        if (!hasConfig) blockers.add("Missing generated pipeline config artifact");
        if (!hasCompilePlan) blockers.add("Missing compile-plan manifest artifact");
        if (!hasDbtSelectors) warnings.add("No dbt selector artifacts generated");

        int todoCount = artifacts.stream()
                .map(com.pulse.codegen.model.GeneratedArtifact::getContent)
                .filter(content -> content != null && !content.isBlank())
                .mapToInt(content -> countOccurrences(content, "TODO"))
                .sum();
        if (todoCount > 0) {
            warnings.add("Generated artifacts still contain TODO markers (" + todoCount + ")");
        }

        boolean hasAirflowCore = hasRequirementSubstring(artifacts, "apache-airflow>=");
        boolean hasSparkProvider = hasRequirementSubstring(artifacts, "apache-airflow-providers-apache-spark");
        boolean hasDbtCore = hasRequirementSubstring(artifacts, "dbt-core>=");
        boolean hasDbtSpark = hasRequirementSubstring(artifacts, "dbt-spark>=");

        if (!hasAirflowCore) blockers.add("Requirements file is missing apache-airflow");
        if (!hasSparkProvider) blockers.add("Requirements file is missing Airflow Spark provider");
        if (!hasDbtCore) blockers.add("Requirements file is missing dbt-core");
        if (!hasDbtSpark) blockers.add("Requirements file is missing dbt-spark");

        int score = 100
                - (blockers.size() * 25)
                - (warnings.size() * 5);
        if (score < 0) score = 0;

        String verdict = blockers.isEmpty()
                ? (warnings.isEmpty() ? "LIKELY_DEPLOYABLE" : "DEPLOYABLE_WITH_WARNINGS")
                : "NOT_READY";

        assessment.put("verdict", verdict);
        assessment.put("score", score);
        assessment.put("blockers", blockers);
        assessment.put("warnings", warnings);
        assessment.put("todoCount", todoCount);
        return assessment;
    }

    private boolean hasRequirementSubstring(List<com.pulse.codegen.model.GeneratedArtifact> artifacts,
                                            String needle) {
        return artifacts.stream()
                .filter(a -> "REQUIREMENTS_TXT".equals(a.getFileType()))
                .map(com.pulse.codegen.model.GeneratedArtifact::getContent)
                .anyMatch(content -> content != null && content.contains(needle));
    }

    private int countOccurrences(String content, String needle) {
        int count = 0;
        int index = 0;
        while ((index = content.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    // ── PKT-0004: Deploy Boundary Readback ───────────────────

    /**
     * PKT-0004 — deploy boundary readback for a specific deployment target.
     * Returns topology, IAM, credential, and blocker status for Composer,
     * Dataproc, BigQuery, Secret Manager, GCS, and evidence/log targets.
     *
     * <p>The boundary readback is NOT runtime proof, static package proof,
     * preflight proof, or local synthetic proof. It is topology/IAM
     * readiness evidence only.
     *
     * <p>Live GCP deploy paths appear as {@code OPERATOR_BLOCKED} unless
     * all topology/IAM/credential gates are satisfied.
     */
    @GetMapping("/api/v1/tenants/{tenantId}/deployment-targets/{targetId}/boundary")
    public ResponseEntity<Map<String, Object>> getDeployBoundary(
            @PathVariable String tenantId,
            @PathVariable String targetId,
            @RequestParam(required = false) String packageId) {
        enforce(CallerSurface.UI, PulseAction.DEPLOY, tenantId, null);
        DeployBoundaryReadback readback = deployBoundaryService.assembleForTarget(
                tenantId, targetId, packageId);
        return ResponseEntity.ok(readback.toCanonicalJson());
    }
}
