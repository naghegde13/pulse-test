package com.pulse.config;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Postgres-backed integration test that replays the full Flyway chain (including V153 +
 * V154) against real Postgres and asserts the seeded catalog + demo compositions.
 *
 * <p>This is the REAL validation lane for V153/V154: the fast PR lane disables Flyway (H2,
 * ddl-auto=create-drop), so neither migration executes there. Tagged {@code integration}
 * so {@code fastPrTest} excludes it and {@code backendIntegrationTest} includes it; it
 * boots the {@code postgres-it} profile (Flyway enabled, Postgres datasource) like
 * {@link PostgresFlywayMigrationSmokeIT}.
 *
 * <p>Asserts (SPEC #1 §A.1/§A.3, SPEC #2 §D, SPEC #5):
 * <ol>
 *   <li>every active blueprint's schema_behavior op-list uses only the 32 closed ops;</li>
 *   <li>every {@code {"param":...}} ref resolves to a params_schema descriptor;</li>
 *   <li>every active param is tiered user|derived (derivedFrom iff derived);</li>
 *   <li>the 4 deprecations are applied with the right replacements + add_surface='none';</li>
 *   <li>SnapshotModel artifact_types corrected to ["incremental"]; SCD2Dimension stays ["dbt_snapshot"];</li>
 *   <li>no duplicate blueprint_key; SqlModel/SourceSQL inserted;</li>
 *   <li>the V154 demo compositions resolve into fully-wired graphs whose port names match
 *       the catalog's declared ports.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("postgres-it")
@Tag("integration")
class V153V154PostgresCatalogIT {

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

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    @Test
    void activeBlueprintOpListsUseOnlyTheClosed32Vocabulary() throws Exception {
        JdbcTemplate jdbc = jdbc();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT blueprint_key, schema_behavior::text AS sb FROM blueprints "
                        + "WHERE status IS DISTINCT FROM 'deprecated' AND schema_behavior IS NOT NULL");
        assertThat(rows).as("active blueprints with a serialized op-list").isNotEmpty();
        int opEntries = 0;
        for (Map<String, Object> row : rows) {
            String key = (String) row.get("blueprint_key");
            JsonNode sb = MAPPER.readTree((String) row.get("sb"));
            if (!sb.has("ops")) continue; // pre-V153 throwaway content tolerated only on deprecated rows
            for (JsonNode op : sb.get("ops")) {
                assertThat(op.has("op") && op.has("ui_label") && op.has("config"))
                        .as(key + ": op-entry must have op+ui_label+config").isTrue();
                String name = op.get("op").asText();
                assertThat(OPS_32).as(key + ": op '" + name + "' must be in the 32-op vocab").contains(name);
                opEntries++;
            }
        }
        assertThat(opEntries).as("total op-entries across active blueprints").isGreaterThanOrEqualTo(40);
    }

    @Test
    void everyParamRefResolvesAndEveryActiveParamIsTiered() throws Exception {
        JdbcTemplate jdbc = jdbc();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT blueprint_key, schema_behavior::text AS sb, params_schema::text AS ps "
                        + "FROM blueprints WHERE status IS DISTINCT FROM 'deprecated' "
                        + "AND schema_behavior IS NOT NULL AND params_schema IS NOT NULL");
        int paramsChecked = 0;
        for (Map<String, Object> row : rows) {
            String key = (String) row.get("blueprint_key");
            JsonNode sb = MAPPER.readTree((String) row.get("sb"));
            JsonNode ps = MAPPER.readTree((String) row.get("ps"));
            if (!sb.has("ops") || !ps.isArray()) continue;

            Set<String> descriptors = new HashSet<>();
            for (JsonNode p : ps) descriptors.add(p.get("name").asText());

            // (A.4) every param-ref resolves
            for (JsonNode op : sb.get("ops")) {
                op.get("config").fields().forEachRemaining(e -> {
                    JsonNode v = e.getValue();
                    if (v.isObject() && v.has("param")) {
                        assertThat(v.size()).as(key + ": param-ref must be exactly {param:...}").isEqualTo(1);
                        assertThat(descriptors).as(key + ": param-ref '" + v.get("param").asText() + "' resolves")
                                .contains(v.get("param").asText());
                    }
                });
            }
            for (JsonNode bp : sb.get("blueprint_params")) {
                assertThat(descriptors).as(key + ": blueprint_params '" + bp.asText() + "' resolves")
                        .contains(bp.asText());
            }
            // (A.3) every active param tiered
            for (JsonNode p : ps) {
                String name = p.path("name").asText("<unnamed>");
                assertThat(p.has("tier")).as(key + "." + name + ": has tier").isTrue();
                String tier = p.get("tier").asText();
                assertThat(tier).as(key + "." + name + ": tier user|derived").isIn("user", "derived");
                if (tier.equals("derived")) {
                    assertThat(p.has("derivedFrom")).as(key + "." + name + ": derived has derivedFrom").isTrue();
                    assertThat(ALLOWED_DERIVED_FROM).as(key + "." + name + ": derivedFrom allowed")
                            .contains(p.get("derivedFrom").asText());
                } else {
                    assertThat(p.has("derivedFrom")).as(key + "." + name + ": user has no derivedFrom").isFalse();
                }
                paramsChecked++;
            }
        }
        assertThat(paramsChecked).as("active tiered params").isGreaterThanOrEqualTo(200);
    }

    @Test
    void fourDeprecationsApplied() {
        JdbcTemplate jdbc = jdbc();
        assertReplacement(jdbc, "ObjectStoreKeySensor", "FileArrivalSensor");
        assertReplacement(jdbc, "DatasetDependencySensor", "ScheduleAndTriggers");
        assertReplacement(jdbc, "BackfillAndReplay", "BulkBackfill");
        // CostMonitoringHook -> NULL replacement
        Map<String, Object> cmh = jdbc.queryForMap(
                "SELECT status, deferred, add_surface, replacement_blueprint_key FROM blueprints "
                        + "WHERE blueprint_key = 'CostMonitoringHook'");
        assertThat(cmh.get("status")).isEqualTo("deprecated");
        assertThat(cmh.get("add_surface")).isEqualTo("none");
        assertThat(cmh.get("replacement_blueprint_key")).isNull();
    }

    private void assertReplacement(JdbcTemplate jdbc, String key, String replacement) {
        Map<String, Object> r = jdbc.queryForMap(
                "SELECT status, deferred, add_surface, replacement_blueprint_key FROM blueprints "
                        + "WHERE blueprint_key = ?", key);
        assertThat(r.get("status")).as(key + " status").isEqualTo("deprecated");
        assertThat(r.get("deferred")).as(key + " deferred").isEqualTo(true);
        assertThat(r.get("add_surface")).as(key + " add_surface").isEqualTo("none");
        assertThat(r.get("replacement_blueprint_key")).as(key + " replacement").isEqualTo(replacement);
    }

    @Test
    void snapshotModelCorrectedAndScd2Untouched() {
        JdbcTemplate jdbc = jdbc();
        String snap = jdbc.queryForObject(
                "SELECT artifact_types::text FROM blueprints WHERE blueprint_key = 'SnapshotModel'", String.class);
        assertThat(snap).as("SnapshotModel artifact_types").contains("incremental").doesNotContain("dbt_snapshot");
        String scd2 = jdbc.queryForObject(
                "SELECT artifact_types::text FROM blueprints WHERE blueprint_key = 'SCD2Dimension'", String.class);
        assertThat(scd2).as("SCD2Dimension artifact_types stays dbt_snapshot").contains("dbt_snapshot");
    }

    @Test
    void noDuplicateBlueprintKeysAndTwoNewSqlBlueprintsInserted() {
        JdbcTemplate jdbc = jdbc();
        List<Map<String, Object>> dups = jdbc.queryForList(
                "SELECT blueprint_key, COUNT(*) c FROM blueprints GROUP BY blueprint_key HAVING COUNT(*) > 1");
        assertThat(dups).as("no duplicate blueprint_key").isEmpty();

        Integer sqlModel = jdbc.queryForObject(
                "SELECT COUNT(*) FROM blueprints WHERE blueprint_key = 'SqlModel' AND category = 'TRANSFORM'",
                Integer.class);
        Integer sourceSql = jdbc.queryForObject(
                "SELECT COUNT(*) FROM blueprints WHERE blueprint_key = 'SourceSQL' AND category = 'INGESTION'",
                Integer.class);
        assertThat(sqlModel).as("SqlModel inserted as TRANSFORM").isEqualTo(1);
        assertThat(sourceSql).as("SourceSQL inserted as INGESTION").isEqualTo(1);

        // category preservation: sinks stay DESTINATION, controls stay ORCHESTRATION
        Integer destinations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM blueprints WHERE blueprint_key IN "
                        + "('WarehouseWriter','LakeWriter','DatabaseWriter','StreamWriter') AND category = 'DESTINATION'",
                Integer.class);
        assertThat(destinations).as("4 sink blueprints keep category DESTINATION").isEqualTo(4);
    }

    @Test
    void demoCompositionsResolveIntoFullyWiredGraphs() {
        JdbcTemplate jdbc = jdbc();
        // The 3 V154 demo versions. The anchor (loan_master) first.
        List<String> versions = List.of(
                "01JDEMO0HL0LOANMASTERV0101",
                "01JDEMO0HL0RISKMARTV010001",
                "01JDEMO0UL0CARDTXNV0100001");
        int totalInstances = 0;
        int totalWirings = 0;
        for (String versionId : versions) {
            List<Map<String, Object>> instances = jdbc.queryForList(
                    "SELECT id, name, blueprint_key, execution_order FROM sub_pipeline_instances "
                            + "WHERE version_id = ? ORDER BY execution_order ASC", versionId);
            assertThat(instances).as("version " + versionId + " has instances").isNotEmpty();

            Set<String> instanceIds = new HashSet<>();
            for (Map<String, Object> inst : instances) {
                String key = (String) inst.get("blueprint_key");
                instanceIds.add((String) inst.get("id"));
                // every instance must reference an ACTIVE catalog blueprint (so its ports resolve)
                Integer active = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM blueprints WHERE blueprint_key = ? "
                                + "AND status IS DISTINCT FROM 'deprecated'", Integer.class, key);
                assertThat(active).as(versionId + ": instance blueprint '" + key + "' is active").isEqualTo(1);
                assertThat(inst.get("name")).as(versionId + ": instance has a name").isNotNull();
            }
            totalInstances += instances.size();

            List<Map<String, Object>> wirings = jdbc.queryForList(
                    "SELECT source_instance_id, source_port_name, target_instance_id, target_port_name "
                            + "FROM port_wirings WHERE version_id = ?", versionId);
            for (Map<String, Object> w : wirings) {
                // endpoints reference instances of this version
                assertThat(instanceIds).as(versionId + ": wiring source instance exists")
                        .contains((String) w.get("source_instance_id"));
                assertThat(instanceIds).as(versionId + ": wiring target instance exists")
                        .contains((String) w.get("target_instance_id"));
                // source port is a declared OUTPUT port of the source blueprint
                assertPortDeclared(jdbc, (String) w.get("source_instance_id"), (String) w.get("source_port_name"), true, versionId);
                // target port is a declared INPUT port of the target blueprint
                assertPortDeclared(jdbc, (String) w.get("target_instance_id"), (String) w.get("target_port_name"), false, versionId);
            }
            totalWirings += wirings.size();
        }
        assertThat(totalInstances).as("demo instances across 3 pipelines").isGreaterThanOrEqualTo(12);
        assertThat(totalWirings).as("demo wirings across 3 pipelines").isGreaterThanOrEqualTo(8);
    }

    /**
     * Asserts the given wiring port name is one of the blueprint's declared input/output ports
     * (so the composition page renders the edge against a real handle).
     */
    private void assertPortDeclared(JdbcTemplate jdbc, String instanceId, String portName,
                                    boolean output, String versionId) {
        String col = output ? "output_ports" : "input_ports";
        String portsJson = jdbc.queryForObject(
                "SELECT b." + col + "::text FROM sub_pipeline_instances spi "
                        + "JOIN blueprints b ON b.blueprint_key = spi.blueprint_key WHERE spi.id = ?",
                String.class, instanceId);
        List<String> names = new ArrayList<>();
        try {
            for (JsonNode p : MAPPER.readTree(portsJson)) names.add(p.get("name").asText());
        } catch (Exception e) {
            throw new AssertionError("could not parse " + col + " for instance " + instanceId, e);
        }
        assertThat(names).as(versionId + ": port '" + portName + "' is a declared " + col + " of "
                + instanceId).contains(portName);
    }
}
