package com.pulse.deploy.projection.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/**
 * ARCH-006 — V2 source-package manifest shape.
 *
 * <p>Extends the Phase 2 {@code deployment-package-manifest.v1} schema
 * with first-class contract refs (table, landing, logical DDL),
 * entrypoint catalog, runtime authority snapshot, and capability
 * profile. The {@link #toMap()} method produces the canonical JSON body
 * persisted inside the package metadata and used by preflight /
 * evidence / adapter layers.
 *
 * <p>Immutable record — callers build via the canonical constructor.
 */
public record SourcePackageManifestV2(
        String manifestVersion,
        String packageId,
        String pipelineId,
        String pipelineVersionId,
        String sourceHash,
        String createdAt,
        String createdBy,
        Map<String, Object> gitProvenance,
        Map<String, Object> runtimeAuthority,
        Map<String, Object> capabilityProfile,
        List<Map<String, Object>> tableContractRefs,
        List<Map<String, Object>> landingContractRefs,
        List<Map<String, Object>> logicalDdlRefs,
        List<Map<String, Object>> fileInventory,
        EntrypointCatalog entrypointCatalog,
        Map<String, Object> compatibility
) {

    /** Default manifest version for ARCH-006 V2 manifests. */
    public static final String DEFAULT_VERSION = "sourcePackageManifest.v2";
    public static final String CONTROL_PLANE_DEPENDENCY_KEY = "controlPlaneDependency";
    public static final String AIRFLOW_CALLBACK_POLICY_KEY = "airflowCallbackPolicy";
    public static final String CONTROL_PLANE_DEPENDENCY_NONE = "NONE";
    public static final String CONTROL_PLANE_DEPENDENCY_REQUIRED = "REQUIRED";
    public static final String AIRFLOW_CALLBACK_POLICY_DISABLED = "DISABLED";
    public static final String AIRFLOW_CALLBACK_POLICY_OPTIONAL = "OPTIONAL";

    /**
     * Convenience constructor that defaults {@code manifestVersion} to
     * {@link #DEFAULT_VERSION}.
     */
    public SourcePackageManifestV2(
            String packageId,
            String pipelineId,
            String pipelineVersionId,
            String sourceHash,
            String createdAt,
            String createdBy,
            Map<String, Object> gitProvenance,
            Map<String, Object> runtimeAuthority,
            Map<String, Object> capabilityProfile,
            List<Map<String, Object>> tableContractRefs,
            List<Map<String, Object>> landingContractRefs,
            List<Map<String, Object>> logicalDdlRefs,
            List<Map<String, Object>> fileInventory,
            EntrypointCatalog entrypointCatalog,
            Map<String, Object> compatibility) {
        this(DEFAULT_VERSION, packageId, pipelineId, pipelineVersionId,
                sourceHash, createdAt, createdBy, gitProvenance,
                runtimeAuthority, capabilityProfile, tableContractRefs,
                landingContractRefs, logicalDdlRefs, fileInventory,
                entrypointCatalog, compatibility);
    }

    /**
     * Produce the canonical JSON-serializable map for persistence.
     * Key order matches the schema declaration for deterministic hashing.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("manifestVersion", manifestVersion);
        map.put("packageId", packageId);
        map.put("pipelineId", pipelineId);
        map.put("pipelineVersionId", pipelineVersionId);
        map.put("sourceHash", sourceHash);
        map.put("createdAt", createdAt);
        map.put("createdBy", createdBy);
        map.put("gitProvenance", gitProvenance != null ? gitProvenance : Map.of());
        map.put("runtimeAuthority", runtimeAuthority != null ? runtimeAuthority : Map.of());
        map.put("capabilityProfile", normalizeCapabilityProfile(capabilityProfile));
        map.put("tableContractRefs", tableContractRefs != null ? tableContractRefs : List.of());
        map.put("landingContractRefs", landingContractRefs != null ? landingContractRefs : List.of());
        map.put("logicalDdlRefs", logicalDdlRefs != null ? logicalDdlRefs : List.of());
        map.put("fileInventory", fileInventory != null ? fileInventory : List.of());
        map.put("entrypointCatalog", entrypointCatalog != null ? entrypointCatalog.toMap() : Map.of());
        map.put("compatibility", compatibility != null ? compatibility : Map.of());
        return map;
    }

    public static Map<String, Object> normalizeCapabilityProfile(Map<String, Object> raw) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        if (raw != null) {
            normalized.putAll(raw);
        }
        normalized.putIfAbsent(CONTROL_PLANE_DEPENDENCY_KEY, CONTROL_PLANE_DEPENDENCY_NONE);
        normalized.putIfAbsent(AIRFLOW_CALLBACK_POLICY_KEY, AIRFLOW_CALLBACK_POLICY_DISABLED);
        return normalized;
    }

    public static String controlPlaneDependency(Map<String, Object> raw) {
        Object value = normalizeCapabilityProfile(raw).get(CONTROL_PLANE_DEPENDENCY_KEY);
        return value == null ? CONTROL_PLANE_DEPENDENCY_NONE : String.valueOf(value);
    }

    public static String airflowCallbackPolicy(Map<String, Object> raw) {
        Object value = normalizeCapabilityProfile(raw).get(AIRFLOW_CALLBACK_POLICY_KEY);
        return value == null ? AIRFLOW_CALLBACK_POLICY_DISABLED : String.valueOf(value);
    }

    public static boolean isOptionalAirflowCallbackPolicy(Map<String, Object> raw) {
        return AIRFLOW_CALLBACK_POLICY_OPTIONAL.equalsIgnoreCase(airflowCallbackPolicy(raw));
    }

    public static boolean isLocalOrDevEnvironment(String environment) {
        if (environment == null || environment.isBlank()) {
            return false;
        }
        String normalized = environment.trim().toLowerCase(Locale.ROOT);
        return "local".equals(normalized) || "dev".equals(normalized);
    }
}
