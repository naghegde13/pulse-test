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

/** {@code route-rows} (rule 18) — FIX #1: rows split, schema carried UNCHANGED to each port. */
class RouteRowsOpTest {

    private final RouteRowsOp op = new RouteRowsOp();

    @Test
    void opName() {
        assertEquals(OpVocabulary.ROUTE_ROWS, op.opName());
    }

    @Test
    void applyIsPassthrough() {
        // FIX #1: routing splits rows, not columns — schema unchanged.
        Schema in = Schema.of(
                ColumnModel.simple("loan_id", "string"),
                ColumnModel.simple("status", "string"),
                ColumnModel.simple("amount", "decimal"));

        Schema out = op.apply(in, null, ResolvedConfig.empty());

        assertEquals(in.names(), out.names());
    }

    @Test
    void applyMultiReturnsNCopiesOfTheInputSchema() {
        Schema in = Schema.of(
                ColumnModel.simple("loan_id", "string"),
                ColumnModel.simple("amount", "decimal"));

        List<Schema> out = op.applyMulti(in, null, ResolvedConfig.empty(), 3);

        assertEquals(3, out.size());
        for (Schema branch : out) {
            assertEquals(in.names(), branch.names());
        }
    }

    @Test
    void branchCountComesFromConfigBranchesWhenPresent() {
        Schema in = Schema.of(ColumnModel.simple("loan_id", "string"));

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("branches", List.of(
                Map.of("name", "high"),
                Map.of("name", "low"),
                Map.of("name", "default")));
        ResolvedConfig cfg = new ResolvedConfig(values);

        // ports arg deliberately differs; config.branches (3) wins.
        List<Schema> out = op.applyMulti(in, null, cfg, 1);

        assertEquals(3, out.size());
        for (Schema branch : out) {
            assertEquals(in.names(), branch.names());
        }
    }

    @Test
    void fallsBackToEnginePortsWhenNoBranchesConfigured() {
        Schema in = Schema.of(ColumnModel.simple("loan_id", "string"));

        List<Schema> out = op.applyMulti(in, null, ResolvedConfig.empty(), 4);

        assertEquals(4, out.size());
    }

    @Test
    void neverFewerThanOneCopy() {
        Schema in = Schema.of(ColumnModel.simple("loan_id", "string"));

        List<Schema> out = op.applyMulti(in, null, ResolvedConfig.empty(), 0);

        assertEquals(1, out.size());
        assertEquals(in.names(), out.get(0).names());
    }
}
