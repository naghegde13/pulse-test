package com.pulse.codegen.opengine.handlers;

import com.pulse.codegen.opengine.EmitContext;
import com.pulse.codegen.opengine.Mode;
import com.pulse.pipeline.opengine.ColumnModel;
import com.pulse.pipeline.opengine.ResolvedConfig;
import com.pulse.pipeline.opengine.Schema;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GroupAndAggregateDbtSqlHandlerTest {

    private final GroupAndAggregateDbtSqlHandler handler = new GroupAndAggregateDbtSqlHandler();

    @Test
    void emitsGroupByAndAggFns() {
        Map<String, Object> sumAgg = new LinkedHashMap<>();
        sumAgg.put("alias", "total_amount");
        sumAgg.put("function", "SUM");
        sumAgg.put("column", "amount");

        Map<String, Object> countAgg = new LinkedHashMap<>();
        countAgg.put("alias", "row_count");
        countAgg.put("function", "COUNT");
        // no column -> COUNT(*)

        Map<String, Object> distinctAgg = new LinkedHashMap<>();
        distinctAgg.put("alias", "distinct_status");
        distinctAgg.put("function", "COUNT_DISTINCT");
        distinctAgg.put("column", "status");

        EmitContext ctx = EmitContext.builder()
                .mode(Mode.GCP_PULSE)
                .config(new ResolvedConfig(Map.of(
                        "group_by_columns", List.of("region", "product"),
                        "aggregations", List.of(sumAgg, countAgg, distinctAgg))))
                .inputSchema(Schema.of(ColumnModel.simple("region", "string")))
                .upstreamRef("ref('up')")
                .build();

        String sql = handler.emit(ctx);
        assertTrue(sql.contains("GROUP BY region, product"), sql);
        assertTrue(sql.contains("SUM(amount) AS total_amount"), sql);
        assertTrue(sql.contains("COUNT(*) AS row_count"), sql);
        assertTrue(sql.contains("COUNT(DISTINCT status) AS distinct_status"), sql);
        assertTrue(sql.contains("FROM ref('up')"), sql);
    }

    @Test
    void noConfigDegradesToPassthrough() {
        EmitContext ctx = EmitContext.builder()
                .config(ResolvedConfig.empty())
                .upstreamRef("ref('up')")
                .build();
        assertEquals("SELECT *\nFROM ref('up')", handler.emit(ctx));
    }
}
