package com.pulse.secret.service;

import com.pulse.auth.service.TenantService;
import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.config.GcpEnvironmentConfig;
import com.pulse.config.TenantConfig.TenantDefinition;
import com.pulse.deploy.environment.DeploymentEnvironment;
import com.pulse.sor.model.ConnectorInstance;
import com.pulse.sor.model.CredentialProfile;
import com.pulse.sor.model.CredentialStatus;
import com.pulse.sor.model.Domain;
import com.pulse.sor.model.SystemOfRecord;
import com.pulse.sor.repository.ConnectorInstanceRepository;
import com.pulse.sor.repository.CredentialProfileRepository;
import com.pulse.sor.repository.DomainRepository;
import com.pulse.sor.repository.SystemOfRecordRepository;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class CredentialPersistenceService {

    private final CredentialProfileRepository credRepo;
    private final ConnectorInstanceRepository ciRepo;
    private final SystemOfRecordRepository sorRepo;
    private final DomainRepository domainRepo;
    private final TenantService tenantService;
    private final GcpSecretManagerService gcpSecretManagerService;
    private final GcpEnvironmentConfig gcpEnvironmentConfig;
    private final SecretReferenceService secretReferenceService;

    public CredentialPersistenceService(
            CredentialProfileRepository credRepo,
            ConnectorInstanceRepository ciRepo,
            SystemOfRecordRepository sorRepo,
            DomainRepository domainRepo,
            TenantService tenantService,
            GcpSecretManagerService gcpSecretManagerService,
            GcpEnvironmentConfig gcpEnvironmentConfig,
            SecretReferenceService secretReferenceService) {
        this.credRepo = credRepo;
        this.ciRepo = ciRepo;
        this.sorRepo = sorRepo;
        this.domainRepo = domainRepo;
        this.tenantService = tenantService;
        this.gcpSecretManagerService = gcpSecretManagerService;
        this.gcpEnvironmentConfig = gcpEnvironmentConfig;
        this.secretReferenceService = secretReferenceService;
    }

    public CredentialProfile persistCredential(String ciId, String envInput, Map<String, Object> requestBody) {
        // Phase 1: canonicalize at the service entry so direct callers
        // (chat tool, fixtures, future services) cannot bypass the
        // controller-level normalization and write 'DEV' into
        // credential_profiles.environment.
        final String env = DeploymentEnvironment.normalize(envInput);
        ConnectorInstance ci = ciRepo.findById(ciId)
                .orElseThrow(() -> new ResourceNotFoundException("ConnectorInstance", ciId));
        SystemOfRecord sor = sorRepo.findById(ci.getSorId())
                .orElseThrow(() -> new ResourceNotFoundException("SystemOfRecord", ci.getSorId()));
        TenantDefinition tenant = tenantService.getTenant(sor.getTenantId());

        String tenantSlug = tenant.getSlug();
        String domainSlug = resolveDomainSlug(sor);
        String resourceKind = resolveResourceKind(sor);

        Map<String, Object> metadata = castObjectMap(requestBody == null ? null : requestBody.get("metadata"));
        Map<String, String> secretRefs = castStringMap(requestBody == null ? null : requestBody.get("secretRefs"));
        Map<String, Object> secretValues = castObjectMap(requestBody == null ? null : requestBody.get("secretValues"));

        Map<String, String> resolvedSecretRefs = new LinkedHashMap<>(secretRefs);

        for (var entry : secretValues.entrySet()) {
            String fieldName = entry.getKey();
            Object rawValue = entry.getValue();
            if (!(rawValue instanceof String stringValue)) {
                continue;
            }
            if (stringValue.isBlank() || "••••••••".equals(stringValue)) {
                continue;
            }
            if (secretReferenceService.isSecretReference(stringValue)) {
                resolvedSecretRefs.put(fieldName, stringValue);
                continue;
            }
            SecretNamingContext context = new SecretNamingContext(
                    env,
                    tenantSlug,
                    domainSlug,
                    resourceKind,
                    ci.getName(),
                    fieldName,
                    ci.getId()
            );
            String secretId = gcpSecretManagerService.buildSecretId(context);
            Map<String, String> labels = Map.of(
                    "tenant_id", sor.getTenantId(),
                    "domain_id", nullToEmpty(sor.getDomainId()),
                    "resource_kind", resourceKind,
                    "resource_id", ci.getId(),
                    "field_name", fieldName,
                    "environment", env == null ? "" : env
            );
            String reference = gcpSecretManagerService.createOrUpdateSecret(env, secretId, stringValue, labels);
            resolvedSecretRefs.put(fieldName, reference);
        }

        CredentialProfile cred = credRepo.findByConnectorInstanceIdAndEnvironment(ciId, env)
                .orElseGet(() -> {
                    CredentialProfile cp = new CredentialProfile();
                    cp.setConnectorInstanceId(ciId);
                    cp.setEnvironment(env);
                    return cp;
                });

        Map<String, Object> mergedMetadata = new LinkedHashMap<>(cred.getConnectionMetadata());
        Map<String, String> mergedSecretRefs = new LinkedHashMap<>(cred.getSecretReferences());

        for (var entry : metadata.entrySet()) {
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            if (value instanceof String stringValue) {
                if (stringValue.isBlank() && isSecretLikeKey(entry.getKey())) {
                    continue;
                }
                if ("••••••••".equals(stringValue) && isSecretLikeKey(entry.getKey())) {
                    continue;
                }
                if (secretReferenceService.isSecretReference(stringValue)) {
                    mergedSecretRefs.put(entry.getKey(), stringValue);
                    mergedMetadata.remove(entry.getKey());
                    continue;
                }
            }
            mergedMetadata.put(entry.getKey(), value);
            mergedSecretRefs.remove(entry.getKey());
        }

        for (var entry : resolvedSecretRefs.entrySet()) {
            String reference = entry.getValue();
            if (reference == null || reference.isBlank()) {
                continue;
            }
            mergedSecretRefs.put(entry.getKey(), reference);
            mergedMetadata.remove(entry.getKey());
        }

        cred.setConnectionMetadata(mergedMetadata);
        cred.setSecretReferences(mergedSecretRefs);
        cred.setStatus(CredentialStatus.UNTESTED);
        try {
            cred.setSecretProjectId(gcpEnvironmentConfig.resolveProjectId(env));
        } catch (IllegalArgumentException ignored) {
            cred.setSecretProjectId(null);
        }
        return sanitize(credRepo.save(cred));
    }

    public CredentialProfile skipCredential(String ciId, String envInput) {
        // Same canonicalization rule as persistCredential — direct callers
        // must not bypass the canonical key contract.
        final String env = DeploymentEnvironment.normalize(envInput);
        ciRepo.findById(ciId)
                .orElseThrow(() -> new ResourceNotFoundException("ConnectorInstance", ciId));
        CredentialProfile cred = credRepo.findByConnectorInstanceIdAndEnvironment(ciId, env)
                .orElseGet(() -> {
                    CredentialProfile cp = new CredentialProfile();
                    cp.setConnectorInstanceId(ciId);
                    cp.setEnvironment(env);
                    return cp;
                });
        cred.setConnectionMetadata(Map.of());
        cred.setSecretReferences(Map.of());
        cred.setStatus(CredentialStatus.SKIPPED);
        cred.setSecretProjectId(null);
        return sanitize(credRepo.save(cred));
    }

    public CredentialProfile sanitize(CredentialProfile credential) {
        CredentialProfile sanitized = new CredentialProfile();
        sanitized.setId(credential.getId());
        sanitized.setConnectorInstanceId(credential.getConnectorInstanceId());
        sanitized.setEnvironment(credential.getEnvironment());
        sanitized.setStatus(credential.getStatus());
        sanitized.setLastTestedAt(credential.getLastTestedAt());
        sanitized.setLastValidatedAt(credential.getLastValidatedAt());
        sanitized.setValidationCategory(credential.getValidationCategory());
        sanitized.setValidationReason(credential.getValidationReason());
        sanitized.setSecretProjectId(credential.getSecretProjectId());
        sanitized.setConnectionConfig(redactConnectionConfig(credential.getConnectionConfig()));
        sanitized.setMetadataConfig(extractMetadataConfig(credential.getConnectionConfig()));
        sanitized.setSecretRefs(extractSecretRefs(credential.getConnectionConfig()));
        sanitized.setSecretMetadata(extractSecretMetadata(credential.getConnectionConfig()));
        sanitized.setRuntimeSecretBindings(secretReferenceService.collectBindings(credential).stream()
                .map(binding -> new CredentialProfile.RuntimeSecretBindingMetadata(
                        binding.fieldName(),
                        binding.envVarName(),
                        binding.deliveryMode().name()
                ))
                .collect(Collectors.toList()));
        return sanitized;
    }

    private String resolveDomainSlug(SystemOfRecord sor) {
        if (sor.getDomainId() == null || sor.getDomainId().isBlank()) {
            return "default";
        }
        return domainRepo.findById(sor.getDomainId())
                .map(Domain::getSlug)
                .filter(s -> s != null && !s.isBlank())
                .orElse("default");
    }

    private String resolveResourceKind(SystemOfRecord sor) {
        Map<String, Object> metadata = sor.getMetadata();
        if (metadata == null) {
            return "source";
        }
        Object registryType = metadata.get("registry_type");
        if (registryType instanceof String registryTypeStr
                && "TARGET".equalsIgnoreCase(registryTypeStr)) {
            return "target";
        }
        return "source";
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castObjectMap(Object value) {
        if (value instanceof Map<?, ?> mapValue) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (var entry : mapValue.entrySet()) {
                if (entry.getKey() instanceof String key) {
                    result.put(key, entry.getValue());
                }
            }
            return result;
        }
        return new LinkedHashMap<>();
    }

    private Map<String, String> castStringMap(Object value) {
        Map<String, String> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> mapValue) {
            for (var entry : mapValue.entrySet()) {
                if (entry.getKey() instanceof String key && entry.getValue() instanceof String stringValue) {
                    result.put(key, stringValue);
                }
            }
        }
        return result;
    }

    private Map<String, Object> redactConnectionConfig(Map<String, Object> connectionConfig) {
        Map<String, Object> sanitized = new HashMap<>();
        if (connectionConfig == null) {
            return sanitized;
        }
        for (var entry : connectionConfig.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String && (isSecretLikeKey(entry.getKey()) || secretReferenceService.isSecretReference(value))) {
                sanitized.put(entry.getKey(), "••••••••");
            } else {
                sanitized.put(entry.getKey(), value);
            }
        }
        return sanitized;
    }

    private Map<String, Object> extractMetadataConfig(Map<String, Object> connectionConfig) {
        Map<String, Object> metadataConfig = new HashMap<>();
        if (connectionConfig == null) {
            return metadataConfig;
        }
        for (var entry : connectionConfig.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String stringValue
                    && (isSecretLikeKey(entry.getKey()) || secretReferenceService.isSecretReference(stringValue))) {
                continue;
            }
            metadataConfig.put(entry.getKey(), value);
        }
        return metadataConfig;
    }

    private Map<String, String> extractSecretRefs(Map<String, Object> connectionConfig) {
        Map<String, String> secretRefs = new HashMap<>();
        if (connectionConfig == null) {
            return secretRefs;
        }
        for (var entry : connectionConfig.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String stringValue && secretReferenceService.isSecretReference(stringValue)) {
                secretRefs.put(entry.getKey(), stringValue);
            }
        }
        return secretRefs;
    }

    private Map<String, CredentialProfile.SecretFieldMetadata> extractSecretMetadata(Map<String, Object> connectionConfig) {
        Map<String, CredentialProfile.SecretFieldMetadata> metadata = new HashMap<>();
        if (connectionConfig == null) {
            return metadata;
        }
        for (var entry : connectionConfig.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String stringValue
                    && (isSecretLikeKey(entry.getKey()) || secretReferenceService.isSecretReference(stringValue))) {
                metadata.put(
                        entry.getKey(),
                        new CredentialProfile.SecretFieldMetadata(
                                true,
                                secretReferenceService.isSecretReference(stringValue)
                        )
                );
            }
        }
        return metadata;
    }

    private boolean isSecretLikeKey(String key) {
        String normalized = key == null ? "" : key.toLowerCase(Locale.ROOT);
        return normalized.contains("password")
                || normalized.contains("secret")
                || normalized.endsWith("_token")
                || normalized.equals("token")
                || normalized.equals("api_key")
                || normalized.equals("private_key")
                || normalized.equals("refresh_token")
                || normalized.equals("credentials_json")
                || normalized.equals("service_account")
                || normalized.equals("sasl_password")
                || normalized.equals("client_secret");
    }

}
