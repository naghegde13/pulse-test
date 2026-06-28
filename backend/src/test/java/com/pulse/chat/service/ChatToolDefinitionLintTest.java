package com.pulse.chat.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * BUG-2026-05-26-73 — Systemic regression guard for ChatTools schemas.
 *
 * <p>OpenAI Function Calling (and therefore OpenRouter) validates the JSON Schema
 * fragment sent under each tool's {@code parameters} against the spec. A
 * {@code type: "object"} schema is REQUIRED to declare a {@code properties}
 * object — even when no properties are accepted ({@code properties: {}} is
 * legal, but the key is mandatory). When the field is missing, OpenRouter
 * returns HTTP 400 ("Provider returned error") and the chat stream is killed.
 *
 * <p>This test walks every entry in {@link ChatTools#getToolDefinitions()}
 * recursively and asserts:
 * <ol>
 *   <li>The top-level {@code parameters} schema declares {@code type: "object"}
 *       AND has a {@code properties} key (possibly empty).</li>
 *   <li>Every nested schema with {@code type: "object"} ALSO has a
 *       {@code properties} key — including those appearing inside
 *       {@code properties.*}, inside {@code items}, inside {@code additionalProperties},
 *       and inside any {@code allOf}/{@code anyOf}/{@code oneOf} branches.</li>
 *   <li>Every nested schema with {@code type: "array"} declares an {@code items}
 *       schema (also OpenAI-spec required, also caught by OpenRouter).</li>
 * </ol>
 *
 * <p>Failing this test means the chat endpoint will emit HTTP 400 the moment
 * the offending tool is included in a request — which is every request, since
 * {@link com.pulse.chat.service.ChatService#handleLLMMode} sends the full tool
 * catalog every turn.
 *
 * <p>If this test fails, the assertion message identifies the breadcrumb path
 * from the offending tool's name down to the violating schema node, so the
 * fix is unambiguous: add {@code "properties", Map.of()} (or the actual
 * properties when known) at the called-out path.
 */
class ChatToolDefinitionLintTest {

    @Test
    void everyToolHasOpenAICompliantSchema() {
        List<Map<String, Object>> tools = ChatTools.getToolDefinitions();
        assertNotNull(tools, "ChatTools.getToolDefinitions() returned null");
        assertTrue(tools.size() > 0, "ChatTools.getToolDefinitions() returned an empty list");

        List<String> violations = new ArrayList<>();
        for (Map<String, Object> tool : tools) {
            lintTool(tool, violations);
        }

        if (!violations.isEmpty()) {
            String joined = String.join("\n  - ", violations);
            fail("Found " + violations.size()
                    + " ChatTools schema violation(s) that will cause OpenRouter HTTP 400:\n  - "
                    + joined
                    + "\n\nFix: add \"properties\", Map.of() to each cited object schema, "
                    + "and \"items\", Map.of(...) to each cited array schema.");
        }
    }

    @SuppressWarnings("unchecked")
    private void lintTool(Map<String, Object> tool, List<String> violations) {
        assertEquals("function", tool.get("type"), "Tool envelope must have type=function");
        Map<String, Object> function = (Map<String, Object>) tool.get("function");
        assertNotNull(function, "Tool envelope missing 'function' key");

        String name = (String) function.get("name");
        assertNotNull(name, "Tool envelope missing function.name");
        assertNotNull(function.get("description"),
                "Tool '" + name + "' missing function.description");

        Map<String, Object> parameters = (Map<String, Object>) function.get("parameters");
        assertNotNull(parameters, "Tool '" + name + "' missing function.parameters");

        // The top-level parameters MUST be type=object with a properties key.
        if (!"object".equals(parameters.get("type"))) {
            violations.add(name + ": top-level parameters.type is not 'object' (got "
                    + parameters.get("type") + ")");
        }

        // Walk recursively.
        Deque<String> path = new ArrayDeque<>();
        path.push(name + ".parameters");
        lintSchema(parameters, path, violations);
        path.pop();
    }

    /**
     * Recursive schema linter. Asserts:
     * - every {@code type: "object"} node has a {@code properties} key (even if empty)
     * - every {@code type: "array"} node has an {@code items} key
     * Recurses into properties, items, additionalProperties, allOf/anyOf/oneOf.
     */
    @SuppressWarnings("unchecked")
    private void lintSchema(Map<String, Object> schema, Deque<String> path, List<String> violations) {
        Object type = schema.get("type");
        String here = breadcrumb(path);

        if ("object".equals(type)) {
            if (!schema.containsKey("properties")) {
                violations.add(here
                        + ": type=\"object\" schema missing required \"properties\" key. "
                        + "Add \"properties\", Map.of() at minimum (OpenAI Function Calling spec; "
                        + "OpenRouter rejects with HTTP 400 otherwise)");
            } else {
                Object propsObj = schema.get("properties");
                if (propsObj instanceof Map) {
                    Map<String, Object> props = (Map<String, Object>) propsObj;
                    for (Map.Entry<String, Object> e : props.entrySet()) {
                        if (e.getValue() instanceof Map) {
                            path.push("properties." + e.getKey());
                            lintSchema((Map<String, Object>) e.getValue(), path, violations);
                            path.pop();
                        }
                    }
                }
            }
        }

        if ("array".equals(type)) {
            if (!schema.containsKey("items")) {
                violations.add(here
                        + ": type=\"array\" schema missing required \"items\" key. "
                        + "Add \"items\", Map.of(...) (OpenAI Function Calling spec)");
            } else {
                Object itemsObj = schema.get("items");
                if (itemsObj instanceof Map) {
                    path.push("items");
                    lintSchema((Map<String, Object>) itemsObj, path, violations);
                    path.pop();
                }
            }
        }

        // Recurse into additionalProperties when it's a schema (not boolean).
        Object addl = schema.get("additionalProperties");
        if (addl instanceof Map) {
            path.push("additionalProperties");
            lintSchema((Map<String, Object>) addl, path, violations);
            path.pop();
        }

        // Recurse into allOf / anyOf / oneOf branches.
        for (String branchKey : List.of("allOf", "anyOf", "oneOf")) {
            Object branches = schema.get(branchKey);
            if (branches instanceof List) {
                List<?> list = (List<?>) branches;
                for (int i = 0; i < list.size(); i++) {
                    Object branch = list.get(i);
                    if (branch instanceof Map) {
                        path.push(branchKey + "[" + i + "]");
                        lintSchema((Map<String, Object>) branch, path, violations);
                        path.pop();
                    }
                }
            }
        }
    }

    private String breadcrumb(Deque<String> path) {
        // Path is a stack; reverse for human-readable left-to-right rendering.
        List<String> segments = new ArrayList<>(path);
        java.util.Collections.reverse(segments);
        return String.join(".", segments);
    }
}
