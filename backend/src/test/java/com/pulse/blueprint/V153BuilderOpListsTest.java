package com.pulse.blueprint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQL-text assertion test for V153 (the builder op-lists + param-tiering migration).
 *
 * <p>Why SQL-text rather than a SpringBootTest replay: Flyway is DISABLED on the fast PR
 * lane (H2, ddl-auto=create-drop), so V153 never executes there — a SpringBootTest could
 * not observe its effect. The Postgres-IT lane ({@link com.pulse.config.V153V154PostgresCatalogIT})
 * replays V153/V154 against real Postgres. This test parses the migration file's JSON
 * literals directly and asserts the op-list / tiering / deprecation / correction CONTRACT,
 * giving the fast lane a real signal without a DB.
 *
 * <p>It enforces SPEC #1 §A.1 (schema_behavior shape + the 32-op closed vocabulary), §A.3
 * (tier:"user"|"derived" + derivedFrom iff derived), and SPEC #2 §D (deprecations + the
 * SnapshotModel artifact_types correction).
 */
class V153BuilderOpListsTest {

    private static final Path V153 = Path.of(
            "src/main/resources/db/migration/V153__builder_op_lists_and_param_tiering.sql");

    /** The 32 closed ops (ADR 0012 §2 / SPEC #1 §B.1). */
    private static final Set<String> OPS_32 = Set.of(
            "read-source", "add-audit-columns", "add-column", "transform-values", "drop-columns",
            "keep-columns", "rename-columns", "change-types", "mask-columns", "flatten-json",
            "build-struct", "join", "group-and-aggregate", "union-all", "distinct-union", "sort",
            "sample-limit", "filter-rows", "deduplicate", "route-rows", "merge-rows",
            "track-history-scd2", "take-periodic-snapshot", "check-data", "emit-report", "write-sink",
            "sql-model", "sense", "schedule-and-triggers", "rollback", "advance-time", "invoke-remote");

    private static final Set<String> ALLOWED_DERIVED_FROM = Set.of(
            "pipeline.storage", "domain.calendar", "connector", "platform_default",
            "target_dataset.state_binding", "pipeline", "pipeline.id");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String sql() throws Exception {
        return Files.readString(V153);
    }

    /**
     * The migration with {@code --} line comments stripped, so text assertions match only
     * executable SQL (the header/section comments legitimately mention strings like
     * "system-derived" or "ON CONFLICT" in prose, which must not trip a contract check).
     */
    private static String sqlNoComments() throws Exception {
        StringBuilder b = new StringBuilder();
        for (String line : sql().split("\n", -1)) {
            int c = line.indexOf("--");
            b.append(c >= 0 ? line.substring(0, c) : line).append('\n');
        }
        return b.toString();
    }

