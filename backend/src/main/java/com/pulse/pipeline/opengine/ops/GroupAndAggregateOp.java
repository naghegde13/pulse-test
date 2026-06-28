package com.pulse.pipeline.opengine.ops;

import com.pulse.pipeline.opengine.ColumnModel;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;
import com.pulse.pipeline.opengine.Schema;
import com.pulse.pipeline.opengine.SchemaOp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code group-and-aggregate} (SPEC #1 §B.1 rule 11) — collapse rows to one per
 * distinct group-by tuple, emitting aggregate columns.
 *
 * <p>OUT = the group-by columns (types taken from IN, "string" if a group-by
 * name is absent from IN) followed by one column per aggregation entry. Output
 * order: all group-by columns in config order, then all aggregation columns in
 * config order.
 *
 * <p>Config:
 * <pre>
 *   group_by_columns: [name, ...]
 *   aggregations: [{alias, function, column}, ...]
 * </pre>
 *
 * <p><b>FIX #6</b> aggregate output types (the legacy types were wrong):
 * <ul>
 *   <li>COUNT, COUNT_DISTINCT &rarr; {@code long} (legacy wrongly used integer)</li>
 *   <li>SUM on an integer-source column ({@code integer|int|long}) &rarr; {@code long}</li>
 *   <li>SUM on a decimal-source column ({@code decimal|double|float|numeric}) &rarr; {@code double};
 *       SUM with an unknown/absent source type defaults to {@code double}</li>
 *   <li>AVG &rarr; {@code double} (legacy wrongly used decimal)</li>
 *   <li>MIN, MAX &rarr; the source column's type</li>
 * </ul>
 * Function names are matched case-insensitively.
 */
public final class GroupAndAggregateOp implements SchemaOp {

    @Override
    public String opName() {
        return OpVocabulary.GROUP_AND_AGGREGATE;
    }

    @Override
    public Schema apply(Schema in, Schema in2, ResolvedConfig cfg) {
        List<ColumnModel> out = new ArrayList<>();

        // 1) Group-by columns, types pulled from IN (string fallback if absent).
        for (String gb : cfg.getStringList("group_by_columns")) {
            ColumnModel src = in == null ? null : in.find(gb);
            if (src != null) {
                out.add(src.withName(gb));
            } else {
                out.add(ColumnModel.simple(gb, "string"));
            }
        }

        // 2) Aggregation columns, one per entry, output type per FIX #6.
        for (Map<String, Object> agg : cfg.getMapList("aggregations")) {
            String alias = str(agg.get("alias"));
            String function = str(agg.get("function"));
            String column = str(agg.get("column"));
            String outType = aggregateType(function, column, in);
            out.add(ColumnModel.simple(alias, outType));
        }

        return new Schema(out);
    }

    private static String aggregateType(String function, String sourceColumn, Schema in) {
        String fn = function == null ? "" : function.trim().toLowerCase(Locale.ROOT);
        switch (fn) {
            case "count":
            case "count_distinct":
                return "long";
            case "avg":
                return "double";
            case "min":
            case "max": {
                ColumnModel src = in == null ? null : in.find(sourceColumn);
                return src != null ? src.type() : "double";
            }
            case "sum": {
                String srcType = sourceType(sourceColumn, in);
                if (isIntegerSource(srcType)) return "long";
                if (isDecimalSource(srcType)) return "double";
                return "double"; // unknown / absent source type defaults to double
            }
            default:
                // Unrecognised function — default to double (numeric aggregate).
                return "double";
        }
    }

    private static String sourceType(String column, Schema in) {
        ColumnModel src = in == null ? null : in.find(column);
        return src == null ? null : src.type();
    }

    private static boolean isIntegerSource(String type) {
        if (type == null) return false;
        String t = type.toLowerCase(Locale.ROOT);
        return t.equals("integer") || t.equals("int") || t.equals("long");
    }

    private static boolean isDecimalSource(String type) {
        if (type == null) return false;
        String t = type.toLowerCase(Locale.ROOT);
        return t.equals("decimal") || t.equals("double") || t.equals("float") || t.equals("numeric");
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
