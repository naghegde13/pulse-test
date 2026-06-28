package com.pulse.config;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Postgres-backed Flyway migration smoke test.
 *
 * <p>This test is the integration-PR lane's safety net for migration drift. It
 * boots the full Spring application context with the {@code postgres-it}
 * profile (Flyway enabled, Postgres datasource) and asserts:
 *
 * <ol>
 *   <li>The {@link org.springframework.context.ApplicationContext} starts
 *       cleanly — i.e. all autoconfigure beans wire together with real
 *       Flyway/Postgres present, and Hibernate {@code ddl-auto=validate}
 *       finds no JPA/schema mismatches.</li>
 *   <li>Every checked-in {@code V*.sql} migration under
 *       {@code db/migration/} produced a {@code flyway_schema_history} row
 *       with {@code success=true}.</li>
 *   <li>No repeatable migrations exist yet (we assert this explicitly so a
 *       future {@code R__*.sql} addition is a deliberate, visible change).</li>
 * </ol>
 *
 * <p>H2 (used by {@code application-test.yml} on the fast PR lane) hides
 * Postgres-side issues like index name length limits, partial-index syntax,
 * and {@code GIN}/{@code JSONB} operator class drift. This test catches them
 * before merge.
 *
 * <p>Run it locally with:
 * <pre>
 *   ./gradlew test --tests com.pulse.config.PostgresFlywayMigrationSmokeIT \
 *       -Dspring.profiles.active=postgres-it
 * </pre>
 *
 * <p>Tagged {@code integration} so the {@code fastPrTest} Gradle task can
 * exclude it and the {@code backendIntegrationTest} task can include it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("postgres-it")
@Tag("integration")
class PostgresFlywayMigrationSmokeIT {

    @Autowired
    private DataSource dataSource;

    @Test
    void applicationContextStartsAndAllMigrationsAreApplied() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // 1. Discover the migration files that ship in the JAR/classpath.
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] versionedMigrations = resolver.getResources("classpath:db/migration/V*.sql");
        Resource[] repeatableMigrations = resolver.getResources("classpath:db/migration/R*.sql");

        assertThat(versionedMigrations.length)
                .as("There must be at least one versioned migration; classpath resolution may have failed")
                .isGreaterThan(0);

        // 2. Repeatable migrations are not used yet — flag any addition explicitly.
        assertThat(repeatableMigrations)
                .as("No repeatable (R__*.sql) migrations expected yet — adding one is a deliberate change "
                        + "that must update this assertion and the migration smoke documentation.")
                .isEmpty();

        // 3. Every shipped migration must have a successful row in flyway_schema_history.
        Integer historyCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE script LIKE 'V%' AND success = true",
                Integer.class);
        assertThat(historyCount)
                .as("flyway_schema_history must contain a successful row for every checked-in V*.sql migration")
                .isEqualTo(versionedMigrations.length);

        // 4. No migration row may be marked failed.
        Integer failedCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = false",
                Integer.class);
        assertThat(failedCount)
                .as("flyway_schema_history must not contain any failed migration rows")
                .isZero();

        // 5. Sanity-check ordering: highest installed_rank should match the row count.
        Integer maxRank = jdbc.queryForObject(
                "SELECT COALESCE(MAX(installed_rank), 0) FROM flyway_schema_history WHERE success = true",
                Integer.class);
        Integer rowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true",
                Integer.class);
        assertThat(maxRank)
                .as("installed_rank should be contiguous: max rank must equal successful-row count")
                .isEqualTo(rowCount);

        // 6. Reject obvious drift: list of versions in flyway_schema_history must be unique.
        List<Map<String, Object>> versions = jdbc.queryForList(
                "SELECT version, COUNT(*) AS hits FROM flyway_schema_history "
                        + "WHERE script LIKE 'V%' GROUP BY version HAVING COUNT(*) > 1");
        assertThat(versions)
                .as("Each Flyway version must appear at most once in flyway_schema_history")
                .isEmpty();
    }
}
