package com.pulse.chat.service;

/**
 * Deterministic conversation phase used by {@link PhaseDetector} to decide
 * which phase-gated prompt segments to inject. Eight values, ordered roughly
 * by pipeline construction lifecycle.
 */
public enum ConversationPhase {
    DISCOVERY,
    SOURCE_SETUP,
    INGESTION,
    SILVER,
    GOLD,
    DQ,
    ORCHESTRATION,
    REVIEW_BUILD
}
