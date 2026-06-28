package com.pulse.cobol.runner;

import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class CobolDiscoverySparkJobRunnerTest {

    private static final Charset EBCDIC = Charset.forName("Cp037");

    private static final String SIMPLE_COPYBOOK = """
                  01 TEST-REC.
                     05 TEST-ID       PIC X(4).
                     05 TEST-STATUS   PIC X(1).
            """;

    @TempDir
    Path tempDir;

    @BeforeEach
    void ensureSpark() {
        SparkSession.builder()
                .appName("runner-test")
                .master("local[*]")
                .config("spark.ui.enabled", "false")
                .config("spark.driver.host", "127.0.0.1")
                .config("spark.sql.session.timeZone", "UTC")
                .getOrCreate();
    }

    @AfterEach
    void stopSpark() {
        scala.Option<SparkSession> active = SparkSession.getActiveSession();
        if (active.isDefined()) {
            active.get().stop();
        }
    }

    // ── Tests ──

    @Test
    void main_writesSchemaToTsv() throws Exception {
        Prepared p = prepareSimple();
        callMain(p);

        Path schemaFile = p.outputDir.resolve("schema.tsv");
        assertTrue(Files.exists(schemaFile), "schema.tsv should exist");
        List<String> lines = Files.readAllLines(schemaFile, StandardCharsets.UTF_8);
        assertEquals("name\ttype\tnullable", lines.get(0));
        assertTrue(lines.size() >= 3, "schema should have header + at least 2 field rows");
        assertTrue(lines.get(1).startsWith("test_id\t"));
        assertTrue(lines.get(2).startsWith("test_status\t"));
    }

    @Test
    void main_writesMappingToTsv() throws Exception {
        Prepared p = prepareSimple();
        callMain(p);

        Path mappingFile = p.outputDir.resolve("mapping.tsv");
        assertTrue(Files.exists(mappingFile), "mapping.tsv should exist");
        List<String> lines = Files.readAllLines(mappingFile, StandardCharsets.UTF_8);
        assertEquals("sourcePath\toutputColumn\tstrategy\tdataType", lines.get(0));
        assertTrue(lines.size() >= 3, "mapping should have header + at least 2 rows");
        assertTrue(lines.stream().anyMatch(l -> l.contains("test_id") && l.contains("primitive_pass_through")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("test_status") && l.contains("primitive_pass_through")));
    }

    @Test
    void main_writesPreviewRowsToTsv() throws Exception {
        Prepared p = prepareSimple();
        callMain(p);

        Path previewFile = p.outputDir.resolve("preview.tsv");
        assertTrue(Files.exists(previewFile), "preview.tsv should exist");
        List<String> lines = Files.readAllLines(previewFile, StandardCharsets.UTF_8);
        assertTrue(lines.size() >= 3, "preview should have header + 2 data rows");
        String header = lines.get(0);
        assertTrue(header.contains("test_id"));
        assertTrue(header.contains("test_status"));
        assertTrue(lines.get(1).contains("AAA1"));
        assertTrue(lines.get(1).contains("A"));
        assertTrue(lines.get(2).contains("BBB2"));
        assertTrue(lines.get(2).contains("I"));
    }

    @Test
    void main_writesMetadataProperties() throws Exception {
        Prepared p = prepareSimple();
        callMain(p);

        Path metadataFile = p.outputDir.resolve("metadata.properties");
        assertTrue(Files.exists(metadataFile), "metadata.properties should exist");
        Properties props = new Properties();
        try (var in = Files.newInputStream(metadataFile)) {
            props.load(in);
        }
        assertEquals("2", props.getProperty("rowCount"));
        assertEquals("2", props.getProperty("columnCount"));
        assertEquals("2", props.getProperty("previewRowCount"));
    }

    @Test
    void main_escapesTabsAndNewlinesInPreviewValues() throws Exception {
        // Verify structural integrity of the preview TSV, which relies on
        // escape() correctly neutralising any tab/newline characters in
        // field values.  Each data row must have the same number of
        // tab-separated columns as the header; any unescaped tabs in a
        // value would produce extra columns, and unescaped newlines would
        // produce extra lines.
        Prepared p = prepareSimple();
        callMain(p);

        Path previewFile = p.outputDir.resolve("preview.tsv");
        List<String> lines = Files.readAllLines(previewFile, StandardCharsets.UTF_8);
        assertTrue(lines.size() >= 2, "preview should have header + at least 1 data row");

        String[] headerCols = lines.get(0).split("\t", -1);
        int expectedCols = headerCols.length;
        assertTrue(expectedCols >= 2, "header should have at least 2 columns");

        // Every data row must match the header's column count.
        for (int i = 1; i < lines.size(); i++) {
            String[] dataCols = lines.get(i).split("\t", -1);
            assertEquals(expectedCols, dataCols.length,
                    "Row " + i + " should have " + expectedCols + " tab-separated columns");
        }

        // No raw newlines in values — readAllLines would have split them.
        assertEquals(3, lines.size(), "preview with 2 records should produce exactly 3 lines");
    }

    @Test
    void main_handlesFixedRecordFormat() throws Exception {
        Prepared p = prepareSimple();
        callMain(p);

        Properties meta = loadMetadata(p.outputDir);
        assertEquals("2", meta.getProperty("rowCount"));

        Path previewFile = p.outputDir.resolve("preview.tsv");
        List<String> lines = Files.readAllLines(previewFile, StandardCharsets.UTF_8);
        assertEquals(3, lines.size(), "preview should have header + 2 rows");

        String[] row1 = lines.get(1).split("\t", -1);
        assertEquals("AAA1", row1[0].trim());
        assertEquals("A", row1[1].trim());

        String[] row2 = lines.get(2).split("\t", -1);
        assertEquals("BBB2", row2[0].trim());
        assertEquals("I", row2[1].trim());
    }

    @Test
    void main_handlesMultipleFields() throws Exception {
        String multiCopybook = """
                      01 MULTI-REC.
                         05 FIELD-A       PIC X(3).
                         05 FIELD-B       PIC X(2).
                         05 FIELD-C       PIC X(4).
                """;
        byte[] data = (pad("ABC", 3) + pad("XY", 2) + pad("1234", 4))
                .getBytes(EBCDIC);

        Path copybook = writeFile("multi.cob", multiCopybook);
        Path dataFile = writeFile("multi.ebc", data);
        Path options = writeDefaultOptions();
        Path outputDir = tempDir.resolve("output");

        callMain(copybook, dataFile, options, outputDir);

        // Verify schema has 3 fields
        List<String> schemaLines = Files.readAllLines(
                outputDir.resolve("schema.tsv"), StandardCharsets.UTF_8);
        assertEquals(4, schemaLines.size(), "schema should have header + 3 fields");
        assertTrue(schemaLines.get(1).startsWith("field_a\t"));
        assertTrue(schemaLines.get(2).startsWith("field_b\t"));
        assertTrue(schemaLines.get(3).startsWith("field_c\t"));

        // Verify preview has correct values
        List<String> previewLines = Files.readAllLines(
                outputDir.resolve("preview.tsv"), StandardCharsets.UTF_8);
        assertEquals(2, previewLines.size(), "preview should have header + 1 row");
        String[] values = previewLines.get(1).split("\t", -1);
        assertEquals(3, values.length);
        assertEquals("ABC", values[0].trim());
        assertEquals("XY", values[1].trim());
        assertEquals("1234", values[2].trim());

        // Verify metadata
        Properties meta = loadMetadata(outputDir);
        assertEquals("1", meta.getProperty("rowCount"));
        assertEquals("3", meta.getProperty("columnCount"));
    }

    @Test
    void main_failsWithIncorrectArgCount() {
        assertThrows(IllegalArgumentException.class, () ->
                CobolDiscoverySparkJobRunner.main(new String[]{"one", "two", "three"})
        );
        assertThrows(IllegalArgumentException.class, () ->
                CobolDiscoverySparkJobRunner.main(new String[]{})
        );
    }

    // ── Helpers ──

    private record Prepared(Path copybook, Path dataFile, Path options, Path outputDir) {}

    private Prepared prepareSimple() throws IOException {
        byte[] data = (pad("AAA1", 4) + pad("A", 1)
                + pad("BBB2", 4) + pad("I", 1)).getBytes(EBCDIC);
        Path copybook = writeFile("test.cob", SIMPLE_COPYBOOK);
        Path dataFile = writeFile("test.ebc", data);
        Path options = writeDefaultOptions();
        Path outputDir = tempDir.resolve("output");
        return new Prepared(copybook, dataFile, options, outputDir);
    }

    private void callMain(Prepared p) throws Exception {
        callMain(p.copybook, p.dataFile, p.options, p.outputDir);
    }

    private void callMain(Path copybook, Path data, Path options, Path outputDir) throws Exception {
        CobolDiscoverySparkJobRunner.main(new String[]{
                copybook.toString(), data.toString(), options.toString(), outputDir.toString()
        });
    }

    private Path writeFile(String filename, String content) throws IOException {
        Path path = tempDir.resolve(filename);
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path;
    }

    private Path writeFile(String filename, byte[] content) throws IOException {
        Path path = tempDir.resolve(filename);
        Files.write(path, content);
        return path;
    }

    private Path writeDefaultOptions() throws IOException {
        String content = "record_format=F\n"
                + "schema_retention_policy=collapse_root\n"
                + "ebcdic_code_page=cp037\n"
                + "_sample_rows=10\n";
        return writeFile("options.properties", content);
    }

    private Properties loadMetadata(Path outputDir) throws IOException {
        Properties props = new Properties();
        try (var in = Files.newInputStream(outputDir.resolve("metadata.properties"))) {
            props.load(in);
        }
        return props;
    }

    private String pad(String value, int size) {
        return String.format("%1$-" + size + "s", value);
    }
}
