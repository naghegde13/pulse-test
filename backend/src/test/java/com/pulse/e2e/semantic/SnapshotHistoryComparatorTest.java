package com.pulse.e2e.semantic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotHistoryComparatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @TempDir Path tempDir;

    @Test
    void passesExactMultiRunHistoryRowsLateWindowSplitAndKeyRejections() throws Exception {
        Path fixture = copyFixture();

        var result = comparator().validate(request(fixture));

        assertEquals("PASS", result.verdict());
        assertEquals(0, result.failureCodes().size());
        assertTrue(Files.exists(result.comparisonPath()));
        assertEquals("PASS", objectMapper.readTree(result.comparisonPath().toFile()).get("verdict").asText());
    }

    @Test
    void failsWhenLateCorrectionDoesNotSplitHistoryWindow() throws Exception {
        Path fixture = copyFixture();
        ObjectNode actual = (ObjectNode) objectMapper.readTree(fixture.resolve("actual-history.json").toFile());
        ((ObjectNode) actual.path("rows").get(0)).put("valid_to", "2026-02-01T00:00:00Z");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(fixture.resolve("actual-history.json").toFile(), actual);

        var result = comparator().validate(request(fixture));

        assertEquals("FAIL", result.verdict());
        assertTrue(result.failureCodes().contains("SNAPSHOT_HISTORY_ROWS_MISMATCH"));
        assertTrue(result.failureCodes().contains("SNAPSHOT_WINDOWS_OVERLAP"));
    }

    @Test
    void failsWhenMoreThanOneCurrentRowExistsForAKey() throws Exception {
        Path fixture = copyFixture();
        ObjectNode actual = (ObjectNode) objectMapper.readTree(fixture.resolve("actual-history.json").toFile());
        ((ObjectNode) actual.path("rows").get(1)).putNull("valid_to");
        ((ObjectNode) actual.path("rows").get(1)).put("current_flag", true);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(fixture.resolve("actual-history.json").toFile(), actual);

        var result = comparator().validate(request(fixture));

        assertEquals("FAIL", result.verdict());
        assertTrue(result.failureCodes().contains("CURRENT_ROW_COUNT_NOT_ONE"));
    }

    @Test
    void failsWhenMissingOrDuplicateBusinessKeyCasesAreAccepted() throws Exception {
        Path fixture = copyFixture();
        ObjectNode verdict = (ObjectNode) objectMapper.readTree(fixture.resolve("snapshot-key-rejection-verdict.json").toFile());
        ((ObjectNode) verdict.path("cases").get(0)).put("accepted", true);
        ((ObjectNode) verdict.path("cases").get(1)).put("accepted", true);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(fixture.resolve("snapshot-key-rejection-verdict.json").toFile(), verdict);

        var result = comparator().validate(request(fixture));

        assertEquals("FAIL", result.verdict());
        assertTrue(result.failureCodes().contains("MISSING_KEY_ACCEPTED"));
        assertTrue(result.failureCodes().contains("DUPLICATE_KEY_ACCEPTED"));
    }

    private SnapshotHistoryComparator comparator() {
        return new SnapshotHistoryComparator(objectMapper);
    }

    private SnapshotHistoryComparator.ComparatorRequest request(Path fixture) {
        return new SnapshotHistoryComparator.ComparatorRequest(
                fixture.resolve("manifest.json"),
                fixture.resolve("expected-history.json"),
                fixture.resolve("actual-history.json"),
                fixture.resolve("snapshot-key-rejection-verdict.json"),
                tempDir.resolve("evidence")
        );
    }

    private Path copyFixture() throws Exception {
        Path source = Path.of("src/test/resources/e2e/fixtures/blueprint_semantic/SnapshotModel/history");
        Path target = tempDir.resolve("history");
        Files.createDirectories(target);
        try (var stream = Files.list(source)) {
            for (Path file : stream.toList()) {
                Files.copy(file, target.resolve(file.getFileName()));
            }
        }
        return target;
    }
}
