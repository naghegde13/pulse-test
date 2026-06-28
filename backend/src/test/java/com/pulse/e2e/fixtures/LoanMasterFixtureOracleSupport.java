package com.pulse.e2e.fixtures;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

final class LoanMasterFixtureOracleSupport {

    private static final Pattern DATE_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern INTEGER_PATTERN = Pattern.compile("-?\\d+");
    private static final Pattern DECIMAL_PATTERN = Pattern.compile("-?\\d+\\.\\d+");
    private static final Pattern BOOLEAN_PATTERN = Pattern.compile("(?i:true|false)");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern PHONE_PATTERN = Pattern.compile("^\\(\\d{3}\\) \\d{3}-\\d{4}$");
    private static final Pattern MASKED_SSN_PATTERN = Pattern.compile("^XXX-XX-\\d{4}$");
    private static final Pattern RAW_SSN_PATTERN = Pattern.compile("^\\d{3}-?\\d{2}-?\\d{4}$");

    private static final List<String> BUSINESS_KEYS = List.of(
            "loan_id", "loan_number", "investor_loan_id", "mers_min_number"
    );

    private static final List<String> PII_COLUMNS = List.of(
            "borrower_first_name",
            "borrower_last_name",
            "borrower_ssn_masked",
            "borrower_dob",
            "borrower_email",
            "borrower_phone",
            "property_address_line1",
            "coborrower_first_name",
            "coborrower_last_name"
    );

    private static final List<String> PARTITION_COLUMNS = List.of(
            "investor_name", "property_state", "loan_status"
    );

    private static final List<String> AGGREGATE_COLUMNS = List.of(
            "original_loan_amount",
            "current_upb",
            "interest_rate",
            "pi_payment",
            "escrow_payment_monthly",
            "total_monthly_payment",
            "months_delinquent",
            "ltv_ratio",
            "cltv_ratio",
            "appraised_value",
            "borrower_annual_income",
            "borrower_credit_score",
            "remaining_term_months",
            "late_charges_due",
            "suspense_balance",
            "partial_payment_balance"
    );

    private static final List<String> REQUIRED_COLUMNS = List.of(
            "loan_id", "loan_number", "origination_date", "current_upb", "investor_name"
    );

    private static final List<String> PROFILE_COLUMNS = List.of(
            "loan_status", "investor_name", "property_state", "loan_type", "loan_purpose", "occupancy_type"
    );

    private static final List<String> BAD_RECORD_LOAN_IDS = List.of("LN100000", "LN100001", "LN100002");
    private static final List<String> LATE_ARRIVING_LOAN_IDS = List.of(
            "LN100475", "LN100476", "LN100477", "LN100478", "LN100479",
            "LN100480", "LN100481", "LN100482", "LN100483", "LN100484"
    );

    private LoanMasterFixtureOracleSupport() {
    }

