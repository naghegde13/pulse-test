package com.pulse.e2e.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class SnapshotHistoryComparator {

    private final ObjectMapper objectMapper;

    public SnapshotHistoryComparator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ValidationResult validate(ComparatorRequest request) throws IOException {
        List<String> failureCodes = new ArrayList<>();
        JsonNode manifest = read(request.manifest());
        JsonNode expectedHistory = read(request.expectedHistory());
        JsonNode actualHistory = read(request.actualHistory());
        JsonNode keyRejectionVerdict = read(request.keyRejectionVerdict());

        String businessKey = text(manifest, "businessKey", "subscription_id");
        String validFrom = text(manifest, "validFromColumn", "valid_from");
        String validTo = text(manifest, "validToColumn", "valid_to");
        String effectiveTs = text(manifest, "sourceEffectiveTsColumn", "business_effective_ts");
        String currentRule = text(manifest, "currentRule", "current_flag_and_open_end");
        List<String> sortKeys = stringList(manifest.path("sortKeys"));
        if (sortKeys.isEmpty()) {
            sortKeys = List.of(businessKey, validFrom);
        }

        List<JsonNode> expectedRows = sortedRows(expectedHistory, sortKeys);
        List<JsonNode> actualRows = sortedRows(actualHistory, sortKeys);
        if (!Objects.equals(expectedRows, actualRows)) {
            failureCodes.add("SNAPSHOT_HISTORY_ROWS_MISMATCH");
        }

        validateHistoryWindows(actualRows, businessKey, validFrom, validTo, effectiveTs, failureCodes);
        validateCurrentRows(actualRows, businessKey, validTo, currentRule, failureCodes);
        validateRequiredFields(actualRows, manifest.path("requiredFields"), failureCodes);
        validateKeyRejections(keyRejectionVerdict, failureCodes);

        String verdict = failureCodes.isEmpty() ? "PASS" : "FAIL";
        Files.createDirectories(request.evidenceRoot());
        Path comparison = request.evidenceRoot().resolve("snapshot-history-oracle-comparison.json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(comparison.toFile(), Map.of(
                "blueprintKey", "SnapshotModel",
                "scenarioId", text(manifest, "scenarioId", "snapshot-model-history-golden"),
                "businessKey", businessKey,
                "validFromColumn", validFrom,
                "validToColumn", validTo,
                "currentRule", currentRule,
                "expectedRowCount", expectedRows.size(),
                "actualRowCount", actualRows.size(),
                "verdict", verdict,
                "failureCodes", List.copyOf(failureCodes)
        ));
        return new ValidationResult(verdict, List.copyOf(failureCodes), comparison);
    }

    private JsonNode read(Path path) throws IOException {
        return objectMapper.readTree(path.toFile());
    }

    private List<JsonNode> sortedRows(JsonNode payload, List<String> sortKeys) {
        JsonNode rows = payload.isArray() ? payload : payload.path("rows");
        return StreamSupport.stream(rows.spliterator(), false)
                .sorted(Comparator.comparing(row -> sortKey(row, sortKeys)))
                .toList();
    }

    private String sortKey(JsonNode row, List<String> sortKeys) {
        return sortKeys.stream()
                .map(key -> row.path(key).isNull() ? "" : row.path(key).asText(""))
                .collect(Collectors.joining("\u0001"));
    }

    private void validateHistoryWindows(List<JsonNode> rows,
                                        String businessKey,
                                        String validFrom,
                                        String validTo,
                                        String effectiveTs,
                                        List<String> failureCodes) {
        Map<String, List<JsonNode>> byKey = rows.stream()
                .collect(Collectors.groupingBy(
                        row -> row.path(businessKey).asText(""),
                        LinkedHashMap::new,
                        Collectors.toList()));
        for (Map.Entry<String, List<JsonNode>> entry : byKey.entrySet()) {
            if (entry.getKey().isBlank()) {
                failureCodes.add("BUSINESS_KEY_MISSING");
                continue;
            }
            List<JsonNode> windows = entry.getValue().stream()
                    .sorted(Comparator.comparing(row -> instant(row.path(validFrom).asText(null), failureCodes, "VALID_FROM_INVALID")))
                    .toList();
            Instant previousValidTo = null;
            Instant previousEffectiveTs = null;
            for (int i = 0; i < windows.size(); i++) {
                JsonNode row = windows.get(i);
                Instant rowValidFrom = instant(row.path(validFrom).asText(null), failureCodes, "VALID_FROM_INVALID");
                Instant rowValidTo = nullableInstant(row.path(validTo), failureCodes, "VALID_TO_INVALID");
                Instant rowEffectiveTs = instant(row.path(effectiveTs).asText(null), failureCodes, "EFFECTIVE_TS_INVALID");
                if (rowValidTo != null && !rowValidTo.isAfter(rowValidFrom)) {
                    failureCodes.add("SNAPSHOT_WINDOW_NOT_FORWARD");
                }
                if (previousValidTo != null && rowValidFrom.isBefore(previousValidTo)) {
                    failureCodes.add("SNAPSHOT_WINDOWS_OVERLAP");
                }
                if (previousValidTo == null && i > 0) {
                    failureCodes.add("OPEN_WINDOW_BEFORE_FINAL_ROW");
                }
                if (previousEffectiveTs != null && rowEffectiveTs.isBefore(previousEffectiveTs)) {
                    failureCodes.add("EFFECTIVE_TS_NOT_ORDERED");
                }
                previousValidTo = rowValidTo;
                previousEffectiveTs = rowEffectiveTs;
            }
        }
    }

    private void validateCurrentRows(List<JsonNode> rows,
                                     String businessKey,
                                     String validTo,
                                     String currentRule,
                                     List<String> failureCodes) {
        Map<String, Long> currentCounts = rows.stream()
                .collect(Collectors.groupingBy(
                        row -> row.path(businessKey).asText(""),
                        LinkedHashMap::new,
                        Collectors.filtering(row -> isCurrent(row, validTo, currentRule), Collectors.counting())));
        for (Map.Entry<String, Long> entry : currentCounts.entrySet()) {
            if (!entry.getKey().isBlank() && entry.getValue() != 1) {
                failureCodes.add("CURRENT_ROW_COUNT_NOT_ONE");
            }
        }
    }

    private boolean isCurrent(JsonNode row, String validTo, String currentRule) {
        boolean currentFlag = row.path("current_flag").asBoolean(false);
        boolean openEnd = row.path(validTo).isMissingNode()
                || row.path(validTo).isNull()
                || row.path(validTo).asText("").isBlank();
        return switch (currentRule) {
            case "current_flag_only" -> currentFlag;
            case "open_end_only" -> openEnd;
            default -> currentFlag && openEnd;
        };
    }

    private void validateRequiredFields(List<JsonNode> rows, JsonNode requiredFields, List<String> failureCodes) {
        for (String field : stringList(requiredFields)) {
            for (JsonNode row : rows) {
                if (row.path(field).isMissingNode() || row.path(field).isNull() || row.path(field).asText("").isBlank()) {
                    failureCodes.add("REQUIRED_FIELD_MISSING:" + field);
                    return;
                }
            }
        }
    }

    private void validateKeyRejections(JsonNode verdict, List<String> failureCodes) {
        JsonNode cases = verdict.path("cases");
        if (!cases.isArray() || cases.isEmpty()) {
            failureCodes.add("SNAPSHOT_KEY_REJECTION_VERDICT_MISSING");
            return;
        }
        Map<String, Boolean> acceptedByType = new LinkedHashMap<>();
        for (JsonNode keyCase : cases) {
            acceptedByType.put(keyCase.path("type").asText(""), keyCase.path("accepted").asBoolean(true));
        }
        if (!Boolean.FALSE.equals(acceptedByType.get("missing_key"))) {
            failureCodes.add("MISSING_KEY_ACCEPTED");
        }
        if (!Boolean.FALSE.equals(acceptedByType.get("duplicate_key"))) {
            failureCodes.add("DUPLICATE_KEY_ACCEPTED");
        }
    }

    private Instant instant(String value, List<String> failureCodes, String failureCode) {
        try {
            return Instant.parse(value);
        } catch (RuntimeException e) {
            failureCodes.add(failureCode);
            return Instant.EPOCH;
        }
    }

    private Instant nullableInstant(JsonNode node, List<String> failureCodes, String failureCode) {
        if (node.isMissingNode() || node.isNull() || node.asText("").isBlank()) {
            return null;
        }
        return instant(node.asText(), failureCodes, failureCode);
    }

    private String text(JsonNode node, String field, String fallback) {
        String value = node.path(field).asText("");
        return value.isBlank() ? fallback : value;
    }

    private List<String> stringList(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        return StreamSupport.stream(node.spliterator(), false)
                .map(JsonNode::asText)
                .filter(value -> !value.isBlank())
                .toList();
    }

    public record ComparatorRequest(
            Path manifest,
            Path expectedHistory,
            Path actualHistory,
            Path keyRejectionVerdict,
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
