package com.pulse.codegen.opengine;

import com.pulse.pipeline.opengine.ColumnModel;
import com.pulse.pipeline.opengine.OpList;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;
import com.pulse.pipeline.opengine.Schema;
import com.pulse.runtime.model.RuntimePersona;
import com.pulse.runtime.service.RuntimeAuthorityService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/** Phase 3 integration — the codegen op-engine wires 32 handlers + composes via the 5 emitters. */
class CodegenOpEngineTest {

    private CodegenOpEngine engine() {
        RuntimeAuthorityService ra = mock(RuntimeAuthorityService.class);
        lenient().when(ra.getActivePersona()).thenReturn(RuntimePersona.GCP_PULSE);
        return new CodegenOpEngine(new ModeResolver(ra));
    }

    @Test
    void registryCoversClosedVocabularyAndEngineSpecificHandlers() {
        assertEquals(32, OpVocabulary.ALL.size());
        assertTrue(engine().registry().size() >= OpVocabulary.ALL.size(),
                "Multiple emission engines may register handlers for the same op");
        HandlerRegistry r = engine().registry();
        assertTrue(r.has(OpVocabulary.WRITE_SINK, EmissionEngine.PYSPARK));
        assertTrue(r.has(OpVocabulary.WRITE_SINK, EmissionEngine.DBT_SQL));
        assertTrue(r.has(OpVocabulary.TRACK_HISTORY_SCD2, EmissionEngine.DBT_SNAPSHOT));
        assertTrue(r.has(OpVocabulary.CHECK_DATA, EmissionEngine.GX));
        assertTrue(r.has(OpVocabulary.SENSE, EmissionEngine.DAG_ONLY));
    }

    @Test
    void everyOpHasAHandlerOnItsPrimaryEngine() {
        HandlerRegistry r = engine().registry();
        // Every one of the 32 ops must resolve a handler on SOME engine.
        for (String op : OpVocabulary.ALL) {
            boolean covered =
                    r.has(op, EmissionEngine.DBT_SQL)
                    || r.has(op, EmissionEngine.PYSPARK)
                    || r.has(op, EmissionEngine.GX)
                    || r.has(op, EmissionEngine.DBT_SNAPSHOT)
                    || r.has(op, EmissionEngine.DAG_ONLY);
            assertTrue(covered, "op '" + op + "' has no emission handler on any engine");
        }
    }

    @Test
    void dbtSqlEmitterComposesCleaningChainAsCtes() {
        CodegenOpEngine e = engine();
        // A Cleaning-style dbt-SQL chain: rename -> drop -> deduplicate.
        List<OpList.OpEntry> ops = List.of(
                new OpList.OpEntry(OpVocabulary.RENAME_COLUMNS, "Rename", Map.of()),
                new OpList.OpEntry(OpVocabulary.DROP_COLUMNS, "Drop", Map.of()),
                new OpList.OpEntry(OpVocabulary.DEDUPLICATE, "Dedup", Map.of()));
        Schema in = Schema.of(
                ColumnModel.simple("a", "string"),
                ColumnModel.simple("b", "string"));

        String sql = e.dbtSql().emit(ops, "ref('bronze')", (op, upstreamRef) ->
                EmitContext.builder()
                        .mode(Mode.GCP_PULSE)
                        .modeResolver(e.modeResolver())
                        .config(ResolvedConfig.empty())
                        .inputSchema(in)
                        .upstreamRef(upstreamRef)
                        .build());

        // CTE chain: WITH step_1_rename_columns AS (... FROM ref('bronze')), step_2..., SELECT * FROM step_3...
        assertTrue(sql.contains("WITH step_1_rename_columns AS ("), sql);
        assertTrue(sql.contains("step_2_drop_columns AS ("), sql);
        assertTrue(sql.contains("step_3_deduplicate AS ("), sql);
        assertTrue(sql.trim().endsWith("FROM step_3_deduplicate"), sql);
    }

    @Test
    void pySparkEmitterComposesIngestionChain() {
        CodegenOpEngine e = engine();
        List<OpList.OpEntry> ops = List.of(
                new OpList.OpEntry(OpVocabulary.READ_SOURCE, "Read", Map.of()),
                new OpList.OpEntry(OpVocabulary.ADD_AUDIT_COLUMNS, "Audit", Map.of()),
                new OpList.OpEntry(OpVocabulary.WRITE_SINK, "Write", Map.of()));

        String py = e.pySpark().emit(ops, op ->
                EmitContext.builder()
                        .mode(Mode.GCP_PULSE)
                        .modeResolver(e.modeResolver())
                        .config(ResolvedConfig.empty())
                        .inputSchema(Schema.empty())
                        .lakeLayer("bronze")
                        .dfVar("df")
                        .build());

        // read-source loads into df; add-audit-columns emits the 8-col set incl _pulse_dag_id;
        // write-sink uses iceberg on GCP bronze, never delta.
        assertTrue(py.contains("spark.read"), py);
        assertTrue(py.contains("_pulse_dag_id"), py);
        assertTrue(py.contains("iceberg"), py);
        assertTrue(!py.contains("delta"), "GCP write must not emit delta:\n" + py);
    }
}
