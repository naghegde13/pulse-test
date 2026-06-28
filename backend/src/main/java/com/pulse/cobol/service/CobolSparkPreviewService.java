package com.pulse.cobol.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.function.Consumer;
import java.util.concurrent.TimeUnit;

@Service
public class CobolSparkPreviewService {

    private static final Logger log = LoggerFactory.getLogger(CobolSparkPreviewService.class);
    private static final Pattern INTEGER_PATTERN = Pattern.compile("^-?\\d+$");
    private static final Pattern DECIMAL_PATTERN = Pattern.compile("^-?\\d+(\\.\\d+)?$");
    private static final String SPARK_CP = "/app/spark-cp/BOOT-INF/classes:/app/spark-cp/BOOT-INF/lib/*";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Object DAEMON_LOCK = new Object();
    private static Process daemonProcess;
    private static java.io.BufferedWriter daemonStdin;
    private static java.io.BufferedReader daemonStdout;

    private final CobolCopybookAnalyzer analyzer;

    @Autowired
    public CobolSparkPreviewService(CobolCopybookAnalyzer analyzer) {
        this.analyzer = analyzer;
    }

    // Backward-compat for tests pre-dating the CobolFlatteningService extraction.
    // Flattening responsibility moved inside the analyzer; the second arg is unused.
    // NOT annotated with @Autowired so Spring DI never selects this constructor at runtime.
    public CobolSparkPreviewService(CobolCopybookAnalyzer analyzer, CobolFlatteningService unused) {
        this(analyzer);
    }

    public PreviewOutcome execute(
            String copybookContent,
            byte[] dataSample,
            Path dataFile,
            Map<String, Object> optionOverrides,
            int sampleRows) {
        return execute(copybookContent, dataSample, dataFile, optionOverrides, sampleRows, ignored -> {});
    }

    public PreviewOutcome execute(
            String copybookContent,
            byte[] dataSample,
            Path dataFile,
            Map<String, Object> optionOverrides,
            int sampleRows,
            Consumer<Map<String, Object>> progressSink) {
        CandidatePlan plan = prepareCandidates(copybookContent, dataSample, optionOverrides);
        CobolCopybookAnalyzer.Analysis analysis = plan.analysis();
        List<Map<String, Object>> candidateOptions = plan.candidateOptions();

        List<Map<String, Object>> eventLog = new ArrayList<>();
        Map<String, Object> startEvent = event("info", "Starting Cobrix preview", Map.of("candidates", candidateOptions.size()));
        eventLog.add(startEvent);
        progressSink.accept(startEvent);
        Exception lastError = null;

        for (Map<String, Object> candidate : candidateOptions) {
            String label = String.valueOf(candidate.getOrDefault("_candidate_label", "candidate"));
            Map<String, Object> candidateEvent = event("candidate", "Trying candidate " + label, candidate);
            eventLog.add(candidateEvent);
            progressSink.accept(candidateEvent);
            try {
                Map<String, Object> sparkStart = event("spark", "Sending to Spark daemon", Map.of("candidate", label));
                eventLog.add(sparkStart);
                progressSink.accept(sparkStart);

                // Write copybook to temp file for daemon
                Path copybookFile = Files.createTempFile("copybook-", ".cpy");
                Files.writeString(copybookFile, copybookContent);

                // Build command JSON
                Map<String, Object> cmd = new LinkedHashMap<>();
                cmd.put("copybookFile", copybookFile.toAbsolutePath().toString());
                cmd.put("dataFile", dataFile.toAbsolutePath().toString());
                cmd.put("options", toSparkOptions(candidate));
                cmd.put("sampleRows", sampleRows);

                String jsonLine = sendToDaemon(MAPPER.writeValueAsString(cmd), label);
                Files.deleteIfExists(copybookFile);

                if (jsonLine == null) {
                    throw new RuntimeException("No response from Spark daemon");
                }

                @SuppressWarnings("unchecked")
                Map<String, Object> sparkResult = MAPPER.readValue(jsonLine, Map.class);
                if ("ERROR".equals(sparkResult.get("status"))) {
                    throw new RuntimeException(String.valueOf(sparkResult.get("error")));
                }
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> previewRows = (List<Map<String, Object>>) sparkResult.get("previewRows");
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> fields = (List<Map<String, Object>>) sparkResult.get("fields");
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> mapping = (List<Map<String, Object>>) sparkResult.get("mapping");
                @SuppressWarnings("unchecked")
                Map<String, Object> flattenSpec = (Map<String, Object>) sparkResult.get("flattenSpec");
                long rowCount = ((Number) sparkResult.get("rowCount")).longValue();

                Map<String, Object> schemaSnapshot = new LinkedHashMap<>();
                schemaSnapshot.put("fields", fields);
                schemaSnapshot.put("fieldCount", fields.size());

                PreviewOutcome outcome = buildOutcome(
                        candidate, analysis.summary(), schemaSnapshot, previewRows,
                        mapping, flattenSpec, rowCount, eventLog
                );
                Map<String, Object> successEvent = event("success", "Candidate " + label + " parsed successfully", Map.of(
                        "rowCount", outcome.profilingSummary().get("rowCount"),
                        "columnCount", outcome.profilingSummary().get("columnCount")
                ));
                eventLog.add(successEvent);
                progressSink.accept(successEvent);
                return new PreviewOutcome(
                        outcome.chosenConfig(), outcome.copybookSummary(), outcome.schemaSnapshot(),
                        outcome.profilingSummary(), outcome.anomalySummary(), outcome.confidenceScore(),
                        outcome.previewRows(), outcome.mappingSpec(), outcome.flattenSpec(), eventLog
                );
            } catch (Exception ex) {
                lastError = ex;
                Map<String, Object> failureEvent = event("failure", "Candidate " + label + " failed", Map.of("error", ex.getMessage()));
                eventLog.add(failureEvent);
                progressSink.accept(failureEvent);
            }
        }

        throw new IllegalStateException(
                "Cobrix preview failed for all candidates"
                        + (lastError != null ? ": " + lastError.getMessage() : ""));
    }

