package com.pulse.support;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TC_fake_llm_client_records — FakeLlmClient returns recorded responses by request signature
 * and fails loudly with the missing signature when no recording matches.
 */
class FakeLlmClientTest {

    @Test
    void recordedSignature_replaysDeterministically() {
        FakeLlmClient client = new FakeLlmClient();
        client.recordMessage("schema_infer:loan_master.csv",
                "{\"columns\":[\"loan_id\",\"status\"]}");

        FakeLlmClient.Request request = FakeLlmClient.Request.of(
                "schema_infer:loan_master.csv",
                "openai/gpt-5.2",
                "You are PULSE schema inferrer.",
                "Infer schema from sample of loan_master.csv");
        FakeLlmClient.Response first = client.respond(request);
        FakeLlmClient.Response second;
        try {
            second = client.respond(request);
        } catch (FakeLlmClient.UnrecordedRequestException expected) {
            // single-response recording exhausts after one call — that's the deterministic contract
            second = null;
        }

        assertEquals(FakeLlmClient.Response.Kind.MESSAGE, first.kind());
        assertEquals("{\"columns\":[\"loan_id\",\"status\"]}", first.content());
        assertEquals(2, first.events().size(), "delta + done events generated for plain message");
        assertEquals("delta", first.events().get(0).event());
        assertEquals("done", first.events().get(1).event());
        assertTrue(second == null, "exhausted recording must not silently replay");
    }

    @Test
    void unrecordedSignature_failsWithSignatureInMessage() {
        FakeLlmClient client = new FakeLlmClient();
        FakeLlmClient.UnrecordedRequestException ex = assertThrows(
                FakeLlmClient.UnrecordedRequestException.class,
                () -> client.respond(FakeLlmClient.Request.of(
                        "dq_readiness:never_recorded",
                        "openai/gpt-5.2",
                        "system",
                        "user")));
        assertTrue(ex.getMessage().contains("dq_readiness:never_recorded"),
                "error must include the missing signature, got: " + ex.getMessage());
    }

    @Test
    void toolCallResponse_distinguishesFromMessage() {
        FakeLlmClient client = new FakeLlmClient();
        client.recordToolCall("chat:apply_plan",
                "create_pipeline",
                Map.of("name", "loan-master-curate"));

        FakeLlmClient.Response response = client.respond(FakeLlmClient.Request.of(
                "chat:apply_plan", "openai/gpt-5.2", "system", "user"));
        assertEquals(FakeLlmClient.Response.Kind.TOOL_CALL, response.kind(),
                "tool-call kind must be distinguishable from message kind");
        assertEquals("create_pipeline", response.toolName());
        assertEquals("loan-master-curate", response.arguments().get("name"));
        assertEquals("tool_call", response.events().get(0).event());
    }

    @Test
    void multipleResponses_replayInOrder() {
        FakeLlmClient client = new FakeLlmClient();
        client.record(new FakeLlmClient.Recording(
                "chat:multi_turn",
                FakeLlmClient.MatchSpec.matchAll(),
                List.of(
                        FakeLlmClient.Response.message("first"),
                        FakeLlmClient.Response.message("second"))));

        FakeLlmClient.Request req = FakeLlmClient.Request.of(
                "chat:multi_turn", "model", "system", "user");
        assertEquals("first", client.respond(req).content());
        assertEquals("second", client.respond(req).content());
        FakeLlmClient.UnrecordedRequestException exhausted = assertThrows(
                FakeLlmClient.UnrecordedRequestException.class,
                () -> client.respond(req));
        assertTrue(exhausted.getMessage().contains("exhausted"),
                "third call must explicitly indicate exhaustion: " + exhausted.getMessage());
    }
}
