package com.pulse.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pulse.blueprint.model.Blueprint;
import com.pulse.blueprint.repository.BlueprintRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Regression for LCT-016/020/021/043/044 (JSONB/API shape normalization, workstream A).
 *
 * <p>Spark on the classpath provides {@code jackson-module-scala}, which Hibernate's default
 * JSON format mapper auto-discovers via the ServiceLoader. That made untyped ({@code Object})
 * values nested inside {@code jsonb} columns deserialize into Scala immutable collections, which
 * the clean Spring MVC ObjectMapper then bean-serialized as {@code {"empty":true,"traversableAgain":true}},
 * corrupting every JSON array/object exposed through a REST DTO.
 *
 * <p>{@link com.pulse.config.HibernateJsonConfig} pins Hibernate's format mapper to a clean
 * (no-Scala) ObjectMapper. This IT loads seeded blueprints through the real Hibernate read path
 * and asserts every nested collection deserializes to a {@code java.util} type and re-serializes
 * as a JSON array/object (never the {@code traversableAgain} shape).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("postgres-it")
@Tag("integration")
class JsonbCollectionShapePostgresIT {

    private static final ObjectMapper CLEAN_MAPPER =
            new ObjectMapper().registerModule(new JavaTimeModule());

    @Autowired
    private BlueprintRepository blueprintRepository;

    @Test
    void seededBlueprintJsonbDeserializesToPlainJavaCollections() throws Exception {
        List<Blueprint> blueprints = blueprintRepository.findAllByOrderByCategoryAscNameAsc();
        assertThat(blueprints).isNotEmpty();

        boolean sawNestedCollection = false;
        for (Blueprint bp : blueprints) {
            for (Object root : List.of(
                    nullSafe(bp.getParamsSchema()),
                    nullSafe(bp.getInputPorts()),
                    nullSafe(bp.getOutputPorts()),
                    nullSafe(bp.getArtifactTypes()),
                    nullSafe(bp.getValidLayers()))) {
                sawNestedCollection |= assertPlainJava(root, bp.getBlueprintKey());

                String json = CLEAN_MAPPER.writeValueAsString(root);
                assertThat(json)
                        .as("blueprint %s jsonb must not serialize as a Scala-collection bean", bp.getBlueprintKey())
                        .doesNotContain("traversableAgain")
                        .doesNotContain("\"empty\":true,\"traversableAgain\"");
            }
        }
        assertThat(sawNestedCollection)
                .as("at least one seeded blueprint must expose a nested array/object to make this regression meaningful")
                .isTrue();
    }

    private static Object nullSafe(Object value) {
        return value == null ? List.of() : value;
    }

    /** Recursively asserts every Collection/Map is a {@code java.util} (not Scala) type. Returns true if a nested collection was seen. */
    private static boolean assertPlainJava(Object value, String blueprintKey) {
        if (value == null) {
            return false;
        }
        String pkg = value.getClass().getName();
        assertThat(pkg)
                .as("blueprint %s holds a non-java collection (%s) from jsonb", blueprintKey, pkg)
                .doesNotStartWith("scala.");
        boolean nested = false;
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                nested = true;
                assertPlainJava(e.getValue(), blueprintKey);
            }
        } else if (value instanceof Collection<?> coll) {
            for (Object o : coll) {
                nested = true;
                assertPlainJava(o, blueprintKey);
            }
        }
        return nested;
    }
}
