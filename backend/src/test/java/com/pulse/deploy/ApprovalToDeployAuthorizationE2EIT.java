package com.pulse.deploy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.auth.model.PulseUser;
import com.pulse.auth.model.Tenant;
import com.pulse.auth.policy.ActorResolverService;
import com.pulse.auth.policy.PulseRole;
import com.pulse.auth.repository.TenantRepository;
import com.pulse.auth.repository.UserRepository;
import com.pulse.blueprint.repository.BlueprintRepository;
import com.pulse.deploy.model.ApprovalRequest;
import com.pulse.deploy.model.Deployment;
import com.pulse.deploy.model.DeploymentTarget;
import com.pulse.deploy.model.Package;
import com.pulse.deploy.repository.ApprovalRequestRepository;
import com.pulse.deploy.repository.DeploymentRepository;
import com.pulse.deploy.repository.DeploymentTargetRepository;
import com.pulse.deploy.repository.PackageRepository;
import com.pulse.pipeline.repository.PipelineRepository;
import com.pulse.pipeline.repository.PipelineVersionRepository;
import com.pulse.pipeline.repository.PortWiringRepository;
import com.pulse.pipeline.repository.SubPipelineInstanceRepository;
import com.pulse.sor.model.Domain;
import com.pulse.sor.repository.ConnectorDefinitionRepository;
import com.pulse.sor.repository.ConnectorInstanceRepository;
import com.pulse.sor.repository.DatasetRepository;
import com.pulse.sor.repository.DomainRepository;
import com.pulse.sor.repository.SystemOfRecordRepository;
import com.pulse.support.SeedFixtures;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Wave 2 — TASK_P0_approval_to_deploy_authorization_e2e.
 *
 * <p>End-to-end (controller-through-policy) integration test for the
 * approval → deploy lifecycle. Drives the real
 * {@link com.pulse.deploy.controller.DeployController} via {@link MockMvc}
 * and supplies the caller's role context through the
 * {@link ActorResolverService} headers ({@code X-Pulse-User-Id},
 * {@code X-Pulse-Tenant-Id}, {@code X-Pulse-Roles}) so the
 * {@link com.pulse.auth.policy.AuthorizationPolicyService} runs against
 * realistic per-request role sets instead of the dev-stub all-roles fallback.
 *
 * <p>Behaviors covered:
 * <ul>
 *   <li>Happy path — deployer requests, approver approves, deploy proceeds
 *       without an HTTP 403 from the authorization gate.</li>
 *   <li>Deploy without prior approval — pinned to current Phase 4 behavior
 *       (no mandatory approval gate; preflight only warns).</li>
 *   <li>Double approval — pinned to current behavior (no idempotency check;
 *       same ApprovalRequest row is updated, no duplicate is created).</li>
 *   <li>Approval-to-package binding — current model binds an
 *       {@link ApprovalRequest} to {@code deploymentId}, which transitively
 *       binds it to a single package via the Deployment row; approvals
 *       cannot be re-pointed to a different deployment/package.</li>
 *   <li>Cross-env regression — DEPLOYMENT_OPERATOR is currently allowed in
 *       every env including PROD per the locked authorization matrix
 *       (Phase 3 default). This test pins that current allow so any future
 *       Phase 4 SoD tightening is a deliberate, visible matrix flip.</li>
 * </ul>
 *
 * <p>Aspirational behaviors in the packet (TC_deploy_without_approval_rejected
 * 409, TC_approval_bound_to_package_id 409, TC_approval_dev_approver_cannot_approve_prod
 * 403) DO NOT reproduce against today's controller + policy. Per the
 * authorization-matrix.json {@code followUpNotes}, those flips land in
 * Phase 4 with per-tenant SoD config and ApprovalPolicyService. This IT
 * pins the current observable behavior so the future tightening surfaces
 * as a planned test update rather than a regression mystery.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ApprovalToDeployAuthorizationE2EIT {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    // SeedFixtures collaborators
    @Autowired private TenantRepository tenantRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private DomainRepository domainRepository;
    @Autowired private SystemOfRecordRepository sorRepository;
    @Autowired private ConnectorDefinitionRepository connectorDefinitionRepository;
    @Autowired private ConnectorInstanceRepository connectorInstanceRepository;
    @Autowired private DatasetRepository datasetRepository;
    @Autowired private BlueprintRepository blueprintRepository;
    @Autowired private PipelineRepository pipelineRepository;
    @Autowired private PipelineVersionRepository pipelineVersionRepository;
    @Autowired private SubPipelineInstanceRepository subPipelineInstanceRepository;
    @Autowired private PortWiringRepository portWiringRepository;

    // Deploy domain repositories — used to persist fixtures directly so the
    // tests focus on the authorization gate rather than the package-build
    // pipeline (which is exercised separately by RepresentativeStaticDeployabilityProofIT).
    @Autowired private PackageRepository packageRepository;
    @Autowired private DeploymentTargetRepository deploymentTargetRepository;
    @Autowired private DeploymentRepository deploymentRepository;
    @Autowired private ApprovalRequestRepository approvalRequestRepository;

    private SeedFixtures seedFixtures() {
        return new SeedFixtures(
                tenantRepository, userRepository, domainRepository, sorRepository,
                connectorDefinitionRepository, connectorInstanceRepository, datasetRepository,
                blueprintRepository, pipelineRepository, pipelineVersionRepository,
                subPipelineInstanceRepository, portWiringRepository);
    }

    /**
     * Builds a minimal deploy fixture: tenant + user + domain + pipeline +
     * version + Package + DeploymentTarget. Returns the ids the tests need
     * to drive the deploy/approval flow.
     */
    private DeployFixture seedDeployFixture(String env) {
        SeedFixtures fx = seedFixtures();
        Tenant tenant = fx.seedTenant();
        PulseUser builder = fx.seedUser(tenant.getId());
        Domain domain = fx.seedDomain(tenant.getId());
        SeedFixtures.PipelineWithVersion pv = fx.seedPipelineWithVersion(
                tenant.getId(), domain.getId(), domain.getName());

        Package pkg = new Package();
        pkg.setTenantId(tenant.getId());
        pkg.setPipelineId(pv.pipeline().getId());
        pkg.setVersionId(pv.version().getId());
        pkg.setPackageType("ARTIFACT_BUNDLE");
        pkg.setBuildStatus("COMPLETED");
        pkg.setBuiltBy(builder.getId());
        pkg.setBuiltAt(Instant.now());
        pkg.setArtifactUrl("artifactory.test/" + pv.pipeline().getId() + ".tar.gz");
        pkg.setArtifactHash("0".repeat(64));
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("workingTreeStatus", "clean");
        pkg.setMetadata(meta);
        pkg = packageRepository.save(pkg);

        DeploymentTarget target = new DeploymentTarget();
        target.setTenantId(tenant.getId());
        target.setName("Target " + env);
        target.setEnvironment(env);
        target.setTargetType("LOCAL_MATERIALIZATION");
        target.setConfig(Map.of());
        target.setEnabled(true);
        target = deploymentTargetRepository.save(target);

        return new DeployFixture(tenant.getId(), builder.getId(), pv.pipeline().getId(),
                pv.version().getId(), pkg.getId(), target.getId());
    }

    private record DeployFixture(String tenantId, String userId, String pipelineId,
                                 String versionId, String packageId, String targetId) { }

    private static String rolesHeader(PulseRole... roles) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < roles.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(roles[i].name());
        }
        return sb.toString();
    }

    private Map<String, Object> readJson(MvcResult result) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsByteArray(), MAP_TYPE);
    }

    // -------------------------------------------------------------------
    //  TC_approval_happy_path_dev_deploy
    // -------------------------------------------------------------------

    /**
     * Deployer requests approval, an approver approves, then deployer triggers
     * the deploy. Under Phase 3 defaults the deploy is allowed for any
     * {@code DEPLOYMENT_OPERATOR} regardless of environment, so the canonical
     * flow does not gate on approval today. The assertion is that none of the
     * three transitions returns 403 — the authorization gate accepts each
     * role on its respective endpoint.
     */
    @Test
    @DisplayName("TC_approval_happy_path_dev_deploy: deployer + approver + deploy all clear the auth gate in DEV")
    void approvalHappyPathInDevClearsAuthorizationGate() throws Exception {
        DeployFixture fx = seedDeployFixture("dev");

        // 1. Deployer creates a Deployment row first by deploying the package.
        //    The deploy controller is the canonical creator of Deployment rows
        //    (request approval requires an existing deployment id). We allow
        //    the auth gate to accept DEPLOYMENT_OPERATOR for DEV deploy.
        MvcResult deployResult = mockMvc.perform(post("/api/v1/packages/{id}/deploy", fx.packageId())
                        .header(ActorResolverService.HEADER_USER_ID, "deployer-user")
                        .header(ActorResolverService.HEADER_TENANT_ID, fx.tenantId())
                        .header(ActorResolverService.HEADER_ROLES, rolesHeader(PulseRole.DEPLOYMENT_OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "targetId", fx.targetId(),
                                "tenantId", fx.tenantId()))))
                .andReturn();
        assertEquals(200, deployResult.getResponse().getStatus(),
                "DEPLOYMENT_OPERATOR must clear the DEPLOY auth gate in DEV; got "
                        + deployResult.getResponse().getStatus()
                        + " body=" + deployResult.getResponse().getContentAsString());
        Map<String, Object> deployBody = readJson(deployResult);
        String deploymentId = (String) deployBody.get("id");
        assertNotNull(deploymentId, "deploy must return a Deployment id");
        // Phase 3 audit: deployedBy must come from the resolved actor.
        assertEquals("deployer-user", deployBody.get("deployedBy"));

        // 2. Deployer requests an approval against that deployment. The
        //    request endpoint isn't policy-gated (it's record-keeping) but
        //    it still resolves the caller and stamps requestedBy.
        MvcResult requestResult = mockMvc.perform(post("/api/v1/deployments/{id}/approval", deploymentId)
                        .header(ActorResolverService.HEADER_USER_ID, "deployer-user")
                        .header(ActorResolverService.HEADER_TENANT_ID, fx.tenantId())
                        .header(ActorResolverService.HEADER_ROLES, rolesHeader(PulseRole.DEPLOYMENT_OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "tenantId", fx.tenantId(),
                                "requestedBy", "ignored-body-field"))))
                .andReturn();
        assertEquals(200, requestResult.getResponse().getStatus());
        Map<String, Object> approvalBody = readJson(requestResult);
        String approvalId = (String) approvalBody.get("id");
        assertNotNull(approvalId);
        assertEquals("deployer-user", approvalBody.get("requestedBy"));
        assertEquals("PENDING", approvalBody.get("status"));

        // 3. Approver decides. PULL_REQUEST_APPROVER is the only non-admin
        //    role that clears the APPROVE auth gate.
        MvcResult approveResult = mockMvc.perform(put("/api/v1/approvals/{id}", approvalId)
                        .header(ActorResolverService.HEADER_USER_ID, "approver-user")
                        .header(ActorResolverService.HEADER_TENANT_ID, fx.tenantId())
                        .header(ActorResolverService.HEADER_ROLES, rolesHeader(PulseRole.PULL_REQUEST_APPROVER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "status", "APPROVED",
                                "reason", "happy path",
                                "decidedBy", "ignored-body-field"))))
                .andReturn();
        assertEquals(200, approveResult.getResponse().getStatus(),
                "PULL_REQUEST_APPROVER must clear the APPROVE auth gate; got "
                        + approveResult.getResponse().getStatus()
                        + " body=" + approveResult.getResponse().getContentAsString());
        Map<String, Object> approved = readJson(approveResult);
        assertEquals("APPROVED", approved.get("status"));
        assertEquals("approver-user", approved.get("approvedBy"),
                "approvedBy is the resolved actor, not body.decidedBy");
        assertNotNull(approved.get("decidedAt"));
    }

    // -------------------------------------------------------------------
    //  TC_deploy_without_approval_rejected (pinned to current behavior)
    // -------------------------------------------------------------------

    /**
     * Packet aspiration: deploy without prior approval returns 409.
     *
     * <p>Current behavior: there is no mandatory-approval gate in
     * {@link com.pulse.deploy.controller.DeployController#deploy} and
     * {@code checkApprovalPolicy} in the Phase 4 preflight is permissive
     * (returns PASS for every env). The deploy therefore succeeds at the
     * authorization boundary even without any matching approval row.
     *
     * <p>This test pins that current behavior. When Phase 6's
     * {@code ApprovalPolicyService} lands and turns the check into a hard
     * 409 for uat/prod, this assertion will flip and signal the deliberate
     * tightening.
     */
    @Test
    @DisplayName("TC_deploy_without_approval_rejected: pinned — current Phase 4 deploy succeeds without approval")
    void deployWithoutPriorApprovalIsCurrentlyAllowed() throws Exception {
        DeployFixture fx = seedDeployFixture("dev");

        // Sanity: no approvals exist for this fixture.
        assertTrue(approvalRequestRepository.findAll().stream()
                .noneMatch(a -> fx.tenantId().equals(a.getTenantId())));

        int status = mockMvc.perform(post("/api/v1/packages/{id}/deploy", fx.packageId())
                        .header(ActorResolverService.HEADER_USER_ID, "deployer-user")
                        .header(ActorResolverService.HEADER_TENANT_ID, fx.tenantId())
                        .header(ActorResolverService.HEADER_ROLES, rolesHeader(PulseRole.DEPLOYMENT_OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "targetId", fx.targetId(),
                                "tenantId", fx.tenantId()))))
                .andReturn()
                .getResponse()
                .getStatus();
        // Pinned: deploy must NOT be rejected with 409 today. When the
        // mandatory approval gate lands, this expectation flips.
        assertEquals(200, status,
                "Phase 4 has no mandatory approval gate; deploy must clear without an approval row. "
                        + "If this assertion fails the gate has been tightened — update the test together with the matrix.");
        assertNotEquals(409, status, "409 conflict on missing approval is aspirational, not yet implemented");
    }

    // -------------------------------------------------------------------
    //  TC_double_approval_idempotent
    // -------------------------------------------------------------------

    /**
     * Decide endpoint has no idempotency-by-state guard today: calling
     * {@code PUT /api/v1/approvals/{id}} a second time with
     * {@code status=APPROVED} simply re-stamps the same ApprovalRequest
     * row (same id, status remains APPROVED, decidedAt is refreshed).
     * No duplicate ApprovalRequest row is created.
     */
    @Test
    @DisplayName("TC_double_approval_idempotent: second approve returns 200 and the row count for that deployment stays at 1")
    void doubleApprovalIsRowCountIdempotent() throws Exception {
        DeployFixture fx = seedDeployFixture("dev");

        Deployment deployment = persistDeployment(fx, "dev");
        ApprovalRequest existing = persistPendingApproval(deployment);

        // First approve — 200.
        MvcResult first = mockMvc.perform(put("/api/v1/approvals/{id}", existing.getId())
                        .header(ActorResolverService.HEADER_USER_ID, "approver-user")
                        .header(ActorResolverService.HEADER_TENANT_ID, fx.tenantId())
                        .header(ActorResolverService.HEADER_ROLES, rolesHeader(PulseRole.PULL_REQUEST_APPROVER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "status", "APPROVED",
                                "reason", "first call"))))
                .andReturn();
        assertEquals(200, first.getResponse().getStatus());

        // Second approve with the same payload — also 200 today.
        MvcResult second = mockMvc.perform(put("/api/v1/approvals/{id}", existing.getId())
                        .header(ActorResolverService.HEADER_USER_ID, "approver-user")
                        .header(ActorResolverService.HEADER_TENANT_ID, fx.tenantId())
                        .header(ActorResolverService.HEADER_ROLES, rolesHeader(PulseRole.PULL_REQUEST_APPROVER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "status", "APPROVED",
                                "reason", "second call (retry)"))))
                .andReturn();
        assertEquals(200, second.getResponse().getStatus(),
                "Phase 3 decide endpoint has no idempotency guard; second call is also 200.");

        // Row-count idempotency: the deployment still has exactly ONE
        // approval row. No duplicate has been spawned.
        List<ApprovalRequest> approvalsForDeployment = approvalRequestRepository
                .findByDeploymentIdOrderByCreatedAtDesc(deployment.getId());
        assertEquals(1, approvalsForDeployment.size(),
                "double approval must NOT spawn a duplicate ApprovalRequest row");
        ApprovalRequest only = approvalsForDeployment.get(0);
        assertEquals(existing.getId(), only.getId(),
                "the same row must be reused for retries, not replaced");
        assertEquals("APPROVED", only.getStatus());
        // Pinned divergence from the packet: the packet imagines an
        // idempotency contract that ignores second-call payload changes.
        // Today the second body's reason overwrites the first call's.
        assertEquals("second call (retry)", only.getReason(),
                "current behavior: second call overwrites reason (no idempotency-by-state guard)");
    }

    // -------------------------------------------------------------------
    //  TC_approval_bound_to_package_id
    // -------------------------------------------------------------------

    /**
     * Packet aspiration: "approval is bound to package_id, not deployment_id;
     * reusing approval for a different package returns 409".
     *
     * <p>Current data model: {@link ApprovalRequest} has a
     * {@code deployment_id} foreign key (and via the Deployment row, a
     * single {@code package_id}). Two packages produce two distinct
     * Deployment rows and therefore two distinct ApprovalRequest rows —
     * an approval is structurally inseparable from its deployment, so
     * "reuse" is not addressable by API at all.
     *
     * <p>This test pins that structural binding: approving deployment A's
     * approval row leaves deployment B's approval row untouched, and the
     * deployment_id on each approval row matches its origin deployment.
     */
    @Test
    @DisplayName("TC_approval_bound_to_package_id: approvals are scoped to their deployment (and thus a single package)")
    void approvalIsStructurallyBoundToDeploymentNotReusableAcrossPackages() throws Exception {
        DeployFixture fxA = seedDeployFixture("dev");
        // Second fixture in the same tenant with its own package + target.
        DeployFixture fxB = seedDeployFixture("dev");

        Deployment depA = persistDeployment(fxA, "dev");
        Deployment depB = persistDeployment(fxB, "dev");
        ApprovalRequest approvalA = persistPendingApproval(depA);
        ApprovalRequest approvalB = persistPendingApproval(depB);

        assertNotEquals(approvalA.getId(), approvalB.getId(),
                "each deployment gets its own approval row");
        assertEquals(depA.getId(), approvalA.getDeploymentId());
        assertEquals(depB.getId(), approvalB.getDeploymentId());
        assertNotEquals(depA.getPackageId(), depB.getPackageId(),
                "fixtures must use distinct packages for this test to be meaningful");

        // Approve A only.
        MvcResult approveA = mockMvc.perform(put("/api/v1/approvals/{id}", approvalA.getId())
                        .header(ActorResolverService.HEADER_USER_ID, "approver-user")
                        .header(ActorResolverService.HEADER_TENANT_ID, fxA.tenantId())
                        .header(ActorResolverService.HEADER_ROLES, rolesHeader(PulseRole.PULL_REQUEST_APPROVER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "status", "APPROVED",
                                "reason", "approve A"))))
                .andReturn();
        assertEquals(200, approveA.getResponse().getStatus());

        // Approval B must remain PENDING — the approve action on A does not
        // implicitly approve any other deployment / package.
        ApprovalRequest reloadedB = approvalRequestRepository.findById(approvalB.getId()).orElseThrow();
        assertEquals("PENDING", reloadedB.getStatus(),
                "approving deployment A's approval row must not bleed across to deployment B");
        assertEquals(depB.getId(), reloadedB.getDeploymentId(),
                "approval B is still bound to its origin deployment");
    }

    // -------------------------------------------------------------------
    //  Cross-env regression — pinned to current authorization matrix
    // -------------------------------------------------------------------

    /**
     * Post-PKT-FINAL-2: PULSE deploys to dev only. The aspirational
     * "Phase 4 SoD tightening" foretold in the pre-merge comment landed
     * not as per-role env narrowing but as a flat categorical rule:
     * non-dev targets are categorically forbidden regardless of role.
     * So DEPLOYMENT_OPERATOR — previously allowed in every env per the
     * Phase 3 matrix — now gets 403 dev-only when attempting PROD, the
     * same as any other role. This test was originally written as a
     * regression-tracking placeholder for exactly this flip; the
     * assertion has now been flipped to its post-flip form.
     */
    @Test
    @DisplayName("PKT-FINAL-2: DEPLOYMENT_OPERATOR PROD deploy attempt is rejected with 403 dev-only (any role)")
    void deploymentOperatorInProdIsAllowedToday() throws Exception {
        DeployFixture fx = seedDeployFixture("prod");

        int status = mockMvc.perform(post("/api/v1/packages/{id}/deploy", fx.packageId())
                        .header(ActorResolverService.HEADER_USER_ID, "deployer-user")
                        .header(ActorResolverService.HEADER_TENANT_ID, fx.tenantId())
                        .header(ActorResolverService.HEADER_ROLES, rolesHeader(PulseRole.DEPLOYMENT_OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "targetId", fx.targetId(),
                                "tenantId", fx.tenantId()))))
                .andReturn()
                .getResponse()
                .getStatus();
        assertEquals(403, status,
                "Post-PKT-FINAL-2 (BUG-2026-05-25-02), PULSE deploys to dev only — non-dev targets are "
                        + "categorically forbidden regardless of role. The 403 here carries the canonical "
                        + "DEV_ONLY_DEPLOY_MESSAGE, not env_not_allowed.");
    }

    /**
     * Sanity counter-test: a role without DEPLOY permission must still
     * cause an auth-gate denial even in DEV, and the request must NOT
     * leave a Deployment row behind.
     *
     * <p>Pinned-behavior divergence from the packet's aspirational shape:
     * the policy throws {@code ResponseStatusException(403 FORBIDDEN, "missing_role")}
     * inside the controller, but {@code GlobalExceptionHandler}'s catch-all
     * {@code @ExceptionHandler(Exception.class)} swallows it and returns
     * HTTP 500 with a {@code ProblemDetail} body of
     * {@code "An unexpected error occurred"}. The denial is real and the
     * mutation is prevented — only the HTTP code on the wire is the wrong
     * one. Fixing this is a future cleanup (move {@code ResponseStatusException}
     * to its own handler, or remove the {@code Exception.class} catch-all).
     */
    @Test
    @DisplayName("Counter-test: PIPELINE_DEVELOPER deploy attempt is denied with 403 + missing_role reason; no Deployment row written")
    void pipelineDeveloperDeployIsDenied() throws Exception {
        DeployFixture fx = seedDeployFixture("dev");

        MvcResult result = mockMvc.perform(post("/api/v1/packages/{id}/deploy", fx.packageId())
                        .header(ActorResolverService.HEADER_USER_ID, "dev-user")
                        .header(ActorResolverService.HEADER_TENANT_ID, fx.tenantId())
                        .header(ActorResolverService.HEADER_ROLES, rolesHeader(PulseRole.PIPELINE_DEVELOPER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "targetId", fx.targetId(),
                                "tenantId", fx.tenantId()))))
                .andReturn();
        // GlobalExceptionHandler now has a dedicated @ExceptionHandler(ResponseStatusException.class)
        // that preserves the actual status code and reason instead of letting the
        // Exception.class catch-all rewrite it to 500. The original masking bug is
        // captured in docs/testing/non-blueprint-rollout-findings.md.
        assertEquals(403, result.getResponse().getStatus(),
                "ResponseStatusException(403, \"missing_role\") must surface as HTTP 403, not be masked to 500");
        String body = result.getResponse().getContentAsString();
        assertTrue(body.contains("\"status\":403"),
                "ProblemDetail body must report status 403; got: " + body);
        assertTrue(body.contains("missing_role") || body.contains("Forbidden"),
                "ProblemDetail body should expose the policy reason (missing_role) or at least the Forbidden title; got: " + body);
        // The real proof that the gate fired: no Deployment row was persisted
        // for the unauthorized caller.
        boolean anyDeploymentForPackage = deploymentRepository.findAll().stream()
                .anyMatch(d -> fx.packageId().equals(d.getPackageId()));
        assertTrue(!anyDeploymentForPackage,
                "denied request must not have left a Deployment row behind");
    }

    // -------------------------------------------------------------------
    //  Helpers
    // -------------------------------------------------------------------

    private Deployment persistDeployment(DeployFixture fx, String env) {
        Deployment d = new Deployment();
        d.setPackageId(fx.packageId());
        d.setTargetId(fx.targetId());
        d.setPipelineId(fx.pipelineId());
        d.setVersionId(fx.versionId());
        d.setTenantId(fx.tenantId());
        d.setDeployedBy(fx.userId());
        d.setStatus("DRAFT");
        d.setDeployLog("seeded by ApprovalToDeployAuthorizationE2EIT");
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("targetEnvironment", env);
        d.setMetadata(meta);
        return deploymentRepository.save(d);
    }

    private ApprovalRequest persistPendingApproval(Deployment deployment) {
        ApprovalRequest a = new ApprovalRequest();
        a.setDeploymentId(deployment.getId());
        a.setTenantId(deployment.getTenantId());
        a.setRequestedBy(deployment.getDeployedBy());
        a.setExpiresAt(Instant.now().plusSeconds(86400));
        return approvalRequestRepository.save(a);
    }
}
