package com.pulse.chat.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 7 (IMPL-ui-composition) — token-based history auto-compaction units
 * (H2-free pure unit lane): compaction triggers above the threshold, emits a
 * summary block, keeps the recent tail + system message, and shrinks the
 * estimated token count. Below the threshold it is a no-op.
 */
class HistoryCompactorTest {

    private HistoryCompactor compactor;

    @BeforeEach
    void setUp() {
        compactor = new HistoryCompactor();
        // A small budget so the test can cross 50% with a handful of messages.
        ReflectionTestUtils.setField(compactor, "tokenThreshold", 1000);
        ReflectionTestUtils.setField(compactor, "triggerFraction", 0.5);
        ReflectionTestUtils.setField(compactor, "keepRecent", 4);
    }

    private static Map<String, Object> msg(String role, String content) {
        return Map.of("role", role, "content", content);
    }

    private List<Map<String, Object>> longHistory(int turns) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(msg("system", "You are PULSE."));
        // ~200 chars each ≈ 50 tokens; 12 turns ≈ 600 tokens > 500 (50% of 1000).
        String chunk = "x".repeat(200);
        for (int i = 0; i < turns; i++) {
            messages.add(msg("user", "request " + i + " " + chunk));
            messages.add(msg("assistant", "reply " + i + " " + chunk));
        }
        return messages;
    }

    @Test
    void estimateTokensIsCharsOverFour() {
        // "abcd" -> 1 token (4/4); empty -> floored to 1.
        assertEquals(1, HistoryCompactor.estimateTokens(msg("user", "abcd")));
        assertEquals(1, HistoryCompactor.estimateTokens(msg("user", "")));
        assertEquals(25, HistoryCompactor.estimateTokens(msg("user", "z".repeat(100))));
    }

    @Test
    void belowThresholdIsNoOp() {
        List<Map<String, Object>> small = List.of(msg("system", "sys"), msg("user", "hi"));
        var r = compactor.compact(small);
        assertFalse(r.compacted(), "short history must not compact");
        assertEquals(small, r.messages(), "input list returned untouched");
    }

    @Test
    void aboveThresholdCompactsEmitsSummaryAndShrinks() {
        List<Map<String, Object>> history = longHistory(12);
        assertTrue(compactor.shouldCompact(history), "12 turns must cross 50% of the 1000-token budget");

        var r = compactor.compact(history);
        assertTrue(r.compacted(), "above-threshold history compacts");
        assertTrue(r.summarizedMessageCount() > 0, "some turns summarized");
        assertTrue(r.estimatedTokensAfter() < r.estimatedTokensBefore(),
                "compaction shrinks estimated tokens: " + r.estimatedTokensBefore()
                        + " -> " + r.estimatedTokensAfter());

        List<Map<String, Object>> out = r.messages();
        assertEquals("system", out.get(0).get("role"), "leading system message preserved");
        // The summary block is inserted right after the system message and is marked.
        String summary = out.get(1).get("content").toString();
        assertTrue(summary.startsWith(HistoryCompactor.SUMMARY_MARKER),
                "summary block carries the marker: " + summary.substring(0, Math.min(40, summary.length())));
        // The most recent 4 messages are kept verbatim at the tail.
        Map<String, Object> lastIn = history.get(history.size() - 1);
        Map<String, Object> lastOut = out.get(out.size() - 1);
        assertEquals(lastIn.get("content"), lastOut.get("content"), "recent tail kept verbatim");
    }

    @Test
    void usesRealSummarizerWhenProvided() {
        List<Map<String, Object>> history = longHistory(12);
        var r = compactor.compact(history, (sys, transcript) -> "REAL SUMMARY: decisions captured.");
        assertTrue(r.compacted());
        String summary = r.messages().get(1).get("content").toString();
        assertTrue(summary.contains("REAL SUMMARY"), "real summarizer body used: " + summary);
    }

    @Test
    void fallsBackToDeterministicWhenSummarizerBlank() {
        List<Map<String, Object>> history = longHistory(12);
        var r = compactor.compact(history, (sys, transcript) -> "   ");
        assertTrue(r.compacted());
        String summary = r.messages().get(1).get("content").toString();
        assertTrue(summary.contains("Earlier conversation"),
                "blank summarizer falls back to deterministic summary: " + summary);
    }
}
