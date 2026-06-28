package com.pulse.secret.service;

import com.pulse.sor.model.CredentialProfile;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class SecretReferenceService {

    public List<RuntimeSecretBinding> collectBindings(Map<String, Object> connectionConfig) {
        return collectBindingsFromResolvedSecretRefs(secretReferencesFrom(connectionConfig));
    }

    public List<RuntimeSecretBinding> collectBindings(CredentialProfile credentialProfile) {
        if (credentialProfile == null) {
            return List.of();
        }
        return collectBindingsFromResolvedSecretRefs(credentialProfile.getSecretReferences());
    }

    public List<RuntimeSecretBinding> collectBindingsFromSecretReferences(Map<String, String> secretReferences) {
        return collectBindingsFromResolvedSecretRefs(secretReferences);
    }

    public Map<String, String> secretReferencesFrom(Map<String, Object> connectionConfig) {
        Map<String, String> secretReferences = new LinkedHashMap<>();
        if (connectionConfig == null) {
            return secretReferences;
        }

        if (connectionConfig.containsKey(CredentialProfile.CANONICAL_SECRET_REFS_KEY)) {
            Object canonicalSecretRefs = connectionConfig.get(CredentialProfile.CANONICAL_SECRET_REFS_KEY);
            if (canonicalSecretRefs instanceof Map<?, ?> secretRefMap) {
                for (var entry : secretRefMap.entrySet()) {
                    if (entry.getKey() instanceof String key && entry.getValue() instanceof String secretRef && isSecretReference(secretRef)) {
                        secretReferences.put(key, secretRef);
                    }
                }
            }
            return secretReferences;
        }

        for (var entry : connectionConfig.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String secretRef && isSecretReference(secretRef)) {
                secretReferences.put(entry.getKey(), secretRef);
            }
        }

        return secretReferences;
    }

    private List<RuntimeSecretBinding> collectBindingsFromResolvedSecretRefs(Map<String, String> secretReferences) {
        List<RuntimeSecretBinding> bindings = new ArrayList<>();
        if (secretReferences == null) {
            return bindings;
        }

        for (var entry : secretReferences.entrySet()) {
            String secretRef = entry.getValue();
            if (secretRef != null && isSecretReference(secretRef)) {
                bindings.add(bindingFor(entry.getKey(), secretRef));
            }
        }

        return bindings;
    }

    public RuntimeSecretBinding bindingFor(String fieldName, String secretRef) {
        DeliveryMode deliveryMode = deliveryModeForField(fieldName);
        String baseEnvVar = toBaseEnvVarName(secretRef);
        String envVarName = deliveryMode == DeliveryMode.FILE
                ? baseEnvVar + "_FILE"
                : baseEnvVar;

        return new RuntimeSecretBinding(
                fieldName,
                secretRef,
                normalizeForRuntime(secretRef),
                envVarName,
                deliveryMode
        );
    }

    public boolean isSecretReference(Object value) {
        return value instanceof String stringValue && isSecretReference(stringValue);
    }

    public boolean isSecretReference(String value) {
        return value.startsWith("vault://") || value.startsWith("gcp-sm://");
    }

    public DeliveryMode deliveryModeForField(String fieldName) {
        if (fieldName == null) {
            return DeliveryMode.ENV;
        }

        String normalized = fieldName.toLowerCase(Locale.ROOT);
        if (normalized.equals("private_key")
                || normalized.equals("credentials_json")
                || normalized.equals("service_account")
                || normalized.contains("certificate")
                || normalized.endsWith("_cert")
                || normalized.contains("keystore")
                || normalized.contains("truststore")) {
            return DeliveryMode.FILE;
        }

        return DeliveryMode.ENV;
    }

    public String normalizeForRuntime(String secretRef) {
        if (!secretRef.startsWith("gcp-sm://")) {
            return secretRef;
        }

        String withoutScheme = secretRef.substring("gcp-sm://".length());
        if (withoutScheme.contains("/versions/latest")) {
            return "gcp-sm://" + withoutScheme.replace("/versions/latest", "/versions/active");
        }
        if (!withoutScheme.contains("/versions/")) {
            return "gcp-sm://" + withoutScheme + "/versions/active";
        }
        return secretRef;
    }

    private String toBaseEnvVarName(String secretRef) {
        if (secretRef.startsWith("vault://")) {
            String path = secretRef.replace("vault://", "");
            String[] segments = path.split("/");
            StringBuilder envVar = new StringBuilder();
            for (int i = 2; i < segments.length; i++) {
                if (i > 2) {
                    envVar.append("_");
                }
                envVar.append(segments[i].replace("-", "_").toUpperCase(Locale.ROOT));
            }
            return envVar.toString();
        }

        String path = secretRef.replace("gcp-sm://", "");
        String[] segments = path.split("/");
        String secretName = segments.length >= 4 ? segments[3] : path;
        return secretName
                .replaceAll("[^A-Za-z0-9]+", "_")
                .replaceAll("^_+|_+$", "")
                .toUpperCase(Locale.ROOT);
    }

    public enum DeliveryMode {
        ENV,
        FILE
    }

    public record RuntimeSecretBinding(
            String fieldName,
            String secretRef,
            String runtimeSecretRef,
            String envVarName,
            DeliveryMode deliveryMode
    ) {}
}
