package com.pulse.command.draft;

import java.util.LinkedHashMap;
import java.util.Map;

public record DraftRefDeclaration(
        String draftRef,
        String aggregateType,
        int declaredByCommandIndex,
        String description) {

    @SuppressWarnings("unchecked")
    public static DraftRefDeclaration fromMap(Map<String, Object> source) {
        if (source == null) {
            throw new DraftRefException(
                    PlanDraftRefErrorCodes.PLAN_DRAFT_REF_INVALID,
                    "Draft ref declaration row is missing");
        }
        Object rawDraftRef = source.get("draftRef");
        Object rawAggregateType = source.get("aggregateType");
        Object rawIndex = source.get("declaredByCommandIndex");
        if (!(rawDraftRef instanceof String draftRef) || !(rawAggregateType instanceof String aggregateType)) {
            throw new DraftRefException(
                    PlanDraftRefErrorCodes.PLAN_DRAFT_REF_INVALID,
                    "Draft ref declaration must contain draftRef and aggregateType");
        }
        int declaredByCommandIndex = asInt(rawIndex, "declaredByCommandIndex");
        String description = source.get("description") instanceof String text ? text : null;
        return new DraftRefDeclaration(draftRef, aggregateType, declaredByCommandIndex, description);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("draftRef", draftRef);
        data.put("aggregateType", aggregateType);
        data.put("declaredByCommandIndex", declaredByCommandIndex);
        if (description != null && !description.isBlank()) {
            data.put("description", description);
        }
        return data;
    }

    private static int asInt(Object value, String field) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        throw new DraftRefException(
                PlanDraftRefErrorCodes.PLAN_DRAFT_REF_INVALID,
                "Draft ref declaration field '" + field + "' must be numeric");
    }
}
