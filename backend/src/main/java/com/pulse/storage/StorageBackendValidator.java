package com.pulse.storage;

import com.pulse.storage.model.LakeFormat;
import com.pulse.storage.model.LakeLayer;
import com.pulse.storage.model.StorageBackendType;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

/**
 * Application-layer validation for the (backend × layer × format)
 * compatibility matrix from #30 spec section 5. The DB enforces only
 * the gold-on-GCP rule via CHECK constraint (because that's the
 * single load-bearing invariant); this class enforces the full matrix
 * with helpful error messages.
 *
 * <p>Matrix:
 * <pre>
 *               DPC                                GCP
 *   bronze   parquet, iceberg_external       iceberg_bq_managed, iceberg_external
 *   silver   parquet, iceberg_external       iceberg_bq_managed, iceberg_external
 *   gold     parquet, iceberg_external       bq_native (only)
 * </pre>
 *
 * <p>Delta is excluded from DPC and GCP per RuntimeAuthorityService: DPC
 * resolves to Hive-managed Parquet on S3, GCP resolves to BigQuery-managed
 * Iceberg. Delta remains available only for unconstrained/local backends.
 *
 * <p>Called from {@code SubPipelineInstanceService.save()} before persisting
 * a new/updated instance.
 */
@Component
public class StorageBackendValidator {

    private static final Set<LakeFormat> DPC_FORMATS = EnumSet.of(
            LakeFormat.PARQUET, LakeFormat.ICEBERG_EXTERNAL);

    private static final Set<LakeFormat> GCP_NON_GOLD_FORMATS = EnumSet.of(
            LakeFormat.ICEBERG_BQ_MANAGED, LakeFormat.ICEBERG_EXTERNAL);

    private static final Set<LakeFormat> GCP_GOLD_FORMATS = EnumSet.of(LakeFormat.BQ_NATIVE);

    /**
     * Validate the {backend, layer, format} triplet. Throws
     * {@link IllegalArgumentException} with a clear message when illegal.
     * Returns silently when legal.
     *
     * <p>Both layer and format may be null when the blueprint isn't
     * table-producing (e.g., FileArrivalSensor, DQValidator). The caller
     * decides whether they're required for the blueprint at hand.
     */
    public void validate(StorageBackendType backend, LakeLayer layer, LakeFormat format) {
        if (backend == null) {
            throw new IllegalArgumentException("storage_backend is required");
        }
        if (layer == null && format == null) {
            return; // non-table-producing blueprint — backend alone is enough
        }
        if (layer == null || format == null) {
            throw new IllegalArgumentException(
                    "lake_layer and lake_format must be set together; got "
                            + "layer=" + layer + " format=" + format);
        }

        Set<LakeFormat> legal = legalFormatsFor(backend, layer);
        if (!legal.contains(format)) {
            throw new IllegalArgumentException(messageFor(backend, layer, format, legal));
        }
    }

    /** Public for use by UI auto-defaulting and chat-tool surfaces. */
    public Set<LakeFormat> legalFormatsFor(StorageBackendType backend, LakeLayer layer) {
        if (backend == StorageBackendType.DPC) return DPC_FORMATS;
        if (backend == StorageBackendType.GCP) {
            return layer == LakeLayer.GOLD ? GCP_GOLD_FORMATS : GCP_NON_GOLD_FORMATS;
        }
        throw new IllegalStateException("Unknown backend: " + backend);
    }

    private String messageFor(StorageBackendType backend, LakeLayer layer,
                              LakeFormat format, Set<LakeFormat> legal) {
        // The gold-on-GCP rule has its own dedicated message because
        // it's the most common violation.
        if (backend == StorageBackendType.GCP && layer == LakeLayer.GOLD) {
            return "Gold tables on GCP must use BigQuery native storage "
                    + "(lake_format=bq_native). Got format=" + format
                    + ". Use storage_backend=DPC if you need a non-BQ gold table.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Illegal lake_format=").append(format.dbValue())
                .append(" for backend=").append(backend)
                .append(", layer=").append(layer.pathFragment())
                .append(". Legal formats: ");
        boolean first = true;
        for (LakeFormat f : legal) {
            if (!first) sb.append(", ");
            sb.append(f.dbValue());
            first = false;
        }
        return sb.toString();
    }
}
