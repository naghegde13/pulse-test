package com.pulse.codegen.opengine.handlers;

import com.pulse.codegen.opengine.EmissionEngine;
import com.pulse.codegen.opengine.EmitContext;
import com.pulse.codegen.opengine.OpEmitHandler;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * dbt-SQL emission for {@code sql-model} (SPEC #2 §C.1, power-user op).
 *
 * <p>Emits the DE-authored dbt SQL from either the V153 {@code steps} array or
 * the older flat {@code sql} value. This handler does NOT validate or rewrite
 * expressions — Calcite validation happens elsewhere; here we assemble a stable
 * SQL chain. With no SQL configured the op degrades to a
 * {@code SELECT * FROM <ref>} pass-through with a marker comment.
 */
public final class SqlModelDbtSqlHandler implements OpEmitHandler {

    @Override
    public String opName() {
        return OpVocabulary.SQL_MODEL;
    }

    @Override
    public EmissionEngine engine() {
        return EmissionEngine.DBT_SQL;
    }

    @Override
    public String emit(EmitContext ctx) {
        ResolvedConfig config = ctx.config();
        List<Map<String, Object>> steps = config.getMapList("steps");
        if (!steps.isEmpty()) {
            return emitSteps(steps, ctx.upstreamRef());
        }

        String sql = config.getString("sql");
        if (sql == null || sql.isBlank()) {
            return "-- sql-model: no SQL supplied; pass-through\nSELECT * FROM " + ctx.upstreamRef();
        }
        return stripTrailingSemicolon(sql);
    }

    private String emitSteps(List<Map<String, Object>> steps, String upstreamRef) {
        List<String> ctes = new ArrayList<>();
        String lastName = null;
        for (int i = 0; i < steps.size(); i++) {
            Map<String, Object> step = steps.get(i);
            String name = sanitizeName(step.get("name"), i + 1);
            String sql = step.get("sql") == null ? "" : step.get("sql").toString().trim();
            if (sql.isBlank()) {
                sql = "SELECT * FROM " + upstreamRef;
            }
            ctes.add(name + " AS (\n" + indent(stripTrailingSemicolon(sql)) + "\n)");
            lastName = name;
        }
        return "WITH " + String.join(",\n", ctes)
                + "\nSELECT *\nFROM " + lastName;
    }

    private String sanitizeName(Object raw, int fallbackIndex) {
        String candidate = raw == null ? "" : raw.toString().trim().toLowerCase();
        String sanitized = candidate.replaceAll("[^a-z0-9_]+", "_")
                .replaceAll("^_+|_+$", "");
        if (sanitized.isBlank()) {
            return "sql_step_" + fallbackIndex;
        }
        if (Character.isDigit(sanitized.charAt(0))) {
            return "sql_step_" + sanitized;
        }
        return sanitized;
    }

    private String stripTrailingSemicolon(String sql) {
        String out = sql == null ? "" : sql.stripTrailing();
        while (out.endsWith(";")) {
            out = out.substring(0, out.length() - 1).stripTrailing();
        }
        return out;
    }

    private String indent(String sql) {
        StringBuilder out = new StringBuilder();
        for (String line : sql.split("\n", -1)) {
            out.append("    ").append(line).append("\n");
        }
        if (out.length() > 0 && out.charAt(out.length() - 1) == '\n') {
            out.setLength(out.length() - 1);
        }
        return out.toString();
    }
}
