package com.pulse.secret.controller;

import com.pulse.secret.model.SecretAuthorityReadiness;
import com.pulse.secret.service.SecretAuthorityReadinessService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST surface for secret authority mode readiness.
 * <p>
 * Exposes whether the system's secret authority is provable for real-world
 * GCP-backed scenarios, and provides redacted readbacks of tenant GCP
 * Secret Manager configuration when applicable.
 * <p>
 * No secret material is ever returned through these endpoints.
 */
@RestController
@RequestMapping("/api/v1")
public class SecretAuthorityReadinessController {

    private final SecretAuthorityReadinessService readinessService;

    public SecretAuthorityReadinessController(SecretAuthorityReadinessService readinessService) {
        this.readinessService = readinessService;
    }

    /**
     * GET /api/v1/connector-instances/{ciId}/secret-authority
     * <p>
     * Returns the secret authority mode and readiness for a connector instance's
     * credential surface. Includes redacted tenant GCP SM metadata when applicable.
     */
    @GetMapping("/connector-instances/{ciId}/secret-authority")
    public ResponseEntity<Map<String, Object>> getConnectorInstanceSecretAuthority(
            @PathVariable String ciId,
            @RequestParam(defaultValue = "dev") String environment,
            @RequestParam String tenantId) {

        SecretAuthorityReadiness readiness = readinessService.computeForConnectorInstance(
                tenantId, ciId, environment);

        return ResponseEntity.ok(toResponseMap(readiness));
    }

    /**
     * GET /api/v1/tenants/{tenantId}/secret-authority
     * <p>
     * Returns the tenant-level secret authority mode and readiness.
     * Shows whether the tenant can prove GCP-backed secret management
     * or is operating in local-stub (non-proof) mode.
     */
    @GetMapping("/tenants/{tenantId}/secret-authority")
    public ResponseEntity<Map<String, Object>> getTenantSecretAuthority(
            @PathVariable String tenantId) {

        SecretAuthorityReadiness readiness = readinessService.computeForTenant(tenantId);
        return ResponseEntity.ok(toResponseMap(readiness));
    }

    /**
     * GET /api/v1/pipelines/{pipelineId}/credential-readiness/secret-authority
     * <p>
     * Returns secret authority readiness in the context of pipeline credential
     * readiness. Useful for deployment gates that need to verify the secret
     * backend is real-world-provable before proceeding.
     */
    @GetMapping("/pipelines/{pipelineId}/credential-readiness/secret-authority")
    public ResponseEntity<Map<String, Object>> getPipelineCredentialSecretAuthority(
            @PathVariable String pipelineId,
            @RequestParam(defaultValue = "dev") String environment,
            @RequestParam String tenantId) {

        SecretAuthorityReadiness readiness = readinessService.computeForTenant(tenantId);

        Map<String, Object> response = toResponseMap(readiness);
        response.put("pipelineId", pipelineId);
        response.put("environment", environment);
        return ResponseEntity.ok(response);
    }

    private Map<String, Object> toResponseMap(SecretAuthorityReadiness readiness) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("secretAuthorityMode", readiness.mode().name());
        response.put("proofStatus", readiness.proofStatus().name());
        response.put("credentialSource", readiness.credentialSource());
        response.put("validationCategory", readiness.validationCategory());
        response.put("ready", readiness.isReady());
        response.put("redactedContext", readiness.redactedContext());
        return response;
    }
}
