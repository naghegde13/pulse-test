package com.pulse.pipeline.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.llm.LlmEndpointService;
import com.pulse.llm.LlmSurface;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SchemaInferenceService {

    private static final Logger log = LoggerFactory.getLogger(SchemaInferenceService.class);

    @Value("${pulse.schema-inference.api-key:${pulse.llm.api-key:}}")
    private String apiKey;

    @Value("${pulse.schema-inference.base-url:${OPENROUTER_BASE_URL:https://openrouter.ai/api/v1}}")
    private String baseUrl;

    @Value("${pulse.schema-inference.model:${SCHEMA_INFERENCE_MODEL:google/gemini-2.0-flash-001}}")
    private String model;

    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private LlmEndpointService llmEndpointService;

    // Simple in-memory cache: hash(blueprintKey + inputSchema + params) -> outputSchema
    private final Map<String, Map<String, Object>> cache = new ConcurrentHashMap<>();

    public SchemaInferenceService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Infer the output schema of a transform given its blueprint type, input schema(s), and params.
     * Uses an LLM call via OpenRouter with result caching.
     *
     * @param blueprintKey  The blueprint key (e.g., "GenericFilter", "GenericAggregate")
     * @param inputSchema   The primary input schema: {"columns": [{"name":"col","type":"string"}, ...]}
     * @param secondarySchema Optional secondary schema (for joins: right input). Null if not applicable.
     * @param params        The configured transform params (e.g., filter conditions, group_by columns)
     * @return The inferred output schema in the same format, or null if inference failed/not supported.
     */
    public Map<String, Object> inferOutputSchema(
            String blueprintKey,
            Map<String, Object> inputSchema,
            Map<String, Object> secondarySchema,
            Map<String, Object> params) {

        if (!isLlmConfigured()) {
            log.debug("Schema inference skipped: no API key configured");
            return null;
        }

        if (inputSchema == null || inputSchema.isEmpty()) {
            log.debug("Schema inference skipped: no input schema available");
            return null;
        }

        // Check cache
        String cacheKey = computeCacheKey(blueprintKey, inputSchema, secondarySchema, params);
        Map<String, Object> cached = cache.get(cacheKey);
        if (cached != null) {
            log.debug("Schema inference cache hit for {}", blueprintKey);
            return cached;
        }

        try {
            String prompt = buildPrompt(blueprintKey, inputSchema, secondarySchema, params);
            String response = callLLM(prompt);
            Map<String, Object> outputSchema = parseSchemaResponse(response);

            if (outputSchema != null) {
                cache.put(cacheKey, outputSchema);
                log.info("Schema inference succeeded for {} -> {} columns", blueprintKey,
                        outputSchema.containsKey("columns") ? ((List<?>) outputSchema.get("columns")).size() : "?");
            }
            return outputSchema;
        } catch (Exception e) {
            log.error("Schema inference failed for {}: {}", blueprintKey, e.getMessage());
            return null;
        }
    }

    public void invalidateCache() {
        cache.clear();
    }

    String buildPrompt(String blueprintKey, Map<String, Object> inputSchema,
                       Map<String, Object> secondarySchema, Map<String, Object> params) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(SYSTEM_PROMPT);
            sb.append("\n\n## Transform: ").append(blueprintKey);
            sb.append("\n\n## Input Schema (primary):\n```json\n");
            sb.append(objectMapper.writeValueAsString(inputSchema));
            sb.append("\n```");

            if (secondarySchema != null && !secondarySchema.isEmpty()) {
                sb.append("\n\n## Secondary Input Schema (right/reference):\n```json\n");
                sb.append(objectMapper.writeValueAsString(secondarySchema));
                sb.append("\n```");
            }

            sb.append("\n\n## Transform Parameters:\n```json\n");
            sb.append(objectMapper.writeValueAsString(params));
            sb.append("\n```");

            sb.append("\n\n## Output:\nReturn ONLY the output schema JSON. No explanation, no markdown fences.");
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to build inference prompt", e);
        }
    }

    private String callLLM(String userPrompt) throws Exception {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", modelFor());
        requestBody.put("messages", List.of(
                Map.of("role", "system", "content", "You are a schema inference engine. You ONLY output valid JSON."),
                Map.of("role", "user", "content", userPrompt)
        ));
        requestBody.put("temperature", 0.0);
        requestBody.put("max_tokens", 2000);

        HttpURLConnection conn = openLlmConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(30_000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(objectMapper.writeValueAsBytes(requestBody));
        }

        int status = conn.getResponseCode();
        InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            String raw = sb.toString();

            if (status >= 400) {
                throw new RuntimeException("LLM API returned " + status + ": " + raw);
            }

            // Parse the OpenAI-compatible response
            @SuppressWarnings("unchecked")
            Map<String, Object> response = objectMapper.readValue(raw, Map.class);
            @SuppressWarnings("unchecked")
            var choices = (List<Map<String, Object>>) response.get("choices");
            if (choices == null || choices.isEmpty()) {
                throw new RuntimeException("No choices in LLM response");
            }
            @SuppressWarnings("unchecked")
            var message = (Map<String, Object>) choices.get(0).get("message");
            return (String) message.get("content");
        }
    }

    private boolean isLlmConfigured() {
        if (llmEndpointService != null && llmEndpointService.isConfigured(LlmSurface.SCHEMA_INFERENCE)) {
            return llmEndpointService.isConfigured(LlmSurface.SCHEMA_INFERENCE);
        }
        return apiKey != null && !apiKey.isBlank();
    }

    private String modelFor() {
        if (llmEndpointService != null && llmEndpointService.isConfigured(LlmSurface.SCHEMA_INFERENCE)) {
            return llmEndpointService.model(LlmSurface.SCHEMA_INFERENCE);
        }
        return model;
    }

    private HttpURLConnection openLlmConnection() throws IOException {
        if (llmEndpointService != null && llmEndpointService.isConfigured(LlmSurface.SCHEMA_INFERENCE)) {
            return llmEndpointService.openChatCompletionsConnection(LlmSurface.SCHEMA_INFERENCE, "PULSE Schema Inference");
        }
        HttpURLConnection conn = (HttpURLConnection) URI.create(baseUrl + "/chat/completions").toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setRequestProperty("HTTP-Referer", "https://pulse.app");
        conn.setRequestProperty("X-Title", "PULSE Schema Inference");
        conn.setDoOutput(true);
        return conn;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> parseSchemaResponse(String response) {
        if (response == null || response.isBlank()) return null;

        // Strip markdown fences if present
        String cleaned = response.strip();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceFirst("```(?:json)?\\s*", "");
            cleaned = cleaned.replaceFirst("\\s*```\\s*$", "");
        }

        try {
            Map<String, Object> schema = objectMapper.readValue(cleaned, new TypeReference<>() {});
            // Validate it has "columns" array
            if (!schema.containsKey("columns")) {
                log.warn("Schema response missing 'columns' key: {}", cleaned);
                return null;
            }
            return schema;
        } catch (Exception e) {
            log.error("Failed to parse schema response: {} -- raw: {}", e.getMessage(), cleaned);
            return null;
        }
    }

    private String computeCacheKey(String blueprintKey, Map<String, Object> inputSchema,
                                   Map<String, Object> secondarySchema, Map<String, Object> params) {
        try {
            String key = blueprintKey + "|"
                    + objectMapper.writeValueAsString(inputSchema) + "|"
                    + (secondarySchema != null ? objectMapper.writeValueAsString(secondarySchema) : "") + "|"
                    + objectMapper.writeValueAsString(params);
            return String.valueOf(key.hashCode());
        } catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }

    // The core prompt that teaches the LLM the schema inference rules
    static final String SYSTEM_PROMPT = """
You are a deterministic schema inference engine for a data pipeline builder called PULSE.

Given a transform type, its input schema(s), and its configuration parameters, you must compute the EXACT output schema.

## Rules by transform type:

### GenericFilter
- Output schema is IDENTICAL to input schema (same columns, same types)
- Filtering only removes rows, never columns

### GenericAggregate
- Output columns = group_by columns (retain original name and type) + aggregation columns (new name from alias, type from function)
- Type rules for aggregation functions:
  - COUNT, COUNT_DISTINCT -> "long"
  - SUM -> if source column is integer/long, output is "long"; if double/float/decimal, output is "double"
  - AVG -> "double"
  - MIN, MAX -> same type as source column
  - If column is "*" (e.g., COUNT(*)), source type doesn't matter, use the function rule above

### GenericJoin
- Start with ALL columns from the left input (in order)
- Then append columns from the right input, with these rules:
  - If a right column name matches a JOIN KEY column name, SKIP it (it's redundant with the left key)
  - If a right column name matches a NON-KEY left column name, RENAME BOTH: the left one becomes "left_{name}" and the right one becomes "right_{name}"
  - Otherwise, append the right column as-is
- The join key columns appear exactly ONCE (from the left side)
- Join type (inner/left/right/full) does NOT affect the output schema
- For collisions: the LEFT column keeps its original name. The RIGHT column gets "right_" prefix.

**Example — Join with collision:**
Left: [{name:"id",type:"long"}, {name:"value",type:"string"}]
Right: [{name:"id",type:"long"}, {name:"value",type:"double"}, {name:"score",type:"integer"}]
Keys: ["id"]
Output: [{name:"id",type:"long"}, {name:"value",type:"string"}, {name:"right_value",type:"double"}, {name:"score",type:"integer"}]

**Example — Self-join:**
Left: [{name:"emp_id",type:"long"}, {name:"dept",type:"string"}]
Right: [{name:"emp_id",type:"long"}, {name:"dept",type:"string"}]
Keys: ["emp_id"]
Output: [{name:"emp_id",type:"long"}, {name:"dept",type:"string"}, {name:"right_dept",type:"string"}]

### GenericRouter
- Output schema is IDENTICAL to input schema for EVERY output port
- Routing only splits rows by condition, never changes columns

### JsonFlatten
- For each source column of type "struct": expand nested fields using the separator
  - e.g., column "address" type "struct" with fields [{name:"city",type:"string"},{name:"zip",type:"string"}] becomes "address_city" (string) and "address_zip" (string)
- If explode_arrays is true: array columns become their element type (changes cardinality)
- If keep_original is true: include the original struct column alongside the flattened fields
- Non-flattened columns pass through unchanged
- If source_columns is ["*"] or empty, flatten ALL struct/nested columns
- If max_depth is specified, STOP flattening at that depth. Depth 1 = expand top-level struct fields only. Fields beyond max_depth remain as struct type.
- Flatten struct columns RECURSIVELY: if a struct has sub-struct fields, expand those too (unless max_depth is reached).
- If sourceColumns includes a map column, pass the map through UNCHANGED (maps cannot be struct-flattened).
- IMPORTANT: When flattening, if a struct field is itself a struct, you MUST recurse into it (unless max_depth stops you). Do NOT leave sub-structs unexpanded.

### JsonStruct
- Output = passthrough columns + new struct columns
- Passthrough = all input columns NOT in any mapping's source_columns list (if drop_source_columns is true) or all input columns (if drop_source_columns is false)
- New struct columns: one per mapping, type is "struct" with fields typed from their source columns
- If output_format is "json_string": the new columns are type "string" (JSON serialized) instead of "struct"

### Other blueprints (DedupeAndMerge, PIIMasking, SCD2Dimension, BronzeToSilverCleaning, SchemaNormalization, EnrichmentJoin, etc.)
- DedupeAndMerge: output = input (same columns, deduplicated rows)
- PIIMasking: output = input but masked columns have type "string" (masked/tokenized). If no specific columns listed in params, output = input
- BronzeToSilverCleaning: output = input (cleaned but same schema)
- SchemaNormalization: output = input with standardized column names (lowercase, underscores)
- SCD2Dimension: output = input + [effective_from (timestamp), effective_to (timestamp), is_current (boolean), scd2_hash (string)]
- EnrichmentJoin: output = main_data columns + reference_data columns (like a left join). Key columns from reference are omitted. Non-key collisions: left keeps name, right gets "right_" prefix. ALWAYS include both sides of a collision.
- For any unknown blueprint: output = input (passthrough assumption)

## Output format:
Return ONLY valid JSON in this exact format:
{"columns": [{"name": "column_name", "type": "column_type"}, ...]}

Valid types: "string", "long", "integer", "double", "float", "decimal", "boolean", "date", "timestamp", "struct", "array", "binary", "map"

Do NOT include any explanation. Do NOT wrap in markdown. ONLY the JSON object.
""";
}
