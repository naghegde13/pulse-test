package com.pulse.pipeline.opengine;

import com.pulse.blueprint.model.Blueprint;
import com.pulse.blueprint.model.BlueprintCategory;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase 0 — op-list reader + param surface (SPEC #1 §A). H2-free pure unit tests. */
class SchemaBehaviorReaderTest {

    private final SchemaBehaviorReader reader = new SchemaBehaviorReader();

    private Blueprint bp(Map<String, Object> schemaBehavior, List<Map<String, Object>> paramsSchema) {
        Blueprint b = new Blueprint();
        b.setBlueprintKey("TestBp");
        b.setCategory(BlueprintCategory.TRANSFORM);
        b.setSchemaBehavior(schemaBehavior);
        b.setParamsSchema(paramsSchema);
        return b;
    }

    private Map<String, Object> cleaningOpList() {
        // The §A.2 Cleaning worked example (subset): a param-ref + literal.
        Map<String, Object> sb = new LinkedHashMap<>();
        sb.put("version", 1);
        sb.put("ops", List.of(
                OpList.opEntryMap("rename-columns", "Rename columns",
                        Map.of("rename_map", Map.of("param", "rename_map"))),
                OpList.opEntryMap("deduplicate", "Remove duplicates",
                        Map.of("dedup_key", Map.of("param", "dedup_key")))));
        sb.put("blueprint_params", List.of("partition_by"));
        sb.put("emission", Map.of("orchestration", "airflow", "compute", "dbt"));
        return sb;
    }

    private List<Map<String, Object>> cleaningParamsSchema() {
        return List.of(
                Map.of("name", "rename_map", "type", "object"),
                Map.of("name", "dedup_key", "type", "array"),
                Map.of("name", "partition_by", "type", "array", "tier", "derived",
                        "derivedFrom", "platform_default"));
    }

    @Test
    void parsesPinnedShape() {
        SchemaBehaviorReader.BehaviorView view = reader.read(bp(cleaningOpList(), cleaningParamsSchema()));
        assertEquals(1, view.opList().version());
        assertEquals(2, view.opList().ops().size());
        assertEquals("rename-columns", view.opList().ops().get(0).op());
        assertEquals("Rename columns", view.opList().ops().get(0).uiLabel());
        assertEquals("dbt", view.opList().emission().compute());
        assertFalse(view.opList().emission().isControl());
    }

    @Test
    void derivesParamSurfaceAsUnion() {
        SchemaBehaviorReader.BehaviorView view = reader.read(bp(cleaningOpList(), cleaningParamsSchema()));
        ParamSurface surface = view.surface();
        // union = op-referenced (rename_map, dedup_key) + blueprint_params (partition_by)
        assertEquals(3, surface.size());
        assertTrue(surface.contains("rename_map"));
        assertTrue(surface.contains("dedup_key"));
        assertTrue(surface.contains("partition_by"));
    }

    @Test
    void absentTierDefaultsToUser() {
        SchemaBehaviorReader.BehaviorView view = reader.read(bp(cleaningOpList(), cleaningParamsSchema()));
        assertEquals(ParamSurface.TIER_USER, view.surface().get("rename_map").tier());
        assertEquals(ParamSurface.TIER_DERIVED, view.surface().get("partition_by").tier());
        assertEquals("platform_default", view.surface().get("partition_by").derivedFrom());
    }

    @Test
    void unknownOpLoudFails() {
        Map<String, Object> sb = new LinkedHashMap<>();
        sb.put("ops", List.of(OpList.opEntryMap("not-a-real-op", "x", Map.of())));
        OpEngineException ex = assertThrows(OpEngineException.class, () -> reader.readOpList(bp(sb, List.of())));
        assertTrue(ex.getMessage().contains("outside the 32-op closed vocabulary"));
    }

    @Test
    void missingUiLabelLoudFails() {
        Map<String, Object> sb = new LinkedHashMap<>();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("op", "filter-rows");
        entry.put("config", Map.of());
        sb.put("ops", List.of(entry));
        assertThrows(OpEngineException.class, () -> reader.readOpList(bp(sb, List.of())));
    }

    @Test
    void paramRefWithoutDescriptorLoudFails() {
        Map<String, Object> sb = new LinkedHashMap<>();
        sb.put("ops", List.of(OpList.opEntryMap("rename-columns", "Rename",
                Map.of("rename_map", Map.of("param", "ghost_param")))));
        // params_schema has no "ghost_param" descriptor.
        OpEngineException ex = assertThrows(OpEngineException.class,
                () -> reader.read(bp(sb, List.of())));
        assertTrue(ex.getMessage().contains("ghost_param"));
    }

    @Test
    void noOpListIsDetected() {
        // Legacy throwaway content {effect_type, conflict_policy} — no ops array.
        Blueprint legacy = bp(Map.of("effect_type", "history_emitter", "conflict_policy", "block"), List.of());
        assertFalse(reader.hasOpList(legacy));
        assertThrows(OpEngineException.class, () -> reader.read(legacy));
    }

    @Test
    void controlEmissionHasNullCompute() {
        Map<String, Object> sb = new LinkedHashMap<>();
        sb.put("ops", List.of(OpList.opEntryMap("sense", "Sense", Map.of())));
        sb.put("emission", Map.of("orchestration", "airflow", "compute", "null"));
        OpList ol = reader.readOpList(bp(sb, List.of()));
        assertTrue(ol.emission().isControl());
    }
}