    /**
     * Pulls every {@code '...'::jsonb} literal out of the file and json-parses it. Returns
     * only the ones that are objects with a top-level "ops" key (schema_behavior blobs) or
     * arrays whose first element has a "tier"/"name" (params_schema blobs) — enough for the
     * structural assertions below. Apostrophes inside SQL strings are doubled ('') per SQL;
     * we un-double them before JSON-parsing.
     */
    private static List<JsonNode> jsonbBlobs(String sql) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        int i = 0;
        int n = sql.length();
        // Linear scan for SQL single-quoted string literals (with '' escaping) that are
        // immediately followed by ::jsonb. Avoids regex backtracking on multi-KB JSON blobs.
        while (i < n) {
            char ch = sql.charAt(i);
            if (ch != '\'') { i++; continue; }
            StringBuilder lit = new StringBuilder();
            i++; // past opening quote
            while (i < n) {
                char c = sql.charAt(i);
                if (c == '\'') {
                    if (i + 1 < n && sql.charAt(i + 1) == '\'') { // escaped ''
                        lit.append('\'');
                        i += 2;
                    } else { // closing quote
                        i++;
                        break;
                    }
                } else {
                    lit.append(c);
                    i++;
                }
            }
            if (sql.startsWith("::jsonb", i)) {
                i += "::jsonb".length();
                String raw = lit.toString();
                try {
                    out.add(MAPPER.readTree(raw));
                } catch (Exception parseFail) {
                    throw new AssertionError("V153 contains a '...'::jsonb literal that is not valid JSON: "
                            + raw.substring(0, Math.min(120, raw.length())), parseFail);
                }
            }
        }
        return out;
    }

    @Test
    void everyJsonbLiteralParses() throws Exception {
        // jsonbBlobs throws on any non-JSON ::jsonb literal; this asserts we found a meaningful number.
        List<JsonNode> blobs = jsonbBlobs(sqlNoComments());
        assertTrue(blobs.size() >= 70,
                "Expected >= 70 ::jsonb literals (40 schema_behavior + 40 params_schema across ~40 rows); got "
                        + blobs.size());
    }

    @Test
    void everyOpIsInTheClosed32Vocabulary() throws Exception {
        int opEntries = 0;
        for (JsonNode blob : jsonbBlobs(sqlNoComments())) {
            if (blob.isObject() && blob.has("ops")) {
                for (JsonNode op : blob.get("ops")) {
                    assertTrue(op.has("op") && op.has("ui_label") && op.has("config"),
                            "Every op-entry must have op + ui_label + config: " + op);
                    assertFalse(op.get("ui_label").asText().isBlank(), "ui_label must be non-empty: " + op);
                    String name = op.get("op").asText();
                    assertTrue(OPS_32.contains(name),
                            "op '" + name + "' is not one of the 32 closed-vocabulary ops");
                    opEntries++;
                }
            }
        }
        assertTrue(opEntries >= 40, "Expected >= 40 op-entries across the catalog; got " + opEntries);
    }

    @Test
    void everyParamRefResolvesToADescriptorInTheSameBlueprint() throws Exception {
        // Pair each schema_behavior blob with the params_schema blob that follows it (file order:
        // each row writes schema_behavior then params_schema). Then assert every {"param":"x"} ref
        // (in ops config) AND every blueprint_params name has a descriptor in that params_schema.
        List<JsonNode> blobs = jsonbBlobs(sqlNoComments());
        int checkedRows = 0;
        for (int i = 0; i < blobs.size() - 1; i++) {
            JsonNode sb = blobs.get(i);
            if (!(sb.isObject() && sb.has("ops"))) continue;
            JsonNode ps = blobs.get(i + 1);
            assertTrue(ps.isArray(), "A schema_behavior blob must be followed by its params_schema array");

            Set<String> descriptors = new HashSet<>();
            for (JsonNode p : ps) descriptors.add(p.get("name").asText());

            // refs inside ops config
            for (JsonNode op : sb.get("ops")) {
                JsonNode config = op.get("config");
                config.fields().forEachRemaining(e -> {
                    JsonNode v = e.getValue();
                    if (v.isObject() && v.has("param")) {
                        // a param-ref must be EXACTLY {"param":"<name>"} (size 1)
                        assertEquals(1, v.size(),
                                "A config param-ref must be exactly {\"param\":...}: " + v);
                        String ref = v.get("param").asText();
                        assertTrue(descriptors.contains(ref),
                                "param-ref '" + ref + "' has no descriptor in params_schema; descriptors="
                                        + descriptors);
                    }
                });
            }
            // names in blueprint_params
            for (JsonNode bp : sb.get("blueprint_params")) {
                assertTrue(descriptors.contains(bp.asText()),
                        "blueprint_params entry '" + bp.asText() + "' has no params_schema descriptor");
            }
            checkedRows++;
        }
        assertTrue(checkedRows >= 38, "Expected >= 38 schema_behavior rows checked; got " + checkedRows);
    }

    @Test
    void everyParamIsExplicitlyTieredUserOrDerivedWithDerivedFromIffDerived() throws Exception {
        int paramsChecked = 0;
        for (JsonNode blob : jsonbBlobs(sqlNoComments())) {
            if (!blob.isArray() || blob.isEmpty() || !blob.get(0).has("tier")) continue;
            for (JsonNode p : blob) {
                String name = p.path("name").asText("<unnamed>");
                assertTrue(p.has("tier"), "param '" + name + "' is missing tier");
                String tier = p.get("tier").asText();
                assertTrue(tier.equals("user") || tier.equals("derived"),
                        "param '" + name + "' tier must be 'user' or 'derived' (NOT 'system-derived'); got " + tier);
                if (tier.equals("derived")) {
                    assertTrue(p.has("derivedFrom"), "derived param '" + name + "' must carry derivedFrom");
                    assertTrue(ALLOWED_DERIVED_FROM.contains(p.get("derivedFrom").asText()),
                            "param '" + name + "' derivedFrom not in allowed set: " + p.get("derivedFrom").asText());
                } else {
                    assertFalse(p.has("derivedFrom"),
                            "user-tier param '" + name + "' must NOT carry derivedFrom");
                }
                paramsChecked++;
            }
        }
        assertTrue(paramsChecked >= 200, "Expected >= 200 tiered params; got " + paramsChecked);
    }

    @Test
    void emissionComputeIsNeverTheHumanSpellingSystemDerived() throws Exception {
        // Guard against the R2 risk: the serialized tier value must be "derived", never "system-derived".
        assertFalse(sqlNoComments().contains("system-derived"),
                "V153 must serialize tier 'derived', never the human spelling 'system-derived'");
    }

    @Test
    void doesNotTouchTheCategoryColumn() throws Exception {
        // category may appear only inside the 2 INSERT column-lists, never as a SET target.
        assertFalse(sqlNoComments().matches("(?s).*SET[^;]*\\bcategory\\b\\s*=.*"),
                "V153 must NOT assign the category column (DESTINATION/ORCHESTRATION must be preserved)");
    }

    @Test
    void deprecatesTheFourDeadBlueprintsWithReplacements() throws Exception {
        String s = sql();
        assertTrue(s.contains("replacement_blueprint_key = 'FileArrivalSensor'")
                && s.contains("WHERE blueprint_key = 'ObjectStoreKeySensor'"),
                "ObjectStoreKeySensor must deprecate to FileArrivalSensor");
        assertTrue(s.contains("replacement_blueprint_key = 'ScheduleAndTriggers'")
                && s.contains("WHERE blueprint_key = 'DatasetDependencySensor'"),
                "DatasetDependencySensor must deprecate to ScheduleAndTriggers");
        assertTrue(s.contains("replacement_blueprint_key = 'BulkBackfill'")
                && s.contains("WHERE blueprint_key = 'BackfillAndReplay'"),
                "BackfillAndReplay must deprecate to BulkBackfill");
        assertTrue(s.contains("replacement_blueprint_key = NULL")
                && s.contains("WHERE blueprint_key = 'CostMonitoringHook'"),
                "CostMonitoringHook must deprecate to NULL");
        // each deprecation pins add_surface='none' and status='deprecated'
        long deprecations = s.lines().filter(l -> l.contains("status = 'deprecated'")).count();
        assertTrue(deprecations >= 4, "Expected >= 4 deprecation UPDATEs; got " + deprecations);
        assertTrue(s.contains("add_surface = 'none'"), "deprecations must pin add_surface='none'");
    }

    @Test
    void correctsSnapshotModelArtifactTypesAndLeavesScd2Untouched() throws Exception {
        String s = sqlNoComments();
        assertTrue(s.contains("SET artifact_types = '[\"incremental\"]'::jsonb")
                && s.contains("WHERE blueprint_key = 'SnapshotModel'"),
                "SnapshotModel artifact_types must be corrected to [\"incremental\"]");
        // No executable statement may assign artifact_types to the SCD2Dimension row.
        assertFalse(s.matches("(?s).*artifact_types\\s*=[^;]*SCD2Dimension.*"),
                "SCD2Dimension artifact_types must NOT be touched (it stays [\"dbt_snapshot\"])");
    }

    @Test
    void insertsTheTwoNewSqlBlueprintsIdempotently() throws Exception {
        String s = sqlNoComments();
        assertTrue(s.contains("'SqlModel'") && s.contains("'SourceSQL'"),
                "V153 must INSERT SqlModel and SourceSQL");
        long onConflict = s.lines().filter(l -> l.contains("ON CONFLICT (blueprint_key) DO NOTHING")).count();
        assertEquals(2, onConflict, "Both new-blueprint INSERTs must guard ON CONFLICT (blueprint_key) DO NOTHING");
        assertTrue(s.contains("connector_instance_id"),
                "SourceSQL/SqlModel must use the connector_instance_id convention");
    }

    @Test
    void mergesAggregateMaterializationAndExcludesDerive() throws Exception {
        String s = sql();
        assertTrue(s.contains("WHERE blueprint_key = 'GenericAggregate'"),
                "GenericAggregate (the merge survivor) must be seeded");
        assertFalse(s.contains("'AggregateMaterialization'"),
                "AggregateMaterialization must NOT be seeded (merged into GenericAggregate)");
        assertFalse(s.contains("WHERE blueprint_key = 'Derive'") || s.contains("'Derive',"),
                "Derive (V102) is out of scope for V153");
    }

    @Test
    void advanceTimeDimensionIsTheConsolidatedIntentSurface() throws Exception {
        // Find AdvanceTimeDimension's params_schema (the array preceding its WHERE clause) and
        // assert the consolidation: advance_to present, advance_mode/requested_asof_expr/storage_backend absent.
        String s = sql();
        int where = s.indexOf("WHERE blueprint_key = 'AdvanceTimeDimension'");
        assertTrue(where > 0, "AdvanceTimeDimension must be re-seeded");
        // the block from its UPDATE to its WHERE
        int updStart = s.lastIndexOf("UPDATE blueprints", where);
        String block = s.substring(updStart, where);
        assertTrue(block.contains("\"advance_to\""), "AdvanceTimeDimension must carry the consolidated advance_to");
        assertFalse(block.contains("advance_mode"), "advance_mode must be consolidated away");
        assertFalse(block.contains("requested_asof_expr"), "requested_asof_expr must be consolidated away");
        assertFalse(block.contains("storage_backend"),
                "the AdvanceTimeDimension intent rewrite drops storage_backend from its param surface");
    }
}
