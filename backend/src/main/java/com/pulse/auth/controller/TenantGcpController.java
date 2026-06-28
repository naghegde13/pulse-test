package com.pulse.auth.controller;

import com.pulse.auth.model.TenantGcpConfig;
import com.pulse.auth.service.GcpRoleManifestValidator;
import com.pulse.auth.service.TenantGcpConfigService;
import com.pulse.auth.service.TenantGcpCredentialResolver;
import com.pulse.auth.service.TenantGcpCredentialService;
import com.pulse.common.exception.ResourceNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Product API surface for tenant GCP configuration, credential management,
 * and identity probe. All endpoints are under
 * {@code /api/v1/tenants/{tenantId}/gcp-*}.
 * <p>
 * Security: These endpoints follow the same auth pattern as
 * {@link TenantController} — gated by Spring Security filter chain.
 * The credential submission endpoint is a secret-bearing path; the readback
 * endpoint returns only redacted metadata, never private key material.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}")
public class TenantGcpController {

    private final TenantGcpConfigService configService;
    private final TenantGcpCredentialService credentialService;
    private final TenantGcpCredentialResolver credentialResolver;
    private final GcpRoleManifestValidator roleValidator;

    public TenantGcpController(TenantGcpConfigService configService,
                               TenantGcpCredentialService credentialService,
                               TenantGcpCredentialResolver credentialResolver,
                               GcpRoleManifestValidator roleValidator) {
        this.configService = configService;
        this.credentialService = credentialService;
        this.credentialResolver = credentialResolver;
        this.roleValidator = roleValidator;
    }

    // ---- GCP Config ----

