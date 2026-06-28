package com.pulse.blueprint;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQL-text guard for V155. The fast backend test lane does not replay Flyway
 * migrations, so this checks the catalog-control hint patch directly.
 */
class V155BlueprintParamUiConstructHintsTest {

    private static final Path V155 = Path.of(
            "src/main/resources/db/migration/V155__blueprint_param_ui_construct_hints.sql");

    @Test
    void v155SeedsImplementedConstructFamilies() throws Exception {
        String sql = Files.readString(V155);
        for (String construct : List.of(
                "column-picker",
                "column-picker (multi)",
                "rename-mapper",
                "key-value-mapper",
                "type-cast-mapper",
                "condition-builder",
                "dq-outcome-control",
                "date-mnemonic-picker",
                "expression-builder",
                "simple-sql-builder",
                "sql-chain-editor",
                "cron-builder")) {
            assertTrue(sql.contains("'" + construct + "'"),
                    "V155 must seed ui_construct = " + construct);
        }
    }

    @Test
    void v155CoversSeededRepresentativeBlueprints() throws Exception {
        String sql = Files.readString(V155);
        for (String row : List.of(
                "('BulkBackfill', 'source_query', 'simple-sql-builder'",
                "('BronzeToSilverCleaning', 'rename_map', 'rename-mapper'",
                "('BronzeToSilverCleaning', 'type_coercions', 'type-cast-mapper'",
                "('GenericFilter', 'conditions', 'condition-builder'",
                "('DQValidator', 'on_failure', 'dq-outcome-control'",
                "('ScheduleAndTriggers', 'cron_expression', 'cron-builder'",
                "('SqlModel', 'steps', 'sql-chain-editor'",
                "('SourceSQL', 'source_query', 'simple-sql-builder'")) {
            assertTrue(sql.contains(row), "V155 must include hint row: " + row);
        }
    }

    @Test
    void v155PatchesExistingParamDescriptorsInPlace() throws Exception {
        String sql = Files.readString(V155);
        assertTrue(sql.contains("param.elem || jsonb_build_object('ui_construct', h.ui_construct)"),
                "V155 should append ui_construct to each matching descriptor");
        assertTrue(sql.contains("ORDER BY param.ord"),
                "V155 must preserve params_schema array order");
        assertTrue(sql.contains("h.param_name = param.elem ->> 'name'"),
                "V155 must match params by descriptor name, not by array index");
    }

    @Test
    void v155AddsFilterTypesForTypedColumnPickers() throws Exception {
        String sql = Files.readString(V155);
        for (String row : List.of(
                "('FreshnessChecks', 'timestamp_column', 'column-picker', '[\"timestamp\",\"date\"]'::jsonb)",
                "('JsonFlatten', 'source_columns', 'column-picker (multi)', '[\"struct\",\"json\",\"array\",\"map\"]'::jsonb)",
                "('FeatureTablePublish', 'point_in_time_column', 'column-picker', '[\"timestamp\",\"date\"]'::jsonb)")) {
            assertTrue(sql.contains(row), "V155 must include typed column-picker row: " + row);
        }
    }
}
