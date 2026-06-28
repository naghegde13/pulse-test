package com.pulse.codegen.opengine.handlers;

import com.pulse.codegen.opengine.EmissionEngine;
import com.pulse.codegen.opengine.EmitContext;
import com.pulse.codegen.opengine.OpEmitHandler;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;

/**
 * dbt-SQL emission for {@code sample-limit} (SPEC #2 §C.1).
 *
 * <p>Two forms, chosen by config:
 * <ul>
 *   <li>{@code sample_fraction} present &rarr; {@code SELECT * FROM <ref>
 *       TABLESAMPLE (<pct> PERCENT)} (the fraction interpreted as a 0-1 ratio,
 *       converted to a percent).</li>
 *   <li>otherwise &rarr; {@code SELECT * FROM <ref> LIMIT <n>} from {@code limit}
 *       (default {@code 1000}).</li>
 * </ul>
 */
public final class SampleLimitDbtSqlHandler implements OpEmitHandler {

    private static final int DEFAULT_LIMIT = 1000;

    @Override
    public String opName() {
        return OpVocabulary.SAMPLE_LIMIT;
    }

    @Override
    public EmissionEngine engine() {
        return EmissionEngine.DBT_SQL;
    }

    @Override
    public String emit(EmitContext ctx) {
        ResolvedConfig config = ctx.config();
        String ref = ctx.upstreamRef();

        if (config.has("sample_fraction")) {
            String pct = formatPercent(config.getString("sample_fraction"));
            return "SELECT * FROM " + ref + " TABLESAMPLE (" + pct + " PERCENT)";
        }

        String limit = config.getString("limit");
        long n = parseLimit(limit);
        return "SELECT * FROM " + ref + "\nLIMIT " + n;
    }

    private long parseLimit(String limit) {
        if (limit == null || limit.isBlank()) {
            return DEFAULT_LIMIT;
        }
        try {
            return Long.parseLong(limit.trim());
        } catch (NumberFormatException ex) {
            return DEFAULT_LIMIT;
        }
    }

    private String formatPercent(String fraction) {
        double f;
        try {
            f = Double.parseDouble(fraction.trim());
        } catch (NumberFormatException | NullPointerException ex) {
            return "10";
        }
        // Interpret 0-1 as a ratio; a value >1 is already a percent.
        double pct = f <= 1.0 ? f * 100.0 : f;
        if (pct == Math.rint(pct)) {
            return Long.toString((long) pct);
        }
        return Double.toString(pct);
    }
}