    public CandidatePlan prepareCandidates(
            String copybookContent,
            byte[] dataSample,
            Map<String, Object> optionOverrides) {
        CobolCopybookAnalyzer.Analysis analysis = analyzer.analyze(copybookContent, dataSample);
        List<Map<String, Object>> candidateOptions = new ArrayList<>();
        if (optionOverrides != null && !optionOverrides.isEmpty()) {
            Map<String, Object> explicit = new LinkedHashMap<>(analysis.baseOptions());
            explicit.putAll(optionOverrides);
            candidateOptions.add(normalizeCandidate(withLabel(explicit, "user-overrides")));
        } else {
            candidateOptions.addAll(analysis.candidateOptions().stream()
                    .map(this::normalizeCandidate)
                    .toList());
        }
        return new CandidatePlan(analysis, candidateOptions);
    }

    public PreviewOutcome buildOutcome(
            Map<String, Object> chosenConfig,
            Map<String, Object> copybookSummary,
            Map<String, Object> schemaSnapshot,
            List<Map<String, Object>> previewRows,
            List<? extends Map<String, ?>> mappingSpec,
            Map<String, Object> flattenSpec,
            long rowCount,
            List<Map<String, Object>> eventLog) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> mapping = (List<Map<String, Object>>) (List<?>) mappingSpec;
        Set<String> redefinesBranchPrefixes = extractRedefinesBranchPrefixes(chosenConfig);
        Map<String, Object> profilingSummary = profiling(rowCount, schemaSnapshot, previewRows);
        Map<String, Object> anomalySummary = anomalies(schemaSnapshot, previewRows, profilingSummary, redefinesBranchPrefixes);
        double confidenceScore = confidence(profilingSummary, anomalySummary, redefinesBranchPrefixes);
        return new PreviewOutcome(
                chosenConfig,
                copybookSummary,
                schemaSnapshot,
                profilingSummary,
                anomalySummary,
                confidenceScore,
                previewRows,
                mapping,
                flattenSpec,
                eventLog
        );
    }

    private String sendToDaemon(String commandJson, String label) throws Exception {
        synchronized (DAEMON_LOCK) {
            ensureDaemonRunning(label);
            daemonStdin.write(commandJson);
            daemonStdin.newLine();
            daemonStdin.flush();
            // Read lines until we get SPARK_RESULT_JSON (skip any Spark log noise)
            String line;
            while ((line = daemonStdout.readLine()) != null) {
                if (line.startsWith("SPARK_RESULT_JSON:")) {
                    return line.substring("SPARK_RESULT_JSON:".length());
                }
                log.info("SparkDaemon stdout (skipped): {}", line);
            }
            // Daemon died
            daemonProcess = null;
            throw new RuntimeException("Spark daemon terminated unexpectedly");
        }
    }

    private void ensureDaemonRunning(String label) throws Exception {
        if (daemonProcess != null && daemonProcess.isAlive()) return;

        log.info("Starting Spark daemon subprocess...");
        ProcessBuilder pb = new ProcessBuilder(
                "java",
                "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
                "--add-opens=java.base/java.nio=ALL-UNNAMED",
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.util=ALL-UNNAMED",
                "-cp", SPARK_CP,
                "com.pulse.cobol.spark.SparkRunner"
        );
        pb.redirectErrorStream(false);
        daemonProcess = pb.start();
        daemonStdin = new java.io.BufferedWriter(new java.io.OutputStreamWriter(daemonProcess.getOutputStream()));
        daemonStdout = new BufferedReader(new InputStreamReader(daemonProcess.getInputStream()));

        // Stream stderr to log in background
        Thread errThread = new Thread(() -> {
            try (var br = new BufferedReader(new InputStreamReader(daemonProcess.getErrorStream()))) {
                String l;
                while ((l = br.readLine()) != null) log.info("SparkDaemon: {}", l);
            } catch (Exception ignored) {}
        }, "spark-daemon-stderr");
        errThread.setDaemon(true);
        errThread.start();

        // Wait for SPARK_READY (skip any Spark log lines that leak to stdout)
        long deadline = System.currentTimeMillis() + 120_000;
        String readyLine;
        while ((readyLine = daemonStdout.readLine()) != null) {
            log.info("SparkDaemon stdout: {}", readyLine);
            if (readyLine.contains("SPARK_READY")) break;
            if (System.currentTimeMillis() > deadline) {
                daemonProcess.destroyForcibly();
                daemonProcess = null;
                throw new RuntimeException("Spark daemon timed out waiting for SPARK_READY");
            }
        }
        if (readyLine == null) {
            daemonProcess.destroyForcibly();
            daemonProcess = null;
            throw new RuntimeException("Spark daemon died before SPARK_READY");
        }
        log.info("Spark daemon ready (PID {})", daemonProcess.pid());
    }

    private Map<String, String> toSparkOptions(Map<String, Object> options) {
        Map<String, String> sparkOptions = new LinkedHashMap<>();
        appendCobrixOptions(sparkOptions, options);
        return sparkOptions;
    }

    @SuppressWarnings("unchecked")
    void appendCobrixOptions(Map<String, String> target, Map<String, Object> options) {
        if (options == null || options.isEmpty()) {
            return;
        }
        int redefineIndex = 0;
        for (Map.Entry<String, Object> entry : options.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (key == null || key.startsWith("_") || value == null) continue;
            if ("redefine_segment_id_map".equals(key) && value instanceof Map<?, ?> redefineMap) {
                for (Map.Entry<String, Object> redefineEntry : ((Map<String, Object>) redefineMap).entrySet()) {
                    if (redefineEntry.getKey() == null || redefineEntry.getValue() == null) continue;
                    target.put(
                            "redefine-segment-id-map:" + redefineIndex++,
                            String.valueOf(redefineEntry.getValue()) + " => " + redefineEntry.getKey()
                    );
                }
                continue;
            }
            target.put(key, String.valueOf(value));
        }
    }

    private Map<String, Object> normalizeCandidate(Map<String, Object> candidate) {
        Map<String, Object> normalized = new LinkedHashMap<>(candidate);
        String encoding = String.valueOf(normalized.getOrDefault("encoding", "")).trim().toLowerCase();
        if ("ascii".equals(encoding)) {
            normalized.remove("ebcdic_code_page");
        }
        return normalized;
    }

    private Map<String, Object> withLabel(Map<String, Object> candidate, String label) {
        Map<String, Object> labeled = new LinkedHashMap<>(candidate);
        labeled.put("_candidate_label", label);
        return labeled;
    }

    private Map<String, Object> profiling(long rowCount, Map<String, Object> schemaSnapshot, List<Map<String, Object>> previewRows) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) schemaSnapshot.getOrDefault("fields", List.of());
        int columnCount = fields.size();
        Map<String, Object> maxLengths = new LinkedHashMap<>();
        Map<String, Object> nullCounts = new LinkedHashMap<>();
        Map<String, Object> nullRates = new LinkedHashMap<>();
        Map<String, Object> distinctCounts = new LinkedHashMap<>();
        Map<String, Object> topValues = new LinkedHashMap<>();
        Map<String, Object> typeValidation = new LinkedHashMap<>();
        for (Map<String, Object> field : fields) {
            String column = String.valueOf(field.get("name"));
            int maxLength = 0;
            int nullCount = 0;
            Map<String, Integer> frequencies = new LinkedHashMap<>();
            String dataType = String.valueOf(field.get("type"));
            int validCount = 0;
            int checkedCount = 0;
            for (Map<String, Object> row : previewRows) {
                Object value = row.get(column);
                if (value == null || String.valueOf(value).isBlank()) {
                    nullCount++;
                } else {
                    String text = String.valueOf(value).trim();
                    maxLength = Math.max(maxLength, text.length());
                    frequencies.put(text, frequencies.getOrDefault(text, 0) + 1);
                    checkedCount++;
                    if (matchesType(dataType, text)) {
                        validCount++;
                    }
                }
            }
            maxLengths.put(column, maxLength);
            nullCounts.put(column, nullCount);
            nullRates.put(column, previewRows.isEmpty() ? 0.0 : ((double) nullCount / (double) previewRows.size()));
            distinctCounts.put(column, frequencies.size());
            topValues.put(column, frequencies.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
                    .limit(3)
                    .map(entry -> Map.of("value", entry.getKey(), "count", entry.getValue()))
                    .toList());
            typeValidation.put(column, Map.of(
                    "declaredType", dataType,
                    "checkedPreviewValues", checkedCount,
                    "validPreviewValues", validCount
            ));
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("rowCount", rowCount);
        summary.put("columnCount", columnCount);
        summary.put("previewRowCount", previewRows.size());
        summary.put("maxLengths", maxLengths);
        summary.put("nullCountsInPreview", nullCounts);
        summary.put("nullRatesInPreview", nullRates);
        summary.put("distinctCountsInPreview", distinctCounts);
        summary.put("topValuesInPreview", topValues);
        summary.put("typeValidation", typeValidation);
        return summary;
    }

    @SuppressWarnings("unchecked")
    private Set<String> extractRedefinesBranchPrefixes(Map<String, Object> config) {
        Set<String> prefixes = new LinkedHashSet<>();
        if (config == null) return prefixes;
        Object mapObj = config.get("redefine_segment_id_map");
        if (mapObj instanceof Map<?, ?> redefineMap) {
            for (Object branch : redefineMap.values()) {
                if (branch != null) {
                    String sanitized = String.valueOf(branch).toLowerCase(Locale.ROOT)
                            .replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
                    if (!sanitized.isEmpty()) {
                        prefixes.add(sanitized + "_");
                    }
                }
            }
        }
        return prefixes;
    }

    private boolean isRedefinesBranchColumn(String columnName, Set<String> redefinesBranchPrefixes) {
        if (redefinesBranchPrefixes.isEmpty()) return false;
        String lower = columnName.toLowerCase(Locale.ROOT);
        for (String prefix : redefinesBranchPrefixes) {
            if (lower.startsWith(prefix)) return true;
        }
        return false;
    }

    private Map<String, Object> anomalies(
            Map<String, Object> schemaSnapshot,
            List<Map<String, Object>> previewRows,
            Map<String, Object> profilingSummary,
            Set<String> redefinesBranchPrefixes) {
        List<String> warnings = new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) schemaSnapshot.getOrDefault("fields", List.of());
        if (fields.isEmpty()) {
            warnings.add("Parsed dataset has no columns.");
        }
        if (previewRows.isEmpty()) {
            warnings.add("Preview returned zero rows.");
        }
        long fullyNullRows = previewRows.stream()
                .filter(row -> row.values().stream().allMatch(value -> value == null || String.valueOf(value).isBlank()))
                .count();
        if (fullyNullRows > 0) {
            warnings.add("Some preview rows are entirely null/blank after flattening.");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> nullRates = (Map<String, Object>) profilingSummary.getOrDefault("nullRatesInPreview", Map.of());
        for (Map.Entry<String, Object> entry : nullRates.entrySet()) {
            if (isRedefinesBranchColumn(entry.getKey(), redefinesBranchPrefixes)) continue;
            double nullRate = ((Number) entry.getValue()).doubleValue();
            if (nullRate >= 0.8) {
                warnings.add("Column `" + entry.getKey() + "` is mostly null in preview (" + percent(nullRate) + ").");
            }
        }

        @SuppressWarnings("unchecked")
        Map<String, List<Map<String, Object>>> topValues = (Map<String, List<Map<String, Object>>>) profilingSummary.getOrDefault("topValuesInPreview", Map.of());
        for (Map.Entry<String, List<Map<String, Object>>> entry : topValues.entrySet()) {
            if (isRedefinesBranchColumn(entry.getKey(), redefinesBranchPrefixes)) continue;
            List<Map<String, Object>> values = entry.getValue();
            if (!values.isEmpty()) {
                int topCount = ((Number) values.get(0).get("count")).intValue();
                if (!previewRows.isEmpty() && ((double) topCount / (double) previewRows.size()) >= 0.9) {
                    warnings.add("Column `" + entry.getKey() + "` is dominated by one preview value.");
                }
            }
        }

        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> typeValidation = (Map<String, Map<String, Object>>) profilingSummary.getOrDefault("typeValidation", Map.of());
        for (Map.Entry<String, Map<String, Object>> entry : typeValidation.entrySet()) {
            if (isRedefinesBranchColumn(entry.getKey(), redefinesBranchPrefixes)) continue;
            int checked = ((Number) entry.getValue().getOrDefault("checkedPreviewValues", 0)).intValue();
            int valid = ((Number) entry.getValue().getOrDefault("validPreviewValues", 0)).intValue();
            String declaredType = String.valueOf(entry.getValue().getOrDefault("declaredType", "string"));
            if (checked > 0 && valid < checked && !"string".equals(declaredType)) {
                warnings.add("Column `" + entry.getKey() + "` has preview values that do not match declared type `" + declaredType + "`.");
            }
        }
        return Map.of("warnings", warnings);
    }

    @SuppressWarnings("unchecked")
    private double confidence(Map<String, Object> profilingSummary, Map<String, Object> anomalySummary, Set<String> redefinesBranchPrefixes) {
        long rowCount = ((Number) profilingSummary.getOrDefault("rowCount", 0L)).longValue();
        int columnCount = ((Number) profilingSummary.getOrDefault("columnCount", 0)).intValue();
        List<String> warnings = (List<String>) anomalySummary.getOrDefault("warnings", List.of());

        // Base: 20. Max without penalties: 20+15+5+10+5+15+15+15 = 100
        double score = 20.0;
        if (rowCount > 0) score += 15.0;
        if (rowCount > 5) score += 5.0;
        if (columnCount > 0) score += 10.0;
        if (columnCount > 3) score += 5.0;

        // Type validation: do parsed values match declared types? Skip REDEFINES branch columns.
        Map<String, Map<String, Object>> typeValidation = (Map<String, Map<String, Object>>) profilingSummary.getOrDefault("typeValidation", Map.of());
        long tvTotal = typeValidation.entrySet().stream()
                .filter(e -> !isRedefinesBranchColumn(e.getKey(), redefinesBranchPrefixes))
                .count();
        long fullMatches = typeValidation.entrySet().stream()
                .filter(e -> !isRedefinesBranchColumn(e.getKey(), redefinesBranchPrefixes))
                .filter(e -> ((Number) e.getValue().getOrDefault("checkedPreviewValues", 0)).intValue() == ((Number) e.getValue().getOrDefault("validPreviewValues", 0)).intValue())
                .count();
        if (tvTotal > 0) {
            score += Math.min(15.0, (15.0 * fullMatches) / tvTotal);
        }

        // Null rate health: only penalize columns that are 100% null AND are not
        // REDEFINES branch columns or filler columns
        Map<String, Object> nullRates = (Map<String, Object>) profilingSummary.getOrDefault("nullRatesInPreview", Map.of());
        long nonBranchCols = nullRates.entrySet().stream()
                .filter(e -> !isRedefinesBranchColumn(e.getKey(), redefinesBranchPrefixes))
                .filter(e -> !e.getKey().toLowerCase().contains("filler"))
                .count();
        long totallyNullNonBranch = nullRates.entrySet().stream()
                .filter(e -> ((Number) e.getValue()).doubleValue() >= 1.0)
                .filter(e -> !isRedefinesBranchColumn(e.getKey(), redefinesBranchPrefixes))
                .filter(e -> !e.getKey().toLowerCase().contains("filler"))
                .count();
        if (nonBranchCols > 0) {
            double nullHealthRatio = 1.0 - ((double) totallyNullNonBranch / nonBranchCols);
            score += 15.0 * nullHealthRatio;
        } else {
            score += 15.0;
        }

        // Distinct value diversity: skip REDEFINES branch columns entirely
        Map<String, Object> distinctCounts = (Map<String, Object>) profilingSummary.getOrDefault("distinctCountsInPreview", Map.of());
        long populatedCols = distinctCounts.entrySet().stream()
                .filter(e -> !isRedefinesBranchColumn(e.getKey(), redefinesBranchPrefixes))
                .filter(e -> {
                    Object nr = nullRates.get(e.getKey());
                    return nr == null || ((Number) nr).doubleValue() < 1.0;
                })
                .count();
        long lowDiversityPopulated = distinctCounts.entrySet().stream()
                .filter(e -> !isRedefinesBranchColumn(e.getKey(), redefinesBranchPrefixes))
                .filter(e -> {
                    Object nr = nullRates.get(e.getKey());
                    return nr == null || ((Number) nr).doubleValue() < 1.0;
                })
                .filter(e -> ((Number) e.getValue()).intValue() <= 1)
                .count();
        if (populatedCols > 0) {
            double diversityRatio = 1.0 - ((double) lowDiversityPopulated / populatedCols);
            score += 15.0 * diversityRatio;
        } else {
            score += 15.0;
        }

        // Warning penalty: mild, 3 per warning
        score -= warnings.size() * 3.0;
        return Math.max(0.0, Math.min(100.0, score));
    }

    private boolean matchesType(String declaredType, String text) {
        return switch (declaredType) {
            case "byte", "short", "integer", "long" -> INTEGER_PATTERN.matcher(text).matches();
            case "float", "double", "decimal" -> DECIMAL_PATTERN.matcher(text).matches();
            case "date" -> isDate(text);
            default -> true;
        };
    }

    private boolean isDate(String text) {
        try {
            LocalDate.parse(text);
            return true;
        } catch (DateTimeParseException ex) {
            return false;
        }
    }

    private String percent(double fraction) {
        return String.format("%.0f%%", fraction * 100.0);
    }

    private Map<String, Object> event(String type, String message, Map<String, Object> detail) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", type);
        event.put("message", message);
        event.put("detail", detail);
        event.put("timestamp", Instant.now().toString());
        return event;
    }

    public record PreviewOutcome(
            Map<String, Object> chosenConfig,
            Map<String, Object> copybookSummary,
            Map<String, Object> schemaSnapshot,
            Map<String, Object> profilingSummary,
            Map<String, Object> anomalySummary,
            double confidenceScore,
            List<Map<String, Object>> previewRows,
            List<Map<String, Object>> mappingSpec,
            Map<String, Object> flattenSpec,
            List<Map<String, Object>> eventLog) {}

    public record CandidatePlan(
            CobolCopybookAnalyzer.Analysis analysis,
            List<Map<String, Object>> candidateOptions) {}
}
