package com.pulse.sor.controller;

import com.pulse.auth.filter.TenantIsolationEnforcer;
import com.pulse.auth.policy.ActionContext;
import com.pulse.auth.policy.ActorResolverService;
import com.pulse.auth.policy.AuthorizationPolicyService;
import com.pulse.auth.policy.CallerContext;
import com.pulse.auth.policy.CallerSurface;
import com.pulse.auth.policy.PolicyDecision;
import com.pulse.auth.policy.PulseAction;
import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.deploy.environment.DeploymentEnvironment;
import com.pulse.secret.service.CredentialPersistenceService;
import com.pulse.secret.service.CredentialReadinessService;
import com.pulse.secret.service.CredentialValidationService;
import com.pulse.secret.service.SecretManagerException;
import com.pulse.sor.model.ConnectorInstance;
import com.pulse.sor.model.ConnectorType;
import com.pulse.sor.model.CredentialProfile;
import com.pulse.sor.model.SystemOfRecord;
import com.pulse.sor.repository.ConnectorDefinitionRepository;
import com.pulse.sor.repository.ConnectorInstanceRepository;
import com.pulse.sor.repository.CredentialProfileRepository;
import com.pulse.sor.repository.DatasetRepository;
import com.pulse.sor.repository.SystemOfRecordRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
public class ConnectorInstanceController {

    private final ConnectorInstanceRepository ciRepo;
    private final CredentialProfileRepository credRepo;
    private final ConnectorDefinitionRepository connDefRepo;
    private final DatasetRepository datasetRepo;
    private final CredentialPersistenceService credentialPersistenceService;
    private final CredentialReadinessService credentialReadinessService;
    private final CredentialValidationService credentialValidationService;
    private final SystemOfRecordRepository sorRepo;
    private final AuthorizationPolicyService authPolicy;
    private final ActorResolverService actorResolver;

    public ConnectorInstanceController(ConnectorInstanceRepository ciRepo,
                                       CredentialProfileRepository credRepo,
                                       ConnectorDefinitionRepository connDefRepo,
                                       DatasetRepository datasetRepo,
                                       CredentialPersistenceService credentialPersistenceService,
                                       CredentialReadinessService credentialReadinessService,
                                       CredentialValidationService credentialValidationService,
                                       SystemOfRecordRepository sorRepo,
                                       AuthorizationPolicyService authPolicy,
                                       ActorResolverService actorResolver) {
        this.ciRepo = ciRepo;
        this.credRepo = credRepo;
        this.connDefRepo = connDefRepo;
        this.datasetRepo = datasetRepo;
        this.credentialPersistenceService = credentialPersistenceService;
        this.credentialReadinessService = credentialReadinessService;
        this.credentialValidationService = credentialValidationService;
        this.sorRepo = sorRepo;
        this.authPolicy = authPolicy;
        this.actorResolver = actorResolver;
    }

    /** Enforce tenant isolation for SOR-scoped operations. */
    private SystemOfRecord enforceTenantForSor(String sorId) {
        SystemOfRecord sor = sorRepo.findById(sorId)
                .orElseThrow(() -> new ResourceNotFoundException("SystemOfRecord", sorId));
        TenantIsolationEnforcer.enforce(sor.getTenantId());
        return sor;
    }

    /** Enforce tenant isolation for connector-instance direct-ID operations. */
    private ConnectorInstance enforceTenantForConnectorInstance(String ciId) {
        ConnectorInstance ci = ciRepo.findById(ciId)
                .orElseThrow(() -> new ResourceNotFoundException("ConnectorInstance", ciId));
        SystemOfRecord sor = sorRepo.findById(ci.getSorId())
                .orElseThrow(() -> new ResourceNotFoundException("SystemOfRecord", ci.getSorId()));
        TenantIsolationEnforcer.enforce(sor.getTenantId());
        return ci;
    }

