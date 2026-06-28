package com.pulse.storage.service;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import com.pulse.auth.model.Tenant;
import com.pulse.auth.service.TenantGcpConfigService;
import com.pulse.auth.service.TenantGcpCredentialResolver;
import com.pulse.auth.service.TenantService;
import com.pulse.sor.model.Domain;
import com.pulse.sor.repository.DomainRepository;
import com.pulse.storage.model.StorageScaffoldManifest;
import com.pulse.storage.model.StorageScaffoldStatus;
import com.pulse.storage.repository.StorageBackendRepository;
import com.pulse.storage.repository.StorageScaffoldStatusRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * PKT-0012: Service for previewing, gating, and tracking GCP storage scaffold
 * for a tenant's domain. The scaffold covers:
 * <ul>
 *   <li>File lifecycle folders: SRC, Processing, Archive, bad_files, outgoing_extracts</li>
 *   <li>Medallion lake layers: bronze, silver, gold</li>
 *   <li>Reserved prefixes: _quarantine, _checkpoints, _gx_docs</li>
 *   <li>Proof prefixes: _packages, _evidence</li>
 * </ul>
 *
 * <p>Design rules:
 * <ul>
 *   <li>Derives GCP project and principal from tenant GCP config and credential,
 *       never from hardcoded values or ambient gcloud auth.</li>
 *   <li>Storage-backend gcpProject alone is insufficient — tenant GCP config
 *       must be present.</li>
 *   <li>Execution surface exists but is gated: live GCS writes are
 *       OPERATOR_BLOCKED until {@code pulse.storage.scaffold.live-writes-enabled=true}.</li>
 *   <li>Preview is idempotent — repeated calls produce the same manifest
 *       without duplicating or destructively rewriting state.</li>
 *   <li>PKT-FINAL-5: When live writes are enabled, execute creates one
 *       empty {@code _$folder$} marker per scaffold path. Folder creation is
 *       idempotent (412 from doesNotExist precondition is treated as "exists,
 *       no-op"). Partial failure returns 207 Multi-Status.</li>
 * </ul>
 */
@Service
public class StorageScaffoldService {

    private static final Logger log = LoggerFactory.getLogger(StorageScaffoldService.class);

    /**
     * Folder marker convention. {@code _$folder$} is the gsutil default —
     * an empty object at {@code <prefix>/_$folder$} causes the GCS console
     * (and many downstream tools) to render the prefix as a folder.
     */
    private static final String FOLDER_MARKER = "_$folder$";

    private final TenantService tenantService;
    private final TenantGcpConfigService gcpConfigService;
    private final TenantGcpCredentialResolver credentialResolver;
    private final DomainRepository domainRepository;
    private final StorageBackendRepository storageBackendRepository;
    private final StorageScaffoldStatusRepository scaffoldStatusRepository;
    private final GcsStorageClientFactory storageClientFactory;

    /**
     * PKT-FINAL-5: Execution gate for live GCS writes. Safe-by-default
     * false; operator opts in by setting
     * {@code PULSE_STORAGE_SCAFFOLD_LIVE_WRITES_ENABLED=true}.
     */
    @Value("${pulse.storage.scaffold.live-writes-enabled:false}")
    private boolean liveGcsWritesEnabled;

    public StorageScaffoldService(TenantService tenantService,
                                  TenantGcpConfigService gcpConfigService,
                                  TenantGcpCredentialResolver credentialResolver,
                                  DomainRepository domainRepository,
                                  StorageBackendRepository storageBackendRepository,
                                  StorageScaffoldStatusRepository scaffoldStatusRepository,
                                  GcsStorageClientFactory storageClientFactory) {
        this.tenantService = tenantService;
        this.gcpConfigService = gcpConfigService;
        this.credentialResolver = credentialResolver;
        this.domainRepository = domainRepository;
        this.storageBackendRepository = storageBackendRepository;
        this.scaffoldStatusRepository = scaffoldStatusRepository;
        this.storageClientFactory = storageClientFactory;
    }

    /** Test-only setter for the live-writes flag. */
    void setLiveGcsWritesEnabled(boolean enabled) {
        this.liveGcsWritesEnabled = enabled;
    }

    /**
     * Preview the storage scaffold manifest for a tenant. Returns the full
     * manifest without writing to GCS. Records the preview in scaffold status.
     *
     * @param tenantId the tenant to preview scaffold for
     * @return result map containing the manifest or error details
     */
    public Map<String, Object> preview(String tenantId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tenantId", tenantId);
        result.put("action", "preview");

        // Step 1: Validate tenant exists
        Tenant tenant;
        try {
            tenant = tenantService.getTenantEntity(tenantId);
        } catch (Exception e) {
            result.put("status", "failed");
            result.put("error", "Tenant not found: " + tenantId);
            return result;
        }

        // Step 2: Validate tenant GCP config (not storage-backend gcpProject)
        var gcpConfig = gcpConfigService.getConfig(tenantId);
        if (gcpConfig.isEmpty()) {
            result.put("status", "failed");
            result.put("error", "No tenant GCP config found. "
                    + "Storage-backend gcpProject alone is insufficient. "
                    + "Configure via PUT /api/v1/tenants/" + tenantId + "/gcp-config");
            return result;
        }

        // Step 3: Validate tenant GCP credential via resolver probe
        var probe = credentialResolver.probe(tenantId);
        if (!"ready".equals(probe.get("status"))) {
            result.put("status", "failed");
            result.put("error", "Tenant GCP credential not ready: " + probe.get("error"));
            result.put("credentialProbe", probe);
            return result;
        }

        // Step 4: Resolve GCP storage backend for dev environment
        var gcpBackend = storageBackendRepository
                .findByTenantIdAndEnvironmentAndBackend(tenantId, "dev", "GCP");
        if (gcpBackend.isEmpty()) {
            result.put("status", "failed");
            result.put("error", "No GCP storage backend found for tenant " + tenantId
                    + " in dev environment");
            return result;
        }
        var sb = gcpBackend.get();

        // Step 5: Resolve domains for this tenant
        List<Domain> domains = domainRepository.findByTenantIdOrderByNameAsc(tenantId);
        if (domains.isEmpty()) {
            result.put("status", "failed");
            result.put("error", "No domains found for tenant " + tenantId
                    + ". At least one domain is required for scaffold.");
            return result;
        }

        // Step 6: Build manifests for all domains
        String gcpProjectId = gcpConfig.get().getControlPlaneProjectId();
        String gcpRegion = gcpConfig.get().getGcpRegion();
        String serviceAccountEmail = (String) probe.get("serviceAccountEmail");
        String credentialSource = (String) probe.get("credentialSource");

        List<Map<String, Object>> domainManifests = domains.stream().map(domain -> {
            StorageScaffoldManifest manifest = StorageScaffoldManifest.build(
                    tenantId, tenant.getSlug(),
                    domain.getId(), domain.getSlug(),
                    sb.getStorageRootFiles(), sb.getStorageRootLake(),
                    gcpProjectId, gcpRegion,
                    credentialSource, serviceAccountEmail
            );

            // Upsert scaffold status (idempotent)
            upsertPreviewStatus(tenantId, domain.getSlug(), manifest);

            return manifest.toMap();
        }).toList();

        result.put("status", "previewed");
        result.put("tenantSlug", tenant.getSlug());
        result.put("gcpProjectId", gcpProjectId);
        result.put("gcpRegion", gcpRegion);
        result.put("credentialSource", credentialSource);
        result.put("serviceAccountEmail", serviceAccountEmail);
        result.put("privateKeyRedacted", true);
        result.put("domainManifests", domainManifests);
        result.put("domainCount", domains.size());

        log.info("PKT-0012: Storage scaffold preview for tenant {} — {} domain(s)",
                tenantId, domains.size());

        return result;
    }

    /**
     * Execute the storage scaffold — creates GCS folders. Gated by
     * {@code pulse.storage.scaffold.live-writes-enabled}. When the gate is
     * closed, returns operator_blocked (409 at HTTP layer). When open,
     * delegates to {@link #executeInternal} which creates one
     * {@code _$folder$} marker per scaffold path and aggregates per-path
     * results into a 200/207/422 HTTP response shape.
     *
     * @param tenantId the tenant to execute scaffold for
     * @return result map with execution status
     */
    public Map<String, Object> execute(String tenantId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tenantId", tenantId);
        result.put("action", "execute");

        if (!liveGcsWritesEnabled) {
            result.put("status", "operator_blocked");
            result.put("error", "Live GCS writes are not authorized. "
                    + "Storage scaffold execution requires explicit operator authorization. "
                    + "Preview is available via GET /api/v1/tenants/" + tenantId
                    + "/storage-scaffold/preview");
            result.put("gateReason", "pulse.storage.scaffold.live-writes-enabled=false");
            result.put("httpStatus", 409);

            // Mark all existing previewed scaffolds as operator_blocked
            var statuses = scaffoldStatusRepository.findByTenantId(tenantId);
            for (StorageScaffoldStatus s : statuses) {
                if ("previewed".equals(s.getStatus())) {
                    s.setStatus("operator_blocked");
                    s.setExecutionError("Live GCS writes gated");
                    scaffoldStatusRepository.save(s);
                }
            }

            log.info("PKT-0012: Storage scaffold execute BLOCKED for tenant {} — "
                    + "live GCS writes not authorized", tenantId);
            return result;
        }

        return executeInternal(tenantId);
    }

    /**
     * PKT-FINAL-5: Execute live GCS folder marker creation.
     *
     * <p>For each scaffold path (lifecycle/medallion/reserved/proof, across
     * all configured domains), attempts to create an empty
     * {@code gs://<bucket>/<prefix>/_$folder$} object with a
     * {@code doesNotExist} precondition. The precondition makes the call
     * idempotent: an existing folder marker returns HTTP 412 from GCS,
     * which is treated as "exists, no-op."
     *
     * <p>Partial failure is best-effort: every path is attempted, then the
     * aggregate result is returned with an {@code httpStatus} of:
     * <ul>
     *   <li>200 — every path either created or already existed</li>
     *   <li>207 — at least one path failed (Multi-Status)</li>
     *   <li>422 — every path failed</li>
     * </ul>
     */
    Map<String, Object> executeInternal(String tenantId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tenantId", tenantId);
        result.put("action", "execute");

        // Same prerequisite checks as preview — fail closed if config missing.
        Tenant tenant;
        try {
            tenant = tenantService.getTenantEntity(tenantId);
        } catch (Exception e) {
            result.put("status", "failed");
            result.put("error", "Tenant not found: " + tenantId);
            result.put("httpStatus", 404);
            return result;
        }

        var gcpConfig = gcpConfigService.getConfig(tenantId);
        if (gcpConfig.isEmpty()) {
            result.put("status", "failed");
            result.put("error", "No tenant GCP config found");
            result.put("httpStatus", 422);
            return result;
        }

        var probe = credentialResolver.probe(tenantId);
        if (!"ready".equals(probe.get("status"))) {
            result.put("status", "failed");
            result.put("error", "Tenant GCP credential not ready: " + probe.get("error"));
            result.put("httpStatus", 422);
            return result;
        }

        var gcpBackend = storageBackendRepository
                .findByTenantIdAndEnvironmentAndBackend(tenantId, "dev", "GCP");
        if (gcpBackend.isEmpty()) {
            result.put("status", "failed");
            result.put("error", "No GCP storage backend found for tenant " + tenantId);
            result.put("httpStatus", 422);
            return result;
        }
        var sb = gcpBackend.get();

        List<Domain> domains = domainRepository.findByTenantIdOrderByNameAsc(tenantId);
        if (domains.isEmpty()) {
            result.put("status", "failed");
            result.put("error", "No domains found for tenant " + tenantId);
            result.put("httpStatus", 422);
            return result;
        }

        // Build the Storage client once for this execute call.
        Storage storage;
        try {
            storage = storageClientFactory.build(tenantId, gcpConfig.get().getControlPlaneProjectId());
        } catch (Exception e) {
            result.put("status", "failed");
            result.put("error", "Failed to construct GCS client: " + e.getMessage());
            result.put("httpStatus", 422);
            return result;
        }

        String gcpProjectId = gcpConfig.get().getControlPlaneProjectId();
        String gcpRegion = gcpConfig.get().getGcpRegion();
        String serviceAccountEmail = (String) probe.get("serviceAccountEmail");
        String credentialSource = (String) probe.get("credentialSource");

        // BUG-2026-05-26-70b: Provision the unique set of buckets referenced
        // by the manifest BEFORE creating folder markers. Folder marker
        // creation assumes the bucket exists; without this loop, scaffold
        // execute returns 404 for every path in a freshly-onboarded project.
        Set<String> uniqueBuckets = new LinkedHashSet<>();
        uniqueBuckets.add(sb.getStorageRootFiles());
        uniqueBuckets.add(sb.getStorageRootLake());

        int totalBucketsCreated = 0;
        int totalBucketsExists = 0;
        int totalBucketsFailed = 0;
        boolean operatorBlocked = false;
        List<Map<String, Object>> bucketsResults = new ArrayList<>();
        Map<String, Map<String, Object>> bucketResultIndex = new LinkedHashMap<>();
        for (String bucketName : uniqueBuckets) {
            Map<String, Object> br = createBucket(storage, bucketName, gcpProjectId, gcpRegion);
            bucketsResults.add(br);
            bucketResultIndex.put(bucketName, br);
            String s = (String) br.get("status");
            if ("created".equals(s)) {
                totalBucketsCreated++;
            } else if ("exists".equals(s)) {
                totalBucketsExists++;
            } else {
                totalBucketsFailed++;
                if (Boolean.TRUE.equals(br.get("operatorBlocked"))) {
                    operatorBlocked = true;
                }
            }
        }

        int totalCreated = 0;
        int totalExists = 0;
        int totalFailed = 0;
        List<Map<String, Object>> domainResults = new ArrayList<>();

        for (Domain domain : domains) {
            StorageScaffoldManifest manifest = StorageScaffoldManifest.build(
                    tenantId, tenant.getSlug(),
                    domain.getId(), domain.getSlug(),
                    sb.getStorageRootFiles(), sb.getStorageRootLake(),
                    gcpProjectId, gcpRegion,
                    credentialSource, serviceAccountEmail
            );

            List<String> allPaths = new ArrayList<>();
            allPaths.addAll(manifest.lifecycleFolders());
            allPaths.addAll(manifest.medallionFolders());
            allPaths.addAll(manifest.reservedPrefixes());
            allPaths.addAll(manifest.proofPrefixes());

            int domainCreated = 0;
            int domainExists = 0;
            int domainFailed = 0;
            int domainBucketsCreated = 0;
            int domainBucketsExists = 0;
            int domainBucketsFailed = 0;
            for (String bucketName : List.of(sb.getStorageRootFiles(), sb.getStorageRootLake())) {
                Map<String, Object> br = bucketResultIndex.get(bucketName);
                if (br == null) continue;
                String s = (String) br.get("status");
                if ("created".equals(s)) {
                    domainBucketsCreated++;
                } else if ("exists".equals(s)) {
                    domainBucketsExists++;
                } else {
                    domainBucketsFailed++;
                }
            }
            List<Map<String, Object>> pathResults = new ArrayList<>();

            for (String gsPath : allPaths) {
                // BUG-2026-05-26-70b: skip path creation if its bucket
                // failed to provision; surface as failed path with the
                // bucket error to keep counts consistent.
                String bucketName;
                try {
                    bucketName = parseGsPath(gsPath)[0];
                } catch (IllegalArgumentException e) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("gsPath", gsPath);
                    r.put("status", "failed");
                    r.put("error", "Invalid gs:// path: " + gsPath);
                    pathResults.add(r);
                    domainFailed++;
                    continue;
                }
                Map<String, Object> bucketResult = bucketResultIndex.get(bucketName);
                if (bucketResult != null
                        && !"created".equals(bucketResult.get("status"))
                        && !"exists".equals(bucketResult.get("status"))) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("gsPath", gsPath);
                    r.put("status", "failed");
                    r.put("code", bucketResult.get("code"));
                    r.put("error", "Bucket provisioning failed: "
                            + bucketResult.get("error"));
                    pathResults.add(r);
                    domainFailed++;
                    continue;
                }
                Map<String, Object> pr = createFolderMarker(storage, gsPath);
                pathResults.add(pr);
                String status = (String) pr.get("status");
                if ("created".equals(status)) {
                    domainCreated++;
                } else if ("exists".equals(status)) {
                    domainExists++;
                } else {
                    domainFailed++;
                }
            }

            totalCreated += domainCreated;
            totalExists += domainExists;
            totalFailed += domainFailed;

            // Persist per-domain status row.
            String domainStatus;
            String executionError = null;
            if (domainFailed == 0 && domainBucketsFailed == 0) {
                domainStatus = "executed";
            } else if (domainCreated + domainExists > 0
                    || domainBucketsCreated + domainBucketsExists > 0) {
                domainStatus = "partial";
                executionError = domainFailed + " of " + allPaths.size()
                        + " paths failed; " + domainBucketsFailed
                        + " of " + 2 + " buckets failed";
            } else {
                domainStatus = "failed";
                executionError = "All " + allPaths.size()
                        + " paths failed and " + domainBucketsFailed
                        + " buckets failed";
            }
            upsertExecuteStatus(tenantId, domain.getSlug(), manifest, domainStatus, executionError);

            Map<String, Object> dr = new LinkedHashMap<>();
            dr.put("domainSlug", domain.getSlug());
            dr.put("status", domainStatus);
            dr.put("bucketsCreated", domainBucketsCreated);
            dr.put("bucketsExists", domainBucketsExists);
            dr.put("bucketsFailed", domainBucketsFailed);
            dr.put("pathsCreated", domainCreated);
            dr.put("pathsExists", domainExists);
            dr.put("pathsFailed", domainFailed);
            dr.put("paths", pathResults);
            if (executionError != null) {
                dr.put("error", executionError);
            }
            domainResults.add(dr);
        }

        int totalPaths = totalCreated + totalExists + totalFailed;
        String aggregateStatus;
        int httpStatus;
        if (totalFailed == 0 && totalBucketsFailed == 0) {
            aggregateStatus = "executed";
            httpStatus = 200;
        } else if (totalCreated + totalExists > 0
                || totalBucketsCreated + totalBucketsExists > 0) {
            aggregateStatus = "partial";
            httpStatus = 207;
        } else if (operatorBlocked && totalBucketsCreated + totalBucketsExists == 0) {
            // BUG-2026-05-26-70b: all bucket creates blocked by missing IAM
            // → 409 with structured operator_blocked guidance so the
            // operator knows to re-run the BUG-74 bootstrap extension.
            aggregateStatus = "operator_blocked";
            httpStatus = 409;
            result.put("error", "Service account requires storage.buckets.create"
                    + " permission. Re-run scripts/gcp-bootstrap-tenant-provisioner.sh"
                    + " with the BUG-74 IAM extension.");
            result.put("gateReason",
                    "tenant_sa_missing_storage_buckets_create");
        } else {
            aggregateStatus = "failed";
            httpStatus = 422;
        }

        result.put("status", aggregateStatus);
        result.put("httpStatus", httpStatus);
        result.put("totalPaths", totalPaths);
        result.put("created", totalCreated);
        result.put("exists", totalExists);
        result.put("failed", totalFailed);
        // BUG-2026-05-26-70b: bucket counts + per-bucket evidence
        result.put("bucketsCreated", totalBucketsCreated);
        result.put("bucketsExists", totalBucketsExists);
        result.put("bucketsFailed", totalBucketsFailed);
        result.put("bucketsResults", bucketsResults);
        result.put("pathsCreated", totalCreated);
        result.put("pathsExists", totalExists);
        result.put("pathsFailed", totalFailed);
        result.put("gcpProjectId", gcpProjectId);
        result.put("credentialSource", credentialSource);
        result.put("serviceAccountEmail", serviceAccountEmail);
        result.put("privateKeyRedacted", true);
        result.put("domainResults", domainResults);

        log.info("PKT-FINAL-5/BUG-70b: Storage scaffold execute for tenant {} — "
                        + "bucketsCreated={}, bucketsExists={}, bucketsFailed={}, "
                        + "pathsCreated={}, pathsExists={}, pathsFailed={}, http={}",
                tenantId, totalBucketsCreated, totalBucketsExists, totalBucketsFailed,
                totalCreated, totalExists, totalFailed, httpStatus);

        return result;
    }

    /**
     * BUG-2026-05-26-70b: Create a single GCS bucket idempotently. Maps the
     * GCS response codes to a stable per-bucket evidence shape consumed by
     * the controller response.
     *
     * <ul>
     *   <li>{@code created} (200) — bucket did not exist, was created</li>
     *   <li>{@code exists} (409) — bucket already exists, idempotent no-op</li>
     *   <li>{@code failed} + {@code operatorBlocked=true} (403) — service
     *       account lacks {@code storage.buckets.create}; operator must run
     *       the bootstrap script with the BUG-74 IAM extension before
     *       scaffold execute will succeed</li>
     *   <li>{@code failed} (other) — any other GCS error</li>
     * </ul>
     */
    private Map<String, Object> createBucket(Storage storage, String bucketName,
                                             String gcpProjectId, String gcpRegion) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("bucket", bucketName);
        BucketInfo.Builder builder = BucketInfo.newBuilder(bucketName);
        if (gcpRegion != null && !gcpRegion.isBlank()) {
            builder.setLocation(gcpRegion);
        }
        BucketInfo info = builder.build();
        try {
            storage.create(info);
            r.put("status", "created");
            r.put("code", 200);
            return r;
        } catch (StorageException e) {
            int code = e.getCode();
            r.put("code", code);
            if (code == 409) {
                // Conflict — bucket already exists. Treat as idempotent.
                r.put("status", "exists");
                return r;
            }
            r.put("status", "failed");
            if (code == 403) {
                r.put("operatorBlocked", true);
                r.put("error", "Service account requires storage.buckets.create"
                        + " permission. Re-run scripts/gcp-bootstrap-tenant-provisioner.sh"
                        + " with the BUG-74 IAM extension.");
            } else {
                r.put("error", "GCS error " + code + ": " + e.getMessage());
            }
            return r;
        } catch (Exception e) {
            r.put("status", "failed");
            r.put("error", "Unexpected error creating bucket '" + bucketName
                    + "': " + e.getMessage());
            return r;
        }
    }

    /**
     * Create a single folder marker object in GCS. Returns a path-result
     * map describing the outcome.
     *
     * <p>{@code gsPath} is a fully-qualified {@code gs://bucket/prefix/}
     * URL; the bucket name is the first path segment, the object key is
     * the remainder followed by {@code _$folder$}.
     *
     * <p>Status mapping:
     * <ul>
     *   <li>{@code created} — blob did not exist, was created</li>
     *   <li>{@code exists} — blob already existed (412 precondition failed)</li>
     *   <li>{@code failed} — IAM denied (403), bucket not found (404), or
     *       any other GCS error</li>
     * </ul>
     */
    private Map<String, Object> createFolderMarker(Storage storage, String gsPath) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("gsPath", gsPath);
        String bucket;
        String prefix;
        try {
            String[] parts = parseGsPath(gsPath);
            bucket = parts[0];
            prefix = parts[1];
        } catch (IllegalArgumentException e) {
            r.put("status", "failed");
            r.put("error", "Invalid gs:// path: " + gsPath);
            return r;
        }
        String objectName = prefix.endsWith("/") ? prefix + FOLDER_MARKER
                : prefix + "/" + FOLDER_MARKER;
        BlobInfo info = BlobInfo.newBuilder(BlobId.of(bucket, objectName))
                .setContentType("application/x-empty")
                .build();
        try {
            storage.create(info, new byte[0],
                    Storage.BlobTargetOption.doesNotExist());
            r.put("status", "created");
            r.put("object", "gs://" + bucket + "/" + objectName);
            return r;
        } catch (StorageException e) {
            int code = e.getCode();
            if (code == 412) {
                // Precondition failed → object already exists, idempotent no-op.
                r.put("status", "exists");
                r.put("object", "gs://" + bucket + "/" + objectName);
                return r;
            }
            r.put("status", "failed");
            r.put("code", code);
            if (code == 403) {
                r.put("error", "IAM denied for principal on bucket '" + bucket
                        + "'. Grant roles/storage.objectAdmin on the tenant SA.");
            } else if (code == 404) {
                r.put("error", "Bucket not found: '" + bucket
                        + "'. Create the bucket before running scaffold execute.");
            } else {
                r.put("error", "GCS error " + code + ": " + e.getMessage());
            }
            return r;
        } catch (Exception e) {
            r.put("status", "failed");
            r.put("error", "Unexpected error: " + e.getMessage());
            return r;
        }
    }

    /**
     * Parse a {@code gs://bucket/prefix/...} URL into {@code [bucket, prefix]}.
     * The prefix preserves trailing slashes from the manifest entries.
     */
    static String[] parseGsPath(String gsPath) {
        if (gsPath == null || !gsPath.startsWith("gs://")) {
            throw new IllegalArgumentException("Expected gs:// URL: " + gsPath);
        }
        String rest = gsPath.substring("gs://".length());
        int slash = rest.indexOf('/');
        if (slash < 0) {
            throw new IllegalArgumentException("Missing object prefix in: " + gsPath);
        }
        String bucket = rest.substring(0, slash);
        String prefix = rest.substring(slash + 1);
        // Remove a trailing slash on the prefix (we'll re-add when joining with
        // the marker) so we don't end up with `prefix//_$folder$`.
        if (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return new String[]{bucket, prefix};
    }

    /**
     * Read back the current scaffold status for a tenant.
     *
     * @param tenantId the tenant to check status for
     * @return result map with per-domain scaffold statuses
     */
    public Map<String, Object> getStatus(String tenantId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tenantId", tenantId);

        List<StorageScaffoldStatus> statuses = scaffoldStatusRepository.findByTenantId(tenantId);

        if (statuses.isEmpty()) {
            result.put("status", "not_scaffolded");
            result.put("error", "No scaffold status found for tenant " + tenantId
                    + ". Preview scaffold first via GET /api/v1/tenants/" + tenantId
                    + "/storage-scaffold/preview");
            return result;
        }

        List<Map<String, Object>> domainStatuses = statuses.stream().map(s -> {
            Map<String, Object> ds = new LinkedHashMap<>();
            ds.put("domainSlug", s.getDomainSlug());
            ds.put("status", s.getStatus());
            ds.put("gcpProjectId", s.getGcpProjectId());
            ds.put("serviceAccountEmail", s.getServiceAccountEmail());
            ds.put("credentialSource", s.getCredentialSource());
            ds.put("entryCount", s.getEntryCount());
            ds.put("lastPreviewedAt", s.getLastPreviewedAt() != null
                    ? s.getLastPreviewedAt().toString() : null);
            ds.put("lastExecutedAt", s.getLastExecutedAt() != null
                    ? s.getLastExecutedAt().toString() : null);
            ds.put("executionError", s.getExecutionError());
            return ds;
        }).toList();

        // Aggregate status: all must be 'executed' for overall 'executed'
        boolean allExecuted = statuses.stream()
                .allMatch(s -> "executed".equals(s.getStatus()));
        boolean anyBlocked = statuses.stream()
                .anyMatch(s -> "operator_blocked".equals(s.getStatus()));

        String overallStatus;
        if (allExecuted) {
            overallStatus = "executed";
        } else if (anyBlocked) {
            overallStatus = "operator_blocked";
        } else {
            overallStatus = "previewed";
        }

        result.put("status", overallStatus);
        result.put("domainStatuses", domainStatuses);
        result.put("domainCount", statuses.size());

        return result;
    }

    /**
     * Build readiness category for storageScaffold consumption by
     * tenant readiness endpoint.
     *
     * @param tenantId the tenant to check
     * @return readiness category map
     */
    public Map<String, Object> buildReadinessCategory(String tenantId) {
        Map<String, Object> category = new LinkedHashMap<>();

        // Check GCP config prerequisite
        var gcpConfig = gcpConfigService.getConfig(tenantId);
        if (gcpConfig.isEmpty()) {
            category.put("status", "blocked");
            category.put("blocker", "missing_gcp_config");
            category.put("error", "Tenant GCP config not set. Scaffold requires tenant-level "
                    + "GCP config, not storage-backend gcpProject.");
            return category;
        }

        // Check credential prerequisite
        var probe = credentialResolver.probe(tenantId);
        if (!"ready".equals(probe.get("status"))) {
            category.put("status", "blocked");
            category.put("blocker", "missing_gcp_credential");
            category.put("error", "Tenant GCP credential not ready: " + probe.get("error"));
            return category;
        }

        // Check scaffold status
        List<StorageScaffoldStatus> statuses = scaffoldStatusRepository.findByTenantId(tenantId);
        if (statuses.isEmpty()) {
            category.put("status", "not_scaffolded");
            category.put("error", "Storage scaffold not yet previewed or executed.");
            return category;
        }

        boolean allExecuted = statuses.stream()
                .allMatch(s -> "executed".equals(s.getStatus()));
        boolean anyBlocked = statuses.stream()
                .anyMatch(s -> "operator_blocked".equals(s.getStatus()));

        if (allExecuted) {
            category.put("status", "executed");
        } else if (anyBlocked) {
            category.put("status", "operator_blocked");
            category.put("blocker", "live_gcs_writes_gated");
        } else {
            category.put("status", "previewed");
        }

        category.put("domainCount", statuses.size());
        category.put("gcpProjectId", gcpConfig.get().getControlPlaneProjectId());
        category.put("credentialSource", (String) probe.get("credentialSource"));

        return category;
    }

    private void upsertPreviewStatus(String tenantId, String domainSlug,
                                     StorageScaffoldManifest manifest) {
        Optional<StorageScaffoldStatus> existing =
                scaffoldStatusRepository.findByTenantIdAndDomainSlug(tenantId, domainSlug);

        StorageScaffoldStatus status;
        if (existing.isPresent()) {
            status = existing.get();
        } else {
            status = new StorageScaffoldStatus();
            status.setTenantId(tenantId);
            status.setDomainSlug(domainSlug);
        }

        status.setStatus("previewed");
        status.setGcpProjectId(manifest.gcpProjectId());
        status.setServiceAccountEmail(manifest.serviceAccountEmail());
        status.setCredentialSource(manifest.credentialSource());
        status.setEntryCount(manifest.totalEntries());
        status.setLastPreviewedAt(Instant.now());
        status.setExecutionError(null);

        scaffoldStatusRepository.save(status);
    }

    private void upsertExecuteStatus(String tenantId, String domainSlug,
                                     StorageScaffoldManifest manifest,
                                     String executeStatus,
                                     String executionError) {
        Optional<StorageScaffoldStatus> existing =
                scaffoldStatusRepository.findByTenantIdAndDomainSlug(tenantId, domainSlug);

        StorageScaffoldStatus status;
        if (existing.isPresent()) {
            status = existing.get();
        } else {
            status = new StorageScaffoldStatus();
            status.setTenantId(tenantId);
            status.setDomainSlug(domainSlug);
        }

        status.setStatus(executeStatus);
        status.setGcpProjectId(manifest.gcpProjectId());
        status.setServiceAccountEmail(manifest.serviceAccountEmail());
        status.setCredentialSource(manifest.credentialSource());
        status.setEntryCount(manifest.totalEntries());
        status.setLastExecutedAt(Instant.now());
        status.setExecutionError(executionError);

        scaffoldStatusRepository.save(status);
    }
}
