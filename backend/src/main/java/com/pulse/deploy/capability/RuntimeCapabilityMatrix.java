package com.pulse.deploy.capability;

import com.pulse.deploy.adapter.CapabilityCheckResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Phase 7 — runtime / table-format compatibility matrix.
 *
 * <p>Phase 7 implements only the Iceberg + table-format dimension
 * called out in {@code docs/architecture/deployment-productization-plan.md}
 * lines 807–831. Other dimensions (network, service-account,
 * dependency policy) are TODO and currently return
 * {@code approved=true} with a "not yet evaluated" reason — the
 * preflight blocker honors the {@code approved} flag, so adding
 * those dimensions later is non-breaking.
 *
 * <p>Matrix rules (matrix v1):
 * <ul>
 *   <li>{@code LOCAL_MATERIALIZATION} approves every format. Local
 *       materialization is product-owned proof; runtime semantics
 *       don't apply.</li>
 *   <li>{@code GCP_COMPOSER_DATAPROC} approves
 *       {@code ICEBERG / DELTA / PARQUET / UNSPECIFIED}. The plan
 *       explicitly endorses Iceberg on GCP via Composer + Dataproc
 *       Spark when catalog access exists; this matrix v1 trusts the
 *       target's recorded capability profile.</li>
 *   <li>{@code DPC_AIRFLOW_OPENSHIFT_SPARK} approves
 *       {@code PARQUET / UNSPECIFIED} unconditionally. {@code ICEBERG}
 *       is gated on a documented capability hint
 *       ({@link Request#dpcIcebergSupported()}). Without that hint, the
 *       matrix returns a {@code DPC_PARQUET_LIMITED} fallback per the
 *       plan's "explicit temporary capability mode" guidance.
 *       {@code DELTA} on DPC is rejected outright; the plan calls out
 *       {@code DPC_DELTA_LIMITED} as a possible explicit fallback only,
 *       not a default.</li>
 * </ul>
 */
@Service
public class RuntimeCapabilityMatrix {

    public static final String LOCAL = "LOCAL_MATERIALIZATION";
    public static final String GCP = "GCP_COMPOSER_DATAPROC";
    public static final String DPC = "DPC_AIRFLOW_OPENSHIFT_SPARK";

    public static final String FORMAT_ICEBERG = "ICEBERG";
    public static final String FORMAT_DELTA = "DELTA";
    public static final String FORMAT_PARQUET = "PARQUET";
    public static final String FORMAT_UNSPECIFIED = "UNSPECIFIED";

    public static final String FALLBACK_DPC_PARQUET_LIMITED = "DPC_PARQUET_LIMITED";

    public CapabilityCheckResult evaluate(Request req) {
        if (req == null) {
            return CapabilityCheckResult.rejected("UNKNOWN", FORMAT_UNSPECIFIED,
                    List.of("matrix request was null"));
        }
        String targetType = normalize(req.targetType());
        String requestedFormat = normalizeFormat(req.requestedFormat());
        List<String> reasons = new ArrayList<>();

        if (LOCAL.equals(targetType)) {
            reasons.add("local materialization is runtime-agnostic; every table format approved.");
            reasons.add("note: other capability dimensions (network, SA, dependency policy) are not yet evaluated.");
            return CapabilityCheckResult.approved(targetType, requestedFormat, reasons);
        }

        if (GCP.equals(targetType)) {
            // Phase 7 v1 trusts that GCP has Iceberg support wired via
            // Composer + Dataproc Spark + a configured catalog. Real
            // catalog/network checks land in matrix v2.
            reasons.add("GCP Composer + Dataproc Spark accepts " + requestedFormat + " under matrix v1.");
            reasons.add("note: catalog/network/SA dimensions are not yet evaluated.");
            return CapabilityCheckResult.approved(targetType, requestedFormat, reasons);
        }

        if (DPC.equals(targetType)) {
            return evaluateDpc(targetType, requestedFormat, req, reasons);
        }

        reasons.add("unknown targetType '" + targetType + "'");
        return CapabilityCheckResult.rejected(targetType, requestedFormat, reasons);
    }

    private CapabilityCheckResult evaluateDpc(String targetType, String requestedFormat,
                                              Request req, List<String> reasons) {
        switch (requestedFormat) {
            case FORMAT_PARQUET, FORMAT_UNSPECIFIED -> {
                reasons.add("DPC unconditionally accepts " + requestedFormat + ".");
                reasons.add("note: catalog/network/SA dimensions are not yet evaluated.");
                return CapabilityCheckResult.approved(targetType, requestedFormat, reasons);
            }
            case FORMAT_ICEBERG -> {
                if (req.dpcIcebergSupported()) {
                    reasons.add("DPC Iceberg accepted: dpcIcebergSupported=true (platform capability hint).");
                    return CapabilityCheckResult.approved(targetType, requestedFormat, reasons);
                }
                reasons.add("DPC Iceberg not yet capability-confirmed by platform; "
                        + "applying DPC_PARQUET_LIMITED fallback per plan §Iceberg Portability.");
                reasons.add("operator must approve fallback explicitly before deploy.");
                return CapabilityCheckResult.fallback(targetType, FORMAT_ICEBERG,
                        FORMAT_PARQUET, FALLBACK_DPC_PARQUET_LIMITED, reasons);
            }
            case FORMAT_DELTA -> {
                reasons.add("DPC Delta is rejected by matrix v1; the plan calls out "
                        + "DPC_DELTA_LIMITED as an explicit fallback only, not a default.");
                return CapabilityCheckResult.rejected(targetType, requestedFormat, reasons);
            }
            default -> {
                reasons.add("unknown table format '" + requestedFormat + "' for DPC.");
                return CapabilityCheckResult.rejected(targetType, requestedFormat, reasons);
            }
        }
    }

    private static String normalize(String s) {
        return s == null ? "UNKNOWN" : s.toUpperCase(Locale.ROOT);
    }

    private static String normalizeFormat(String s) {
        if (s == null || s.isBlank()) return FORMAT_UNSPECIFIED;
        String upper = s.toUpperCase(Locale.ROOT);
        return switch (upper) {
            case FORMAT_ICEBERG, FORMAT_DELTA, FORMAT_PARQUET, FORMAT_UNSPECIFIED -> upper;
            default -> upper;
        };
    }

    /**
     * @param targetType            canonical adapter key
     * @param requestedFormat       one of {@code ICEBERG}, {@code DELTA},
     *                              {@code PARQUET}, {@code UNSPECIFIED}
     * @param dpcIcebergSupported   platform capability hint — set
     *                              {@code true} only when DPC platform
     *                              has confirmed Iceberg readiness for
     *                              this tenant
     */
    public record Request(
            String targetType,
            String requestedFormat,
            boolean dpcIcebergSupported
    ) {}
}