    private ConnectorInstance enforceConnectorBelongsToSor(String sorId, String ciId) {
        SystemOfRecord sor = enforceTenantForSor(sorId);
        ConnectorInstance ci = ciRepo.findById(ciId)
                .orElseThrow(() -> new ResourceNotFoundException("ConnectorInstance", ciId));
        if (!sor.getId().equals(ci.getSorId())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "Connector instance does not belong to SOR");
        }
        return ci;
    }

    /**
     * Phase 3: SECRET_METADATA gate. Tenant id is resolved server-side
     * by walking ConnectorInstance → SOR → tenantId so callers cannot
     * spoof tenant scope through path/header.
     */
    private void enforceSecretMetadata(CallerSurface surface, String ciId) {
        ConnectorInstance ci = ciRepo.findById(ciId)
                .orElseThrow(() -> new ResourceNotFoundException("ConnectorInstance", ciId));
        SystemOfRecord sor = sorRepo.findById(ci.getSorId())
                .orElseThrow(() -> new ResourceNotFoundException("SystemOfRecord", ci.getSorId()));
        CallerContext caller = actorResolver.resolve(surface, sor.getTenantId());
        PolicyDecision decision = authPolicy.check(caller, PulseAction.SECRET_METADATA,
                ActionContext.forTenant(sor.getTenantId()));
        if (!decision.allowed()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, decision.denyReason());
        }
    }

    @GetMapping("/api/v1/sors/{sorId}/connectors")
    public ResponseEntity<List<Map<String, Object>>> list(@PathVariable String sorId) {
        enforceTenantForSor(sorId);
        return ResponseEntity.ok(enrich(ciRepo.findBySorIdOrderByNameAsc(sorId), null));
    }

    /**
     * LCT-045/047: connector-type-filtered listing for the Add-Sink picker.
     * Distinguished from {@link #list} by the required {@code type} param so
     * the no-filter path stays backward-compatible. When {@code environment}
     * is supplied each connector is enriched with the strategy-aware
     * {@code credentialReadiness} contract from {@link CredentialReadinessService}.
     */
    @GetMapping(value = "/api/v1/sors/{sorId}/connectors", params = "type")
    public ResponseEntity<List<Map<String, Object>>> listFiltered(
            @PathVariable String sorId,
            @RequestParam ConnectorType type,
            @RequestParam(required = false) String environment) {
        enforceTenantForSor(sorId);
        String canonicalEnv = null;
        if (environment != null && !environment.isBlank()) {
            try {
                canonicalEnv = DeploymentEnvironment.normalize(environment);
            } catch (IllegalArgumentException ignored) {
                canonicalEnv = null;
            }
        }
        return ResponseEntity.ok(enrich(
                ciRepo.findBySorIdAndConnectorTypeOrderByNameAsc(sorId, type), canonicalEnv));
    }

    @GetMapping("/api/v1/tenants/{tenantId}/connector-instances")
    public ResponseEntity<List<Map<String, Object>>> listForTenant(
            @PathVariable String tenantId,
            @RequestParam(required = false) ConnectorType type) {
        List<ConnectorInstance> instances = type == null
                ? ciRepo.findByTenantIdOrderByNameAsc(tenantId)
                : ciRepo.findByTenantIdAndConnectorTypeOrderByNameAsc(tenantId, type);
        return ResponseEntity.ok(enrich(instances, null));
    }

    @PostMapping("/api/v1/sors/{sorId}/connectors")
    public ResponseEntity<ConnectorInstance> create(
            @PathVariable String sorId,
            @RequestBody CreateConnectorRequest request) {
        SystemOfRecord sor = enforceTenantForSor(sorId);
        enforceConnectorDirectionCompatibility(sor, request.connectorDefinitionId());
        ConnectorInstance ci = new ConnectorInstance();
        ci.setSorId(sorId);
        ci.setConnectorDefinitionId(request.connectorDefinitionId());
        ci.setName(request.name());
        ci.setDescription(request.description());
        ci.setConfigTemplate(request.configTemplate() != null ? request.configTemplate() : new HashMap<>());
        ci.setEnabled(true);
        return ResponseEntity.ok(ciRepo.save(ci));
    }

    /**
     * LCT-045(a): a connector instance attached to a TARGET registry must be a
     * DESTINATION-typed connector; a source registry must hold SOURCE-typed
     * connectors. This is the durable server-side authority that prevents a
     * sink being built from source connector metadata (the MongoDB-sink bug).
     * Enforced only when the connector definition is resolvable.
     */
    private void enforceConnectorDirectionCompatibility(SystemOfRecord sor, String connectorDefinitionId) {
        String registryType = declaredRegistryType(sor);
        if (registryType == null) {
            return;
        }
        connDefRepo.findById(connectorDefinitionId).ifPresent(def -> {
            ConnectorType required = "TARGET".equalsIgnoreCase(registryType)
                    ? ConnectorType.DESTINATION : ConnectorType.SOURCE;
            if (def.getConnectorType() != required) {
                throw new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "Connector direction mismatch: " + (required == ConnectorType.DESTINATION ? "target" : "source")
                                + " registry requires a " + required.name() + " connector, but '"
                                + def.getName() + "' is " + def.getConnectorType().name());
            }
        });
    }

    private static String declaredRegistryType(SystemOfRecord sor) {
        Map<String, Object> metadata = sor.getMetadata();
        if (metadata == null) return null;
        Object rt = metadata.get("registry_type");
        return rt instanceof String s ? s : null;
    }

    private boolean isTargetRegistry(SystemOfRecord sor) {
        return "TARGET".equalsIgnoreCase(declaredRegistryType(sor));
    }

    @GetMapping("/api/v1/sors/{sorId}/connectors/{ciId}")
    public ResponseEntity<ConnectorInstance> get(
            @PathVariable String sorId,
            @PathVariable String ciId) {
        return ResponseEntity.ok(enforceConnectorBelongsToSor(sorId, ciId));
    }

    /**
     * Direct GET-by-id (no SOR scope) — used by the configure-dialog's inherited-settings
     * panel to display the linked connector's configTemplate without needing the SOR id.
     * Tenant isolation enforced by resolving ConnectorInstance → SOR → tenantId.
     */
    @GetMapping("/api/v1/connector-instances/{ciId}")
    public ResponseEntity<ConnectorInstance> getById(@PathVariable String ciId) {
        ConnectorInstance ci = ciRepo.findById(ciId)
                .orElseThrow(() -> new ResourceNotFoundException("ConnectorInstance", ciId));
        SystemOfRecord sor = sorRepo.findById(ci.getSorId())
                .orElseThrow(() -> new ResourceNotFoundException("SystemOfRecord", ci.getSorId()));
        TenantIsolationEnforcer.enforce(sor.getTenantId());
        return ResponseEntity.ok(ci);
    }

    @DeleteMapping("/api/v1/sors/{sorId}/connectors/{ciId}")
    public ResponseEntity<Void> delete(
            @PathVariable String sorId,
            @PathVariable String ciId) {
        enforceConnectorBelongsToSor(sorId, ciId);
        ciRepo.deleteById(ciId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/connector-instances/{ciId}/credentials")
    public ResponseEntity<List<CredentialProfile>> listCredentials(@PathVariable String ciId) {
        enforceTenantForConnectorInstance(ciId);
        return ResponseEntity.ok(credRepo.findByConnectorInstanceIdOrderByEnvironmentAsc(ciId).stream()
                .map(credentialPersistenceService::sanitize)
                .collect(Collectors.toList()));
    }

    @PutMapping("/api/v1/connector-instances/{ciId}/credentials/{env}")
    public ResponseEntity<?> upsertCredential(
            @PathVariable String ciId,
            @PathVariable String env,
            @RequestBody Map<String, Object> requestBody) {
        // Phase 1: normalize legacy uppercase envs at the boundary so the
        // persisted credential_profiles.environment row is always canonical.
        String canonicalEnv;
        try {
            canonicalEnv = DeploymentEnvironment.normalize(env);
        } catch (IllegalArgumentException badEnv) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "invalid_environment", "detail", badEnv.getMessage()));
        }
        // Phase 3: SECRET_METADATA policy fires BEFORE persistence so a
        // denied caller never reaches the Secret Manager path.
        enforceSecretMetadata(CallerSurface.UI, ciId);
        try {
            return ResponseEntity.ok(credentialPersistenceService.persistCredential(ciId, canonicalEnv, requestBody));
        } catch (SecretManagerException e) {
            Map<String, String> body = new HashMap<>();
            body.put("error", "Secret Manager operation failed");
            body.put("detail", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
        }
    }

    @PostMapping("/api/v1/connector-instances/{ciId}/credentials/{env}/skip")
    public ResponseEntity<?> skipCredential(
            @PathVariable String ciId,
            @PathVariable String env,
            @RequestBody(required = false) Map<String, Object> ignoredBody) {
        String canonicalEnv;
        try {
            canonicalEnv = DeploymentEnvironment.normalize(env);
        } catch (IllegalArgumentException badEnv) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "invalid_environment", "detail", badEnv.getMessage()));
        }
        enforceSecretMetadata(CallerSurface.UI, ciId);
        return ResponseEntity.ok(credentialPersistenceService.skipCredential(ciId, canonicalEnv));
    }

    @PostMapping("/api/v1/connector-instances/{ciId}/credentials/{env}/validate")
    public ResponseEntity<?> validateCredential(
            @PathVariable String ciId,
            @PathVariable String env,
            @RequestBody(required = false) Map<String, Object> ignoredBody) {
        String canonicalEnv;
        try {
            canonicalEnv = DeploymentEnvironment.normalize(env);
        } catch (IllegalArgumentException badEnv) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "invalid_environment", "detail", badEnv.getMessage()));
        }
        enforceSecretMetadata(CallerSurface.UI, ciId);
        try {
            return ResponseEntity.ok(credentialValidationService.validate(ciId, canonicalEnv));
        } catch (ResourceNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "not_found", "detail", e.getMessage()));
        } catch (SecretManagerException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "Secret Manager operation failed", "detail", e.getMessage()));
        }
    }

    @GetMapping("/api/v1/pipelines/{pipelineId}/credential-readiness")
    public ResponseEntity<?> credentialReadiness(
            @PathVariable String pipelineId,
            @RequestParam String environment) {
        String canonicalEnv;
        try {
            canonicalEnv = DeploymentEnvironment.normalize(environment);
        } catch (IllegalArgumentException badEnv) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "invalid_environment", "detail", badEnv.getMessage()));
        }
        return ResponseEntity.ok(credentialReadinessService.compute(pipelineId, canonicalEnv));
    }

    private List<Map<String, Object>> enrich(List<ConnectorInstance> instances, String environment) {
        return instances.stream().map(ci -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", ci.getId());
            m.put("sorId", ci.getSorId());
            m.put("connectorDefinitionId", ci.getConnectorDefinitionId());
            m.put("name", ci.getName());
            m.put("description", ci.getDescription());
            m.put("configTemplate", ci.getConfigTemplate());
            m.put("enabled", ci.isEnabled());
            m.put("createdAt", ci.getCreatedAt());
            m.put("updatedAt", ci.getUpdatedAt());

            connDefRepo.findById(ci.getConnectorDefinitionId()).ifPresent(def -> {
                m.put("connectorTypeName", def.getName());
                m.put("dockerRepository", def.getDockerRepository());
                m.put("connectorType", def.getConnectorType().name());
                m.put("iconUrl", def.getIconUrl());
                m.put("releaseStage", def.getReleaseStage().name());
                m.put("credentialStrategy", def.getCredentialStrategy().name());
                // LCT-045/048: declared sink capability drives the Add-Sink UI.
                m.put("sinkWriterKind", def.getSinkWriterKind() != null ? def.getSinkWriterKind().name() : null);
                m.put("writeDispositions", def.getWriteDispositions());
            });

            List<CredentialProfile> creds = credRepo.findByConnectorInstanceIdOrderByEnvironmentAsc(ci.getId());
            Map<String, String> credentialStatuses = new HashMap<>();
            for (var cred : creds) {
                credentialStatuses.put(cred.getEnvironment(), cred.getStatus().name());
            }
            m.put("credentialStatuses", credentialStatuses);
            m.put("datasetCount", datasetRepo.countByConnectorInstanceId(ci.getId()));

            // LCT-047: strategy-aware, central readiness contract so the
            // Add-Sink dialog agrees with the connector detail surfaces.
            if (environment != null && !environment.isBlank()) {
                m.put("credentialReadiness",
                        credentialReadinessService.computeForConnector(ci, environment));
            }
            return m;
        }).collect(Collectors.toList());
    }

    record CreateConnectorRequest(String connectorDefinitionId, String name, String description, Map<String, Object> configTemplate) {}
}
