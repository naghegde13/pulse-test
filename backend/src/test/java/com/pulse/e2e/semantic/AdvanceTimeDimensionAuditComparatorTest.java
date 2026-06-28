package com.pulse.e2e.semantic;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdvanceTimeDimensionAuditComparatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @TempDir Path tempDir;

    @Test
    void passesDatasetAdvanceBeforeAfterStateAuditAndDownstreamWindow() throws Exception {
        Path fixture = copyFixture("dataset-advance");

        var result = comparator().validate(request(fixture));

        assertEquals("PASS", result.verdict());
        assertEquals(0, result.failureCodes().size());
        assertTrue(Files.exists(result.comparisonPath()));
        assertEquals("PASS", objectMapper.readTree(result.comparisonPath().toFile()).get("verdict").asText());
    }

    @Test
    void passesInvalidBackwardAdvanceRejectionWithoutStateOrWindowMutation() throws Exception {
        Path fixture = copyFixture("backward-rejection");

        var result = comparator().validate(request(fixture));

        assertEquals("PASS", result.verdict());
        assertEquals(0, result.failureCodes().size());
    }

    @Test
    void passesMonthYearAndDstBoundaryAdvance() throws Exception {
        Path fixture = copyFixture("month-year-dst-boundary");

        var result = comparator().validate(request(fixture));

        assertEquals("PASS", result.verdict());
        assertEquals(0, result.failureCodes().size());
    }

    @Test
    void failsClosedForDomainAdvanceUntilParameterContractIsFixed() throws Exception {
        Path fixture = copyFixture("dataset-advance");
        writeJson(fixture.resolve("manifest.json"), Map.of(
                "scenarioId", "advance-time-dimension-domain-blocked",
                "advanceTargetMode", "domain_advance",
                "advanceAttemptKind", "accepted_advance",
                "domainId", "servicing",
                "advance_domain", "servicing"
        ));

        var result = comparator().validate(request(fixture));

        assertEquals("FAIL", result.verdict());
        assertTrue(result.failureCodes().contains("BLOCKED_PARAM_CONTRACT"));
        assertTrue(result.failureCodes().contains("ADVANCE_DOMAIN_NOT_BOOLEAN"));
    }

    private AdvanceTimeDimensionAuditComparator comparator() {
        return new AdvanceTimeDimensionAuditComparator(objectMapper);
    }

    private AdvanceTimeDimensionAuditComparator.ComparatorRequest request(Path fixture) {
        return new AdvanceTimeDimensionAuditComparator.ComparatorRequest(
                fixture.resolve("manifest.json"),
                fixture.resolve("calendar_state_before.json"),
                fixture.resolve("calendar_state_after_expected.json"),
                fixture.resolve("calendar_state_after_actual.json"),
                fixture.resolve("calendar_audit_log_expected.json"),
                fixture.resolve("calendar_audit_log_actual.json"),
                fixture.resolve("downstream_window_expected.json"),
                fixture.resolve("downstream_window_actual.json"),
                fixture.resolve("composer_task_state.json"),
                tempDir.resolve("evidence").resolve(fixture.getFileName().toString())
        );
    }

    private Path copyFixture(String scenario) throws Exception {
        Path source = Path.of("src/test/resources/e2e/fixtures/blueprint_semantic/AdvanceTimeDimension")
                .resolve(scenario);
        Path target = tempDir.resolve(scenario);
        Files.createDirectories(target);
        try (var stream = Files.list(source)) {
            for (Path file : stream.toList()) {
                Files.copy(file, target.resolve(file.getFileName()));
            }
        }
        return target;
    }

    private void writeJson(Path path, Map<String, Object> payload) throws Exception {
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), payload);
    }
}
