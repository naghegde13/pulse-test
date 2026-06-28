package com.pulse.pipeline.opengine.ops;

import com.pulse.pipeline.opengine.ColumnModel;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;
import com.pulse.pipeline.opengine.Schema;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * read-source (SPEC #1 §B.1 rule 24) — OUT = the source columns the engine
 * supplies as {@code in}. The op does not fetch a dataset; OUT == IN.
 */
class ReadSourceOpTest {

    private final ReadSourceOp op = new ReadSourceOp();

    private static Schema in() {
        return Schema.of(
                ColumnModel.simple("loan_id", "string"),
                ColumnModel.simple("amount", "decimal"),
                ColumnModel.simple("opened_on", "date"));
    }

    @Test
    void opNameIsCanonical() {
        assertEquals(OpVocabulary.READ_SOURCE, op.opName());
    }

    @Test
    void outputColumnsEqualInput() {
        Schema out = op.apply(in(), null, ResolvedConfig.empty());
        assertEquals(in().names(), out.names());
        assertEquals(in().size(), out.size());
    }

    @Test
    void columnTypesArePreserved() {
        Schema out = op.apply(in(), null, ResolvedConfig.empty());
        assertEquals("string", out.find("loan_id").type());
        assertEquals("decimal", out.find("amount").type());
        assertEquals("date", out.find("opened_on").type());
    }

    @Test
    void columnsAreTaggedSourceLineageWhenAbsent() {
        Schema out = op.apply(in(), null, ResolvedConfig.empty());
        assertEquals("source", out.find("loan_id").extras().get("lineage"));
    }

    @Test
    void existingLineageIsPreserved() {
        Schema withLineage = Schema.of(
                ColumnModel.simple("derived_col", "string").withExtra("lineage", "computed"));
        Schema out = op.apply(withLineage, null, ResolvedConfig.empty());
        assertEquals("computed", out.find("derived_col").extras().get("lineage"));
        assertEquals(List.of("derived_col"), out.names());
    }

    @Test
    void emptyInputStaysEmpty() {
        Schema out = op.apply(Schema.empty(), null, ResolvedConfig.empty());
        assertTrue(out.isEmpty());
    }
}
