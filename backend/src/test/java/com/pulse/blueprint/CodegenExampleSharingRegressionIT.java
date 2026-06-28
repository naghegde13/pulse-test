package com.pulse.blueprint;

import com.pulse.blueprint.model.Blueprint;
import com.pulse.blueprint.repository.BlueprintRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for #44 Phase D — once V94 has remapped the eight
 * sharing offenders, NO codegen example file should be referenced by
 * more than one blueprint.
 *
 * <p>The user lost trust in the example-viewer feature when discovering
 * that {@code stg_cleaning_basic.sql} was the example for FOUR
 * unrelated blueprints (BronzeToSilverCleaning, JsonFlatten, JsonStruct,
 * SchemaNormalization) and {@code int_filter_complex.sql} was shared
 * by GenericFilter and GenericRouter. The fix in #44 was a
 * comprehensive rewrite: each blueprint that lacked a dedicated example
 * got one, and V94 remapped its example_keys.
 *
 * <p>This test fails LOUDLY if any future migration re-introduces
 * sharing — we never want the agent to receive a code example
 * mismatched to the blueprint it's reasoning about.
 */
@SpringBootTest
@ActiveProfiles("postgres-it")
@Tag("integration")
class CodegenExampleSharingRegressionIT {

    @Autowired
    BlueprintRepository blueprintRepository;

    @Test
    void noExampleKeyIsSharedAcrossBlueprints() {
        Map<String, Set<String>> exampleKeyToBlueprints = new TreeMap<>();
        for (Blueprint bp : blueprintRepository.findAll()) {
            if (bp.getCodegenHints() == null) continue;
            Object raw = bp.getCodegenHints().get("example_keys");
            if (!(raw instanceof List<?> keys)) continue;
            for (Object key : keys) {
                if (key == null) continue;
                exampleKeyToBlueprints
                        .computeIfAbsent(key.toString(), k -> new HashSet<>())
                        .add(bp.getBlueprintKey());
            }
        }

        Map<String, Set<String>> shared = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : exampleKeyToBlueprints.entrySet()) {
            if (entry.getValue().size() > 1) {
                shared.put(entry.getKey(), entry.getValue());
            }
        }

        assertTrue(shared.isEmpty(),
                "Codegen example sharing detected — every example_key should be "
                        + "referenced by at most ONE blueprint. After #44 the user "
                        + "lost trust in the example-viewer feature. Sharing found: "
                        + shared);
    }
}
