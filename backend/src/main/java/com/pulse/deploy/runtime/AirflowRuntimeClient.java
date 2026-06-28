package com.pulse.deploy.runtime;

import java.time.Instant;
import java.util.List;

/**
 * ARCH-007 — Airflow runtime client abstraction for active-run checks.
 *
 * <p>Implementations talk to the Airflow REST API (or return stubs for
 * local/test mode) to determine whether a logical DAG currently has
 * running executions that would conflict with a new deployment or
 * re-trigger.</p>
 */
public interface AirflowRuntimeClient {

    /**
     * List DAG runs in an active state ({@code queued}, {@code running})
     * for the given logical DAG in the target environment.
     *
     * @param logicalDagId the PULSE-assigned logical DAG identifier
     * @param targetId     the deployment target / Airflow instance id
     * @param environment  environment slug (dev, integration, uat, prod)
     * @return active runs — empty list when none are running or the
     *         Airflow instance is unreachable in stub mode
     */
    List<ActiveDagRun> listActiveDagRuns(String logicalDagId, String targetId, String environment);

    /**
     * Convenience check — returns {@code true} when at least one active
     * run exists.  Default implementation delegates to
     * {@link #listActiveDagRuns}.
     */
    default boolean isActiveRunPresent(String logicalDagId, String targetId, String environment) {
        return !listActiveDagRuns(logicalDagId, targetId, environment).isEmpty();
    }

    // ------------------------------------------------------------------ value types

    record ActiveDagRun(
            String dagRunId,
            String dagId,
            String state,
            Instant startDate
    ) {}
}
