package com.pulse.command.draft;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public record DraftRefBinding(
        String draftRef,
        String aggregateType,
        String realId,
        int boundByCommandIndex,
        String boundByCommandId,
        Instant boundAt) {

    public static DraftRefBinding fromMap(Map<String, Object> source) {
        if (source == null) {
            throw new DraftRefException(
                    PlanDraftRefErrorCodes.PLAN_DRAFT_REF_INVALID,
                    "Draft ref binding row is missing");
        }
        Object rawDraftRef = source.get("draftRef");
        Object rawAggregateType = source.get("aggregateType");
        Object rawRealId = source.get("realId");
        Object rawIndex = source.get("boundByCommandIndex");
        Object rawCommandId = source.get("boundByCommandId");
        Object rawBoundAt = source.get("boundAt");
        if (!(rawDraftRef instanceof String draftRef)
                || !(rawAggregateType instanceof String aggregateType)
                || !(rawRealId instanceof String realId)
                || !(rawCommandId instanceof String boundByCommandId)
                || !(rawBoundAt instanceof String boundAtText)) {
            throw new DraftRefException(
                    PlanDraftRefErrorCodes.PLAN_DRAFT_REF_INVALID,
                    "Draft ref binding must contain draftRef, aggregateType, realId, boundByCommandId, and boundAt");
        }
        return new DraftRefBinding(
                draftRef,
                aggregateType,
                realId,
                asInt(rawIndex, "boundByCommandIndex"),
                boundByCommandId,
                Instant.parse(boundAtText));
    }

    public Map<String, Object> toMap() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("draftRef", draftRef);
        data.put("aggregateType", aggregateType);
        data.put("realId", realId);
        data.put("boundByCommandIndex", boundByCommandIndex);
        data.put("boundByCommandId", boundByCommandId);
        data.put("boundAt", boundAt.toString());
        return data;
    }

    private static int asInt(Object value, String field) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        throw new DraftRefException(
                PlanDraftRefErrorCodes.PLAN_DRAFT_REF_INVALID,
                "Draft ref binding field '" + field + "' must be numeric");
    }
}
