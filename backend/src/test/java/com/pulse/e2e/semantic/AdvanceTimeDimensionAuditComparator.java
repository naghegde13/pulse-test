package com.pulse.e2e.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class AdvanceTimeDimensionAuditComparator {

    private final ObjectMapper objectMapper;

    public AdvanceTimeDimensionAuditComparator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ValidationResult validate(ComparatorRequest request) throws IOException {
        List<String> failureCodes = new ArrayList<>();
        JsonNode manifest = read(request.manifest());
        JsonNode stateBefore = read(request.stateBefore());
        JsonNode expectedStateAfter = read(request.expectedStateAfter());
        JsonNode actualStateAfter = read(request.actualStateAfter());
        JsonNode expectedAuditLog = read(request.expectedAuditLog());
        JsonNode actualAuditLog = read(request.actualAuditLog());
        JsonNode expectedDownstreamWindow = read(request.expectedDownstreamWindow());
        JsonNode actualDownstreamWindow = read(request.actualDownstreamWindow());
        JsonNode composerTaskState = read(request.composerTaskState());

        String advanceTargetMode = manifest.path("advanceTargetMode").asText();
        String advanceAttemptKind = manifest.path("advanceAttemptKind").asText("accepted_advance");
        if (!"dataset_advance".equals(advanceTargetMode)) {
            failureCodes.add("BLOCKED_PARAM_CONTRACT");
        }
        if (manifest.path("datasetName").asText().isBlank()) {
            failureCodes.add("DATASET_NAME_MISSING");
        }
        if (manifest.has("advance_domain") && !manifest.path("advance_domain").isBoolean()) {
            failureCodes.add("ADVANCE_DOMAIN_NOT_BOOLEAN");
        }

        compareExact("STATE_AFTER_MISMATCH", expectedStateAfter, actualStateAfter, failureCodes);
        compareExact("AUDIT_LOG_MISMATCH", normalizeAuditTimestamps(expectedAuditLog), normalizeAuditTimestamps(actualAuditLog),
                failureCodes);
        compareExact("DOWNSTREAM_WINDOW_MISMATCH", expectedDownstreamWindow, actualDownstreamWindow, failureCodes);
        validateComposerTaskState(composerTaskState, failureCodes);
        validateRequiredAuditFields(expectedAuditLog, failureCodes);

        switch (advanceAttemptKind) {
            case "accepted_advance" -> validateAcceptedAdvance(stateBefore, actualStateAfter, actualAuditLog, failureCodes);
            case "rejected_backward" -> validateRejectedAdvance(stateBefore, actualStateAfter, actualAuditLog,
                    actualDownstreamWindow, "BACKWARD_ADVANCE_ACCEPTED", failureCodes);
            case "duplicate_idempotent" -> validateRejectedAdvance(stateBefore, actualStateAfter, actualAuditLog,
                    actualDownstreamWindow, "DUPLICATE_ADVANCE_NOT_IDEMPOTENT", failureCodes);
            default -> failureCodes.add("UNKNOWN_ADVANCE_ATTEMPT_KIND");
        }

        String verdict = failureCodes.isEmpty() ? "PASS" : "FAIL";
        Files.createDirectories(request.evidenceRoot());
        Path comparison = request.evidenceRoot().resolve("advance-time-dimension-audit-comparison.json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(comparison.toFile(), Map.of(
                "blueprintKey", "AdvanceTimeDimension",
                "scenarioId", manifest.path("scenarioId").asText("advance-time-dimension-dataset-golden"),
                "advanceTargetMode", advanceTargetMode,
                "advanceAttemptKind", advanceAttemptKind,
                "verdict", verdict,
                "failureCodes", List.copyOf(failureCodes)
        ));
        return new ValidationResult(verdict, List.copyOf(failureCodes), comparison);
    }

    private JsonNode read(Path path) throws IOException {
        return objectMapper.readTree(path.toFile());
    }

    private void compareExact(String failureCode, JsonNode expected, JsonNode actual, List<String> failureCodes) {
        if (!Objects.equals(expected, actual)) {
            failureCodes.add(failureCode);
        }
    }

    private void validateComposerTaskState(JsonNode composerTaskState, List<String> failureCodes) {
        String state = composerTaskState.path("state").asText();
        if (!"success".equalsIgnoreCase(state)) {
            failureCodes.add("COMPOSER_TASK_NOT_SUCCESS");
        }
    }

    private void validateRequiredAuditFields(JsonNode auditLog, List<String> failureCodes) {
        JsonNode events = auditLog.path("events");
        if (!events.isArray() || events.isEmpty()) {
            failureCodes.add("AUDIT_LOG_EMPTY");
            return;
        }
        for (JsonNode event : events) {
            requireText(event, "previousPeriod", "AUDIT_PREVIOUS_PERIOD_MISSING", failureCodes);
            requireText(event, "newPeriod", "AUDIT_NEW_PERIOD_MISSING", failureCodes);
            requireText(event, "actor", "AUDIT_ACTOR_MISSING", failureCodes);
            requireText(event, "runId", "AUDIT_RUN_ID_MISSING", failureCodes);
            requireText(event, "timestamp", "AUDIT_TIMESTAMP_MISSING", failureCodes);
            requireText(event, "status", "AUDIT_STATUS_MISSING", failureCodes);
            if (!event.path("timestamp").asText().isBlank()) {
                try {
                    OffsetDateTime.parse(event.path("timestamp").asText());
                } catch (RuntimeException e) {
                    failureCodes.add("AUDIT_TIMESTAMP_INVALID");
                }
            }
        }
    }

    private JsonNode normalizeAuditTimestamps(JsonNode auditLog) {
        JsonNode copy = auditLog.deepCopy();
        JsonNode events = copy.path("events");
        if (events.isArray()) {
            for (JsonNode event : events) {
                if (event instanceof com.fasterxml.jackson.databind.node.ObjectNode objectNode
                        && !objectNode.path("timestamp").asText().isBlank()) {
                    objectNode.put("timestamp", "<valid-runtime-timestamp>");
                }
            }
        }
        return copy;
    }

    private void validateAcceptedAdvance(JsonNode stateBefore,
                                         JsonNode actualStateAfter,
                                         JsonNode auditLog,
                                         List<String> failureCodes) {
        LocalDate before = localDate(stateBefore.path("currentAsof").asText(null), "STATE_BEFORE_ASOF_INVALID", failureCodes);
        LocalDate after = localDate(actualStateAfter.path("currentAsof").asText(null), "STATE_AFTER_ASOF_INVALID", failureCodes);
        if (before != null && after != null && !after.isAfter(before)) {
            failureCodes.add("PROCESSING_PERIOD_NOT_ADVANCED");
        }
        long acceptedEvents = countEventsWithStatus(auditLog, "ACCEPTED");
        if (acceptedEvents != 1) {
            failureCodes.add("PROCESSING_PERIOD_NOT_ADVANCED_EXACTLY_ONCE");
        }
    }

    private void validateRejectedAdvance(JsonNode stateBefore,
                                         JsonNode actualStateAfter,
                                         JsonNode auditLog,
                                         JsonNode downstreamWindow,
                                         String acceptedFailureCode,
                                         List<String> failureCodes) {
        if (!Objects.equals(stateBefore, actualStateAfter)) {
            failureCodes.add("REJECTED_ADVANCE_MUTATED_STATE");
        }
        if (countEventsWithStatus(auditLog, "ACCEPTED") > 0) {
            failureCodes.add(acceptedFailureCode);
        }
        JsonNode selectedRows = downstreamWindow.path("selectedRows");
        if (selectedRows.isArray() && !selectedRows.isEmpty()) {
            failureCodes.add("REJECTED_ADVANCE_SELECTED_DOWNSTREAM_ROWS");
        }
    }

    private long countEventsWithStatus(JsonNode auditLog, String status) {
        long count = 0;
        for (JsonNode event : auditLog.path("events")) {
            if (status.equalsIgnoreCase(event.path("status").asText())) {
                count++;
            }
        }
        return count;
    }

    private LocalDate localDate(String value, String failureCode, List<String> failureCodes) {
        try {
            return value == null ? null : LocalDate.parse(value);
        } catch (RuntimeException e) {
            failureCodes.add(failureCode);
            return null;
        }
    }

    private void requireText(JsonNode node, String field, String failureCode, List<String> failureCodes) {
        if (node.path(field).asText().isBlank()) {
            failureCodes.add(failureCode);
        }
    }

    public record ComparatorRequest(
            Path manifest,
            Path stateBefore,
            Path expectedStateAfter,
            Path actualStateAfter,
            Path expectedAuditLog,
            Path actualAuditLog,
            Path expectedDownstreamWindow,
            Path actualDownstreamWindow,
            Path composerTaskState,
            Path evidenceRoot
    ) {
    }

    public record ValidationResult(
            String verdict,
            List<String> failureCodes,
            Path comparisonPath
    ) {
    }
}
