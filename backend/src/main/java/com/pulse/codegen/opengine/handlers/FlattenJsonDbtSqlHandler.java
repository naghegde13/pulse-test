package com.pulse.codegen.opengine.handlers;

import com.pulse.codegen.opengine.EmissionEngine;
import com.pulse.codegen.opengine.EmitContext;
import com.pulse.codegen.opengine.OpEmitHandler;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * dbt-SQL emission for {@code flatten-json} (SPEC #2 §C.1).
 *
 * <p>Expands struct columns into flat {@code <struct>.<field> AS <field>}
 * dot-access projections.
 *
 * <p><b>Simplification (documented):</b> column names alone do not carry nested
 * field detail, so the set of sub-fields to expand must be supplied explicitly via
 * config:
 * <ul>
 *   <li>{@code columns}: [struct-column names] to flatten (these are dropped from
 *       the passthrough output, replaced by their expanded fields).</li>
 *   <li>{@code flatten_fields}: [{column, field}] enumerating which
 *       {@code <column>.<field>} accessors to emit (as {@code <field>}).</li>
 * </ul>
 * Non-flattened INPUT columns pass through in schema order. With no flatten
 * config the op degrades to {@code SELECT * FROM <ref>} (SPEC #1 §A.2 passthrough).
 * A richer struct-aware emission (full recursive descent from the schema's struct
 * fields) is intentionally out of scope for this fragment.
 */
public final class FlattenJsonDbtSqlHandler implements OpEmitHandler {

    @Override
    public String opName() {
        return OpVocabulary.FLATTEN_JSON;
    }

    @Override
    public EmissionEngine engine() {
        return EmissionEngine.DBT_SQL;
    }

    @Override
    public String emit(EmitContext ctx) {
        ResolvedConfig config = ctx.config();
        String ref = ctx.upstreamRef();
        List<String> cols = ctx.inputColumnNames();

        Set<String> flattenCols = new LinkedHashSet<>(config.getStringList("columns"));
        // Ordered map of struct-column -> ordered list of fields to expand.
        Map<String, List<String>> fieldsByColumn = new LinkedHashMap<>();
        for (Map<String, Object> entry : config.getMapList("flatten_fields")) {
            Object column = entry.get("column");
            Object field = entry.get("field");
            if (column == null || field == null) {
                continue;
            }
            fieldsByColumn
                    .computeIfAbsent(column.toString(), k -> new java.util.ArrayList<>())
                    .add(field.toString());
        }

        if (fieldsByColumn.isEmpty()) {
            return "SELECT *\nFROM " + ref;
        }

        // Build the projection list: passthrough columns (in schema order, minus the
        // flattened struct columns) followed by the expanded dot-access fields
        // (in config insertion order).
        java.util.List<String> projections = new java.util.ArrayList<>();
        for (String col : cols) {
            if (flattenCols.contains(col) || fieldsByColumn.containsKey(col)) {
                continue;
            }
            projections.add(col);
        }
        for (Map.Entry<String, List<String>> e : fieldsByColumn.entrySet()) {
            String column = e.getKey();
            for (String field : e.getValue()) {
                projections.add(column + "." + field + " AS " + field);
            }
        }

        if (projections.isEmpty()) {
            return "SELECT *\nFROM " + ref;
        }

        StringBuilder sql = new StringBuilder("SELECT\n");
        for (int i = 0; i < projections.size(); i++) {
            sql.append("    ").append(projections.get(i));
            if (i < projections.size() - 1) {
                sql.append(",");
            }
            sql.append("\n");
        }
        sql.append("FROM ").append(ref);
        return sql.toString();
    }
}
