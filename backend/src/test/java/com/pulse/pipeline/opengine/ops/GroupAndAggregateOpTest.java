package com.pulse.pipeline.opengine.ops;

import com.pulse.pipeline.opengine.ColumnModel;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;
import com.pulse.pipeline.opengine.Schema;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@code group-and-aggregate} (rule 11) — FIX #6: correct aggregate output types. */
class GroupAndAggregateOpTest {

    private final GroupAndAggregateOp op = new GroupAndAggregateOp();

    @Test
    void opName() {
        assertEquals(OpVocabulary.GROUP_AND_AGGREGATE, op.opName());
    }

    private static Map<String, Object> agg(String alias, String function, String column) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("alias", alias);
        m.put("function", function);
        m.put("column", column);
        return m;
    }

    private static ResolvedConfig cfg(List<String> groupBy, List<Map<String, Object>> aggs) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("group_by_columns", groupBy);
        values.put("aggregations", aggs);
        return new ResolvedConfig(values);
    }

    @Test
    void countAndCountDistinctAreLong() {
        // FIX #6: COUNT and COUNT_DISTINCT -> long (legacy wrongly used integer).
        Schema in = Schema.of(
                ColumnModel.simple("region", "string"),
                ColumnModel.simple("loan_id", "string"));
        ResolvedConfig cfg = cfg(List.of("region"), List.of(
                agg("n", "count", "loan_id"),
                agg("n_distinct", "count_distinct", "loan_id")));

        Schema out = op.apply(in, null, cfg);

        assertEquals(List.of("region", "n", "n_distinct"), out.names());
        assertEquals("long", out.find("n").type());
        assertEquals("long", out.find("n_distinct").type());
    }

    @Test
    void sumOnIntegerSourceIsLong() {
        // FIX #6: SUM(integer-source) -> long.
        Schema in = Schema.of(
                ColumnModel.simple("region", "string"),
                ColumnModel.simple("count_units", "integer"),
                ColumnModel.simple("big_count", "long"));
        ResolvedConfig cfg = cfg(List.of("region"), List.of(
                agg("total_units", "sum", "count_units"),
                agg("total_big", "sum", "big_count")));

        Schema out = op.apply(in, null, cfg);

        assertEquals("long", out.find("total_units").type());
        assertEquals("long", out.find("total_big").type());
    }

    @Test
    void sumOnDecimalSourceIsDouble() {
        // FIX #6: SUM(decimal-source) -> double.
        Schema in = Schema.of(
                ColumnModel.simple("region", "string"),
                ColumnModel.simple("amount", "decimal"),
                ColumnModel.simple("rate", "double"));
        ResolvedConfig cfg = cfg(List.of("region"), List.of(
                agg("total_amount", "sum", "amount"),
                agg("total_rate", "sum", "rate")));

        Schema out = op.apply(in, null, cfg);

        assertEquals("double", out.find("total_amount").type());
        assertEquals("double", out.find("total_rate").type());
    }

    @Test
    void avgIsAlwaysDouble() {
        // FIX #6: AVG -> double (legacy wrongly used decimal).
        Schema in = Schema.of(
                ColumnModel.simple("region", "string"),
                ColumnModel.simple("amount", "decimal"),
                ColumnModel.simple("units", "integer"));
        ResolvedConfig cfg = cfg(List.of("region"), List.of(
                agg("avg_amount", "avg", "amount"),
                agg("avg_units", "avg", "units")));

        Schema out = op.apply(in, null, cfg);

        assertEquals("double", out.find("avg_amount").type());
        assertEquals("double", out.find("avg_units").type());
    }

    @Test
    void minMaxTakeSourceType() {
        // FIX #6: MIN/MAX -> source column type.
        Schema in = Schema.of(
                ColumnModel.simple("region", "string"),
                ColumnModel.simple("amount", "decimal"),
                ColumnModel.simple("opened_on", "date"));
        ResolvedConfig cfg = cfg(List.of("region"), List.of(
                agg("min_amount", "min", "amount"),
                agg("max_opened", "max", "opened_on")));

        Schema out = op.apply(in, null, cfg);

        assertEquals("decimal", out.find("min_amount").type());
        assertEquals("date", out.find("max_opened").type());
    }

    @Test
    void groupByTypesComeFromInput() {
        Schema in = Schema.of(
                ColumnModel.simple("region", "string"),
                ColumnModel.simple("year", "integer"),
                ColumnModel.simple("amount", "decimal"));
        ResolvedConfig cfg = cfg(List.of("region", "year"), List.of(
                agg("total", "sum", "amount")));

        Schema out = op.apply(in, null, cfg);

        // Order: group-by cols (config order), then agg cols (config order).
        assertEquals(List.of("region", "year", "total"), out.names());
        assertEquals("string", out.find("region").type());
        assertEquals("integer", out.find("year").type());
    }

    @Test
    void groupByColumnAbsentFromInputIsString() {
        Schema in = Schema.of(ColumnModel.simple("amount", "decimal"));
        ResolvedConfig cfg = cfg(List.of("missing_dim"), List.of(
                agg("total", "sum", "amount")));

        Schema out = op.apply(in, null, cfg);

        assertEquals("string", out.find("missing_dim").type());
    }

    @Test
    void functionNamesAreCaseInsensitive() {
        Schema in = Schema.of(
                ColumnModel.simple("region", "string"),
                ColumnModel.simple("amount", "decimal"));
        ResolvedConfig cfg = cfg(List.of("region"), List.of(
                agg("n", "COUNT", "amount"),
                agg("total", "Sum", "amount"),
                agg("a", "AVG", "amount")));

        Schema out = op.apply(in, null, cfg);

        assertEquals("long", out.find("n").type());
        assertEquals("double", out.find("total").type());
        assertEquals("double", out.find("a").type());
    }

    @Test
    void sumWithUnknownSourceDefaultsToDouble() {
        Schema in = Schema.of(ColumnModel.simple("region", "string"));
        ResolvedConfig cfg = cfg(List.of("region"), List.of(
                agg("total", "sum", "not_present")));

        Schema out = op.apply(in, null, cfg);

        assertEquals("double", out.find("total").type());
    }
}
