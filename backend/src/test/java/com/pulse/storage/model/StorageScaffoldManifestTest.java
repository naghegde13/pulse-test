package com.pulse.storage.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PKT-0012 — StorageScaffoldManifest value-object tests.
 */
class StorageScaffoldManifestTest {

    @Test
    void build_producesCorrectLifecycleFolders() {
        var manifest = buildTestManifest();

        List<String> lifecycle = manifest.lifecycleFolders();
        assertEquals(5, lifecycle.size());
        assertEquals("gs://files-bucket/lending/SRC/", lifecycle.get(0));
        assertEquals("gs://files-bucket/lending/Processing/", lifecycle.get(1));
        assertEquals("gs://files-bucket/lending/Archive/", lifecycle.get(2));
        assertEquals("gs://files-bucket/lending/bad_files/", lifecycle.get(3));
        assertEquals("gs://files-bucket/lending/outgoing_extracts/", lifecycle.get(4));
    }

    @Test
    void build_producesCorrectMedallionFolders() {
        var manifest = buildTestManifest();

        List<String> medallion = manifest.medallionFolders();
        assertEquals(3, medallion.size());
        assertEquals("gs://lake-bucket/lending/bronze/", medallion.get(0));
        assertEquals("gs://lake-bucket/lending/silver/", medallion.get(1));
        assertEquals("gs://lake-bucket/lending/gold/", medallion.get(2));
    }

    @Test
    void build_producesCorrectReservedPrefixes() {
        var manifest = buildTestManifest();

        List<String> reserved = manifest.reservedPrefixes();
        assertEquals(3, reserved.size());
        assertEquals("gs://lake-bucket/lending/_quarantine/", reserved.get(0));
        assertEquals("gs://lake-bucket/lending/_checkpoints/", reserved.get(1));
        assertEquals("gs://lake-bucket/lending/_gx_docs/", reserved.get(2));
    }

    @Test
    void build_producesCorrectProofPrefixes() {
        var manifest = buildTestManifest();

        List<String> proof = manifest.proofPrefixes();
        assertEquals(2, proof.size());
        assertEquals("gs://lake-bucket/lending/_packages/", proof.get(0));
        assertEquals("gs://lake-bucket/lending/_evidence/", proof.get(1));
    }

    @Test
    void totalEntries_correctCount() {
        var manifest = buildTestManifest();
        assertEquals(13, manifest.totalEntries()); // 5 + 3 + 3 + 2
    }

    @Test
    void toMap_includesAllFields() {
        var manifest = buildTestManifest();
        Map<String, Object> map = manifest.toMap();

        assertEquals("t1", map.get("tenantId"));
        assertEquals("acme-lending", map.get("tenantSlug"));
        assertEquals("d1", map.get("domainId"));
        assertEquals("lending", map.get("domainSlug"));
        assertEquals("files-bucket", map.get("storageRootFiles"));
        assertEquals("lake-bucket", map.get("storageRootLake"));
        assertEquals("proj-1", map.get("gcpProjectId"));
        assertEquals("us-central1", map.get("gcpRegion"));
        assertEquals("tenant_postgres", map.get("credentialSource"));
        assertEquals("sa@proj.iam.gserviceaccount.com", map.get("serviceAccountEmail"));
        assertEquals(true, map.get("privateKeyRedacted"));
        assertNotNull(map.get("lifecycleFolders"));
        assertNotNull(map.get("medallionFolders"));
        assertNotNull(map.get("reservedPrefixes"));
        assertNotNull(map.get("proofPrefixes"));
        assertNotNull(map.get("generatedAt"));
    }

    @Test
    void toMap_neverContainsPrivateKey() {
        var manifest = buildTestManifest();
        String serialized = manifest.toMap().toString();

        assertFalse(serialized.contains("private_key"));
        assertFalse(serialized.contains("BEGIN RSA"));
        assertFalse(serialized.contains("BEGIN PRIVATE"));
        assertTrue(serialized.contains("privateKeyRedacted=true"));
    }

    @Test
    void build_isDeterministic() {
        var m1 = buildTestManifest();
        var m2 = buildTestManifest();

        assertEquals(m1.lifecycleFolders(), m2.lifecycleFolders());
        assertEquals(m1.medallionFolders(), m2.medallionFolders());
        assertEquals(m1.reservedPrefixes(), m2.reservedPrefixes());
        assertEquals(m1.proofPrefixes(), m2.proofPrefixes());
    }

    private StorageScaffoldManifest buildTestManifest() {
        return StorageScaffoldManifest.build(
                "t1", "acme-lending",
                "d1", "lending",
                "files-bucket", "lake-bucket",
                "proj-1", "us-central1",
                "tenant_postgres", "sa@proj.iam.gserviceaccount.com"
        );
    }
}
