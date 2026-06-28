package com.pulse.command.draft;

import com.pulse.command.service.PlanService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DraftRefSubstitutor {

    public PlanService.PlannedCommand substitute(PlanService.PlannedCommand command,
                                                 Map<String, DraftRefBinding> bindings,
                                                 boolean allowUnboundAggregateId) {
        String aggregateId = substituteScalar(command.aggregateId(), bindings, allowUnboundAggregateId);
        Map<String, Object> payload = substituteMap(command.payload(), bindings, false);
        Map<String, Object> uiIntent = substituteMap(command.uiIntent(), bindings, true);
        return command.withResolvedValues(aggregateId, payload, uiIntent);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> substituteMap(Map<String, Object> source,
                                             Map<String, DraftRefBinding> bindings,
                                             boolean allowUnbound) {
        if (source == null) {
            return Map.of();
        }
        return (Map<String, Object>) substituteValue(source, bindings, allowUnbound);
    }

    @SuppressWarnings("unchecked")
    private Object substituteValue(Object value,
                                   Map<String, DraftRefBinding> bindings,
                                   boolean allowUnbound) {
        if (value instanceof Map<?, ?> mapValue) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (var entry : mapValue.entrySet()) {
                if (entry.getKey() instanceof String key) {
                    copy.put(key, substituteValue(entry.getValue(), bindings, allowUnbound));
                }
            }
            return copy;
        }
        if (value instanceof List<?> listValue) {
            List<Object> copy = new ArrayList<>(listValue.size());
            for (Object item : listValue) {
                copy.add(substituteValue(item, bindings, allowUnbound));
            }
            return copy;
        }
        if (value instanceof String stringValue) {
            return substituteScalar(stringValue, bindings, allowUnbound);
        }
        return value;
    }

    public String substituteScalar(String value,
                                   Map<String, DraftRefBinding> bindings,
                                   boolean allowUnbound) {
        var parsed = DraftRef.tryParse(value);
        if (parsed.isEmpty()) {
            return value;
        }
        DraftRefBinding binding = bindings.get(parsed.get().raw());
        if (binding != null) {
            return binding.realId();
        }
        if (allowUnbound) {
            return value;
        }
        throw new DraftRefException(
                PlanDraftRefErrorCodes.PLAN_DRAFT_REF_UNBOUND,
                "Draft ref '" + value + "' is not bound yet");
    }
}
