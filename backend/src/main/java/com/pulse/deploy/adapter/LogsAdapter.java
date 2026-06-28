package com.pulse.deploy.adapter;

/**
 * Phase 7 — read-only adapter operation: fetch a normalized log
 * batch for a deployment run. Phase 8 lights up the rich Pulse-native
 * log views; Phase 7 just standardizes the envelope.
 */
public interface LogsAdapter {

    /**
     * Fetch up to {@code limit} log entries for {@code deploymentRunId},
     * oldest-first. Implementations may return an empty batch when the
     * runtime has no logs available yet.
     */
    RuntimeLogs fetch(String deploymentRunId, int limit);
}
