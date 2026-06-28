package com.pulse.cobol.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CobolSparkPreviewServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void executesCobrixPreviewAndFlattensNestedOutput() throws Exception {
        CobolSparkPreviewService service = new CobolSparkPreviewService(
                new CobolCopybookAnalyzer(),
                new CobolFlatteningService()
        );

        String copybook = """
                      01 CUSTOMER-REC.
                         05 HEADER.
                            10 ACCOUNT-NO    PIC X(10).
                            10 REGION        PIC X(2).
                         05 CUSTOMER-NAME    PIC X(20).
                         05 STATUS           PIC X(1).
                """;

        String record1 = pad("0000000001", 10) + pad("US", 2) + pad("ALICE SMITH", 20) + pad("A", 1);
        String record2 = pad("0000000002", 10) + pad("UK", 2) + pad("BOB JONES", 20) + pad("I", 1);

        byte[] bytes = (record1 + record2).getBytes(Charset.forName("Cp037"));
        Path dataFile = tempDir.resolve("customers.ebc");
        Files.write(dataFile, bytes);

        CobolSparkPreviewService.PreviewOutcome outcome = service.execute(
                copybook,
                bytes,
                dataFile,
                Map.of(
                        "record_format", "F",
                        "schema_retention_policy", "collapse_root",
                        "ebcdic_code_page", "cp037"
                ),
                10
        );

        assertEquals("F", outcome.chosenConfig().get("record_format"));
        assertEquals(2L, ((Number) outcome.profilingSummary().get("rowCount")).longValue());
        assertFalse(outcome.previewRows().isEmpty());
        assertTrue(outcome.previewRows().get(0).containsKey("header_account_no"));
        assertEquals("0000000001", outcome.previewRows().get(0).get("header_account_no"));
        assertEquals("US", outcome.previewRows().get(0).get("header_region"));
        assertEquals("ALICE SMITH", String.valueOf(outcome.previewRows().get(0).get("customer_name")).trim());
        assertEquals("A", outcome.previewRows().get(0).get("status"));
        assertEquals(4, outcome.mappingSpec().size());
        assertTrue(outcome.confidenceScore() >= 75.0);
        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) outcome.anomalySummary().get("warnings");
        assertTrue(warnings.isEmpty());
    }

    @Test
    void executesCobrixPreviewForOccursArrayAndStringifiesComplexField() throws Exception {
        CobolSparkPreviewService service = new CobolSparkPreviewService(
                new CobolCopybookAnalyzer(),
                new CobolFlatteningService()
        );

        String copybook = """
                      01 ORDER-REC.
                         05 ORDER-ID          PIC X(4).
                         05 ITEMS OCCURS 2 TIMES.
                            10 ITEM-CODE      PIC X(3).
                """;

        String record = pad("A001", 4) + pad("ABC", 3) + pad("XYZ", 3);
        byte[] bytes = record.getBytes(Charset.forName("Cp037"));
        Path dataFile = tempDir.resolve("orders.ebc");
        Files.write(dataFile, bytes);

        CobolSparkPreviewService.PreviewOutcome outcome = service.execute(
                copybook,
                bytes,
                dataFile,
                Map.of(
                        "record_format", "F",
                        "schema_retention_policy", "collapse_root",
                        "ebcdic_code_page", "cp037"
                ),
                5
        );

        assertEquals(1L, ((Number) outcome.profilingSummary().get("rowCount")).longValue());
        assertTrue(outcome.previewRows().get(0).containsKey("items"));
        assertTrue(String.valueOf(outcome.previewRows().get(0).get("items")).contains("ABC"));
        assertTrue(String.valueOf(outcome.previewRows().get(0).get("items")).contains("XYZ"));
        assertTrue(outcome.mappingSpec().stream()
                .anyMatch(mapping -> "json_stringify".equals(mapping.get("strategy"))));
    }

    @Test
    void executesCobrixPreviewForVariableRecordRdw() throws Exception {
        CobolSparkPreviewService service = new CobolSparkPreviewService(
                new CobolCopybookAnalyzer(),
                new CobolFlatteningService()
        );

        String copybook = """
                      01 EVENT-REC.
                         05 EVENT-ID          PIC X(4).
                         05 EVENT-STATUS      PIC X(1).
                """;

        byte[] record1 = withBigEndianRdw((pad("E001", 4) + pad("A", 1)).getBytes(Charset.forName("Cp037")));
        byte[] record2 = withBigEndianRdw((pad("E002", 4) + pad("I", 1)).getBytes(Charset.forName("Cp037")));
        byte[] bytes = concat(record1, record2);
        Path dataFile = tempDir.resolve("events-rdw.ebc");
        Files.write(dataFile, bytes);

        CobolSparkPreviewService.PreviewOutcome outcome = service.execute(
                copybook,
                bytes,
                dataFile,
                Map.of(
                        "record_format", "V",
                        "is_rdw_big_endian", "true",
                        "schema_retention_policy", "collapse_root",
                        "ebcdic_code_page", "cp037"
                ),
                10
        );

        assertEquals("V", outcome.chosenConfig().get("record_format"));
        assertEquals(2L, ((Number) outcome.profilingSummary().get("rowCount")).longValue());
        assertEquals("E001", String.valueOf(outcome.previewRows().get(0).get("event_id")).trim());
        assertEquals("A", String.valueOf(outcome.previewRows().get(0).get("event_status")).trim());
    }

    @Test
    void executesCobrixPreviewForVariableBlockedRecords() throws Exception {
        CobolSparkPreviewService service = new CobolSparkPreviewService(
                new CobolCopybookAnalyzer(),
                new CobolFlatteningService()
        );

        String copybook = """
                      01 VB-REC.
                         05 VB-ID             PIC X(4).
                         05 VB-STATUS         PIC X(1).
                """;

        byte[] rec1 = withBigEndianPayloadLengthRdw((pad("V001", 4) + pad("A", 1)).getBytes(Charset.forName("Cp037")));
        byte[] rec2 = withBigEndianPayloadLengthRdw((pad("V002", 4) + pad("I", 1)).getBytes(Charset.forName("Cp037")));
        byte[] blockPayload = concat(rec1, rec2);
        byte[] bdw = new byte[] {0x00, 0x12, 0x00, 0x00}; // 18-byte block payload, no adjustment
        byte[] bytes = concat(bdw, blockPayload);
        Path dataFile = tempDir.resolve("events-vb.ebc");
        Files.write(dataFile, bytes);

        CobolSparkPreviewService.PreviewOutcome outcome = service.execute(
                copybook,
                bytes,
                dataFile,
                Map.of(
                        "record_format", "VB",
                        "is_bdw_big_endian", "true",
                        "is_rdw_big_endian", "true",
                        "schema_retention_policy", "collapse_root",
                        "ebcdic_code_page", "cp037"
                ),
                10
        );

        assertEquals("VB", outcome.chosenConfig().get("record_format"));
        assertEquals(2L, ((Number) outcome.profilingSummary().get("rowCount")).longValue());
        assertEquals("V001", String.valueOf(outcome.previewRows().get(0).get("vb_id")).trim());
        assertEquals("I", String.valueOf(outcome.previewRows().get(1).get("vb_status")).trim());
    }

    @Test
    void executesCobrixPreviewForFixedBlockedRecords() throws Exception {
        CobolSparkPreviewService service = new CobolSparkPreviewService(
                new CobolCopybookAnalyzer(),
                new CobolFlatteningService()
        );

        String copybook = """
                      01 BLOCK-REC.
                         05 BLOCK-ID          PIC X(4).
                         05 BLOCK-STATUS      PIC X(1).
                """;

        byte[] bytes = (pad("B001", 4) + pad("Y", 1) + pad("B002", 4) + pad("N", 1))
                .getBytes(Charset.forName("Cp037"));
        Path dataFile = tempDir.resolve("blocks.ebc");
        Files.write(dataFile, bytes);

        CobolSparkPreviewService.PreviewOutcome outcome = service.execute(
                copybook,
                bytes,
                dataFile,
                Map.of(
                        "record_format", "FB",
                        "record_length", "5",
                        "records_per_block", "2",
                        "schema_retention_policy", "collapse_root",
                        "ebcdic_code_page", "cp037"
                ),
                10
        );

        assertEquals("FB", outcome.chosenConfig().get("record_format"));
        assertEquals(2L, ((Number) outcome.profilingSummary().get("rowCount")).longValue());
        assertEquals("B001", String.valueOf(outcome.previewRows().get(0).get("block_id")).trim());
        assertEquals("N", String.valueOf(outcome.previewRows().get(1).get("block_status")).trim());
    }

    @Test
    void expandsRedefineSegmentMapIntoCobrixOptionFormat() {
        CobolSparkPreviewService service = new CobolSparkPreviewService(
                new CobolCopybookAnalyzer(),
                new CobolFlatteningService()
        );

        Map<String, String> expanded = new LinkedHashMap<>();
        service.appendCobrixOptions(expanded, Map.of(
                "record_format", "F",
                "segment_field", "SEGMENT-ID",
                "redefine_segment_id_map", Map.of(
                        "C", "COMPANY",
                        "P", "PERSON",
                        "B", "PO-BOX"
                )
        ));

        assertEquals("F", expanded.get("record_format"));
        assertEquals("SEGMENT-ID", expanded.get("segment_field"));
        assertTrue(expanded.containsValue("COMPANY => C"));
        assertTrue(expanded.containsValue("PERSON => P"));
        assertTrue(expanded.containsValue("PO-BOX => B"));
    }

    @Test
    void executesCobrixPreviewForPackedDecimal() throws Exception {
        CobolSparkPreviewService service = new CobolSparkPreviewService(
                new CobolCopybookAnalyzer(),
                new CobolFlatteningService()
        );

        String copybook = """
                      01 AMOUNT-REC.
                         05 ACCOUNT-ID        PIC X(4).
                         05 AMOUNT            PIC S9(3)V99 COMP-3.
                """;

        byte[] payload = concat(
                pad("A001", 4).getBytes(Charset.forName("Cp037")),
                new byte[] {0x12, 0x34, 0x5C}
        );
        Path dataFile = tempDir.resolve("packed.ebc");
        Files.write(dataFile, payload);

        CobolSparkPreviewService.PreviewOutcome outcome = service.execute(
                copybook,
                payload,
                dataFile,
                Map.of(
                        "record_format", "F",
                        "schema_retention_policy", "collapse_root",
                        "ebcdic_code_page", "cp037"
                ),
                5
        );

        assertEquals(1L, ((Number) outcome.profilingSummary().get("rowCount")).longValue());
        assertEquals("A001", String.valueOf(outcome.previewRows().get(0).get("account_id")).trim());
        assertTrue(String.valueOf(outcome.previewRows().get(0).get("amount")).contains("123.45"));
    }

    @Test
    void executesCobrixPreviewForMultisegmentRedefines() throws Exception {
        CobolSparkPreviewService service = new CobolSparkPreviewService(
                new CobolCopybookAnalyzer(),
                new CobolFlatteningService()
        );

        String copybook = """
                      01 COMPANY-REC.
                         05 SEGMENT-ID        PIC X(1).
                         05 STATIC-DETAILS.
                            10 COMPANY-ID     PIC X(3).
                            10 COMPANY-NAME   PIC X(5).
                         05 CONTACTS REDEFINES STATIC-DETAILS.
                            10 COMPANY-ID-C   PIC X(3).
                            10 CONTACT-NAME   PIC X(5).
                """;

        byte[] bytes = (pad("C", 1) + pad("001", 3) + pad("ACME", 5)
                + pad("P", 1) + pad("001", 3) + pad("ALAN", 5)).getBytes(Charset.forName("Cp037"));
        Path dataFile = tempDir.resolve("multisegment.ebc");
        Files.write(dataFile, bytes);

        CobolSparkPreviewService.PreviewOutcome outcome = service.execute(
                copybook,
                bytes,
                dataFile,
                Map.of(
                        "record_format", "F",
                        "record_length", "9",
                        "schema_retention_policy", "collapse_root",
                        "segment_field", "SEGMENT-ID",
                        "redefine_segment_id_map:1", "STATIC-DETAILS => C",
                        "redefine_segment_id_map:2", "CONTACTS => P",
                        "ebcdic_code_page", "cp037"
                ),
                10
        );

        System.out.println("MULTISEGMENT PREVIEW: " + outcome.previewRows());

        assertEquals(2L, ((Number) outcome.profilingSummary().get("rowCount")).longValue());
        assertEquals("C", String.valueOf(outcome.previewRows().get(0).get("segment_id")).trim());
        assertEquals("001", String.valueOf(outcome.previewRows().get(0).get("static_details_company_id")).trim());
        assertEquals("P", String.valueOf(outcome.previewRows().get(1).get("segment_id")).trim());
        assertEquals("ALAN", String.valueOf(outcome.previewRows().get(1).get("contacts_contact_name")).trim());
    }

    @Test
    void executesCobrixPreviewForRecordLengthField() throws Exception {
        CobolSparkPreviewService service = new CobolSparkPreviewService(
                new CobolCopybookAnalyzer(),
                new CobolFlatteningService()
        );

        String copybook = """
                      01 LEN-REC.
                         05 REC-LEN           PIC 9(1).
                         05 REC-ID            PIC X(4).
                """;

        byte[] bytes = ("5A001" + "5A002").getBytes(Charset.forName("Cp037"));
        Path dataFile = tempDir.resolve("record-length-field.ebc");
        Files.write(dataFile, bytes);

        CobolSparkPreviewService.PreviewOutcome outcome = service.execute(
                copybook,
                bytes,
                dataFile,
                Map.of(
                        "record_format", "F",
                        "record_length_field", "REC-LEN",
                        "schema_retention_policy", "collapse_root",
                        "ebcdic_code_page", "cp037"
                ),
                10
        );

        assertEquals(2L, ((Number) outcome.profilingSummary().get("rowCount")).longValue());
        assertEquals("A001", String.valueOf(outcome.previewRows().get(0).get("rec_id")).trim());
        assertEquals("A002", String.valueOf(outcome.previewRows().get(1).get("rec_id")).trim());
    }

    @Test
    void executesCobrixPreviewForTextModeD() throws Exception {
        CobolSparkPreviewService service = new CobolSparkPreviewService(
                new CobolCopybookAnalyzer(),
                new CobolFlatteningService()
        );

        String copybook = """
                      01 TXT-REC.
                         05 TXT-ID            PIC X(4).
                         05 TXT-STATUS        PIC X(1).
                """;

        byte[] bytes = ("T001A\nT002I\n").getBytes(Charset.forName("US-ASCII"));
        Path dataFile = tempDir.resolve("text-d-mode.dat");
        Files.write(dataFile, bytes);

        CobolSparkPreviewService.PreviewOutcome outcome = service.execute(
                copybook,
                bytes,
                dataFile,
                Map.of(
                        "record_format", "D",
                        "encoding", "ascii",
                        "schema_retention_policy", "collapse_root"
                ),
                10
        );

        assertEquals(2L, ((Number) outcome.profilingSummary().get("rowCount")).longValue());
        assertEquals("T001", String.valueOf(outcome.previewRows().get(0).get("txt_id")).trim());
        assertEquals("I", String.valueOf(outcome.previewRows().get(1).get("txt_status")).trim());
    }

    private String pad(String value, int size) {
        return String.format("%1$-" + size + "s", value);
    }

    private byte[] withBigEndianRdw(byte[] payload) {
        int totalLength = payload.length + 4;
        byte[] out = new byte[payload.length + 4];
        out[0] = (byte) ((totalLength >> 8) & 0xFF);
        out[1] = (byte) (totalLength & 0xFF);
        out[2] = 0x00;
        out[3] = 0x00;
        System.arraycopy(payload, 0, out, 4, payload.length);
        return out;
    }

    private byte[] withBigEndianPayloadLengthRdw(byte[] payload) {
        int payloadLength = payload.length;
        byte[] out = new byte[payload.length + 4];
        out[0] = (byte) ((payloadLength >> 8) & 0xFF);
        out[1] = (byte) (payloadLength & 0xFF);
        out[2] = 0x00;
        out[3] = 0x00;
        System.arraycopy(payload, 0, out, 4, payload.length);
        return out;
    }

    private byte[] concat(byte[] first, byte[] second) {
        byte[] out = new byte[first.length + second.length];
        System.arraycopy(first, 0, out, 0, first.length);
        System.arraycopy(second, 0, out, first.length, second.length);
        return out;
    }
}
