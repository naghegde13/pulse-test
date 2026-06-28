package com.pulse.storage;

import com.pulse.storage.model.FileLifecycle;
import com.pulse.storage.model.LakeFormat;
import com.pulse.storage.model.LakeLayer;
import com.pulse.storage.model.StorageBackend;
import com.pulse.storage.model.TableLocation;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the typed placeholder map that codegen substitutes into
 * generated PySpark / SQL / DAG code for one SubPipelineInstance in
 * one target environment.
 *
 * <p>Pure transformation — caller is responsible for resolving the
 * {@link StorageBackend} row (typically via
 * {@link com.pulse.storage.repository.StorageBackendRepository#findByTenantIdAndEnvironmentAndBackend})
 * and gathering the slugs for tenant/domain/sor/pipeline/table. This
 * keeps the resolver unit-testable and free of DB or Spring context.
 *
 * <p>Placeholder vocabulary (locked by #30 spec section 8):
 *
 * <table border="1">
 *   <tr><th>Placeholder</th><th>Description</th></tr>
 *   <tr><td>__STORAGE_BACKEND__</td><td>"DPC" or "GCP"</td></tr>
 *   <tr><td>__LAKE_FORMAT__</td><td>"delta" | "iceberg_external" | "iceberg_bq_managed" | "bq_native" | "parquet"</td></tr>
 *   <tr><td>__LAKE_LAYER__</td><td>"bronze" | "silver" | "gold"</td></tr>
 *   <tr><td>__TENANT_SLUG__ / __DOMAIN_SLUG__ / __SOR_SLUG__ / __PIPELINE_SLUG__ / __TABLE_SLUG__</td><td>kebab-case path slugs</td></tr>
 *   <tr><td>__FILES_SRC_PATH__ ... __FILES_OUTGOING_EXTRACTS_PATH__</td><td>file-lifecycle folder paths</td></tr>
 *   <tr><td>__TABLE_PATH__</td><td>fully-qualified object-store URI for the lake table (empty when format=BQ_NATIVE)</td></tr>
 *   <tr><td>__BQ_TABLE__</td><td>project.dataset.table for BQ-native (empty otherwise)</td></tr>
 *   <tr><td>__QUARANTINE_PATH__ / __CHECKPOINT_PATH__ / __GX_DOCS_PATH__</td><td>reserved-folder paths</td></tr>
 *   <tr><td>__GCP_PROJECT__</td><td>GCP project id (empty for DPC backend)</td></tr>
 *   <tr><td>__DBT_SCHEMA__</td><td>snake_case dbt schema name</td></tr>
 * </table>
 *
 * <p>Examples branch on {@code __LAKE_FORMAT__} to choose which of
 * {@code __TABLE_PATH__} (path-based formats) or {@code __BQ_TABLE__}
 * (BQ-native) to consume.
 */
@Service
public class StoragePlaceholderResolver {

    private final PathConventionService paths;

    public StoragePlaceholderResolver(PathConventionService paths) {
        this.paths = paths;
    }

    /** Inputs the resolver needs. The caller assembles this from the
     * SubPipelineInstance, its Pipeline, and the target environment.
     *
     * <p>{@code tableSlug} can be null for non-table-producing blueprints
     * (DQValidator, FreshnessChecks, FileArrivalSensor, etc.); table /
     * BQ-table / quarantine placeholders are then absent from the result.
     *
     * <p>{@code layer}/{@code format} can both be null for non-table
     * blueprints; if one is null the resolver throws to surface the
     * inconsistency.
     */
    public record Context(
            StorageBackend backend,
            LakeLayer layer,
            LakeFormat format,
            String tenantSlug,
            String domainSlug,
            String sorSlug,
            String pipelineSlug,
            String tableSlug,
            // Optional disambiguators for reserved-folder paths.
            String streamName,        // for __CHECKPOINT_PATH__
            String checkpointName,    // for __GX_DOCS_PATH__
            String quarantineDs       // for __QUARANTINE_PATH__
    ) {
        public Context {
            if (backend == null) {
                throw new IllegalArgumentException("backend is required");
            }
            if (tenantSlug == null || domainSlug == null
                    || sorSlug == null || pipelineSlug == null) {
                throw new IllegalArgumentException(
                        "tenantSlug, domainSlug, sorSlug, pipelineSlug all required");
            }
            if ((layer == null) != (format == null)) {
                throw new IllegalArgumentException(
                        "layer and format must both be set or both null; got "
                                + "layer=" + layer + " format=" + format);
            }
        }
    }

    public Map<String, String> resolve(Context ctx) {
        Map<String, String> out = new LinkedHashMap<>();

        // Always-present metadata.
        out.put("__STORAGE_BACKEND__", ctx.backend().getBackend());
        out.put("__TENANT_SLUG__", ctx.tenantSlug());
        out.put("__DOMAIN_SLUG__", ctx.domainSlug());
        out.put("__SOR_SLUG__", ctx.sorSlug());
        out.put("__PIPELINE_SLUG__", ctx.pipelineSlug());

        // GCP project id is empty for DPC backend; example branches on
        // __STORAGE_BACKEND__ before consuming.
        out.put("__GCP_PROJECT__",
                ctx.backend().getGcpProject() != null ? ctx.backend().getGcpProject() : "");

        // dbt schema — derived from (tenant, domain, layer); only if layer is set.
        if (ctx.layer() != null) {
            out.put("__LAKE_LAYER__", ctx.layer().pathFragment());
            out.put("__LAKE_FORMAT__", ctx.format().dbValue());
            out.put("__DBT_SCHEMA__",
                    paths.dbtSchemaName(ctx.tenantSlug(), ctx.domainSlug(), ctx.layer()));
        } else {
            // Non-table blueprint — both empty so substitution doesn't leave
            // raw placeholder text in generated output.
            out.put("__LAKE_LAYER__", "");
            out.put("__LAKE_FORMAT__", "");
            out.put("__DBT_SCHEMA__", "");
        }

        // File-lifecycle folder paths (always populated; every blueprint
        // can produce or consume from these).
        out.put("__FILES_SRC_PATH__",
                paths.filesPath(ctx.backend(), ctx.domainSlug(), ctx.sorSlug(),
                        ctx.pipelineSlug(), FileLifecycle.SRC));
        out.put("__FILES_PROCESSING_PATH__",
                paths.filesPath(ctx.backend(), ctx.domainSlug(), ctx.sorSlug(),
                        ctx.pipelineSlug(), FileLifecycle.PROCESSING));
        out.put("__FILES_ARCHIVE_PATH__",
                paths.filesPath(ctx.backend(), ctx.domainSlug(), ctx.sorSlug(),
                        ctx.pipelineSlug(), FileLifecycle.ARCHIVE));
        out.put("__FILES_BAD_FILES_PATH__",
                paths.filesPath(ctx.backend(), ctx.domainSlug(), ctx.sorSlug(),
                        ctx.pipelineSlug(), FileLifecycle.BAD_FILES));
        out.put("__FILES_OUTGOING_EXTRACTS_PATH__",
                paths.filesPath(ctx.backend(), ctx.domainSlug(), ctx.sorSlug(),
                        ctx.pipelineSlug(), FileLifecycle.OUTGOING_EXTRACTS));

        // Table placeholders — only when this blueprint produces a table.
        if (ctx.layer() != null && ctx.tableSlug() != null) {
            out.put("__TABLE_SLUG__", ctx.tableSlug());

            TableLocation loc = paths.tableLocation(ctx.backend(), ctx.layer(),
                    ctx.format(), ctx.domainSlug(), ctx.sorSlug(),
                    ctx.pipelineSlug(), ctx.tableSlug());
            if (loc instanceof TableLocation.ObjectStorePath osp) {
                out.put("__TABLE_PATH__", osp.uri());
                out.put("__BQ_TABLE__", "");
            } else if (loc instanceof TableLocation.CatalogIdentifier cat) {
                out.put("__TABLE_PATH__", "");
                out.put("__BQ_TABLE__", cat.fullyQualified());
            }

            // Reserved folder paths — quarantine/checkpoint/gx_docs
            // require disambiguators (table/stream/checkpoint name + ds).
            if (ctx.quarantineDs() != null) {
                out.put("__QUARANTINE_PATH__",
                        paths.quarantinePath(ctx.backend(), ctx.domainSlug(),
                                ctx.sorSlug(), ctx.pipelineSlug(),
                                ctx.tableSlug(), ctx.quarantineDs()));
            }
        } else {
            out.put("__TABLE_SLUG__", "");
            out.put("__TABLE_PATH__", "");
            out.put("__BQ_TABLE__", "");
        }

        if (ctx.streamName() != null) {
            out.put("__CHECKPOINT_PATH__",
                    paths.checkpointPath(ctx.backend(), ctx.domainSlug(),
                            ctx.sorSlug(), ctx.pipelineSlug(), ctx.streamName()));
        }
        if (ctx.checkpointName() != null) {
            out.put("__GX_DOCS_PATH__",
                    paths.gxDocsPath(ctx.backend(), ctx.domainSlug(),
                            ctx.sorSlug(), ctx.pipelineSlug(), ctx.checkpointName()));
        }

        return out;
    }

    /**
     * Apply a placeholder map to a template string. Replaces every
     * {@code __KEY__} occurrence with its value; placeholders absent from
     * the map are left as-is (so a partial substitution can still flag
     * missing keys downstream).
     */
    public static String substitute(String template, Map<String, String> placeholders) {
        if (template == null) return null;
        String out = template;
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            out = out.replace(e.getKey(), e.getValue());
        }
        return out;
    }
}
