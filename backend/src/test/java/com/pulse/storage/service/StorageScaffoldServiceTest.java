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
import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.sor.model.Domain;
import com.pulse.sor.repository.DomainRepository;
import com.pulse.storage.model.StorageBackend;
import com.pulse.storage.model.StorageScaffoldStatus;
import com.pulse.storage.repository.StorageBackendRepository;
import com.pulse.storage.repository.StorageScaffoldStatusRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PKT-0012 — StorageScaffoldService contract tests.
 * Covers preview, execute gating, status readback, readiness category,
 * negative blockers, and idempotency.
 */
@ExtendWith(MockitoExtension.class)
class StorageScaffoldServiceTest {

    private static final String TENANT_ID = "tenant-acme-lending";
    private static final String GCP_PROJECT = "acme-lending-prod";
    private static final String GCP_REGION = "us-central1";
    private static final String SA_EMAIL = "sa@acme-lending-prod.iam.gserviceaccount.com";

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
                domainRepository, storageBackendRepository, scaffoldStatusRepository,
                storageClientFactory);
    }

    // ---- Preview: Happy Path ----

    @Test
    void preview_happyPath_returnsManifestWithAllFolders() {
        setupHappyPath();

        var result = service.preview(TENANT_ID);

        assertEquals("previewed", result.get("status"));
        assertEquals(TENANT_ID, result.get("tenantId"));
        assertEquals("acme-lending", result.get("tenantSlug"));
        assertEquals(GCP_PROJECT, result.get("gcpProjectId"));
        assertEquals(GCP_REGION, result.get("gcpRegion"));
        assertEquals("tenant_postgres", result.get("credentialSource"));
        assertEquals(SA_EMAIL, result.get("serviceAccountEmail"));
        assertEquals(true, result.get("privateKeyRedacted"));
        assertEquals(1, result.get("domainCount"));

        @SuppressWarnings("unchecked")
        var manifests = (List<Map<String, Object>>) result.get("domainManifests");
        assertEquals(1, manifests.size());

        Map<String, Object> manifest = manifests.get(0);
        assertEquals("lending", manifest.get("domainSlug"));

        // Verify lifecycle folders
        @SuppressWarnings("unchecked")
        var lifecycle = (List<String>) manifest.get("lifecycleFolders");
        assertEquals(5, lifecycle.size());
        assertTrue(lifecycle.contains("gs://acme-files-dev/lending/SRC/"));
        assertTrue(lifecycle.contains("gs://acme-files-dev/lending/Processing/"));
        assertTrue(lifecycle.contains("gs://acme-files-dev/lending/Archive/"));
        assertTrue(lifecycle.contains("gs://acme-files-dev/lending/bad_files/"));
        assertTrue(lifecycle.contains("gs://acme-files-dev/lending/outgoing_extracts/"));

        // Verify medallion folders
        @SuppressWarnings("unchecked")
        var medallion = (List<String>) manifest.get("medallionFolders");
        assertEquals(3, medallion.size());
        assertTrue(medallion.contains("gs://acme-lake-dev/lending/bronze/"));
        assertTrue(medallion.contains("gs://acme-lake-dev/lending/silver/"));
        assertTrue(medallion.contains("gs://acme-lake-dev/lending/gold/"));

        // Verify reserved prefixes
        @SuppressWarnings("unchecked")
        var reserved = (List<String>) manifest.get("reservedPrefixes");
        assertEquals(3, reserved.size());
        assertTrue(reserved.contains("gs://acme-lake-dev/lending/_quarantine/"));
        assertTrue(reserved.contains("gs://acme-lake-dev/lending/_checkpoints/"));
        assertTrue(reserved.contains("gs://acme-lake-dev/lending/_gx_docs/"));

        // Verify proof prefixes
        @SuppressWarnings("unchecked")
        var proof = (List<String>) manifest.get("proofPrefixes");
        assertEquals(2, proof.size());
        assertTrue(proof.contains("gs://acme-lake-dev/lending/_packages/"));
        assertTrue(proof.contains("gs://acme-lake-dev/lending/_evidence/"));
    }

    @Test
    void preview_recordsScaffoldStatus() {
        setupHappyPath();
        when(scaffoldStatusRepository.findByTenantIdAndDomainSlug(TENANT_ID, "lending"))
                .thenReturn(Optional.empty());

        service.preview(TENANT_ID);

        ArgumentCaptor<StorageScaffoldStatus> captor =
                ArgumentCaptor.forClass(StorageScaffoldStatus.class);
        verify(scaffoldStatusRepository).save(captor.capture());

        StorageScaffoldStatus saved = captor.getValue();
        assertEquals(TENANT_ID, saved.getTenantId());
        assertEquals("lending", saved.getDomainSlug());
        assertEquals("previewed", saved.getStatus());
        assertEquals(GCP_PROJECT, saved.getGcpProjectId());
        assertEquals(SA_EMAIL, saved.getServiceAccountEmail());
        assertEquals("tenant_postgres", saved.getCredentialSource());
        assertEquals(13, saved.getEntryCount()); // 5 lifecycle + 3 medallion + 3 reserved + 2 proof
        assertNotNull(saved.getLastPreviewedAt());
    }

    // ---- Preview: Negative / Blocker Tests ----

    @Test
    void preview_missingTenant_fails() {
        when(tenantService.getTenantEntity(TENANT_ID))
                .thenThrow(new ResourceNotFoundException("Tenant", TENANT_ID));

        var result = service.preview(TENANT_ID);

        assertEquals("failed", result.get("status"));
        assertTrue(((String) result.get("error")).contains("Tenant not found"));
        verify(scaffoldStatusRepository, never()).save(any());
    }

    @Test
    void preview_missingGcpConfig_failsClosed() {
        when(tenantService.getTenantEntity(TENANT_ID)).thenReturn(makeTenant());
        when(gcpConfigService.getConfig(TENANT_ID)).thenReturn(Optional.empty());

        var result = service.preview(TENANT_ID);

        assertEquals("failed", result.get("status"));
        String error = (String) result.get("error");
        assertTrue(error.contains("No tenant GCP config found"));
        assertTrue(error.contains("Storage-backend gcpProject alone is insufficient"));
        verify(scaffoldStatusRepository, never()).save(any());
    }

    @Test
    void preview_missingGcpCredential_failsClosed() {
        when(tenantService.getTenantEntity(TENANT_ID)).thenReturn(makeTenant());
        when(gcpConfigService.getConfig(TENANT_ID)).thenReturn(Optional.of(makeGcpConfig()));

        Map<String, Object> failedProbe = new LinkedHashMap<>();
        failedProbe.put("status", "failed");
        failedProbe.put("error", "No GCP credential found for tenant");
        when(credentialResolver.probe(TENANT_ID)).thenReturn(failedProbe);

        var result = service.preview(TENANT_ID);

        assertEquals("failed", result.get("status"));
        assertTrue(((String) result.get("error")).contains("Tenant GCP credential not ready"));
        verify(scaffoldStatusRepository, never()).save(any());
    }

    @Test
    void preview_unvalidatedCredential_failsClosed() {
        when(tenantService.getTenantEntity(TENANT_ID)).thenReturn(makeTenant());
        when(gcpConfigService.getConfig(TENANT_ID)).thenReturn(Optional.of(makeGcpConfig()));

        Map<String, Object> failedProbe = new LinkedHashMap<>();
        failedProbe.put("status", "failed");
        failedProbe.put("error", "Credential status is 'revoked'; expected 'active'");
        when(credentialResolver.probe(TENANT_ID)).thenReturn(failedProbe);

        var result = service.preview(TENANT_ID);

        assertEquals("failed", result.get("status"));
        verify(scaffoldStatusRepository, never()).save(any());
    }

    @Test
    void preview_missingGcpStorageBackend_fails() {
        when(tenantService.getTenantEntity(TENANT_ID)).thenReturn(makeTenant());
        when(gcpConfigService.getConfig(TENANT_ID)).thenReturn(Optional.of(makeGcpConfig()));
        when(credentialResolver.probe(TENANT_ID)).thenReturn(makeReadyProbe());
        when(storageBackendRepository.findByTenantIdAndEnvironmentAndBackend(TENANT_ID, "dev", "GCP"))
                .thenReturn(Optional.empty());

        var result = service.preview(TENANT_ID);

        assertEquals("failed", result.get("status"));
        assertTrue(((String) result.get("error")).contains("No GCP storage backend"));
    }

    @Test
    void preview_noDomains_fails() {
        when(tenantService.getTenantEntity(TENANT_ID)).thenReturn(makeTenant());
        when(gcpConfigService.getConfig(TENANT_ID)).thenReturn(Optional.of(makeGcpConfig()));
        when(credentialResolver.probe(TENANT_ID)).thenReturn(makeReadyProbe());
        when(storageBackendRepository.findByTenantIdAndEnvironmentAndBackend(TENANT_ID, "dev", "GCP"))
                .thenReturn(Optional.of(makeGcpStorageBackend()));
        when(domainRepository.findByTenantIdOrderByNameAsc(TENANT_ID))
                .thenReturn(Collections.emptyList());

        var result = service.preview(TENANT_ID);

        assertEquals("failed", result.get("status"));
        assertTrue(((String) result.get("error")).contains("No domains found"));
    }

    // ---- Idempotency ----

    @Test
    void preview_repeatedCalls_updateExistingStatus_noDuplication() {
        setupHappyPath();
        StorageScaffoldStatus existing = new StorageScaffoldStatus();
        existing.setTenantId(TENANT_ID);
        existing.setDomainSlug("lending");
        existing.setStatus("previewed");
        when(scaffoldStatusRepository.findByTenantIdAndDomainSlug(TENANT_ID, "lending"))
                .thenReturn(Optional.of(existing));

        // Call preview twice
        var result1 = service.preview(TENANT_ID);
        var result2 = service.preview(TENANT_ID);

        // Both should succeed
        assertEquals("previewed", result1.get("status"));
        assertEquals("previewed", result2.get("status"));

        // Should save (update) existing status, not create new
        verify(scaffoldStatusRepository, times(2)).save(existing);
    }

    @Test
    void preview_manifestContentIsDeterministic() {
        setupHappyPath();

        var result1 = service.preview(TENANT_ID);
        var result2 = service.preview(TENANT_ID);

        @SuppressWarnings("unchecked")
        var manifests1 = (List<Map<String, Object>>) result1.get("domainManifests");
        @SuppressWarnings("unchecked")
        var manifests2 = (List<Map<String, Object>>) result2.get("domainManifests");

        // Folder lists should be identical
        assertEquals(manifests1.get(0).get("lifecycleFolders"),
                manifests2.get(0).get("lifecycleFolders"));
        assertEquals(manifests1.get(0).get("medallionFolders"),
                manifests2.get(0).get("medallionFolders"));
        assertEquals(manifests1.get(0).get("reservedPrefixes"),
                manifests2.get(0).get("reservedPrefixes"));
        assertEquals(manifests1.get(0).get("proofPrefixes"),
                manifests2.get(0).get("proofPrefixes"));
    }

    // ---- Execute: Gate Tests ----

    @Test
    void execute_alwaysReturnsOperatorBlocked() {
        var result = service.execute(TENANT_ID);

        assertEquals("operator_blocked", result.get("status"));
        assertEquals(TENANT_ID, result.get("tenantId"));
        assertEquals("execute", result.get("action"));
        assertNotNull(result.get("error"));
        assertTrue(((String) result.get("error")).contains("Live GCS writes are not authorized"));
        assertEquals("pulse.storage.scaffold.live-writes-enabled=false", result.get("gateReason"));
        assertEquals(409, result.get("httpStatus"));
    }

    @Test
    void execute_marksPreviewedStatusesAsBlocked() {
        StorageScaffoldStatus previewed = new StorageScaffoldStatus();
        previewed.setTenantId(TENANT_ID);
        previewed.setDomainSlug("lending");
        previewed.setStatus("previewed");
        when(scaffoldStatusRepository.findByTenantId(TENANT_ID))
                .thenReturn(List.of(previewed));

        service.execute(TENANT_ID);

        assertEquals("operator_blocked", previewed.getStatus());
        assertEquals("Live GCS writes gated", previewed.getExecutionError());
        verify(scaffoldStatusRepository).save(previewed);
    }

    // ---- Status Readback ----

    @Test
    void getStatus_noScaffold_returnsNotScaffolded() {
        when(scaffoldStatusRepository.findByTenantId(TENANT_ID))
                .thenReturn(Collections.emptyList());

        var result = service.getStatus(TENANT_ID);

        assertEquals("not_scaffolded", result.get("status"));
    }

    @Test
    void getStatus_previewed_returnsPreviewedStatus() {
        StorageScaffoldStatus status = new StorageScaffoldStatus();
        status.setTenantId(TENANT_ID);
        status.setDomainSlug("lending");
        status.setStatus("previewed");
        status.setGcpProjectId(GCP_PROJECT);
        status.setServiceAccountEmail(SA_EMAIL);
        status.setCredentialSource("tenant_postgres");
        status.setEntryCount(13);
        when(scaffoldStatusRepository.findByTenantId(TENANT_ID))
                .thenReturn(List.of(status));

        var result = service.getStatus(TENANT_ID);

        assertEquals("previewed", result.get("status"));
        assertEquals(1, result.get("domainCount"));

        @SuppressWarnings("unchecked")
        var domainStatuses = (List<Map<String, Object>>) result.get("domainStatuses");
        assertEquals(1, domainStatuses.size());
        assertEquals("lending", domainStatuses.get(0).get("domainSlug"));
        assertEquals("previewed", domainStatuses.get(0).get("status"));
    }

    @Test
    void getStatus_blocked_returnsOperatorBlocked() {
        StorageScaffoldStatus status = new StorageScaffoldStatus();
        status.setTenantId(TENANT_ID);
        status.setDomainSlug("lending");
        status.setStatus("operator_blocked");
        when(scaffoldStatusRepository.findByTenantId(TENANT_ID))
                .thenReturn(List.of(status));

        var result = service.getStatus(TENANT_ID);

        assertEquals("operator_blocked", result.get("status"));
    }

    // ---- Readiness Category ----

    @Test
    void readinessCategory_missingGcpConfig_returnsBlocked() {
        when(gcpConfigService.getConfig(TENANT_ID)).thenReturn(Optional.empty());

        var category = service.buildReadinessCategory(TENANT_ID);

        assertEquals("blocked", category.get("status"));
        assertEquals("missing_gcp_config", category.get("blocker"));
    }

    @Test
    void readinessCategory_missingCredential_returnsBlocked() {
        when(gcpConfigService.getConfig(TENANT_ID)).thenReturn(Optional.of(makeGcpConfig()));
        Map<String, Object> failedProbe = new LinkedHashMap<>();
        failedProbe.put("status", "failed");
        failedProbe.put("error", "No credential");
        when(credentialResolver.probe(TENANT_ID)).thenReturn(failedProbe);

        var category = service.buildReadinessCategory(TENANT_ID);

        assertEquals("blocked", category.get("status"));
        assertEquals("missing_gcp_credential", category.get("blocker"));
    }

    @Test
    void readinessCategory_notScaffolded_returnsNotScaffolded() {
        when(gcpConfigService.getConfig(TENANT_ID)).thenReturn(Optional.of(makeGcpConfig()));
        when(credentialResolver.probe(TENANT_ID)).thenReturn(makeReadyProbe());
        when(scaffoldStatusRepository.findByTenantId(TENANT_ID))
                .thenReturn(Collections.emptyList());

        var category = service.buildReadinessCategory(TENANT_ID);

        assertEquals("not_scaffolded", category.get("status"));
    }

    @Test
    void readinessCategory_previewed_returnsPreviewed() {
        when(gcpConfigService.getConfig(TENANT_ID)).thenReturn(Optional.of(makeGcpConfig()));
        when(credentialResolver.probe(TENANT_ID)).thenReturn(makeReadyProbe());
        StorageScaffoldStatus status = new StorageScaffoldStatus();
        status.setStatus("previewed");
        when(scaffoldStatusRepository.findByTenantId(TENANT_ID))
                .thenReturn(List.of(status));

        var category = service.buildReadinessCategory(TENANT_ID);

        assertEquals("previewed", category.get("status"));
        assertEquals(1, category.get("domainCount"));
        assertEquals(GCP_PROJECT, category.get("gcpProjectId"));
        assertEquals("tenant_postgres", category.get("credentialSource"));
    }

    @Test
    void readinessCategory_operatorBlocked_returnsBlocked() {
        when(gcpConfigService.getConfig(TENANT_ID)).thenReturn(Optional.of(makeGcpConfig()));
        when(credentialResolver.probe(TENANT_ID)).thenReturn(makeReadyProbe());
        StorageScaffoldStatus status = new StorageScaffoldStatus();
        status.setStatus("operator_blocked");
        when(scaffoldStatusRepository.findByTenantId(TENANT_ID))
                .thenReturn(List.of(status));

        var category = service.buildReadinessCategory(TENANT_ID);

        assertEquals("operator_blocked", category.get("status"));
        assertEquals("live_gcs_writes_gated", category.get("blocker"));
    }

    // ---- Security: No Private Key Material ----

    @Test
    void preview_neverExposesPrivateKeyMaterial() {
        setupHappyPath();

        var result = service.preview(TENANT_ID);

        String serialized = result.toString();
        assertFalse(serialized.contains("private_key"), "Must not contain private_key");
        assertFalse(serialized.contains("BEGIN RSA"), "Must not contain RSA key material");
        assertFalse(serialized.contains("BEGIN PRIVATE"), "Must not contain private key PEM");
        assertFalse(serialized.contains("encrypted_credential"),
                "Must not contain encrypted credential");
        assertTrue(serialized.contains("privateKeyRedacted=true"),
                "Must explicitly declare privateKeyRedacted=true");
    }

    // ---- Multi-domain ----

    @Test
    void preview_multipleDomains_generatesManifestPerDomain() {
        when(tenantService.getTenantEntity(TENANT_ID)).thenReturn(makeTenant());
        when(gcpConfigService.getConfig(TENANT_ID)).thenReturn(Optional.of(makeGcpConfig()));
        when(credentialResolver.probe(TENANT_ID)).thenReturn(makeReadyProbe());
        when(storageBackendRepository.findByTenantIdAndEnvironmentAndBackend(TENANT_ID, "dev", "GCP"))
                .thenReturn(Optional.of(makeGcpStorageBackend()));

        Domain d1 = makeDomain("d1", "lending");
        Domain d2 = makeDomain("d2", "insurance");
        when(domainRepository.findByTenantIdOrderByNameAsc(TENANT_ID))
                .thenReturn(List.of(d1, d2));

        var result = service.preview(TENANT_ID);

        assertEquals(2, result.get("domainCount"));
        @SuppressWarnings("unchecked")
        var manifests = (List<Map<String, Object>>) result.get("domainManifests");
        assertEquals(2, manifests.size());
        assertEquals("lending", manifests.get(0).get("domainSlug"));
        assertEquals("insurance", manifests.get(1).get("domainSlug"));

        // Each domain has its own lifecycle paths
        @SuppressWarnings("unchecked")
        var l1 = (List<String>) manifests.get(0).get("lifecycleFolders");
        @SuppressWarnings("unchecked")
        var l2 = (List<String>) manifests.get(1).get("lifecycleFolders");
        assertTrue(l1.get(0).contains("/lending/"));
        assertTrue(l2.get(0).contains("/insurance/"));
    }

    // ---- PKT-FINAL-5: executeInternal (live GCS writes enabled) ----

    @Test
    void executeInternal_createsAllPaths() throws Exception {
        setupHappyPath();
        service.setLiveGcsWritesEnabled(true);
        when(storageClientFactory.build(TENANT_ID, GCP_PROJECT)).thenReturn(storage);
        // BUG-70b: bucket-create now runs before folder marker create.
        when(storage.create(any(BucketInfo.class))).thenReturn(null);
        // All Storage.create calls succeed (return a Blob is fine; we don't
        // inspect the return value, only that no StorageException is thrown).
        when(storage.create(any(BlobInfo.class), any(byte[].class),
                any(Storage.BlobTargetOption.class))).thenReturn(null);

        var result = service.execute(TENANT_ID);

        assertEquals("executed", result.get("status"));
        assertEquals(200, result.get("httpStatus"));
        // 5 lifecycle + 3 medallion + 3 reserved + 2 proof = 13 per domain.
        assertEquals(13, result.get("created"));
        assertEquals(0, result.get("exists"));
        assertEquals(0, result.get("failed"));
        verify(storage, times(13)).create(any(BlobInfo.class), any(byte[].class),
                any(Storage.BlobTargetOption.class));
    }

    @Test
    void executeInternal_idempotentOnRerun() throws Exception {
        setupHappyPath();
        service.setLiveGcsWritesEnabled(true);
        when(storageClientFactory.build(TENANT_ID, GCP_PROJECT)).thenReturn(storage);
        // BUG-70b: bucket-create returns 409 (already exists), folder-create
        // returns 412 (already exists). Both idempotent — "executed".
        StorageException conflict = new StorageException(409, "Conflict");
        when(storage.create(any(BucketInfo.class))).thenThrow(conflict);
        // Every doesNotExist precondition fails with 412 — all blobs already
        // exist. This must still be reported as "executed" with status=exists.
        StorageException precondition = new StorageException(412, "Precondition Failed");
        when(storage.create(any(BlobInfo.class), any(byte[].class),
                any(Storage.BlobTargetOption.class))).thenThrow(precondition);

        var result = service.execute(TENANT_ID);

        assertEquals("executed", result.get("status"));
        assertEquals(200, result.get("httpStatus"));
        assertEquals(0, result.get("created"));
        assertEquals(13, result.get("exists"));
        assertEquals(0, result.get("failed"));
    }

    @Test
    void executeInternal_partialFailureReturns207() throws Exception {
        setupHappyPath();
        service.setLiveGcsWritesEnabled(true);
        when(storageClientFactory.build(TENANT_ID, GCP_PROJECT)).thenReturn(storage);
        // BUG-70b: bucket-create succeeds for both buckets so folder-create
        // is reached.
        when(storage.create(any(BucketInfo.class))).thenReturn(null);
        // First call succeeds, second returns 403 (IAM denied), the rest
        // succeed → partial failure should surface as 207 Multi-Status.
        StorageException iamDenied = new StorageException(403, "Forbidden");
        when(storage.create(any(BlobInfo.class), any(byte[].class),
                any(Storage.BlobTargetOption.class)))
                .thenReturn(null)
                .thenThrow(iamDenied)
                .thenReturn(null, null, null, null, null, null, null, null, null, null, null);

        var result = service.execute(TENANT_ID);

        assertEquals("partial", result.get("status"));
        assertEquals(207, result.get("httpStatus"));
        assertEquals(12, result.get("created"));
        assertEquals(0, result.get("exists"));
        assertEquals(1, result.get("failed"));
    }

    @Test
    void executeInternal_allFailureReturns422() throws Exception {
        setupHappyPath();
        service.setLiveGcsWritesEnabled(true);
        when(storageClientFactory.build(TENANT_ID, GCP_PROJECT)).thenReturn(storage);
        // BUG-70b: when every bucket-create fails with a non-403 GCS error
        // (e.g. 500 Internal — neither idempotent nor operator-blocked),
        // every dependent path is short-circuited as failed and the
        // aggregate is 422 Unprocessable. (A 403 surfaces as 409
        // operator_blocked instead — exercised in the new BUG-70b suite.)
        StorageException serverError = new StorageException(500, "Internal");
        when(storage.create(any(BucketInfo.class))).thenThrow(serverError);

        var result = service.execute(TENANT_ID);

        assertEquals("failed", result.get("status"));
        assertEquals(422, result.get("httpStatus"));
        assertEquals(0, result.get("created"));
        assertEquals(0, result.get("exists"));
        assertEquals(13, result.get("failed"));
        assertEquals(0, result.get("bucketsCreated"));
        assertEquals(0, result.get("bucketsExists"));
        assertEquals(2, result.get("bucketsFailed"));
    }

    @Test
    void executeInternal_failsClosedWithoutGcpConfig() {
        // Live writes enabled, but tenant has no GCP config configured.
        service.setLiveGcsWritesEnabled(true);
        when(tenantService.getTenantEntity(TENANT_ID)).thenReturn(makeTenant());
        when(gcpConfigService.getConfig(TENANT_ID)).thenReturn(Optional.empty());

        var result = service.execute(TENANT_ID);

        assertEquals("failed", result.get("status"));
        assertEquals(422, result.get("httpStatus"));
    }

    // ---- Fixture Helpers ----

    private void setupHappyPath() {
        when(tenantService.getTenantEntity(TENANT_ID)).thenReturn(makeTenant());
        when(gcpConfigService.getConfig(TENANT_ID)).thenReturn(Optional.of(makeGcpConfig()));
        when(credentialResolver.probe(TENANT_ID)).thenReturn(makeReadyProbe());
        when(storageBackendRepository.findByTenantIdAndEnvironmentAndBackend(TENANT_ID, "dev", "GCP"))
                .thenReturn(Optional.of(makeGcpStorageBackend()));
        when(domainRepository.findByTenantIdOrderByNameAsc(TENANT_ID))
                .thenReturn(List.of(makeDomain("domain-1", "lending")));
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
