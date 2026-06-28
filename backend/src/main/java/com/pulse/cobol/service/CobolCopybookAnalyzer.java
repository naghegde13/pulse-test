package com.pulse.cobol.service;

import org.springframework.stereotype.Service;
import scala.Option;
import za.co.absa.cobrix.cobol.parser.CopybookParser$;
import za.co.absa.cobrix.cobol.parser.exceptions.SyntaxErrorException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class CobolCopybookAnalyzer {

    private static final Pattern LEVEL_01 = Pattern.compile("^\\s*01\\s+", Pattern.MULTILINE);
    private static final Pattern OCCURS = Pattern.compile("\\bOCCURS\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern OCCURS_DEPENDING_ON = Pattern.compile("\\bOCCURS\\b.*\\bDEPENDING\\s+ON\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern REDEFINES = Pattern.compile("\\bREDEFINES\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMP3 = Pattern.compile("\\bCOMP-3\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMP = Pattern.compile("\\bCOMP(?:-[1259])?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PIC_N = Pattern.compile("\\bPIC\\s+N\\b", Pattern.CASE_INSENSITIVE);

    public record Analysis(
            Map<String, Object> summary,
            Map<String, Object> baseOptions,
            List<Map<String, Object>> candidateOptions) {}

    public record SyntaxValidation(boolean valid, String errorMessage) {}

    public Analysis analyze(String copybookContent, byte[] dataBytes) {
        String normalized = copybookContent == null ? "" : copybookContent;
        int rootSegments = count(LEVEL_01, normalized);
        boolean hasOccurs = OCCURS.matcher(normalized).find();
        boolean hasOccursDependingOn = OCCURS_DEPENDING_ON.matcher(normalized).find();
        boolean hasRedefines = REDEFINES.matcher(normalized).find();
        boolean hasComp3 = COMP3.matcher(normalized).find();
        boolean hasComp = COMP.matcher(normalized).find();
        boolean hasNational = PIC_N.matcher(normalized).find();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("rootSegmentCount", rootSegments);
        summary.put("hasOccurs", hasOccurs);
        summary.put("hasOccursDependingOn", hasOccursDependingOn);
        summary.put("hasRedefines", hasRedefines);
        summary.put("hasComp3", hasComp3);
        summary.put("hasComp", hasComp);
        summary.put("hasNational", hasNational);
        summary.put("rawPreviewHex", leadingHex(dataBytes, 16));
        summary.put("looksAsciiText", looksAsciiText(dataBytes));

        Map<String, Object> baseOptions = new LinkedHashMap<>();
        baseOptions.put("schema_retention_policy", "collapse_root");
        baseOptions.put("ebcdic_code_page", "cp037");
        if (hasOccurs || hasOccursDependingOn) {
            baseOptions.put("variable_size_occurs", "true");
        }
        if (hasRedefines) {
            baseOptions.put("drop_group_fillers", "false");
            baseOptions.put("drop_value_fillers", "false");
        }

        List<Map<String, Object>> candidates = new ArrayList<>();
        candidates.add(withCandidate(baseOptions, "fixed-default"));
        if (looksAsciiText(dataBytes)) {
            Map<String, Object> textCandidate = withCandidate(baseOptions, "ascii-text-d");
            textCandidate.put("record_format", "D");
            textCandidate.put("encoding", "ascii");
            candidates.add(0, textCandidate);

            Map<String, Object> textCandidateD2 = withCandidate(baseOptions, "ascii-text-d2");
            textCandidateD2.put("record_format", "D2");
            textCandidateD2.put("encoding", "ascii");
            candidates.add(1, textCandidateD2);
        }

        RecordHeaderGuess headerGuess = guessHeaders(dataBytes);
        if (headerGuess.variableBigEndian) {
            Map<String, Object> candidate = withCandidate(baseOptions, "rdw-big-endian");
            candidate.put("record_format", "V");
            candidate.put("is_rdw_big_endian", "true");
            candidates.add(0, candidate);
        }
        if (headerGuess.variableLittleEndian) {
            Map<String, Object> candidate = withCandidate(baseOptions, "rdw-little-endian");
            candidate.put("record_format", "V");
            candidate.put("is_rdw_big_endian", "false");
            candidates.add(candidate);
        }
        if (headerGuess.variableBlockedBigEndian) {
            Map<String, Object> candidate = withCandidate(baseOptions, "vb-big-endian");
            candidate.put("record_format", "VB");
            candidate.put("is_bdw_big_endian", "true");
            candidate.put("is_rdw_big_endian", "true");
            candidate.put("bdw_adjustment", "-4");
            candidates.add(0, candidate);
        }
        if (headerGuess.variableBlockedLittleEndian) {
            Map<String, Object> candidate = withCandidate(baseOptions, "vb-little-endian");
            candidate.put("record_format", "VB");
            candidate.put("is_bdw_big_endian", "false");
            candidate.put("is_rdw_big_endian", "false");
            candidate.put("bdw_adjustment", "-4");
            candidates.add(candidate);
        }
        if (rootSegments > 1 && hasRedefines) {
            summary.put("recommendSegmentMapping", true);
        }
        return new Analysis(summary, baseOptions, candidates);
    }

    public SyntaxValidation validateSyntax(String copybookContent) {
        if (copybookContent == null || copybookContent.isBlank()) {
            return new SyntaxValidation(false, "Copybook text is empty.");
        }
        try {
            CopybookParser$.MODULE$.parseSimple(
                    copybookContent,
                    CopybookParser$.MODULE$.parseSimple$default$2(),
                    CopybookParser$.MODULE$.parseSimple$default$3(),
                    CopybookParser$.MODULE$.parseSimple$default$4(),
                    CopybookParser$.MODULE$.parseSimple$default$5(),
                    CopybookParser$.MODULE$.parseSimple$default$6()
            );
            return new SyntaxValidation(true, "");
        } catch (Exception ex) {
            if (ex instanceof SyntaxErrorException syntaxError) {
                return new SyntaxValidation(false, formatSyntaxError(syntaxError));
            }
            return new SyntaxValidation(false, "Cobrix syntax validation failed: " + ex.getMessage());
        }
    }

    private boolean looksAsciiText(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return false;
        int sample = Math.min(bytes.length, 256);
        int printable = 0;
        int newline = 0;
        for (int i = 0; i < sample; i++) {
            int b = bytes[i] & 0xFF;
            if (b == '\n' || b == '\r') newline++;
            if ((b >= 32 && b <= 126) || b == '\n' || b == '\r' || b == '\t') {
                printable++;
            }
        }
        return newline > 0 && printable >= (sample * 0.85);
    }

    private int count(Pattern pattern, String content) {
        int matches = 0;
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            matches++;
        }
        return matches;
    }

    private String leadingHex(byte[] bytes, int size) {
        if (bytes == null || bytes.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        int max = Math.min(bytes.length, size);
        for (int i = 0; i < max; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format(Locale.ROOT, "%02X", bytes[i] & 0xFF));
        }
        return sb.toString();
    }

    private Map<String, Object> withCandidate(Map<String, Object> baseOptions, String label) {
        Map<String, Object> candidate = new LinkedHashMap<>(baseOptions);
        candidate.put("_candidate_label", label);
        return candidate;
    }

    private RecordHeaderGuess guessHeaders(byte[] bytes) {
        if (bytes == null || bytes.length < 8) return new RecordHeaderGuess(false, false, false, false);
        int be = ((bytes[0] & 0xFF) << 8) | (bytes[1] & 0xFF);
        int le = ((bytes[1] & 0xFF) << 8) | (bytes[0] & 0xFF);
        int bdwBe = ((bytes[0] & 0xFF) << 8) | (bytes[1] & 0xFF);
        int bdwLe = ((bytes[1] & 0xFF) << 8) | (bytes[0] & 0xFF);
        int rdwBe = ((bytes[4] & 0xFF) << 8) | (bytes[5] & 0xFF);
        int rdwLe = ((bytes[5] & 0xFF) << 8) | (bytes[4] & 0xFF);

        boolean variableBigEndian = plausibleLength(be, bytes.length);
        boolean variableLittleEndian = plausibleLength(le, bytes.length);
        boolean variableBlockedBigEndian = plausibleLength(bdwBe, bytes.length) && plausibleLength(rdwBe, bytes.length);
        boolean variableBlockedLittleEndian = plausibleLength(bdwLe, bytes.length) && plausibleLength(rdwLe, bytes.length);
        return new RecordHeaderGuess(variableBigEndian, variableLittleEndian, variableBlockedBigEndian, variableBlockedLittleEndian);
    }

    private boolean plausibleLength(int length, int totalBytes) {
        return length > 8 && length <= Math.max(totalBytes, 32760);
    }

    private String formatSyntaxError(SyntaxErrorException ex) {
        StringBuilder sb = new StringBuilder("Cobrix syntax validation failed");
        if (ex.lineNumber() > 0) {
            sb.append(" at line ").append(ex.lineNumber());
        }
        Option<Object> pos = ex.posOpt();
        if (pos != null && pos.isDefined()) {
            sb.append(", column ").append(pos.get());
        }
        Option<String> field = ex.fieldOpt();
        if (field != null && field.isDefined()) {
            sb.append(" near ").append(field.get());
        }
        if (ex.msg() != null && !ex.msg().isBlank()) {
            sb.append(": ").append(ex.msg());
        }
        return sb.toString();
    }

    private record RecordHeaderGuess(
            boolean variableBigEndian,
            boolean variableLittleEndian,
            boolean variableBlockedBigEndian,
            boolean variableBlockedLittleEndian) {}
}
