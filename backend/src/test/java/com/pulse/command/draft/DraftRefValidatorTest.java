package com.pulse.command.draft;

import com.pulse.command.service.PlanService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DraftRefValidatorTest {

    private final DraftRefValidator validator = new DraftRefValidator();
    private final DraftRefSubstitutor substitutor = new DraftRefSubstitutor();

    @Test
    void parser_acceptsSupportedDraftRefs() {
        assertEquals("pipeline", DraftRef.tryParse("draft:pipeline:1").orElseThrow().kind());
        assertEquals("connector", DraftRef.tryParse("draft:connector:1").orElseThrow().kind());
    }

    @Test
    void parser_rejectsMalformedUnsupportedAndPartialRefs() {
        assertThrows(DraftRefException.class, () -> DraftRef.tryParse("draft:pipeline:0"));
        assertThrows(DraftRefException.class, () -> DraftRef.tryParse("draft:dataset:1"));
        assertThrows(DraftRefException.class, () -> DraftRef.tryParse("prefix-draft:pipeline:1"));
        assertThrows(DraftRefException.class, () -> DraftRef.tryParse("draft:pipeline:-1"));
    }

    @Test
    void validator_rejectsDuplicateDeclarationsAndUndeclaredUses() {
        List<PlanService.PlannedCommand> duplicate = List.of(
                createPipelineCommand("draft:pipeline:1"),
                createPipelineCommand("draft:pipeline:1"));
        DraftRefException duplicateEx = assertThrows(DraftRefException.class,
                () -> validator.deriveDeclarationsAndValidate(duplicate));
        assertEquals(PlanDraftRefErrorCodes.PLAN_DRAFT_REF_INVALID, duplicateEx.getErrorCode());

        List<PlanService.PlannedCommand> undeclared = List.of(
                new PlanService.PlannedCommand(
                        "composition.add_step",
                        "Pipeline",
                        "draft:pipeline:1",
                        "add step",
                        Map.of("pipelineId", "draft:pipeline:1")));
        DraftRefException undeclaredEx = assertThrows(DraftRefException.class,
                () -> validator.deriveDeclarationsAndValidate(undeclared));
        assertEquals(PlanDraftRefErrorCodes.PLAN_DRAFT_REF_UNDECLARED, undeclaredEx.getErrorCode());
    }

    @Test
    void validator_allowsOwnOutputDraftRefInAggregateIdAndUiIntent() {
        List<PlanService.PlannedCommand> valid = List.of(createPipelineCommand("draft:pipeline:1"));
        assertEquals(1, validator.deriveDeclarationsAndValidate(valid).size());

        List<PlanService.PlannedCommand> validUiIntent = List.of(
                new PlanService.PlannedCommand(
                        "connector.create",
                        "Connector",
                        "draft:connector:1",
                        "create connector",
                        Map.of("name", "Loan DB"),
                        List.of(new PlanService.CommandOutput("draft:connector:1", "aggregateId")),
                        Map.of(
                                "kind", "credential_attach",
                                "connectorInstanceId", "draft:connector:1",
                                "environment", "DEV")));
        assertEquals(1, validator.deriveDeclarationsAndValidate(validUiIntent).size());

        List<PlanService.PlannedCommand> invalid = List.of(
                new PlanService.PlannedCommand(
                        "pipeline.create",
                        "Pipeline",
                        "draft:pipeline:1",
                        "create pipeline",
                        Map.of("pipelineId", "draft:pipeline:1", "name", "Loan Master"),
                        List.of(new PlanService.CommandOutput("draft:pipeline:1", "aggregateId")),
                        Map.of()));
        DraftRefException ex = assertThrows(DraftRefException.class,
                () -> validator.deriveDeclarationsAndValidate(invalid));
        assertEquals(PlanDraftRefErrorCodes.PLAN_DRAFT_REF_INVALID, ex.getErrorCode());
    }

    @Test
    void substitutor_replacesNestedExactValuesWithoutMutatingOriginal() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pipelineId", "draft:pipeline:1");
        payload.put("items", List.of(
                "draft:pipeline:1",
                Map.of("pipelineId", "draft:pipeline:1")));

        Map<String, DraftRefBinding> bindings = Map.of(
                "draft:pipeline:1",
                new DraftRefBinding(
                        "draft:pipeline:1",
                        "pipeline",
                        "pipe-real-1",
                        0,
                        "cmd-1",
                        Instant.parse("2026-05-14T12:00:00Z")));

        Map<String, Object> resolved = substitutor.substituteMap(payload, bindings, false);

        assertEquals("pipe-real-1", resolved.get("pipelineId"));
        assertEquals("pipe-real-1", ((List<?>) resolved.get("items")).get(0));
        assertEquals("draft:pipeline:1", payload.get("pipelineId"));
    }

    private PlanService.PlannedCommand createPipelineCommand(String draftRef) {
        return new PlanService.PlannedCommand(
                "pipeline.create",
                "Pipeline",
                draftRef,
                "create pipeline",
                Map.of("name", "Loan Master", "description", "test"),
                List.of(new PlanService.CommandOutput(draftRef, "aggregateId")),
                Map.of());
    }
}
