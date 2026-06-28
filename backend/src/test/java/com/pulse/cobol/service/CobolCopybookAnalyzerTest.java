package com.pulse.cobol.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CobolCopybookAnalyzerTest {

    private final CobolCopybookAnalyzer analyzer = new CobolCopybookAnalyzer();

    @Test
    void detectsOccursDependingOnAndRedefines() {
        String copybook = """
                01 CUSTOMER-ROOT.
                   05 RECORD-TYPE            PIC X(1).
                   05 DETAIL-COUNT           PIC 9(2).
                   05 CUSTOMER-DETAILS.
                      10 CUSTOMER-ID         PIC X(10).
                      10 BALANCE             PIC S9(5)V99 COMP-3.
                   05 DETAIL-LIST OCCURS 0 TO 10 TIMES DEPENDING ON DETAIL-COUNT.
                      10 DETAIL-ID           PIC X(4).
                01 CUSTOMER-ALT REDEFINES CUSTOMER-ROOT.
                   05 ALT-FIELD              PIC X(12).
                """;

        byte[] bytes = new byte[] {0, 48, 0, 0, 65, 66, 67, 68};
        CobolCopybookAnalyzer.Analysis analysis = analyzer.analyze(copybook, bytes);

        assertTrue((Boolean) analysis.summary().get("hasOccurs"));
        assertTrue((Boolean) analysis.summary().get("hasOccursDependingOn"));
        assertTrue((Boolean) analysis.summary().get("hasRedefines"));
        assertTrue((Boolean) analysis.summary().get("hasComp3"));
        assertEquals("collapse_root", analysis.baseOptions().get("schema_retention_policy"));
        assertEquals("true", analysis.baseOptions().get("variable_size_occurs"));
        assertFalse(analysis.candidateOptions().isEmpty());
    }

    @Test
    void preservesFixedDefaultCandidateWhenNoHeadersDetected() {
        String copybook = "01 ROOT. 05 FIELD-A PIC X(5).";
        byte[] bytes = "SIMPLE".getBytes(StandardCharsets.UTF_8);

        CobolCopybookAnalyzer.Analysis analysis = analyzer.analyze(copybook, bytes);

        assertEquals("fixed-default", analysis.candidateOptions().get(0).get("_candidate_label"));
    }

    @Test
    void detectsVariableRecordHeaderCandidates() {
        String copybook = """
                01 ROOT-REC.
                   05 FIELD-A PIC X(4).
                """;
        byte[] bytes = new byte[] {0x00, 0x20, 0x00, 0x00, 0x40, 0x40, 0x40, 0x40};

        CobolCopybookAnalyzer.Analysis analysis = analyzer.analyze(copybook, bytes);

        assertTrue(analysis.candidateOptions().stream()
                .anyMatch(candidate -> "V".equals(candidate.get("record_format"))));
    }

    @Test
    void validatesWellFormedCopybookSyntax() {
        String copybook = """
                       01  ENTITY.
                           05  SEGMENT-ID        PIC X(1).
                           05  COMPANY.
                              10  COMPANY-NAME      PIC X(20).
                              10  ADDRESS           PIC X(30).
                              10  TAXPAYER          PIC X(8).
                           05  PERSON REDEFINES COMPANY.
                              10  FIRST-NAME        PIC X(16).
                              10  LAST-NAME         PIC X(16).
                              10  ADDRESS           PIC X(20).
                              10  PHONE-NUM         PIC X(11).
                """;

        CobolCopybookAnalyzer.SyntaxValidation validation = analyzer.validateSyntax(copybook);

        assertTrue(validation.valid(), validation.errorMessage());
        assertTrue(validation.errorMessage().isBlank());
    }

    @Test
    void rejectsMalformedCopybookSyntax() {
        String copybook = """
                       01 ROOT-REC.
                          05 FIELD-A PIC X(4)
                          05 FIELD-B PIC X(2).
                """;

        CobolCopybookAnalyzer.SyntaxValidation validation = analyzer.validateSyntax(copybook);

        assertFalse(validation.valid());
        assertTrue(validation.errorMessage().toLowerCase().contains("syntax"));
    }
}
