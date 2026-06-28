package com.pulse.deploy.adapter.dpc;

import com.pulse.deploy.run.DeploymentRunState;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(value = "pulse.deploy.runtime.dpc.enabled",
        havingValue = "false", matchIfMissing = true)
public class StubDpcAirflowClient implements DpcAirflowClient {

    @Override
    public SyncResult syncDags(SyncRequest request) {
        if (request == null) throw new IllegalArgumentException("request is required");
        if (request.dagFilePaths() == null) {
            throw new IllegalArgumentException("dagFilePaths is required");
        }
        List<String> names = new ArrayList<>(request.dagFilePaths().size());
        for (String path : request.dagFilePaths()) {
            names.add(deriveDagName(path));
        }
        String triggeredId = request.triggerImmediately()
                ? "stub-dpc-dagrun-" + (request.dagBucketUri() == null
                        ? "unknown"
                        : Integer.toHexString(request.dagBucketUri().hashCode()))
                : null;
        return new SyncResult(names, triggeredId);
    }

    @Override
    public DagRunStatus pollDagRun(String dpcAirflowEndpoint, String dagId, String dagRunId,
                                   String tokenReference) {
        if (dagRunId == null || dagRunId.isBlank()) {
            throw new IllegalArgumentException("dagRunId is required");
        }
        return new DagRunStatus(dagRunId, DeploymentRunState.SUCCEEDED, "success", null);
    }

    @Override
    public BrokerDagRun triggerDagRun(String logicalDagId, String dagRunId, Map<String, Object> conf) {
        if (logicalDagId == null || logicalDagId.isBlank()) {
            throw new IllegalArgumentException("logicalDagId is required");
        }
        return new BrokerDagRun(dagRunId == null || dagRunId.isBlank() ? "stub-broker-dagrun" : dagRunId, "queued");
    }

    @Override
    public void cancelDagRun(String dpcAirflowEndpoint, String dagId, String dagRunId,
                             String tokenReference) {
        if (dagRunId == null || dagRunId.isBlank()) {
            throw new IllegalArgumentException("dagRunId is required");
        }
    }

    private static String deriveDagName(String path) {
        if (path == null || path.isBlank()) return "unknown_dag";
        String name = path;
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        if (name.endsWith(".py")) name = name.substring(0, name.length() - 3);
        return name;
    }
}
