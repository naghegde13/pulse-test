package com.pulse.deploy.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * ARCH-007 — Stub implementation of {@link AirflowRuntimeClient} used in
 * local development and test profiles where no live Airflow instance is
 * available.
 *
 * <p>Always returns an empty list (no active runs), allowing deployment
 * preflight checks to pass without a real orchestrator.</p>
 */
@Component
public class StubAirflowRuntimeClient implements AirflowRuntimeClient {

    private static final Logger log = LoggerFactory.getLogger(StubAirflowRuntimeClient.class);

    @Override
    public List<ActiveDagRun> listActiveDagRuns(String logicalDagId, String targetId, String environment) {
        log.debug("Stub Airflow client: no active runs (local/test mode)");
        return List.of();
    }
}
