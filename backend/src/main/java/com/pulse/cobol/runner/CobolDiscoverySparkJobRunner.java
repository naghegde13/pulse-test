package com.pulse.cobol.runner;

import org.apache.spark.sql.Column;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.SparkFiles;
import org.apache.spark.sql.functions;
import org.apache.spark.sql.types.ArrayType;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.MapType;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

public final class CobolDiscoverySparkJobRunner {

    private CobolDiscoverySparkJobRunner() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException("Usage: CobolDiscoverySparkJobRunner <copybookPath> <dataPath> <optionsPath> <outputDir>");
        }

        Path outputDir = Path.of(args[3]);
        Files.createDirectories(outputDir);
        SparkSession spark = SparkSession.builder()
                .appName("pulse-cobol-discovery-runner")
                .getOrCreate();

        try {
            Path copybookPath = resolveSparkFile(args[0]);
            Path dataPath = resolveSparkFile(args[1]);
            Path optionsPath = resolveSparkFile(args[2]);

            String copybookContent = Files.readString(copybookPath, StandardCharsets.UTF_8);
            Properties props = new Properties();
            try (var in = Files.newInputStream(optionsPath)) {
                props.load(in);
            }
            int sampleRows = Integer.parseInt(props.getProperty("_sample_rows", "20"));

            String segmentField = props.getProperty("segment_field", "");
            Map<String, String> segmentToActiveBranch = parseRedefineSegmentIdMap(props);

            Dataset<Row> raw = spark.read()
                    .format("cobol")
                    .options(toSparkOptions(props))
                    .option("copybook_contents", copybookContent)
                    .load(dataPath.toString());

            FlattenOutcome flattened = flatten(raw);
            Dataset<Row> output = flattened.dataset();
            long rowCount = output.count();
            List<Row> previewRows = output.limit(sampleRows).collectAsList();

            String segmentColumnName = segmentField.isBlank() ? ""
                    : sanitize(segmentField);

            writeSchema(output.schema(), outputDir.resolve("schema.tsv"));
            writeMappings(flattened.mappingSpec(), outputDir.resolve("mapping.tsv"));
            writePreview(output.schema().fieldNames(), previewRows,
                    segmentColumnName, segmentToActiveBranch,
                    outputDir.resolve("preview.tsv"));
            writeMetadata(rowCount, output.schema().fields().length, previewRows.size(), outputDir.resolve("metadata.properties"));
        } finally {
            spark.stop();
        }
    }

    private static Map<String, String> parseRedefineSegmentIdMap(Properties props) {
        Map<String, String> segmentToActiveBranch = new LinkedHashMap<>();
        for (String name : props.stringPropertyNames()) {
            if (!name.startsWith("redefine-segment-id-map:")) continue;
            // Format: "GROUP_NAME => DISCRIMINATOR_VALUE"
            String value = props.getProperty(name, "").trim();
            int arrow = value.indexOf("=>");
            if (arrow < 0) continue;
            String branchName = value.substring(0, arrow).trim();
            String discriminator = value.substring(arrow + 2).trim();
            if (!discriminator.isEmpty() && !branchName.isEmpty()) {
                segmentToActiveBranch.put(discriminator, branchName);
            }
        }
        return segmentToActiveBranch;
    }

    private static Map<String, String> toSparkOptions(Properties props) {
        Map<String, String> options = new LinkedHashMap<>();
        for (String name : props.stringPropertyNames()) {
            if (!name.startsWith("_")) {
                options.put(name, props.getProperty(name));
            }
        }
        return options;
    }

    private static FlattenOutcome flatten(Dataset<Row> dataset) {
        List<Column> columns = new ArrayList<>();
        List<Map<String, String>> mappings = new ArrayList<>();
        Map<String, Integer> aliasCounts = new LinkedHashMap<>();
        for (StructField field : dataset.schema().fields()) {
            collect(field.dataType(), field.name(), field.name(), columns, mappings, aliasCounts);
        }
        Dataset<Row> flattened = dataset.select(columns.toArray(new Column[0]));
        return new FlattenOutcome(flattened, mappings);
    }

    private static void collect(
            DataType dataType,
            String sourcePath,
            String aliasBase,
            List<Column> columns,
            List<Map<String, String>> mappings,
            Map<String, Integer> aliasCounts) {
        if (dataType instanceof StructType structType) {
            for (StructField child : structType.fields()) {
                collect(child.dataType(), sourcePath + "." + child.name(), aliasBase + "_" + child.name(), columns, mappings, aliasCounts);
            }
            return;
        }

        String alias = uniqueAlias(sanitize(aliasBase), aliasCounts);
        String strategy = "primitive_pass_through";
        Column column;
        if (dataType instanceof ArrayType || dataType instanceof MapType) {
            column = functions.to_json(functions.col(sourcePath)).alias(alias);
            strategy = "json_stringify";
        } else {
            column = functions.col(sourcePath).alias(alias);
        }
        columns.add(column);
        Map<String, String> mapping = new LinkedHashMap<>();
        mapping.put("sourcePath", sourcePath);
        mapping.put("outputColumn", alias);
        mapping.put("strategy", strategy);
        mapping.put("dataType", dataType.typeName());
        mappings.add(mapping);
    }

    private static String sanitize(String raw) {
        return raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    private static String uniqueAlias(String alias, Map<String, Integer> aliasCounts) {
        int current = aliasCounts.getOrDefault(alias, 0);
        aliasCounts.put(alias, current + 1);
        return current == 0 ? alias : alias + "_" + current;
    }

    private static void writeSchema(StructType schema, Path target) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            writer.write("name\ttype\tnullable\n");
            for (StructField field : schema.fields()) {
                writer.write(field.name());
                writer.write('\t');
                writer.write(field.dataType().typeName());
                writer.write('\t');
                writer.write(String.valueOf(field.nullable()));
                writer.write('\n');
            }
        }
    }

    private static void writeMappings(List<Map<String, String>> mappings, Path target) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            writer.write("sourcePath\toutputColumn\tstrategy\tdataType\n");
            for (Map<String, String> mapping : mappings) {
                writer.write(mapping.getOrDefault("sourcePath", ""));
                writer.write('\t');
                writer.write(mapping.getOrDefault("outputColumn", ""));
                writer.write('\t');
                writer.write(mapping.getOrDefault("strategy", ""));
                writer.write('\t');
                writer.write(mapping.getOrDefault("dataType", ""));
                writer.write('\n');
            }
        }
    }

    private static void writePreview(String[] headers, List<Row> rows,
                                     String segmentColumnName,
                                     Map<String, String> segmentToActiveBranch,
                                     Path target) throws IOException {
        // Build set of all REDEFINES branch prefixes from the map values
        // e.g. "STATIC-DETAILS" -> "static_details_", "CONTACTS" -> "contacts_"
        Set<String> allBranchPrefixes = new LinkedHashSet<>();
        for (String branch : segmentToActiveBranch.values()) {
            allBranchPrefixes.add(sanitize(branch) + "_");
        }

        // For each discriminator value, compute which column prefixes are INACTIVE
        // e.g. for "C" -> active="STATIC-DETAILS", inactive prefixes = {"contacts_"}
        Map<String, Set<String>> inactivePrefixesBySegment = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : segmentToActiveBranch.entrySet()) {
            String activeBranchPrefix = sanitize(entry.getValue()) + "_";
            Set<String> inactive = new LinkedHashSet<>(allBranchPrefixes);
            inactive.remove(activeBranchPrefix);
            inactivePrefixesBySegment.put(entry.getKey(), inactive);
        }

        int segmentColIdx = -1;
        if (!segmentColumnName.isEmpty()) {
            for (int i = 0; i < headers.length; i++) {
                if (headers[i].equals(segmentColumnName)) {
                    segmentColIdx = i;
                    break;
                }
            }
        }

        try (BufferedWriter writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            writer.write(String.join("\t", headers));
            writer.write('\n');
            for (Row row : rows) {
                String segmentValue = (segmentColIdx >= 0 && row.get(segmentColIdx) != null)
                        ? String.valueOf(row.get(segmentColIdx)).trim() : "";
                Set<String> inactive = inactivePrefixesBySegment.getOrDefault(segmentValue, Set.of());

                for (int i = 0; i < headers.length; i++) {
                    if (i > 0) writer.write('\t');
                    Object value = row.get(i);
                    if (value != null) {
                        boolean suppressed = false;
                        if (!inactive.isEmpty()) {
                            for (String prefix : inactive) {
                                if (headers[i].startsWith(prefix)) {
                                    suppressed = true;
                                    break;
                                }
                            }
                        }
                        if (!suppressed) {
                            writer.write(escape(String.valueOf(value)));
                        }
                    }
                }
                writer.write('\n');
            }
        }
    }

    private static void writeMetadata(long rowCount, int columnCount, int previewRowCount, Path target) throws IOException {
        Properties props = new Properties();
        props.setProperty("rowCount", String.valueOf(rowCount));
        props.setProperty("columnCount", String.valueOf(columnCount));
        props.setProperty("previewRowCount", String.valueOf(previewRowCount));
        try (var out = Files.newOutputStream(target)) {
            props.store(out, "Cobol Discovery Spark Job Metadata");
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static Path resolveSparkFile(String arg) {
        Path direct = Path.of(arg);
        if (Files.exists(direct)) {
            return direct;
        }
        return Path.of(SparkFiles.get(direct.getFileName().toString()));
    }

    private record FlattenOutcome(Dataset<Row> dataset, List<Map<String, String>> mappingSpec) {}
}
