package com.pulse.chat.plan;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proof matrix for ARCH-018 forbidden-key blockers on
 * {@code plan_set_step_params}. Each test pins one stable error code.
 */
class StepParamsForbiddenKeyValidatorTest {

    @Test
    void canonicalStorageBackendKey_rejectsAsCanonicalFieldForbidden() {
        var r = StepParamsForbiddenKeyValidator.validate(Map.of(
                "storage_backend", "DPC",
                "filter_mode", "sql"));
        assertFalse(r.allowed());
        assertEquals(ChatPlanErrorCodes.STEP_PARAMS_CANONICAL_FIELD_FORBIDDEN, r.errorCode());
        assertTrue(r.offendingKeys().contains("storage_backend"));
    }

    @Test
    void canonicalLakeLayerCamelCase_alsoRejected() {
        var r = StepParamsForbiddenKeyValidator.validate(Map.of("lakeLayer", "silver"));
        assertFalse(r.allowed());
        assertEquals(ChatPlanErrorCodes.STEP_PARAMS_CANONICAL_FIELD_FORBIDDEN, r.errorCode());
    }

    @Test
    void canonicalLakeFormatKey_alsoRejected() {
        var r = StepParamsForbiddenKeyValidator.validate(Map.of("lake_format", "delta"));
        assertFalse(r.allowed());
        assertEquals(ChatPlanErrorCodes.STEP_PARAMS_CANONICAL_FIELD_FORBIDDEN, r.errorCode());
    }

    @Test
    void tableContractField_rejectsAsCanonical() {
        var r = StepParamsForbiddenKeyValidator.validate(Map.of(
                "tableFormat", "iceberg",
                "raw_sql", "x"));
        assertFalse(r.allowed());
        assertEquals(ChatPlanErrorCodes.STEP_PARAMS_CANONICAL_FIELD_FORBIDDEN, r.errorCode());
    }

    @Test
    void physicalTableNamePrefix_rejectsAsCanonical() {
        var r = StepParamsForbiddenKeyValidator.validate(Map.of(
                "physical_table_name", "loans"));
        assertFalse(r.allowed());
        assertEquals(ChatPlanErrorCodes.STEP_PARAMS_CANONICAL_FIELD_FORBIDDEN, r.errorCode());
    }

    @Test
    void scheduleCronKey_rejectsAsRequiresPipelineOrchestration() {
        var r = StepParamsForbiddenKeyValidator.validate(Map.of(
                "schedule_cron", "0 6 * * *"));
        assertFalse(r.allowed());
        assertEquals(ChatPlanErrorCodes.STEP_PARAMS_REQUIRES_PIPELINE_ORCHESTRATION, r.errorCode());
    }

    @Test
    void retryCountAndTimezone_alsoOrchestration() {
        var r = StepParamsForbiddenKeyValidator.validate(Map.of(
                "retryCount", 3,
                "timezone", "UTC"));
        assertFalse(r.allowed());
        assertEquals(ChatPlanErrorCodes.STEP_PARAMS_REQUIRES_PIPELINE_ORCHESTRATION, r.errorCode());
    }

    @Test
    void dqExpectations_rejectsWithDqRoutingHint() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("dqExpectations", java.util.List.of(Map.of("type", "ExpectColumnValuesToNotBeNull")));
        var r = StepParamsForbiddenKeyValidator.validate(params);
        assertFalse(r.allowed());
        assertEquals(ChatPlanErrorCodes.STEP_PARAMS_REQUIRES_DQ_TOOL, r.errorCode());
        assertTrue(r.message().contains("apply_dq_expectations"));
    }

    @Test
    void dqRulesAlias_alsoRoutesToDqTool() {
        var r = StepParamsForbiddenKeyValidator.validate(Map.of("dq_rules", java.util.List.of()));
        assertFalse(r.allowed());
        assertEquals(ChatPlanErrorCodes.STEP_PARAMS_REQUIRES_DQ_TOOL, r.errorCode());
    }

    @Test
    void emptyPayload_rejectsWithEmptyAfterNormalization() {
        var r = StepParamsForbiddenKeyValidator.validate(Map.of());
        assertFalse(r.allowed());
        assertEquals(ChatPlanErrorCodes.STEP_PARAMS_EMPTY_AFTER_NORMALIZATION, r.errorCode());
    }

    @Test
    void onlyCanonicalKeysProvided_stripsToEmptyThenReportsCanonicalCategory() {
        // Even though stripping all canonical keys would leave an empty payload,
        // the canonical-field code is more actionable for the LLM, so it wins.
        var r = StepParamsForbiddenKeyValidator.validate(Map.of(
                "storage_backend", "GCP",
                "lake_layer", "gold",
                "lake_format", "bq_native"));
        assertFalse(r.allowed());
        assertEquals(ChatPlanErrorCodes.STEP_PARAMS_CANONICAL_FIELD_FORBIDDEN, r.errorCode());
    }

    @Test
    void purelyGenericParams_pass() {
        var r = StepParamsForbiddenKeyValidator.validate(Map.of(
                "filter_mode", "sql",
                "raw_sql", "loan_status = 'Current'"));
        assertTrue(r.allowed(), "purely generic params must pass; offending: " + r.offendingKeys());
        assertNull(r.errorCode());
        assertEquals(2, r.sanitizedParams().size());
    }

    @Test
    void mixedGenericAndOrchestration_returnsOrchestrationCode() {
        // Generic-only keys are preserved in sanitizedParams BUT the call is
        // still rejected because at least one forbidden orchestration key
        // appeared; the validator never partially applies.
        var r = StepParamsForbiddenKeyValidator.validate(Map.of(
                "filter_mode", "sql",
                "schedule_cron", "0 6 * * *"));
        assertFalse(r.allowed());
        assertEquals(ChatPlanErrorCodes.STEP_PARAMS_REQUIRES_PIPELINE_ORCHESTRATION, r.errorCode());
        assertTrue(r.sanitizedParams().containsKey("filter_mode"));
    }

    @Test
    void dqWinsOverOrchestrationWinsOverCanonical_priority() {
        // When all three categories are hit at once, the DQ code wins because
        // it's the most specific routing hint.
        Map<String, Object> mixed = new LinkedHashMap<>();
        mixed.put("dqExpectations", java.util.List.of());
        mixed.put("schedule_cron", "0 6 * * *");
        mixed.put("storage_backend", "DPC");
        var r = StepParamsForbiddenKeyValidator.validate(mixed);
        assertFalse(r.allowed());
        assertEquals(ChatPlanErrorCodes.STEP_PARAMS_REQUIRES_DQ_TOOL, r.errorCode());
    }
}
