package com.pulse.schema;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drift detector: every JPA entity field annotated
 * {@code @Column(columnDefinition = "jsonb")} must resolve to
 * {@code data_type = 'jsonb'} in the migrated Postgres schema.
 *
 * <p>Background: PKT-FINAL-1 / BUG-2026-05-25-01. 9 entity columns were
 * declared {@code "jsonb"} in JPA but created as {@code TEXT} in older Flyway
 * migrations. With {@code spring.jpa.hibernate.ddl-auto=validate} the backend
 * cannot boot on a fresh Postgres until each one is altered to {@code jsonb}.
 * V143 migrates the 9 known offenders; this IT prevents future entity vs
 * migration drift from sneaking back in.
 *
 * <p>How it works:
 * <ol>
 *   <li>Boots a Testcontainers Postgres 16 (Spring Boot's
 *       {@code @DynamicPropertySource} redirects the datasource onto it).
 *       Using a dedicated container keeps the assertion independent of any
 *       shared dev DB that may already carry hand-applied ALTERs (the
 *       rehearsal-session bypass for the same bug).</li>
 *   <li>Activates the {@code postgres-it} profile so Flyway runs every
 *       checked-in migration including V143.</li>
 *   <li>Scans the classpath for {@code @Entity}-annotated classes under
 *       {@code com.pulse} and collects every field annotated
 *       {@code @Column(columnDefinition = "jsonb")} (case-insensitive).</li>
 *   <li>For each collected (table, column) pair, queries
 *       {@code information_schema.columns} and asserts
 *       {@code data_type = 'jsonb'}. Any drift fails the test with the full
 *       offending list so the operator can write a follow-up forward
 *       migration in one go.</li>
 * </ol>
 *
 * <p>Tagged {@code integration} so it runs on the
 * {@code backendIntegrationTest} lane and is excluded from the fast PR lane.
 * Requires a working Docker daemon (CI runners + macOS dev hosts both have
 * one; the test is skipped explicitly nowhere -- if Docker is missing the
 * Testcontainers startup will fail fast with a clear message).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("postgres-it")
@Testcontainers
@Tag("integration")
class JsonbColumnDriftIT {

    /**
     * Pinned to the same Postgres version docker-compose runs locally (16-alpine)
     * so the Flyway scripts apply with identical syntax/extension support.
     */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("pulse_jsonb_drift")
            .withUsername("pulse")
            .withPassword("pulse");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        // Make sure Flyway picks up our migrations and applies them top-to-bottom
        // on the freshly-created container. The postgres-it profile already
        // enables Flyway, but be explicit so the container schema is fully built
        // before the assertion runs.
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
    }

    @Autowired
    private DataSource dataSource;

    @Test
    void everyEntityDeclaredJsonbColumnResolvesToJsonbInPostgres() {
        List<JsonbColumnRef> declared = collectJsonbColumnRefs();

        assertThat(declared)
                .as("Scanner found no entity-declared jsonb columns under com.pulse -- "
                        + "classpath scan or annotation matching is broken")
                .isNotEmpty();

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // For diagnostic value when something fails: collect ALL drift, don't
        // bail on the first one. The implementer of the next forward migration
        // can then patch every offender in a single PR.
        Map<String, String> drift = new TreeMap<>();

        for (JsonbColumnRef ref : declared) {
            List<String> dataTypes = jdbc.queryForList(
                    "SELECT data_type "
                            + "FROM information_schema.columns "
                            + "WHERE table_schema = current_schema() "
                            + "  AND table_name = ? "
                            + "  AND column_name = ?",
                    String.class,
                    ref.table(), ref.column());

            if (dataTypes.isEmpty()) {
                drift.put(ref.qualified(),
                        "MISSING: entity " + ref.entityClass().getName() + "#" + ref.fieldName()
                                + " maps to " + ref.qualified() + " but no such column exists in the migrated schema");
                continue;
            }

            String actual = dataTypes.get(0);
            if (!"jsonb".equalsIgnoreCase(actual)) {
                drift.put(ref.qualified(),
                        "DRIFT: " + ref.entityClass().getName() + "#" + ref.fieldName()
                                + " declares columnDefinition=\"jsonb\" but column type is \"" + actual + "\" -- "
                                + "add an ALTER TABLE ... TYPE jsonb USING ... migration");
            }
        }

        assertThat(drift)
                .as("Entity vs migration jsonb drift detected. Each entry below is a "
                        + "column that needs a forward Flyway migration ALTERing it to jsonb. "
                        + "See PKT-FINAL-1 / V143 for the pattern.")
                .isEmpty();
    }

    /**
     * Scans the classpath for {@code @Entity} classes under {@code com.pulse}
     * and returns a {@link JsonbColumnRef} for every field annotated
     * {@code @Column(columnDefinition = "jsonb")} (case-insensitive on the
     * column-definition value).
     */
    private List<JsonbColumnRef> collectJsonbColumnRefs() {
        // includeFilters need to be set; useDefaultFilters=false so we don't
        // pick up @Component/@Service/etc. that happen to live under com.pulse.
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        scanner.setResourceLoader(new PathMatchingResourcePatternResolver(getClass().getClassLoader()));

        List<JsonbColumnRef> refs = new ArrayList<>();
        for (BeanDefinition beanDef : scanner.findCandidateComponents("com.pulse")) {
            String className = beanDef.getBeanClassName();
            if (className == null) {
                continue;
            }
            Class<?> entityClass;
            try {
                entityClass = Class.forName(className, false, getClass().getClassLoader());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Scanner produced an unloadable entity class: " + className, e);
            }
            String tableName = resolveTableName(entityClass);
            for (Field field : entityClass.getDeclaredFields()) {
                Column column = field.getAnnotation(Column.class);
                if (column == null) {
                    continue;
                }
                String definition = column.columnDefinition();
                if (definition == null || !definition.trim().toLowerCase(Locale.ROOT).startsWith("jsonb")) {
                    continue;
                }
                String columnName = column.name();
                if (columnName == null || columnName.isBlank()) {
                    // JPA default: when @Column(name=...) is absent, the column
                    // name is the field name. Match Hibernate's default by
                    // applying snake_case-ish conversion only if the field
                    // already looks camelCase. In practice every jsonb field
                    // in this codebase that omits name= uses an all-lowercase
                    // field name that matches the DB column verbatim, so we
                    // skip naming-strategy translation and rely on convention.
                    columnName = field.getName();
                }
                refs.add(new JsonbColumnRef(entityClass, field.getName(), tableName, columnName));
            }
        }
        return refs;
    }

    private static String resolveTableName(Class<?> entityClass) {
        Table table = entityClass.getAnnotation(Table.class);
        if (table != null && !table.name().isBlank()) {
            return table.name();
        }
        Entity entity = entityClass.getAnnotation(Entity.class);
        if (entity != null && !entity.name().isBlank()) {
            return entity.name();
        }
        return entityClass.getSimpleName();
    }

    /**
     * @param entityClass the JPA entity declaring the field
     * @param fieldName   the Java field name (for diagnostic messages only)
     * @param table       the resolved DB table name
     * @param column      the resolved DB column name
     */
    private record JsonbColumnRef(Class<?> entityClass, String fieldName, String table, String column) {
        String qualified() { return table + "." + column; }
    }
}
