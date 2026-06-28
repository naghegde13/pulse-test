package com.pulse.broker.runtime;

import com.pulse.command.model.CommandLog;
import com.pulse.command.service.CommandService;
import com.pulse.pipeline.model.Pipeline;
import com.pulse.pipeline.model.SubPipelineInstance;
import com.pulse.pipeline.repository.PipelineRepository;
import com.pulse.pipeline.service.CompositionService;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class BrokerInvocationCommandHandlers {

    public static final String CONFIGURE_REMOTE_INVOCATION = "broker.remoteInvocation.configure";

    private final CommandService commandService;
    private final CompositionService compositionService;
    private final PipelineRepository pipelineRepository;

    public BrokerInvocationCommandHandlers(CommandService commandService,
                                           CompositionService compositionService,
                                           PipelineRepository pipelineRepository) {
        this.commandService = commandService;
        this.compositionService = compositionService;
        this.pipelineRepository = pipelineRepository;
    }

    @PostConstruct
    void register() {
        commandService.registerHandler(CONFIGURE_REMOTE_INVOCATION, this::configureRemoteInvocation);
    }

    @SuppressWarnings("unchecked")
    private Object configureRemoteInvocation(CommandLog cmd) {
        Map<String, Object> payload = cmd.getPayload() == null ? Map.of() : cmd.getPayload();
        String pipelineId = required((String) payload.get("pipelineId"), "pipelineId");
        Pipeline pipeline = pipelineRepository.findById(pipelineId)
                .orElseThrow(() -> new IllegalArgumentException("Pipeline not found: " + pipelineId));
        String versionId = stringOr((String) payload.get("versionId"), pipeline.getActiveVersionId());
        String instanceId = (String) payload.get("instanceId");
        String instanceName = stringOr((String) payload.get("instanceName"), "Invoke remote pipeline");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("federated_tenant_key", required((String) payload.get("federatedTenantKey"), "federatedTenantKey"));
        params.put("remote_target_ref", required((String) payload.get("remoteTargetRef"), "remoteTargetRef"));
        params.put("environment", required((String) payload.get("environment"), "environment"));
        params.put("airflow_connection_id", required((String) payload.get("airflowConnectionId"), "airflowConnectionId"));
        putIfPresent(params, "remote_dag_id", payload.get("remoteDagId"));
        putIfPresent(params, "poll_interval_seconds", payload.get("pollIntervalSeconds"));
        putIfPresent(params, "timeout_seconds", payload.get("timeoutSeconds"));
        if (payload.get("payloadTemplate") instanceof Map<?, ?> template) {
            params.put("payload_template", new LinkedHashMap<>((Map<String, Object>) template));
        }
        SubPipelineInstance instance;
        if (instanceId == null || instanceId.isBlank()) {
            instance = compositionService.addInstance(
                    pipelineId, versionId, "RemotePipelineInvocation", instanceName, params);
        } else {
            instance = compositionService.updateInstanceParams(versionId, instanceId, params);
        }
        return Map.of(
                "pipelineId", pipelineId,
                "versionId", versionId,
                "instanceId", instance.getId(),
                "blueprintKey", instance.getBlueprintKey());
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static String stringOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value == null) return;
        if (value instanceof String string && string.isBlank()) return;
        target.put(key, value);
    }
}
