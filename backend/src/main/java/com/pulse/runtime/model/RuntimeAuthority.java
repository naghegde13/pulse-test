package com.pulse.runtime.model;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record RuntimeAuthority(
        RuntimePersona activePersona,
        String displayName,
        Set<String> allowedTargetTypes,
        Set<String> allowedStorageBackends,
        Set<String> allowedOrchestrators,
        Set<String> allowedComputeRuntimes,
        Set<String> allowedStorageKinds,
        Set<String> allowedCatalogs,
        Set<RuntimePersona> allowedBrokerPeers,
        Map<String, List<String>> allowedMaterializations,
        SecretAuthorityKind secretAuthority,
        String legalRuntimeMatrixVersion
) {
    public boolean isTargetTypeAllowed(String targetType) {
        return targetType != null && allowedTargetTypes.contains(targetType);
    }

    public boolean isStorageBackendAllowed(String backend) {
        return backend != null && allowedStorageBackends.contains(backend);
    }

    public boolean isMaterializationAllowed(String layer, String format) {
        if (layer == null || format == null) return false;
        List<String> allowed = allowedMaterializations.get(layer.toLowerCase());
        if (allowed == null) return false;
        return allowed.contains(format.toLowerCase());
    }

    public boolean isCatalogAllowed(String catalog) {
        return catalog != null && allowedCatalogs.contains(catalog);
    }

    public boolean isBrokerPeerAllowed(RuntimePersona persona) {
        return persona != null && allowedBrokerPeers.contains(persona);
    }
}
