package com.pulse.pipeline.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that PKT-FINAL-2 / BUG-2026-05-25-02 invariants hold across the
 * Flyway migration set:
 *
 * <ol>
 *   <li>The V5 seed file contains no INSERT into {@code pipeline_versions}
 *       with a removed {@code lifecycle_stage} value
 *       ({@code INTEGRATION_QUALIFIED}, {@code UAT_DEPLOYED}, or
 *       {@code PRODUCTION}). A fresh DB therefore boots into a state
 *       compatible with the V144 CHECK constraint without first relying on
 *       the V144 backfill.</li>
 *   <li>The V144 migration exists and contains both the backfill UPDATE
 *       (collapsing removed stages → {@code PUBLISHED}) and the CHECK
 *       constraint preventing future inserts of removed values.</li>
 * </ol>
 *
 * Flyway is disabled in the test profile (H2 in-memory + JPA create-drop),
 * so this test verifies migration content directly rather than running it.
 */
class PipelineStageTruncationMigrationTest {

    private static final Path MIGRATIONS = Paths.get(
            "src", "main", "resources", "db", "migration");

    private static final List<String> REMOVED_STAGES = List.of(
            "INTEGRATION_QUALIFIED", "UAT_DEPLOYED", "PRODUCTION");

    /**
     * Locates lifecycle_stage values in INSERT INTO pipeline_versions
     * statements within the V5 seed. We tolerate any column order by
     * grabbing the value at the 4th position of the VALUES tuple — that
     * matches the V5 seed schema
     * {@code (id, pipeline_id, version, lifecycle_stage, ...)}.
     */
    private static final Pattern PIPELINE_VERSIONS_STAGE_VALUE = Pattern.compile(
            "\\(\\s*'[^']*'\\s*,\\s*'[^']*'\\s*,\\s*'[^']*'\\s*,\\s*'([A-Z_]+)'",
            Pattern.MULTILINE);

    @Test
    void v5SeedHasNoRemovedLifecycleStages() throws IOException {
        Path v5 = MIGRATIONS.resolve("V5__seed_test_data.sql");
        assertTrue(Files.exists(v5), "V5 seed file must exist at " + v5);

        String contents = Files.readString(v5, StandardCharsets.UTF_8);

        // Walk every pipeline_versions INSERT and assert the stage value.
        // We only consider VALUES tuples that appear inside a pipeline_versions
        // INSERT block, so other INSERTs (e.g. sub_pipeline_instances) don't
        // create false positives.
        String[] blocks = contents.split("(?i)INSERT\\s+INTO\\s+");
        for (String block : blocks) {
            if (!block.regionMatches(true, 0, "pipeline_versions", 0, "pipeline_versions".length())) {
                continue;
            }
            Matcher m = PIPELINE_VERSIONS_STAGE_VALUE.matcher(block);
            while (m.find()) {
                String stage = m.group(1);
                assertFalse(
                        REMOVED_STAGES.contains(stage),
                        "V5 seed still contains removed lifecycle_stage value '"
                                + stage + "'. PKT-FINAL-2 requires the seed "
                                + "to use only PULSE-observable stages "
                                + "(DRAFT, ENGINEERING, DEV_DEPLOYED, "
                                + "DEV_VALIDATED, PUBLISHED).");
            }
        }
    }

    @Test
    void v144MigrationBackfillsAndConstrainsLifecycleStage() throws IOException {
        Path v144 = MIGRATIONS.resolve("V144__truncate_pipeline_stage_post_handoff.sql");
        assertTrue(Files.exists(v144),
                "V144 migration is required by PKT-FINAL-2 but was not found at " + v144);

        String contents = Files.readString(v144, StandardCharsets.UTF_8);

        // Backfill collapses removed stages into PUBLISHED.
        assertTrue(
                contents.contains("UPDATE pipeline_versions")
                        && contents.contains("SET lifecycle_stage = 'PUBLISHED'")
                        && contents.contains("'INTEGRATION_QUALIFIED'")
                        && contents.contains("'UAT_DEPLOYED'")
                        && contents.contains("'PRODUCTION'"),
                "V144 must contain the backfill UPDATE collapsing "
                        + "INTEGRATION_QUALIFIED, UAT_DEPLOYED, PRODUCTION → PUBLISHED.");

        // Hard-locks future writes to PULSE-observable stages.
        assertTrue(
                contents.contains("ADD CONSTRAINT")
                        && contents.contains("chk_lifecycle_stage_pulse_observable")
                        && contents.contains("CHECK")
                        && contents.contains("'PUBLISHED'")
                        && contents.contains("'ENGINEERING'")
                        && contents.contains("'DEV_DEPLOYED'")
                        && contents.contains("'DEV_VALIDATED'"),
                "V144 must add a CHECK constraint restricting lifecycle_stage "
                        + "to PULSE-observable values.");

        // Negative: removed stages must not appear inside the CHECK list.
        // Find the CHECK clause and assert removed stages are not enumerated as allowed.
        Matcher checkClause = Pattern.compile("CHECK\\s*\\([^)]*\\)", Pattern.DOTALL)
                .matcher(contents);
        assertTrue(checkClause.find(), "V144 must contain a CHECK(...) clause.");
        String checkBody = checkClause.group();
        for (String removed : REMOVED_STAGES) {
            assertFalse(
                    checkBody.contains("'" + removed + "'"),
                    "V144 CHECK constraint must not allow removed stage '" + removed + "'.");
        }
    }
}
