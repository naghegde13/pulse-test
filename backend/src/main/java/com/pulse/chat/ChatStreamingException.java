package com.pulse.chat;

/**
 * Typed exception for failures that occur while streaming LLM/tool responses
 * over the SSE chat endpoint.
 *
 * Carries a stable {@link #getCode()} plus an optional upstream-error body
 * captured from the LLM provider (e.g. OpenRouter 400 response body) so that
 * the chat panel can render an actionable inline error instead of the generic
 * "Sorry, I encountered an error."
 *
 * See:
 * - BUG-2026-05-25-57 (chat streaming errors lost to ProblemDetail JSON
 *   converter on a text/event-stream response).
 * - BUG-2026-05-25-56 (compounded by 57: the operator never saw the real
 *   OpenRouter 400 payload that pointed at the malformed tool_calls wrapper).
 */
public class ChatStreamingException extends RuntimeException {

    public static final String CODE_UPSTREAM_LLM_ERROR = "CHAT_UPSTREAM_LLM_ERROR";
    public static final String CODE_STREAM_INTERRUPTED = "CHAT_STREAM_INTERRUPTED";
    public static final String CODE_STREAM_ERROR = "CHAT_STREAM_ERROR";

    private final String code;
    private final String upstreamBody;

    public ChatStreamingException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.upstreamBody = null;
    }

    public ChatStreamingException(String code, String message, String upstreamBody, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.upstreamBody = upstreamBody;
    }

    public String getCode() {
        return code;
    }

    /**
     * Raw upstream-provider error body (e.g. OpenRouter's JSON error object).
     * May be null when the failure was not produced by the LLM provider.
     */
    public String getUpstreamBody() {
        return upstreamBody;
    }
}
