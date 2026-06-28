package com.pulse.pipeline.opengine.ops;

import com.pulse.pipeline.opengine.ColumnModel;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;
import com.pulse.pipeline.opengine.Schema;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code join} (rule 10) — FIX #5: keep BOTH sides on a same-name collision. */
class JoinOpTest {

    private final JoinOp op = new JoinOp();

    @Test
    void opName() {
        assertEquals(OpVocabulary.JOIN, op.opName());
    }

    @Test
    void matchingTypeCollisionKeepsBothSides() {
        // FIX #5: same name, SAME type -> keep both; right side becomes right_<name>.
        Schema in = Schema.of(
                ColumnModel.simple("loan_id", "string"),
                ColumnModel.simple("amount", "decimal"));
        Schema in2 = Schema.of(
                ColumnModel.simple("loan_id", "string"),   // collides, same type
                ColumnModel.simple("rate", "double"));      // non-colliding

        Schema out = op.apply(in, in2, ResolvedConfig.empty());

        assertEquals(List.of("loan_id", "amount", "right_loan_id", "rate"), out.names());
        // The right column survives (legacy wrongly dropped it).
        assertTrue(out.hasColumn("right_loan_id"));
        assertEquals("string", out.find("right_loan_id").type());
    }

    @Test
    void differingTypeCollisionAlsoRenamesRight() {
        // Same name, DIFFERENT type -> keep both, right side right_<name>.
        Schema in = Schema.of(ColumnModel.simple("id", "string"));
        Schema in2 = Schema.of(ColumnModel.simple("id", "integer"));

        Schema out = op.apply(in, in2, ResolvedConfig.empty());

        assertEquals(List.of("id", "right_id"), out.names());
        assertEquals("string", out.find("id").type());
        assertEquals("integer", out.find("right_id").type());
    }

    @Test
    void nonCollidingColumnsPassThroughUnchanged() {
        Schema in = Schema.of(
                ColumnModel.simple("a", "integer"),
                ColumnModel.simple("b", "string"));
        Schema in2 = Schema.of(
                ColumnModel.simple("c", "double"),
                ColumnModel.simple("d", "boolean"));

        Schema out = op.apply(in, in2, ResolvedConfig.empty());

        assertEquals(List.of("a", "b", "c", "d"), out.names());
        assertFalse(out.hasColumn("right_c"));
    }

    @Test
    void orderPreservedAllInFirstThenIn2() {
        Schema in = Schema.of(
                ColumnModel.simple("x", "integer"),
                ColumnModel.simple("shared", "string"),
                ColumnModel.simple("y", "string"));
        Schema in2 = Schema.of(
                ColumnModel.simple("z", "double"),
                ColumnModel.simple("shared", "string"));

        Schema out = op.apply(in, in2, ResolvedConfig.empty());

        // All IN cols first (unchanged), then IN2 cols (renamed where colliding).
        assertEquals(List.of("x", "shared", "y", "z", "right_shared"), out.names());
    }

    @Test
    void nullSecondaryInputYieldsJustIn() {
        Schema in = Schema.of(
                ColumnModel.simple("a", "integer"),
                ColumnModel.simple("b", "string"));

        Schema out = op.apply(in, null, ResolvedConfig.empty());

        assertEquals(List.of("a", "b"), out.names());
    }

    @Test
    void emptySecondaryInputYieldsJustIn() {
        Schema in = Schema.of(ColumnModel.simple("a", "integer"));
        Schema out = op.apply(in, Schema.empty(), ResolvedConfig.empty());
        assertEquals(List.of("a"), out.names());
    }
}
