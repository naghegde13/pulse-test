package com.pulse.deploy.projection.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ARCH-006 — Entrypoint catalog embedded inside
 * {@link SourcePackageManifestV2}.
 *
 * <p>Enumerates every executable entrypoint a package declares: DAG
 * files, Spark jobs, dbt run commands, DDL executors, and config
 * artifacts. Preflight uses this catalog to verify entrypoint
 * uniqueness (no ambiguity blocker) and adapter layers use it to
 * resolve the physical execution target for each stage.
 */
public record EntrypointCatalog(
        String catalogVersion,
        List<DagEntrypoint> dagEntrypoints,
        List<SparkEntrypoint> sparkEntrypoints,
        List<DbtEntrypoint> dbtEntrypoints,
        List<DdlEntrypoint> ddlEntrypoints,
        List<ConfigEntrypoint> configEntrypoints,
        String primaryDagId
) {

    /** Default catalog version for ARCH-006. */
    public static final String DEFAULT_VERSION = "entrypointCatalog.v1";

    /**
     * Convenience constructor that defaults {@code catalogVersion} to
     * {@link #DEFAULT_VERSION}.
     */
    public EntrypointCatalog(
            List<DagEntrypoint> dagEntrypoints,
            List<SparkEntrypoint> sparkEntrypoints,
            List<DbtEntrypoint> dbtEntrypoints,
            List<DdlEntrypoint> ddlEntrypoints,
            List<ConfigEntrypoint> configEntrypoints,
            String primaryDagId) {
        this(DEFAULT_VERSION, dagEntrypoints, sparkEntrypoints,
                dbtEntrypoints, ddlEntrypoints, configEntrypoints, primaryDagId);
    }

    /**
     * Produce the canonical JSON-serializable map. Key order matches
     * the schema declaration for deterministic hashing.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("catalogVersion", catalogVersion);
        map.put("primaryDagId", primaryDagId);
        map.put("dagEntrypoints", dagEntrypoints != null
                ? dagEntrypoints.stream().map(DagEntrypoint::toMap).toList()
                : List.of());
        map.put("sparkEntrypoints", sparkEntrypoints != null
                ? sparkEntrypoints.stream().map(SparkEntrypoint::toMap).toList()
                : List.of());
        map.put("dbtEntrypoints", dbtEntrypoints != null
                ? dbtEntrypoints.stream().map(DbtEntrypoint::toMap).toList()
                : List.of());
        map.put("ddlEntrypoints", ddlEntrypoints != null
                ? ddlEntrypoints.stream().map(DdlEntrypoint::toMap).toList()
                : List.of());
        map.put("configEntrypoints", configEntrypoints != null
                ? configEntrypoints.stream().map(ConfigEntrypoint::toMap).toList()
                : List.of());
        return map;
    }

    // ── Inner records ───────────────────────────────────────────────

    public record DagEntrypoint(
            String logicalDagId,
            String dagFilePath,
            String description
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("logicalDagId", logicalDagId);
            m.put("dagFilePath", dagFilePath);
            m.put("description", description);
            return m;
        }
    }

    public record SparkEntrypoint(
            String name,
            String mainPyFile,
            String mainClass,
            Map<String, Object> sparkArgs
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("mainPyFile", mainPyFile);
            m.put("mainClass", mainClass);
            m.put("sparkArgs", sparkArgs != null ? sparkArgs : Map.of());
            return m;
        }
    }

    public record DbtEntrypoint(
            String name,
            String profileTarget,
            String modelSelector,
            String runCommand
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("profileTarget", profileTarget);
            m.put("modelSelector", modelSelector);
            m.put("runCommand", runCommand);
            return m;
        }
    }

    public record DdlEntrypoint(
            String name,
            String executor,
            String dialect,
            int statementCount
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("executor", executor);
            m.put("dialect", dialect);
            m.put("statementCount", statementCount);
            return m;
        }
    }

    public record ConfigEntrypoint(
            String name,
            String path,
            String configType
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("path", path);
            m.put("configType", configType);
            return m;
        }
    }
}
