package com.pulse.cobol.spark;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.spark.sql.Column;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.functions;
import org.apache.spark.sql.types.ArrayType;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.MapType;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Standalone Spark process for EBCDIC/Cobrix parsing.
 * Runs in a separate JVM with ANTLR 4.9.3 to avoid classpath conflict with Hibernate.
 *
 * Args: <copybookFile> <dataFile> <optionsJson> <sampleRows>
 * Outputs JSON result to stdout.
 */
public class SparkRunner {

    public static void main(String[] args) {
        // Capture real stdout for protocol, redirect System.out to stderr
        // so Spark log noise doesn't pollute our JSON protocol
        java.io.PrintStream protocol = System.out;
        System.setOut(System.err);

        ObjectMapper mapper = new ObjectMapper();
        SparkSession spark = null;

        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(System.in))) {
            System.err.println("SparkRunner daemon starting...");

            spark = SparkSession.builder()
                    .appName("pulse-cobol-discovery")
                    .master("local[*]")
                    .config("spark.ui.enabled", "false")
                    .config("spark.sql.shuffle.partitions", "4")
                    .config("spark.driver.host", "127.0.0.1")
                    .config("spark.sql.session.timeZone", "UTC")
                    .getOrCreate();

            System.err.println("SparkRunner daemon ready (Spark initialized)");
            protocol.println("SPARK_READY");
            protocol.flush();

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> cmd = mapper.readValue(line, Map.class);
                    String copybookContent = Files.readString(Path.of((String) cmd.get("copybookFile")));
                    String dataFilePath = (String) cmd.get("dataFile");
                    @SuppressWarnings("unchecked")
                    Map<String, String> options = (Map<String, String>) cmd.get("options");
                    int sampleRows = (Integer) cmd.getOrDefault("sampleRows", 50);

                    Map<String, Object> result = runCobrix(spark, copybookContent, dataFilePath, options, sampleRows);
                    protocol.println("SPARK_RESULT_JSON:" + mapper.writeValueAsString(result));
                    protocol.flush();
                } catch (Exception e) {
                    Map<String, Object> err = new LinkedHashMap<>();
                    err.put("status", "ERROR");
                    err.put("error", e.getClass().getName() + ": " + e.getMessage());
                    protocol.println("SPARK_RESULT_JSON:" + mapper.writeValueAsString(err));
                    protocol.flush();
                    System.err.println("SparkRunner error: " + e.getMessage());
                    e.printStackTrace(System.err);
                }
            }
        } catch (Exception e) {
            System.err.println("SparkRunner FATAL: " + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(2);
        } finally {
            if (spark != null) spark.stop();
        }
    }

    private static Map<String, Object> runCobrix(SparkSession spark, String copybookContent,
                                                   String dataFilePath, Map<String, String> options,
                                                   int sampleRows) {
        Dataset<Row> raw = spark.read()
                .format("cobol")
                .options(options)
                .option("copybook_contents", copybookContent)
                .load(dataFilePath);

        List<Column> columns = new ArrayList<>();
        List<Map<String, Object>> mapping = new ArrayList<>();
        Map<String, Integer> aliasCounts = new LinkedHashMap<>();
        for (StructField field : raw.schema().fields()) {
            collectColumns(field.dataType(), field.name(), field.name(), columns, mapping, aliasCounts);
        }
        Dataset<Row> flattened = raw.select(columns.toArray(new Column[0]));

        List<Row> collected = flattened.limit(sampleRows).collectAsList();
        List<Map<String, Object>> previewRows = new ArrayList<>();
        for (Row row : collected) {
            Map<String, Object> item = new LinkedHashMap<>();
            String[] fieldNames = row.schema().fieldNames();
            for (int i = 0; i < fieldNames.length; i++) {
                Object value = row.get(i);
                item.put(fieldNames[i], value == null ? null : String.valueOf(value));
            }
            previewRows.add(item);
        }

        List<Map<String, Object>> fields = new ArrayList<>();
        for (StructField f : flattened.schema().fields()) {
            Map<String, Object> fm = new LinkedHashMap<>();
            fm.put("name", f.name());
            fm.put("type", f.dataType().typeName());
            fm.put("nullable", f.nullable());
            fields.add(fm);
        }

        long rowCount = flattened.count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("previewRows", previewRows);
        result.put("fields", fields);
        result.put("mapping", mapping);
        result.put("rowCount", rowCount);
        result.put("flattenSpec", Map.of("mode", "flatten_structs_jsonify_complex", "columns", mapping));
        return result;
    }

    private static void collectColumns(DataType dataType, String sourcePath, String aliasBase,
                                        List<Column> columns, List<Map<String, Object>> mapping,
                                        Map<String, Integer> aliasCounts) {
        if (dataType instanceof StructType structType) {
            for (StructField child : structType.fields()) {
                collectColumns(child.dataType(), sourcePath + "." + child.name(),
                        aliasBase + "_" + child.name(), columns, mapping, aliasCounts);
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
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("sourcePath", sourcePath);
        row.put("outputColumn", alias);
        row.put("dataType", dataType.typeName());
        row.put("strategy", strategy);
        mapping.add(row);
    }

    private static String sanitize(String raw) {
        return raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    private static String uniqueAlias(String alias, Map<String, Integer> aliasCounts) {
        int current = aliasCounts.getOrDefault(alias, 0);
        aliasCounts.put(alias, current + 1);
        return current == 0 ? alias : alias + "_" + current;
    }
}
