package com.pulse.codegen.opengine.handlers;

import com.pulse.codegen.opengine.EmissionEngine;
import com.pulse.codegen.opengine.EmitContext;
import com.pulse.codegen.opengine.OpEmitHandler;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;

import java.util.List;

/**
 * dbt-SQL emission for {@code build-struct} (SPEC #2 §C.1).
 *
 * <p>Emits {@code SELECT *, named_struct('f1', c1, ...) AS <struct_name> FROM <ref>},
 * packing the {@code source_columns} into a new struct column. Spark SQL's
 * {@code named_struct} is used (field-name + value pairs) so the struct's field
 * names are preserved; columns are emitted in config order (deterministic, ADR
 * 0009). With no {@code struct_name} or {@code source_columns} it degrades to
 * {@code SELECT * FROM <ref>}.
 */
public final class BuildStructDbtSqlHandler implements OpEmitHandler {

    @Override
    public String opName() {
        return OpVocabulary.BUILD_STRUCT;
    }

    @Override
    public EmissionEngine engine() {
        return EmissionEngine.DBT_SQL;
    }

    @Override
    public String emit(EmitContext ctx) {
        ResolvedConfig config = ctx.config();
        String ref = ctx.upstreamRef();
        String structName = config.getString("struct_name");
        List<String> sources = config.getStringList("source_columns");
        if (structName == null || structName.isBlank() || sources.isEmpty()) {
            return "SELECT *\nFROM " + ref;
        }
        StringBuilder struct = new StringBuilder("named_struct(");
        for (int i = 0; i < sources.size(); i++) {
            String col = sources.get(i);
            if (i > 0) {
                struct.append(", ");
            }
            struct.append("'").append(col).append("', ").append(col);
        }
        struct.append(")");
        return "SELECT *, " + struct + " AS " + structName + "\nFROM " + ref;
    }
}
