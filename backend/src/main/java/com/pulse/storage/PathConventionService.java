package com.pulse.storage;

import com.pulse.storage.model.FileLifecycle;
import com.pulse.storage.model.LakeFormat;
import com.pulse.storage.model.LakeLayer;
import com.pulse.storage.model.StorageBackend;
import com.pulse.storage.model.StorageBackendType;
import com.pulse.storage.model.TableLocation;
import org.springframework.stereotype.Service;

/**
 * Pure path-construction service for PULSE's locked storage convention
 * (#30 spec). Translates a (storage_backend, layout-fragments) tuple
 * into a fully-qualified {@link TableLocation} or path string.
 *
 * <p>Has no DB dependencies — callers (codegen substitution, deploy
 * service, chat tools) are responsible for resolving the storage_backend
 * and the slug fragments and passing them in. This keeps the service
 * unit-testable and pure.
 *
 * <p>Layout convention summary:
 * <pre>
 * Files:        {scheme}://{storage_root_files}/{domain}/{sor}/{pipeline}/{lifecycle}/
 * Lake (path):  {scheme}://{storage_root_lake}/{domain}/{sor}/{pipeline}/{layer}/{table}/
 * Lake (BQ):    {gcp_project}.{domain_snake}_{layer}.{table_snake}
 * </pre>
 *
 * <p>{@code scheme} is derived from the backend: {@code gs://} for GCP,
 * {@code s3a://} or {@code hdfs://} for DPC depending on
 * {@link StorageBackend#getDpcScheme()}.
 */
@Service
public class PathConventionService {

    /** Build a files-bucket path for the given lifecycle folder.
     *
     * <p>Used by FileIngestion / FileArrivalSensor / file-producing legs.
     * Always returns an object-storage URI (files are never warehouse-resident).
     */
    public String filesPath(StorageBackend sb, String domainSlug, String sorSlug,
                            String pipelineSlug, FileLifecycle lifecycle) {
        SlugNormalizer.validatePathSlug(domainSlug);
        SlugNormalizer.validatePathSlug(sorSlug);
        SlugNormalizer.validatePathSlug(pipelineSlug);
        String scheme = schemeFor(sb);
        return scheme + "://" + sb.getStorageRootFiles() + "/"
                + domainSlug + "/" + sorSlug + "/" + pipelineSlug + "/"
                + lifecycle.folder() + "/";
    }

    /** Resolve a lake table's location.
     *
     * <p>Returns a {@link TableLocation.CatalogIdentifier} when format is
     * {@code BQ_NATIVE} (warehouse-resident, no path); otherwise an
     * {@link TableLocation.ObjectStorePath} ending with {@code /}.
     *
     * <p>Validation lives in {@link StorageBackendValidator}; this method
     * trusts the inputs and constructs the location.
     */
    public TableLocation tableLocation(StorageBackend sb, LakeLayer layer, LakeFormat format,
                                        String domainSlug, String sorSlug,
                                        String pipelineSlug, String tableSlug) {
        SlugNormalizer.validatePathSlug(domainSlug);
        SlugNormalizer.validatePathSlug(sorSlug);
        SlugNormalizer.validatePathSlug(pipelineSlug);
        SlugNormalizer.validatePathSlug(tableSlug);

        if (format == LakeFormat.BQ_NATIVE) {
            // GCP gold case — catalog-identifier resolution.
            if (sb.getGcpProject() == null || sb.getGcpProject().isBlank()) {
                throw new IllegalStateException(
                        "BQ_NATIVE requires gcp_project on the storage_backend; got null/blank");
            }
            String dataset = SlugNormalizer.toBqIdentifier(domainSlug)
                    + "_" + layer.pathFragment();
            String table = SlugNormalizer.toBqIdentifier(tableSlug);
            return new TableLocation.CatalogIdentifier(sb.getGcpProject(), dataset, table);
        }

        String scheme = schemeFor(sb);
        String uri = scheme + "://" + sb.getStorageRootLake() + "/"
                + domainSlug + "/" + sorSlug + "/" + pipelineSlug + "/"
                + layer.pathFragment() + "/" + tableSlug + "/";
        return new TableLocation.ObjectStorePath(uri);
    }

