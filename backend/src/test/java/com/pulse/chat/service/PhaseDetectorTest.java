package com.pulse.chat.service;

import com.pulse.blueprint.model.Blueprint;
import com.pulse.blueprint.model.BlueprintCategory;
import com.pulse.blueprint.repository.BlueprintRepository;
import com.pulse.chat.model.ChatMessage;
import com.pulse.chat.repository.ChatMessageRepository;
import com.pulse.pipeline.repository.PipelineRepository;
import com.pulse.pipeline.service.CompositionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(MockitoExtension.class)
class PhaseDetectorTest {

    @Mock private ChatMessageRepository messageRepo;
    @Mock private PipelineRepository pipelineRepo;
    @Mock private BlueprintRepository blueprintRepo;
    @Mock private CompositionService compositionService;

    private PhaseDetector detector() {
        return new PhaseDetector(messageRepo, pipelineRepo, blueprintRepo, compositionService);
    }

    @Test
    void discoveryWhenNoSignals() {
        assertEquals(ConversationPhase.DISCOVERY,
                detector().detectFromInputs(List.of(), List.of()));
    }

    @Test
    void sourceSetupWhenListingDataSources() {
        ChatMessage toolCall = new ChatMessage();
        toolCall.setRole("tool_call");
        toolCall.setContent("list_data_sources");
        toolCall.setToolResults(Map.of("tool_name", "list_data_sources"));
        assertEquals(ConversationPhase.SOURCE_SETUP,
                detector().detectFromInputs(List.of(toolCall), List.of()));
    }

    @Test
    void ingestionWhenBronzeKeyword() {
        ChatMessage user = userMessage("set up bronze ingest for employees");
        assertEquals(ConversationPhase.INGESTION,
                detector().detectFromInputs(List.of(user), List.of()));
    }

    @Test
    void silverWhenCleanKeyword() {
        ChatMessage user = userMessage("clean the silver layer");
        assertEquals(ConversationPhase.SILVER,
                detector().detectFromInputs(List.of(user), List.of()));
    }

    @Test
    void goldWhenFactKeyword() {
        ChatMessage user = userMessage("build a fact table for revenue");
        assertEquals(ConversationPhase.GOLD,
                detector().detectFromInputs(List.of(user), List.of()));
    }

    @Test
    void dqWhenExpectationKeyword() {
        ChatMessage user = userMessage("add GX expectations for not-null checks");
        assertEquals(ConversationPhase.DQ,
                detector().detectFromInputs(List.of(user), List.of()));
    }

    @Test
    void orchestrationWhenScheduleKeyword() {
        ChatMessage user = userMessage("set the cron schedule to daily 06:00");
        assertEquals(ConversationPhase.ORCHESTRATION,
                detector().detectFromInputs(List.of(user), List.of()));
    }

    @Test
    void reviewBuildWhenApprovalKeyword() {
        ChatMessage user = userMessage("plan looks good, build it");
        assertEquals(ConversationPhase.REVIEW_BUILD,
                detector().detectFromInputs(List.of(user), List.of()));
    }

    @Test
    void priorityOrderReviewBuildBeatsOrchestration() {
        ChatMessage user = userMessage("plan looks good, build it, and confirm schedule");
        assertEquals(ConversationPhase.REVIEW_BUILD,
                detector().detectFromInputs(List.of(user), List.of()));
    }

    @Test
    void compositionWithGxCheckpointForcesDq() {
        ChatMessage user = userMessage("tell me about this pipeline");
        Blueprint dq = new Blueprint();
        dq.setBlueprintKey("DQValidator");
        dq.setCategory(BlueprintCategory.DATA_QUALITY);
        dq.setArtifactTypes(List.of("gx_checkpoint"));
        assertEquals(ConversationPhase.DQ,
                detector().detectFromInputs(List.of(user), List.of(dq)));
    }

    private ChatMessage userMessage(String content) {
        ChatMessage m = new ChatMessage();
        m.setRole("user");
        m.setContent(content);
        return m;
    }
}
