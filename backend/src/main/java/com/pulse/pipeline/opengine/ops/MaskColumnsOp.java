package com.pulse.pipeline.opengine.ops;

import com.pulse.pipeline.opengine.ColumnModel;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;
import com.pulse.pipeline.opengine.Schema;
import com.pulse.pipeline.opengine.SchemaOp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code mask-columns} (SPEC #1 §B.1 rule 7) — non-masked columns pass through;
 * masked columns gain a {@code lineage="masked:<strategy>"} extra, the
 * {@code ["masked","pii"]} tags (merged with any existing tags), and a
 * {@code transform={kind:"mask", strategy:<strategy>}} extra.
 *
 * <p>Type rule: strategy {@code "hash"} forces the column type to {@code string}
 * (a SHA-256 hex digest, fixed-length, regardless of source type); every other
 * strategy preserves the source type (format-preserving by default).
 *
 * <p>Config accepts two shapes (both may be present):
 * <pre>
 *   pii_columns: [names]                       (default strategy "hash")
 *   mask_specs:  [{column, strategy}]          (per-column; strategy defaults "hash")
 * </pre>
 *
 * <p>If neither config is present the op is the unconfigured do-nothing
 * passthrough default (SPEC #1 §A.2). This mirrors the legacy
 * {@code SchemaPropagationService.maskSchema} / {@code maskedTypeFor} shape.
 */
public final class MaskColumnsOp implements SchemaOp {

    @Override
    public String opName() {
        return OpVocabulary.MASK_COLUMNS;
    }

    @Override
    public Schema apply(Schema in, Schema in2, ResolvedConfig cfg) {
        if (in == null) return Schema.empty();

        // Build column -> strategy from both config shapes. pii_columns first
        // (default "hash"), then mask_specs (per-column strategy) overrides.
        Map<String, String> maskByColumn = new LinkedHashMap<>();
        for (String name : cfg.getStringList("pii_columns")) {
            maskByColumn.put(name, "hash");
        }
        for (Map<String, Object> spec : cfg.getMapList("mask_specs")) {
            Object col = spec.get("column");
            if (col == null) continue;
            Object strategy = spec.get("strategy");
            maskByColumn.put(col.toString(), strategy != null ? strategy.toString() : "hash");
        }

        if (maskByColumn.isEmpty()) {
            // Unconfigured — pass columns through unchanged.
            return new Schema(in.columns());
        }

        List<ColumnModel> out = new ArrayList<>();
        for (ColumnModel c : in.columns()) {
            String strategy = c.name() == null ? null : maskByColumn.get(c.name());
            if (strategy == null) {
                out.add(c); // non-masked — unchanged.
                continue;
            }
            ColumnModel masked = c
                    .withType(maskedTypeFor(strategy, c.type()))
                    .withExtra("lineage", "masked:" + strategy)
                    .withExtra("tags", appendTags(c.extras().get("tags"), List.of("masked", "pii")));
            Map<String, Object> transform = new LinkedHashMap<>();
            transform.put("kind", "mask");
            transform.put("strategy", strategy);
            masked = masked.withExtra("transform", transform);
            out.add(masked);
        }
        return new Schema(out);
    }

    /** SHA-256 hash -> fixed-length string; every other strategy preserves source type. */
    private static String maskedTypeFor(String strategy, String originalType) {
        String orig = originalType == null ? "string" : originalType;
        return "hash".equals(strategy) ? "string" : orig;
    }

    /** Merge any existing tags with the additions, de-duplicated, order-preserving. */
    private static List<String> appendTags(Object existing, List<String> additions) {
        Set<String> merged = new LinkedHashSet<>();
        if (existing instanceof List<?> list) {
            for (Object t : list) if (t != null) merged.add(t.toString());
        }
        merged.addAll(additions);
        return new ArrayList<>(merged);
    }
}
