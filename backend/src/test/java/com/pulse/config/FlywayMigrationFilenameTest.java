package com.pulse.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayMigrationFilenameTest {

    private static final Pattern VERSIONED_MIGRATION =
            Pattern.compile("^V([0-9]+(?:\\.[0-9]+)*)__.+\\.sql$");

    @Test
    void versionedMigrationsHaveUniqueVersions() throws IOException {
        Path migrationDir = Path.of("src/main/resources/db/migration");

        Map<String, List<String>> filesByVersion;
        try (var stream = Files.list(migrationDir)) {
            filesByVersion = stream
                    .map(path -> path.getFileName().toString())
                    .map(name -> Map.entry(versionOf(name), name))
                    .filter(entry -> entry.getKey() != null)
                    .collect(Collectors.groupingBy(
                            Map.Entry::getKey,
                            Collectors.mapping(Map.Entry::getValue, Collectors.toList())
                    ));
        }

        Map<String, List<String>> duplicateVersions = filesByVersion.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        assertThat(duplicateVersions)
                .as("Each Flyway V*.sql version must be unique; duplicate versions crash Cloud Run at startup")
                .isEmpty();
    }

    private static String versionOf(String fileName) {
        Matcher matcher = VERSIONED_MIGRATION.matcher(fileName);
        return matcher.matches() ? matcher.group(1) : null;
    }
}
