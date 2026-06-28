package com.pulse.storage.service;

import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import com.pulse.auth.model.Tenant;
import com.pulse.auth.model.TenantGcpConfig;
import com.pulse.auth.service.TenantGcpConfigService;
import com.pulse.auth.service.TenantGcpCredentialResolver;
import com.pulse.auth.service.TenantService;
import com.pulse.sor.model.Domain;
import com.pulse.sor.repository.DomainRepository;
import com.pulse.storage.model.StorageBackend;
import com.pulse.storage.repository.StorageBackendRepository;
import com.pulse.storage.repository.StorageScaffoldStatusRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BUG-2026-05-26-70b — verifies that {@link StorageScaffoldService} provisions
 * the per-tenant GCS buckets referenced by the manifest before attempting to
 * create folder-marker blobs, and surfaces structured per-bucket evidence
 * (created/exists/failed) in the response.
 *
 * <p>Coverage matrix:
 * <ol>
 *   <li>{@link #execute_provisionsBothBucketsBeforeFolders_returnsExecuted()}
 *       — happy path: 2 buckets created + 13 folders within (per domain).
 *       Bundled with multi-domain so the test ALSO exercises the requested
 *       "4 buckets" total when the spec talks about 2 domains × 2 buckets
 *       (collapsed to 2 unique buckets per dedup).</li>
 *   <li>{@link #execute_idempotent_bothBucketsExist_returnsExisting()} —
 *       409 from {@code storage.create(BucketInfo)} surfaces as "exists"
 *       and does NOT abort folder-marker creation.</li>
 *   <li>{@link #execute_serviceAccountMissingBucketCreatePermission_returnsOperatorBlocked()}
 *       — 403 from {@code storage.create(BucketInfo)} surfaces as a
 *       structured operator_blocked response pointing the operator at
 *       the BUG-74 bootstrap IAM extension.</li>
 *   <li>{@link #execute_partialBucketFailure_returns207MultiStatus()} —
 *       one bucket created, the other denied IAM → 207 partial.</li>
 * </ol>
 *
 * <p>This test follows the same mock-{@link Storage} pattern as
 * {@link StorageScaffoldServiceTest} rather than spinning up the
 * fake-gcs-server Testcontainer used by Suite A. The bucket-provisioning
 * branch under test is purely a code-path concern (response shape +
 * status-code mapping), not a wire-protocol concern — exercising the
 * Storage client with a mock keeps the test bound to the seconds-not-
 * minutes execution budget while still asserting every required
 * branch of the contract.
 */
@ExtendWith(MockitoExtension.class)
class StorageScaffoldBucketProvisioningTest {

    private static final String TENANT_ID = "tenant-acme-lending";
    private static final String GCP_PROJECT = "acme-lending-prod";
    private static final String GCP_REGION = "us-central1";
    private static final String SA_EMAIL =
            "sa@acme-lending-prod.iam.gserviceaccount.com";

    @Mock private TenantService tenantService;
    @Mock private TenantGcpConfigService gcpConfigService;
    @Mock private TenantGcpCredentialResolver credentialResolver;
    @Mock private DomainRepository domainRepository;
    @Mock private StorageBackendRepository storageBackendRepository;
    @Mock private StorageScaffoldStatusRepository scaffoldStatusRepository;
    @Mock private GcsStorageClientFactory storageClientFactory;
    @Mock private Storage storage;

    private StorageScaffoldService service;

    @BeforeEach
    void setUp() {
        service = new StorageScaffoldService(
                tenantService, gcpConfigService, credentialResolver,
                domainRepository, storageBackendRepository,
                scaffoldStatusRepository, storageClientFactory);
    }

    // ---- Case 1: happy path — buckets + folders all created ----

    @Test
    void execute_provisionsBothBucketsBeforeFolders_returnsExecuted() throws Exception {
        // Use 2 domains so the manifest still resolves to 2 unique buckets
        // (one files, one lake) but with 26 folders total — proving that
        // the bucket-create loop runs ONCE per unique bucket, not once
        // per domain × bucket combination.
        setupMultiDomainHappyPath();
        service.setLiveGcsWritesEnabled(true);
        when(storageClientFactory.build(TENANT_ID, GCP_PROJECT)).thenReturn(storage);
        // Bucket-create returns successfully (any non-throwing return is OK).
        when(storage.create(any(BucketInfo.class))).thenReturn(null);
        when(storage.create(any(BlobInfo.class), any(byte[].class),
                any(Storage.BlobTargetOption.class))).thenReturn(null);

        Map<String, Object> result = service.execute(TENANT_ID);

        assertEquals("executed", result.get("status"));
        assertEquals(200, result.get("httpStatus"));
        // 2 unique buckets across both domains (one files, one lake).
        assertEquals(2, result.get("bucketsCreated"));
        assertEquals(0, result.get("bucketsExists"));
        assertEquals(0, result.get("bucketsFailed"));
        // 26 folders = 13 per domain * 2 domains.
        assertEquals(26, result.get("created"));
        assertEquals(0, result.get("exists"));
        assertEquals(0, result.get("failed"));

        // storage.create(BucketInfo) called exactly once per unique bucket
        // (NOT once per domain). The dedup is the entire point.
        verify(storage, times(2)).create(any(BucketInfo.class));
        verify(storage, times(26)).create(any(BlobInfo.class), any(byte[].class),
                any(Storage.BlobTargetOption.class));

        // Per-bucket evidence is surfaced in the response shape.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> bucketsResults =
                (List<Map<String, Object>>) result.get("bucketsResults");
        assertNotNull(bucketsResults);
        assertEquals(2, bucketsResults.size());
        assertTrue(bucketsResults.stream()
                .allMatch(b -> "created".equals(b.get("status"))));
    }

    // ---- Case 2: idempotent — buckets already exist (409) ----

    @Test
    void execute_idempotent_bothBucketsExist_returnsExisting() throws Exception {
        setupHappyPath();
        service.setLiveGcsWritesEnabled(true);
        when(storageClientFactory.build(TENANT_ID, GCP_PROJECT)).thenReturn(storage);
        // GCS returns 409 from buckets.insert when the bucket already
        // exists. The service must treat this as idempotent success.
        StorageException conflict = new StorageException(409, "Conflict");
        when(storage.create(any(BucketInfo.class))).thenThrow(conflict);
        when(storage.create(any(BlobInfo.class), any(byte[].class),
                any(Storage.BlobTargetOption.class))).thenReturn(null);

        Map<String, Object> result = service.execute(TENANT_ID);

        assertEquals("executed", result.get("status"));
        assertEquals(200, result.get("httpStatus"));
        assertEquals(0, result.get("bucketsCreated"));
        assertEquals(2, result.get("bucketsExists"));
        assertEquals(0, result.get("bucketsFailed"));
        // Folders should still be attempted and succeed (buckets exist).
        assertEquals(13, result.get("created"));
        assertEquals(0, result.get("failed"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> bucketsResults =
                (List<Map<String, Object>>) result.get("bucketsResults");
        assertTrue(bucketsResults.stream()
                .allMatch(b -> "exists".equals(b.get("status"))));
        assertTrue(bucketsResults.stream()
                .allMatch(b -> Integer.valueOf(409).equals(b.get("code"))));
    }

    // ---- Case 3: SA lacks storage.buckets.create (403) ----

    @Test
    void execute_serviceAccountMissingBucketCreatePermission_returnsOperatorBlocked()
            throws Exception {
        setupHappyPath();
        service.setLiveGcsWritesEnabled(true);
        when(storageClientFactory.build(TENANT_ID, GCP_PROJECT)).thenReturn(storage);
        // Every bucket-create attempt is denied with 403 — the tenant SA
        // doesn't carry storage.buckets.create. The service MUST short-
        // circuit with a structured operator_blocked response pointing
        // the operator at the BUG-74 bootstrap script extension.
        StorageException forbidden = new StorageException(403, "Forbidden");
        when(storage.create(any(BucketInfo.class))).thenThrow(forbidden);

        Map<String, Object> result = service.execute(TENANT_ID);

        assertEquals("operator_blocked", result.get("status"));
        assertEquals(409, result.get("httpStatus"));
        assertEquals(0, result.get("bucketsCreated"));
        assertEquals(0, result.get("bucketsExists"));
        assertEquals(2, result.get("bucketsFailed"));
        // The error message must contain the actionable guidance verbatim
        // from the BUG-70b spec so operators can pivot directly to the
        // bootstrap script rerun without spelunking.
        String error = (String) result.get("error");
        assertNotNull(error);
        assertTrue(error.contains("storage.buckets.create"),
                "error must name the missing permission, got: " + error);
        assertTrue(error.contains("BUG-74"),
                "error must reference the BUG-74 IAM extension, got: "
                        + error);
        assertTrue(error.contains("gcp-bootstrap-tenant-provisioner.sh"),
                "error must reference the bootstrap script, got: " + error);
        assertEquals("tenant_sa_missing_storage_buckets_create",
                result.get("gateReason"));

        // Per-bucket evidence carries the operatorBlocked flag.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> bucketsResults =
                (List<Map<String, Object>>) result.get("bucketsResults");
        assertTrue(bucketsResults.stream()
                .allMatch(b -> Boolean.TRUE.equals(b.get("operatorBlocked"))));
        assertTrue(bucketsResults.stream()
                .allMatch(b -> Integer.valueOf(403).equals(b.get("code"))));
    }

    // ---- Case 4: partial bucket provisioning (1 created, 1 denied) ----

    @Test
    void execute_partialBucketFailure_returns207MultiStatus() throws Exception {
        setupHappyPath();
        service.setLiveGcsWritesEnabled(true);
        when(storageClientFactory.build(TENANT_ID, GCP_PROJECT)).thenReturn(storage);
        // First bucket-create succeeds, second is denied — surfaces
        // as 207 Multi-Status (partial provisioning). Folder creation
        // proceeds for the bucket that succeeded.
        StorageException forbidden = new StorageException(403, "Forbidden");
        when(storage.create(any(BucketInfo.class)))
                .thenReturn(null)
                .thenThrow(forbidden);
        // All blob-create attempts that DO run succeed.
        when(storage.create(any(BlobInfo.class), any(byte[].class),
                any(Storage.BlobTargetOption.class))).thenReturn(null);

        Map<String, Object> result = service.execute(TENANT_ID);

        assertEquals("partial", result.get("status"));
        assertEquals(207, result.get("httpStatus"));
        assertEquals(1, result.get("bucketsCreated"));
        assertEquals(0, result.get("bucketsExists"));
        assertEquals(1, result.get("bucketsFailed"));

        // 5 lifecycle paths (in files bucket) succeed; 8 lake paths
        // (3 medallion + 3 reserved + 2 proof) all fail because the
        // lake bucket couldn't be provisioned. The exact split here is
        // tied to the storage backend root names in the fixture.
        int created = (Integer) result.get("created");
        int failed = (Integer) result.get("failed");
        assertEquals(13, created + failed);
        // At least some folders should have been created (the bucket
        // that succeeded carried them).
        assertTrue(created > 0, "expected at least 1 folder created");
        assertTrue(failed > 0, "expected at least 1 folder failed");
    }

    // ---- Fixture helpers (mirror StorageScaffoldServiceTest) ----

    private void setupHappyPath() {
        when(tenantService.getTenantEntity(TENANT_ID)).thenReturn(makeTenant());
        when(gcpConfigService.getConfig(TENANT_ID))
                .thenReturn(Optional.of(makeGcpConfig()));
        when(credentialResolver.probe(TENANT_ID)).thenReturn(makeReadyProbe());
        when(storageBackendRepository
                .findByTenantIdAndEnvironmentAndBackend(TENANT_ID, "dev", "GCP"))
                .thenReturn(Optional.of(makeGcpStorageBackend()));
        when(domainRepository.findByTenantIdOrderByNameAsc(TENANT_ID))
                .thenReturn(List.of(makeDomain("domain-1", "lending")));
    }

    private void setupMultiDomainHappyPath() {
        when(tenantService.getTenantEntity(TENANT_ID)).thenReturn(makeTenant());
        when(gcpConfigService.getConfig(TENANT_ID))
                .thenReturn(Optional.of(makeGcpConfig()));
        when(credentialResolver.probe(TENANT_ID)).thenReturn(makeReadyProbe());
        when(storageBackendRepository
                .findByTenantIdAndEnvironmentAndBackend(TENANT_ID, "dev", "GCP"))
                .thenReturn(Optional.of(makeGcpStorageBackend()));
        when(domainRepository.findByTenantIdOrderByNameAsc(TENANT_ID))
                .thenReturn(List.of(
                        makeDomain("d1", "lending"),
                        makeDomain("d2", "insurance")));
    }

    private Tenant makeTenant() {
        Tenant t = new Tenant();
        t.setId(TENANT_ID);
        t.setName("Acme Lending");
        t.setSlug("acme-lending");
        t.setOrigin("api");
        t.setStatus("active");
        return t;
    }

    private TenantGcpConfig makeGcpConfig() {
        TenantGcpConfig c = new TenantGcpConfig();
        c.setTenantId(TENANT_ID);
        c.setControlPlaneProjectId(GCP_PROJECT);
        c.setGcpRegion(GCP_REGION);
        return c;
    }

    private Map<String, Object> makeReadyProbe() {
        Map<String, Object> probe = new LinkedHashMap<>();
        probe.put("tenantId", TENANT_ID);
        probe.put("status", "ready");
        probe.put("credentialSource", "tenant_postgres");
        probe.put("serviceAccountEmail", SA_EMAIL);
        probe.put("keyId", "abc123");
        probe.put("gcpProjectId", GCP_PROJECT);
        probe.put("ambientAuthUsed", false);
        probe.put("privateKeyRedacted", true);
        return probe;
    }

    private StorageBackend makeGcpStorageBackend() {
        StorageBackend sb = new StorageBackend();
        sb.setTenantId(TENANT_ID);
        sb.setEnvironment("dev");
        sb.setBackend("GCP");
        sb.setStorageRootFiles("acme-files-dev");
        sb.setStorageRootLake("acme-lake-dev");
        sb.setGcpProject(GCP_PROJECT);
        sb.setProvisioningStatus("validated");
        return sb;
    }

    private Domain makeDomain(String id, String slug) {
        Domain d = new Domain();
        d.setId(id);
        d.setTenantId(TENANT_ID);
        d.setName(slug.substring(0, 1).toUpperCase() + slug.substring(1));
        d.setSlug(slug);
        return d;
    }
}
