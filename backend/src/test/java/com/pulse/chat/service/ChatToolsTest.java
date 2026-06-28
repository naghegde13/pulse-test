package com.pulse.chat.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ChatToolsTest {

    @Test
    void findDbtReuseCandidateToolExposesPlanningContext() {
        Map<String, Object> tool = ChatTools.getToolDefinitions().stream()
                .map(definition -> (Map<String, Object>) definition.get("function"))
                .filter(function -> "find_dbt_reuse_candidate".equals(function.get("name")))
                .findFirst()
                .orElseThrow();

        assertTrue(((String) tool.get("description")).contains("compatibility"));
        assertTrue(((String) tool.get("description")).contains("Call this before proposing a new dbt-backed transform or model"));

        Map<String, Object> parameters = (Map<String, Object>) tool.get("parameters");
        Map<String, Object> properties = (Map<String, Object>) parameters.get("properties");
        assertTrue(properties.containsKey("planning_context"));

        Map<String, Object> planningContext = (Map<String, Object>) properties.get("planning_context");
        assertEquals("object", planningContext.get("type"));
        assertTrue(((String) planningContext.get("description")).contains("semantic"));

        Map<String, Object> emitStrategy = (Map<String, Object>) properties.get("emit_strategy");
        assertEquals(List.of("generate", "reuse_wrapper", "reference_only"), emitStrategy.get("enum"));
    }

    @Test
    void toolDefinitionsIncludeListSinkTargets() {
        Map<String, Object> fn = findTool("list_sink_targets");
        assertNotNull(fn);
        Map<String, Object> parameters = (Map<String, Object>) fn.get("parameters");
        assertEquals(List.of(), parameters.get("required"));
    }

    @Test
    void toolDefinitionsIncludeCreateSinkTarget() {
        Map<String, Object> fn = findTool("create_sink_target");
        assertNotNull(fn);
        Map<String, Object> parameters = (Map<String, Object>) fn.get("parameters");
        assertEquals(List.of("name", "domain_id"), parameters.get("required"));
    }

    @Test
    void toolDefinitionsIncludeViewCodeExamples() {
        Map<String, Object> fn = findTool("view_code_examples");
        assertNotNull(fn);
        Map<String, Object> parameters = (Map<String, Object>) fn.get("parameters");
        assertEquals(List.of("blueprint_key"), parameters.get("required"));
    }

    @Test
    void listBlueprintsSchemaHasIncludeDeprecated() {
        Map<String, Object> fn = findTool("list_blueprints");
        assertNotNull(fn);
        Map<String, Object> parameters = (Map<String, Object>) fn.get("parameters");
        Map<String, Object> properties = (Map<String, Object>) parameters.get("properties");
        Map<String, Object> includeDeprecated =
                (Map<String, Object>) properties.get("include_deprecated");
        assertNotNull(includeDeprecated);
        assertEquals("boolean", includeDeprecated.get("type"));
        assertEquals(false, includeDeprecated.get("default"));
    }

    @Test
    void planCreatePipelineAllowsRuntimeDerivedDefaultStorageBackend() {
        Map<String, Object> fn = findTool("plan_create_pipeline");
        assertNotNull(fn);

        Map<String, Object> parameters = (Map<String, Object>) fn.get("parameters");
        assertEquals(List.of("name", "description"), parameters.get("required"));

        Map<String, Object> properties = (Map<String, Object>) parameters.get("properties");
        Map<String, Object> defaultStorageBackend =
                (Map<String, Object>) properties.get("default_storage_backend");
        assertNotNull(defaultStorageBackend);
        assertEquals(List.of("DPC", "GCP"), defaultStorageBackend.get("enum"));
        assertTrue(((String) defaultStorageBackend.get("description")).contains("Optional"));
    }

    @Test
    void phase9ReadToolsAreRegistered() {
        // T8-T14 + the renamed schema tool and its deprecated alias.
        for (String name : List.of(
                "get_step_schema", "get_upstream_schema",
                "get_composition_overview", "get_blueprint_op_list",
                "validate_structure", "validate_configuration",
                "validate_plan", "validate_sql_expression")) {
            assertNotNull(findTool(name), "missing tool: " + name);
        }
    }

    @Test
    void getStepSchemaRequiresInstanceAndVersion() {
        Map<String, Object> fn = findTool("get_step_schema");
        assertNotNull(fn);
        Map<String, Object> parameters = (Map<String, Object>) fn.get("parameters");
        assertEquals(List.of("pipeline_id", "version_id", "instance_id"), parameters.get("required"));
    }

    @Test
    void validateSqlExpressionRequiresExpression() {
        Map<String, Object> fn = findTool("validate_sql_expression");
        assertNotNull(fn);
        Map<String, Object> parameters = (Map<String, Object>) fn.get("parameters");
        assertEquals(List.of("expression"), parameters.get("required"));
    }

    @Test
    void getBlueprintOpListDescriptionDocumentsV153Fallback() {
        Map<String, Object> fn = findTool("get_blueprint_op_list");
        assertNotNull(fn);
        assertTrue(((String) fn.get("description")).contains("V153"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> findTool(String name) {
        return ChatTools.getToolDefinitions().stream()
                .map(definition -> (Map<String, Object>) definition.get("function"))
                .filter(function -> name.equals(function.get("name")))
                .findFirst()
                .orElse(null);
    }
}
