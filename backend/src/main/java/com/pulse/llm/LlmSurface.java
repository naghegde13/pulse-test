package com.pulse.llm;

public enum LlmSurface {
    CHAT,
    CHAT_REASONING,
    // ADR 0025 §2: the cheap/Flash chat tier for the Router/Discovery/Configure/
    // Provision/Responder graph stages. This is a NEW, dedicated chat-stage key
    // (pulse.llm.cheap-model / pulse.llm.vertex.cheap-chat-model) — it MUST NOT
    // reuse the dead SCHEMA_INFERENCE key (ADR 0011 retired model-based schema
    // inference; that key is not a chat-stage model).
    CHAT_CHEAP,
    STORY_GENERATION,
    SCHEMA_INFERENCE,
    DQ_READINESS,
    COBOL_DISCOVERY
}
