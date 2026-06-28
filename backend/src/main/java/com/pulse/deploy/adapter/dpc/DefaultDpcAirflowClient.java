package com.pulse.deploy.adapter.dpc;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@ConditionalOnProperty(value = "pulse.deploy.runtime.dpc.enabled", havingValue = "true")
public class DefaultDpcAirflowClient implements DpcAirflowClient {

    @Override
    public SyncResult syncDags(SyncRequest request) {
        throw new UnsupportedOperationException(
                "DefaultDpcAirflowClient: production DPC Airflow wiring not yet enabled. "
                        + "Awaiting platform confirmation of the Airflow REST endpoint, auth model, "
                        + "and DAG-delivery semantics. Disable pulse.deploy.runtime.dpc.enabled to "
                        + "fall back to StubDpcAirflowClient.");
    }

    @Override
    public DagRunStatus pollDagRun(String dpcAirflowEndpoint, String dagId, String dagRunId,
                                   String tokenReference) {
        throw new UnsupportedOperationException(
                "DefaultDpcAirflowClient: production DPC Airflow wiring not yet enabled.");
    }

    @Override
    public BrokerDagRun triggerDagRun(String logicalDagId, String dagRunId, Map<String, Object> conf) {
        throw new UnsupportedOperationException(
                "DefaultDpcAirflowClient: broker trigger wiring not yet enabled.");
    }

    @Override
    public void cancelDagRun(String dpcAirflowEndpoint, String dagId, String dagRunId,
                             String tokenReference) {
        throw new UnsupportedOperationException(
                "DefaultDpcAirflowClient: broker cancellation wiring not yet enabled.");
    }
}