    @GetMapping("/gcp-config")
    public ResponseEntity<Map<String, Object>> getGcpConfig(@PathVariable String tenantId) {
        try {
            TenantGcpConfig config = configService.getConfig(tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException("TenantGcpConfig", tenantId));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("tenantId", config.getTenantId());
            body.put("gcpProjectId", config.getControlPlaneProjectId());
            body.put("gcpRegion", config.getGcpRegion());
            body.put("status", config.getStatus());
            return ResponseEntity.ok(body);
        } catch (ResourceNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @PutMapping("/gcp-config")
    public ResponseEntity<Map<String, Object>> setGcpConfig(
            @PathVariable String tenantId,
            @RequestBody GcpConfigRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request body is required");
        }
        try {
            TenantGcpConfig config = configService.setConfig(
                    tenantId, request.gcpProjectId(), request.gcpRegion());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("tenantId", config.getTenantId());
            body.put("gcpProjectId", config.getControlPlaneProjectId());
            body.put("gcpRegion", config.getGcpRegion());
            body.put("status", config.getStatus());
            return ResponseEntity.ok(body);
        } catch (ResourceNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    // ---- GCP Credentials (secret-bearing path) ----

    @GetMapping("/gcp-credentials")
    public ResponseEntity<Map<String, Object>> getGcpCredentials(@PathVariable String tenantId) {
        return credentialService.getRedactedCredential(tenantId)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No GCP credential configured for tenant: " + tenantId));
    }

    @PutMapping("/gcp-credentials")
    public ResponseEntity<Map<String, Object>> submitGcpCredentials(
            @PathVariable String tenantId,
            @RequestBody GcpCredentialRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request body is required");
        }
        try {
            Map<String, Object> readback = credentialService.submitCredential(
                    tenantId, request.serviceAccountJson());
            return ResponseEntity.ok(readback);
        } catch (ResourceNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * IMPERSONATION-mode credential submission. Stores only the tenant SA email;
     * no key material is captured anywhere. The control-plane project ID is
     * inferred from the email's project suffix. If the optional
     * {@code controlPlaneProjectId} field is supplied, it MUST match the inferred
     * value; mismatches reject with 400 to prevent silent misconfiguration.
     *
     * <p>Fixes BUG-2026-05-26-71: the service method
     * {@link TenantGcpCredentialService#submitImpersonationCredential(String, String)}
     * shipped in BUG-48 / PKT-FINAL-6 but had no HTTP endpoint until this route
     * was added. STATIC_KEY (PUT {@code /gcp-credentials}) is deprecated; new
     * tenants should use this route.
     *
     * @param tenantId path-variable tenant identifier
     * @param request  {@link ImpersonationCredentialRequest} with email + optional project
     * @return redacted credential readback (no key material) — 200 OK
     */
    @PostMapping("/gcp-credentials/impersonation")
    public ResponseEntity<Map<String, Object>> submitImpersonationGcpCredentials(
            @PathVariable String tenantId,
            @RequestBody ImpersonationCredentialRequest request) {
        if (request == null
                || request.tenantServiceAccountEmail() == null
                || request.tenantServiceAccountEmail().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "tenantServiceAccountEmail is required");
        }
        try {
            Map<String, Object> readback = credentialService.submitImpersonationCredential(
                    tenantId, request.tenantServiceAccountEmail());
            // If caller supplied an explicit controlPlaneProjectId, it MUST match
            // the project inferred from the email. Mismatch is a 400 — silently
            // accepting it would let a typo split the control-plane address space.
            String suppliedProject = request.controlPlaneProjectId();
            String inferredProject = (String) readback.get("controlPlaneProjectId");
            if (suppliedProject != null && !suppliedProject.isBlank()
                    && inferredProject != null
                    && !suppliedProject.strip().toLowerCase().equals(inferredProject)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "controlPlaneProjectId '" + suppliedProject + "' does not match "
                                + "project inferred from tenantServiceAccountEmail ('"
                                + inferredProject + "'). Omit controlPlaneProjectId to use "
                                + "the inferred value, or correct the email.");
            }
            return ResponseEntity.ok(readback);
        } catch (ResourceNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    // ---- Identity Probe ----

    @GetMapping("/gcp-identity-probe")
    public ResponseEntity<Map<String, Object>> probeGcpIdentity(@PathVariable String tenantId) {
        Map<String, Object> result = credentialResolver.probe(tenantId);
        return ResponseEntity.ok(result);
    }

    // ---- Role Manifest Validation ----

    @PostMapping("/gcp-role-manifest/validate")
    public ResponseEntity<Map<String, Object>> validateRoleManifest(
            @PathVariable String tenantId,
            @RequestBody RoleManifestRequest request) {
        if (request == null || request.roles() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Request body with roles list is required");
        }
        var result = roleValidator.validate(request.roles());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", tenantId);
        body.put("status", result.status());
        body.put("errors", result.errors());
        body.put("warnings", result.warnings());
        body.put("hasGcsAccess", result.hasGcsAccess());
        body.put("hasSecretManagerAccess", result.hasSecretManagerAccess());
        body.put("iamBindingExecution", "OPERATOR_BLOCKED");
        return ResponseEntity.ok(body);
    }

    @GetMapping("/gcp-role-manifest/recommended")
    public ResponseEntity<Map<String, Object>> getRecommendedRoleManifest(
            @PathVariable String tenantId) {
        // Use project from tenant config if available, otherwise use a placeholder
        String projectId = configService.getConfig(tenantId)
                .map(TenantGcpConfig::getControlPlaneProjectId)
                .orElse("(configure via PUT /gcp-config first)");
        Map<String, Object> manifest = roleValidator.getRecommendedManifest(projectId);
        manifest.put("tenantId", tenantId);
        manifest.put("iamBindingExecution", "OPERATOR_BLOCKED");
        return ResponseEntity.ok(manifest);
    }

    // ---- Request Records ----

    public record GcpConfigRequest(String gcpProjectId, String gcpRegion) {}
    public record GcpCredentialRequest(String serviceAccountJson) {}
    public record RoleManifestRequest(List<String> roles) {}

    /**
     * Request body for {@link #submitImpersonationGcpCredentials}.
     *
     * <p>{@code tenantServiceAccountEmail} is required and must match
     * {@code <name>@<project>.iam.gserviceaccount.com}. {@code controlPlaneProjectId}
     * is optional — it is inferred from the email's project suffix. If supplied,
     * it must match the inferred value (controller rejects mismatch with 400).
     * Surfacing both fields lets the frontend prefill the project box for the
     * operator (improves error legibility when validating the email), without
     * granting the client authority to split the inferred-vs-supplied state.
     */
    public record ImpersonationCredentialRequest(String tenantServiceAccountEmail,
                                                  String controlPlaneProjectId) {}
}
