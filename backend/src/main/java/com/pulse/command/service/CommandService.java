package com.pulse.command.service;

import com.pulse.command.model.CommandLog;
import com.pulse.command.model.CommandStatus;
import com.pulse.command.repository.CommandLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

@Service
public class CommandService {

    private final CommandLogRepository commandLogRepository;
    private final Map<String, Function<CommandLog, Object>> handlers = new LinkedHashMap<>();

    public CommandService(CommandLogRepository commandLogRepository) {
        this.commandLogRepository = commandLogRepository;
    }

    public void registerHandler(String commandType, Function<CommandLog, Object> handler) {
        handlers.put(commandType, handler);
    }

    @Transactional
    public CommandLog execute(String commandType, String aggregateType, String aggregateId,
                              String tenantId, String actorId, String planId,
                              Map<String, Object> payload) {
        String idempotencyKey = tenantId + ":" + commandType + ":" + aggregateId + ":" + UUID.randomUUID();

        // Check idempotency
        var existing = commandLogRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        CommandLog cmd = new CommandLog();
        cmd.setPlanId(planId);
        cmd.setCommandType(commandType);
        cmd.setAggregateType(aggregateType);
        cmd.setAggregateId(aggregateId);
        cmd.setTenantId(tenantId);
        cmd.setActorId(actorId);
        cmd.setIdempotencyKey(idempotencyKey);
        cmd.setPayload(payload);
        cmd.setStatus(CommandStatus.PENDING);
        cmd = commandLogRepository.save(cmd);

        Function<CommandLog, Object> handler = handlers.get(commandType);
        if (handler == null) {
            cmd.setStatus(CommandStatus.FAILED);
            cmd.setErrorMessage("No handler registered for command type: " + commandType);
            cmd.setExecutedAt(Instant.now());
            return commandLogRepository.save(cmd);
        }

        cmd.setStatus(CommandStatus.EXECUTING);
        commandLogRepository.save(cmd);

        try {
            Object result = handler.apply(cmd);
            cmd.setStatus(CommandStatus.SUCCEEDED);
            cmd.setExecutedAt(Instant.now());
            if (result != null) {
                cmd.setResultPayload(structuredResultPayload(result));
                cmd.setPayload(mergeResult(cmd.getPayload(), result));
            }
        } catch (Exception e) {
            cmd.setStatus(CommandStatus.FAILED);
            cmd.setErrorMessage(e.getMessage());
            cmd.setExecutedAt(Instant.now());
        }

        return commandLogRepository.save(cmd);
    }

    public List<CommandLog> listByTenant(String tenantId) {
        return commandLogRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    public List<CommandLog> listByAggregate(String aggregateId) {
        return commandLogRepository.findByAggregateIdOrderByCreatedAtDesc(aggregateId);
    }

    public List<CommandLog> listByPlan(String planId) {
        return commandLogRepository.findByPlanIdOrderByCreatedAtAsc(planId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mergeResult(Map<String, Object> payload, Object result) {
        Map<String, Object> merged = new LinkedHashMap<>(payload != null ? payload : Map.of());
        if (result instanceof Map) {
            merged.put("_result", result);
        } else {
            merged.put("_resultType", result.getClass().getSimpleName());
        }
        return merged;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> structuredResultPayload(Object result) {
        if (result instanceof Map<?, ?> mapResult) {
            Map<String, Object> payload = new LinkedHashMap<>();
            for (var entry : mapResult.entrySet()) {
                if (entry.getKey() instanceof String key) {
                    payload.put(key, entry.getValue());
                }
            }
            return payload;
        }
        return null;
    }
}
