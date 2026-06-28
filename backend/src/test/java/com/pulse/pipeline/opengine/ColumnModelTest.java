package com.pulse.pipeline.opengine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase 0 — recursive column model round-trip (SPEC #1 §B.0, fix-item #12). */
class ColumnModelTest {

    @Test
    void simpleRoundTrip() {
        ColumnModel c = ColumnModel.simple("loan_id", "string", false);
        Map<String, Object> m = c.toMap();
        assertEquals("loan_id", m.get("name"));
        assertEquals("string", m.get("type"));
        assertEquals(false, m.get("nullable"));
        assertFalse(m.containsKey("fields"));
        assertFalse(m.containsKey("element"));

        ColumnModel back = ColumnModel.fromMap(m);
        assertTrue(back.isSimple());
        assertEquals("loan_id", back.name());
    }

    @Test
    void structCarriesNestedFields() {
        ColumnModel borrower = ColumnModel.struct("borrower", List.of(
                ColumnModel.simple("fico", "integer"),
                ColumnModel.simple("name", "string")), true);
        Map<String, Object> m = borrower.toMap();
        assertEquals("struct", m.get("type"));
        assertTrue(m.containsKey("fields"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) m.get("fields");
        assertEquals(2, fields.size());
        assertEquals("fico", fields.get(0).get("name"));

        // Round-trip preserves nested fields (fix-item #12 — no silent flattening).
        ColumnModel back = ColumnModel.fromMap(m);
        assertTrue(back.isStruct());
        assertEquals(2, back.fields().size());
        assertEquals("fico", back.fields().get(0).name());
    }

    @Test
    void listOfScalarUsesCompactElementForm() {
        ColumnModel prior = ColumnModel.list("prior_addresses",
                ColumnModel.simple(null, "string"), true);
        Map<String, Object> m = prior.toMap();
        assertEquals("list", m.get("type"));
        // a simple no-name element serializes as the bare type string
        assertEquals("string", m.get("element"));

        ColumnModel back = ColumnModel.fromMap(m);
        assertTrue(back.isList());
        assertEquals("string", back.element().type());
    }

    @Test
    void listOfStructRoundTrips() {
        ColumnModel payments = ColumnModel.list("payments",
                ColumnModel.struct(null, List.of(
                        ColumnModel.simple("amount", "decimal"),
                        ColumnModel.simple("date", "date")), true), true);
        Map<String, Object> m = payments.toMap();
        ColumnModel back = ColumnModel.fromMap(m);
        assertTrue(back.isList());
        assertTrue(back.element().isStruct());
        assertEquals(2, back.element().fields().size());
    }

    @Test
    void extrasArePreservedAcrossRoundTrip() {
        ColumnModel c = ColumnModel.simple("email", "string")
                .withExtra("lineage", "masked:hash")
                .withExtra("tags", List.of("masked", "pii"));
        ColumnModel back = ColumnModel.fromMap(c.toMap());
        assertEquals("masked:hash", back.extras().get("lineage"));
    }

    @Test
    void schemaWrapperRoundTrip() {
        Schema s = Schema.of(
                ColumnModel.simple("a", "integer"),
                ColumnModel.simple("b", "string"));
        Map<String, Object> wrapper = s.toMap();
        Schema back = Schema.fromMap(wrapper);
        assertEquals(List.of("a", "b"), back.names());
    }

    @Test
    void schemaWrapperAcceptsDatasetFieldsShape() {
        Map<String, Object> wrapper = Map.of("fields", List.of(
                Map.of("name", "loan_id", "type", "VARCHAR"),
                Map.of("name", "borrower_name", "type", "VARCHAR")));

        Schema back = Schema.fromMap(wrapper);

        assertEquals(List.of("loan_id", "borrower_name"), back.names());
        assertEquals("VARCHAR", back.find("loan_id").type());
    }
}
