package com.pulse.chat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Token-based history auto-compaction (IMPL-ui-composition Phase 7;
 * 07-orchestration-revert-layout.md §2 / SPEC §7.11, §7.18 row 21). When a
 * session's rebuilt LLM message history exceeds the configured fraction of a
 * tunable context budget, the OLDER turns are folded into a single
 * conversation-summary block so token growth stays bounded — the PULSE analogue
 * of n8n's {@code compact_messages} node ({@code [read A]}).
 *
 * <p>Configuration:</p>
 * <ul>
 *   <li>{@code pulse.chat.compaction.token-threshold} — the context budget in
 *       tokens (default 96000); compaction triggers when the estimated history
 *       size exceeds {@code trigger-fraction} of this.</li>
 *   <li>{@code pulse.chat.compaction.trigger-fraction} — default 0.5 (~50%, per
 *       the spec).</li>
 *   <li>{@code pulse.chat.compaction.keep-recent} — how many of the most recent
 *       NON-system messages to keep verbatim (default 8); older ones are
 *       summarized.</li>
 * </ul>
 *
 * <p><b>Token estimate.</b> Tokens are estimated cheaply as
 * {@code chars / 4} (the documented heuristic, §7.11), NOT a real tokenizer —
 * good enough to bound growth without a model call on every turn.</p>
 *
 * <p><b>Summary.</b> By default the summary is a DETERMINISTIC concatenation +
 * truncation of the older turns (no LLM), so the path is hermetically testable
 * on H2. A real-summary mode is available via {@link #compact(List, java.util.function.BiFunction)}
 * for callers that want {@code ChatService.reasoningCall}; the deterministic
 * estimate-only summary is documented as the default.</p>
 */
@Component
public class HistoryCompactor {

    private static final Logger log = LoggerFactory.getLogger(HistoryCompactor.class);

    /** A marker key the compacted summary message carries so it is recognizable. */
    public static final String SUMMARY_MARKER = "[conversation-summary]";

    @Value("${pulse.chat.compaction.token-threshold:96000}")
    private int tokenThreshold;

    @Value("${pulse.chat.compaction.trigger-fraction:0.5}")
    private double triggerFraction;

    @Value("${pulse.chat.compaction.keep-recent:8}")
    private int keepRecent;

    /** The outcome of a compaction attempt. */
    public record CompactionResult(
            boolean compacted,
            int estimatedTokensBefore,
            int estimatedTokensAfter,
            int summarizedMessageCount,
            List<Map<String, Object>> messages) {}

    // ------------------------------------------------------------------
    // Token estimate (chars / 4 — the documented cheap heuristic).
    // ------------------------------------------------------------------

    /** Cheap per-message token estimate: {@code content.length() / 4}, role-floored at 1. */
    public static int estimateTokens(Map<String, Object> message) {
        Object content = message == null ? null : message.get("content");
        int chars = content == null ? 0 : content.toString().length();
        // Tool-call payloads also carry tokens; count their serialized length.
        Object toolCalls = message == null ? null : message.get("tool_calls");
        if (toolCalls != null) chars += toolCalls.toString().length();
        return Math.max(1, chars / 4);
    }

    /** Cheap whole-history token estimate (sum of per-message estimates). */
    public static int estimateTokens(List<Map<String, Object>> messages) {
        int total = 0;
        for (Map<String, Object> m : messages) total += estimateTokens(m);
        return total;
    }

    /** True when the estimated history size has crossed the trigger fraction of the budget. */
    public boolean shouldCompact(List<Map<String, Object>> messages) {
        if (messages == null || messages.size() <= keepRecent + 1) return false;
        int budgetTrigger = (int) Math.floor(tokenThreshold * triggerFraction);
        return estimateTokens(messages) > budgetTrigger;
    }

    /** The trigger point in tokens (budget * fraction) — visible for tests/diagnostics. */
    public int triggerTokens() {
        return (int) Math.floor(tokenThreshold * triggerFraction);
    }

    // ------------------------------------------------------------------
    // Compaction.
    // ------------------------------------------------------------------

    /**
     * Compact with the DETERMINISTIC (no-LLM) summary. Keeps the leading system
     * message + the last {@code keep-recent} non-system messages verbatim; folds
     * the rest into one summary message inserted right after the system message.
     */
    public CompactionResult compact(List<Map<String, Object>> messages) {
        return compact(messages, null);
    }

    /**
     * Compact, optionally using a real summarizer. When {@code summarizer} is
     * non-null it is called {@code summarizer.apply(systemPrompt, transcript)} to
     * produce the summary body (e.g. {@code ChatService::reasoningCall}); when it
     * is null (or returns blank), the deterministic transcript-truncation summary
     * is used.
     *
     * @return a {@link CompactionResult}; {@code compacted=false} (with the input
     *         list untouched) when the threshold is not crossed.
     */
    public CompactionResult compact(List<Map<String, Object>> messages,
                                    java.util.function.BiFunction<String, String, String> summarizer) {
        int before = estimateTokens(messages);
        if (!shouldCompact(messages)) {
            return new CompactionResult(false, before, before, 0, messages);
        }

        // Split off the leading system message (kept), the recent tail (kept),
        // and the older middle (summarized).
        int start = (!messages.isEmpty() && "system".equals(messages.get(0).get("role"))) ? 1 : 0;
        List<Map<String, Object>> head = new ArrayList<>(messages.subList(0, start));
        List<Map<String, Object>> body = messages.subList(start, messages.size());

        int keep = Math.min(keepRecent, body.size());
        int summarizeCount = body.size() - keep;
        if (summarizeCount <= 0) {
            return new CompactionResult(false, before, before, 0, messages);
        }
        List<Map<String, Object>> toSummarize = new ArrayList<>(body.subList(0, summarizeCount));
        List<Map<String, Object>> recent = new ArrayList<>(body.subList(summarizeCount, body.size()));

        String transcript = renderTranscript(toSummarize);
        String summaryBody = null;
        if (summarizer != null) {
            try {
                summaryBody = summarizer.apply(
                        "Summarize the earlier turns of this PULSE pipeline-building chat into a compact, "
                                + "factual brief: decisions made, steps staged/applied, datasets/blueprints named, "
                                + "and open questions. Preserve identifiers verbatim. No preamble.",
                        transcript);
            } catch (Exception e) {
                log.debug("Compaction summarizer failed ({}); using deterministic summary.", e.getMessage());
            }
        }
        if (summaryBody == null || summaryBody.isBlank() || summaryBody.startsWith("Reasoning model")) {
            summaryBody = deterministicSummary(transcript);
        }

        Map<String, Object> summaryMsg = Map.of(
                "role", "system",
                "content", SUMMARY_MARKER + "\n" + summaryBody);

        List<Map<String, Object>> out = new ArrayList<>(head.size() + 1 + recent.size());
        out.addAll(head);
        out.add(summaryMsg);
        out.addAll(recent);

        int after = estimateTokens(out);
        log.info("History compacted: session messages {} -> {} (summarized {} turns; est tokens {} -> {}).",
                messages.size(), out.size(), summarizeCount, before, after);
        return new CompactionResult(true, before, after, summarizeCount, out);
    }

    // ------------------------------------------------------------------
    // Summary rendering.
    // ------------------------------------------------------------------

    private static String renderTranscript(List<Map<String, Object>> messages) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> m : messages) {
            Object role = m.get("role");
            Object content = m.get("content");
            String text = content == null ? "" : content.toString();
            if (text.isBlank() && m.get("tool_calls") != null) {
                text = "(tool call) " + m.get("tool_calls");
            }
            if (text.isBlank()) continue;
            sb.append(role == null ? "?" : role.toString().toUpperCase()).append(": ")
                    .append(text.strip()).append('\n');
        }
        return sb.toString();
    }

    /**
     * Deterministic, no-LLM summary: a header line + the role-tagged transcript,
     * truncated to a bounded length. This bounds token growth without a model
     * call (the documented default; §7.11).
     */
    private static String deterministicSummary(String transcript) {
        final int max = 4000; // chars (~1k tokens) — keeps the summary block small.
        String body = transcript.strip();
        if (body.length() > max) {
            body = body.substring(0, max) + "\n…(earlier turns truncated for length)";
        }
        return "Earlier conversation (compacted to bound context size):\n" + body;
    }
}
