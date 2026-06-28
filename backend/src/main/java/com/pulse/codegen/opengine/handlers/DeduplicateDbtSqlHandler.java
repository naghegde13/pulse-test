package com.pulse.codegen.opengine.handlers;

import com.pulse.codegen.opengine.EmissionEngine;
import com.pulse.codegen.opengine.EmitContext;
import com.pulse.codegen.opengine.OpEmitHandler;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;

import java.util.List;

/**
 * dbt-SQL emission for {@code deduplicate} (SPEC #2 §C.1).
 *
 * <p>Emits a keep-one-per-key dedup via the {@code QUALIFY} clause:
 * {@code SELECT * FROM <ref> QUALIFY row_number() OVER (PARTITION BY <dedup_key>
 * ORDER BY <tiebreaker>) = 1}.
 *
 * <p><b>Determinism (ADR 0009 byte-exactness):</b> a dedup MUST have a stable
 * {@code ORDER BY} — without one the surviving row is arbitrary and the artifact is
 * non-reproducible. The tiebreaker is {@code order_by} when supplied; otherwise it
 * falls back to the {@code dedup_key} columns themselves, which is always present
 * and stable. With no {@code dedup_key} the op degrades to
 * {@code SELECT DISTINCT * FROM <ref>}.
 */
public final class DeduplicateDbtSqlHandler implements OpEmitHandler {

    @Override
    public String opName() {
        return OpVocabulary.DEDUPLICATE;
    }

    @Override
    public EmissionEngine engine() {
        return EmissionEngine.DBT_SQL;
    }

    @Override
    public String emit(EmitContext ctx) {
        ResolvedConfig config = ctx.config();
        String ref = ctx.upstreamRef();
        List<String> dedupKey = config.getStringList("dedup_key");
        if (dedupKey.isEmpty()) {
            return "SELECT DISTINCT *\nFROM " + ref;
        }
        String partitionBy = String.join(", ", dedupKey);
        String orderBy = config.getString("order_by");
        if (orderBy == null || orderBy.isBlank()) {
            // Deterministic fallback: ORDER BY the partition columns themselves so
            // the artifact is byte-stable even without an explicit tiebreaker.
            orderBy = partitionBy;
        }
        return "SELECT *\nFROM " + ref
                + "\nQUALIFY row_number() OVER (PARTITION BY " + partitionBy
                + " ORDER BY " + orderBy + ") = 1";
    }
}