    /**
     * Resolve the object-storage root for all tables in one lake layer.
     *
     * <p>This exists for engines such as dbt-spark whose {@code location_root}
     * config appends the model identifier itself. Table-specific paths should
     * still use {@link #tableLocation(StorageBackend, LakeLayer, LakeFormat, String, String, String, String)}.
     */
    public String lakeLayerRoot(StorageBackend sb, LakeLayer layer,
                                String domainSlug, String sorSlug, String pipelineSlug) {
        SlugNormalizer.validatePathSlug(domainSlug);
        SlugNormalizer.validatePathSlug(sorSlug);
        SlugNormalizer.validatePathSlug(pipelineSlug);
        String scheme = schemeFor(sb);
        return scheme + "://" + sb.getStorageRootLake() + "/"
                + domainSlug + "/" + sorSlug + "/" + pipelineSlug + "/"
                + layer.pathFragment();
    }

    /** Row-level DQ-rejected partition. Always object-storage; never
     * applies to BQ-native tables (those don't have a quarantine path —
     * they have a quarantine SQL-table side-by-side). */
    public String quarantinePath(StorageBackend sb, String domainSlug, String sorSlug,
                                  String pipelineSlug, String tableSlug, String ds) {
        SlugNormalizer.validatePathSlug(domainSlug);
        SlugNormalizer.validatePathSlug(sorSlug);
        SlugNormalizer.validatePathSlug(pipelineSlug);
        SlugNormalizer.validatePathSlug(tableSlug);
        if (ds == null || ds.isBlank()) {
            throw new IllegalArgumentException("ds must be non-blank");
        }
        String scheme = schemeFor(sb);
        return scheme + "://" + sb.getStorageRootLake() + "/"
                + domainSlug + "/" + sorSlug + "/" + pipelineSlug
                + "/_quarantine/" + tableSlug + "/" + ds + "/";
    }

    /** Spark Structured Streaming checkpoint location. Pipeline-scoped;
     * stream_name disambiguates multiple stream tasks within one pipeline. */
    public String checkpointPath(StorageBackend sb, String domainSlug, String sorSlug,
                                  String pipelineSlug, String streamName) {
        SlugNormalizer.validatePathSlug(domainSlug);
        SlugNormalizer.validatePathSlug(sorSlug);
        SlugNormalizer.validatePathSlug(pipelineSlug);
        SlugNormalizer.validatePathSlug(streamName);
        String scheme = schemeFor(sb);
        return scheme + "://" + sb.getStorageRootLake() + "/"
                + domainSlug + "/" + sorSlug + "/" + pipelineSlug
                + "/_checkpoints/" + streamName + "/";
    }

    /** Great Expectations Data Docs publish location.
     * checkpoint_name disambiguates multiple GX checkpoints in one pipeline. */
    public String gxDocsPath(StorageBackend sb, String domainSlug, String sorSlug,
                              String pipelineSlug, String checkpointName) {
        SlugNormalizer.validatePathSlug(domainSlug);
        SlugNormalizer.validatePathSlug(sorSlug);
        SlugNormalizer.validatePathSlug(pipelineSlug);
        SlugNormalizer.validatePathSlug(checkpointName);
        String scheme = schemeFor(sb);
        return scheme + "://" + sb.getStorageRootLake() + "/"
                + domainSlug + "/" + sorSlug + "/" + pipelineSlug
                + "/_gx_docs/" + checkpointName + "/";
    }

    /** dbt schema name for a (tenant, domain, layer) tuple, snake_case.
     * Used by dbt-runner config generation. NOT a path — it's an
     * identifier in the warehouse/lake catalog. */
    public String dbtSchemaName(String tenantSlug, String domainSlug, LakeLayer layer) {
        SlugNormalizer.validatePathSlug(tenantSlug);
        SlugNormalizer.validatePathSlug(domainSlug);
        return SlugNormalizer.toBqIdentifier(tenantSlug) + "_"
                + SlugNormalizer.toBqIdentifier(domainSlug) + "_"
                + layer.pathFragment();
    }

    /** Pick the URI scheme: gs:// for GCP, s3a://|hdfs:// for DPC per dpc_scheme. */
    private String schemeFor(StorageBackend sb) {
        StorageBackendType type = sb.getBackendType();
        if (type == StorageBackendType.GCP) return "gs";
        if (type == StorageBackendType.DPC) {
            String dpcScheme = sb.getDpcScheme();
            if ("s3a".equals(dpcScheme) || "hdfs".equals(dpcScheme)) return dpcScheme;
            throw new IllegalStateException(
                    "DPC storage_backend missing dpc_scheme (s3a|hdfs): " + sb.getId());
        }
        throw new IllegalStateException("Unknown backend: " + sb.getBackend());
    }
}
