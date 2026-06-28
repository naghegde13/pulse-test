package com.pulse.deploy.adapter;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * Phase 7 — adapter-agnostic log payload returned by
 * {@link LogsAdapter#fetch(String, int)}. Phase 8 lights up the rich
 * Pulse-native log views; Phase 7 only normalizes the envelope so the
 * UI / API can read every adapter with one parser.
 *
 * <p>Schema: {@code runtime-logs.v1}.
 *
 * @param schemaVersion         {@code runtime-logs.v1}
 * @param adapter               canonical target type key
 * @param providerRunId         provider-issued Airflow activation or
 *                              validation id this batch describes
 * @param truncated             {@code true} when {@code limit} clipped
 *                              the available history
 * @param fetchedAt             when the batch was fetched
 * @param entries               ordered, oldest-first
 */
public record RuntimeLogs(
        String schemaVersion,
        String adapter,
        String providerRunId,
        boolean truncated,
        Instant fetchedAt,
        List<LogEntry> entries
) {
    public static final String SCHEMA_VERSION = "runtime-logs.v1";

    public RuntimeLogs {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    /**
     * One log line. {@link #taskId} is {@code null} for adapter-level
     * messages that aren't tied to a single Airflow task.
     */
    public record LogEntry(
            Instant at,
            String level,
            String taskId,
            String message
    ) {}

    public static RuntimeLogs empty(String adapter, String providerRunId, Instant fetchedAt) {
        return new RuntimeLogs(SCHEMA_VERSION, adapter, providerRunId, false, fetchedAt, Collections.emptyList());
    }
}
