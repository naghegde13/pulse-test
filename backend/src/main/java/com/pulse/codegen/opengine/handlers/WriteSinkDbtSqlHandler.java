package com.pulse.codegen.opengine.handlers;

import com.pulse.codegen.opengine.EmissionEngine;
import com.pulse.codegen.opengine.EmitContext;
import com.pulse.codegen.opengine.OpEmitHandler;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;

/**
 * dbt-SQL emission for {@code write-sink} when a modeling Blueprint publishes a
 * dbt relation. The dbt model's {@code config(...)} block owns the physical
 * materialization; this op is the deterministic boundary that preserves the
 * transformed rows for that published relation.
 */
public final class WriteSinkDbtSqlHandler implements OpEmitHandler {

    @Override
    public String opName() {
        return OpVocabulary.WRITE_SINK;
    }

    @Override
    public EmissionEngine engine() {
        return EmissionEngine.DBT_SQL;
    }

    @Override
    public String emit(EmitContext ctx) {
        ResolvedConfig config = ctx.config();
        String target = config.getString("target");
        String mode = config.getString("mode");
        StringBuilder sql = new StringBuilder();
        sql.append("-- write-sink");
        if (target != null && !target.isBlank()) {
            sql.append(": target=").append(target);
        }
        if (mode != null && !mode.isBlank()) {
            sql.append(", mode=").append(mode);
        }
        sql.append("\nSELECT *\nFROM ").append(ctx.upstreamRef());
        return sql.toString();
    }
}