    static Path repoRoot() {
        Path cursor = Path.of("").toAbsolutePath().normalize();
        while (cursor != null) {
            if (Files.exists(cursor.resolve("data/loan_master.csv"))
                    && Files.exists(cursor.resolve("backend/build.gradle.kts"))) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("Could not locate repo root from working directory");
    }

    static Path canonicalCsvPath() {
        return repoRoot().resolve("data/loan_master.csv");
    }

    static Map<String, Object> buildFixtureManifest(Path csvPath) throws IOException {
        CsvTable table = CsvTable.load(csvPath);

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("fixture_id", "loan_master");
        manifest.put("oracle_version", 1);
        manifest.put("source", sourceProfile(table, csvPath));
        manifest.put("derivatives", List.of(
                currentLoansDerivative(table),
                delinquentLoansDerivative(table),
                investorStatePartitionsDerivative(table),
                badRecordsDerivative(table),
                schemaEvolutionDerivative(table),
                lateArrivingDerivative(table)
        ));
        return manifest;
    }

    static Map<String, Object> buildDataOracle(Path csvPath) throws IOException {
        CsvTable table = CsvTable.load(csvPath);

        Map<String, Object> oracle = new LinkedHashMap<>();
        oracle.put("dataset", "loan_master");
        oracle.put("oracle_version", 1);
        oracle.put("source_file", repoRoot().relativize(csvPath.toAbsolutePath().normalize()).toString().replace('\\', '/'));
        oracle.put("checksums", Map.of(
                "file_sha256", sha256(Files.readAllBytes(csvPath)),
                "canonical_csv_sha256", sha256(table.toCanonicalCsv().getBytes(StandardCharsets.UTF_8))
        ));
        oracle.put("row_count", table.rows().size());
        oracle.put("column_count", table.headers().size());
        oracle.put("schema", schemaProfile(table));
        oracle.put("business_keys", BUSINESS_KEYS);
        oracle.put("aggregates", aggregateProfile(table));
        oracle.put("dq_counts", dqProfile(table));
        oracle.put("partition_expectations", partitionProfile(table));
        oracle.put("masked_pii_assertions", piiProfile(table));
        oracle.put("fixture_derivative_ids", List.of(
                "current_loans",
                "delinquent_loans",
                "investor_state_partitions",
                "bad_records_required_fields",
                "schema_add_servicing_risk_band",
                "late_arriving_boarding"
        ));
        return oracle;
    }

    private static Map<String, Object> sourceProfile(CsvTable table, Path csvPath) throws IOException {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("relative_path", repoRoot().relativize(csvPath.toAbsolutePath().normalize()).toString().replace('\\', '/'));
        source.put("row_count", table.rows().size());
        source.put("column_count", table.headers().size());
        source.put("file_sha256", sha256(Files.readAllBytes(csvPath)));
        source.put("canonical_csv_sha256", sha256(table.toCanonicalCsv().getBytes(StandardCharsets.UTF_8)));
        source.put("schema_signature", schemaSignature(table.headers(), inferColumnTypes(table)));
        source.put("business_keys", BUSINESS_KEYS);
        source.put("pii_columns", PII_COLUMNS);
        source.put("partition_columns", PARTITION_COLUMNS);
        source.put("stable_sort_keys", List.of("loan_id"));
        return source;
    }

    private static Map<String, Object> currentLoansDerivative(CsvTable table) {
        CsvTable filtered = table.filter(row -> "Current".equals(row.get("loan_status")));
        return derivativeDescriptor(
                "current_loans",
                filtered,
                List.of(Map.of(
                        "type", "filter",
                        "column", "loan_status",
                        "operator", "eq",
                        "value", "Current"
                )),
                "loan_master_current.csv"
        );
    }

    private static Map<String, Object> delinquentLoansDerivative(CsvTable table) {
        CsvTable filtered = table.filter(row -> intValue(row, "months_delinquent") > 0);
        return derivativeDescriptor(
                "delinquent_loans",
                filtered,
                List.of(Map.of(
                        "type", "filter",
                        "column", "months_delinquent",
                        "operator", "gt",
                        "value", 0
                )),
                "loan_master_delinquent.csv"
        );
    }

    private static Map<String, Object> investorStatePartitionsDerivative(CsvTable table) {
        Map<String, Object> out = derivativeDescriptor(
                "investor_state_partitions",
                table,
                List.of(Map.of(
                        "type", "partition_by",
                        "columns", List.of("investor_name", "property_state")
                )),
                "loan_master_partitioned/"
        );
        out.put("partition_count", countsByComposite(table, "investor_name", "property_state").size());
        out.put("expected_partitions", countsByComposite(table, "investor_name", "property_state"));
        return out;
    }

    private static Map<String, Object> badRecordsDerivative(CsvTable table) {
        CsvTable mutated = table.mapRows(row -> {
            if (!BAD_RECORD_LOAN_IDS.contains(row.get("loan_id"))) {
                return row;
            }
            Map<String, String> copy = new LinkedHashMap<>(row);
            if ("LN100000".equals(row.get("loan_id"))) {
                copy.put("loan_id", "");
            } else if ("LN100001".equals(row.get("loan_id"))) {
                copy.put("current_upb", "");
            } else if ("LN100002".equals(row.get("loan_id"))) {
                copy.put("origination_date", "");
            }
            return copy;
        });
        Map<String, Object> out = derivativeDescriptor(
                "bad_records_required_fields",
                mutated,
                List.of(
                        Map.of(
                                "type", "mutate_value",
                                "column", "loan_id",
                                "loan_id", "LN100000",
                                "value", ""
                        ),
                        Map.of(
                                "type", "mutate_value",
                                "column", "current_upb",
                                "loan_id", "LN100001",
                                "value", ""
                        ),
                        Map.of(
                                "type", "mutate_value",
                                "column", "origination_date",
                                "loan_id", "LN100002",
                                "value", ""
                        )
                ),
                "loan_master_bad_required_fields.csv"
        );
        out.put("expected_dq_counts", dqProfile(mutated));
        return out;
    }

    private static Map<String, Object> schemaEvolutionDerivative(CsvTable table) {
        List<String> headers = new ArrayList<>(table.headers());
        headers.add("servicing_risk_band");
        List<Map<String, String>> rows = table.rows().stream()
                .map(row -> {
                    Map<String, String> copy = new LinkedHashMap<>(row);
                    int delinquent = intValue(copy, "months_delinquent");
                    String riskBand = delinquent >= 12 ? "critical"
                            : delinquent >= 3 ? "watch"
                            : "stable";
                    copy.put("servicing_risk_band", riskBand);
                    return copy;
                })
                .toList();
        CsvTable evolved = new CsvTable(headers, rows);
        Map<String, Object> out = derivativeDescriptor(
                "schema_add_servicing_risk_band",
                evolved,
                List.of(Map.of(
                        "type", "add_column",
                        "column", "servicing_risk_band",
                        "expression", "months_delinquent>=12?critical:months_delinquent>=3?watch:stable"
                )),
                "loan_master_with_risk_band.csv"
        );
        out.put("added_columns", List.of(Map.of("name", "servicing_risk_band", "type", "string")));
        out.put("servicing_risk_band_counts", counts(rows, "servicing_risk_band"));
        return out;
    }

    private static Map<String, Object> lateArrivingDerivative(CsvTable table) {
        CsvTable lateRows = table.filter(row -> LATE_ARRIVING_LOAN_IDS.contains(row.get("loan_id")))
                .mapRows(row -> {
                    Map<String, String> copy = new LinkedHashMap<>(row);
                    copy.put("boarding_date", LocalDate.parse(copy.get("boarding_date")).minusDays(45).toString());
                    copy.put("last_payment_date", LocalDate.parse(copy.get("last_payment_date")).minusDays(30).toString());
                    return copy;
                });
        Map<String, Object> out = derivativeDescriptor(
                "late_arriving_boarding",
                lateRows,
                List.of(
                        Map.of(
                                "type", "filter",
                                "column", "loan_id",
                                "operator", "in",
                                "value", LATE_ARRIVING_LOAN_IDS
                        ),
                        Map.of(
                                "type", "mutate_value",
                                "column", "boarding_date",
                                "rule", "minus_days:45"
                        ),
                        Map.of(
                                "type", "mutate_value",
                                "column", "last_payment_date",
                                "rule", "minus_days:30"
                        )
                ),
                "loan_master_late_arriving.csv"
        );
        out.put("loan_ids", LATE_ARRIVING_LOAN_IDS);
        out.put("staging_semantics", "stage_after_primary_file");
        return out;
    }

    private static Map<String, Object> derivativeDescriptor(
            String derivativeId,
            CsvTable table,
            List<Map<String, Object>> operations,
            String outputFile
    ) {
        Map<String, String> columnTypes = inferColumnTypes(table);
        Map<String, Object> derivative = new LinkedHashMap<>();
        derivative.put("derivative_id", derivativeId);
        derivative.put("source_file_sha256", sha256Bytes(canonicalCsvPath()));
        derivative.put("operations", operations);
        derivative.put("output_file", outputFile);
        derivative.put("row_count", table.rows().size());
        derivative.put("column_count", table.headers().size());
        derivative.put("schema_signature", schemaSignature(table.headers(), columnTypes));
        derivative.put("schema", schemaProfile(table));
        derivative.put("business_keys", BUSINESS_KEYS);
        derivative.put("canonical_csv_sha256", sha256(table.toCanonicalCsv().getBytes(StandardCharsets.UTF_8)));
        derivative.put("required_field_nulls", requiredFieldNulls(table));
        derivative.put("aggregates", aggregateProfile(table));
        derivative.put("dq_counts", dqProfile(table));
        derivative.put("partition_expectations", partitionProfile(table));
        derivative.put("masked_pii_assertions", piiProfile(table));
        return derivative;
    }

    private static Map<String, Object> schemaProfile(CsvTable table) {
        Map<String, String> types = inferColumnTypes(table);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("signature", schemaSignature(table.headers(), types));
        schema.put("column_order_sha256", sha256(String.join("|", table.headers()).getBytes(StandardCharsets.UTF_8)));
        schema.put("columns", table.headers().stream().map(header -> {
            Map<String, Object> col = new LinkedHashMap<>();
            col.put("name", header);
            col.put("type", types.get(header));
            col.put("nullable", nullCount(table, header) > 0);
            return col;
        }).toList());
        return schema;
    }

    private static Map<String, String> inferColumnTypes(CsvTable table) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String header : table.headers()) {
            out.put(header, inferType(table.rows(), header));
        }
        return out;
    }

    private static String inferType(List<Map<String, String>> rows, String column) {
        boolean allBoolean = true;
        boolean allInteger = true;
        boolean allDecimalOrInteger = true;
        boolean allDate = true;
        boolean hasLeadingZeroInteger = false;
        for (Map<String, String> row : rows) {
            String value = row.get(column);
            if (value == null || value.isBlank()) {
                continue;
            }
            hasLeadingZeroInteger |= value.matches("^0[0-9]+$");
            allBoolean &= BOOLEAN_PATTERN.matcher(value).matches();
            allInteger &= INTEGER_PATTERN.matcher(value).matches();
            allDecimalOrInteger &= INTEGER_PATTERN.matcher(value).matches() || DECIMAL_PATTERN.matcher(value).matches();
            allDate &= DATE_PATTERN.matcher(value).matches();
        }
        if (hasLeadingZeroInteger) return "string";
        if (allBoolean) return "boolean";
        if (allInteger) return "integer";
        if (allDecimalOrInteger) return "decimal";
        if (allDate) return "date";
        return "string";
    }

    private static String schemaSignature(List<String> headers, Map<String, String> types) {
        String payload = headers.stream()
                .map(header -> header + ":" + types.get(header))
                .collect(Collectors.joining("|"));
        return sha256(payload.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, Object> aggregateProfile(CsvTable table) {
        Map<String, Object> aggregates = new LinkedHashMap<>();
        for (String column : AGGREGATE_COLUMNS) {
            List<BigDecimal> values = table.rows().stream()
                    .map(row -> decimalValue(row, column))
                    .filter(Objects::nonNull)
                    .toList();
            Map<String, Object> stats = new LinkedHashMap<>();
            BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal average = values.isEmpty()
                    ? BigDecimal.ZERO
                    : sum.divide(BigDecimal.valueOf(values.size()), 2, RoundingMode.HALF_UP);
            stats.put("sum", sum.stripTrailingZeros().toPlainString());
            stats.put("min", values.stream().min(BigDecimal::compareTo).orElse(BigDecimal.ZERO).stripTrailingZeros().toPlainString());
            stats.put("max", values.stream().max(BigDecimal::compareTo).orElse(BigDecimal.ZERO).stripTrailingZeros().toPlainString());
            stats.put("avg", average.toPlainString());
            aggregates.put(column, stats);
        }
        Map<String, Object> dimensions = new LinkedHashMap<>();
        for (String column : PROFILE_COLUMNS) {
            dimensions.put(column, counts(table.rows(), column));
        }
        aggregates.put("dimension_counts", dimensions);
        return aggregates;
    }

    private static Map<String, Object> dqProfile(CsvTable table) {
        Map<String, Object> dq = new LinkedHashMap<>();
        dq.put("required_field_nulls", requiredFieldNulls(table));
        dq.put("duplicate_key_counts", Map.of(
                "loan_id", duplicateCount(table, "loan_id"),
                "loan_number", duplicateCount(table, "loan_number"),
                "investor_loan_id", duplicateCount(table, "investor_loan_id")
        ));
        dq.put("email_format_failures", countWhere(table.rows(), row -> !EMAIL_PATTERN.matcher(row.get("borrower_email")).matches()));
        dq.put("phone_format_failures", countWhere(table.rows(), row -> !PHONE_PATTERN.matcher(row.get("borrower_phone")).matches()));
        dq.put("masked_ssn_pattern_failures", countWhere(table.rows(), row -> !MASKED_SSN_PATTERN.matcher(row.get("borrower_ssn_masked")).matches()));
        dq.put("raw_ssn_leak_count", countWhere(table.rows(), row -> RAW_SSN_PATTERN.matcher(row.get("borrower_ssn_masked")).matches()
                && !MASKED_SSN_PATTERN.matcher(row.get("borrower_ssn_masked")).matches()));
        dq.put("current_balance_gt_original_count", countWhere(table.rows(), row -> {
            BigDecimal currentUpb = decimalValue(row, "current_upb");
            BigDecimal originalAmount = decimalValue(row, "original_loan_amount");
            return currentUpb != null && originalAmount != null && currentUpb.compareTo(originalAmount) > 0;
        }));
        dq.put("delinquent_row_count", countWhere(table.rows(), row -> intValue(row, "months_delinquent") > 0));
        dq.put("current_status_with_delinquency_count", countWhere(table.rows(), row -> "Current".equals(row.get("loan_status")) && intValue(row, "months_delinquent") > 0));
        return dq;
    }

    private static Map<String, Object> partitionProfile(CsvTable table) {
        Map<String, Object> partitions = new LinkedHashMap<>();
        partitions.put("investor_name", counts(table.rows(), "investor_name"));
        partitions.put("loan_status", counts(table.rows(), "loan_status"));
        partitions.put("property_state", counts(table.rows(), "property_state"));
        partitions.put("investor_property_state", countsByComposite(table, "investor_name", "property_state"));
        return partitions;
    }

    private static Map<String, Object> piiProfile(CsvTable table) {
        Map<String, Object> pii = new LinkedHashMap<>();
        pii.put("pii_columns", PII_COLUMNS);
        pii.put("masked_columns", List.of("borrower_ssn_masked"));
        pii.put("ssn_mask", Map.of(
                "pattern", "XXX-XX-####",
                "valid_row_count", countWhere(table.rows(), row -> MASKED_SSN_PATTERN.matcher(row.get("borrower_ssn_masked")).matches()),
                "invalid_row_count", countWhere(table.rows(), row -> !MASKED_SSN_PATTERN.matcher(row.get("borrower_ssn_masked")).matches()),
                "raw_leak_count", countWhere(table.rows(), row -> RAW_SSN_PATTERN.matcher(row.get("borrower_ssn_masked")).matches()
                        && !MASKED_SSN_PATTERN.matcher(row.get("borrower_ssn_masked")).matches())
        ));
        pii.put("email_assertions", Map.of(
                "format_valid_count", countWhere(table.rows(), row -> EMAIL_PATTERN.matcher(row.get("borrower_email")).matches()),
                "null_count", nullCount(table, "borrower_email")
        ));
        pii.put("phone_assertions", Map.of(
                "format_valid_count", countWhere(table.rows(), row -> PHONE_PATTERN.matcher(row.get("borrower_phone")).matches()),
                "null_count", nullCount(table, "borrower_phone")
        ));
        return pii;
    }

    private static Map<String, Object> requiredFieldNulls(CsvTable table) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String column : REQUIRED_COLUMNS) {
            out.put(column, nullCount(table, column));
        }
        return out;
    }

    private static int nullCount(CsvTable table, String column) {
        return countWhere(table.rows(), row -> row.get(column) == null || row.get(column).isBlank());
    }

    private static int duplicateCount(CsvTable table, String column) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, String> row : table.rows()) {
            counts.merge(row.get(column), 1, Integer::sum);
        }
        return counts.values().stream().mapToInt(v -> Math.max(0, v - 1)).sum();
    }

    private static Map<String, Object> counts(List<Map<String, String>> rows, String column) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, String> row : rows) {
            counts.merge(row.get(column), 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .sorted((a, b) -> {
                    int countCompare = Integer.compare(b.getValue(), a.getValue());
                    return countCompare != 0 ? countCompare : a.getKey().compareTo(b.getKey());
                })
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (a, b) -> a,
                        LinkedHashMap::new
                ));
    }

    private static Map<String, Object> countsByComposite(CsvTable table, String first, String second) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, String> row : table.rows()) {
            String key = row.get(first) + "|" + row.get(second);
            counts.merge(key, 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .sorted((a, b) -> {
                    int countCompare = Integer.compare(b.getValue(), a.getValue());
                    return countCompare != 0 ? countCompare : a.getKey().compareTo(b.getKey());
                })
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (a, b) -> a,
                        LinkedHashMap::new
                ));
    }

    private static int countWhere(List<Map<String, String>> rows, java.util.function.Predicate<Map<String, String>> predicate) {
        int count = 0;
        for (Map<String, String> row : rows) {
            if (predicate.test(row)) {
                count++;
            }
        }
        return count;
    }

    private static BigDecimal decimalValue(Map<String, String> row, String column) {
        String value = row.get(column);
        if (value == null || value.isBlank()) {
            return null;
        }
        return new BigDecimal(value);
    }

    private static int intValue(Map<String, String> row, String column) {
        String value = row.get(column);
        return value == null || value.isBlank() ? 0 : Integer.parseInt(value);
    }

    private static String sha256Bytes(Path path) {
        try {
            return sha256(Files.readAllBytes(path));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to hash " + path, e);
        }
    }

    private static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(value);
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to compute SHA-256", e);
        }
    }

    static final class CsvTable {
        private final List<String> headers;
        private final List<Map<String, String>> rows;

        CsvTable(List<String> headers, List<Map<String, String>> rows) {
            this.headers = List.copyOf(headers);
            this.rows = rows.stream()
                    .map(row -> Map.copyOf(new LinkedHashMap<>(row)))
                    .toList();
        }

        static CsvTable load(Path csvPath) throws IOException {
            List<String> lines = Files.readAllLines(csvPath, StandardCharsets.UTF_8);
            if (lines.isEmpty()) {
                throw new IllegalArgumentException("CSV is empty: " + csvPath);
            }
            List<String> headers = Arrays.asList(lines.get(0).split(",", -1));
            List<Map<String, String>> rows = new ArrayList<>();
            for (int i = 1; i < lines.size(); i++) {
                String[] values = lines.get(i).split(",", -1);
                if (values.length != headers.size()) {
                    throw new IllegalStateException("Row " + i + " expected " + headers.size() + " columns but found " + values.length);
                }
                Map<String, String> row = new LinkedHashMap<>();
                for (int j = 0; j < headers.size(); j++) {
                    row.put(headers.get(j), values[j]);
                }
                rows.add(row);
            }
            return new CsvTable(headers, rows);
        }

        List<String> headers() {
            return headers;
        }

        List<Map<String, String>> rows() {
            return rows;
        }

        CsvTable filter(java.util.function.Predicate<Map<String, String>> predicate) {
            return new CsvTable(headers, rows.stream().filter(predicate).toList());
        }

        CsvTable mapRows(java.util.function.Function<Map<String, String>, Map<String, String>> mapper) {
            return new CsvTable(headers, rows.stream()
                    .map(row -> mapper.apply(new LinkedHashMap<>(row)))
                    .toList());
        }

        String toCanonicalCsv() {
            StringBuilder out = new StringBuilder();
            out.append(String.join(",", headers));
            for (Map<String, String> row : rows) {
                out.append('\n');
                out.append(headers.stream().map(header -> row.getOrDefault(header, "")).collect(Collectors.joining(",")));
            }
            return out.toString();
        }
    }
}
