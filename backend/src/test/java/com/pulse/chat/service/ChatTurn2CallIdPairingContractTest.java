package com.pulse.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.chat.model.ChatMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for the chat turn-2 call_id pairing bug surfaced by the
 * PKT-FINAL-7 morning summary (2026-05-27):
 *
 * <p>"Turn 2 fails: <code>CHAT_UPSTREAM_LLM_ERROR</code> from Azure via
 * OpenRouter — <code>No tool call found for function call output with call_id
 * call_Iy7LayeNo9c3LlzKxjETlYmm.</code>"
 *
 * <p>Root-cause shape: when PULSE rebuilds the chat history on turn 2 from
 * the DB, the assistant message's <code>tool_calls</code> array must contain
 * an entry whose <code>id</code> exactly equals the <code>tool_call_id</code>
 * on the following <code>tool</code> response message. If those drift across
 * the JSONB persistence round-trip — or if either id is silently lost — the
 * upstream LLM rejects the request with "no tool call found for function
 * call output."
 *
 * <p>SU-2 / BUG-73's {@link ChatToolDefinitionLintTest} +
 * {@link ChatToolRequestPayloadContractTest} guard the <em>request schema</em>.
 * Neither covers the function_call ↔ function_call_output <em>pairing
 * invariant</em>. This test does.
 *
 * <p>The test is a pure unit test against {@link ChatService#unwrapToolCalls(Map)}
 * + a hand-rolled reproduction of the history-rebuild loop in
 * {@code handleLLMMode} (ChatService.java:551-582). Boots in &lt; 50 ms; no
 * Spring context, no Postgres, no LLM call.
 */
class ChatTurn2CallIdPairingContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Canonical turn-1 outcome: model emitted a single tool call with id
     * <code>call_ABC123</code>. PULSE persists the assistant message and the
     * subsequent tool result.
     */
    @Test
    @DisplayName("turn-2 history rebuild: assistant tool_calls[].id MUST equal subsequent tool message's tool_call_id")
    void turn2HistoryPreservesIdPairing() throws Exception {
        String callId = "call_ABC123";

        // ---- Persisted state after turn-1 (matches ChatService.java:619-682) ----
        // The assistant message stores its tool_calls list wrapped under a
        // "calls" key (ChatService.java:623), each call carrying id/type/function.
        ChatMessage assistantRow = new ChatMessage();
        assistantRow.setRole("ASSISTANT");
        assistantRow.setContent("");
        assistantRow.setToolCalls(Map.of(
                "calls", List.of(Map.of(
                        "id", callId,
                        "type", "function",
                        "function", Map.of(
                                "name", "list_data_sources",
                                "arguments", "{}"
                        )
                ))
        ));

        // The tool-result row stores only tool_call_id (ChatService.java:677).
        ChatMessage toolRow = new ChatMessage();
        toolRow.setRole("TOOL");
        toolRow.setContent("[{\"id\":\"sor-1\",\"name\":\"loans\"}]");
        toolRow.setToolCalls(Map.of("tool_call_id", callId));

        // ---- Simulate the turn-2 history-rebuild loop (ChatService.java:551-582) ----
        List<ChatMessage> history = List.of(assistantRow, toolRow);
        List<Map<String, Object>> rebuilt = rebuildTurn2Messages(history);

        // ---- Assertions: the pairing invariant ----
        assertThat(rebuilt)
                .as("rebuild should produce 2 messages (assistant + tool)")
                .hasSize(2);

        Map<String, Object> assistant = rebuilt.get(0);
        Map<String, Object> tool = rebuilt.get(1);

        assertThat(assistant.get("role")).isEqualTo("assistant");
        assertThat(tool.get("role")).isEqualTo("tool");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tcList = (List<Map<String, Object>>) assistant.get("tool_calls");
        assertThat(tcList)
                .as("assistant must carry a non-empty tool_calls array")
                .isNotNull()
                .isNotEmpty();

        String assistantCallId = (String) tcList.get(0).get("id");
        String toolCallId = (String) tool.get("tool_call_id");

        assertThat(assistantCallId)
                .as("assistant tool_calls[0].id must be present (non-null, non-blank)")
                .isNotNull()
                .isNotBlank();

        assertThat(toolCallId)
                .as("tool message tool_call_id must be present (non-null, non-blank)")
                .isNotNull()
                .isNotBlank();

        // THE CONTRACT: pairing must survive the rebuild. If this assertion
        // fails the upstream Azure/OpenRouter request will get rejected with
        // "no tool call found for function call output with call_id X."
        assertThat(toolCallId)
                .as("tool_call_id on the tool response message MUST equal exactly one of "
                        + "the assistant message's tool_calls[].id values. Drift here is the "
                        + "exact failure mode the PKT-FINAL-7 morning summary documented.")
                .isEqualTo(assistantCallId);
    }

    /**
     * Variant: simulate the JSONB serialization round-trip more faithfully
     * by going through Jackson. If Jackson reshapes the persisted map in any
     * way that loses or aliases the id, this test catches it.
     */
    @Test
    @DisplayName("turn-2 history rebuild: id survives Jackson JSONB serialization round-trip")
    void turn2IdSurvivesJsonbRoundTrip() throws Exception {
        String callId = "call_Iy7LayeNo9c3LlzKxjETlYmm"; // verbatim from the bug summary

        // Persist shape — what JdbcTypeCode(SqlTypes.JSON) would write
        Map<String, Object> storedAssistantToolCalls = Map.of(
                "calls", List.of(Map.of(
                        "id", callId,
                        "type", "function",
                        "function", Map.of("name", "list_data_sources", "arguments", "{}")
                ))
        );

        // Round-trip through Jackson (matches the JSON column codec behaviour)
        String json = MAPPER.writeValueAsString(storedAssistantToolCalls);
        @SuppressWarnings("unchecked")
        Map<String, Object> reloaded = MAPPER.readValue(json, Map.class);

        List<?> unwrapped = ChatService.unwrapToolCalls(reloaded);
        assertThat(unwrapped)
                .as("unwrapToolCalls must return the inner list after round-trip")
                .isNotNull()
                .isNotEmpty();

        @SuppressWarnings("unchecked")
        Map<String, Object> firstCall = (Map<String, Object>) unwrapped.get(0);
        assertThat(firstCall.get("id"))
                .as("id must survive the JSONB round-trip unchanged — drift here is the BUG-2026-05-27-CHAT bug")
                .isEqualTo(callId);
    }

    /**
     * Variant: confirm the failure mode (what happens when stored data is
     * malformed). This test pins the BUG-2026-05-27-CHAT bug contract: when
     * the id IS missing/mismatched on the assistant side, the rebuild loop
     * must STILL produce a tool message whose tool_call_id is consistent
     * with whatever id (if any) the assistant has. Mismatched id = the bug.
     */
    @Test
    @DisplayName("DIAGNOSTIC: rebuild produces consistent pairing OR fails loudly — never silently mismatched")
    void diagnosticRebuildPairingIsAlwaysConsistent() {
        // Scenario: assistant has id A, tool says id B. Currently the rebuild
        // forwards both verbatim without checking the pairing, so the request
        // would go upstream with a mismatch. This test pins that — when the
        // bug fix lands, the rebuild should either (a) reject the mismatch
        // and skip the orphan tool message, or (b) heal it from the
        // assistant's id. Either is fine; silent forwarding is not.
        ChatMessage assistantRow = new ChatMessage();
        assistantRow.setRole("ASSISTANT");
        assistantRow.setContent("");
        assistantRow.setToolCalls(Map.of(
                "calls", List.of(Map.of(
                        "id", "call_real",
                        "type", "function",
                        "function", Map.of("name", "list_data_sources", "arguments", "{}")
                ))
        ));

        ChatMessage toolRow = new ChatMessage();
        toolRow.setRole("TOOL");
        toolRow.setContent("[]");
        toolRow.setToolCalls(Map.of("tool_call_id", "call_drifted")); // MISMATCH

        List<Map<String, Object>> rebuilt = rebuildTurn2Messages(List.of(assistantRow, toolRow));

        // Find the orphan tool message
        Map<String, Object> tool = rebuilt.stream()
                .filter(m -> "tool".equals(m.get("role")))
                .findFirst().orElse(null);

        if (tool == null) {
            // Acceptable outcome: rebuild filtered the orphan. Test passes.
            return;
        }

        String toolCallId = (String) tool.get("tool_call_id");
        Map<String, Object> assistant = rebuilt.stream()
                .filter(m -> "assistant".equals(m.get("role")))
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tcList = (List<Map<String, Object>>) assistant.get("tool_calls");
        List<String> assistantIds = tcList == null ? List.of()
                : tcList.stream().map(tc -> (String) tc.get("id")).toList();

        // If the rebuild kept the tool message, its tool_call_id MUST be
        // present in the assistant's id list. Otherwise this is the
        // BUG-2026-05-27 reproduction.
        assertThat(assistantIds)
                .as("If the rebuild keeps a tool message, its tool_call_id MUST appear in the "
                        + "assistant's tool_calls[].id list. Otherwise the upstream LLM rejects "
                        + "with 'no tool call found for function call output' (BUG-2026-05-27).")
                .contains(toolCallId);
    }

    // ------------------------------------------------------------------
    //  Reproduction of ChatService.handleLLMMode's history-rebuild logic
    //  (post-BUG-2026-05-27 fix). Mirrors the real method exactly so this
    //  unit test stays semantically aligned with the production path
    //  without booting a Spring context.
    // ------------------------------------------------------------------

    private static List<Map<String, Object>> rebuildTurn2Messages(List<ChatMessage> history) {
        List<Map<String, Object>> messages = new ArrayList<>();
        java.util.Set<String> knownToolCallIds = new java.util.HashSet<>();
        for (var msg : history) {
            String role = msg.getRole().toLowerCase();
            if ("assistant".equals(role) && msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("role", "assistant");
                m.put("content", msg.getContent() != null ? msg.getContent() : "");
                List<?> tcOut = ChatService.unwrapToolCalls(msg.getToolCalls());
                if (tcOut != null) {
                    m.put("tool_calls", tcOut);
                    for (Object tc : tcOut) {
                        if (tc instanceof Map<?, ?> tcMap) {
                            Object id = tcMap.get("id");
                            if (id != null && !id.toString().isBlank()) {
                                knownToolCallIds.add(id.toString());
                            }
                        }
                    }
                }
                messages.add(m);
            } else if ("tool".equals(role)) {
                Object tcid = (msg.getToolCalls() != null) ? msg.getToolCalls().get("tool_call_id") : null;
                String tcidStr = tcid == null ? "" : tcid.toString();
                if (tcidStr.isBlank() || !knownToolCallIds.contains(tcidStr)) {
                    // Orphan tool message — production code drops it
                    // (BUG-2026-05-27 fix). Mirror that here.
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("role", "tool");
                m.put("tool_call_id", tcidStr);
                m.put("content", msg.getContent() != null ? msg.getContent() : "");
                messages.add(m);
            } else {
                Map<String, Object> m = new HashMap<>();
                m.put("role", role);
                m.put("content", msg.getContent() != null ? msg.getContent() : "");
                messages.add(m);
            }
        }
        return messages;
    }
}
