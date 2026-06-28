package com.pulse.sor.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.pulse.common.model.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "credential_profiles")
public class CredentialProfile extends BaseEntity {

    public static final String CANONICAL_METADATA_KEY = "metadata";
    public static final String CANONICAL_SECRET_REFS_KEY = "secretRefs";

    @Column(name = "connector_instance_id", nullable = false)
    private String connectorInstanceId;

    @Column(nullable = false)
    private String environment;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "connection_config", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> connectionConfig;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CredentialStatus status = CredentialStatus.UNTESTED;

    @Column(name = "last_tested_at")
    private Instant lastTestedAt;

    @Column(name = "last_validated_at")
    private Instant lastValidatedAt;

    @Column(name = "validation_category")
    private String validationCategory;

    @Column(name = "validation_reason")
    private String validationReason;

    @Column(name = "secret_project_id")
    private String secretProjectId;

    @Transient
    private Map<String, SecretFieldMetadata> secretMetadata;

    @Transient
    private Map<String, Object> metadataConfig;

    @Transient
    private Map<String, String> secretRefs;

    @Transient
    private Map<String, Object> connectionMetadata;

    @Transient
    private Map<String, String> secretReferences;

    @Transient
    private List<RuntimeSecretBindingMetadata> runtimeSecretBindings = List.of();

