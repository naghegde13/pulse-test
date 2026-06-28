package com.pulse.storage.model;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable manifest describing the GCP storage scaffold for a tenant.
 * Covers file-lifecycle folders, medallion lake layers, and reserved/proof
 * prefixes. This is a value object — not a JPA entity. The scaffold status
 * entity ({@link StorageScaffoldStatus}) tracks whether this manifest has
 * been previewed or executed.
 *
 * <p>PKT-0012: The manifest is deterministic for a given (tenant, domain,
 * storage-backend) tuple. Repeated generation produces identical content,
 * satisfying the idempotency requirement.
 */
public record StorageScaffoldManifest(
        String tenantId,
        String tenantSlug,
        String domainId,
        String domainSlug,
        String storageRootFiles,
        String storageRootLake,
        String gcpProjectId,
        String gcpRegion,
        String credentialSource,
        String serviceAccountEmail,
        List<String> lifecycleFolders,
        List<String> medallionFolders,
        List<String> reservedPrefixes,
        List<String> proofPrefixes,
        Instant generatedAt
) {

    /**
     * Build a manifest from the resolved tenant/storage/credential context.
     */
    public static StorageScaffoldManifest build(
            String tenantId,
            String tenantSlug,
            String domainId,
            String domainSlug,
            String storageRootFiles,
            String storageRootLake,
            String gcpProjectId,
            String gcpRegion,
            String credentialSource,
            String serviceAccountEmail) {

        String filesBase = "gs://" + storageRootFiles + "/" + domainSlug;
        String lakeBase = "gs://" + storageRootLake + "/" + domainSlug;

        List<String> lifecycle = List.of(
                filesBase + "/SRC/",
                filesBase + "/Processing/",
                filesBase + "/Archive/",
                filesBase + "/bad_files/",
                filesBase + "/outgoing_extracts/"
        );

        List<String> medallion = List.of(
                lakeBase + "/bronze/",
                lakeBase + "/silver/",
                lakeBase + "/gold/"
        );

        List<String> reserved = List.of(
                lakeBase + "/_quarantine/",
                lakeBase + "/_checkpoints/",
                lakeBase + "/_gx_docs/"
        );

        List<String> proof = List.of(
                lakeBase + "/_packages/",
                lakeBase + "/_evidence/"
        );

        return new StorageScaffoldManifest(
                tenantId, tenantSlug, domainId, domainSlug,
                storageRootFiles, storageRootLake,
                gcpProjectId, gcpRegion,
                credentialSource, serviceAccountEmail,
                Collections.unmodifiableList(lifecycle),
                Collections.unmodifiableList(medallion),
                Collections.unmodifiableList(reserved),
                Collections.unmodifiableList(proof),
                Instant.now()
        );
    }

    /**
     * Serialize to a stable map for JSON responses. Never includes
     * private key material.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tenantId", tenantId);
        m.put("tenantSlug", tenantSlug);
        m.put("domainId", domainId);
        m.put("domainSlug", domainSlug);
        m.put("storageRootFiles", storageRootFiles);
        m.put("storageRootLake", storageRootLake);
        m.put("gcpProjectId", gcpProjectId);
        m.put("gcpRegion", gcpRegion);
        m.put("credentialSource", credentialSource);
        m.put("serviceAccountEmail", serviceAccountEmail);
        m.put("privateKeyRedacted", true);
        m.put("lifecycleFolders", lifecycleFolders);
        m.put("medallionFolders", medallionFolders);
        m.put("reservedPrefixes", reservedPrefixes);
        m.put("proofPrefixes", proofPrefixes);
        m.put("generatedAt", generatedAt.toString());
        return m;
    }

    /** Total number of scaffold entries (folders + prefixes). */
    public int totalEntries() {
        return lifecycleFolders.size() + medallionFolders.size()
                + reservedPrefixes.size() + proofPrefixes.size();
    }
}
