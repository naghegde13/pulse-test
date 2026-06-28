package com.pulse.cobol.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.cobol.model.CobolDiscoveryArtifact;
import com.pulse.cobol.model.CobolDiscoveryMessage;
import com.pulse.cobol.model.CobolDiscoveryRun;
import com.pulse.cobol.model.CobolParsingProfile;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CobolDiscoveryAssistantServiceTest {

    @Test
    void buildUserPromptUsesSafeContextWithoutPreviewRows() throws IOException {
        CobolDiscoveryAssistantService service = new CobolDiscoveryAssistantService(
                new ObjectMapper(),
                "",
                "https://api.openai.com/v1",
                "o4-mini"
        );

        CobolDiscoveryRun run = new CobolDiscoveryRun();
        run.setStatus("COMPLETED");
        run.setConfidenceScore(87.5);
        run.setConfigSnapshot(Map.of("record_format", "F"));
        run.setProfilingSummary(Map.of("rowCount", 2, "columnCount", 4));
        run.setAnomalySummary(Map.of("warnings", List.of()));
        run.setResultSchemaSnapshot(Map.of("fields", List.of(Map.of("name", "account_no", "type", "string"))));
        run.setMappingSpec(List.of(Map.of("sourcePath", "HEADER.ACCOUNT-NO", "outputColumn", "account_no")));
        run.setPreviewRows(List.of(Map.of("account_no", "SHOULD_NOT_BE_INCLUDED")));

        CobolDiscoveryMessage message = new CobolDiscoveryMessage();
        message.setRole("USER");
        message.setContent("the preview is shifted");

        CobolDiscoveryArtifact artifact = new CobolDiscoveryArtifact();
        artifact.setArtifactType("data_file");
        artifact.setOriginalFilename("customers.ebc");
        artifact.setCleanupStatus("ACTIVE");

        CobolParsingProfile profile = new CobolParsingProfile();
        profile.setName("customer_profile");

        String prompt = service.buildUserPrompt(
                "the preview is shifted",
                true,
                true,
                run,
                "      01 CUSTOMER-REC.\n         05 ACCOUNT-NO PIC X(10).",
                List.of(message),
                List.of(artifact),
                List.of(profile)
        );

        assertTrue(prompt.contains("Copybook text:"));
        assertTrue(prompt.contains("record_format"));
        assertTrue(prompt.contains("customer_profile"));
        assertFalse(prompt.contains("SHOULD_NOT_BE_INCLUDED"));
    }

    @Test
    void fallbackResponseSuggestsSavingWhenUserAsksToSave() throws IOException {
        CobolDiscoveryAssistantService service = new CobolDiscoveryAssistantService(
                new ObjectMapper(),
                "",
                "https://api.openai.com/v1",
                "o4-mini"
        );

        CobolDiscoveryRun run = new CobolDiscoveryRun();
        run.setStatus("COMPLETED");
        run.setConfidenceScore(96.0);
        run.setAnomalySummary(Map.of("warnings", List.of()));

        String response = service.respond(
                "should I save this?",
                true,
                true,
                run,
                "",
                List.of(),
                List.of(),
                List.of()
        );

        assertTrue(response.toLowerCase().contains("save"));
        assertTrue(response.toLowerCase().contains("profile"));
    }

    @Test
    void plansConfigUpdateAndPreviewRunFromPriorAssistantJson() throws IOException {
        CobolDiscoveryAssistantService service = new CobolDiscoveryAssistantService(
                new ObjectMapper(),
                "",
                "https://api.openai.com/v1",
                "o4-mini"
        );

        CobolDiscoveryMessage priorAssistant = new CobolDiscoveryMessage();
        priorAssistant.setRole("ASSISTANT");
        priorAssistant.setContent("""
                Here’s the updated Cobrix config:

                ```json
                {
                  "_candidate_label": "with-multisegment",
                  "ebcdic_code_page": "cp037",
                  "schema_retention_policy": "collapse_root",
                  "segment_field": "SEGMENT-ID",
                  "redefine_segment_id_map": {
                    "C": "COMPANY",
                    "P": "PERSON",
                    "B": "PO-BOX"
                  }
                }
                ```
                """);

        CobolDiscoveryAssistantService.ActionPlan plan = service.planActions(
                "Can you update the config and kick off the run for me?",
                Map.of("ebcdic_code_page", "cp037"),
                null,
                "",
                List.of(priorAssistant),
                true,
                true
        );

        assertTrue(plan.hasActions());
        assertEquals("preview", plan.runType());
        assertEquals("SEGMENT-ID", plan.optionOverrides().get("segment_field"));
        @SuppressWarnings("unchecked")
        Map<String, Object> redefineMap = (Map<String, Object>) plan.optionOverrides().get("redefine_segment_id_map");
        assertEquals("COMPANY", redefineMap.get("C"));
        assertTrue(plan.assistantMessage().toLowerCase().contains("queued"));
    }

    @Test
    void plansFullConfigFromPriorProseRecommendationAndNaturalLanguagePreviewRequest() throws IOException {
        CobolDiscoveryAssistantService service = new CobolDiscoveryAssistantService(
                new ObjectMapper(),
                "",
                "https://api.openai.com/v1",
                "o4-mini"
        );

        CobolDiscoveryMessage priorAssistant = new CobolDiscoveryMessage();
        priorAssistant.setRole("ASSISTANT");
        priorAssistant.setContent("""
                our fields are being garbled. Next try enabling multi-segment parsing. Add these settings:

                • ebcdic_code_page: "cp037"
                • schema_retention_policy: "collapse_root"
                • segment_field: "SEGMENT_ID"
                • redefine_segment_id_map:
                  {
                    "C": "COMPANY",
                    "P": "PERSON",
                    "B": "PO-BOX"
                  }
                """);

        CobolDiscoveryAssistantService.ActionPlan plan = service.planActions(
                "Can you update the config and run the preview for me the way you described?",
                Map.of(),
                null,
                "",
                List.of(priorAssistant),
                true,
                true
        );

        assertTrue(plan.hasActions());
        assertEquals("preview", plan.runType());
        assertEquals("cp037", plan.optionOverrides().get("ebcdic_code_page"));
        assertEquals("collapse_root", plan.optionOverrides().get("schema_retention_policy"));
        assertEquals("SEGMENT_ID", plan.optionOverrides().get("segment_field"));
        @SuppressWarnings("unchecked")
        Map<String, Object> redefineMap = (Map<String, Object>) plan.optionOverrides().get("redefine_segment_id_map");
        assertEquals("PO-BOX", redefineMap.get("B"));
    }

    @Test
    void respondStructuredFallbackDoesNotInventSpecializedConfig() throws IOException {
        CobolDiscoveryAssistantService service = new CobolDiscoveryAssistantService(
                new ObjectMapper(),
                "",
                "https://api.openai.com/v1",
                "o4-mini"
        );

        CobolDiscoveryRun run = new CobolDiscoveryRun();
        run.setStatus("COMPLETED");
        run.setConfidenceScore(61.0);
        run.setProfilingSummary(Map.of(
                "copybookSummary", Map.of(
                        "hasRedefines", true,
                        "recommendSegmentMapping", true
                )
        ));
        run.setAnomalySummary(Map.of("warnings", List.of("Columns look shifted")));

        String copybook = """
                01 ENTITY.
                   05 SEGMENT-ID        PIC X(1).
                   05 COMPANY.
                      10 COMPANY-NAME    PIC X(20).
                   05 PERSON.
                      10 FIRST-NAME      PIC X(10).
                   05 PO-BOX.
                      10 BOX-ID          PIC X(5).
                """;

        CobolDiscoveryAssistantService.AssistantReply reply = service.respondStructured(
                "this preview is wrong",
                true,
                true,
                run,
                copybook,
                List.of(),
                List.of(),
                List.of()
        );

        assertFalse(reply.content().contains("```json"));
        assertTrue(reply.metadata().isEmpty());
    }

    @Test
    void plansConfigUpdateFromPropertiesStyleRecommendation() throws IOException {
        CobolDiscoveryAssistantService service = new CobolDiscoveryAssistantService(
                new ObjectMapper(),
                "",
                "https://api.openai.com/v1",
                "o4-mini"
        );

        CobolDiscoveryMessage priorAssistant = new CobolDiscoveryMessage();
        priorAssistant.setRole("ASSISTANT");
        priorAssistant.setContent(
                """
                Use this exact config:

                ```
                ebcdic_code_page=cp037
                schema_retention_policy=collapse_root
                record_format=F
                record_length=2202
                debug=true
                ```
                """
        );

        CobolDiscoveryAssistantService.ActionPlan plan = service.planActions(
                "Apply that config and rerun the preview for me.",
                Map.of(),
                null,
                "",
                List.of(priorAssistant),
                true,
                true
        );

        assertEquals("F", plan.optionOverrides().get("record_format"));
        assertEquals("2202", plan.optionOverrides().get("record_length"));
        assertEquals("true", plan.optionOverrides().get("debug"));
    }

    @Test
    void plansCopybookUpdateAndPreviewRunFromPriorAssistantMetadata() throws IOException {
        CobolDiscoveryAssistantService service = new CobolDiscoveryAssistantService(
                new ObjectMapper(),
                "",
                "https://api.openai.com/v1",
                "o4-mini"
        );

        CobolDiscoveryMessage priorAssistant = new CobolDiscoveryMessage();
        priorAssistant.setRole("ASSISTANT");
        priorAssistant.setContent("The copybook layout itself is wrong. I prepared a corrected raw copybook text and the next run should use it.");
        priorAssistant.setMetadata(Map.of(
                "recommendedCopybookText", """
                        01 ENTITY.
                           05 SEGMENT-ID PIC X(1).
                           05 COMPANY.
                              10 NAME PIC X(20).
                        """,
                "recommendedRunType", "preview"
        ));

        CobolDiscoveryAssistantService.ActionPlan plan = service.planActions(
                "Can you update the copybook and run the preview for me the way you described?",
                Map.of("ebcdic_code_page", "cp037"),
                null,
                "",
                List.of(priorAssistant),
                true,
                true
        );

        assertTrue(plan.hasActions());
        assertEquals("preview", plan.runType());
        assertTrue(plan.copybookText().contains("SEGMENT-ID"));
        assertTrue(plan.toolRequests().stream().anyMatch(request -> "update_copybook_text".equals(request.name())));
        assertTrue(plan.assistantMessage().toLowerCase().contains("copybook"));
    }

    // ===== Config extraction edge cases =====

    @Test
    void extractConfigFromText_handlesBareJsonWithoutFence() throws IOException {
        CobolDiscoveryAssistantService service = new CobolDiscoveryAssistantService(
                new ObjectMapper(), "", "https://api.openai.com/v1", "o4-mini");

        CobolDiscoveryMessage priorAssistant = new CobolDiscoveryMessage();
        priorAssistant.setRole("ASSISTANT");
        priorAssistant.setContent("{\"record_format\": \"V\", \"is_rdw_big_endian\": \"true\"}");

        CobolDiscoveryAssistantService.ActionPlan plan = service.planActions(
                "apply the config",
                Map.of(),
                null,
                "",
                List.of(priorAssistant),
                true,
                true
        );

        assertEquals("V", plan.optionOverrides().get("record_format"));
        assertEquals("true", plan.optionOverrides().get("is_rdw_big_endian"));
    }

    @Test
    void extractConfigFromText_returnsEmptyForPlainText() throws IOException {
        CobolDiscoveryAssistantService service = new CobolDiscoveryAssistantService(
                new ObjectMapper(), "", "https://api.openai.com/v1", "o4-mini");

        CobolDiscoveryMessage priorAssistant = new CobolDiscoveryMessage();
        priorAssistant.setRole("ASSISTANT");
        priorAssistant.setContent("hello world");

        CobolDiscoveryAssistantService.ActionPlan plan = service.planActions(
                "update the config",
                Map.of(),
                null,
                "",
                List.of(priorAssistant),
                true,
                true
        );

        assertTrue(plan.optionOverrides().isEmpty());
        assertTrue(plan.assistantMessage().toLowerCase().contains("find a valid json cobrix config"));
    }

    @Test
    void extractConfigFromText_handlesEqualsDelimitedConfig() throws IOException {
        CobolDiscoveryAssistantService service = new CobolDiscoveryAssistantService(
                new ObjectMapper(), "", "https://api.openai.com/v1", "o4-mini");

        CobolDiscoveryMessage priorAssistant = new CobolDiscoveryMessage();
        priorAssistant.setRole("ASSISTANT");
        priorAssistant.setContent("record_format=VB\nebcdic_code_page=cp037");

        CobolDiscoveryAssistantService.ActionPlan plan = service.planActions(
                "apply the config",
                Map.of(),
                null,
                "",
                List.of(priorAssistant),
                true,
                true
        );

        assertEquals("VB", plan.optionOverrides().get("record_format"));
        assertEquals("cp037", plan.optionOverrides().get("ebcdic_code_page"));
    }

    // ===== Copybook text extraction =====

    @Test
    void planActions_extractsCopybookFromCobolFence() throws IOException {
        CobolDiscoveryAssistantService service = new CobolDiscoveryAssistantService(
                new ObjectMapper(), "", "https://api.openai.com/v1", "o4-mini");

        CobolDiscoveryAssistantService.ActionPlan plan = service.planActions(
                "update the copybook\n```cobol\n01 ROOT.\n   05 FIELD PIC X(5).\n```",
                Map.of("ebcdic_code_page", "cp037"),
                null,
                "",
                List.of(),
                true,
                true
        );

        assertTrue(plan.copybookText().contains("FIELD"));
        assertTrue(plan.toolRequests().stream().anyMatch(r -> "update_copybook_text".equals(r.name())));
    }

    @Test
    void planActions_returnsEmptyCopybookWhenNoFence() throws IOException {
        CobolDiscoveryAssistantService service = new CobolDiscoveryAssistantService(
                new ObjectMapper(), "", "https://api.openai.com/v1", "o4-mini");

        CobolDiscoveryAssistantService.ActionPlan plan = service.planActions(
                "update the copybook",
                Map.of("ebcdic_code_page", "cp037"),
                null,
                "",
                List.of(),
                true,
                true
        );

        assertTrue(plan.copybookText().isEmpty());
        assertTrue(plan.assistantMessage().toLowerCase().contains("find a full revised copybook text"));
    }

    // ===== Loop decision fallback =====

    @Test
    void fallbackResponse_suggestsCopybookWhenNoCopybook() throws IOException {
        CobolDiscoveryAssistantService service = new CobolDiscoveryAssistantService(
                new ObjectMapper(), "", "https://api.openai.com/v1", "o4-mini");

        String response = service.respond(
                "what should I do next?",
                false,
                false,
                null,
                "",
                List.of(),
                List.of(),
                List.of()
        );

        assertTrue(response.toLowerCase().contains("copybook"));
    }

    @Test
    void fallbackResponse_suggestsDataFileWhenNoDataFile() throws IOException {
        CobolDiscoveryAssistantService service = new CobolDiscoveryAssistantService(
                new ObjectMapper(), "", "https://api.openai.com/v1", "o4-mini");

        String response = service.respond(
                "what should I do next?",
                true,
                false,
                null,
                "",
                List.of(),
                List.of(),
                List.of()
        );

        assertTrue(response.toLowerCase().contains("data file"));
    }

    @Test
    void fallbackResponse_suggestsPreviewWhenNoLatestRun() throws IOException {
        CobolDiscoveryAssistantService service = new CobolDiscoveryAssistantService(
                new ObjectMapper(), "", "https://api.openai.com/v1", "o4-mini");

        String response = service.respond(
                "what should I do next?",
                true,
                true,
                null,
                "",
                List.of(),
                List.of(),
                List.of()
        );

        assertTrue(response.toLowerCase().contains("preview"));
    }

    @Test
    void fallbackResponse_suggestsOccursHandlingWhenCopybookHasOccurs() throws IOException {
        CobolDiscoveryAssistantService service = new CobolDiscoveryAssistantService(
                new ObjectMapper(), "", "https://api.openai.com/v1", "o4-mini");

        CobolDiscoveryRun run = new CobolDiscoveryRun();
        run.setStatus("COMPLETED");
        run.setConfidenceScore(75.0);
        run.setProfilingSummary(Map.of("copybookSummary", Map.of("hasOccurs", true)));
        run.setAnomalySummary(Map.of("warnings", List.of()));

        String response = service.respond(
                "what should I do next?",
                true,
                true,
                run,
                "",
                List.of(),
                List.of(),
                List.of()
        );

        assertTrue(response.contains("variable_size_occurs") || response.contains("OCCURS"));
    }

    @Test
    void fallbackResponse_suggestsRedefinesHandlingWhenCopybookHasRedefines() throws IOException {
        CobolDiscoveryAssistantService service = new CobolDiscoveryAssistantService(
                new ObjectMapper(), "", "https://api.openai.com/v1", "o4-mini");

        CobolDiscoveryRun run = new CobolDiscoveryRun();
        run.setStatus("COMPLETED");
        run.setConfidenceScore(75.0);
        run.setProfilingSummary(Map.of("copybookSummary", Map.of("hasRedefines", true)));
        run.setAnomalySummary(Map.of("warnings", List.of()));

        String response = service.respond(
                "what should I do next?",
                true,
                true,
                run,
                "",
                List.of(),
                List.of(),
                List.of()
        );

        assertTrue(response.contains("REDEFINES") || response.contains("segment"));
    }

    @Test
    void fallbackResponse_suggestsFramingWhenColumnsShifted() throws IOException {
        CobolDiscoveryAssistantService service = new CobolDiscoveryAssistantService(
                new ObjectMapper(), "", "https://api.openai.com/v1", "o4-mini");

        CobolDiscoveryRun run = new CobolDiscoveryRun();
        run.setStatus("COMPLETED");
        run.setConfidenceScore(60.0);
        run.setProfilingSummary(Map.of());
        run.setAnomalySummary(Map.of("warnings", List.of()));

        String response = service.respond(
                "the preview is shifted",
                true,
                true,
                run,
                "",
                List.of(),
                List.of(),
                List.of()
        );

        assertTrue(response.contains("record_format"));
    }

    // ===== Action plan edge cases =====

    @Test
    void planActions_returnsNoneForUnrelatedMessage() throws IOException {
        CobolDiscoveryAssistantService service = new CobolDiscoveryAssistantService(
                new ObjectMapper(), "", "https://api.openai.com/v1", "o4-mini");

        CobolDiscoveryAssistantService.ActionPlan plan = service.planActions(
                "what is EBCDIC?",
                Map.of(),
                null,
                "",
                List.of(),
                true,
                true
        );

        assertFalse(plan.hasActions());
        assertNull(plan.runType());
        assertTrue(plan.assistantMessage().isEmpty());
    }

    @Test
    void planActions_detectsProfileRunRequest() throws IOException {
        CobolDiscoveryAssistantService service = new CobolDiscoveryAssistantService(
                new ObjectMapper(), "", "https://api.openai.com/v1", "o4-mini");

        CobolDiscoveryAssistantService.ActionPlan plan = service.planActions(
                "run the full profile for me",
                Map.of(),
                null,
                "",
                List.of(),
                true,
                true
        );

        assertEquals("profile", plan.runType());
        assertTrue(plan.assistantMessage().toLowerCase().contains("profile"));
    }

    @Test
    void planActions_detectsPreviewRunFromKickOff() throws IOException {
        CobolDiscoveryAssistantService service = new CobolDiscoveryAssistantService(
                new ObjectMapper(), "", "https://api.openai.com/v1", "o4-mini");

        CobolDiscoveryAssistantService.ActionPlan plan = service.planActions(
                "kick off a preview run",
                Map.of(),
                null,
                "",
                List.of(),
                true,
                true
        );

        assertEquals("preview", plan.runType());
        assertTrue(plan.assistantMessage().toLowerCase().contains("queued"));
    }

    @Test
    void planActions_detectsRerunRequest() throws IOException {
        CobolDiscoveryAssistantService service = new CobolDiscoveryAssistantService(
                new ObjectMapper(), "", "https://api.openai.com/v1", "o4-mini");

        CobolDiscoveryAssistantService.ActionPlan plan = service.planActions(
                "rerun the preview",
                Map.of(),
                null,
                "",
                List.of(),
                true,
                true
        );

        assertEquals("preview", plan.runType());
    }

    @Test
    void planActions_blocksRunWhenMissingCopybook() throws IOException {
        CobolDiscoveryAssistantService service = new CobolDiscoveryAssistantService(
                new ObjectMapper(), "", "https://api.openai.com/v1", "o4-mini");

        CobolDiscoveryAssistantService.ActionPlan plan = service.planActions(
                "run preview",
                Map.of(),
                null,
                "",
                List.of(),
                false,
                true
        );

        assertNull(plan.runType());
        assertTrue(plan.assistantMessage().toLowerCase().contains("artifacts"));
    }

    @Test
    void planActions_blocksRunWhenMissingDataFile() throws IOException {
        CobolDiscoveryAssistantService service = new CobolDiscoveryAssistantService(
                new ObjectMapper(), "", "https://api.openai.com/v1", "o4-mini");

        CobolDiscoveryAssistantService.ActionPlan plan = service.planActions(
                "run preview",
                Map.of(),
                null,
                "",
                List.of(),
                true,
                false
        );

        assertNull(plan.runType());
        assertTrue(plan.assistantMessage().toLowerCase().contains("artifacts"));
    }

    @Test
    void planActions_detectsConfigUpdateRequest() throws IOException {
        CobolDiscoveryAssistantService service = new CobolDiscoveryAssistantService(
                new ObjectMapper(), "", "https://api.openai.com/v1", "o4-mini");

        CobolDiscoveryMessage priorAssistant = new CobolDiscoveryMessage();
        priorAssistant.setRole("ASSISTANT");
        priorAssistant.setContent("```json\n{\"record_format\": \"F\", \"record_length\": \"800\"}\n```");

        CobolDiscoveryAssistantService.ActionPlan plan = service.planActions(
                "update the config for me",
                Map.of(),
                null,
                "",
                List.of(priorAssistant),
                true,
                true
        );

        assertTrue(plan.hasActions());
        assertEquals("F", plan.optionOverrides().get("record_format"));
        assertTrue(plan.toolRequests().stream().anyMatch(r -> "update_config".equals(r.name())));
    }

    @Test
    void planActions_detectsPreviewResultsRequest() throws IOException {
        CobolDiscoveryAssistantService service = new CobolDiscoveryAssistantService(
                new ObjectMapper(), "", "https://api.openai.com/v1", "o4-mini");

        CobolDiscoveryAssistantService.ActionPlan plan = service.planActions(
                "review the preview",
                Map.of(),
                null,
                "",
                List.of(),
                true,
                true
        );

        assertTrue(plan.retrievePreviewResults());
        assertTrue(plan.toolRequests().stream().anyMatch(r -> "retrieve_preview_results".equals(r.name())));
    }

    @Test
    void planActions_extractsConfigFromAssistantMetadataRecommendedConfig() throws IOException {
        CobolDiscoveryAssistantService service = new CobolDiscoveryAssistantService(
                new ObjectMapper(), "", "https://api.openai.com/v1", "o4-mini");

        CobolDiscoveryMessage priorAssistant = new CobolDiscoveryMessage();
        priorAssistant.setRole("ASSISTANT");
        priorAssistant.setContent("I recommend the following config changes.");
        priorAssistant.setMetadata(Map.of(
                "recommendedConfig", Map.of("record_format", "F", "ebcdic_code_page", "cp037"),
                "recommendedRunType", "preview"
        ));

        CobolDiscoveryAssistantService.ActionPlan plan = service.planActions(
                "apply the config and run it",
                Map.of(),
                null,
                "",
                List.of(priorAssistant),
                true,
                true
        );

        assertTrue(plan.hasActions());
        assertEquals("preview", plan.runType());
        assertEquals("F", plan.optionOverrides().get("record_format"));
        assertEquals("cp037", plan.optionOverrides().get("ebcdic_code_page"));
    }

    @Test
    void planActions_detectsReferenceToDescription() throws IOException {
        CobolDiscoveryAssistantService service = new CobolDiscoveryAssistantService(
                new ObjectMapper(), "", "https://api.openai.com/v1", "o4-mini");

        CobolDiscoveryMessage priorAssistant = new CobolDiscoveryMessage();
        priorAssistant.setRole("ASSISTANT");
        priorAssistant.setContent("I recommend adjusting the config.");
        priorAssistant.setMetadata(Map.of(
                "recommendedConfig", Map.of("record_format", "V", "is_rdw_big_endian", "true")
        ));

        CobolDiscoveryAssistantService.ActionPlan plan = service.planActions(
                "do it the way you described",
                Map.of(),
                null,
                "",
                List.of(priorAssistant),
                true,
                true
        );

        assertTrue(plan.hasActions());
        assertEquals("preview", plan.runType());
        assertEquals("V", plan.optionOverrides().get("record_format"));
        assertEquals("true", plan.optionOverrides().get("is_rdw_big_endian"));
    }

    @Test
    void respondStructured_fallbackIncludesRunStatus() throws IOException {
        CobolDiscoveryAssistantService service = new CobolDiscoveryAssistantService(
                new ObjectMapper(), "", "https://api.openai.com/v1", "o4-mini");

        CobolDiscoveryRun run = new CobolDiscoveryRun();
        run.setStatus("COMPLETED");
        run.setConfidenceScore(72.0);
        run.setProfilingSummary(Map.of("copybookSummary", Map.of("hasOccurs", false, "hasRedefines", false)));
        run.setAnomalySummary(Map.of("warnings", List.of("custom warning text")));

        CobolDiscoveryAssistantService.AssistantReply reply = service.respondStructured(
                "what do you think?",
                true,
                true,
                run,
                "",
                List.of(),
                List.of(),
                List.of()
        );

        assertNotNull(reply.content());
        assertTrue(reply.content().contains("COMPLETED"));
        assertTrue(reply.content().contains("72.0"));
        assertTrue(reply.content().contains("custom warning text"));
    }
}