    public String getConnectorInstanceId() { return connectorInstanceId; }
    public void setConnectorInstanceId(String connectorInstanceId) { this.connectorInstanceId = connectorInstanceId; }
    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }
    public Map<String, Object> getConnectionConfig() {
        Map<String, Object> flattened = new LinkedHashMap<>(getConnectionMetadata());
        flattened.putAll(getSecretReferences());
        return flattened;
    }
    public void setConnectionConfig(Map<String, Object> connectionConfig) {
        this.connectionConfig = canonicalizeConnectionConfig(connectionConfig);
        hydrateDerivedState();
    }
    @JsonIgnore
    public Map<String, Object> getCanonicalConnectionConfig() {
        if (connectionConfig == null) {
            connectionConfig = canonicalizeConnectionConfig(null);
        } else if (!isCanonical(connectionConfig)) {
            connectionConfig = canonicalizeConnectionConfig(connectionConfig);
        }
        return cloneCanonicalConfig(connectionConfig);
    }
    public CredentialStatus getStatus() { return status; }
    public void setStatus(CredentialStatus status) { this.status = status; }
    public Instant getLastTestedAt() { return lastTestedAt; }
    public void setLastTestedAt(Instant lastTestedAt) { this.lastTestedAt = lastTestedAt; }
    public Instant getLastValidatedAt() { return lastValidatedAt; }
    public void setLastValidatedAt(Instant lastValidatedAt) { this.lastValidatedAt = lastValidatedAt; }
    public String getValidationCategory() { return validationCategory; }
    public void setValidationCategory(String validationCategory) { this.validationCategory = validationCategory; }
    public String getValidationReason() { return validationReason; }
    public void setValidationReason(String validationReason) { this.validationReason = validationReason; }
    public String getSecretProjectId() { return secretProjectId; }
    public void setSecretProjectId(String secretProjectId) { this.secretProjectId = secretProjectId; }
    public Map<String, SecretFieldMetadata> getSecretMetadata() { return secretMetadata; }
    public void setSecretMetadata(Map<String, SecretFieldMetadata> secretMetadata) { this.secretMetadata = secretMetadata; }
    public Map<String, Object> getMetadataConfig() { return metadataConfig; }
    public void setMetadataConfig(Map<String, Object> metadataConfig) {
        this.metadataConfig = copyObjectMap(metadataConfig);
        this.connectionMetadata = copyObjectMap(metadataConfig);
    }
    public Map<String, String> getSecretRefs() { return secretRefs; }
    public void setSecretRefs(Map<String, String> secretRefs) {
        this.secretRefs = copyStringMap(secretRefs);
        this.secretReferences = copyStringMap(secretRefs);
    }
    @JsonIgnore
    public Map<String, Object> getConnectionMetadata() {
        if (connectionMetadata == null) {
            hydrateDerivedState();
        }
        return new LinkedHashMap<>(connectionMetadata);
    }
    @JsonIgnore
    public void setConnectionMetadata(Map<String, Object> connectionMetadata) {
        this.connectionMetadata = copyObjectMap(connectionMetadata);
        this.metadataConfig = copyObjectMap(connectionMetadata);
        this.connectionConfig = canonicalizeConnectionConfigFromParts(this.connectionMetadata, this.secretReferences);
    }
    @JsonIgnore
    public Map<String, String> getSecretReferences() {
        if (secretReferences == null) {
            hydrateDerivedState();
        }
        return new LinkedHashMap<>(secretReferences);
    }
    @JsonIgnore
    public void setSecretReferences(Map<String, String> secretReferences) {
        this.secretReferences = copyStringMap(secretReferences);
        this.secretRefs = copyStringMap(secretReferences);
        this.connectionConfig = canonicalizeConnectionConfigFromParts(this.connectionMetadata, this.secretReferences);
    }
    @JsonIgnore
    public List<RuntimeSecretBindingMetadata> getRuntimeSecretBindings() {
        return runtimeSecretBindings == null ? List.of() : List.copyOf(runtimeSecretBindings);
    }
    public void setRuntimeSecretBindings(List<RuntimeSecretBindingMetadata> runtimeSecretBindings) {
        this.runtimeSecretBindings = runtimeSecretBindings == null ? List.of() : List.copyOf(runtimeSecretBindings);
    }

    public record SecretFieldMetadata(boolean configured, boolean secretReference) {}
    public record RuntimeSecretBindingMetadata(String fieldName, String envVarName, String deliveryMode) {}

    private void hydrateDerivedState() {
        Map<String, Object> canonical = getCanonicalConnectionConfig();
        connectionMetadata = extractObjectMap(canonical.get(CANONICAL_METADATA_KEY));
        metadataConfig = copyObjectMap(connectionMetadata);
        secretReferences = extractStringMap(canonical.get(CANONICAL_SECRET_REFS_KEY));
        secretRefs = copyStringMap(secretReferences);
        if (runtimeSecretBindings == null) {
            runtimeSecretBindings = List.of();
        }
    }

    private Map<String, Object> canonicalizeConnectionConfig(Map<String, Object> rawConfig) {
        if (isCanonical(rawConfig)) {
            return canonicalizeConnectionConfigFromParts(
                    extractObjectMap(rawConfig.get(CANONICAL_METADATA_KEY)),
                    extractStringMap(rawConfig.get(CANONICAL_SECRET_REFS_KEY))
            );
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        Map<String, String> refs = new LinkedHashMap<>();
        if (rawConfig != null) {
            for (var entry : rawConfig.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof String stringValue && isSecretReference(stringValue)) {
                    refs.put(entry.getKey(), stringValue);
                } else if (value != null) {
                    metadata.put(entry.getKey(), value);
                }
            }
        }
        return canonicalizeConnectionConfigFromParts(metadata, refs);
    }

    private Map<String, Object> canonicalizeConnectionConfigFromParts(
            Map<String, Object> metadata,
            Map<String, String> secretRefs) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put(CANONICAL_METADATA_KEY, copyObjectMap(metadata));
        canonical.put(CANONICAL_SECRET_REFS_KEY, copyStringMap(secretRefs));
        return canonical;
    }

    private boolean isCanonical(Map<String, Object> config) {
        return config != null
                && config.containsKey(CANONICAL_METADATA_KEY)
                && config.containsKey(CANONICAL_SECRET_REFS_KEY);
    }

    private boolean isSecretReference(String value) {
        return value.startsWith("vault://") || value.startsWith("gcp-sm://");
    }

    private Map<String, Object> cloneCanonicalConfig(Map<String, Object> canonical) {
        return canonicalizeConnectionConfigFromParts(
                extractObjectMap(canonical.get(CANONICAL_METADATA_KEY)),
                extractStringMap(canonical.get(CANONICAL_SECRET_REFS_KEY))
        );
    }

    private Map<String, Object> extractObjectMap(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> mapValue) {
            for (var entry : mapValue.entrySet()) {
                if (entry.getKey() instanceof String key && entry.getValue() != null) {
                    result.put(key, entry.getValue());
                }
            }
        }
        return result;
    }

    private Map<String, String> extractStringMap(Object value) {
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

    private Map<String, Object> copyObjectMap(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (source != null) {
            result.putAll(source);
        }
        return result;
    }

    private Map<String, String> copyStringMap(Map<String, String> source) {
        Map<String, String> result = new LinkedHashMap<>();
        if (source != null) {
            result.putAll(source);
        }
        return result;
    }
}
