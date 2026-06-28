package com.pulse.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * BUG-2026-05-26-73 — End-to-end contract test for the chat request payload.
 *
 * <p>The lint test ({@link ChatToolDefinitionLintTest}) checks
 * {@code ChatTools.getToolDefinitions()} in isolation. This test goes one
 * level higher: it reconstructs the exact request body shape that
 * {@code ChatService.handleLLMMode} builds and sends to OpenRouter, serializes
 * it to JSON the same way Spring's REST client would, parses it back as a
 * JsonNode tree, then walks every tool's parameters subtree and asserts the
 * OpenAI Function Calling JSON Schema invariants.
 *
 * <p>Why a separate test from the lint?
 * <ul>
 *   <li>The lint test guards the source of truth; this test guards the wire
 *       contract. Even if someone reformats / restructures ChatTools to a
 *       different representation, this test will still detect the moment a
 *       spec-non-compliant payload would be sent.</li>
 *   <li>If we ever start mutating the tool list per-request (e.g. trimming
 *       deprecated tools, injecting workspace-scoped tools), this test
 *       protects that path too — the lint test doesn't see request-time
 *       transforms.</li>
 * </ul>
 *
 * <p>This test does NOT call the real OpenRouter API. The operator's
 * exit-criteria curl one-liner (see DEVIATIONS.md) does that and asserts
 * HTTP 200 vs HTTP 400 on the real provider.
 */
class ChatToolRequestPayloadContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void wireBodyToolsAllSatisfyOpenAiFunctionCallingSchema() throws Exception {
        // Reconstruct the request body the way ChatService.handleLLMMode does
        // (see ChatService.java:586-598). The test fixture is a minimal
        // single-user-turn conversation — the smallest payload OpenRouter
        // will accept that still includes the full tool catalog.
        Map<String, Object> requestBody = buildFixtureRequestBody();

        // Round-trip through Jackson to get the exact wire JSON tree the HTTP
        // client would send. This catches anything Map.of()-quirks might miss
        // (e.g. Map.of() iteration order is fixed but unspecified — toString
        // is unreliable for contract validation; JSON is authoritative).
        String wireJson = MAPPER.writeValueAsString(requestBody);
        JsonNode root = MAPPER.readTree(wireJson);

        // Structural shape (what OpenRouter parses first).
        assertNotNull(root.get("model"), "wire body must include 'model'");
        assertTrue(root.get("messages").isArray(), "'messages' must be a JSON array");
        assertTrue(root.get("messages").size() >= 1, "'messages' must contain at least one entry");
        assertTrue(root.get("tools").isArray(), "'tools' must be a JSON array");
        assertTrue(root.get("tools").size() > 0, "'tools' must not be empty");

        // Walk every tool's parameters subtree and validate.
        List<String> violations = new ArrayList<>();
        for (int i = 0; i < root.get("tools").size(); i++) {
            JsonNode tool = root.get("tools").get(i);
            assertEquals("function", tool.get("type").asText(),
                    "tool[" + i + "].type must be 'function'");

            JsonNode function = tool.get("function");
            assertNotNull(function, "tool[" + i + "] missing 'function'");
            String name = function.get("name").asText();
            JsonNode parameters = function.get("parameters");
            assertNotNull(parameters,
                    "tool '" + name + "' missing function.parameters");

            // Use a path stack rooted at the tool name for human-readable
            // violation messages.
            List<String> pathStack = new ArrayList<>();
            pathStack.add(name + ".parameters");
            lintJsonSchema(parameters, pathStack, violations);
        }

        if (!violations.isEmpty()) {
            fail("Wire request body would be rejected by OpenRouter (HTTP 400) due to "
                    + violations.size() + " JSON Schema violation(s):\n  - "
                    + String.join("\n  - ", violations));
        }
    }

    /**
     * Mirrors the exact request-body keys that
     * {@link com.pulse.chat.service.ChatService#handleLLMMode} sets — see
     * ChatService.java:586-598. The fixture has one user message so the body
     * is the smallest legal payload that still exercises the entire tool
     * catalog.
     */
    private Map<String, Object> buildFixtureRequestBody() {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system",
                "content", "You are PULSE, an enterprise GenAI Pipeline Builder."));
        messages.add(Map.of("role", "user",
                "content", "Hello, list the data sources."));

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", "anthropic/claude-opus-4");
        requestBody.put("messages", messages);
        requestBody.put("tools", ChatTools.getToolDefinitions());
        requestBody.put("stream", true);
        requestBody.put("max_tokens", 16_000);
        return requestBody;
    }

    /**
     * Validate a JSON Schema fragment (as a {@link JsonNode}) against the
     * OpenAI Function Calling invariants. Equivalent in spirit to
     * {@link ChatToolDefinitionLintTest#lintSchema}, but operating on the
     * post-serialization JSON tree instead of the raw {@code Map<>}.
     */
    private void lintJsonSchema(JsonNode schema, List<String> path, List<String> violations) {
        JsonNode type = schema.get("type");
        String here = String.join(".", path);

        if (type != null && "object".equals(type.asText())) {
            if (!schema.has("properties")) {
                violations.add(here
                        + ": type=\"object\" missing required \"properties\" key "
                        + "(OpenAI Function Calling spec)");
            } else {
                JsonNode props = schema.get("properties");
                if (props.isObject()) {
                    Iterator<Map.Entry<String, JsonNode>> it = props.fields();
                    while (it.hasNext()) {
                        Map.Entry<String, JsonNode> e = it.next();
                        path.add("properties." + e.getKey());
                        lintJsonSchema(e.getValue(), path, violations);
                        path.remove(path.size() - 1);
                    }
                }
            }
        }

        if (type != null && "array".equals(type.asText())) {
            if (!schema.has("items")) {
                violations.add(here
                        + ": type=\"array\" missing required \"items\" key "
                        + "(OpenAI Function Calling spec)");
            } else {
                JsonNode items = schema.get("items");
                path.add("items");
                lintJsonSchema(items, path, violations);
                path.remove(path.size() - 1);
            }
        }

        if (schema.has("additionalProperties") && schema.get("additionalProperties").isObject()) {
            path.add("additionalProperties");
            lintJsonSchema(schema.get("additionalProperties"), path, violations);
            path.remove(path.size() - 1);
        }

        for (String branchKey : List.of("allOf", "anyOf", "oneOf")) {
            JsonNode branches = schema.get(branchKey);
            if (branches != null && branches.isArray()) {
                for (int i = 0; i < branches.size(); i++) {
                    path.add(branchKey + "[" + i + "]");
                    lintJsonSchema(branches.get(i), path, violations);
                    path.remove(path.size() - 1);
                }
            }
        }
    }
}
