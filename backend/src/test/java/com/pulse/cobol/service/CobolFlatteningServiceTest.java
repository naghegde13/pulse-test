package com.pulse.cobol.service;

import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CobolFlatteningServiceTest {

    private static SparkSession spark;
    private final CobolFlatteningService service = new CobolFlatteningService();

    @BeforeAll
    static void startSpark() {
        spark = SparkSession.builder()
                .appName("flattening-test")
                .master("local[*]")
                .config("spark.ui.enabled", "false")
                .config("spark.driver.host", "127.0.0.1")
                .config("spark.sql.session.timeZone", "UTC")
                .getOrCreate();
    }

    @AfterAll
    static void stopSpark() {
        if (spark != null) {
            spark.stop();
        }
    }

    @Test
    void flatten_flattensNestedStructToSnakeCaseColumns() {
        StructType schema = new StructType(new StructField[]{
                DataTypes.createStructField("HEADER", new StructType(new StructField[]{
                        DataTypes.createStructField("ACCOUNT_NO", DataTypes.StringType, true),
                        DataTypes.createStructField("REGION", DataTypes.StringType, true)
                }), true)
        });
        List<Row> rows = List.of(
                RowFactory.create(RowFactory.create("12345", "US"))
        );
        Dataset<Row> df = spark.createDataFrame(rows, schema);

        CobolFlatteningService.FlattenOutcome outcome = service.flatten(df);
        Dataset<Row> result = outcome.dataset();

        List<String> cols = Arrays.asList(result.columns());
        assertTrue(cols.contains("header_account_no"), "expected column header_account_no");
        assertTrue(cols.contains("header_region"), "expected column header_region");

        Row first = result.collectAsList().get(0);
        assertEquals("12345", first.getAs("header_account_no"));
        assertEquals("US", first.getAs("header_region"));
    }

    @Test
    void flatten_preservesPrimitiveTopLevelFields() {
        StructType schema = new StructType(new StructField[]{
                DataTypes.createStructField("STATUS", DataTypes.StringType, true)
        });
        List<Row> rows = List.of(RowFactory.create("ACTIVE"));
        Dataset<Row> df = spark.createDataFrame(rows, schema);

        CobolFlatteningService.FlattenOutcome outcome = service.flatten(df);
        Dataset<Row> result = outcome.dataset();

        List<String> cols = Arrays.asList(result.columns());
        assertTrue(cols.contains("status"), "expected column status");

        assertEquals("ACTIVE", result.collectAsList().get(0).getAs("status"));

        Map<String, Object> entry = outcome.mappingSpec().get(0);
        assertEquals("primitive_pass_through", entry.get("strategy"));
    }

    @Test
    void flatten_jsonStringifiesArrayColumns() {
        StructType schema = new StructType(new StructField[]{
                DataTypes.createStructField("TAGS",
                        DataTypes.createArrayType(DataTypes.StringType), true)
        });
        List<Row> rows = List.of(RowFactory.create(Arrays.asList("alpha", "beta")));
        Dataset<Row> df = spark.createDataFrame(rows, schema);

        CobolFlatteningService.FlattenOutcome outcome = service.flatten(df);
        Dataset<Row> result = outcome.dataset();

        String value = result.collectAsList().get(0).getAs("tags");
        assertTrue(value.contains("alpha"), "JSON should contain alpha");
        assertTrue(value.contains("beta"), "JSON should contain beta");

        assertTrue(outcome.mappingSpec().stream()
                .anyMatch(m -> "json_stringify".equals(m.get("strategy"))));
    }

    @Test
    void flatten_jsonStringifiesMapColumns() {
        StructType schema = new StructType(new StructField[]{
                DataTypes.createStructField("ATTRS",
                        DataTypes.createMapType(DataTypes.StringType, DataTypes.StringType), true)
        });
        Map<String, String> mapValue = new HashMap<>();
        mapValue.put("color", "red");
        List<Row> rows = List.of(RowFactory.create(mapValue));
        Dataset<Row> df = spark.createDataFrame(rows, schema);

        CobolFlatteningService.FlattenOutcome outcome = service.flatten(df);
        Dataset<Row> result = outcome.dataset();

        String value = result.collectAsList().get(0).getAs("attrs");
        assertTrue(value.contains("color"), "JSON should contain key 'color'");
        assertTrue(value.contains("red"), "JSON should contain value 'red'");

        assertTrue(outcome.mappingSpec().stream()
                .anyMatch(m -> "json_stringify".equals(m.get("strategy"))));
    }

    @Test
    void flatten_handlesDoublyNestedStructs() {
        StructType innerStruct = new StructType(new StructField[]{
                DataTypes.createStructField("VALUE", DataTypes.StringType, true)
        });
        StructType outerStruct = new StructType(new StructField[]{
                DataTypes.createStructField("INNER", innerStruct, true)
        });
        StructType schema = new StructType(new StructField[]{
                DataTypes.createStructField("OUTER", outerStruct, true)
        });
        List<Row> rows = List.of(
                RowFactory.create(RowFactory.create(RowFactory.create("deep")))
        );
        Dataset<Row> df = spark.createDataFrame(rows, schema);

        CobolFlatteningService.FlattenOutcome outcome = service.flatten(df);
        Dataset<Row> result = outcome.dataset();

        List<String> cols = Arrays.asList(result.columns());
        assertTrue(cols.contains("outer_inner_value"),
                "expected fully flattened column outer_inner_value, got: " + cols);

        assertEquals("deep", result.collectAsList().get(0).getAs("outer_inner_value"));
    }

    @Test
    void flatten_deduplicatesCollisionAliasesWithNumericSuffix() {
        // Top-level "A_B" and struct "A" with field "B" both sanitize to "a_b"
        StructType schema = new StructType(new StructField[]{
                DataTypes.createStructField("A_B", DataTypes.StringType, true),
                DataTypes.createStructField("A", new StructType(new StructField[]{
                        DataTypes.createStructField("B", DataTypes.StringType, true)
                }), true)
        });
        List<Row> rows = List.of(
                RowFactory.create("first", RowFactory.create("second"))
        );
        Dataset<Row> df = spark.createDataFrame(rows, schema);

        CobolFlatteningService.FlattenOutcome outcome = service.flatten(df);
        Dataset<Row> result = outcome.dataset();

        List<String> cols = Arrays.asList(result.columns());
        assertTrue(cols.contains("a_b"), "expected original alias a_b");
        assertTrue(cols.contains("a_b_1"), "expected deduplicated alias a_b_1");

        Row first = result.collectAsList().get(0);
        assertEquals("first", first.getAs("a_b"));
        assertEquals("second", first.getAs("a_b_1"));
    }

    @Test
    void flatten_mappingSpecContainsCorrectFields() {
        StructType schema = new StructType(new StructField[]{
                DataTypes.createStructField("NAME", DataTypes.StringType, true),
                DataTypes.createStructField("SCORES",
                        DataTypes.createArrayType(DataTypes.IntegerType), true)
        });
        List<Row> rows = List.of(RowFactory.create("Alice", Arrays.asList(90, 85)));
        Dataset<Row> df = spark.createDataFrame(rows, schema);

        CobolFlatteningService.FlattenOutcome outcome = service.flatten(df);

        // Every mapping entry must have all four keys
        for (Map<String, Object> entry : outcome.mappingSpec()) {
            assertTrue(entry.containsKey("sourcePath"), "missing sourcePath");
            assertTrue(entry.containsKey("outputColumn"), "missing outputColumn");
            assertTrue(entry.containsKey("dataType"), "missing dataType");
            assertTrue(entry.containsKey("strategy"), "missing strategy");
        }

        // Primitive field
        Map<String, Object> nameEntry = outcome.mappingSpec().stream()
                .filter(m -> "NAME".equals(m.get("sourcePath")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No mapping for NAME"));
        assertEquals("name", nameEntry.get("outputColumn"));
        assertEquals("string", nameEntry.get("dataType"));
        assertEquals("primitive_pass_through", nameEntry.get("strategy"));

        // Array field
        Map<String, Object> scoresEntry = outcome.mappingSpec().stream()
                .filter(m -> "SCORES".equals(m.get("sourcePath")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No mapping for SCORES"));
        assertEquals("scores", scoresEntry.get("outputColumn"));
        assertEquals("array", scoresEntry.get("dataType"));
        assertEquals("json_stringify", scoresEntry.get("strategy"));
    }

    @Test
    void flatten_flattenSpecContainsModeAndColumns() {
        StructType schema = new StructType(new StructField[]{
                DataTypes.createStructField("ID", DataTypes.StringType, true)
        });
        List<Row> rows = List.of(RowFactory.create("123"));
        Dataset<Row> df = spark.createDataFrame(rows, schema);

        CobolFlatteningService.FlattenOutcome outcome = service.flatten(df);

        assertEquals("flatten_structs_jsonify_complex", outcome.flattenSpec().get("mode"));
        assertNotNull(outcome.flattenSpec().get("columns"), "flattenSpec must contain 'columns'");
        assertTrue(outcome.flattenSpec().get("columns") instanceof List,
                "columns must be a List");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> columns =
                (List<Map<String, Object>>) outcome.flattenSpec().get("columns");
        assertEquals(outcome.mappingSpec(), columns,
                "flattenSpec.columns should be the same list as mappingSpec");
        assertEquals(1, columns.size());
    }
}
