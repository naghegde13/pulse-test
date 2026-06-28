package com.pulse.deploy.adapter.gcp;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 7 — default in-process stub for {@link ComposerDagSyncClient}.
 * Returns deterministic synced DAG names and a synthetic triggered
 * DAG run id (when {@code triggerImmediately} is set) without making
 * a network call.
 */
@Component
@ConditionalOnProperty(value = "pulse.deploy.runtime.gcp.enabled",
        havingValue = "false", matchIfMissing = true)
public class StubComposerDagSyncClient implements ComposerDagSyncClient {

    @Override
    public SyncResult syncDags(SyncRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        if (request.dagFilePaths() == null) {
            throw new IllegalArgumentException("dagFilePaths is required");
        }
        // DAG names are derived deterministically from file paths so
        // two identical packages produce the same sync result.
        List<String> names = new ArrayList<>(request.dagFilePaths().size());
        for (String path : request.dagFilePaths()) {
            names.add(deriveDagName(path));
        }
        String triggeredRunId = request.triggerImmediately()
                ? "stub-composer-run-" + (request.gcsPackagePrefix() == null
                        ? "unknown" : Integer.toHexString(request.gcsPackagePrefix().hashCode()))
                : null;
        return new SyncResult(names, triggeredRunId);
    }

    @Override
    public DagRunStatus pollDagRun(String composerEnvironment, String dagId, String dagRunId,
                                   String tokenReference) {
        if (dagRunId == null || dagRunId.isBlank()) {
            throw new IllegalArgumentException("dagRunId is required");
        }
        return new DagRunStatus(dagRunId, com.pulse.deploy.run.DeploymentRunState.SUCCEEDED,
                "success", null);
    }

    @Override
    public void cancelDagRun(String composerEnvironment, String dagId, String dagRunId,
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
